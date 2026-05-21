package com.claw.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Reads configuration from environment variables and {@code .claw-java/config} files.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Environment variable (e.g. {@code DEEPSEEK_API_KEY})</li>
 *   <li>{@code ./.claw-java/config} — project-level (relative to user.dir)</li>
 *   <li>{@code ~/.claw-java/config} — user-level</li>
 * </ol>
 *
 * <p>Config file format is Java {@code .properties}:
 * <pre>
 * DEEPSEEK_API_KEY=sk-...
 * ANTHROPIC_API_KEY=sk-ant-...
 * claw.model=deepseek-chat
 * claw.provider=deepseek
 * </pre>
 */
public final class ClawConfig {

    private static final Logger log = LoggerFactory.getLogger(ClawConfig.class);
    private static final Properties props = loadAll();

    private ClawConfig() {}

    /**
     * Returns the value for {@code key}, checking env first, then config files.
     * Returns {@code null} if not found anywhere.
     */
    public static String get(String key) {
        // 1. Environment variable
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) return env;

        // 2. Config files
        String val = props.getProperty(key);
        if (val != null && !val.isBlank()) return val;

        return null;
    }

    /**
     * Returns the value for {@code key}, throwing if not found.
     */
    public static String require(String key) {
        String val = get(key);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException(
                    "Missing required config: " + key
                    + ". Set it via environment variable or in .claw-java/config");
        }
        return val;
    }

    private static Properties loadAll() {
        var merged = new Properties();

        // Project-level
        Path projectConfig = Path.of(System.getProperty("user.dir"), ".claw-java", "config");
        loadFile(merged, projectConfig);

        // User-level (lower priority)
        Path userConfig = Path.of(System.getProperty("user.home"), ".claw-java", "config");
        loadFile(merged, userConfig);

        log.debug("ClawConfig loaded {} keys from .claw-java/config files", merged.size());
        return merged;
    }

    private static void loadFile(Properties merged, Path path) {
        if (Files.exists(path)) {
            try (var reader = Files.newBufferedReader(path)) {
                var fileProps = new Properties();
                fileProps.load(reader);
                // File values are defaults — don't override already-loaded values
                for (var key : fileProps.stringPropertyNames()) {
                    if (!merged.containsKey(key)) {
                        merged.setProperty(key, fileProps.getProperty(key));
                    }
                }
                log.debug("Loaded config from {}", path);
            } catch (IOException e) {
                log.warn("Failed to read config file {}: {}", path, e.getMessage());
            }
        }
    }
}
