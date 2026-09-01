package agent.executor;

import agent.model.ExecutionResult;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

/**
 * Executes shell commands using PowerShell Core (pwsh) or native Windows shells.
 */
public class CommandExecutor {

    private final int defaultTimeoutSeconds;
    private final File workingDirectory;

    public CommandExecutor(int defaultTimeoutSeconds) {
        this(defaultTimeoutSeconds, new File("."));
    }

    public CommandExecutor(int defaultTimeoutSeconds, File workingDirectory) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds > 0 ? defaultTimeoutSeconds : 60;
        this.workingDirectory = workingDirectory != null ? workingDirectory : new File(".");
    }

    /**
     * Executes the command using the default timeout.
     */
    public ExecutionResult execute(String command) {
        return execute(command, this.defaultTimeoutSeconds);
    }

    /**
     * Executes the command with the specified timeout in seconds.
     */
    public ExecutionResult execute(String command, int timeoutSeconds) {
        List<String> shellCommand;
        try {
            shellCommand = resolveShell(command);
        } catch (IllegalStateException ex) {
            return new ExecutionResult("", ex.getMessage(), 127);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(shellCommand);
        processBuilder.directory(workingDirectory);

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException ex) {
            return new ExecutionResult("", "Failed to start process: " + ex.getMessage(), 127);
        }

        ExecutorService streamExecutor = Executors.newFixedThreadPool(2);
        Future<String> stdoutFuture = streamExecutor.submit(() -> readStream(process.getInputStream()));
        Future<String> stderrFuture = streamExecutor.submit(() -> readStream(process.getErrorStream()));

        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ExecutionResult("", "Command timed out after " + timeoutSeconds + " seconds.", 124);
            }

            int exitCode = process.exitValue();
            String stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(5, TimeUnit.SECONDS);

            return new ExecutionResult(stdout, stderr, exitCode);
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new ExecutionResult("", "Command execution was interrupted.", 130);
        } catch (ExecutionException | TimeoutException ex) {
            process.destroyForcibly();
            return new ExecutionResult("", "Error capturing output: " + ex.getMessage(), 1);
        } finally {
            streamExecutor.shutdownNow();
        }
    }

    /**
     * Resolves the appropriate shell invocation based on host OS.
     */
    public static List<String> resolveShell(String command) {
        boolean isWindows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");

        if (isWindows) {
            if (isCommandAvailable("pwsh")) {
                return List.of("pwsh", "-NoProfile", "-Command", command);
            }
            if (isCommandAvailable("powershell")) {
                return List.of("powershell", "-NoProfile", "-Command", command);
            }
            return List.of("cmd.exe", "/c", command);
        }

        // Unix / macOS / Docker: PowerShell Core (pwsh) provides Windows command emulation
        if (isCommandAvailable("pwsh")) {
            return List.of("pwsh", "-NoProfile", "-Command", command);
        }

        throw new IllegalStateException(
                "PowerShell Core (pwsh) is not installed. Install it to run Windows-style commands on this platform " +
                "(it is preinstalled in the provided Docker image)."
        );
    }

    /**
     * Checks whether an executable command exists in PATH.
     */
    public static boolean isCommandAvailable(String command) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }

        boolean isWindows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");

        String[] extensions = isWindows ? new String[]{".exe", ".cmd", ".bat", ""} : new String[]{""};
        String[] dirs = path.split(File.pathSeparator);

        for (String dir : dirs) {
            for (String ext : extensions) {
                File candidate = new File(dir, command + ext);
                if (candidate.isFile() && candidate.canExecute()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String readStream(InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
