# Roadmap

claw-java 与 Claude Code 的功能差距及待办事项。

## P0 — 体验质变

- [ ] **权限交互询问**：ASK 模式改为弹出确认框让用户批准/拒绝，而不是直接返回 error。涉及 `PermissionManager`、`AgentLoop`、JLine 交互提示
- [ ] **LLM 摘要 Compact**：`Conversation.compact()` 调用 LLM 对历史对话做摘要替换，而不是插入一句占位文本

## P1 — 效率提升

- [ ] **Sub-agent 并行**：启动多个子 Agent 并行搜索/分析，主 Agent 汇总结果。涉及 AgentLoop 嵌套、线程池、结果合并
- [ ] **Plan Mode**：`/plan` 命令让 Agent 先输出执行计划，用户审批后再动手改代码。类似 `EnterPlanMode` + `ExitPlanMode`
- [ ] **Todo 追踪**：Agent 自动分解任务、追踪进度，终端实时显示 todo 列表状态

## P2 — 生态扩展

- [ ] **IDE 集成**：VS Code 扩展，在编辑器内直接使用 claw，支持右键菜单、内联补全
- [ ] **MCP 协议**：实现 Model Context Protocol 客户端，连接外部工具服务器
- [ ] **Hook 系统**：PreToolUse / PostToolUse / Notification 钩子，用户在配置文件中注册自定义行为
- [ ] **技能系统**：`/@skill-name` 注册和调用自定义技能

## P3 — 体验打磨

- [ ] **记忆系统**：持久化记忆到 `~/.claw-java/memory/`，跨会话复用
  - `user` 类型：用户角色、偏好、知识背景
  - `feedback` 类型：编码约定、纠正记录、确认过的做法
  - `project` 类型：当前任务、目标、bug 线索
  - `reference` 类型：外部资源指针（文档链接、Slack 频道等）
  - 启动时自动加载 `MEMORY.md` 索引 + 上次对话的活跃记忆
  - 对话中 AI 自动判断何时写入/更新记忆，写入时更新索引
  - `/memory` 命令查看、搜索、删除记忆
  - 记忆关联：`[[other-memory-name]]` 交叉引用
  - 当前手动搭了架子（`MEMORY.md` + 3 个示例文件），需要代码化
- [ ] **对话分叉/回退**：`/undo` 回退最近一次操作，恢复到之前的对话状态
- [ ] **Diff 预览**：`edit` 和 `write_file` 执行前展示 diff，用户确认后再写入
- [ ] **ANSI 颜色/语法高亮**：终端输出带颜色，代码块语法高亮
- [ ] **Tab 补全**：命令补全、路径补全、模型名补全
- [ ] **图片支持**：`read_file` 支持读取图片，利用多模态模型理解截图
- [ ] **后台任务**：启动长时间任务并持续追踪状态

## P4 — 运维完善

- [ ] **Token 用量统计**：每轮对话显示 token 消耗和成本估算
- [ ] **自动更新**：`claw update` 拉取最新 release
- [ ] **沙箱执行**：bash 工具可选 sandbox 模式
- [ ] **更多 Provider**：Ollama 本地模型、Groq、Together AI
- [ ] **更多内置工具**：`ask`（反问用户）、`task`（创建子任务）、`notebook`（Jupyter 支持）

---

> 35 个 Java 文件 / 4100 行代码。记录了差距，有时间逐个消灭。
