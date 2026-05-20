package com.claw.tools.builtin;

import com.claw.tools.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Executes shell commands via ProcessBuilder.
 * Captures stdout and stderr using virtual threads. Output limited to 50KB.
 */
public class BashTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);
    private static final int MAX_OUTPUT_BYTES = 50 * 1024; // 50KB
    private static final long TIMEOUT_SECONDS = 30;

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "Execute a shell command via bash. Returns exit code, stdout, and stderr.";
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(TIMEOUT_SECONDS);
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "command", Map.of(
                    "type", "string",
                    "description", "The shell command to execute"
                )
            ),
            "required", List.of("command")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        String command = (String) arguments.get("command");
        if (command == null || command.isBlank()) {
            return "Error: command parameter is required";
        }

        log.debug("Executing: {}", command);

        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.directory(workingDirectory().toFile());
        pb.redirectErrorStream(false);
        Process process = pb.start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stdoutFuture = executor.submit(() -> captureStream(process.getInputStream(), stdout, MAX_OUTPUT_BYTES));
            var stderrFuture = executor.submit(() -> captureStream(process.getErrorStream(), stderr, MAX_OUTPUT_BYTES));

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                stdoutFuture.cancel(true);
                stderrFuture.cancel(true);
                log.warn("Command timed out after {}s: {}", TIMEOUT_SECONDS, command);
                return "Error: Command timed out after " + TIMEOUT_SECONDS + " seconds";
            }

            try {
                stdoutFuture.get(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            try {
                stderrFuture.get(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }

        int exitCode = process.exitValue();
        var result = new StringBuilder();
        result.append("Exit code: ").append(exitCode).append("\n");
        if (!stdout.isEmpty()) {
            result.append("stdout:\n").append(stdout);
        }
        if (!stderr.isEmpty()) {
            result.append("stderr:\n").append(stderr);
        }
        return result.toString();
    }

    private static void captureStream(java.io.InputStream stream, StringBuilder sb, int maxBytes) {
        try (var reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() + line.length() + 1 <= maxBytes) {
                    sb.append(line).append("\n");
                }
                if (sb.length() >= maxBytes) {
                    break;
                }
            }
        } catch (IOException e) {
            log.debug("Stream closed or interrupted: {}", e.toString());
        }
    }
}
