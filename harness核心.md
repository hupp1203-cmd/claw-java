在大模型 agent 语境里，harness 可以理解成“把模型变成可执行 agent 的外部运行系统”。更口语一点：模型是“大脑”，harness 是让这个大脑能干活、能用工具、能记状态、能被监控、能被纠错的那套“身体 + 工作台 + 安全带 + 测试场”。

LangChain 最近把它概括成一个很直观的公式：Agent = Model + Harness；也就是说，裸模型本身不是完整 agent，模型之外的系统提示词、工具定义、文件系统、沙箱、状态管理、执行循环、上下文压缩、评估、日志、权限控制等，都可以算 harness。

1. harness 到底指什么？

中文里可以译成 “运行外壳”、“执行底座”、“智能体支架” 或 “测试/执行框架”。我更建议你脑子里把它理解成：

Harness = 模型之外，负责让 agent 稳定完成任务的一整套工程系统。

一个最简单的 agent loop 是：

用户目标
↓
模型思考
↓
选择工具
↓
执行工具
↓
观察结果
↓
继续思考 / 修正 / 结束

但真正生产可用的 agent 不能只有这个 loop。它还需要：

状态管理
上下文管理
工具权限
沙箱环境
失败重试
人工审批
日志追踪
评估指标
成本控制
安全边界

这些东西加起来，就是 agent harness。

举个例子，做一个代码 agent：

模块	是不是模型？	属于 harness 吗？
GPT / Claude / Gemini	是	否
system prompt	否	是
read_file / edit_file / bash 工具	否	是
Docker 沙箱	否	是
git diff / 测试运行器	否	是
失败后自动重试	否	是
trace 日志和评估报告	否	是
人工 approve 高风险改动	否	是

所以，agent 做得好不好，很多时候不只取决于模型强不强，而取决于 harness 搭得好不好。

2. 注意：harness 有两个常见含义

第一个是 Agent Execution Harness，也就是运行时 harness。它负责让 agent 真正执行任务，比如工具调用、状态持久化、上下文压缩、文件系统、子 agent、权限、安全、日志等。LangChain 的 Deep Agents 就明确把自己描述成一个更“带电池”的 agent harness，内置 filesystem、sub-agents、context management 和 skills 等能力。

第二个是 Evaluation Harness，也就是评测 harness。它负责用标准化环境测试模型或 agent 的表现。比如 SWE-bench 的 evaluation harness 会用 Docker 创建可复现环境，把模型生成的 patch 应用到真实代码仓库并运行测试，判断 issue 是否解决。 OpenAI Evals 也是类似方向：它提供框架来评估 LLM 或基于 LLM 构建的系统，并支持写自定义 eval。

你看到别人说 “agent harness”，大多数时候是在讲第一个：执行底座。看到 “eval harness” 时，则是在讲第二个：评测框架。

3. 它和 framework、prompt、RAG 的区别

Prompt engineering 主要关心：怎么把话说清楚。

Context engineering 主要关心：什么时候给模型什么信息。

RAG 主要关心：怎么检索外部知识并塞进上下文。

Agent framework 主要是工具库或编排框架，比如 LangGraph、OpenAI Agents SDK、AutoGen。

Harness 更像一个系统工程视角：
它不是单个库，而是你围绕模型搭出来的整套执行环境。

所以可以这么理解：

Prompt 是局部指令
RAG 是知识获取
Framework 是开发工具
Harness 是 agent 的完整运行系统

OpenAI Agents SDK 官方文档里提到的 agent loop、handoffs、guardrails、sessions、function tools、tracing 等能力，本质上都属于构建 agent harness 时会用到的核心部件。

4. 哪些东西最有“含金量”？

我按优先级排一下。真正值钱的不是会喊 “agent”，而是能把 agent 做到可控、可测、可恢复、可上线。

第一层：Agent Loop 和工具调用

这是入门但非常核心。你要理解：

LLM 如何决定调用哪个工具
工具参数如何结构化
工具返回结果怎么进入下一轮上下文
什么时候停止
怎么防止无限循环

很多人以为 agent 就是 “LLM + tools”，但其实工具接口设计会极大影响 agent 表现。工具描述不清、返回太长、错误信息不可修复，agent 很容易跑偏。

建议你练一个小项目：
做一个“文件问答 + 文件编辑 agent”，支持 read_file、search_file、edit_file、run_tests。这比只做聊天机器人有含金量得多。

第二层：状态、记忆和上下文管理

这是 agent 从 demo 到可用的关键。

你需要学：

短期上下文
长期记忆
任务状态
中间产物存储
上下文压缩
大工具结果落盘
会话恢复

LangGraph 的定位就是用于构建 stateful、多 actor、可持久执行的 agentic workflow，并强调 durable execution、human-in-the-loop 和状态持久化能力。

这部分很有含金量，因为长任务 agent 最大的问题通常不是“模型不会”，而是：

做到一半忘了目标
上下文越来越脏
工具结果太长
历史消息污染判断
失败后无法恢复

一个成熟 harness 要能回答这些问题：
“当前任务做到哪一步了？”
“哪些信息必须保留？”
“哪些信息应该压缩或丢弃？”
“崩了以后能不能从断点继续？”

第三层：沙箱、文件系统和执行环境

如果 agent 要写代码、跑 SQL、调用 shell、处理文件，就必须学执行环境。

重点包括：

Docker / sandbox
文件读写权限
命令执行限制
依赖安装
测试运行
超时控制
资源限制
副作用隔离

SWE-bench 的 harness 使用 Docker 是一个很典型的例子：目的不是酷，而是为了让模型生成的代码补丁在一致、可复现的环境里被评估。

这部分对做 coding agent、data agent、browser agent 都非常值钱。

第四层：Eval Harness，也就是评估系统

这是最容易被忽视、但最有工程含金量的方向之一。

你要学的不只是“跑 benchmark”，而是自己设计 eval：

任务集怎么构造
成功标准怎么定义
如何记录中间轨迹
如何评估工具调用是否正确
如何区分模型错误和工具错误
如何做回归测试
如何接入 CI/CD

OpenAI Evals 的核心价值就在于，可以用标准化方式测试模型或 LLM 系统，并能为自己的业务写 custom eval。

这块特别适合做作品集。比如你可以做：

一个客服 agent eval harness：
- 100 条真实/模拟用户问题
- 每条有期望动作
- 检查是否调用正确工具
- 检查是否泄露敏感信息
- 检查最终回答是否符合 policy
- 输出 pass rate、tool accuracy、latency、cost

这比“我会 LangChain”更有说服力。

第五层：Tracing、Observability 和 Debugging

Agent 很难 debug，因为它不是一次函数调用，而是一串动态决策。

你需要记录：

每次模型输入输出
每次工具调用
工具参数
工具返回
handoff 过程
guardrail 触发
token 成本
失败原因
重试路径
最终结果

OpenAI Agents SDK 的 tracing 会记录 agent run 中的 LLM generations、tool calls、handoffs、guardrails 和自定义事件，用于开发和生产环境中的调试、可视化和监控。

一个会做 tracing 的 agent 工程师，价值明显高于只会写 prompt 的人。

第六层：Guardrails、人审和权限控制

越接近生产，越要学这个。

重点包括：

输入检查
输出检查
工具调用审批
高风险动作拦截
敏感数据保护
权限分级
失败降级
人工介入

OpenAI 的 guardrails / human review 文档强调，可以对用户输入和 agent 输出做验证，并在需要时中断或进入人工审查流程。 Microsoft Agent Framework 也有 human-in-the-loop 机制，用于在 workflow 中处理人工请求与响应。

这块在企业里非常有含金量，因为真正的 agent 上线一定会遇到：

能不能让 agent 发邮件？
能不能让 agent 下单？
能不能让 agent 改数据库？
能不能让 agent 自动 merge PR？

答案通常不是简单的“能”或“不能”，而是要靠 harness 设计权限边界。

第七层：MCP 和工具生态

MCP，也就是 Model Context Protocol，现在很值得学。它的目标是给 LLM 应用和外部工具、数据源之间提供一种标准化连接方式。官方规范把 MCP 描述为连接 LLM 应用与外部数据源、工具的开放协议。

你可以把 MCP 理解成：

让 agent 以标准方式接入工具、资源和上下文的协议层

它和 harness 的关系是：
MCP 不是完整 harness，但它可以成为 harness 的工具接入层。

值得练的方向：

写一个 MCP server
暴露数据库查询工具
暴露公司文档搜索工具
暴露 GitHub / Jira / Notion 工具
给 agent 加权限和日志

这类项目很容易体现工程能力。

5. 推荐学习路线

我建议按这个顺序学：

1. 先写一个最小 agent loop
2. 加入结构化 tool calling
3. 加入状态管理和任务状态机
4. 加入文件系统 / sandbox
5. 加入 tracing 日志
6. 加入 eval harness
7. 加入 guardrails 和 human approval
8. 再学 LangGraph / Agents SDK / AutoGen / MCP

不要一上来就陷进框架对比。框架会变，但 harness 的核心问题长期不变：

怎么让模型拿到正确上下文？
怎么让模型安全调用工具？
怎么判断它做对了？
失败后怎么恢复？
怎么观察它为什么错？
怎么让它在生产环境可控？
6. 最值得做的几个作品集项目

最有含金量的不是“调一个 API”，而是做出可验证、可复现、可调试的 agent 系统。

项目 1：代码修复 agent + eval harness

功能：

读取 bug 描述
定位文件
修改代码
运行测试
失败后继续修复
输出 diff 和测试结果

重点体现：

文件工具
bash 工具
沙箱
测试反馈循环
trace 日志
成功率统计

这个项目最接近真实 coding agent。

项目 2：客服 agent + guardrails

功能：

识别用户意图
查询订单
调用退款工具
遇到高风险操作请求人工确认
拒绝越权请求
记录完整轨迹

重点体现：

工具权限
人工审批
输入/输出安全检查
业务 eval
可观测性

这个项目很适合企业应用方向。

项目 3：研究 agent + 引用评估

功能：

搜索资料
读取网页
提取证据
生成报告
检查引用是否支持结论

重点体现：

信息检索
来源引用
上下文压缩
事实一致性检查
反幻觉 eval

这个项目适合做 deep research 类 agent。

项目 4：MCP 工具服务器

功能：

实现一个 MCP server
提供 search_docs / query_db / create_ticket 等工具
agent 通过 MCP 调用
记录权限和日志

重点体现：

协议理解
工具抽象
企业系统集成
权限边界

这类能力在实际工作里很吃香。

7. 哪些东西不太值得重仓？

几个容易浪费时间的方向：

疯狂背各种 agent 框架 API
做很多没有评估的 demo
只研究 prompt 模板
盲目堆 multi-agent
只看 benchmark 排行榜

尤其是 multi-agent，不要为了多 agent 而多 agent。很多任务单 agent + 好 harness 就够了。Anthropic 的 agent 实践文章也提醒，框架能简化调用模型、定义工具、链式调用等低层任务，但构建可靠 agent 更重要的是理解工作流和系统设计，而不是迷信复杂框架。

8. 一句话总结

Harness 在大模型 agent 里，就是模型之外那套让 agent 能执行、能观察、能验证、能纠错、能安全上线的工程系统。

真正有含金量的学习方向是：

工具调用设计
状态和上下文管理
沙箱执行
评估 harness
tracing / observability
guardrails / human-in-the-loop
MCP 工具生态
生产级 agent 架构

你以后看到一个 agent 项目，可以直接问它 6 个问题：

它怎么拿工具？
它怎么管状态？
它怎么防跑偏？
它怎么知道做对了？
它失败后怎么恢复？
它上线后怎么 debug？

能回答清楚这些问题的，就是在做真正的 harness engineering。