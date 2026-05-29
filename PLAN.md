# PLAN — claw-java Harness Engineering Roadmap

> 核心定位：不要把 claw-java 做成“Claude Code 功能复刻清单”，而要做成一个可观察、可回放、可评估、可中断、可控权限、可扩展工具生态的 **Agent Harness**。
>
> 判断标准：每加一个能力，都必须回答三个问题：
>
> 1. 它让 agent 更可控了吗？
> 2. 它让失败更容易定位了吗？
> 3. 它能被自动化测试或 eval 证明有效吗？

---

## 0. 当前阶段判断

claw-java 已经具备一个 agent harness 的雏形：

- core：`AgentLoop`、`Conversation`、`QueryEngine`、`PermissionManager`、`SubAgentDispatcher`
- provider：Anthropic / OpenAI / DeepSeek / DeepSeek-Anthropic
- tools：bash、read_file、write_file、edit、grep、find、web_fetch、dispatch_agents
- cli：JLine REPL、streaming token、session 保存与恢复、slash commands

下一阶段最有价值的方向不是继续堆更多工具，而是补齐 harness 的四条主线：

| 主线 | 目标 | 代表能力 |
|---|---|---|
| Observation Plane | 看得见 agent 怎么失败 | tracing、journal、run replay、debug UI |
| Control Plane | 控得住 agent 能做什么 | cancellation、permission、budget、retry、guardrails |
| Context Plane | 管得住模型看到什么 | token budget、context builder、compaction、message blocks |
| Evaluation Plane | 证明改动真的变好 | eval harness、golden tasks、failure injection、metrics |

这四条线比“多加几个 agent / 多接几个 provider / 多写几个工具”更有含金量。

---

## 1. 北极星目标

**每一次 agent run 都应该可以被观察、被中断、被复盘、被重放、被评估。**

最终希望做到：

```text
用户请求
  -> AgentRun(runId)
  -> ContextBuilder 构造上下文
  -> Provider 调用，产生流式事件
  -> ToolCall 被权限系统拦截或放行
  -> ToolExecutor 执行，输出结构化 ToolResult
  -> TraceSink / Journal 记录完整轨迹
  -> EvalHarness 可离线重放并打分
```

一个成熟的 claw-java 不应该只回答“它能不能跑”，而应该回答：

- 为什么调用了这个工具？
- 工具参数是否安全？
- 哪一步失败？
- 失败能否恢复？
- 重跑是否复现？
- 改动后成功率是否提升？
- 成本、token、耗时是否下降？

---

## 2. 优先级总览

| 优先级 | 方向 | 为什么含金量高 | 不做会怎样 |
|---|---|---|---|
| P0 | Trace / Journal / Replay | agent debug 的地基 | 失败只能看终端残留文本 |
| P0 | Cancellation + Error Taxonomy + Retry | 长任务和生产可用的地基 | Ctrl-C 无效、429/5xx 体验差、子 agent 泄漏 |
| P0 | Interactive Permission | 工具型 agent 的安全边界 | ASK 等于 NONE，无法安全执行 bash/edit |
| P0 | Eval Harness | 证明 harness 能力，而不是靠感觉 | 每次重构都不知道是否退化 |
| P1 | ContextBuilder + TokenBudget + SmartCompaction | 长上下文 agent 的核心能力 | 对话越长越脏，压缩丢事实 |
| P1 | Structured Tool Runtime | 工具可控、可测、可审计 | 工具输出只是字符串，难以自动判断 |
| P1 | Provider Decoupling + Capability Model | 多模型长期维护能力 | core/provider 相互污染，扩展 thinking/cache 困难 |
| P2 | Sub-agent Event Streaming + Run Tree | 多 agent 可观察、可中断 | 父 agent 只能阻塞等待字符串 |
| P2 | Workflow Primitives | 并行、pipeline、resume 的编排能力 | 复杂任务只能靠 prompt 硬凑 |
| P2 | MCP Tool Runtime | 工具生态扩展能力 | 只能维护内置工具，无法接企业系统 |
| P3 | Extended Thinking | provider 高级能力 | 没有通用 message/event 模型前容易做歪 |
| P3 | Prompt Caching / Cost Optimizer | 降本提速 | 没有 usage/trace 前收益不可量化 |
| P3 | TodoWrite / Task / CLI Polish | 使用体验增强 | 可以做，但不应盖过 eval/tracing |

---

## 3. 第一阶段：Observation Plane，先让 agent 可见

### 3.1 Run Event Model

**目标：** 把 agent run 从“黑盒循环”变成“事件流”。

新增核心类型：

```java
record AgentRunId(String value) {}
record StepId(String value) {}

sealed interface RunEvent permits
    RunStarted,
    UserMessageAppended,
    ContextBuilt,
    ProviderRequestStarted,
    AssistantTextDelta,
    ToolCallStarted,
    PermissionRequested,
    PermissionResolved,
    ToolCallFinished,
    RetryScheduled,
    CompactionStarted,
    CompactionFinished,
    RunCancelled,
    RunFailed,
    RunFinished {}
```

建议模块：

```text
claw-core/
  trace/
    RunEvent.java
    TraceSink.java
    JsonlTraceSink.java
    ConsoleTraceSink.java
    NoopTraceSink.java
    RunJournal.java
```

验收标准：

- 每次 `AgentLoop.run()` 生成唯一 `runId`
- 每个 provider call、tool call、permission check、retry、compact 都有事件
- 所有事件写入 `~/.claw-java/runs/<runId>.jsonl`
- sensitive fields 可脱敏，例如 API key、环境变量、Authorization header
- REPL 支持 `/debug last` 打印最近一次 run 的事件摘要

学习收益：

- 事件驱动架构
- 可观测性设计
- agent debugging
- 日志脱敏与审计

### 3.2 Replay 基础

**目标：** 让失败可以被复盘，而不是只靠人工猜。

最小实现：

```text
claw replay <runId> --dry-run
claw replay <runId> --from-step <stepId>
```

第一版不需要真的重新调用模型，可以先做到：

- 读取 journal
- 重建 run tree
- 展示每一步输入、输出、工具参数、错误
- 检查 message/tool_call/tool_result 是否成对
- 检查是否存在 orphan tool result、重复完成事件、丢失 permission 决策

验收标准：

- replay 可以定位一次失败 run 的最后成功 step
- replay 可以输出 markdown 版 debug report
- 单测覆盖：正常 run、tool error、provider error、cancelled run

---

## 4. 第二阶段：Control Plane，先修“能不能安全跑完”

### 4.1 Cancellation Token 全链路取消

**现状问题：** SSE 请求无 abort，Ctrl-C 后 HTTP 请求可能继续消耗资源；父 agent 中断后子 agent 仍可能继续运行。

目标设计：

```java
interface CancellationToken {
    boolean isCancellationRequested();
    void throwIfCancellationRequested();
    void onCancel(Runnable callback);
}
```

接入点：

- `AgentLoop.run(...)`
- `ProviderRequest`
- OkHttp SSE / HTTP call
- `ToolRegistry.execute(...)`
- `BashTool` process destroy
- `SubAgentDispatcher`
- REPL Ctrl-C handler

验收标准：

- Ctrl-C 能取消当前 provider call
- Ctrl-C 能终止正在运行的 bash process
- 父 agent 取消后，所有 sub-agent 收到 cancellation
- journal 中记录 `RunCancelled`，而不是普通失败

### 4.2 Error Taxonomy + Retry Policy

**目标：** 错误不再只是字符串或通用 exception，而是可分类、可恢复、可统计。

新增错误类型：

```java
sealed interface HarnessError permits
    RateLimitError,
    ProviderServerError,
    ProviderAuthError,
    ProviderBadRequestError,
    TokenOverflowError,
    ToolTimeoutError,
    ToolExecutionError,
    PermissionDeniedError,
    SchemaValidationError,
    CancelledError {}
```

Retry 规则：

| 错误 | 策略 |
|---|---|
| 429 | 读取 `Retry-After`，否则指数退避 |
| 5xx / network timeout | 有上限重试 |
| 401 / 403 / invalid model | fatal，不重试 |
| token overflow | 触发 compaction 后重试一次 |
| tool timeout | 不自动重试，交回模型判断 |
| schema validation | 可让模型修复一次 |
| cancelled | 不重试 |

验收标准：

- FakeProvider 可模拟 429、500、401、token overflow
- journal 记录每次 retry 的原因、等待时间、attempt
- eval report 统计 retry_count、fatal_error_count、recovered_error_count

### 4.3 Interactive Permission

**现状问题：** `PermissionLevel.ASK` 直接返回错误，等价于 NONE。

目标设计：

```java
record PermissionRequest(
    String toolName,
    Map<String, Object> input,
    RiskLevel risk,
    List<String> reasons
) {}

enum PermissionDecision {
    ALLOW_ONCE,
    DENY_ONCE,
    ALWAYS_ALLOW_RULE,
    ALWAYS_DENY_RULE
}
```

规则维度：

- tool：`bash`、`edit`、`write_file`、`web_fetch`
- path：项目内、项目外、隐藏目录、home 目录
- command prefix：`git status`、`mvn test`、`rm`、`sudo`
- network：公网、私网、localhost
- risk：LOW / MEDIUM / HIGH / CRITICAL

验收标准：

- REPL 中 `ASK` 会阻断等待用户输入：y / n / always / never
- permission rule 持久化到 `~/.claw-java/permissions.json`
- 高风险工具调用在 journal 里可审计
- 非交互模式默认拒绝高风险动作，除非显式 `--allow` 或配置规则

---

## 5. 第三阶段：Evaluation Plane，做真正的 eval harness

这是当前 PLAN 最大缺口，也是最有含金量的方向。

### 5.1 Eval Harness 目标

不要只测 Java 单元测试，要测 agent 行为：

```text
给定任务 + 文件夹 fixture + provider 行为 + permission 策略
运行 claw-java
检查 tool calls、文件 diff、最终回答、安全边界、token/step 成本
```

建议目录：

```text
evals/
  cases/
    edit_simple.yaml
    grep_then_patch.yaml
    permission_denied.yaml
    retry_429.yaml
    compact_preserve_facts.yaml
    cancel_subagents.yaml
  fixtures/
    tiny-java-project/
    broken-tests-project/
  expected/
    edit_simple.diff
```

### 5.2 Provider 测试替身

新增：

```text
FakeProvider        // 固定响应，适合单测
ScriptedProvider    // 按 step 返回 text/tool_call/error
RecordingProvider   // 录制真实模型返回，后续离线回放
FaultInjectProvider // 注入 429、5xx、截断 JSON、慢响应
```

这样 eval 可以不用每次花钱调模型，也能稳定复现 harness bug。

### 5.3 Eval Case 示例

```yaml
id: grep_then_patch
fixture: tiny-java-project
prompt: "找到 Calculator.add 的 bug，修复并运行测试"
provider: scripted
permission: ask_allow_safe
assert:
  must_call_tools:
    - grep
    - read_file
    - edit
    - bash
  must_not_call_tools:
    - web_fetch
  file_diff_matches: expected/grep_then_patch.diff
  final_text_contains:
    - "测试通过"
  max_steps: 12
  max_tool_errors: 1
  unsafe_actions_blocked: true
```

### 5.4 指标

每次 eval 输出：

```text
pass_rate
avg_steps
avg_provider_calls
avg_tool_calls
tool_call_accuracy
permission_block_rate
retry_recovery_rate
compaction_fact_preservation_rate
cancel_success_rate
avg_input_tokens
avg_output_tokens
estimated_cost
```

验收标准：

- `claw eval evals/cases --json` 可运行
- CI 中可跑 fake/scripted eval
- 真实模型 eval 可选，不阻塞 CI
- 每个新 harness 功能都至少新增一个 eval case
- README 里展示最近一次 eval report

学习收益：

- agent 行为测试
- failure injection
- benchmark 设计
- CI regression
- prompt / tool / harness 改动的量化比较

---

## 6. 第四阶段：Context Plane，做上下文工程而不是简单 compact

### 6.1 Message Block 模型

**目标：** 不要让 core message 被某个 provider 的协议绑死。

建议抽象：

```java
sealed interface MessageBlock permits
    TextBlock,
    ToolUseBlock,
    ToolResultBlock,
    ThinkingBlock,
    SummaryBlock,
    SystemNoteBlock {}

record HarnessMessage(
    Role role,
    List<MessageBlock> blocks,
    MessageMetadata metadata
) {}
```

Provider 负责把 `HarnessMessage` 编码成自己的 API 格式：

```text
HarnessMessage -> AnthropicCodec -> Anthropic JSON
HarnessMessage -> OpenAiCodec    -> OpenAI JSON
HarnessMessage -> DeepSeekCodec  -> DeepSeek JSON
```

验收标准：

- claw-core 不再直接依赖 provider 特定格式
- provider 层不再直接携带 core 内部可变 message 列表
- tool_use 和 tool_result 在转换时保持成对关系
- ThinkingBlock 可以存入 journal，但默认不回灌到下一轮 prompt，除非 provider 明确要求

### 6.2 ContextBuilder

**目标：** 每次请求前显式构造上下文，而不是直接复制整个 conversation。

```java
record ContextBuildRequest(
    Conversation conversation,
    TokenBudget budget,
    ProviderCapabilities capabilities,
    ContextPolicy policy
) {}

record ContextSnapshot(
    List<HarnessMessage> messages,
    TokenEstimate estimate,
    List<ContextDecision> decisions
) {}
```

ContextPolicy 规则：

- 永远保留 system prompt
- 永远保留当前用户请求
- 优先保留最近 N 轮
- 保留未解决 tool call / tool result
- 保留最近错误信息
- 保留 open todo
- 保留 pinned facts
- 历史中间段进入 summary
- 低价值大输出进入 artifact 文件，prompt 中只放摘要和引用

验收标准：

- 每次 provider request 前都有 `ContextBuilt` event
- event 里记录：保留了哪些消息、压缩了哪些消息、丢弃了哪些消息、原因是什么
- 支持 dry-run：`/context` 展示下一次请求会发送什么

### 6.3 SmartCompaction

现有 compaction 是“模型总结后替换全部历史”，风险是丢最近上下文、丢错误、丢 tool result。

改成三层策略：

| 层级 | 策略 | 适用场景 |
|---|---|---|
| L1 trim | 丢弃明显低价值旧消息 | 上下文轻微超预算 |
| L2 summarize middle | 压缩中间段，保留头尾 | 长会话 |
| L3 artifact offload | 大 tool output 落盘，只保留摘要 | bash/grep/web_fetch 输出过大 |

Summary 结构固定：

```text
## User Goal
## Current State
## Decisions Made
## Files / Symbols Mentioned
## Tool Results That Matter
## Errors / Failed Attempts
## Open Questions / Next Steps
## Pinned Facts
```

验收标准：

- compaction 后 token estimate 低于预算
- 最近 N 轮原文保留
- tool_use/tool_result 不被拆散
- 错误信息和当前任务状态保留
- eval 中 `compact_preserve_facts` 通过

---

## 7. 第五阶段：Structured Tool Runtime，让工具可控、可测、可审计

### 7.1 Tool 接口升级

现有 `Tool` 接口可以运行，但缺少风险、schema、权限、结构化结果。

建议改为：

```java
interface Tool {
    String name();
    String description();
    JsonSchema inputSchema();
    RiskAssessment assessRisk(JsonNode input, ToolContext context);
    ToolResult execute(JsonNode input, ToolContext context) throws ToolException;
}

record ToolContext(
    Path workspaceRoot,
    CancellationToken cancellationToken,
    PermissionManager permissionManager,
    TraceSink traceSink,
    Duration timeout
) {}

record ToolResult(
    boolean success,
    int exitCode,
    String stdout,
    String stderr,
    List<ArtifactRef> artifacts,
    boolean truncated,
    ToolError error,
    Duration duration
) {}
```

### 7.2 Tool Safety

优先修：

- `BashTool` 限制工作目录
- 禁止明显危险命令：`rm -rf /`、`sudo`、fork bomb、写系统目录
- 环境变量白名单
- stdout/stderr 分开
- 输出硬限制 + 截断标记
- `write_file` / `edit` 默认只允许 workspace 内
- `web_fetch` 保留私网拦截，并把网络访问纳入 permission

### 7.3 并行 Tool Calls

现状同轮 tool call 串行执行。可以改成：

- 同一 assistant turn 中多个独立 tool call 并发执行
- 对同一文件的写操作串行化
- bash/edit/write_file 默认互斥或按 path lock
- journal 保留原始 tool_call 顺序和实际完成顺序

验收标准：

- 多个 read_file/grep/find 可以并行
- 对同一路径的 edit/write 不并发
- cancellation 能取消所有并发工具
- tool result 顺序回灌给模型时可预测

---

## 8. 第六阶段：Provider Layer，做成能力适配层

### 8.1 Provider Capabilities

不要在业务逻辑里到处判断 Anthropic / OpenAI / DeepSeek。

```java
record ProviderCapabilities(
    boolean supportsToolUse,
    boolean supportsStreamingToolInput,
    boolean supportsThinking,
    boolean supportsPromptCaching,
    boolean supportsUsage,
    int maxInputTokens,
    int maxOutputTokens
) {}
```

目标：

- `ContextBuilder` 根据 capabilities 决定上下文策略
- `ProviderCodec` 根据 capabilities 编码 message blocks
- `AgentLoop` 不关心 provider 细节

### 8.2 公共 SSE / JSON Repair / Usage 抽取

优先提取：

- `repairTruncatedJson()` 公共实现
- SSE event parser 公共层
- usage extraction 标准化
- HTTP error -> HarnessError 映射
- request cancel handle

### 8.3 Extended Thinking 降级为 P3

Extended Thinking 有价值，但应该放在 message block、trace、provider capability 之后。

理由：

- 没有 `ThinkingBlock` 存储模型，容易污染下一轮上下文
- 没有 trace，无法判断 thinking 与 tool call 的关系
- 没有 provider capability，容易写成 provider-specific hack

验收标准：

- provider 支持时可接收 thinking delta
- thinking 存 journal，可选展示
- 默认不把 thinking 原样回灌给下一轮模型
- 不支持 thinking 的 provider 自动降级

---

## 9. 第七阶段：Sub-agent 与 Workflow，不要过早复杂化

### 9.1 先把 Sub-agent 变成 Run Tree

现状 `dispatch_agents` 会阻塞等待字符串合并。下一步应变成事件树：

```text
parent run
  step 4: dispatch_agents
    child run A
      tool: grep
      tool: read_file
    child run B
      tool: find
      tool: read_file
```

目标：

- 每个 sub-agent 有自己的 runId
- parent journal 引用 child runId
- child event 可实时流到 console
- parent cancellation 传播给 child
- child result 是结构化对象，不只是字符串

### 9.2 Workflow Primitives

不要先做大而全 DSL。先实现 Java API：

```java
Workflow.parallel(List<AgentTask> tasks)
Workflow.pipeline(List<T> items, List<Stage<T>> stages)
Workflow.withBudget(TokenBudget budget)
Workflow.withResume(Journal journal)
```

验收标准：

- parallel 有 barrier 和 partial failure 语义
- pipeline 支持 item-level progress
- journal 可跳过已成功 stage
- schema output 验证失败时可重试或报错

### 9.3 Task / TodoWrite

TodoWrite 和 Task 应该依赖 trace/context/eval，而不是先做。

TodoWrite：

- 存储在 conversation state，不只是 prompt 文本
- 每次 todo 变更产生 event
- compaction 必须保留 open todo
- eval 检查 agent 是否按 todo 推进任务

Task：

- 允许 agent 自主派发子任务
- 必须带 budget、deadline、permission profile
- 子任务结果必须 structured summary
- 父 agent 负责合成，不靠字符串拼接

---

## 10. 第八阶段：MCP，作为工具接入层，不是早期主线

MCP 很值得学，但在 claw-java 中应排在 Tool Runtime 之后。

原因：

- 没有 Tool schema / risk / permission，接 MCP 会扩大风险面
- 没有 trace，动态工具调用难 debug
- 没有 eval，无法判断 MCP 工具是否真的改善任务成功率

实现顺序：

1. stdio transport
2. tool list / schema 映射到 `ToolRegistry`
3. namespace：`mcp__serverName__toolName`
4. permission/risk 接入
5. streamable HTTP
6. session lifecycle
7. OAuth 作为最后阶段

验收标准：

- fake MCP server 单测通过
- MCP 工具调用出现在 journal
- MCP 工具同样走 permission、timeout、cancellation
- MCP 工具失败映射为标准 ToolError

---

## 11. CLI / REPL，服务于 harness 调试

CLI 增强不要只做“好看”，要服务可观测、可评估、可自动化。

优先级：

1. `claw -p "..."` 非交互执行
2. pipe：`cat task.md | claw -p`
3. `--json` 输出结构化结果
4. `--trace <runId>` 查看 run
5. `--debug` 展示 tool/provider/context 事件
6. `/context` 查看下次请求上下文
7. `/runs` 查看最近 run
8. `/replay <runId>` 复盘
9. `/eval <case>` 本地跑单个 eval
10. 工具执行视觉区分：pending / running / done / error

验收标准：

- 非交互模式可用于 CI
- `--json` 输出不混入彩色终端文本
- debug 模式展示 provider/tool/permission/context 事件
- 交互模式下 Ctrl-C 行为可预测：取消当前 run，而不是退出整个程序

---

## 12. 从旧 PLAN 到新 PLAN 的调整

| 旧计划项 | 新优先级 | 调整理由 |
|---|---|---|
| 智能 Compaction | P1 | 很重要，但要先有 ContextBuilder、Trace、Eval，否则无法证明没丢信息 |
| 错误分类重试 | P0 | 生产可用地基，应提前 |
| Extended Thinking | P3 | 模型特性，不是 harness 地基；先做 MessageBlock 和 ProviderCapabilities |
| Workflow 引擎 | P2 | 没有 trace/eval/cancellation 前，workflow 只会放大黑盒问题 |
| 交互式权限 | P0 | 工具 agent 安全边界，ASK 当前不可用，应立即修 |
| Token 预算缓存 | P1/P3 | TokenBudget 是 P1，Prompt Caching 是 P3；先能计量，再谈优化成本 |
| Sub-agent 流式进度 | P2 | 依赖 RunEvent / RunTree |
| MCP | P2 | 值得学，但要先有 Tool Runtime 和 Permission |
| TodoWrite / Task | P2/P3 | 依赖 state、trace、compaction、eval |
| Bash 安全 / 非交互模式 | P1/P2 | Bash safety 是 P1，非交互 CLI 是 P2 |

---

## 13. 建议的前 12 个 PR 顺序

### PR 1 — TraceEvent + JsonlTraceSink

交付物：

- `RunEvent` sealed hierarchy
- `TraceSink` 接口
- `JsonlTraceSink`
- AgentLoop 中记录 run started / finished / failed

验收：

- 每次 run 生成 jsonl
- 单测验证 event 顺序

### PR 2 — Provider / Tool / Permission 事件接入

交付物：

- provider request started / finished / failed
- tool call started / finished / failed
- permission requested / resolved

验收：

- 一次完整 tool run 能从 journal 复盘

### PR 3 — CancellationToken

交付物：

- core cancellation token
- provider cancel handle
- bash process destroy
- sub-agent cancellation propagation

验收：

- Ctrl-C 取消当前 run
- fake slow provider / sleep bash 单测通过

### PR 4 — HarnessError + RetryPolicy

交付物：

- 标准错误分类
- RetryPolicy
- provider HTTP 错误映射

验收：

- 429/5xx 可恢复
- 401/403 不重试
- retry 事件写入 journal

### PR 5 — Interactive Permission

交付物：

- PermissionRequest / PermissionDecision
- CLI ask prompt
- rules 持久化

验收：

- ASK 真正可交互
- 非交互模式默认拒绝高风险动作

### PR 6 — Eval Harness MVP

交付物：

- `claw eval`
- FakeProvider / ScriptedProvider
- eval YAML schema
- 5 个基础 eval case

验收：

- CI 可跑 eval
- 输出 pass_rate / avg_steps / tool_call_accuracy

### PR 7 — MessageBlock + ProviderCodec

交付物：

- `HarnessMessage`
- `MessageBlock`
- Anthropic/OpenAI/DeepSeek codec
- provider 与 core 解耦

验收：

- 现有 provider 测试通过
- tool_use/tool_result 成对验证通过

### PR 8 — ContextBuilder + TokenBudget

交付物：

- pre-flight token estimate
- context policy
- context snapshot event
- `/context`

验收：

- 超预算前可解释地裁剪/压缩上下文

### PR 9 — SmartCompaction

交付物：

- summarize middle
- pinned facts
- errors/open todos/recent turns 保留

验收：

- compaction eval 通过
- 不拆散 tool_use/tool_result

### PR 10 — Structured ToolResult + ToolContext

交付物：

- stdout/stderr/exitCode/duration/truncated/errorType
- tool schema validation
- tool risk assessment

验收：

- eval 可断言工具结果
- tool error 不再只是字符串

### PR 11 — Bash/File Safety

交付物：

- workspace jail
- env whitelist
- dangerous command blocklist
- path permission

验收：

- 越权写文件被拦截
- 危险 bash 被拒绝或要求确认

### PR 12 — Parallel Tool Execution

交付物：

- 同轮 read-only 工具并发
- path lock
- result order policy

验收：

- grep/read/find 并发
- edit/write 同路径串行
- cancellation 可终止全部并发工具

---

## 14. 推荐 eval case 清单

| Case | 目标 | 主要验证 |
|---|---|---|
| `edit_simple` | 修改单个 bug | read/edit/bash 顺序、diff 正确 |
| `grep_then_patch` | 先搜索再修改 | 工具选择与测试反馈循环 |
| `permission_denied_path` | 尝试写 workspace 外文件 | permission 是否拦截 |
| `dangerous_bash` | 模型尝试危险命令 | risk assessment 是否生效 |
| `retry_429` | provider 首次 429 | retry/recovery 是否正确 |
| `fatal_401` | provider auth error | 不应重试 |
| `tool_timeout` | bash sleep 超时 | timeout/error 回灌模型 |
| `compact_preserve_facts` | 长上下文压缩 | 关键事实不丢 |
| `orphan_tool_result_guard` | 错误消息结构 | message invariant 检查 |
| `cancel_subagents` | 父 run 取消 | 子 agent 是否一起取消 |
| `mcp_fake_tool` | 动态工具 | MCP 工具注册/权限/trace |
| `non_interactive_json` | CI 调用 | stdout JSON 是否干净 |

---

## 15. Definition of Done

每个 harness PR 合并前必须满足：

- 有单元测试
- 有至少一个 eval case，或解释为什么不适用
- journal 中能看到关键事件
- 失败路径有测试，不只测 happy path
- cancellation 行为明确
- permission / risk 行为明确
- 文档中写清楚新增能力如何 debug
- 不引入 provider-specific hack 到 core

---

## 16. 不建议现在重仓的方向

这些方向不是没价值，而是当前阶段投入产出比不如 P0/P1：

- 大而全 Workflow DSL
- 复杂 multi-agent 协商协议
- 过早实现 OAuth MCP
- 只为了兼容 Claude Code 而复制 TodoWrite/Task
- 在没有 trace/eval 的情况下做 prompt caching 优化
- 在没有 MessageBlock 的情况下硬接 Extended Thinking
- 增加更多 provider，但不统一错误、usage、capability、codec

---

## 17. 最有含金量的学习地图

按能力迁移价值排序：

1. **Trace / Journal / Replay**：任何 agent 系统都需要
2. **Eval Harness**：区分 demo 和工程系统的核心
3. **Context Engineering**：长任务 agent 的核心瓶颈
4. **Tool Runtime Safety**：企业 agent 落地必备
5. **Cancellation / Retry / Error Taxonomy**：生产稳定性基础
6. **Provider Capability Abstraction**：多模型系统长期维护能力
7. **Sub-agent Run Tree**：多 agent 可观察和可控的前提
8. **MCP Tool Runtime**：工具生态连接能力
9. **Workflow Primitives**：复杂任务编排能力
10. **Prompt Caching / Cost Accounting**：规模化后的成本优化

一句话：

> claw-java 下一步最应该学的不是“怎么多调几个工具”，而是“怎么把一次 agent 执行变成可观测、可中断、可复现、可评估、可治理的工程对象”。
