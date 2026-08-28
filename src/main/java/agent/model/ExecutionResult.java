package agent.model;

/**
 * Result of executing a shell command.
 *
 * @param stdout   standard output produced by the process
 * @param stderr   standard error produced by the process
 * @param exitCode process exit code (or custom code on failure/timeout)
 */
public record ExecutionResult(String stdout, String stderr, int exitCode) {
    public boolean isSuccess() {
        return exitCode == 0;
    }
}
