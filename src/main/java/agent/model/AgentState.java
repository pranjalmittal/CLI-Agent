package agent.model;

/**
 * State object carried through the agent pipeline.
 */
public class AgentState {
    private final String prompt;
    private String command = "";
    private String explanation = "";
    private boolean isSafe = true;
    private String safetyReason = "";
    private String stdout = "";
    private String stderr = "";
    private Integer exitCode = null;
    private boolean executed = false;
    private String error = null;

    public AgentState(String prompt) {
        this.prompt = prompt != null ? prompt : "";
    }

    public String getPrompt() {
        return prompt;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command != null ? command : "";
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation != null ? explanation : "";
    }

    public boolean isSafe() {
        return isSafe;
    }

    public void setSafe(boolean safe) {
        isSafe = safe;
    }

    public String getSafetyReason() {
        return safetyReason;
    }

    public void setSafetyReason(String safetyReason) {
        this.safetyReason = safetyReason != null ? safetyReason : "";
    }

    public String getStdout() {
        return stdout;
    }

    public void setStdout(String stdout) {
        this.stdout = stdout != null ? stdout : "";
    }

    public String getStderr() {
        return stderr;
    }

    public void setStderr(String stderr) {
        this.stderr = stderr != null ? stderr : "";
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public boolean isExecuted() {
        return executed;
    }

    public void setExecuted(boolean executed) {
        this.executed = executed;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    @Override
    public String toString() {
        return "AgentState{" +
                "prompt='" + prompt + '\'' +
                ", command='" + command + '\'' +
                ", isSafe=" + isSafe +
                ", executed=" + executed +
                ", exitCode=" + exitCode +
                '}';
    }
}
