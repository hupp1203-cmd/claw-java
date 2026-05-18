package com.claw.tools.builtin;

import com.claw.tools.Tool;

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

        List<String> lines = Files.readAllLines(path);

        if (offset > lines.size()) {
            return "Error: offset " + offset + " exceeds file length " + lines.size();
        }

        int end = Math.min(offset + limit - 1, lines.size());
        StringBuilder sb = new StringBuilder();

        for (int i = offset - 1; i < end; i++) {
            sb.append(i + 1).append("|").append(lines.get(i)).append("\n");
        }

        if (end < lines.size()) {
            sb.append("... (truncated, ")
              .append(lines.size() - end)
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
