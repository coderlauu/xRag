# 《AI大模型Ragent项目》——Docker本地中间件部署

> 原文链接：https://t.zsxq.com/QnBXt
> 所属分组：02-bootstrap-and-deployment 本地开发与模型部署环境

## 一句话概括

这篇文章是 Ragent AI 项目"环境准备"阶段的教程，讲的是如何用 Docker 把项目最小启动所需的四个中间件（PostgreSQL+pgvector、Redis、RocketMQ、RustFS）在本地跑起来，并配套讲解各中间件客户端/控制台的连接验证方法。

## 核心内容提炼

1. **文档定位与迭代计划**：本文只覆盖 2026-03-02 版本的"最小中间件集合"，作者明确后续会继续补充 Nacos（注册配置中心）、Prometheus、Grafana（监控可观测性）等组件。说明 Ragent AI 的中间件矩阵是分阶段搭建的，当前阶段只解决"跑起来"，可观测性和服务治理留给后续章节。

2. **两条部署路径：本地 Docker vs 公共云中间件**。作者提供了一套公共云中间件供本地机器性能不足或不想折腾复杂安装的同学使用。但云方案**只覆盖 Redis 和 RocketMQ 这两项**，PostgreSQL 和 RustFS 并不在云方案覆盖范围内、仍需本地自行安装——这是容易被忽略的细节，云方案是"部分云化"而非"全套托管"。

3. **PostgreSQL + pgvector 是核心存储选型**：
   - 启动镜像是 `pgvector/pgvector:pg16`，即官方 PostgreSQL 16 叠加 pgvector 扩展。这意味着 Ragent AI 的向量检索能力是**内嵌在主关系数据库里**，而不是引入独立的专用向量数据库（如 Milvus、Weaviate）。这是一个值得记录的架构决策信号：牺牲了向量检索的极致性能/规模上限，换来了中间件矩阵的简化（业务数据和向量数据同库，事务、备份、运维都统一）。
   - 关键启动参数：数据库名 `ragent`，用户名/密码默认都是 `postgres`，端口映射 `5432:5432`，并用 Docker 卷 `pgdata` 做数据持久化。
   - 明确要求装完后同步修改项目 `bootstrap` 模块下 `application.yaml` 的数据源配置（url/username/password）。说明项目工程结构里有一个专职的 `bootstrap` 模块管理启动期配置。
   - 给出的连接池是 Spring 默认整合的 HikariCP，具体默认参数：`connection-timeout=5000ms`、`idle-timeout=600000ms`、`max-lifetime=1800000ms`、`maximum-pool-size=10`、`minimum-idle=5`。这组数值可以作为同类 Java 项目配置连接池的基线参考。

4. **Redis 走"轻量可丢弃"路线**：启动命令里只设置了端口映射（6379）和密码认证（`--requirepass`，默认密码 `123456`），**没有挂载持久化数据卷**。这是作者刻意的简化：本地 Redis 被当作纯缓存/临时状态存储，坏了直接删容器重装即可，不需要考虑数据保留。这个设计假设只适用于本地开发环境，不能照搬到生产。

5. **RocketMQ 是四个中间件里部署复杂度最高的**：作者把 RocketMQ 通常需要拆分部署的多个组件（NameServer、Broker、Dashboard 等）合并成了一个组合式 Docker Compose 栈，随项目仓库一起分发在 `resources/docker` 目录下，并按运行环境拆成两个 compose 文件：
   - Windows 和 Mac Intel 芯片用 `rocketmq-stack-5.2.0.compose.yaml`
   - 另一版本文件名标注为"Mac AMD 芯片"专用的 `rocketmq-stack-amd-5.2.0.compose.yaml`
   （注：Mac 从未使用过 AMD 芯片，这里命名应该是指 Apple Silicon / ARM 架构的 Mac；实际选择文件时要按自己机器是 Intel x86 还是 Apple Silicon ARM 来判断，别被"AMD"字面误导。）
   本地部署后控制台走 `8082` 端口；云端方案地址是 `http://common-rocketmq-dev.magestack.cn:8088`。

6. **RustFS 是项目的对象存储引擎，定位为 MinIO 的开源替代**：文章点明这是在 MinIO 收紧开源协议/闭源之后，社区转向的一个用 Rust 实现的新兴替代方案，主打高性能、低资源占用、强一致性、可扩展。这个背景很关键——理解了"为什么不用 MinIO"才能理解 RustFS 在架构图里的位置。
   - 端口分工：`9000` 是 API 端口（代码里连接对象存储用这个），`9001` 是 Web 控制台端口。
   - 默认 AccessKey/SecretKey 都是 `rustfsadmin`（仅适合本地开发，非生产可用凭据）。
   - 镜像版本是 `rustfs/rustfs:1.0.0-alpha.72`，处于 alpha 阶段，需要对后续升级的 API 兼容性/稳定性保持警惕。

7. **验收标准**：四个中间件都跑起来后，执行 `docker ps` 应该能看到 `rustfs`、`redis`、`postgres` 等容器（RocketMQ 因为是组合栈，容器名会更多，文中未逐一列出）。这是判断"最小中间件栈是否搭建成功"的操作性标准，仅是"容器在跑"这一层，不代表业务链路已经打通。

8. **客户端/控制台验证方式**：
   - Redis 用开源工具 AnotherRedisDesktopManager 连接，密码填安装时设置的 `123456`。作者提醒：连公共云 Redis 时，客户端默认不会拉取全部 Key（内存考虑），要按 Key 全称去搜索定位。
   - RocketMQ Dashboard 默认看不到任何消息，**必须等业务代码真实触发一次消息生产/消费后，Topic 才会被创建并显示**——这是判断"环境搭好"和"业务链路真正跑通"的分界线，容器能跑不代表消息通路没问题。作者还吐槽了官方 Dashboard 最新版存在中英文切换失效（选中文刷新后又变回英文）和配色体验差的问题，并透露后续计划自行 fork 重构后发布到个人 Docker 仓库（因为 RocketMQ 官方对该子项目维护并不积极）。
   - RustFS 控制台登录后可以创建 Bucket、上传/下载文件，用来验证对象存储服务是否正常，是官方推荐的验收操作。

9. **文档体系的位置**：这篇属于教程的"环境准备"章节，后续章节安排是"启动 Ragent 项目"，再往后是核心功能、向量检索策略与项目实战演示。整体是"环境搭建 → 项目启动 → 核心功能/实战"的递进结构。

## 关键概念/术语

- pgvector：PostgreSQL 的向量扩展插件，让关系数据库直接具备向量存储与近似检索能力，是 Ragent AI"向量能力内嵌主库、不单独引入向量数据库"路线的技术支撑。
- RustFS：用 Rust 编写的对象存储服务，提供 S3 兼容 API 和 Web 控制台，被定位为 MinIO 闭源后的开源接替方案。
- RocketMQ Dashboard：RocketMQ 官方自带的 Web 管理控制台，用于查看 Topic、消息、Broker 等运行状态。
- HikariCP：Java/Spring Boot 生态默认整合的高性能数据库连接池实现。
- AnotherRedisDesktopManager：一款开源的 Redis 可视化客户端管理工具。
- bootstrap 模块：Ragent AI 项目代码结构里专职管理启动期配置（如数据源 application.yaml）的模块。

## 与 xrag 项目的关系

- xrag 同样是围绕 RAG 场景的 Java 后端项目，本文给出的中间件组合（PostgreSQL+pgvector 做业务数据与向量存储合一、Redis 做缓存、RocketMQ 做异步消息、RustFS 做对象存储）可以直接作为 xrag 中间件选型的对照样本，尤其是"是否要单独引入专用向量数据库，还是复用 pgvector 走合库路线"这个决策，值得在 xrag 存储层设计时明确评估一次并记录理由。
- HikariCP 连接池的默认参数组合可以作为 xrag 的 `application.yaml`/`bootstrap` 配置基线起点，避免从零摸索。
- 本地 Docker vs 公共云中间件并存的方案设计，也适合搬到 xrag 自己的本地开发文档里，降低团队新成员本地环境搭建的门槛（尤其是 RocketMQ 这种部署复杂度高的组件）。
- 文中暴露的两个风险点——RustFS 尚在 alpha 阶段、以及下文提到的 RustFS API 503 报错——如果 xrag 计划采用 RustFS 作对象存储，需要在选型阶段提前做验证性压测，而不是等到集成阶段才发现。

## 值得注意的设计取舍/坑

1. **RustFS 已知问题（来自评论区真实反馈）**：有读者反馈本地用 Docker 起 RustFS 后，Web 控制台可以正常打开，但调用对象存储 API（如 CreateBucket）时报 `503 Service Unavailable`（reached max retries: 4），且截至该反馈时间，评论区未见到明确解决方案。这提示 RustFS 目前存在"控制台能通、API 不一定稳定"的落差，实际接入前应先跑一遍 API 层面的联通性验证，不能只看控制台是否能打开就判定服务正常。
2. **RustFS 版本号是 alpha 阶段**（`1.0.0-alpha.72`），意味着接口和行为可能随版本演进发生破坏性变化，不建议直接用于生产，只适合作为本地开发/学习阶段的存储方案。
3. **RocketMQ compose 文件按芯片区分容易踩坑**：文中把其中一个 compose 文件标注为"Mac AMD 芯片"专用，但 Mac 并不存在 AMD 芯片机型，此处大概率是指 Apple Silicon（ARM）架构。实际操作时应按自己机器的真实架构（Intel x86_64 / Apple Silicon ARM64）去判断该用哪个文件，不要被字面的"AMD"误导选错。
4. **Redis 未做数据持久化是有意为之**：安装命令里没有挂载数据卷，容器删除或异常重启后数据会丢失。这是作者为了简化本地环境刻意做出的取舍（当作纯缓存对待），迁移到生产环境时必须重新评估是否需要持久化。
5. **"公共云中间件"方案容易被误解为全托管**：实际只是 Redis 和 RocketMQ 两项可以云化替代，PostgreSQL 和 RustFS 仍然要求本地自行安装，选择云方案的同学仍需完成这两项的本地部署。
6. **RocketMQ Dashboard 的官方维护现状不理想**：存在语言设置不持久化、默认配色体验差等问题，且作者反馈官方对该子项目的响应并不积极，短期内如果没有替换成作者自行维护的镜像，这些体验问题会持续存在，不是本地环境搭建方式导致的问题。
