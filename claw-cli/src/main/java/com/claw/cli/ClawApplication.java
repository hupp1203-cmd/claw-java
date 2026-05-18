package com.claw.cli;

/**
 * Entry point for the Claw CLI application.
 * Creates a default context and launches the interactive REPL.
 */
public class ClawApplication {

    public static void main(String[] args) {
        ClawContext context = ClawContext.createDefault();
        ClawRepl repl = new ClawRepl(context);
        repl.start();
    }
}
