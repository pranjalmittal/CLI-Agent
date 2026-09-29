package agent.safety;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Screens generated commands for dangerous or destructive operations.
 *
 * <p>Screening runs against every expansion produced by {@link CommandNormalizer}, not just the
 * raw string, so nested shells, encoded scripts, script blocks, and chained segments are all
 * covered. The filter fails closed: anything it cannot read is treated as unsafe.
 */
public class SafetyFilter {

    public record Result(boolean isSafe, String reason) {
        public static Result safe() {
            return new Result(true, "");
        }

        public static Result unsafe(String reason) {
            return new Result(false, reason);
        }
    }

    private record Rule(Pattern pattern, String description) {}

    private final List<Rule> rules;

    public SafetyFilter() {
        this.rules = new ArrayList<>();
        initDefaultRules();
    }

    private void initDefaultRules() {
        // Filesystem destruction
        addRule(r("\\bformat\\b"), "Disk format command detected");
        addRule(r("\\bdel\\b.*[\\\\/]\\*"), "Mass file deletion pattern detected");
        addRule(r("\\bdel\\b\\s+/[sqf]"), "Forced or recursive file deletion detected");
        addRule(r("\\brmdir\\b\\s+/s"), "Recursive directory removal detected");
        addRule(r("\\brd\\b\\s+/s"), "Recursive directory removal detected");
        addRule(r("\\bRemove-Item\\b.*-Recurse"), "PowerShell recursive removal detected");
        addRule(r("\\bRemove-Item\\b.*[A-Za-z]:\\\\\\*"), "Drive root removal detected");
        addRule(r(":\\\\\\s*$"), "Direct drive root operation detected");
        addRule(r("\\bcd\\b.*&&.*\\bdel\\b"), "Chained directory change with deletion detected");
        addRule(r("\\bClear-Content\\b.*-Recurse"), "Recursive content wipe detected");
        addRule(r("\\bDism\\b.*\\/ResetBase"), "Component store reset detected");
        addRule(r("\\bFormat-Volume\\b"), "Volume format detected");
        addRule(r("\\bClear-Disk\\b"), "Disk wipe detected");

        // Disk & system integrity
        addRule(r("\\bdiskpart\\b"), "Disk partitioning tool detected");
        addRule(r("\\bcipher\\b\\s*/w"), "Drive free space wipe detected");
        addRule(r("\\bvssadmin\\b\\s+delete"), "Shadow copy deletion detected");
        addRule(r("\\bbcdedit\\b"), "Boot configuration modification detected");
        addRule(r("\\bbootrec\\b"), "Boot record modification detected");
        addRule(r("\\bInitialize-Disk\\b"), "Disk initialization detected");
        addRule(r("\\bRepair-Volume\\b"), "Volume repair detected");

        // Power operations
        addRule(r("\\bshutdown\\b"), "System shutdown command detected");
        addRule(r("\\bRestart-Computer\\b"), "PowerShell computer restart detected");
        addRule(r("\\bStop-Computer\\b"), "PowerShell computer stop detected");
        addRule(r("\\bSet-ItemProperty\\b.*\\\\Run\\b"), "Persistence autorun entry modification detected");
        addRule(r("\\bNew-ItemProperty\\b.*\\\\Run\\b"), "Persistence autorun entry creation detected");
        addRule(r("\\breg\\b\\s+add\\b.*\\\\Run\\b"), "Persistence autorun registry entry detected");

        // Registry & security bypass
        addRule(r("\\breg\\b\\s+delete"), "Registry deletion command detected");
        addRule(r("\\bSet-ExecutionPolicy\\b.*(Bypass|Unrestricted)"), "PowerShell execution policy bypass detected");
        addRule(r("\\b(Invoke-Expression|iex|Invoke-Command)\\b"), "Dynamic script evaluation (Invoke-Expression/iex) detected");
        addRule(r("\\bUnblock-File\\b"), "Marking downloaded files as trusted detected");
        addRule(r("\\bAdd-MpPreference\\b.*-Exclusion"), "Antivirus exclusion configured");
        addRule(r("\\bSet-MpPreference\\b.*-Disable"), "Antivirus protection disabled");
        addRule(r("\\bnetsh\\b\\s+advfirewall\\s+set\\b.*disable"), "Firewall rule disabled");
        addRule(r("\\bnet\\b\\s+user\\b.*/add"), "Local user account creation detected");
        addRule(r("\\bnet\\b\\s+localgroup\\b.*/add"), "Local group membership change detected");

        // Remote execution & download-and-run
        addRule(r("\\b(Invoke-WebRequest|Invoke-RestMethod|iwr|irm|curl|wget|Start-BitsTransfer)\\b"
                        + ".*\\|\\s*(iex|Invoke-Expression|powershell|pwsh|cmd\\.exe)"),
                "Remote script downloaded and piped into an interpreter");
        addRule(r("\\b(Invoke-Expression|iex)\\b"), "Dynamic script evaluation detected");
        addRule(r("\\bmshta\\b"), "HTML Application Host script execution detected");
        addRule(r("\\bcertutil\\b.*-urlcache"), "certutil remote payload download detected");
        addRule(r("\\b(regsvr32|regsvr)\\b.*\\bscriptlet\\b"), "Scriptlet execution via regsvr32 detected");
    }

    private static Pattern r(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    private void addRule(Pattern pattern, String description) {
        rules.add(new Rule(pattern, description));
    }

    /**
     * Returns every active rule description, for diagnostics and documentation.
     */
    public List<String> describeRules() {
        List<String> descriptions = new ArrayList<>();
        for (Rule rule : rules) {
            descriptions.add(rule.description());
        }
        return List.copyOf(descriptions);
    }

    /**
     * Evaluates whether the given command is safe to execute.
     *
     * <p>The command is expanded by {@link CommandNormalizer} first, so nested shells,
     * encoded scripts, and chained segments are screened individually. If any expansion
     * matches a rule, or an encoded script cannot be decoded, the command is blocked.
     *
     * @param command the command line to screen
     * @return Result containing whether safe and the rejection reason if unsafe
     */
    public Result screen(String command) {
        if (command == null || command.isBlank()) {
            return Result.safe();
        }

        CommandNormalizer.Expansion expansion = CommandNormalizer.expand(command);

        if (expansion.opaquePayload()) {
            return Result.unsafe("Potentially destructive command blocked: encoded script payload "
                    + "could not be decoded and screened (fail-closed policy).");
        }

        for (String candidate : expansion.candidates()) {
            Result result = screenCandidate(candidate);
            if (!result.isSafe()) {
                return result;
            }
        }

        return Result.safe();
    }

    private Result screenCandidate(String candidate) {
        String trimmed = candidate.trim();
        for (Rule rule : rules) {
            if (rule.pattern().matcher(trimmed).find()) {
                return Result.unsafe("Potentially destructive command blocked: " + rule.description()
                        + " (matched pattern: " + rule.pattern().pattern() + ")");
            }
        }
        return Result.safe();
    }
}
