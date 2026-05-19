package com.claw.tools.builtin;

import com.claw.tools.Tool;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Reads a file and returns its content with line numbers.
 * Supports offset (start line, 1-indexed) and limit (max lines).
 */
public class ReadFileTool implements Tool {

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read a file with line numbers. Specify path, optional offset (default 1), and limit (default 500).";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of(
                    "type", "string",
                    "description", "Path to the file to read"
                ),
                "offset", Map.of(
                    "type", "integer",
                    "description", "Line number to start reading from (1-indexed)",
                    "default", 1
                ),
                "limit", Map.of(
                    "type", "integer",
                    "description", "Maximum number of lines to read",
                    "default", 500
                )
            ),
            "required", List.of("path")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        String pathStr = (String) arguments.get("path");
        if (pathStr == null || pathStr.isBlank()) {
            return "Error: path parameter is required";
        }

        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = workingDirectory().resolve(path).normalize();
        }
        if (!Files.exists(path)) {
            return "Error: File not found: " + pathStr;
        }
        if (!Files.isRegularFile(path)) {
            return "Error: Not a regular file: " + pathStr;
        }

        int offset = getIntArg(arguments, "offset", 1);
        int limit = getIntArg(arguments, "limit", 500);

        if (offset < 1) offset = 1;
        if (limit < 1) limit = 1;

        StringBuilder sb = new StringBuilder();
        int lineCount = 0;
        int linesRead = 0;

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                if (lineCount < offset) continue;
                if (linesRead >= limit) break;
                sb.append(lineCount).append("|").append(line).append("\n");
                linesRead++;
            }
        }

        if (lineCount < offset) {
            return "Error: offset " + offset + " exceeds file length " + lineCount;
        }

        if (linesRead >= limit && lineCount > offset + limit - 1) {
            sb.append("... (truncated, ")
              .append(lineCount - offset - limit + 1)
              .append(" more lines)\n");
        }

        return sb.toString();
    }

    private int getIntArg(Map<String, Object> args, String key, int defaultValue) {
        Object val = args.get(key);
        if (val instanceof Number n) {
            return n.intValue();
        }
        if (val instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                // fall through to default
            }
        }
        return defaultValue;
    }
}
