package com.claw.cli;

import com.claw.core.model.Conversation;
import com.claw.core.model.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

/**
 * Saves and loads conversation sessions as JSON files.
 */
final class SessionStore {

    private static final Path SESSIONS_DIR = Path.of(
            System.getProperty("user.home"), ".claw-java", "sessions");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** Save a conversation with metadata. */
    static void save(String sessionId, String model, String provider,
                     Conversation conversation) {
        try {
            Files.createDirectories(SESSIONS_DIR);
            var record = new LinkedHashMap<String, Object>();
            record.put("sessionId", sessionId);
            record.put("model", model);
            record.put("provider", provider);
            record.put("savedAt", Instant.now().toString());
            if (conversation.getSystemPrompt() != null) {
                record.put("systemPrompt", conversation.getSystemPrompt());
            }
            record.put("messages", conversation.getMessages());
            MAPPER.writeValue(SESSIONS_DIR.resolve(sessionId + ".json").toFile(), record);
        } catch (IOException e) {
            // best-effort save, don't crash
        }
    }

    /** List all saved sessions, most recent first. */
    static List<SessionInfo> list() {
        var result = new ArrayList<SessionInfo>();
        try {
            Files.createDirectories(SESSIONS_DIR);
            try (Stream<Path> files = Files.list(SESSIONS_DIR)) {
                files.filter(p -> p.toString().endsWith(".json"))
                        .sorted(Comparator.comparingLong(
                                p -> -p.toFile().lastModified()))
                        .forEach(p -> {
                            try {
                                var node = MAPPER.readTree(p.toFile());
                                var id = node.get("sessionId").asText();
                                var model = node.has("model") ? node.get("model").asText() : "?";
                                var time = node.has("savedAt")
                                        ? parseTime(node.get("savedAt").asText())
                                        : "unknown";
                                int msgs = node.has("messages") ? node.get("messages").size() : 0;
                                result.add(new SessionInfo(id, model, time, msgs));
                            } catch (IOException ignored) {
                            }
                        });
            }
        } catch (IOException ignored) {
        }
        return result;
    }

    /** Load a saved conversation by session ID (exact or prefix match). */
    static Conversation load(String sessionId) {
        Path file = SESSIONS_DIR.resolve(sessionId + ".json");
        if (!Files.exists(file)) {
            // Try prefix match for short IDs
            try {
                Files.createDirectories(SESSIONS_DIR);
                try (var files = Files.list(SESSIONS_DIR)) {
                    var match = files
                            .filter(p -> p.getFileName().toString().startsWith(sessionId))
                            .findFirst();
                    file = match.orElse(null);
                }
            } catch (IOException e) {
                return null;
            }
        }
        if (file == null || !Files.exists(file)) return null;
        try {
            var node = MAPPER.readTree(file.toFile());
            var msgs = new ArrayList<Message>();
            if (node.has("messages")) {
                for (var m : node.get("messages")) {
                    Message msg = MAPPER.convertValue(m, Message.class);
                    msgs.add(msg);
                }
            }
            var conv = new Conversation(msgs);
            if (node.has("systemPrompt")) {
                conv.setSystemPrompt(node.get("systemPrompt").asText());
            }
            return conv;
        } catch (IOException e) {
            return null;
        }
    }

    private static String parseTime(String iso) {
        try {
            return FMT.format(Instant.parse(iso));
        } catch (Exception e) {
            return iso;
        }
    }

    record SessionInfo(String id, String model, String time, int messageCount) {
        String shortId() { return id.length() > 8 ? id.substring(0, 8) : id; }
    }
}
