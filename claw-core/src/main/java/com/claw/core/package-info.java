/**
 * Claw Core — the agent engine.
 *
 * <p>This package contains the core agent loop, conversation state management,
 * model types, and permission handling that form the heart of the Claw-Java
 * agent architecture. It is the Java port of Claude Code's internal agent
 * engine, implementing the turn loop, tool execution, and conversation
 * compaction mechanics.</p>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link com.claw.core.AgentLoop} — the core turn loop (port of {@code query.ts})</li>
 *   <li>{@link com.claw.core.QueryEngine} — orchestrator that wraps the loop with
 *       conversation lifecycle management</li>
 *   <li>{@link com.claw.core.AgentConfig} — immutable agent configuration record</li>
 *   <li>{@link com.claw.core.model.Conversation} — mutable conversation state</li>
 *   <li>{@link com.claw.core.model.Message} — a single message in the conversation</li>
 *   <li>{@link com.claw.core.permission.PermissionManager} — tool permission gating</li>
 * </ul>
 *
 * @see com.claw.core.model
 * @see com.claw.core.permission
 */
package com.claw.core;
