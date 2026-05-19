package com.claw.tools.builtin;

import com.claw.tools.Tool;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Finds files matching a glob pattern under a directory tree.
 * <p>
 * Uses {@link Files#walk(Path, int)} with a {@link PathMatcher} for the glob.
 * Skips hidden directories (names starting with {@code .}) and limits depth to 20.
 * Results are returned as one path per line, sorted.
 * </p>
 */
public class FindTool implements Tool {

    private static final int MAX_DEPTH = 20;
    private static final int DEFAULT_MAX_RESULTS = 200;
    private static final String DEFAULT_PATH = ".";

    @Override
    public String name() {
        return "find";
    }

    @Override
    public String description() {
        return "Find files matching a glob pattern under a directory tree.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "glob", Map.of(
                    "type", "string",
                    "description", "Glob pattern to match, e.g. '*.java' or '**/*.xml'"
                ),
                "path", Map.of(
                    "type", "string",
                    "description", "Base directory to search from",
                    "default", DEFAULT_PATH
                ),
                "maxResults", Map.of(
                    "type", "integer",
                    "description", "Maximum number of results to return",
                    "default", DEFAULT_MAX_RESULTS
                )
            ),
            "required", List.of("glob")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        String glob = (String) arguments.get("glob");
        if (glob == null || glob.isBlank()) {
            return "Error: glob parameter is required";
        }

        String pathStr = (String) arguments.getOrDefault("path", DEFAULT_PATH);
        Object maxResultsArg = arguments.get("maxResults");
        int maxResults = (maxResultsArg instanceof Number n)
                ? n.intValue()
                : DEFAULT_MAX_RESULTS;

        Path startPath = Path.of(pathStr);
        if (!startPath.isAbsolute()) {
            startPath = workingDirectory().resolve(startPath).normalize();
        }
        final Path searchRoot = startPath;
        if (!Files.exists(searchRoot)) {
            return "Error: Path not found: " + pathStr;
        }

        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        } catch (Exception e) {
            return "Error: Invalid glob pattern: " + e.getMessage();
        }

        StringBuilder results = new StringBuilder();
        int[] count = {0};

        try (Stream<Path> stream = Files.walk(searchRoot, MAX_DEPTH)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        // Skip hidden directories
                        for (int i = searchRoot.getNameCount(); i < p.getNameCount(); i++) {
                            if (p.getName(i).toString().startsWith(".")) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .filter(p -> matcher.matches(p.getFileName()))
                    .sorted()
                    .toList();

            for (Path file : files) {
                if (count[0] >= maxResults) break;
                results.append(file.normalize()).append("\n");
                count[0]++;
            }
        } catch (IOException e) {
            return "Error: Failed to walk directory tree: " + e.getMessage();
        }

        if (results.isEmpty()) {
            return "No files found matching glob: " + glob;
        }

        if (count[0] >= maxResults) {
            results.append("... (truncated, max ").append(maxResults).append(" results)\n");
        }

        return results.toString();
    }
}
