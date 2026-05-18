# Claw-Java Implementation Plan

> Java 21 port of the Claude Code agent architecture, distilled from the leaked TypeScript source.
> **Goal:** Educational Java project demonstrating the core agent architecture — turn loop, provider abstraction, tool system.
> **Architecture:** Maven multi-module: `claw-core` (agent engine), `claw-provider` (model abstraction), `claw-tools` (tool system), `claw-cli` (JLine REPL).
> **Tech Stack:** Java 21, Maven, OkHttp, Jackson, JLine, SLF4J

## Module Map (Mapped from Icarus603/claude-code packages)

| claw-java module | Icarus603 source | Purpose |
|-----------------|------------------|---------|
| `claw-core` | `packages/agent/query.ts`, `QueryEngine.ts` | Turn loop, conversation state, compaction |
| `claw-provider` | `packages/provider/` | Model API abstraction (Anthropic/OpenAI/DeepSeek) |
| `claw-tools` | `packages/tool-registry/` | Tool interface + registry + built-in tools |
| `claw-cli` | `packages/cli/`, `packages/repl/` | JLine REPL, command parsing, context wiring |

## Core Architecture Flow

```
User Input → ClawRepl → AgentLoop(query)
                           ↓
                   1. Build system prompt + context
                   2. Provider.complete(messages) → streaming response
                   3. Parse response: text OR tool_call
                   4. If tool_call → ToolRegistry.execute() → result
                   5. Append result to messages → goto 2
                   6. If text → output to user
                           ↓
                    Stream text to terminal
```

## Tasks

### Task 1: Create project skeleton
- Parent POM with modules
- Directory structure
- Git init + .gitignore

### Task 2: claw-core — data model
- Message, Conversation, ToolCall, ToolResult records
- AgentConfig (model, provider, permissions)

### Task 3: claw-core — AgentLoop (the heart)
- Stream-based turn loop
- Tool call dispatch → result → loop back
- Stop condition handling

### Task 4: claw-core — QueryEngine
- Conversation state management
- Token counting and compaction trigger
- File history snapshot

### Task 5: claw-provider — interface + registry
- Provider interface (SPI)
- ProviderRequest/ProviderResponse
- ProviderRegistry

### Task 6: claw-provider — Anthropic + OpenAI + DeepSeek
- AnthropicProvider (Messages API)
- OpenAiProvider (Chat Completions API)
- DeepSeekProvider

### Task 7: claw-tools — interface + registry
- Tool interface (name, description, parameters schema, execute)
- ToolRegistry (register, list, execute by name)

### Task 8: claw-tools — built-in tools
- BashTool (shell execution)
- ReadFileTool, WriteFileTool
- GrepTool

### Task 9: claw-cli — JLine REPL
- ClawApplication main class
- ClawRepl with JLine terminal
- Context wiring

### Task 10: README + push to GitHub
