package com.claw.core.permission;

/**
 * Controls whether the agent must ask for user confirmation before
 * executing a tool.
 *
 * <p>The permission level gates tool execution in the agent loop:
 * <ul>
 *   <li>{@link #NONE} — no tools are allowed (read-only / sandbox mode)</li>
 *   <li>{@link #ASK} — the agent must prompt the user before each tool call</li>
 *   <li>{@link #ALLOW_ALL} — all tools execute without confirmation</li>
 * </ul>
 */
public enum PermissionLevel {
    /** No tool execution permitted. */
    NONE,
    /** Ask the user before every tool invocation. */
    ASK,
    /** Allow all tools without prompting. */
    ALLOW_ALL
}
