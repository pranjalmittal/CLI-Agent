package agent.pipeline;

import agent.config.AgentConfig;
import agent.executor.CommandExecutor;
import agent.llm.OpenAiClient;
import agent.model.AgentState;
import agent.model.ExecutionResult;
import agent.safety.SafetyFilter;

/**
 * Orchestrates the agent workflow:
 * prompt -> [generate] -> [safety] -> (execute | blocked) -> output
 */
public class AgentPipeline {

    private final OpenAiClient llmClient;
    private final SafetyFilter safetyFilter;
    private final CommandExecutor executor;

    public AgentPipeline(AgentConfig config) {
        this.llmClient = new OpenAiClient(config);
        this.safetyFilter = new SafetyFilter();
        this.executor = new CommandExecutor(config.getTimeoutSeconds());
    }

    public AgentPipeline(OpenAiClient llmClient, SafetyFilter safetyFilter, CommandExecutor executor) {
        this.llmClient = llmClient;
        this.safetyFilter = safetyFilter;
        this.executor = executor;
    }

    /**
     * First leg: generates command from prompt and screens for safety.
     * Returns the state before execution so confirmation can be requested.
     */
    public AgentState prepare(String prompt) {
        AgentState state = new AgentState(prompt);

        try {
            // Node 1: Generate command
            OpenAiClient.GeneratedCommand generated = llmClient.generateCommand(prompt);
            state.setCommand(generated.command());
            state.setExplanation(generated.explanation());

            if (state.getCommand().isBlank()) {
                state.setError("Could not generate a command for that request.");
                return state;
            }

            // Node 2: Safety screen
            SafetyFilter.Result safetyResult = safetyFilter.screen(state.getCommand());
            state.setSafe(safetyResult.isSafe());
            state.setSafetyReason(safetyResult.reason());

        } catch (Exception ex) {
            state.setError("Failed during generation: " + ex.getMessage());
        }

        return state;
    }

    /**
     * Second leg: executes the prepared command if safe, or blocks it.
     */
    public AgentState execute(AgentState state) {
        if (!state.isSafe()) {
            state.setExecuted(false);
            state.setStdout("");
            state.setStderr("Execution blocked by safety screen: " + state.getSafetyReason());
            state.setExitCode(null);
            return state;
        }

        if (state.getCommand().isBlank()) {
            state.setExecuted(false);
            state.setStderr("No command to execute.");
            return state;
        }

        ExecutionResult result = executor.execute(state.getCommand());
        state.setExecuted(true);
        state.setStdout(result.stdout());
        state.setStderr(result.stderr());
        state.setExitCode(result.exitCode());

        return state;
    }

    /**
     * Executes the full pipeline end-to-end automatically.
     */
    public AgentState run(String prompt) {
        AgentState state = prepare(prompt);
        if (state.getError() != null) {
            return state;
        }
        return execute(state);
    }
}
