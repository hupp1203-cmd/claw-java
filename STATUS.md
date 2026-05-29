# Claw-Java 现状分析

v1.1.0-SNAPSHOT | 37 个 Java 文件 | ~5100 行 Java 源码 | 4 模块

---

## 一、已实现功能

### 1.1 核心 Agent 循环 (claw-core)

| 功能 | 位置 | 说明 |
|------|------|------|
| Agent 主循环 | `AgentLoop.run()` | 发送消息 → 解析响应 → 执行工具 → 追加结果 → 循环，默认最高 25 轮 |
| Streaming 流式响应 | `AgentLoop.run()` → ProviderCallback | `Consumer<String> onToken` 回调贯穿全链路，token 实时输出到终端 |
| Sealed 类型响应 | `AgentLoop.LoopResponse` | `Text` \| `ToolUse`，消除循环与 Provider 间的 JSON 往返 |
| 最大轮次处理 | `AgentLoop.run()` | 达上限后注入提示消息，强制执行剩余工具，再做一次最终文本调用 |
| Conversation 管理 | `Conversation` | 消息追加、system prompt 管理、trim 式 compact（保留首条 + 最近 6 条） |
| 模型驱动 Compaction | `QueryEngine.compactWithSummary()` | 用模型生成 5 段结构化总结，替换整个对话历史 |
| Compaction 触发 | `QueryEngine.chat()` | 每次对话前检查 `shouldCompact()`（默认阈值 100K 字符） |
| Token 计数 | `TokenCounter` | jtokkit CL100K_BASE 编码，回退 charCount/4 估算 |
| 多 Provider | `Provider` SPI + `ProviderRegistry` | Anthropic / OpenAI / DeepSeek / DeepSeek-Anthropic，按 API key 自动发现 |
| Virtual Thread | 工具执行 | 工具调用在 virtual thread 中执行，超时 Future.get(timeout) |

### 1.2 Provider 层 (claw-provider, 4 个实现)

| Provider | 端点 | 模型 |
|----------|------|------|
| `AnthropicProvider` | api.anthropic.com/v1/messages | claude-sonnet/opus/haiku-4 |
| `OpenAiProvider` | api.openai.com/v1/chat/completions | gpt-4o, gpt-4o-mini, gpt-4-turbo, o4-mini, o3-mini |
| `DeepSeekProvider` | api.deepseek.com/v1/chat/completions | deepseek-v4-pro, deepseek-v4-flash |
| `DeepSeekAnthropicProvider` | api.deepseek.com/anthropic/v1/messages | deepseek-v4-pro[1m], deepseek-v4-flash |

**通用能力：**
- SSE 流式解析（OkHttp SSE client）
- 增量累积 text_delta / input_json_delta
- 截断 JSON 自动修复（`repairTruncatedJson`）
- CompletableFuture 桥接异步→同步
- `AtomicBoolean` race guard 防重复完成信号
- 响应格式自动检测（`ProviderResponse.parse`）

### 1.3 工具系统 (claw-tools, 7 个 + 1 个 CLI 注册)

| 工具 | 文件 | 核心能力 |
|------|------|----------|
| `bash` | `BashTool` | ProcessBuilder 执行命令，30s 超时，50KB 输出上限 |
| `read_file` | `ReadFileTool` | 读文件 + 行号，支持 offset/limit，默认 500 行 |
| `write_file` | `WriteFileTool` | 写文件，自动创建父目录 |
| `edit` | `EditTool` | 精确字符串替换，拒绝 0 和多个匹配 |
| `grep` | `GrepTool` | 正则搜索，file glob 过滤，跳过二进制，上限 200 条 |
| `find` | `FindTool` | Glob 文件匹配，跳过隐藏目录，上限 200 条 |
| `web_fetch` | `WebFetchTool` | HTTP fetch，HTML 剥离，私网地址拦截 |
| `dispatch_agents` | `DispatchTool` | 并行分发子 agent，最多 10 个，4 分钟超时 |

**工具基础设施：** `Tool` 接口（6 个方法）、`ToolRegistry`（ConcurrentHashMap + virtual thread + 超时保护）

### 1.4 Sub-agent 系统 (claw-core + claw-cli)

| 类 | 职责 |
|-----|------|
| `SubAgent` | 独立 Conversation + AgentLoop，隔离执行单个任务 |
| `SubAgentDispatcher` | Virtual thread 池并发执行，全局 deadline 5 分钟，per-agent 超时 |
| `SubAgentResult` | 结果 record，含 agentName / task / result / error / success |
| `DispatchTool` | 注册为 `dispatch_agents` 工具，主 agent 可调用 |
| `DispatchAgentsIntegrationTest` | 集成测试：并行 2 个 sub-agent 验证结果含预期内容 |

### 1.5 CLI / REPL (claw-cli)

| 功能 | 说明 |
|------|------|
| JLine REPL | jansi ANSI 支持，fallback dumb terminal |
| 多行输入 | 行尾 `\` 续行 |
| Slash 命令 | `/exit` `/quit` `/help` `/tools` `/clear` `/resume` `/compact` `/model` |
| 实时 token 输出 | `onToken` 回调流式打印 |
| Session 持久化 | JSON 保存在 `~/.claw-java/sessions/` |
| Session 恢复 | `/resume [id]` 列出或恢复，支持精确 + 前缀 ID 匹配 |
| 自动保存 | 每轮对话后 best-effort 保存 |
| 模型切换 | `/model <name>` 运行时切换（**会丢失对话历史**） |
| Manual DI | `ClawContext.createDefault()` 无框架组装 |

### 1.6 配置与权限

| 组件 | 说明 |
|------|------|
| `ClawConfig` | 环境变量 → `~/.claw-java/config` → `./.claw-java/config`，首次运行自动创建模板 |
| `PermissionLevel` | NONE / ASK / ALLOW_ALL 三级 |
| `PermissionManager` | shouldAsk() / isAllowed()，在 AgentLoop 执行前门控 |

---

## 二、设计/实现缺陷

### Bug 级

1. **ASK 模式无交互** — `AgentLoop.java` 中 `shouldAsk()` 为 true 时直接返回错误字符串，不提示用户，等于 NONE
2. **/model 切换丢失对话** — `switchModel()` 创建新 QueryEngine，旧 Conversation 丢弃
3. **Compaction 全量替换** — `compactWithSummary()` 替换全部历史，不保留最近几轮
4. **同轮工具串行** — 多个 tool call 用 for 循环串行执行，未利用并行
5. **JSON repair 代码重复** — 同一段 `repairTruncatedJson()` 在 `AnthropicProvider` 和 `OpenAiCompatibleProvider` 中各一份
6. **无请求取消** — SSE 流无 abort API，Ctrl-C 后 HTTP 请求继续消耗资源
7. **Sub-agent 无取消传播** — 父 agent 中断后子 agent 继续运行

### 设计问题

8. **`QueryEngine.resume()` 空占位** — 直接 throw `UnsupportedOperationException`
9. **`buildModelMessages()` 无缓存** — 每次调用 O(n) 复制 + system prompt 前置
10. **系统提示词硬编码** — `ClawContext.createDefault()` 中约 20 行提示词不可配置
11. **Provider 层耦合 core Message** — `ProviderRequest` 直接携带 `List<Message>`（claw-core 类型），导致模块间编译依赖
12. **`DeepSeekAnthropicProvider.buildRequest()` 手动索引遍历** — while + index 处理 message 合并，多次 `messages.get(i)`，脆弱

---

## 三、核心能力缺口

按含金量排序，详见 [PLAN.md](PLAN.md) 中的演进路线。

| 优先级 | 模块 | 说明 |
|--------|------|------|
| P0 | 智能 Compaction | 增量压缩、自动触发、优先级保留 |
| P0 | 错误分类重试 | 429/5xx/4xx 分类处理，指数退避 |
| P1 | Extended Thinking | 新的消息类型 + 回环正确处理 |
| P1 | Workflow 引擎 | pipeline/parallel/phase/resume/budget/schema |
| P1 | 交互式权限 | REPL 中 y/n/always/never，细粒度规则 |
| P2 | Token 预算 + Caching | pre-flight 计数、cache_control breakpoint、usage 提取 |
| P2 | Sub-agent 流式进度 | 实时流回 + 流式句柄 |
| P2 | MCP | 动态工具注册、多传输、命名空间 |
| P3 | TodoWrite / Task 工具 | 任务列表管理、自主子任务 |
| P3 | Bash 安全 / 非交互模式 | 沙箱、-p 参数、pipe |

---

## 四、模块改进要点

### claw-core
- [ ] AgentLoop: 工具并行执行、错误分类、thinking block 处理
- [ ] QueryEngine: 实现 resume()、增量 compaction
- [ ] Conversation: 增量 compact 策略、cache 断点标记
- [ ] TokenCounter: provider 特定 tokenizer（非通用 CL100K）
- [ ] PermissionManager: 细粒度规则 + 持久化

### claw-provider
- [ ] 提取公共 `repairTruncatedJson()`
- [ ] 支持 thinking block 序列化/反序列化
- [ ] 支持 Anthropic prompt caching breakpoint
- [ ] Retry 逻辑（429/5xx/4xx 分类）
- [ ] 解耦 core Message 依赖
- [ ] 请求取消/abort 机制

### claw-tools
- [ ] 新增 TodoWrite 工具
- [ ] 新增 Task 工具
- [ ] BashTool: 目录限制、命令拦截、环境变量白名单
- [ ] Tool 接口增加 `needsApproval()` 方法

### claw-cli
- [ ] 非交互模式（`-p` 参数 + pipe）
- [ ] 交互式权限提示
- [ ] 工具执行可视化
- [ ] /model 切换保留对话历史
- [ ] system prompt 可配置化
- [ ] --verbose / --debug 启动参数

---

## 五、架构总览

```
claw-cli/     ClawApplication → ClawRepl → ClawContext（手动 DI）
claw-core/    AgentLoop（核心循环）、QueryEngine（编排）、Conversation、TokenCounter、PermissionManager、ClawConfig、SubAgent、SubAgentDispatcher
claw-provider/ Provider SPI、AnthropicProvider、OpenAiCompatibleProvider、DeepSeekProvider、DeepSeekAnthropicProvider
claw-tools/   Tool 接口、ToolRegistry、BashTool、ReadFileTool、WriteFileTool、EditTool、GrepTool、FindTool、WebFetchTool
```

```
CLI → 单 AgentLoop → Provider (4 个实现)
                   → ToolRegistry → 7 个内置工具
                   → SubAgentDispatcher → N × SubAgent (virtual thread 并发)
```
