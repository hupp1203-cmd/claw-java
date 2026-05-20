package com.claw.cli;

/**
 * Entry point for the Claw CLI application.
 * Creates a default context and launches the interactive REPL.
 */
public class ClawApplication {

    private static final String WORKDIR_PROP = "claw.workdir";

    public static void main(String[] args) {
        // Allow overriding working directory via system property
        String workdir = System.getProperty(WORKDIR_PROP);
        if (workdir != null && !workdir.isBlank()) {
            System.setProperty("user.dir", workdir);
        }

        ClawContext context = ClawContext.createDefault();
        ClawRepl repl = new ClawRepl(context);
        repl.start();
    }
}
