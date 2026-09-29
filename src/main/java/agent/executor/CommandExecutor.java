package agent.executor;

import agent.model.ExecutionResult;

import java.io.ByteArrayOutputStream;
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

    /**
     * Cap on retained output per stream. A command such as {@code Get-Content} on a large file
     * would otherwise buffer without limit; excess output is counted but discarded, and the
     * child process keeps draining so it never blocks on a full pipe.
     */
    private static final int MAX_OUTPUT_BYTES = 1 << 20; // 1 MiB

    /** Grace period between the polite terminate and the forced kill. */
    private static final long KILL_GRACE_SECONDS = 2;

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
                // Ask first, then force, so the process gets a chance to clean up.
                terminate(process);
                // Keep whatever the command produced before the timeout rather than discarding it.
                String partialOut = collect(stdoutFuture);
                String partialErr = collect(stderrFuture);
                return new ExecutionResult(partialOut,
                        join(partialErr, "Command timed out after " + timeoutSeconds + " seconds."), 124);
            }

            int exitCode = process.exitValue();
            String stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(5, TimeUnit.SECONDS);

            return new ExecutionResult(stdout, stderr, exitCode);
        } catch (InterruptedException ex) {
            terminate(process);
            Thread.currentThread().interrupt();
            return new ExecutionResult("", "Command execution was interrupted.", 130);
        } catch (ExecutionException | TimeoutException ex) {
            terminate(process);
            return new ExecutionResult("", "Error capturing output: " + ex.getMessage(), 1);
        } finally {
            streamExecutor.shutdownNow();
        }
    }

    /**
     * Stops a process and its children, escalating to a forced kill if the polite request
     * is not honoured in time.
     */
    private static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static String collect(Future<String> future) {
        try {
            return future.get(KILL_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | TimeoutException ex) {
            return "";
        }
    }

    private static String join(String existing, String message) {
        return (existing == null || existing.isBlank()) ? message : existing + "\n" + message;
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

    /**
     * Reads a stream to completion, retaining at most {@link #MAX_OUTPUT_BYTES} and reporting
     * how much was dropped. The stream is always drained so the child never blocks writing.
     */
    private static String readStream(InputStream inputStream) throws IOException {
        ByteArrayOutputStream retained = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;

        while ((read = inputStream.read(buffer)) > 0) {
            total += read;
            int room = MAX_OUTPUT_BYTES - retained.size();
            if (room > 0) {
                retained.write(buffer, 0, Math.min(room, read));
            }
        }

        String text = retained.toString(StandardCharsets.UTF_8);
        if (total > MAX_OUTPUT_BYTES) {
            text = text + "\n... [output truncated: " + total + " bytes total, "
                    + (total - MAX_OUTPUT_BYTES) + " bytes omitted]";
        }
        return text;
    }
}
