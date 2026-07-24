# 《AI大模型Ragent项目》——MCP之官方Java-SDK深度解析

> 原文链接：https://t.zsxq.com/QvKb8
> 所属分组：01-rag-fundamentals RAG与大模型基础概念

## 一句话概括

这篇文章回答的问题是：当你用 Spring AI 的 `@McpTool`/`@McpResource`/`@McpPrompt` 注解几行代码就跑起一个 MCP Server 时，注解背后的官方 MCP Java SDK 到底做了什么——文章拆开了 SDK 的模块划分、四层架构（Schema/Transport/Session/Client-Server），并用不依赖 Spring 的纯 SDK 代码重新实现了一遍此前的年假查询/订单查询工具，让读者看清 Spring AI 到底替开发者省下了哪些工作量。

## 核心内容提炼

- **modelcontextprotocol 组织与开放标准定位**：GitHub 上的 `modelcontextprotocol` 组织由 Anthropic 主导成立，下辖协议规范仓库 `modelcontextprotocol`（所有 SDK 的实现基准）、三个语言 SDK（`java-sdk`/`typescript-sdk`/`python-sdk`）以及社区 Server 集合仓库 `servers`。MCP 从设计上就是公开协议规范 + MIT 许可证 + 任何人可提 PR 的开放标准，文章拿这个和 OpenAI 单方面定义、闭源在 API 里的 Function Call 协议做对比，强调 MCP 的目标是成为大模型工具调用领域的通用协议。三个 SDK 分工不同：TypeScript SDK 最早跑通协议全流程、是参考实现（Reference Implementation）；Python SDK 在 AI/ML 生态用得最广；Java SDK 面向企业级应用，和 Spring AI 深度集成。Java SDK 当前稳定版本 1.1.0。

- **Java SDK 的 6 个 Maven 模块及其取舍**：`mcp-bom`（统一管理各模块版本号的 BOM）、`mcp-core`（Client/Server/Transport/Schema 核心实现全在这）、`mcp-json-jackson2`/`mcp-json-jackson3`（两个并列的 JSON 序列化实现）、`mcp`（便捷包 = `mcp-core` + `mcp-json-jackson3`，多数场景只引入这一个依赖就够）、`mcp-test`（测试工具）。
  - **为什么 JSON 序列化单独拆两个模块**：根源是 Jackson 生态的大版本分裂——Jackson 2.x 是 Spring Boot 2.x 时代标配、目前仍大量项目在用；Jackson 3.x 是 2025 年发布的新大版本，包名从 `com.fasterxml.jackson` 改成 `tools.jackson`，不向后兼容。SDK 把序列化抽象成 `McpJsonMapper` 接口，具体实现可插拔：老项目用 Jackson 2 就引 `mcp-core + mcp-json-jackson2` 避免依赖冲突，新项目直接用 `mcp` 便捷包走 Jackson 3 默认实现。这是"核心模块定义接口、具体实现按版本拆开"的常见 Java 生态设计模式。
  - **Spring 集成模块已经迁出 SDK 仓库**：0.18.1 及更早版本里还有 `mcp-spring-webflux`/`mcp-spring-webmvc` 两个模块，从 1.0.0 开始迁移到了 Spring AI 2.0 里，Maven 坐标从 `io.modelcontextprotocol.sdk:mcp-spring-*` 变成 `org.springframework.ai:mcp-spring-*`。这解释了为什么用 Spring AI 写 MCP Server 时，pom 里引入的依赖是 `org.springframework.ai` 而不是 `io.modelcontextprotocol.sdk`——SDK 本身保持轻量、只做协议的纯 Java 实现不绑定任何框架，Spring 集成完全由 Spring AI 团队维护，和 Spring Boot 自动配置/生命周期深度绑定，两边各管各的。

- **SDK 内部四层架构（自下而上）**：
  1. **Schema 层**：`McpSchema` 是 SDK 里最大的一个类（原文明确给出 2786 行代码），把协议规范里定义的所有消息类型都实现成了 Java 16+ 的 Record 不可变数据类，相当于协议的"字典"。常用类型包括 `McpSchema.Tool`（工具定义：名称+描述+参数 JSON Schema）、`McpSchema.CallToolRequest`/`CallToolResult`（对应 `tools/call` 请求/响应）、`McpSchema.Resource`、`McpSchema.Prompt`、`McpSchema.InitializeRequest`/`InitializeResult`（握手请求/响应）。这些类型最终被包装进标准 JSON-RPC 2.0 的 Request/Response 结构传输（比如工具调用最终发出的是 `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{...}}`，`params` 就是 `CallToolRequest` 序列化的结果）。
  2. **Transport 层**：只负责把 JSON-RPC 消息从一端送到另一端，不关心消息内容语义。SDK 提供三种实现：
     - **Stdio**：通过子进程的 stdin/stdout 传消息，每条消息是一行 JSON、换行分隔；Claude Desktop 本地集成用的就是这种方式（启动你的 Java 进程，通过 stdin 发请求、stdout 读响应）。关键类：`StdioClientTransport`（客户端）、`StdioServerTransportProvider`（服务端）。
     - **SSE**：因为 SSE 本身只能服务端单向推送，MCP 的 SSE Transport 实际走两条路——Server→Client 走 SSE 长连接推事件，Client→Server 走独立的 HTTP POST。关键类：`HttpClientSseClientTransport`（基于 JDK 内置 HttpClient，客户端）、`HttpServletSseServerTransportProvider`（基于 Jakarta Servlet，服务端）。
     - **Streamable HTTP**：SSE 的进化版，双向都走 HTTP，请求和响应都可流式传输，不需要预先建一条常驻 SSE 长连接，按需建立即可；这是 MCP 协议较新引入、官方推荐的远程传输方案。关键类：`HttpClientStreamableHttpTransport`（客户端）、`HttpServletStreamableServerTransportProvider`（服务端）。
     - 选型经验：本地开发调试用 Stdio，生产远程部署用 Streamable HTTP，SSE 是兼容性较好的过渡方案（三者对比详见下方"设计取舍"表格化描述）。
  3. **Session 层**：管理一次完整连接的生命周期，做三件事——维护连接状态（是否已初始化/已关闭）、按 JSON-RPC 请求 id 把响应路由回正确的等待请求（因为可能同时有多个请求在飞）、处理不需要响应的单向通知消息（如工具列表变更通知）。关键类：`McpClientSession`（客户端）、`McpServerSession`（服务端）。
  4. **Client/Server 层**：开发者实际打交道的 Builder API。`McpServer.sync(transportProvider)`/`McpServer.async(transportProvider)` 创建同步/异步 Server，链式调用 `.serverInfo(...)`、`.tool(toolDef, handler)` 等注册工具；`McpClient.sync(transport)`/`McpClient.async(transport)` 创建 Client，典型调用序列是 `client.initialize()`（握手）→ `client.listTools()`（发现工具）→ `client.callTool(request)`（调用）→ `client.closeGracefully()`（关闭）。

- **同步 vs 异步 API 的实现关系**：`McpSyncClient`/`McpSyncServer` 和 `McpAsyncClient`/`McpAsyncServer` 是两套并列 API，异步版本基于 Project Reactor，返回 `Mono<T>`/`Flux<T>`。关键细节：**同步 API 内部就是对异步 API 做了一层 `.block()` 封装**，底层是同一套实现代码，不是两套独立逻辑。选型建议：传统阻塞式 Spring MVC 项目用同步 API 简单直观；Spring WebFlux 或需要处理大量并发连接的场景用异步 API；不确定就先用同步，遇到性能瓶颈再切异步。

- **纯 SDK 手写 MCP Server 的四个必需步骤**（文章给出年假查询+订单查询两个工具的完整 Java 代码作为示例）：① 创建 Transport（示例用 `StdioServerTransportProvider`，远程部署换成 SSE/Streamable HTTP 的 ServerTransportProvider）；② 手动拼工具的 JSON Schema 描述参数（用 `McpSchema.Tool` 类承载，`McpSchema.JsonSchema.fromJson(...)` 解析），这一步文章明确指出是"最费劲的部分"，和之前 Function Call 文章里用 Gson 手写 JSON Schema 的工作量是一样的；③ 编写处理函数，签名是 `(exchange, request) -> CallToolResult`，需要自己从 `request.arguments()` 里按参数名手动取值；④ **阻塞主线程保活**——`build()` 方法本身不阻塞，SDK 内部监听 stdin 的线程是守护线程（Daemon Thread），不会阻止 JVM 退出，所以必须用 `new CountDownLatch(1).await()` 手动挂住主线程，同时注册 `Runtime.getRuntime().addShutdownHook(...)` 在进程被终止时优雅关闭 Server。文章特别提醒一个容易踩的细节：**Stdio Transport 用 stdout 传输协议消息，日志必须走 `System.err` 而不能走 `System.out`**，否则日志会和 JSON-RPC 协议消息混在一起导致 Client 解析失败。

- **纯 SDK 手写 MCP Client 的完整生命周期**：创建指向 Server 子进程的 `StdioClientTransport`（`StdioClientTransport.builder("java").args("-jar", "xxx.jar").build()`）→ 创建 `McpSyncClient` → `initialize()` 握手 → `listTools()` 发现工具 → `callTool(new CallToolRequest(name, argsMap))` 调用 → 遍历 `result.content()` 里的 `McpSchema.TextContent` 取文本结果 → `closeGracefully()` 关闭。另外 SDK 提供 `toolsChangeConsumer` 回调，用于监听 Server 端工具列表的动态变更（比如运维新增/下线了某个工具）；但这个回调依赖 **Server 主动发送变更通知**才会触发——如果 Server 端没实现通知机制，Client 端注册了回调也不会被调用。Spring AI 注解方式下这个监听是自动处理好的，纯 SDK 需要手动注册。

- **Spring AI 相当于给官方 SDK 封装了三件事**：① 注解驱动的工具注册——扫描 `@McpTool`/`@McpResource`/`@McpPrompt` 标注的 Bean 和方法，通过反射读取方法签名和注解属性，自动生成 `McpSchema.Tool`/`Resource`/`Prompt` 对象并调用 SDK 的 Builder API 注册；② 自动配置 Transport——根据引入的 Starter 依赖（`spring-ai-starter-mcp-server` → Stdio；`-webmvc` → SSE 的 Servlet 实现；`-webflux` → WebFlux 版）自动选择对应的 TransportProvider；③ Spring Boot 生命周期管理——Server 的启停绑定到 Spring 容器启停，不需要手写 `server.build()`/`server.closeGracefully()`。文章总结 `@McpTool` 具体替开发者自动完成的三件事：从方法签名自动生成 JSON Schema（参数名→properties、参数类型→type、`@McpToolParam` 的 description→参数描述、方法名→工具名）、自动把请求参数按名称映射到方法参数、自动把返回值包装成 `TextContent`+`CallToolResult`。原文给出的代码量对比很直观：同一个查年假工具，Spring AI 注解方式 3 行，纯 SDK 方式 30+ 行。

- **版本演进**：0.18.1 → 1.0.0 有三个不兼容变更需注意——① `mcp` 便捷包默认 Jackson 3（而非 2），Jackson 2 项目要改成手动引入 `mcp-core + mcp-json-jackson2`；② Spring 集成模块 Maven 坐标从 `io.modelcontextprotocol.sdk:mcp-spring-*` 变成 `org.springframework.ai:mcp-spring-*`；③ 旧版 `tool()` 方法签名在 0.18.1 就已标记废弃，1.0.0 正式移除改名为 `toolCall()`。SDK 正在向 2.0.0 演进（对标 MCP 2025-11-25 规范修订版），预告的新特性包括 Tasks 管理（长任务查询进度/取消）、Elicitation（Server 主动向 Client 请求用户输入，如弹确认框）、更完善的认证授权、基于 Java 21 虚拟线程的并发能力提升。

## 关键概念/术语

McpSchema：SDK 中最大的类，用 Java Record 把 MCP 协议规范定义的全部消息类型（Tool/Resource/Prompt/CallToolRequest 等）实现为不可变数据类，是协议的数据字典。
Transport（层）：只负责 JSON-RPC 消息的物理送达，不关心消息语义，SDK 提供 Stdio/SSE/Streamable HTTP 三种实现。
Session（层）：管理一次连接的握手状态、请求-响应按 id 配对、通知消息处理，关键类 McpClientSession/McpServerSession。
Streamable HTTP：MCP 较新引入的双向流式 HTTP 传输方式，替代常驻 SSE 长连接、按需建立连接，是官方推荐的生产级远程传输方案。
McpJsonMapper：SDK 对 JSON 序列化做的抽象接口，允许底层可插拔 Jackson 2 或 Jackson 3 实现，避免版本冲突。
toolsChangeConsumer：Client 端注册的回调，监听 Server 端工具列表变更通知；依赖 Server 主动推送通知才会触发。
Elicitation：MCP 2.0 方向的新特性，允许 Server 主动向 Client 发起请求用户输入的交互（如弹出确认对话框）。

## 与 xrag 项目的关系

- xrag 后端目前走 Spring AI 注解方式接入 MCP（对应专栏 01-13/01-14 两篇尚待整理的前置文章），本篇的价值主要是**排障与深度定制的知识储备**，不是当前必须落地的代码改动：理解了 `@McpTool` 背后其实是"反射读注解 → 生成 McpSchema.Tool → 注册进 Builder"这条链路后，未来如果遇到工具没被正确发现、参数映射错误、或 Schema 生成不符合预期这类问题，可以直接对照 SDK 的 Schema 层和 Client/Server 层源码定位，而不是停留在"注解不生效但不知道为什么"的黑盒调试。
- 文章明确给出的三种 Transport 选型建议（本地开发用 Stdio、生产远程部署用 Streamable HTTP、SSE 是过渡方案）直接对应 xrag 后续如果要把内部 MCP Server 开放给远程 Client（而不只是同进程内的 Spring AI 自动装配）时的传输层选型依据——如果要支持外部系统或跨网络调用 xrag 的 MCP 工具，应优先规划 Streamable HTTP 而不是继续用 Stdio 或选偏过渡性质的 SSE。
- 文章里"Stdio Transport 下日志必须走 stderr、不能走 stdout"和"必须手动阻塞主线程保活否则守护线程被 JVM 提前回收"这两个细节，是 xrag 若未来需要脱离 Spring Boot 独立起一个轻量 MCP Server/Client 进程（比如给某个边缘服务或脚本工具单独打包一个不依赖完整 Spring 容器的 MCP 接入点）时必须提前规避的两个坑，属于可以直接复用的工程经验。
- `toolsChangeConsumer` 依赖 Server 端主动发送变更通知才会触发这一点，提示 xrag 在设计工具动态上下线能力（如管理后台增删工具）时，如果希望已连接的 Client 感知到变化，Server 端实现必须主动调用 SDK 提供的通知发送机制，不能假设"改了工具列表 Client 就会自动重新拉取"。

## 值得注意的设计取舍/坑

- **JSON 序列化拆两个模块是为规避 Jackson 2/3 不兼容**：Jackson 3.x 包名从 `com.fasterxml.jackson` 变成 `tools.jackson`，直接引入默认走 Jackson 3 的 `mcp` 便捷包，如果项目历史依赖是 Jackson 2，会造成包名不匹配的编译/运行时问题，需要显式换成 `mcp-core + mcp-json-jackson2` 组合，这是升级或初次引入 SDK 时容易忽略的依赖选型点。
- **Stdio Transport 的守护线程陷阱**：`McpServer.build()` 返回后主线程会立刻往下走，而 SDK 内部监听 stdin 的线程是守护线程，一旦 `main` 方法执行完 JVM 立即退出，Server 还没来得及处理任何请求就已经"死"了；必须手动用 `CountDownLatch` 之类的机制阻塞主线程，这是纯 SDK 编程模型里一个不直观、容易踩的坑。
- **stdout 被协议占用，日志误写会导致协议解析失败**：这是 Stdio Transport 特有的约束，一旦有第三方库或遗留代码里散落着 `System.out.println` 式的调试日志，混进 JSON-RPC 消息流里会让 Client 端解析报错，且这种问题现象（Client 偶发解析失败）和根因（日志误写 stdout）之间的关联并不直观，排查成本较高。
- **同步 API 本质是异步 API 的 `.block()` 封装**：这意味着即便代码写成同步调用的样子，底层仍然经过 Reactor 的响应式管道，如果项目本身是强阻塞式 IO 环境，混用 Reactor 的线程模型需要留意线程池/调度器配置是否符合预期，不能简单假设"同步 API = 完全传统阻塞实现"。
- **`toolsChangeConsumer` 是"被动等通知"，不是"主动轮询"**：这是一个反直觉点——注册了回调不代表 Client 会自动感知工具列表变化，前提是 Server 必须主动推送变更通知；如果只在 Server 端改了数据但没调用 SDK 的通知发送接口，Client 端会一直拿着过期的工具列表而不自知。
- **版本坐标迁移的兼容性风险**：1.0.0 把 Spring 集成模块坐标从 `io.modelcontextprotocol.sdk` 迁到 `org.springframework.ai`，旧项目直接升级 SDK 版本号而不同步改 Spring 集成模块的 groupId，会导致依赖解析失败；同时 `tool()` 方法在 1.0.0 被正式移除改名为 `toolCall()`，是另一个升级时必须处理的破坏性变更。
