package com.claw.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A single message in a conversation, corresponding to one turn.
 *
 * <p>The content can be either a plain text string (legacy/simple path) or a
 * list of {@link ContentBlock} elements (for multi-modal messages with images).
 * The {@code toolCalls} field is populated on assistant messages that request
 * tool invocations, and {@code toolCallId} links a tool-result message back to
 * the originating call.</p>
 *
 * @param role        the role of the message sender (system, user, assistant, or tool)
 * @param content     the message body — either a plain string or a list of content blocks
 * @param toolCalls   tool invocation requests (only present on assistant messages)
 * @param toolCallId  correlation ID linking a tool result to the originating call
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(
        Role role,
        Object content,
        List<ToolCall> toolCalls,
        String toolCallId) {

    // --- Canonical constructors ---

    /** Creates a simple text message with no tool metadata. */
    public Message(Role role, String text) {
        this(role, text, List.of(), null);
    }

    /** Creates a message with structured content blocks and no tool metadata. */
    public Message(Role role, List<ContentBlock> blocks) {
        this(role, blocks, List.of(), null);
    }

    /** Creates an assistant message with text and tool calls. */
    public Message(Role role, String text, List<ToolCall> toolCalls) {
        this(role, text, toolCalls, null);
    }

    /** Full canonical constructor with null-safety. */
    public Message {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (toolCalls == null) toolCalls = List.of();
        toolCalls = List.copyOf(toolCalls);
    }

    // --- Convenience accessors ---

    /** Returns the text content if this message carries a plain string; otherwise {@code null}. */
    public String textContent() {
        return content instanceof String s ? s : null;
    }

    /** Returns the structured content blocks if present; otherwise an empty list. */
    @SuppressWarnings("unchecked")
    public List<ContentBlock> contentBlocks() {
        return content instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof ContentBlock
                ? (List<ContentBlock>) list
                : List.of();
    }

    /** Returns {@code true} when this message contains tool call requests. */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    // --- Factory helpers ---

    /** Convenience factory for a user message. */
    public static Message user(String text) {
        return new Message(Role.USER, text);
    }

    /** Convenience factory for an assistant message. */
    public static Message assistant(String text) {
        return new Message(Role.ASSISTANT, text);
    }

    /** Convenience factory for a tool result message. */
    public static Message tool(String toolCallId, String result) {
        return new Message(Role.TOOL, result, List.of(), toolCallId);
    }

    /** Convenience factory for a system message. */
    public static Message system(String text) {
        return new Message(Role.SYSTEM, text);
    }

    // --- Inner types ---

    /** The role of a message sender in the conversation. */
    public enum Role {
        @JsonProperty("system")    SYSTEM,
        @JsonProperty("user")      USER,
        @JsonProperty("assistant") ASSISTANT,
        @JsonProperty("tool")      TOOL
    }

    /**
     * A single block of content within a multi-modal message.
     *
     * <p>Sealed to allow exhaustive {@code switch} handling. Known subtypes:
     * {@link TextBlock} and {@link ImageBlock}.</p>
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
            @JsonSubTypes.Type(value = ImageBlock.class, name = "image")
    })
    public sealed interface ContentBlock permits TextBlock, ImageBlock {
        /** Discriminator for JSON serialization. */
        String type();
    }

    /** A plain-text content block. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TextBlock(String text) implements ContentBlock {
        public TextBlock { Objects.requireNonNull(text, "text"); }
        @Override public String type() { return "text"; }
    }

    /** An image content block (base64-encoded data). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImageBlock(
            @JsonProperty("source") ImageSource source) implements ContentBlock {

        public ImageBlock { Objects.requireNonNull(source, "source"); }
        @Override public String type() { return "image"; }

        /**
         * @param mediaType MIME type (e.g. {@code "image/png"})
         * @param data      base64-encoded image bytes
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record ImageSource(
                @JsonProperty("media_type") String mediaType,
                String data) {
            public ImageSource {
                Objects.requireNonNull(mediaType, "mediaType");
                Objects.requireNonNull(data, "data");
            }
        }
    }
}
