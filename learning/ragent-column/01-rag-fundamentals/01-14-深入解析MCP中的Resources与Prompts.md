# 《AI大模型Ragent项目》——深入解析MCP中的Resources与Prompts

> 原文链接：https://t.zsxq.com/K7tkK
> 所属分组：01-rag-fundamentals RAG与大模型基础概念

## 一句话概括

上一篇 MCP 文章只讲了 Tools 且认为 Resources/Prompts 用得少、了解即可，本篇是"补坑"之作：作者发现实际项目里有些场景硬用 Tools 会很别扭（比如只读、无副作用的配置读取），于是系统讲解了 MCP 另外两大能力——Resources（只读数据，应用驱动加载）和 Prompts（标准化模板，Client 驱动获取），并给出 Spring AI 的 `@McpResource`/`@McpPrompt` 落地示例。

## 核心内容提炼

- **为什么要补这一课**：作者承认上一篇留了一句"Resources 和 Prompts 用得相对少，了解即可"，但实际项目做下来发现——有些操作没有任何副作用（不修改数据、不触发流程，只是读一份资料），硬套 Tools 就像"去餐厅点菜，服务员端上来的是一本菜单"；而团队沉淀的一套效果很好的知识库问答 Prompt（角色定义、回答规则、引用要求、兜底策略）如果想让所有接入的 Client 直接复用、不用各自拼装消息，也需要一个标准化的分发机制。这两个诉求正是 Resources 和 Prompts 在协议层面被单独抽出来的原因。

- **Resources 的本质与和 Tools 的关键区别**：一句话概括——Server 暴露数据，Client 读取，给模型提供上下文。用餐厅类比：Tools 像点菜（厨房开火炒菜，有副作用），Resources 像看菜单（翻看不会消耗任何东西，纯只读）。协议层面对比：
  - 本质：Tools=执行操作，Resources=提供数据
  - 副作用：Tools=可能有（可读可写），Resources=无（只读）
  - 谁决定调用：Tools=模型驱动（model-controlled，模型自己判断要不要调），Resources=应用驱动（application-controlled，通常由 Host 应用或用户手动选择读哪些资源）
  - 协议方法：Tools 对应 `tools/call`，Resources 对应 `resources/read`

- **Resources 的价值场景：客服上下文预加载**。用户打开客服窗口还没开口，Host 应用就可以主动读取一批 Resources（用户画像、最近订单、退货政策等）拼进上下文，再发给模型。相比全用 Tools 让模型自己判断该查什么、依次调用工具，Resources 方式省掉了"模型先推理该调哪些工具"这一步，避免了两个问题：①额外的推理和调用延迟；②模型可能漏调（比如忘了查会员等级，导致答案漏掉 VIP 权益）。但文章特别强调一个容易误解的点：**读取 Resources 后是否塞进模型上下文，是 Host 应用自己的编排策略，MCP 协议本身只负责"资源怎么被列出和读取"，不负责"读到后要不要用、怎么用"**。

- **一个反直觉的诚实结论：Resources 在单 Client + 成熟微服务架构下可能"鸡肋"**。如果项目本身是 Java 微服务架构（Feign/Dubbo/gRPC 互相调用），直接用现有远程调用查数据拼上下文，效果和走 MCP Resources 协议完全一样，而且类型安全、有现成的熔断重试监控。作者的结论是：如果 AI 应用只有一个 Client（自己的后端服务），Resources 不是必选项；**它真正的优势场景是跨 Client 共享数据源**——同一个 MCP Server 暴露的资源，Claude Desktop、Cursor、自建 Web 应用都能通过统一的 `resources/read` 协议读取，数据获取逻辑只在 Server 端写一次。单 Client 场景下这个优势不存在。

- **两种资源类型及选型标准**：
  - 直接资源（Direct Resources）：固定 URI 指向确定数据（如 `docs://product-manual`、`config://app/settings`），适合数量有限、相对固定的数据，可在 Server 启动时注册好。
  - 资源模板（Resource Templates）：URI 里带参数占位符（遵循 RFC 6570 URI 模板规范，如 `order://users/{userId}/orders/{orderId}`），Client 填参数才能访问，适合数量不固定、需要动态生成的数据（订单详情、按日期的日志、按表名的表结构）。
  - 判断标准很简单：内容固定/数量有限 → 直接资源；需要 ID/日期/表名等参数才能确定具体内容 → 资源模板。

- **Resources 协议层的四个方法**：`resources/list`（列出直接资源，返回 uri/name/description/mimeType）、`resources/templates/list`（列出资源模板，返回 uriTemplate 等）、`resources/read`（传入具体 URI 读取内容，返回 `contents` 数组，支持纯文本或 Base64 编码的二进制内容，且**一次调用可以返回多个 content**，比如读目录时可以把目录下所有文件内容一次性返回）、`resources/subscribe`（订阅资源变更，可选能力）。前三个是核心，最后一个是可选的高级能力。

- **Java/Spring AI 实现细节（`@McpResource`）**：直接资源写固定 `uri`（如 `docs://return-policy`），资源模板写带 `{占位符}` 的 uri（如 `order://{orderId}`）——**框架会根据 uri 中是否含 `{...}` 自动判断是直接资源还是模板，不需要显式声明类型**；返回类型统一是 `ReadResourceResult`，内部包一个 `TextResourceContents`（文本）或 `BlobResourceContents`（二进制），构造时要传入"参数已填入的实际 URI"而不是模板原始 URI；方法参数名和 URI 模板里的占位符名一致即可自动接收 Client 传入的值（如方法参数 `String orderId` 对应 `order://{orderId}`）。

- **同一个需求，Tools 和 Resources 都能实现，怎么选**：文章用"查订单详情"举例——Tools 方式是模型在对话中自己判断要不要调 `getOrderStatus` 工具；Resources 方式是 Host 应用或用户主动决定读取 `order://{orderId}`，模型不参与"要不要读"的决策。选择标准：**会产生副作用（下单、退款、发邮件）或需要模型自主判断要不要执行 → 用 Tools；只是给模型参考资料、由应用/用户决定要不要看 → 用 Resources**。实际项目里两者常配合使用（先用 Resources 加载配置，再用 Tools 让模型按需查具体订单）。

- **资源变更订阅机制**：Client 通过 `resources/subscribe` 订阅资源，内容变化时 Server 发 `notifications/resources/updated` 通知——**注意通知的只是"这个资源更新了"这个事件本身，不会把新内容直接推给 Client，Client 收到通知后还要自己再调一次 `resources/read`**。判断要不要支持订阅的关键点是一个容易搞反的逻辑：**不是看 URI 本身是否固定，而是看同一个 URI 背后的数据源在运行期是否会返回不同内容**——代码里写死的常量内容（如硬编码的退货政策文本）不需要订阅，因为进程不重启就不会变；配置存在数据库/Nacos/Apollo 里的、或实时销售看板类数据则需要订阅，因为 URI 不变但底层数据会被修改。

- **Prompts 的本质**：Server 预定义 Prompt 模板，Client 传入参数后拿到一组可直接用于模型调用的 `messages` 数组。类比公司内部的标准文档模板（周报模板、请假单模板）——只不过这里的模板是给模型用的。和 Client 端自己拼 Prompt 相比，价值在于版本统一（改一处、所有 Client 拿到的都是新版）、最佳实践集中沉淀、参数校验统一定义；但**这个价值只有在多个 Client 接入同一个 MCP Server 时才体现，单 Client 场景直接在 Client 端写 Prompt 完全没问题**。

- **三种能力的控制模式对照**：Tools=模型驱动（模型自己决定调不调）；Resources=应用驱动（Host 应用决定加载哪些资源）；Prompts=Client 驱动（通常由用户在 UI 里通过斜杠命令/菜单触发，比如 Claude Desktop 里输入 `/knowledge-qa`，但也可以是自动化流程调用）。

- **Prompts 协议交互**：`prompts/list`（返回模板列表，每个模板含 name/description/一组 `arguments` 定义，每个参数有 name/description/required）、`prompts/get`（传入模板名+参数值，返回填好参数的完整 `messages` 数组）。返回的是结构化的 `messages` 数组而不是一段纯文本，意味着 Server 可以精确控制消息结构；Client 拿到后可以原样发给模型，也可以再加工（比如把指令部分挪进 system 消息）——**Prompts 提供的是标准化的模板描述与参数化机制，不是"自动发送给模型"的机制，这一点文章反复强调了两次**。

- **一个协议硬约束：PromptMessage 的角色只有 user 和 assistant，没有 system**。如果需要系统级指令，只能塞进第一条 user 消息开头，或者由 Client 拿到后自行拆分到 system 消息——这属于 Client 的编排策略，不是协议本身负责的。

- **Java/Spring AI 实现细节（`@McpPrompt` + `@McpArg`）**：`name` 是模板唯一标识（Client 用这个名字获取模板）；`@McpArg` 的 `required` 控制参数是否必填；返回类型是 `GetPromptResult`（包一个 description + `List<PromptMessage>`，每条 `PromptMessage` 有 `Role`（USER/ASSISTANT）和 `TextContent`）；**所有参数在协议层传递时都是 String 类型，即便语义上是数字（如摘要最大字数 `maxLength`），也需要在方法内部自己做类型转换和解析失败兜底**。

- **Prompts 和"Prompt 工程"系列内容是同一套东西的两个阶段**：文章明确指出 `knowledge-qa` 模板里那些规则（限定知识来源、引用标注、兜底指令）和专栏 Prompt 工程篇讲的内容完全一致——原文表述是"Prompt 工程解决的是怎么写好 Prompt，MCP Prompts 解决的是怎么把好 Prompt 分发出去"。也就是说 Prompts 不是新的 Prompt 设计方法论，而是已有最佳实践的**分发和复用机制**。

- **三大能力总对比与选型决策**：本质（执行操作/提供数据/提供模板）、控制方（模型驱动/应用驱动/Client 驱动）、副作用（可能有/无/无）、返回内容（操作结果/资源内容 text或blob/messages 数组）、Spring AI 注解（`@McpTool`/`@McpResource`/`@McpPrompt`）、协议方法（`tools/call`/`resources/read`/`prompts/get`）。一个成熟的 MCP Server 通常三者都提供、配合使用（Tools 查年假订单状态、Resources 提供配置和表结构、Prompts 提供标准化问答/摘要模板），三者互补而非互相替代。

## 关键概念/术语

Resources（资源）：MCP 三大能力之一，Server 暴露只读数据供 Client 读取，作为模型上下文，无副作用，应用驱动加载（不是模型自己决定读不读）。
Prompts（提示词模板）：MCP 三大能力之一，Server 预定义标准化模板，Client 传参后获取可直接用于模型调用的 messages 数组，Client 驱动获取（通常由用户在 UI 里选择触发）。
直接资源（Direct Resources）：固定 URI 指向确定数据的资源类型，适合数量有限、内容相对固定的数据。
资源模板（Resource Templates）：遵循 RFC 6570 规范、URI 中带参数占位符的资源类型，适合需要按 ID/日期/表名等动态生成的数据。
resources/list、resources/templates/list、resources/read、resources/subscribe：Resources 相关的四个协议方法，分别用于列出直接资源、列出资源模板、读取具体资源内容、订阅资源变更。
notifications/resources/updated：资源变更后 Server 主动发给已订阅 Client 的通知，只通知"变了"这个事件，不携带新内容，需要 Client 再次调用 resources/read。
prompts/list、prompts/get：Prompts 相关的两个协议方法，分别用于列出可用模板、传参获取填好的完整 messages 数组。
@McpResource / @McpPrompt / @McpArg：Spring AI 中用于声明 Resources 和 Prompts 及其参数的注解，与上一篇的 @McpTool 对应。
model-controlled / application-controlled / Client-driven：MCP 三大能力各自的控制模式区分，分别对应 Tools（模型自主决定）、Resources（Host 应用决定加载哪些）、Prompts（通常由用户在 Client UI 里选择触发）。

## 与 xrag 项目的关系

- 本篇明确划出了 Resources 的适用边界——"单 Client + 已有成熟微服务体系时价值有限，真正优势在跨 Client 共享数据源"。这一点对 xrag 有直接指导意义：xrag 当前如果只是自己的后端服务在调用大模型（单一 Client），配置读取、表结构查询等只读数据完全可以继续用现有的 Service 层直接查，不必为了"用上 MCP"而额外包一层 Resources 协议；只有当 xrag 未来需要让 Claude Desktop、Cursor 等外部 Client 或多个内部子系统共享同一份知识库配置/元数据时，才值得把这些只读数据抽成 MCP Resources 对外暴露。这可以避免过度设计——不必在还只有一个 Client 的阶段就提前搭 Resources 层。
- Prompts 部分对 xrag 检索问答链路的意义更直接：文章点出"knowledge-qa 模板里的规则和 Prompt 工程篇讲的内容完全一致，MCP Prompts 解决的是怎么把好 Prompt 分发出去"。如果 xrag 未来除了自己的 Web 端之外还会接入其他 Client（比如把知识库问答能力包装成 MCP Server 给团队内其他工具复用），现在沉淀的标准问答 Prompt（角色定义、引用规则、兜底策略）就可以直接迁移成一个 `@McpPrompt` 模板对外分发，而不用每接入一个新 Client 就复制一遍 Prompt 拼装逻辑。这为 xrag 后续如果要"服务化"知识库问答能力提供了一个具体的落地形态。
- 协议硬约束"PromptMessage 只支持 user/assistant，不支持 system"是一个需要提前记住的坑：如果 xrag 后续真的把知识库问答模板包成 MCP Prompt 对外提供，现有 Prompt 工程里如果把系统级指令放在 system 角色消息里，迁移时需要显式挪到第一条 user 消息开头，否则协议层面直接不支持。
- 资源订阅那段"判断标准不是 URI 固定不固定，而是背后数据源会不会变"的思路，对 xrag 如果要做知识库配置热更新（比如检索参数、Prompt 模板本身存在配置中心）有参考价值：真正需要订阅通知的是"配置中心里的检索策略参数""知识库元数据"这类会被运维修改的内容，而不是所有 Resources 都要一刀切支持订阅。
- 目前只是知识基础的记录，具体是否要把 xrag 包成 MCP Server、暴露哪些 Resources/Prompts，需要等 xrag 明确出现"多 Client 接入"或"能力对外服务化"的真实需求后再结合本篇的选型标准落地，不建议在只有单一 Client 的现状下提前引入。

## 值得注意的设计取舍/坑

- **容易被误解的一点：Resources 读到的数据要不要放进模型上下文，是 Host 应用自己的编排逻辑，MCP 协议不会自动帮你把资源塞进对话**。文章特意强调了这一点，说明这是一个常见的误解——协议只管"怎么列出、怎么读取"，不管"读到后怎么用"。
- **Resources 在单 Client + 成熟微服务架构下可能是多余的一层抽象**：如果已经有 Feign/Dubbo 等远程调用手段，硬套 MCP Resources 协议不会带来类型安全、熔断重试、监控这些现成能力,反而多了一层协议开销，是否引入要先看是否真的存在"多 Client 共享同一数据源"的需求。
- **Prompts 拿到的 messages 数组不会自动发给模型**：Client 拿到后是原样发送还是再加工，仍由 Client 自己决定，不要误以为接入 MCP Prompts 之后"发送"这一步也被协议接管了。
- **PromptMessage 的角色只支持 user/assistant，没有 system**，这是协议规范层面的硬约束，如果照搬现有系统提示词直接放进 system 角色消息会直接不兼容，需要迁移到第一条 user 消息开头。
- **判断是否需要资源订阅时容易搞反判断依据**：不是看 URI 本身是否含参数/是否固定，而是看同一个 URI 背后的实际数据源在运行期会不会变化——代码常量类内容不需要订阅，配置中心/数据库里的动态配置才需要。
- **资源订阅（resources/subscribe）目前生态还不成熟**：文章写作时（2026-03-16）MCP Java SDK 1.1.0（3 月 13 日发布）才刚支持 Resources 的订阅能力，Spring AI、LangChain4j 等上层框架的集成还会更滞后，作者的建议是——如果资源内容本身就是常量，不必强行接订阅这个还在迭代中的能力。
