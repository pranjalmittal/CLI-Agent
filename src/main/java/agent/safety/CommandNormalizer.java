package agent.safety;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands a command line into every string that must be screened before execution.
 *
 * <p>A naive regex screen over the raw string is trivially bypassed:
 * <pre>
 *   pwsh -EncodedCommand &lt;base64&gt;   // payload is invisible to the screen
 *   &amp; { format }                      // nested script block
 *   fo`rmat                             // PowerShell backtick escape
 *   cmd /c format C:                    // nested shell
 *   "fo"+"rmat"                         // literal concatenation
 *   Get-ChildItem | Remove-Item -Recurse // chained segment
 * </pre>
 *
 * This class de-obfuscates and decomposes such commands so {@link SafetyFilter}
 * can screen every piece rather than just the outermost text.
 */
public final class CommandNormalizer {

    /** Depth cap for nested shells / encoded payloads (each shell wrapper adds one level). */
    private static final int MAX_DEPTH = 8;

    /** Total cap on produced candidates, guarding against pathological expansion. */
    private static final int MAX_CANDIDATES = 256;

    /**
     * PowerShell encoded-script flags: -EncodedCommand and its documented prefixes
     * (-e, -ec, -en, -enc, -enco, -encod, -encode). Group 1 is the flag, which decides
     * whether it is specific enough to act on without PowerShell context (see below).
     */
    private static final Pattern ENCODED_FLAG = Pattern.compile(
            "(?<![A-Za-z0-9])-(e|ec|en|enc|enco|encod|encode|encoded|encodedcommand)(?![A-Za-z0-9])",
            Pattern.CASE_INSENSITIVE);

    /**
     * Flags beginning "enc" are specific enough to act on without PowerShell context. The short
     * forms (-e, -ec, -en) collide with ordinary tools such as {@code curl -e <user-agent>},
     * so they only count as an encoded script when PowerShell is actually invoked.
     */
    private static boolean isUnambiguousEncodedFlag(String flag) {
        return flag.regionMatches(true, 0, "enc", 0, 3);
    }

    private static final Pattern POWERSHELL_INVOCATION = Pattern.compile(
            "(?<![A-Za-z0-9])(?:powershell(?:\\.exe)?|pwsh(?:\\.exe)?)(?![A-Za-z0-9])",
            Pattern.CASE_INSENSITIVE);

    /**
     * A shell invocation wrapping another command: {@code cmd /c X}, {@code cmd.exe /k X},
     * {@code powershell -NoProfile -Command X}, {@code pwsh -ec X}. Group 1 is the wrapped text.
     */
    private static final Pattern SHELL_WRAPPER = Pattern.compile(
            "^(?:cmd(?:\\.exe)?|powershell(?:\\.exe)?|pwsh(?:\\.exe)?)\\s+(?:-|/)\\w+(\\s+.*)?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * An encoded-script flag together with its Base64 payload. Tolerates quoting and comma
     * splitting from Start-Process argument lists. Group 1 is the flag, group 2 the blob.
     */
    private static final Pattern ENCODED_PAYLOAD = Pattern.compile(
            "(?<![A-Za-z0-9])-(e|ec|en|enc|enco|encod|encode|encoded|encodedcommand)(?![A-Za-z0-9])"
                    + "[\\s'\"=,:]*([A-Za-z0-9+/]{8,}={0,2})",
            Pattern.CASE_INSENSITIVE);

    /**
     * Result of expanding a command line.
     *
     * @param candidates     every string that should be run through the ruleset
     * @param opaquePayload  true when an encoded-script flag was present but the payload
     *                       could not be decoded and screened (fail closed)
     */
    public record Expansion(List<String> candidates, boolean opaquePayload) {}

    private CommandNormalizer() {}

    public static Expansion expand(String command) {
        Set<String> candidates = new LinkedHashSet<>();
        boolean opaque = false;

        if (command == null || command.isBlank()) {
            return new Expansion(List.of(), false);
        }

        Deque<String> queue = new ArrayDeque<>();
        queue.add(command);
        int depth = 0;

        while (!queue.isEmpty() && depth++ < MAX_DEPTH && candidates.size() < MAX_CANDIDATES) {
            String current = queue.poll();
            if (current == null || current.isBlank()) {
                continue;
            }

            candidates.add(current);

            // PowerShell backtick escapes are pure noise to the screen: fo`rmat == format.
            String stripped = stripEscapes(current);
            if (!stripped.equals(current)) {
                candidates.add(stripped);
            }

            Optional<String> decoded = decodeEncodedPayload(stripped);
            if (decoded.isPresent()) {
                candidates.add(decoded.get());
                queue.add(decoded.get());
            } else if (hasEncodedScriptFlag(stripped)) {
                // Encoded script we cannot read is exactly the case the screen exists for.
                opaque = true;
            }

            for (String unwrapped : unwrapShells(stripped)) {
                candidates.add(unwrapped);
                queue.add(unwrapped);
            }

            for (String segment : split(stripped)) {
                candidates.add(segment);
                resolveCommandName(segment).ifPresent(candidates::add);
            }
        }

        return new Expansion(List.copyOf(candidates), opaque);
    }

    /**
     * Removes PowerShell backtick escapes and collapses runs of whitespace.
     */
    static String stripEscapes(String command) {
        return command.replace("`", "").replaceAll("\\s+", " ").trim();
    }

    /**
     * True when the command line passes an encoded-script flag to PowerShell, or uses an
     * unambiguous long-form flag such as {@code -EncodedCommand} / {@code -enc}.
     */
    public static boolean hasEncodedScriptFlag(String command) {
        Matcher matcher = ENCODED_FLAG.matcher(command);
        if (!matcher.find()) {
            return false;
        }
        boolean powershell = POWERSHELL_INVOCATION.matcher(command).find();
        if (!powershell) {
            // Keep scanning: the flag may belong to a different shell than the first match.
            return isUnambiguousEncodedFlag(matcher.group(1));
        }
        return true;
    }

    /**
     * Decodes the first Base64 script payload found in the command line.
     *
     * <p>PowerShell writes these as UTF-16LE, but cmd tooling and copy-paste routinely
     * produce UTF-8, so both encodings are accepted. The payload is rejected when the
     * decoded bytes are not plausible text, which keeps the caller failing closed.
     */
    public static Optional<String> decodeEncodedPayload(String command) {
        boolean powershell = POWERSHELL_INVOCATION.matcher(command).find();
        Matcher matcher = ENCODED_PAYLOAD.matcher(command);

        while (matcher.find()) {
            if (!powershell && !isUnambiguousEncodedFlag(matcher.group(1))) {
                // -e style flag on a non-PowerShell tool: not an encoded script payload.
                continue;
            }

            Optional<String> decoded = tryDecodeBase64(matcher.group(2));
            if (decoded.isPresent()) {
                return decoded;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> tryDecodeBase64(String blob) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(blob);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        if (bytes.length == 0) {
            return Optional.empty();
        }

        // UTF-16LE ASCII text has a NUL in every odd byte; anything else we read as UTF-8.
        boolean utf16 = bytes.length >= 2;
        for (int i = 1; utf16 && i < bytes.length; i += 2) {
            utf16 = bytes[i] == 0;
        }

        String decoded = new String(bytes, utf16 ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8);
        return isMostlyPrintable(decoded) ? Optional.of(decoded.trim()) : Optional.empty();
    }

    /**
     * Rejects payloads that decoded into mostly unprintable data, which means we decoded
     * with the wrong charset or the blob is not a script at all.
     */
    private static boolean isMostlyPrintable(String text) {
        if (text.isBlank()) {
            return false;
        }
        int printable = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isISOControl(text.charAt(i))) {
                printable++;
            }
        }
        return printable * 100 / text.length() >= 90;
    }

    /**
     * Strips leading shell wrappers so the command they hide is screened too:
     * {@code cmd /c X}, {@code cmd.exe /k X}, {@code powershell -Command X},
     * {@code pwsh -NoProfile -Command X}, and PowerShell call operators such as {@code & X}.
     */
    static List<String> unwrapShells(String command) {
        List<String> unwrapped = new ArrayList<>();
        String current = command.trim();

        for (int i = 0; i < MAX_DEPTH; i++) {
            String next = unwrapOnce(current);
            if (next == null) {
                break;
            }
            unwrapped.add(next);
            current = next;
        }
        return unwrapped;
    }

    private static String unwrapOnce(String command) {
        String current = command.trim();

        // PowerShell call operator: & "C:\x.exe"  /  . "C:\x.exe"
        while (current.startsWith("&") || current.startsWith(".")) {
            current = current.substring(1).trim();
        }
        if (current.isEmpty()) {
            return null;
        }

        // Leading script block: { format C: }
        if (current.startsWith("{") && current.endsWith("}")) {
            return current.substring(1, current.length() - 1).trim();
        }

        Matcher wrapper = SHELL_WRAPPER.matcher(current);
        if (!wrapper.matches() || wrapper.group(1) == null) {
            return null;
        }

        return stripOuterQuotes(wrapper.group(1).trim());
    }

    private static String stripOuterQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    /**
     * Splits a command line into individual commands on shell separators.
     * Quote-aware so that {@code Write-Host "a; b"} stays in one piece.
     */
    public static List<String> split(String command) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }

            if (c == '"' || c == '\'') {
                quote = c;
                current.append(c);
                continue;
            }

            // Two-character separators first: && and ||
            if ((c == '&' || c == '|') && i + 1 < command.length() && command.charAt(i + 1) == c) {
                addSegment(segments, current);
                i++;
                continue;
            }

            if (c == ';' || c == '|' || c == '&' && i + 1 < command.length() && Character.isWhitespace(command.charAt(i + 1))
                    || c == '\n' || c == '\r') {
                addSegment(segments, current);
                continue;
            }

            current.append(c);
        }
        addSegment(segments, current);
        return segments;
    }

    private static void addSegment(List<String> segments, StringBuilder buffer) {
        String segment = buffer.toString().trim();
        buffer.setLength(0);
        if (!segment.isEmpty()) {
            segments.add(segment);
        }
    }

    /**
     * Extracts the invoked command name from a segment, folding literal concatenation so
     * {@code "fo"+"rmat"} screens as {@code format}. Returns empty for variable-driven
     * invocations, which cannot be resolved statically.
     */
    public static Optional<String> resolveCommandName(String segment) {
        String trimmed = segment.trim();
        while (trimmed.startsWith("&") || trimmed.startsWith(".")) {
            trimmed = trimmed.substring(1).trim();
        }
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        String token = readToken(trimmed).trim();
        token = unwrapParens(token).trim();
        if (token.isEmpty()) {
            return Optional.empty();
        }

        if (token.contains("+")) {
            StringBuilder folded = new StringBuilder();
            for (String part : token.split("\\+", -1)) {
                String literal = asLiteral(part.trim());
                if (literal == null) {
                    return Optional.empty();
                }
                folded.append(literal);
            }
            return folded.isEmpty() ? Optional.empty() : Optional.of(folded.toString());
        }

        String literal = asLiteral(token);
        return literal == null || literal.isBlank() ? Optional.empty() : Optional.of(literal);
    }

    /**
     * Strips balanced outer parentheses so {@code ("fo"+"rmat")} resolves like {@code "fo"+"rmat"}.
     */
    static String unwrapParens(String token) {
        String current = token.trim();
        while (current.length() > 1 && current.charAt(0) == '(' && current.charAt(current.length() - 1) == ')') {
            int depth = 0;
            boolean balanced = true;
            char quote = 0;
            for (int i = 0; i < current.length(); i++) {
                char c = current.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                    continue;
                }
                if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0 && i < current.length() - 1) {
                        balanced = false;
                        break;
                    }
                }
            }
            if (!balanced || depth != 0) {
                return current;
            }
            current = current.substring(1, current.length() - 1).trim();
        }
        return current;
    }

    private static String asLiteral(String token) {
        if (token.length() >= 2) {
            char first = token.charAt(0);
            char last = token.charAt(token.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return token.substring(1, token.length() - 1);
            }
        }
        // A bare word is only a command name; anything with metacharacters is an expression.
        return token.matches("[A-Za-z0-9_.\\\\:-]+") ? token : null;
    }

    private static String readToken(String segment) {
        StringBuilder token = new StringBuilder();
        char quote = 0;

        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (quote != 0) {
                token.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                token.append(c);
                continue;
            }
            if (Character.isWhitespace(c)) {
                break;
            }
            token.append(c);
        }
        return token.toString();
    }
}
