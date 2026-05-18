package com.claw.core.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Mutable container for the full conversation history.
 *
 * <p>Maintains an ordered list of {@link Message} objects and an optional
 * system prompt (injected as the first message when the conversation is
 * sent to the model). Provides a simple character-based token estimator
 * and a compaction mechanism to keep the context window manageable.</p>
 *
 * <h3>Token Estimation</h3>
 * Uses a simple heuristic: token count ≈ character count ÷ 4. This is
 * intentionally coarse — production systems would use a proper tokenizer.
 *
 * <h3>Compaction</h3>
 * When {@link #shouldCompact(int)} returns {@code true} the caller should
 * invoke {@link #compact()} to summarize older messages and free space.
 */
public class Conversation {

    private static final Logger log = LoggerFactory.getLogger(Conversation.class);

    /** Default threshold (in characters) above which compaction is recommended. */
    public static final int DEFAULT_COMPACTION_THRESHOLD_CHARS = 100_000;

    private final List<Message> messages;
    private String systemPrompt;

    /** Creates an empty conversation. */
    public Conversation() {
        this.messages = new ArrayList<>();
        this.systemPrompt = null;
    }

    /** Creates a conversation preloaded with the given messages. */
    public Conversation(List<Message> initialMessages) {
        this();
        this.messages.addAll(initialMessages);
    }

    // --- Message management ---

    /**
     * Appends a message to the end of the conversation.
     * @return this conversation (fluent API)
     */
    public Conversation addMessage(Message message) {
        Objects.requireNonNull(message, "message must not be null");
        messages.add(message);
        log.debug("Added message role={} totalMessages={}", message.role(), messages.size());
        return this;
    }

    /** Appends multiple messages. */
    public Conversation addMessages(List<Message> batch) {
        for (var m : batch) addMessage(m);
        return this;
    }

    /** Returns an unmodifiable view of all messages (excluding the system prompt). */
    public List<Message> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /** Returns the number of messages in the conversation. */
    public int messageCount() {
        return messages.size();
    }

    /** Removes all messages from the conversation. */
    public void clear() {
        messages.clear();
    }

    // --- System prompt ---

    /** Sets the system prompt to inject at the start of every model call. */
    public void setSystemPrompt(String prompt) {
        this.systemPrompt = prompt;
    }

    /** Returns the current system prompt, or {@code null} if none is set. */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Builds the full message list for the model, with the system prompt
     * prepended as a {@link Message.Role#SYSTEM} message if one is configured.
     */
    public List<Message> buildModelMessages() {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return List.copyOf(messages);
        }
        var full = new ArrayList<Message>(messages.size() + 1);
        full.add(Message.system(systemPrompt));
        full.addAll(messages);
        return Collections.unmodifiableList(full);
    }

    // --- Token estimation ---

    /**
     * Estimates the total token count using the simple heuristic of
     * {@code characterCount / 4}.
     *
     * <p>Only considers text content for estimation. Tool call arguments
     * are included in the count when serialized.</p>
     */
    public int estimateTokens() {
        long totalChars = messages.stream()
                .mapToLong(Conversation::messageCharCount)
                .sum();
        if (systemPrompt != null) {
            totalChars += systemPrompt.length();
        }
        return (int) (totalChars / 4);
    }

    /** Total character count across all messages (for threshold checks). */
    public long totalCharacterCount() {
        long total = messages.stream().mapToLong(Conversation::messageCharCount).sum();
        if (systemPrompt != null) total += systemPrompt.length();
        return total;
    }

    private static long messageCharCount(Message m) {
        long count = 0;
        if (m.textContent() != null) {
            count += m.textContent().length();
        }
        for (var block : m.contentBlocks()) {
            if (block instanceof Message.TextBlock tb) {
                count += tb.text().length();
            }
        }
        for (var tc : m.toolCalls()) {
            count += tc.name().length();
            count += tc.arguments().toString().length();
        }
        return count;
    }

    // --- Compaction ---

    /**
     * Returns {@code true} when the conversation's character count exceeds
     * the given threshold, signalling that compaction should be performed.
     *
     * @param thresholdChars character count threshold (default: {@link #DEFAULT_COMPACTION_THRESHOLD_CHARS})
     */
    public boolean shouldCompact(int thresholdChars) {
        return totalCharacterCount() > thresholdChars;
    }

    /** Convenience overload using {@link #DEFAULT_COMPACTION_THRESHOLD_CHARS}. */
    public boolean shouldCompact() {
        return shouldCompact(DEFAULT_COMPACTION_THRESHOLD_CHARS);
    }

    /**
     * Compacts the conversation to reduce token usage.
     *
     * <p>The current strategy keeps the first message (often a system/user
     * exchange that sets context) and the most recent {@code keepRecent}
     * messages, replacing the middle portion with a single summary message.</p>
     *
     * @param keepRecent number of recent messages to preserve (default: 6)
     */
    public void compact(int keepRecent) {
        if (messages.size() <= keepRecent + 2) {
            return; // nothing to compact
        }
        int removeStart = 1; // keep the first message
        int removeEnd = messages.size() - keepRecent;

        if (removeEnd <= removeStart) return;

        var removed = new ArrayList<>(messages.subList(removeStart, removeEnd));
        messages.subList(removeStart, removeEnd).clear();

        // Insert a summary placeholder
        String summary = "[Conversation compacted: " + removed.size()
                + " messages summarized. Continuing with most recent context.]";
        messages.add(removeStart, Message.system(summary));

        log.info("Compacted conversation: removed {} messages, kept {} recent, total now {}",
                removed.size(), keepRecent, messages.size());
    }

    /** Convenience overload keeping 6 recent messages. */
    public void compact() {
        compact(6);
    }

    @Override
    public String toString() {
        return "Conversation{messages=%d, systemPromptSet=%s, estimatedTokens=%d}"
                .formatted(messages.size(), systemPrompt != null, estimateTokens());
    }
}
