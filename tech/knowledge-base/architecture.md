# 技术方案：AI 知识库建设模块（xrag Phase 1）

- `status`: approved（`2026-07-25` 用户确认 §4 两项技术性收敛，并选定 Embedding 供应商为阿里云百炼 DashScope）
- `related_docs`: [PRD](../../docs/prd/2026-07-25-knowledge-base-prd.md)、[CONTEXT.md](../../CONTEXT.md)、[ADR 0001](../../docs/adr/0001-build-xrag-independently.md)、[ADR 0002](../../docs/adr/0002-knowledge-base-async-and-concurrency.md)、[数据模型](data-model.md)、[API 契约](api.md)、[界面规格](ui-spec.md)、[测试矩阵](test-matrix.md)、[学习来源](../../learning/ragent-column/03-knowledge-base/)

## 1. 方案范围与阅读顺序

本文件负责**模块架构、关键流程、并发与一致性策略、技术选型**。数据表结构见 [data-model.md](data-model.md)，接口契约见 [api.md](api.md)。三份文档配套阅读，任何一处改动都要同步另两处。

本方案覆盖 PRD 全部四个功能域（知识库管理 / 文档管理 / 定时同步 / 分块管理），不含 PRD 已列为非目标的内容。

## 2. 分层与包结构

在现有 `com.app` 下新增一个业务包 `com.app.knowledge`，内部按职责分层。xrag 现有代码是 JDBC 直连、无 ORM，本模块沿用这个基线，不引入 JPA/MyBatis（[ADR 0001](../../docs/adr/0001-build-xrag-independently.md) 要求不照搬参考仓库的技术栈决定，此处是独立判断：本模块的 SQL 都是明确的单表增删改查加少量聚合，`JdbcTemplate` 够用，引入 ORM 只会多一层需要理解的映射语义）。

```
com.app.knowledge
├── web/            REST 控制器 + 请求/响应 DTO + 参数校验
├── service/        业务编排：事务边界、状态机、一致性保证都在这一层
├── repository/     JdbcTemplate 数据访问，一个类对应一张表
├── model/          领域对象与枚举（术语严格对齐 CONTEXT.md）
├── ingestion/      入库处理：文本提取、分块算法、任务派发与执行
├── embedding/      EmbeddingClient 接口及其实现
└── vector/         pgvector 读写（向量表的唯一入口）
```

一条纪律，来自学习笔记里反复出现五次的同一个坑：**事务边界只允许出现在 `service` 层，且事务内不得有任何外部 IO**（对象存储读写、Embedding HTTP 调用、远程文件下载）。`repository`、`vector` 层不自己开事务，只参与调用方的事务。

### 与现有代码的边界

- 复用 `config.StorageConfig` 提供的 `S3Client` Bean，不新建客户端。
- 复用现有 `DataSource` / `JdbcTemplate` 自动配置。
- `health.HealthController` 不改动。本模块不新增外部依赖类型（Embedding API 是出站调用，不适合放进 readiness——它按次计费，探活会产生真实费用）。
- Redis 当前在本模块**用不到**：并发限流用本地信号量（见 §7），异步任务用数据库表（见 §3）。这是有意的，不是遗漏。

## 3. 核心流程

### 3.1 上传（同步，快）

上传接口只做"存文件 + 存元数据"，绝不触发分块（PRD §7 决策 1）。两种来源汇聚到同一条路径：

```
FILE 来源：MultipartFile ──transferTo──> 本地临时文件 ─┐
                                                      ├─> 上传对象存储 ─> 写 source_document(PENDING) ─> 删临时文件
URL  来源：HTTP GET ──stream──> 本地临时文件 ──────────┘
```

**URL 来源为什么也必须先落临时文件**：远程响应的 `Content-Length` 不可信（可能缺失、可能是 chunked 编码、也可能撒谎），只有真正写完文件才知道实际大小，才能做大小校验和哈希计算。

**为什么不加 `@Transactional`**：这个方法里唯一的数据库操作是最后一条 `INSERT`，本身就是原子的；而前面的文件落盘和对象存储上传是耗时 IO。加事务只会让连接在整个上传期间被占用。代价是可能产生孤儿文件（对象存储上传成功但 `INSERT` 失败），这是显式接受的——孤儿文件不影响任何功能正确性，未来可以用一个对账任务清理。

### 3.2 触发分块（同步接口 + 异步执行）

这是本模块最关键的流程，也是 PRD §9 待决策事项 1 的落地。

**接口侧（一个短事务，毫秒级返回）**：

1. 校验文档存在、未删除、所属知识库存在。
2. **CAS 抢占**：`UPDATE source_document SET status='RUNNING', ... WHERE id=? AND deleted=false AND status<>'RUNNING'`。影响行数为 0 → 说明正在处理中，直接返回 409，不做任何后续操作。
3. 插入一条 `ingestion_run(status='QUEUED', trigger_source='MANUAL')`。
4. 提交，返回 `runId`。

CAS 一条 SQL 同时完成了"检查状态"和"占位"，天然没有检查与占位之间的竞态窗口，因此**不需要任何锁**。这是本方案不引入 RocketMQ 事务消息也不引入分布式锁的根本原因——课程用事务消息解决的是"数据库状态更新"和"消息投递"两个异构系统之间的原子性问题；当任务本身就是同一个数据库里的一行时，这个问题根本不存在。详见 [ADR 0002](../../docs/adr/0002-knowledge-base-async-and-concurrency.md)。

**执行侧（`@Scheduled` 轮询 + 固定线程池）**：

```
每 2s 轮询：SELECT ... FROM ingestion_run WHERE status='QUEUED' ORDER BY id LIMIT n
  └─ 逐条 CAS 抢占：UPDATE ... SET status='RUNNING' WHERE id=? AND status='QUEUED'
       └─ 抢到的提交给线程池执行 executeRun(runId)
```

`executeRun` 的执行顺序被一致性要求严格约束：

| 步骤 | 动作 | 事务 |
|---|---|---|
| 1 | 从对象存储下载文件到临时文件 | 无 |
| 2 | 提取纯文本（Apache Tika） | 无 |
| 3 | 按分块策略切分为分块列表，逐块算 `charCount`/`tokenCount`/`contentHash` | 无 |
| 4 | 批量调用 Embedding API 得到全部向量 | **无（关键）** |
| 5 | 逻辑删除该文档旧版本的分块 + 物理删除其向量 + 插入新分块 + 插入新向量 + 更新文档为 `SUCCESS`/`revision+1`/`chunkCount` | **一个短事务** |
| 6 | 标记 `ingestion_run` 为 `SUCCESS` | 独立事务 |

第 4 步和第 5 步的顺序是整个流程的核心：**先把所有向量算完，再开事务写库**。Embedding 是按分块数量线性增长的网络调用（一份 100 页 PDF 可能切出几百个分块），放进事务会让数据库连接被占用几十秒。第 5 步事务内全是本地数据库写入，耗时可控。

失败处理：捕获异常 → **在一个新事务里**把 `ingestion_run` 标记 `FAILED` 并记录 `errorMessage`、文档标记 `FAILED`。必须是新事务，否则失败信息会被外层事务的回滚一起冲掉。文档回到 `FAILED` 后用户可以直接重新触发分块，不用重新上传（PRD §4.2）。

第 5 步的"删旧插新"是重新分块的通用语义——首次分块时旧分块集合为空，因此**首次分块和重新分块走完全同一段代码**，不需要区分。

### 3.3 定时同步

复用 3.2 的执行链路，只在前面多一个"值不值得刷新"的判断（PRD §7 决策 5）。

```
每 60s 扫描：SELECT ... WHERE source_type='URL' AND sync_enabled AND deleted=false
                        AND status<>'RUNNING' AND next_sync_time <= now()
  └─ 对每个命中的文档：
       ① HEAD 请求比对 ETag / Last-Modified ──未变──> 记 run(status='SKIPPED')，只推进 next_sync_time
       ② 变了或 HEAD 不可用 → 下载到临时文件，算 SHA-256 ──与 content_hash 相同──> 同上 SKIPPED
       ③ 确认变化 → CAS 抢占文档 + 插入 run(trigger_source='SCHEDULED') → 走 3.2 的执行链路
```

**两级变更检测**（先 HEAD 后内容哈希）的必要性：HEAD 便宜但不可靠（很多服务器不返回 `ETag`，或 `Last-Modified` 精度只到秒、甚至每次请求都变）；内容哈希绝对可靠但要付一次完整下载的代价。两级串联的效果是：服务器行为规范时省下整次下载，不规范时也不会漏更新或误更新。

`SKIPPED` 状态必须记进 `ingestion_run`，否则用户在界面上看到"定时同步开着但从来没有执行记录"，无法区分"检查过没变化"和"调度根本没跑"。

定时同步和手动触发的互斥由 3.2 的同一个 CAS 保证——两条路径抢的是同一行的同一个状态字段，先到先得，后到的直接跳过。**不需要额外的锁机制。**

### 3.4 卡死任务的回收

进程在第 1~4 步（事务外的耗时阶段）崩溃或被 kill，会留下 `status='RUNNING'` 的僵尸文档，如果不处理就会永久卡住——CAS 永远抢不到，用户无法重试。两层回收：

- **启动时回收**：应用启动后把所有 `status='RUNNING'` 的 `ingestion_run` 与对应文档标记为 `FAILED(reason=进程重启)`。xrag 是单实例部署，进程刚启动时不可能存在真正在运行的任务，所以这个回收是**完全准确**的，没有误伤。
- **心跳超时回收**：执行线程每 10s 更新 `ingestion_run.heartbeat_time`；一个 `@Scheduled` 任务把 `heartbeat_time` 超过 5 分钟没更新的 `RUNNING` 任务判定为卡死并标记 `FAILED`。这一层专门覆盖"进程活着但任务卡住"（下载 hang 死、Embedding API 不返回）的情况。

> **单实例假设**：启动时回收的正确性完全依赖"同一时刻只有一个应用实例"。这个假设写进 [ADR 0002](../../docs/adr/0002-knowledge-base-async-and-concurrency.md)，如果将来要多实例部署，必须先改掉这一层，否则实例 A 启动会把实例 B 正在跑的任务误判为僵尸。

### 3.5 启用/禁用与分块编辑

这几个操作的共同点是都要维护"数据库分块内容"和"向量库"的一致，模式统一为：

```
（事务外）需要向量时：先算好 Embedding
（事务内）改数据库分块状态 → 同步操作向量表 → 提交
```

- **禁用文档/分块** → 物理删除对应向量（PRD §7 决策 3、§7.6 例外 1）。不需要 Embedding 调用，可以直接用声明式事务。
- **启用文档/分块** → 重新计算 Embedding（禁用时向量已被删掉），再写回。
- **编辑分块内容** → 内容与库中完全一致时**直接返回，什么都不做**：既省下按 token 计费的 Embedding 调用，也避免"删旧插新"造成的那一小段该分块不在向量库里的检索空窗。
- **批量启用/禁用分块** → 单次上限 500 条；必须用编程式事务（`TransactionOperations`）把批量 Embedding 调用留在事务外，方法本身**不能**加 `@Transactional`，否则内层会加入外层事务、精确控制事务范围的意图就落空了。

一致性原则：**数据库是主数据，向量表是派生索引，永远先写数据库再操作向量**。xrag 用 pgvector，向量表和业务表在同一个 PostgreSQL 实例里，因此这两步天然在同一个数据库事务内——这是选 pgvector 相对独立向量库（Milvus/Qdrant）最实际的一个收益：**不存在跨系统最终一致性问题，学习笔记 03-11 里那个"向量库写失败留下脏数据、等下次操作修复"的风险在 xrag 不成立。**

## 4. 两项技术性收敛（已确认）

这两项是对 PRD 描述的实现层调整，功能范围不变，但都放弃了一些能力。`2026-07-25` 已由用户确认采纳。

### 4.1 "每个知识库独立 collection" → 单张向量表 + `kb_id` 过滤

PRD §4.1 说"每个知识库对应向量库里一个独立的 collection"。这个说法来自课程使用独立向量数据库的语境。在 pgvector 下，"collection" 只能映射为"独立的表"，而独立表意味着**每建一个知识库就要动态执行一次 DDL**（建表 + 建 HNSW 索引），还要自己维护"知识库 → 表名"的映射、处理建表失败的回滚、以及 Flyway 管不到这些运行时表的问题。

方案：**单张 `document_chunk_embedding` 表，用 `kb_id` 列做检索过滤**。pgvector 0.8 支持 HNSW 索引配合过滤条件（iterative index scan），检索性能在个人项目的数据量级下不成问题。

放弃的能力：**不同知识库不能使用不同维度的 Embedding 模型**——`vector(N)` 的维度是列级固定的。第一版全局单一 Embedding 配置（`vector(1024)`），知识库表上仍然保留 `embedding_model`/`embedding_dimensions` 字段并在创建时校验必须与全局配置一致，这样将来真要支持多维度时，可以按维度分表而不必重构数据模型。

### 4.2 「文档版本」不建独立表，改为文档表上的自增版本号

[CONTEXT.md](../../CONTEXT.md) 定义了「文档版本（Document Revision）」这个术语，要求"新版本替代旧版本参与问答前，必须能区分它们的处理和索引状态"。定时同步会让文档反复更新，这个概念确实需要落地。

但建独立的 `document_revision` 表代价不小：检索时要额外过滤"当前生效版本"、要设计旧版本数据的保留策略、所有查询都多一层关联。而个人项目并不需要"回滚到文档的历史版本"这个能力。

方案：**在 `source_document` 上放一个自增 `revision` 字段，`document_chunk` 冗余携带 `revision`**。这满足 CONTEXT.md 的可区分要求（任何一个分块都能说清自己属于哪一版内容），同时不引入新表和关联。重新分块时新版本分块写入、旧版本分块逻辑删除，都在 §3.2 第 5 步的同一个事务内完成。

放弃的能力：不保留历史版本的分块内容，无法回滚到文档的某个历史版本、无法比较两版之间的差异。

## 5. 分块策略（PRD §9 待决策 2）

第一版支持两种，都是纯本地字符串处理，无外部依赖：

| 策略 | 参数 | 算法 | 适用 |
|---|---|---|---|
| `FIXED_SIZE` | `chunkSize`、`overlap` | 按字符数硬切，相邻分块重叠 `overlap` 个字符 | 无明显结构的纯文本；行为完全可预测，作为默认兜底 |
| `RECURSIVE` | `chunkSize`、`overlap`、`separators` | 按分隔符优先级递归切分（默认 `["\n\n", "\n", "。", ". ", " "]`）：先用最高优先级分隔符切，超长的片段再用下一级切，最终仍超长的硬切 | 有段落/句子结构的文档，默认策略 |

**语义分块不做**：它需要对每个候选切点两侧算 Embedding 相似度，Embedding 调用量是普通分块的数倍，成本和实现复杂度都显著上升，而收益需要有评测体系（Phase 4）才能量化。等 Phase 4 建好评测能力后再回来做，届时能用数据说明它到底值不值得，这比现在凭感觉加一个策略更有意义。

`tokenCount` 走启发式估算（中文 1 字≈1 token、英文 4 字符≈1 token、其他 2 字符≈1 token），**只用于界面展示分块规模，不参与任何逻辑判断**（PRD §2 非目标）。

文本提取用 **Apache Tika**（`tika-core` + `tika-parsers-standard-package`），一个依赖覆盖 PDF/Word/PPT/HTML/Markdown/纯文本，避免为每种格式各引一个库。第一版格式白名单：`.txt` `.md` `.pdf` `.docx`；白名单外的扩展名在上传接口就拒绝，不等到分块阶段才失败。

## 6. Embedding 接入（PRD §9 待决策 4）

定义一个最小接口，只有两个方法：

```java
public interface EmbeddingClient {
    List<float[]> embed(List<String> texts);   // 批量；顺序与输入一一对应
    int dimensions();                          // 供启动时校验与建表维度对齐
}
```

这个抽象不违反"不做投机性抽象"——Phase 2「大模型调度引擎」明确会引入第二个实现（多 Provider 路由、降级、限流），接口的第二个使用者是已知的，不是假想的。

第一版单一实现走 **OpenAI 兼容的 `/v1/embeddings` 协议**。这不是绑定 OpenAI，而是绑定一个事实标准：阿里云百炼（DashScope）、智谱、硅基流动、本地 Ollama 都提供兼容该协议的端点，一份实现全部覆盖，供应商通过配置切换。

**已确认的供应商（`2026-07-25`）：火山方舟 Ark**，模型 `doubao-embedding-vision`，通过 `dimensions` 参数取 1024 维——与 [data-model.md](data-model.md) 里 `vector(1024)` 对齐，无需改 migration；国内网络可直连。

> 选型是被 Key 的套餐决定的，不是偏好：手上这把是 **Coding Plan** 的 Key，实测 `doubao-embedding-large` / `doubao-embedding` / `doubao-embedding-text-240715` 都返回 *"The requested model does not support the agent plan feature"*，`doubao-embedding-vision` 是唯一可用的向量模型。它本身是多模态模型，但纯文本输入工作正常。

> ### ⚠️ 这套配置违反 Coding Plan 的使用条款（`2026-07-28` 查证）
>
> 方舟官方文档写明：**「Coding Plan API Key 仅能在官方支持的 AI 编程工具中使用，不能用于直接 API 调用。违规使用会被系统判定为滥用，导致订阅停用或账号封禁。」** 官方支持的工具指 Claude Code、Cursor、Cline、Codex CLI 等；套餐额度也只在这些工具里才计入。
>
> **本模块拿它做知识库向量化，正属于被明令禁止的"直接 API 调用"。** 这是项目所有者在知情后的选择（个人学习项目、自担风险），**不是本文档推荐的用法**，后来者不要照抄。
>
> 顺带订正一处事实：官方现在给出的 Coding Plan base-url 是 `/api/coding/v3`（OpenAI 协议）与 `/api/coding`（Anthropic 协议），与本文档记录的 `/api/plan/v3` 并不一致——可能是产品迭代改过路径。`/api/plan/v3` 在 `2026-07-25` 和 `2026-07-28` 两次实测中确实可用，但这不改变上面那条结论。
>
> **合规路径**：换一把标准计费 Key，并在方舟控制台开通一个支持标准 `/embeddings` 接口的**纯文本** embedding 模型，届时 `base-url` 改回 `/api/v3`、`model` 换成对应模型名、`dimensions` 按新模型核实。已验证 `doubao-embedding-vision` **不能**走这条路——标准端点对它返回 `the requested model does not support this api`，它只吃 `/embeddings/multimodal`，而那个接口一次只返回一个融合向量、维度 2048，与本模块"N 个分块 → N 个向量"的需求不匹配。
>
> 来源：[Coding Plan API 配置与 API Key 管理](https://www.volcengine.com/article/38138)、[方舟 Coding Plan 使用限制全解析](https://www.volcengine.com/article/37156)。

```properties
app.embedding.base-url=${EMBEDDING_BASE_URL:https://ark.cn-beijing.volces.com/api/plan/v3}
app.embedding.api-key=${EMBEDDING_API_KEY:}
app.embedding.model=${EMBEDDING_MODEL:doubao-embedding-vision}
app.embedding.dimensions=${EMBEDDING_DIMENSIONS:1024}
app.embedding.batch-size=${EMBEDDING_BATCH_SIZE:10}
```

三处实测确认过、且都容易被"顺手改错"的点（`2026-07-25`）：

- **`base-url` 是 `/api/plan/v3`，不是常见的 `/api/v3`。** Coding Plan 的 Key 打到标准路径直接 401。看起来像笔误，其实不是。
- **模型原生输出 2048 维，1024 是靠请求里的 `dimensions` 参数降下来的。** 这正是"`dimensions` 始终显式发送"那条决定的用武之地——去掉它会拿到 2048 维向量，然后每次写 `vector(1024)` 都在运行时报错。启动时的维度校验拦的是配置与建表不一致，拦不住这种"配置对、请求少发了个参数"的情况。
- **`batch-size` 的 10 是 Ark 的硬上限**，实测传 32 条被拒：`Embeddings API input limit exceeded: max 10, got 32`。超限的症状是**大文档在第一批请求就整体失败**、小文档却完全正常，很容易被误判成偶发问题——所以它是配置项而不是硬编码，换供应商时必须重新核实。

若将来想用模型的完整 2048 维精度，代价是一次 migration：改 `document_chunk_embedding.embedding` 的列宽并重建 HNSW 索引，同时全量重算已有向量。第一版不做——1024 是模型原生支持的降维档位，不是截断。

- HTTP 客户端用 Spring 6 自带的 `RestClient`，不引入第三方 HTTP 库。
- **启动时不校验 API Key 可用性**（会产生真实费用），但校验 `dimensions` 与数据库向量列维度一致，不一致直接启动失败——维度不匹配会让所有向量写入在运行时才报错，越早暴露越好。
- 未配置 `api-key` 时注入一个"直接抛异常说明未配置"的实现，保持"应用始终能启动、能力不可用时给出明确错误"的既有模式（和 `FlywayConfig`/`ensureStorageBucket` 的处理方式一致）。
- `batch-size` 是必要的：一次请求塞几百个分块会超出多数供应商的单请求上限，按批切分后串行发送。

### 出站限流防护（`2026-07-28` 补）

本文档此前所有关于"限流"的讨论——包括 §7 的上传信号量、学习笔记 03-04 / 03-05 两篇——**讲的全是入站方向**：用限流保护自己的磁盘与 IO。**出站方向被供应商限流，一处都没提到。** 同一个词，两个相反的方向，这就是它被漏掉的原因。

实测代价：一份切出 700 分块的文档要串行发出约 70 次请求，**8.4 秒后**收到 `429 AccountRateLimitExceeded`，整个入库任务失败；用户重试也没用，因为重试同样是一次性把几十个请求打出去。**这意味着当时的实现只能处理小文档**，与 PRD §8 的验收标准直接冲突。

```properties
app.embedding.batch-interval=${EMBEDDING_BATCH_INTERVAL:200ms}
app.embedding.max-retries=${EMBEDDING_MAX_RETRIES:3}
app.embedding.retry-base-delay=${EMBEDDING_RETRY_BASE_DELAY:1s}
```

两个手段分工不同，**缺一不可**：

- **批次间隔**从源头降低触发概率。200ms 把速率压到约 5 次/秒，实测被限时约为 8.3 次/秒。
- **`429` 指数退避重试**处理仍然撞上的情况。只做这一项的话，大文档会每批都撞一次再退避一次，总耗时反而更长。

`429` 单独成 `EmbeddingRateLimitException` 而不是用状态码在调用处 if 判断：**它是唯一值得重试的失败**，维度不符、模型不存在、Key 无效重试多少次结果都一样，白白拖慢失败反馈还多花钱。让类型承担这个判断，重试条件就不会散落成字符串匹配。

回归验证：同一份 700 分块的文档 `SUCCESS`，700 分块 / 700 向量，耗时 6 分 02 秒。**这个耗时超过了 5 分钟的心跳超时阈值，任务却没被误判为卡死**——心跳线程独立于执行线程，退避期间照常每 10 秒跳一次（§3.2 那条设计此前只有推理，这里拿到了实证）。

重试时打 WARN 日志。没有它，运维看到的只是"入库怎么这么慢"，真正原因无从查起。

> **这个缺口在拿到真实 Key 之前不可能被发现**：假 Embedding 实现不会限流，集成测试永远走不到这条路径。它是"外部依赖长期未解除"所掩盖的问题的典型样本。

## 7. 上传安全（PRD §6.1）

### 大小限制

```properties
spring.servlet.multipart.max-file-size=${UPLOAD_MAX_FILE_SIZE:50MB}
spring.servlet.multipart.max-request-size=${UPLOAD_MAX_REQUEST_SIZE:51MB}
server.tomcat.max-swallow-size=${UPLOAD_MAX_SWALLOW_SIZE:2MB}
```

`max-swallow-size` 容易被漏掉但很关键：超限被拒绝后，Tomcat 默认还会继续读完并丢弃整个请求体，为的是让连接可以复用。设小之后 Tomcat 会直接断开连接，避免为一个已经确定要拒绝的 50MB 请求白白消耗带宽和时间。

#### `max-request-size` 必须紧贴 `max-file-size`（`2026-07-28` 实测修正）

这三个值原先是 50MB / **60MB** / 2MB，看起来"给 multipart 开销留了充足余量"，实际上让 `max-swallow-size` 的意图落了空。**两个上限的检查时机根本不同**：

| 配置 | 检查时机 | 代价 |
|---|---|---|
| `max-request-size` | Tomcat 用 **`Content-Length` 头预判** | 超了立即拒绝，请求体一个字节都不读 |
| `max-file-size` | 只能**流式**检查 | 必须真读满 50MB 才知道超限 |

留 10MB 余量意味着 50~60MB 的文件 `Content-Length` 全部落在缝里，绕过头部预判、走进流式慢路径。`max-swallow-size` 这时只来得及省下最后 2MB。M-05 实测：

```
max-request-size=60MB → 51MB 文件上传 52,494,336 字节、耗时 25.1 秒后才 413
max-request-size=51MB → 51MB 文件上传     65,536 字节、耗时  0.036 秒就 413
```

**相差 800 倍，而两种配置返回的状态码完全一样**——只断言 `413` 的测试永远发现不了。1MB 余量足以覆盖 multipart 边界与表单字段（实际开销不到 1KB），48MB 合法文件回归验证仍正常上传。

### 内存安全——先验证更简单的方案，不直接照抄课程

学习笔记 03-03 记录了 AWS SDK v2 上传大文件时的堆内存放大问题（根因是 SigV4 签名需要先算整个请求体的 SHA-256），课程最终用**预签名 URL + 原生 HTTP 连接**绕过 SDK 解决。

xrag 不直接照搬这个方案，先验证一个更简单的做法：**`MultipartFile.transferTo(临时文件)` 落盘，然后 `s3Client.putObject(req, RequestBody.fromFile(tempFile))`**。`RequestBody.fromFile` 是 SDK 对大文件的推荐用法，它能从文件长度直接得到 `Content-Length`、并分块读取文件计算 checksum，理论上不需要把整个文件读进堆。03-03 的问题主要出现在 `fromBytes`/`fromInputStream`（流不可重放，SDK 只能先全部缓冲）。

这不是猜测就完事——**Step E 测试环节必须实测**：上传一个 30MB 文件，用 JFR 或 `jcmd GC.heap_info` 观察堆峰值。如果实测确认 `fromFile` 也有放大，再退到预签名 URL 方案（03-03 里已有完整记录，退路是明确的）。

之所以不一开始就上预签名方案：它把上传链路从"前端 → 后端 → 存储"改成"前端 → 后端拿签名 → 前端直传存储"，前端复杂度、CORS 配置、上传完成后的回调确认都要一并处理。为一个尚未在本项目上验证过的问题预先付这些代价不划算。

### 并发限流——单实例下不需要 Redisson

课程用 Redisson 的 `RPermitExpirableSemaphore` 做分布式并发限流。xrag 单实例部署，**本地 `java.util.concurrent.Semaphore` 与之完全等价**，且零依赖、无网络往返、无租约过期需要处理。

拦截位置是这里唯一需要想清楚的问题，答案来自学习笔记 03-05：**必须放在 `Filter` 里，不能放在 `HandlerInterceptor`**。原因是 multipart 请求体是在 `DispatcherServlet` 解析的——那个时机在 `Filter` 之后、`Interceptor` 之前。放在 `Interceptor` 里限流，文件已经被完整读进来了，限流的目的（保护磁盘和 IO）已经落空。

```
Filter（tryAcquire，此时请求体还没读）→ DispatcherServlet 解析 multipart → Controller
```

`tryAcquire` 带超时（默认 5s），拿不到许可返回 429 而不是无限期排队。配置 `app.upload.max-concurrent`（默认 3）。

> 限流为什么不按 QPS 做：QPS 限流对长耗时操作无效。上传持续几十秒，"每秒 5 个请求"意味着稳态并发量是 5×耗时秒数，可能同时有上百个上传在跑。长耗时操作要限的是**并发数**，不是速率。

## 8. 关键取舍汇总

| 取舍 | 选择 | 放弃了什么 |
|---|---|---|
| 异步任务载体 | 数据库任务表 + 轮询 | 秒级以下的派发延迟（现为最多 2s）、跨实例水平扩展 |
| 并发控制 | 状态字段 CAS | 无（CAS 在此场景下能力等价于锁，且更简单） |
| 卡死恢复 | 启动回收 + 心跳超时 | 多实例部署能力（启动回收会误伤其他实例） |
| 向量存储 | 单表 + `kb_id` 过滤 | 不同知识库用不同维度的 Embedding 模型 |
| 文档版本 | 文档表自增版本号 | 历史版本内容保留、版本回滚、版本间差异对比 |
| 限流 | 本地信号量 + Filter | 多实例下的全局并发上限 |
| 上传内存方案 | 临时文件 + `fromFile`（待实测） | 若实测不达标需改预签名方案，退路已明确 |
| 分块策略 | 固定长度 + 递归分隔符 | 语义分块（推迟到 Phase 4 有评测数据后再评估） |

## 9. 与后续 Phase 的接口

- **Phase 2（大模型调度引擎）**：接管 `EmbeddingClient` 的实现，本模块只依赖接口，不需要改动。
- **Phase 3（知识问答链路）**：检索侧读 `document_chunk_embedding` 表。本模块必须保证一条不变量——**该表中存在的每一行，都对应一个未删除且已启用的分块**。检索方可以直接查向量表而不必关联业务表过滤，这是本模块"禁用/删除即物理删除向量"设计的直接收益。
- **Phase 4（评测体系）**：`ingestion_run` 是入库侧的链路记录（CONTEXT.md 的「链路记录/Trace」概念在入库侧的载体），评测和问题排查都会读它。
