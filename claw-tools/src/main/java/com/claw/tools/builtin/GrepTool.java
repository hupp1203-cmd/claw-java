package com.claw.tools.builtin;

import com.claw.tools.Tool;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Searches files using a regex pattern via Java Pattern/Matcher.
 * Walks directory trees with Files.walk. Returns matched lines with file:line info.
 */
public class GrepTool implements Tool {

    private static final int MAX_MATCHES = 200;
    private static final int MAX_DEPTH = 20;

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "Search files for lines matching a regex pattern. Returns file:line_number:matched_line.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "pattern", Map.of(
                    "type", "string",
                    "description", "Regex pattern to search for"
                ),
                "path", Map.of(
                    "type", "string",
                    "description", "Directory or file to search in",
                    "default", "."
                ),
                "fileGlob", Map.of(
                    "type", "string",
                    "description", "Optional file glob pattern to filter files (e.g., '*.java')"
                )
            ),
            "required", List.of("pattern")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        String patternStr = (String) arguments.get("pattern");
        if (patternStr == null || patternStr.isBlank()) {
            return "Error: pattern parameter is required";
        }

        String searchPath = (String) arguments.getOrDefault("path", ".");
        String fileGlob = (String) arguments.get("fileGlob");

        Path startPath = Path.of(searchPath).toAbsolutePath().normalize();
        if (!Files.exists(startPath)) {
            return "Error: Path not found: " + searchPath;
        }

        Pattern pattern;
        try {
            pattern = Pattern.compile(patternStr);
        } catch (PatternSyntaxException e) {
            return "Error: Invalid regex pattern: " + e.getMessage();
        }

        final PathMatcher pathMatcher = (fileGlob != null && !fileGlob.isBlank())
            ? FileSystems.getDefault().getPathMatcher("glob:" + fileGlob)
            : null;

        StringBuilder results = new StringBuilder();
        int[] matchCount = {0};

        if (Files.isRegularFile(startPath)) {
            grepFile(startPath, pattern, results, matchCount, MAX_MATCHES);
        } else {
            try (Stream<Path> stream = Files.walk(startPath, MAX_DEPTH)) {
                List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> pathMatcher == null || pathMatcher.matches(p.getFileName()))
                    .sorted()
                    .toList();

                for (Path file : files) {
                    if (matchCount[0] >= MAX_MATCHES) break;
                    if (isBinary(file)) continue;
                    grepFile(file, pattern, results, matchCount, MAX_MATCHES);
                }
            }
        }

        if (results.isEmpty()) {
            return "No matches found for pattern: " + patternStr;
        }

        if (matchCount[0] >= MAX_MATCHES) {
            results.append("... (truncated, max ").append(MAX_MATCHES).append(" matches)\n");
        }

        return results.toString();
    }

    private void grepFile(Path file, Pattern pattern, StringBuilder results,
                          int[] matchCount, int max) {
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size() && matchCount[0] < max; i++) {
                if (pattern.matcher(lines.get(i)).find()) {
                    results.append(file).append(":").append(i + 1).append(":")
                           .append(lines.get(i)).append("\n");
                    matchCount[0]++;
                }
            }
        } catch (IOException ignored) {
            // Permission denied, binary file, etc.
        }
    }

    private boolean isBinary(Path file) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buf = new byte[1024];
            int read = in.read(buf);
            for (int i = 0; i < read; i++) {
                if (buf[i] == 0) return true;
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }
}
