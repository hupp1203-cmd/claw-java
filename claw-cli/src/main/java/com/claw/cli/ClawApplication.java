package com.claw.cli;

import com.claw.core.ClawConfig;

/**
 * Entry point for the Claw CLI application.
 *
 * <p>Configuration resolution order:
 * <ol>
 *   <li>{@code -Dclaw.workdir=...} system property</li>
 *   <li>{@code claw.workdir} in {@code .claw-java/config}</li>
 *   <li>current working directory</li>
 * </ol>
 */
public class ClawApplication {

    private static final String WORKDIR_PROP = "claw.workdir";

    public static void main(String[] args) {
        // Allow overriding working directory via system property or .claw-java/config
        String workdir = System.getProperty(WORKDIR_PROP);
        if (workdir == null || workdir.isBlank()) {
            workdir = ClawConfig.get(WORKDIR_PROP);
        }
        if (workdir != null && !workdir.isBlank()) {
            System.setProperty("user.dir", workdir);
        }

        ClawContext context = ClawContext.createDefault();
        ClawRepl repl = new ClawRepl(context);
        repl.start();
    }
}
