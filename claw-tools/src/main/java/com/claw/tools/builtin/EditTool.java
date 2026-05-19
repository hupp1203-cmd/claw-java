package com.claw.tools.builtin;

import com.claw.tools.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Performs exact string replacement in a file.
 * <p>
 * Reads the file, finds the given {@code oldText} (which must appear exactly once),
 * replaces it with {@code newText}, and writes the result back.
 * Rejects ambiguous or missing matches to avoid accidental corruption.
 * </p>
 */
public class EditTool implements Tool {

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String description() {
        return "Edit a file with exact string replacement. oldText must match exactly once.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of(
                    "type", "string",
                    "description", "Path to the file to edit"
                ),
                "oldText", Map.of(
                    "type", "string",
                    "description", "Exact text to find and replace"
                ),
                "newText", Map.of(
                    "type", "string",
                    "description", "Replacement text"
                )
            ),
            "required", List.of("path", "oldText", "newText")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        String pathStr = (String) arguments.get("path");
        String oldText = (String) arguments.get("oldText");
        String newText = (String) arguments.get("newText");

        if (pathStr == null || pathStr.isBlank()) {
            return "Error: path parameter is required";
        }
        if (oldText == null) {
            return "Error: oldText parameter is required";
        }
        if (newText == null) {
            return "Error: newText parameter is required";
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

        String content = Files.readString(path);

        int index = content.indexOf(oldText);
        if (index == -1) {
            return "Error: oldText not found in file";
        }

        // Check for multiple matches
        int secondIndex = content.indexOf(oldText, index + oldText.length());
        if (secondIndex != -1) {
            return "Error: oldText matches multiple locations";
        }

        String replaced = content.substring(0, index) + newText + content.substring(index + oldText.length());
        Files.writeString(path, replaced);

        return "File edited: " + path.toAbsolutePath();
    }
}
