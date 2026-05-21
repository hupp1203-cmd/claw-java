# CLAUDE.md

Claw-Java — Claude Code 架构的 Java 21 移植版。

## 技术栈

- Java 21, Maven 3.8+, OkHttp 4.12, Jackson 2.18, JLine 3.27, jtokkit 1.1
- 多模块 Maven 项目：`claw-core`, `claw-provider`, `claw-tools`, `claw-cli`

## 构建与运行

```bash
mvn install -q -DskipTests     # 编译
claw                            # 启动（需要 ~/.claw-java/config 里有 API key）
claw -d /path/to/project       # 指定工作目录
```

## 模块架构

```
claw-cli/     ClawApplication → ClawRepl → ClawContext（手动 DI）
claw-core/    AgentLoop（核心循环）、QueryEngine（编排）、Conversation、TokenCounter、PermissionManager、ClawConfig
claw-provider/ Provider SPI、AnthropicProvider、OpenAiCompatibleProvider → DeepSeek/OpenAI
claw-tools/   Tool 接口、ToolRegistry、7 个内置工具
```

## 关键文件

| 文件 | 说明 |
|------|------|
| `claw-core/.../AgentLoop.java` | 核心循环，最重要的文件 |
| `claw-core/.../QueryEngine.java` | 编排层 |
| `claw-core/.../model/Conversation.java` | 会话管理 + compact |
| `claw-provider/.../OpenAiCompatibleProvider.java` | SSE 流式 + 同步 API |
| `claw-cli/.../ClawContext.java` | 手动 DI，桥接所有模块 |
| `claw-cli/.../ClawRepl.java` | JLine REPL |
| `claw-cli/.../SessionStore.java` | 会话持久化 |
| `claw-core/.../ClawConfig.java` | `~/.claw-java/config` 加载 |

## 编码约定

- Java 21：record、sealed interface、switch pattern matching、virtual threads
- 手动 DI（ClawContext），不用框架
- 日志用 SLF4J，不要 println
- 工具执行用 virtual thread + 超时保护
- 流式响应要有 race guard（AtomicBoolean done）
- 工具执行超时：BashTool 30s，WebFetchTool 30s，其他默认

## 当前状态

- v1.1.0-SNAPSHOT，35 个 Java 文件，约 4100 行
- 最新提交：`6a1ffbd` docs: add ROADMAP.md
- 下一步见 ROADMAP.md，P0 优先
