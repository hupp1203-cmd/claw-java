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
 *   <li>Slash commands: /help, /exit, /model, /tools, /clear, /resume</li>
 *   <li>Multi-line input with {@code \} line continuation</li>
 *   <li>Auto-save session to {@code ~/.claw-java/sessions/}</li>
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
            Terminal t;
            try {
                t = TerminalBuilder.builder()
                        .system(true)
                        .jansi(true)
                        .build();
            } catch (Exception e) {
                // fallback for environments where jansi is unavailable
                t = TerminalBuilder.builder()
                        .system(true)
                        .dumb(true)
                        .build();
            }
            this.terminal = t;
            this.reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .variable(LineReader.HISTORY_SIZE, 200)
                    .option(LineReader.Option.HISTORY_IGNORE_DUPS, true)
                    .option(LineReader.Option.HISTORY_IGNORE_SPACE, true)
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
                    saveSession();
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
                    saveSession();
                    try { terminal.close(); } catch (java.io.IOException ignored) {}
                    return; // exit
                }

            } catch (UserInterruptException e) {
                terminal.writer().println("^C (type /exit to quit)");
                terminal.flush();
            } catch (EndOfFileException e) {
                saveSession();
                terminal.writer().println("Goodbye!");
                terminal.flush();
                try { terminal.close(); } catch (java.io.IOException ignored) {}
                return;
            }
        }
    }

    private void saveSession() {
        try {
            SessionStore.save(context.engine().getSessionId(),
                    context.model(), context.provider().name(),
                    context.engine().getConversation());
        } catch (Exception ignored) {
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
            case "/resume" -> { resumeSession(terminal, null); yield false; }
            case "/compact" -> { compactConversation(terminal); yield false; }
            default -> handleDefault(line, terminal);
        };
    }

    private boolean handleDefault(String line, Terminal terminal) {
        if (line.startsWith("/model ")) {
            switchModel(terminal, line.substring(7).trim());
        } else if (line.startsWith("/resume ")) {
            resumeSession(terminal, line.substring(8).trim());
        } else if (line.startsWith("/")) {
            terminal.writer().println("Unknown command: " + line + " (type /help for commands)");
            terminal.flush();
        } else {
            chat(terminal, line);
        }
        return false;
    }

    private void resumeSession(Terminal terminal, String id) {
        if (id != null && !id.isBlank()) {
            var conv = SessionStore.load(id);
            if (conv == null) {
                terminal.writer().println("Session not found: " + id);
                terminal.flush();
                return;
            }
            context.restoreConversation(conv);
            context.engine().setOnToken(token -> {
                terminal.writer().print(token);
                terminal.flush();
            });
            terminal.writer().println("Session restored: " + id + " ("
                    + conv.messageCount() + " messages)");
            terminal.flush();
            return;
        }

        // No id — list available sessions
        var sessions = SessionStore.list();
        if (sessions.isEmpty()) {
            terminal.writer().println("No saved sessions.");
            terminal.flush();
            return;
        }
        terminal.writer().println("Saved sessions:");
        for (var s : sessions) {
            terminal.writer().printf("  %s  %s  %s  %d msgs\n",
                    s.shortId(), s.time(), s.model(), s.messageCount());
        }
        terminal.writer().println("Use /resume <id> to restore a session.");
        terminal.flush();
    }

    private void showHelp(Terminal terminal) {
        terminal.writer().print("""
            Commands:
              /exit, /quit    Exit Claw
              /help           Show this help message
              /model <name>   Switch to a different model
              /tools          List all registered tools
              /clear          Clear conversation history
              /compact        Summarize and compress conversation history
              /resume [id]    List or restore saved sessions
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

    private void compactConversation(Terminal terminal) {
        int before = context.engine().messageCount();
        if (before == 0) {
            terminal.writer().println("Nothing to compact.");
            terminal.flush();
            return;
        }
        terminal.writer().println("Compacting conversation (" + before + " messages)...");
        terminal.flush();
        try {
            String summary = context.engine().compactWithSummary();
            if (summary != null) {
                terminal.writer().println("Compacted to summary (" + summary.length() + " chars).");
            } else {
                terminal.writer().println("Compacted.");
            }
        } catch (Exception e) {
            terminal.writer().println("Compact failed: " + e.getMessage());
        }
        terminal.flush();
        saveSession();
    }

    private void chat(Terminal terminal, String userMessage) {
        try {
            context.engine().chat(userMessage);
            terminal.writer().println();
            terminal.flush();
            saveSession();
        } catch (Exception e) {
            terminal.writer().println("Error: " + e.getMessage());
            terminal.flush();
        }
    }
}
