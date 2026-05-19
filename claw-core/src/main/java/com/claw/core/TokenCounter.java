package com.claw.core;

import com.claw.core.model.Message;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Accurate token counter backed by jtokkit's CL100K_BASE encoding.
 *
 * <p>Falls back to the classic {@code charCount / 4} heuristic if jtokkit
 * is unavailable at runtime (e.g. missing on the classpath). All public
 * methods are thread-safe.</p>
 */
public final class TokenCounter {

    private static final Logger log = LoggerFactory.getLogger(TokenCounter.class);

    /** Lazy-loaded, thread-safe singleton encoding instance. */
    private static final class EncodingHolder {
        static final Encoding INSTANCE = loadEncoding();

        private static Encoding loadEncoding() {
            try {
                EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
                Encoding enc = registry.getEncoding(EncodingType.CL100K_BASE);
                log.info("TokenCounter initialized with jtokkit encoding: {}", enc.getName());
                return enc;
            } catch (Exception | NoClassDefFoundError e) {
                log.warn("jtokkit unavailable, falling back to char/4 heuristic: {}", e.toString());
                return null;
            }
        }
    }

    private TokenCounter() { /* utility class */ }

    /** Returns the encoding if available, otherwise {@code null}. */
    private static Encoding encoding() {
        return EncodingHolder.INSTANCE;
    }

    /**
     * Counts tokens in a single text string.
     *
     * @param text the text to count tokens for
     * @return estimated token count
     */
    public static int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Encoding enc = encoding();
        if (enc != null) {
            return enc.countTokensOrdinary(text);
        }
        // fallback heuristic
        return text.length() / 4;
    }

    /**
     * Counts tokens across a list of messages, including text content,
     * structured content blocks, and tool call names/arguments.
     *
     * @param messages the messages to count
     * @return total estimated token count
     */
    public static int countMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Message m : messages) {
            total += countMessage(m);
        }
        return total;
    }

    /**
     * Counts tokens in a single message, including text, content blocks,
     * and tool call data.
     */
    public static int countMessage(Message message) {
        if (message == null) {
            return 0;
        }
        int total = 0;

        // Plain text content
        if (message.textContent() != null) {
            total += count(message.textContent());
        }

        // Structured content blocks
        for (var block : message.contentBlocks()) {
            if (block instanceof Message.TextBlock tb) {
                total += count(tb.text());
            }
        }

        // Tool call name + serialized arguments
        for (var tc : message.toolCalls()) {
            total += count(tc.name());
            total += count(tc.arguments().toString());
        }

        return total;
    }

    /**
     * Returns {@code true} if jtokkit encoding is available and being used.
     */
    public static boolean isAccurate() {
        return encoding() != null;
    }
}
