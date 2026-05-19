package com.claw.tools.builtin;

import com.claw.tools.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes content to a file, creating parent directories as needed.
 * Overwrites the file if it already exists.
 */
public class WriteFileTool implements Tool {

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Write content to a file. Creates parent directories. Overwrites if file exists.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of(
                    "type", "string",
                    "description", "Path to the file to write"
                ),
                "content", Map.of(
                    "type", "string",
                    "description", "Content to write to the file"
                )
            ),
            "required", List.of("path", "content")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        String pathStr = (String) arguments.get("path");
        String content = (String) arguments.get("content");

        if (pathStr == null || pathStr.isBlank()) {
            return "Error: path parameter is required";
        }
        if (content == null) {
            return "Error: content parameter is required";
        }

        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = workingDirectory().resolve(path).normalize();
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.writeString(path, content);

        return "File written successfully: " + path.toAbsolutePath()
                + " (" + content.length() + " bytes)";
    }
}
