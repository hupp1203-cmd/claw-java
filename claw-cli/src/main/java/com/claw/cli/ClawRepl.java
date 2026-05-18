package com.claw.cli;

import com.claw.tools.Tool;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * JLine-based interactive REPL for Claw.
 * Provides command parsing, tool listing, model switching, and conversation features.
 */
public class ClawRepl {

    private static final String PROMPT = "claw> ";

    private final ClawContext context;

    public ClawRepl(ClawContext context) {
        this.context = context;
    }

    /**
     * Start the REPL loop. Blocks until the user exits.
     */
    public void start() {
        try {
            Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build();

            LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();

            printBanner(terminal);

            while (true) {
                String line = null;
                try {
                    line = reader.readLine(PROMPT);
                } catch (UserInterruptException e) {
                    terminal.writer().println("^C");
                    terminal.flush();
                    continue;
                } catch (EndOfFileException e) {
                    terminal.writer().println("exit");
                    terminal.flush();
                    break;
                }

                if (line == null) break;

                line = line.trim();
                if (line.isEmpty()) continue;

                if (handleCommand(line, terminal)) {
                    break;
                }
            }

            terminal.close();
        } catch (Exception e) {
            System.err.println("Error starting REPL: " + e.getMessage());
        }
    }

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
            Claw Commands:
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
        terminal.writer().println("Switched to model: " + modelName);
        terminal.flush();
    }

    private void chat(Terminal terminal, String userMessage) {
        try {
            String response = context.engine().chat(userMessage);
            terminal.writer().println(response);
            terminal.writer().println();
        } catch (Exception e) {
            terminal.writer().println("Error: " + e.getMessage());
        }
        terminal.flush();
    }
}
