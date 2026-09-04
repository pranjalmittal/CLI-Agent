package agent.safety;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Screens generated commands for dangerous or destructive operations.
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

        // Disk & system integrity
        addRule(r("\\bdiskpart\\b"), "Disk partitioning tool detected");
        addRule(r("\\bcipher\\b\\s+/w"), "Drive free space wipe detected");
        addRule(r("\\bvssadmin\\b\\s+delete"), "Shadow copy deletion detected");
        addRule(r("\\bbcdedit\\b"), "Boot configuration modification detected");

        // Power operations
        addRule(r("\\bshutdown\\b"), "System shutdown command detected");
        addRule(r("\\bRestart-Computer\\b"), "PowerShell computer restart detected");
        addRule(r("\\bStop-Computer\\b"), "PowerShell computer stop detected");

        // Registry & security bypass
        addRule(r("\\breg\\b\\s+delete"), "Registry deletion command detected");
        addRule(r("\\bSet-ExecutionPolicy\\b.*(Bypass|Unrestricted)"), "PowerShell execution policy bypass detected");
        addRule(r("\\b(Invoke-Expression|iex)\\b"), "Dynamic script evaluation (Invoke-Expression/iex) detected");
    }

    private static Pattern r(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    private void addRule(Pattern pattern, String description) {
        rules.add(new Rule(pattern, description));
    }

    /**
     * Evaluates whether the given command is safe to execute.
     *
     * @param command the command line to screen
     * @return Result containing whether safe and the rejection reason if unsafe
     */
    public Result screen(String command) {
        if (command == null || command.isBlank()) {
            return Result.safe();
        }

        String trimmed = command.trim();
        for (Rule rule : rules) {
            if (rule.pattern().matcher(trimmed).find()) {
                return Result.unsafe("Potentially destructive command blocked: " + rule.description()
                        + " (matched pattern: " + rule.pattern().pattern() + ")");
            }
        }

        return Result.safe();
    }
}
