package com.claw.cli;

import com.claw.tools.Tool;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;

/**
 * Interactive REPL using JLine for rich terminal I/O.
 *
 * <p>Supports:
 * <ul>
 *   <li>Multi-turn conversation with the agent</li>
 *   <li>Streaming token output (real-time rendering)</li>
 *   <li>Slash commands: /help, /exit, /model, /tools, /clear, /continue</li>
 *   <li>Multi-line input with {@code \} line continuation</li>
 * </ul>
 */
public class ClawRepl {

    private final ClawContext context;
    private final Terminal terminal;
    private final LineReader reader;

    /** Creates a REPL for the given context. */
    public ClawRepl(ClawContext context) {
        this.context = context;
        try {
            this.terminal = TerminalBuilder.builder()
                    .system(true)
                    .dumb(true)  // fallback for non-TTY environments
                    .build();
            this.reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize JLine terminal", e);
        }
    }

    // --- Main loop ---

    /**
     * Starts the interactive REPL loop.
     * Blocks until the user types {@code /exit} or sends EOF.
     */
    public void start() {
        printBanner(terminal);

        // Enable streaming output
        context.engine().setOnToken(token -> {
            terminal.writer().print(token);
            terminal.flush();
        });

        while (true) {
            try {
                String prompt = context.model() + "> ";
                String line = reader.readLine(prompt);

                if (line == null) {
                    // EOF
                    terminal.writer().println("Goodbye!");
                    terminal.flush();
                    try { terminal.close(); } catch (java.io.IOException ignored) {}
                    return;
                }

                line = line.trim();
                if (line.isEmpty()) continue;

                // Handle multi-line input (trailing backslash)
                while (line.endsWith("\\")) {
                    line = line.substring(0, line.length() - 1);
                    String next = reader.readLine("... ");
                    if (next == null) break;
                    line += "\n" + next;
                }

                // Process command or chat
                if (handleCommand(line, terminal)) {
                    try { terminal.close(); } catch (java.io.IOException ignored) {}
                    return; // exit
                }

            } catch (UserInterruptException e) {
                terminal.writer().println("^C (type /exit to quit)");
                terminal.flush();
            } catch (EndOfFileException e) {
                terminal.writer().println("Goodbye!");
                terminal.flush();
                try { terminal.close(); } catch (java.io.IOException ignored) {}
                return;
            }
        }
    }

    // --- Banner ---

    private void printBanner(Terminal terminal) {
        terminal.writer().println("""
            ╔══════════════════════════════════════════╗
            ║            🦞  Claw-Java                  ║
            ║  Claude Code architecture in Java 21     ║
            ╚══════════════════════════════════════════╝""");
        terminal.writer().println("Provider: " + context.provider().name()
                + " | Model: " + context.model());
        terminal.writer().println("Type /help for commands, or just ask a question.");
        terminal.writer().println();
        terminal.flush();
    }

    // --- Command handling ---

    /**
     * Handle a command line. Returns true if the REPL should exit.
     */
    private boolean handleCommand(String line, Terminal terminal) {
        return switch (line) {
            case "/exit", "/quit" -> {
                terminal.writer().println("Goodbye!");
                terminal.flush();
                yield true;
            }
            case "/help" -> { showHelp(terminal); yield false; }
            case "/tools" -> { showTools(terminal); yield false; }
            case "/clear" -> {
                context.clearConversation();
                context.engine().setOnToken(token -> {
                    terminal.writer().print(token);
                    terminal.flush();
                });
                terminal.writer().println("Conversation cleared.");
                terminal.flush();
                yield false;
            }
            case "/continue" -> {
                terminal.writer().println("Continuing last conversation...");
                terminal.flush();
                try {
                    String response = context.engine().continue_();
                    terminal.writer().println(response);
                    terminal.writer().println();
                } catch (Exception e) {
                    terminal.writer().println("Error: " + e.getMessage());
                }
                terminal.flush();
                yield false;
            }
            default -> handleDefault(line, terminal);
        };
    }

    private boolean handleDefault(String line, Terminal terminal) {
        if (line.startsWith("/model ")) {
            switchModel(terminal, line.substring(7).trim());
        } else if (line.startsWith("/")) {
            terminal.writer().println("Unknown command: " + line + " (type /help for commands)");
            terminal.flush();
        } else {
            chat(terminal, line);
        }
        return false;
    }

    private void showHelp(Terminal terminal) {
        terminal.writer().print("""
            Commands:
              /exit, /quit    Exit Claw
              /help           Show this help message
              /model <name>   Switch to a different model
              /tools          List all registered tools
              /clear          Clear conversation history
              /continue       Continue the last conversation
            """);
        terminal.flush();
    }

    private void showTools(Terminal terminal) {
        terminal.writer().println("Available tools:");
        for (Tool tool : context.toolRegistry().listAll()) {
            terminal.writer().println("  " + tool.name() + " - " + tool.description());
        }
        terminal.flush();
    }

    private void switchModel(Terminal terminal, String modelName) {
        if (modelName.isBlank()) {
            terminal.writer().println("Usage: /model <model-name>");
            terminal.writer().println("Current model: " + context.model());
            terminal.flush();
            return;
        }
        context.setModel(modelName);
        context.engine().setOnToken(token -> {
            terminal.writer().print(token);
            terminal.flush();
        });
        terminal.writer().println("Switched to model: " + modelName);
        terminal.flush();
    }

    private void chat(Terminal terminal, String userMessage) {
        try {
            String response = context.engine().chat(userMessage);
            // Print a newline after streaming output for separation
            terminal.writer().println();
            terminal.flush();
        } catch (Exception e) {
            terminal.writer().println("Error: " + e.getMessage());
            terminal.flush();
        }
    }
}
