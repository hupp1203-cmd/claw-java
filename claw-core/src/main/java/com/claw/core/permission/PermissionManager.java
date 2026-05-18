package com.claw.core.permission;

/**
 * Manages the current permission level for tool execution.
 *
 * <p>Simple wrapper around {@link PermissionLevel} that answers the question
 * "should the agent ask for confirmation before running this tool?"</p>
 *
 * <p>Usage:
 * <pre>{@code
 * var pm = new PermissionManager(PermissionLevel.ASK);
 * if (pm.shouldAsk("execute_command")) {
 *     // prompt user
 * }
 * }</pre>
 */
public class PermissionManager {

    private PermissionLevel level;

    /**
     * Creates a manager with the given permission level.
     */
    public PermissionManager(PermissionLevel level) {
        this.level = level;
    }

    /** Creates a manager defaulting to {@link PermissionLevel#ASK}. */
    public PermissionManager() {
        this(PermissionLevel.ASK);
    }

    // --- Accessors ---

    /** Returns the current permission level. */
    public PermissionLevel getLevel() {
        return level;
    }

    /** Updates the permission level. */
    public void setLevel(PermissionLevel level) {
        this.level = level;
    }

    // --- Decision logic ---

    /**
     * Returns {@code true} if the agent should ask the user for confirmation
     * before executing the named tool.
     *
     * <p>When the level is {@link PermissionLevel#ALLOW_ALL}, this always
     * returns {@code false}. When {@link PermissionLevel#NONE}, this returns
     * {@code true} (and execution should be blocked). When
     * {@link PermissionLevel#ASK}, this always returns {@code true}.</p>
     *
     * @param toolName the name of the tool being invoked
     * @return whether to ask for confirmation
     */
    public boolean shouldAsk(String toolName) {
        return switch (level) {
            case NONE      -> true;   // ask → block execution
            case ASK       -> true;   // genuinely ask
            case ALLOW_ALL -> false;  // never ask
        };
    }

    /**
     * Returns {@code true} if the tool is allowed to execute at all under
     * the current permission level.
     */
    public boolean isAllowed(String toolName) {
        return level != PermissionLevel.NONE;
    }

    @Override
    public String toString() {
        return "PermissionManager{level=" + level + "}";
    }
}
