package com.claw.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Reads configuration from environment variables and {@code ~/.claw-java/config}.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Environment variable (e.g. {@code DEEPSEEK_API_KEY})</li>
 *   <li>{@code ~/.claw-java/config} — user-level (auto-created on first run)</li>
 *   <li>{@code ./.claw-java/config} — project-level (relative to user.dir)</li>
 * </ol>
 *
 * <p>Config file format:
 * <pre>
 * DEEPSEEK_API_KEY=sk-...
 * ANTHROPIC_API_KEY=sk-ant-...
 * </pre>
 */
public final class ClawConfig {

    private static final Logger log = LoggerFactory.getLogger(ClawConfig.class);

    /** User-level config path: ~/.claw-java/config */
    public static final Path USER_CONFIG = Path.of(System.getProperty("user.home"), ".claw-java", "config");

    private static final Properties props;

    static {
        initUserConfig();
        props = loadAll();
    }

    private ClawConfig() {}

    /**
     * Returns the value for {@code key}, checking env first, then config files.
     * Returns {@code null} if not found anywhere.
     */
    public static String get(String key) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) return env;

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
                    + ". Set it via environment variable or in " + USER_CONFIG);
        }
        return val;
    }

    /**
     * Creates {@code ~/.claw-java/config} with a commented template if it
     * doesn't already exist.
     */
    private static void initUserConfig() {
        if (Files.exists(USER_CONFIG)) return;
        try {
            Files.createDirectories(USER_CONFIG.getParent());
            Files.writeString(USER_CONFIG, """
                    # Claw-Java configuration
                    # Uncomment and set your API key:
                    # DEEPSEEK_API_KEY=sk-...
                    # ANTHROPIC_API_KEY=sk-ant-...
                    # OPENAI_API_KEY=sk-...
                    """);
            log.info("Created config template at {}", USER_CONFIG);
        } catch (IOException e) {
            log.warn("Failed to create config template at {}: {}", USER_CONFIG, e.getMessage());
        }
    }

    private static Properties loadAll() {
        var merged = new Properties();

        // User-level (loaded first, so project-level can override)
        loadFile(merged, USER_CONFIG);

        // Project-level (higher priority — overrides user config)
        Path projectConfig = Path.of(System.getProperty("user.dir"), ".claw-java", "config");
        loadFile(merged, projectConfig);

        log.debug("ClawConfig loaded {} keys", merged.size());
        return merged;
    }

    private static void loadFile(Properties merged, Path path) {
        if (Files.exists(path)) {
            try (var reader = Files.newBufferedReader(path)) {
                var fileProps = new Properties();
                fileProps.load(reader);
                for (var key : fileProps.stringPropertyNames()) {
                    merged.setProperty(key, fileProps.getProperty(key));
                }
                log.debug("Loaded config from {}", path);
            } catch (IOException e) {
                log.warn("Failed to read config file {}: {}", path, e.getMessage());
            }
        }
    }
}
