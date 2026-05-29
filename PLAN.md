# PLAN — claw-java Agent/Harness 演进路线

对照 Claude Code 的 agent 架构，按含金量排序的缺口与实施路线。

---

## 一、核心 Agent 引擎

### 1.1 智能 Compaction（增量 + 自动触发）

**现状：** 模型写一次总结 → 替换整个对话。需用户手动 `/compact`。

**目标：**
- 增量压缩：保留最近 N 轮 + 压缩中间部分（不丢 system prompt、最近的 tool 结果、错误信息）
- 基于 token 预估自动触发（发请求前 pre-flight 计数，超阈值自动 compact）
- 多策略渐进：trim → summarize → trim+summarize 组合

### 1.2 Extended Thinking 建模

**现状：** `DeepSeekAnthropicProvider` 显式禁止 thinking，注释标明 "message model doesn't store thinking blocks"。

**目标：**
- 新增 `ThinkingBlock` 消息类型，扩展 `Message` sealed 层级
- ProviderResponse 的 `Text | ToolCall` 扩展为 `Text | ToolCall | Thinking`
- 工具调用回环中正确处理 thinking block（存下来，下次 assistant 消息不包含它）
- Anthropic / DeepSeek Anthropic 协议均支持

### 1.3 Token 预算与 Prompt Caching

**现状：** 无 pre-flight token 计数，无 Anthropic cache_control breakpoint。

**目标：**
- 每次请求前做 token 预估，决定是否需要 compact
- Anthropic prompt caching：system prompt + 最近 N 轮 tool results 标记 `cache_control`
- 流式结束后提取 `usage` 信息（`input_tokens` / `output_tokens`），累计会话成本
- 支持 `+500k` 式的 token 预算指令，循环中做 remaining 判断

### 1.4 错误分类与重试

**现状：** `AgentLoopException` 不区分可重试/致命。

**目标：**
- 429 rate-limit → 指数退避重试（从响应头读 `retry-after`）
- 5xx server error → 最多 3 次重试
- 4xx auth/param error → 致命，直接抛
- token overflow → 自动 compact 后重试，二次失败则报错

---

## 二、多 Agent 编排

### 2.1 Workflow 编排引擎

**现状：** 只有 `SubAgentDispatcher` — 纯并发 + 字符串合并，无编排逻辑。

**目标：**
- `pipeline(items, stage1, stage2, ...)` — item 独立流过 stage 链，不互相阻塞
- `parallel(thunks)` — barrier 模式，等待全部完成后聚合
- Phase 分组 + live 进度树展示
- Resume/缓存：相同 (prompt, opts) 从 journal 回放，只跑变更部分
- Token budget 感知：循环条件 `while (budget.remaining() > 50_000)`
- Schema 约束：`agent(prompt, {schema: JSON_SCHEMA})` → StructuredOutput → 验证后返回

### 2.2 Sub-agent 流式进度

**现状：** SubAgent 完全黑盒，父 agent 调用 dispatch 后阻塞等待合并结果。

**目标：**
- Sub-agent 的工具执行结果实时流回父 agent 上下文
- 父 agent 等待期间看到子 agent 实时操作（"正在读文件...", "正在 grep..."）
- `dispatch_agents` 返回流式句柄而非最终字符串

### 2.3 Sub-agent 间通信与上下文共享

**现状：** Sub-agent 相互隔离，无共享上下文。

**目标：**
- 共享只读上下文（项目文件、conversation 前缀）
- 结果聚合不靠字符串拼接，靠模型驱动的合成
- 父 agent 可中断/取消子 agent

---

## 三、平台与协议

### 3.1 MCP (Model Context Protocol)

**现状：** 不支持。

**目标：**
- 动态工具注册：运行时从 MCP server 获取 tool schema，注入 ToolRegistry
- 多传输支持：stdio 子进程、HTTP SSE、streamable HTTP
- 命名空间隔离：`mcp__serverName__toolName`
- OAuth 2.0 认证流

### 3.2 交互式权限系统

**现状：** `PermissionLevel.ASK` 返回错误信息，无交互式提示。

**目标：**
- REPL 中阻断等待用户输入（y/n/always/never）
- 按工具类型、路径模式、命令前缀做细粒度规则
- 规则持久化到 `~/.claw-java/permissions.json`

### 3.3 工具使用流式显示

**现状：** 工具参数在 provider 层累积完毕后一次性传递，用户看不到工具调用过程。

**目标：**
- 流式解析工具参数（`input_json_delta` 实时传递到 REPL）
- 显示 "Tool: bash Running... → Done" 进度
- 工具输出截断展示 + 展开能力

---

## 四、工具系统

### 4.1 TodoWrite 工具

**现状：** 无。

**目标：** 实现 Claude Code 兼容的 TodoWrite 工具，支持 merge/overwrite 模式和状态流转。

### 4.2 Task 工具（自主子任务）

**现状：** 无。dispatch_agents 要求父 agent 显式指定子 agent 的完整 task。

**目标：** Task 工具允许 agent 自主创建子任务，子 agent 独立完成一个封闭目标后回报。

### 4.3 Bash 安全增强

**现状：** 无沙箱、无目录限制、无环境变量过滤。

**目标：**
- 工作目录限制（禁止逃逸到项目外）
- 危险命令拦截（rm -rf /、sudo 等）
- 环境变量白名单
- 输出大小硬限制 + 截断标记

---

## 五、REPL / CLI

### 5.1 非交互模式

**现状：** 只能交互式 REPL。

**目标：**
- `claw -p "refactor this file"` 一次性执行
- pipe 模式：`echo "explain this" | claw`
- `--model` / `--verbose` / `--debug` 启动参数

### 5.2 工具执行可视化

**现状：** REPL 不显示工具执行过程。

**目标：** 展示每个 tool call 的名称、参数摘要、执行状态（pending → running → done/error），流式输出阶段和工具执行阶段视觉区分。

---

## 实施优先级

| 优先级 | 模块 | 含金量 |
|--------|------|--------|
| P0 | 1.1 智能 Compaction | 直接改善长对话质量 |
| P0 | 1.4 错误分类重试 | 减少无响应/丢失上下文 |
| P1 | 1.2 Extended Thinking | 释放模型推理能力 |
| P1 | 2.1 Workflow 引擎 | 多 agent 编排核心 |
| P1 | 3.2 交互式权限 | 安全 + 用户体验 |
| P2 | 1.3 Token 预算缓存 | 降本 + 长会话 |
| P2 | 2.2 Sub-agent 流式进度 | 多 agent 可见性 |
| P2 | 3.1 MCP | 工具生态扩展 |
| P3 | 4.x 工具补充 | 功能完整性 |
| P3 | 5.x CLI 增强 | 使用场景覆盖 |
