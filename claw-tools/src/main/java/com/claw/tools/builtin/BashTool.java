package com.claw.tools.builtin;

import com.claw.tools.Tool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Executes shell commands via ProcessBuilder.
 * Captures stdout and stderr. Timeout after 30 seconds. Output limited to 50KB.
 */
public class BashTool implements Tool {

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

        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        Thread stdoutThread = new Thread(() -> captureStream(process.getInputStream(), stdout, MAX_OUTPUT_BYTES));
        Thread stderrThread = new Thread(() -> captureStream(process.getErrorStream(), stderr, MAX_OUTPUT_BYTES));

        stdoutThread.start();
        stderrThread.start();

        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            stdoutThread.interrupt();
            stderrThread.interrupt();
            return "Error: Command timed out after " + TIMEOUT_SECONDS + " seconds";
        }

        stdoutThread.join(5000);
        stderrThread.join(5000);

        int exitCode = process.exitValue();
        StringBuilder result = new StringBuilder();
        result.append("Exit code: ").append(exitCode).append("\n");
        if (stdout.length() > 0) {
            result.append("stdout:\n").append(stdout);
        }
        if (stderr.length() > 0) {
            result.append("stderr:\n").append(stderr);
        }
        return result.toString();
    }

    private void captureStream(java.io.InputStream stream, StringBuilder sb, int maxBytes) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
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
            // Stream closed or interrupted
        }
    }
}
