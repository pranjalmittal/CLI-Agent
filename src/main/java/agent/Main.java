package agent;

import agent.config.AgentConfig;
import agent.model.AgentState;
import agent.pipeline.AgentPipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * CLI entry point for WinCliAgent.
 *
 * Usage:
 *     java -cp target/classes agent.Main "list files in the current directory"
 *     java -cp target/classes agent.Main                # interactive REPL
 *     java -cp target/classes agent.Main --auto "..."   # skip confirmation prompt
 */
public class Main {

    private static final String VERSION = "1.0.0";

    public static void main(String[] args) {
        boolean auto = false;
        List<String> promptParts = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--auto".equals(arg) || "-y".equals(arg)) {
                auto = true;
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                printHelp();
                System.exit(0);
            } else if ("--version".equals(arg) || "-v".equals(arg)) {
                System.out.println("WinCliAgent version " + VERSION);
                System.exit(0);
            } else {
                promptParts.add(arg);
            }
        }

        AgentConfig config = AgentConfig.load();
        AgentPipeline pipeline = new AgentPipeline(config);

        if (!promptParts.isEmpty()) {
            String prompt = String.join(" ", promptParts);
            int exitCode = runOnce(pipeline, prompt, auto, new Scanner(System.in));
            System.exit(exitCode);
        } else {
            interactiveRepl(pipeline, auto);
        }
    }

    private static int runOnce(AgentPipeline pipeline, String prompt, boolean auto, Scanner scanner) {
        AgentState state = pipeline.prepare(prompt);

        if (state.getError() != null) {
            System.err.println("\n[ERROR] " + state.getError() + "\n");
            return 1;
        }

        String command = state.getCommand();
        if (command == null || command.isBlank()) {
            System.out.println("Could not generate a command for that request.\n");
            return 1;
        }

        System.out.println("\n  command : " + command);
        if (state.getExplanation() != null && !state.getExplanation().isBlank()) {
            System.out.println("  details : " + state.getExplanation());
        }

        if (!state.isSafe()) {
            System.out.println("  [BLOCKED] " + state.getSafetyReason());
        }

        if (!auto) {
            System.out.print("\nRun this command? [y/N] ");
            String answer = "";
            if (scanner.hasNextLine()) {
                answer = scanner.nextLine().trim().toLowerCase();
            }
            if (!answer.equals("y") && !answer.equals("yes")) {
                System.out.println("Skipped.\n");
                return 0;
            }
        }

        pipeline.execute(state);
        printOutput(state);

        return state.getExitCode() != null ? state.getExitCode() : (state.isSafe() ? 0 : 1);
    }

    private static void interactiveRepl(AgentPipeline pipeline, boolean auto) {
        System.out.println("WinCliAgent (Java Edition) — type a request, or 'exit' to quit.");
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\n> ");
            if (!scanner.hasNextLine()) {
                System.out.println();
                break;
            }

            String prompt = scanner.nextLine().trim();
            if (prompt.equalsIgnoreCase("exit") || prompt.equalsIgnoreCase("quit") || prompt.equalsIgnoreCase("q")) {
                break;
            }
            if (prompt.isEmpty()) {
                continue;
            }

            runOnce(pipeline, prompt, auto, scanner);
        }
    }

    private static void printOutput(AgentState state) {
        if (state.isExecuted()) {
            System.out.println("  exit    : " + state.getExitCode());
            String stdout = state.getStdout();
            String stderr = state.getStderr();
            if (stdout != null && !stdout.isBlank()) {
                System.out.println("\n--- output ---");
                System.out.print(stdout.stripTrailing());
                System.out.println();
            }
            if (stderr != null && !stderr.isBlank()) {
                System.out.println("\n--- errors ---");
                System.out.print(stderr.stripTrailing());
                System.out.println();
            }
        } else if (!state.isSafe()) {
            System.out.println("\n--- errors ---");
            System.out.println(state.getStderr());
        }
        System.out.println();
    }

    private static void printHelp() {
        System.out.println("WinCliAgent - Natural language to Windows CLI agent (Java Edition)");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -cp target/classes agent.Main [options] [prompt...]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --auto, -y       Execute the command without asking for confirmation");
        System.out.println("  --help, -h       Display this help message");
        System.out.println("  --version, -v    Display version information");
        System.out.println();
        System.out.println("Environment Variables:");
        System.out.println("  OPENAI_API_KEY           OpenAI API key (required for LLM generation)");
        System.out.println("  OPENAI_MODEL             OpenAI model name (default: gpt-4o-mini)");
        System.out.println("  COMMAND_TIMEOUT_SECONDS  Timeout in seconds for execution (default: 60)");
    }
}
