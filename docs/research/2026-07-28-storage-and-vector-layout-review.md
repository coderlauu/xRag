# xrag 原始文件与向量布局复核

- 日期：2026-07-28
- 范围：RustFS 原始文件对象 Key、pgvector 向量隔离、删除与生命周期一致性
- 对照材料：[《AI大模型Ragent项目》——控制台功能的全面剖析](https://articles.zsxq.com/id_h1kl1jobp952.html)

## 实施状态

本报告提出的当前阶段改进已于 `2026-07-28` 落地：不可变版本 Key、任务输入快照、
成功后切换当前原文、共享向量表强制 `kb_id` 检索、测试专用 Schema/Bucket、
知识库删除保护，以及带恢复宽限期的对象审计。永久清理开关仍默认关闭，历史业务对象
没有在本次实施中批量搬迁或删除。

## 结论摘要

1. **RustFS 仍应使用一个业务 Bucket，不建议照搬文章的“每个知识库一个 Bucket”。** xrag 是单用户项目，知识库之间没有独立权限、合规或保留策略要求；一个 Bucket 加可读前缀已经能解决控制台难辨认的问题，也避免把创建知识库变成“数据库 + 对象存储”的跨系统创建流程。
2. **对象 Key 应调整，而且不仅是为了好看。** 建议从当前的 `knowledge-base/{kbId}/{uuid}.{ext}` 改成带不可变、可读知识库别名和内容版本的结构，例如：

   ```text
   knowledge-bases/{kbId}-{storageAlias}/
     documents/{documentObjectId}/
       versions/{contentVersion}/{safeOriginalName}
   ```

   `storageAlias` 在创建知识库时生成后不可变；知识库展示名改名时不搬对象。`documentObjectId` 在上传前由应用生成 UUID，因此不要求先占用数据库自增 ID。`contentVersion` 用内容哈希或 UUID，保证 URL 每次同步写新对象而不是覆盖旧对象。
3. **当前单张 `document_chunk_embedding` + `kb_id` 的向量布局不需要立即改成分表或独立 collection。** 它符合 pgvector 的常规用法，也保留了业务表与向量表同事务提交的优势。但 Phase 3 检索实现必须正视 HNSW 的过滤行为：近似索引先扫描候选，再应用 `kb_id` 过滤；应启用 iterative scan，并用真实数据测召回率与执行计划。
4. **当前最高优先级问题是 URL 同步的原文/分块版本错配。** `ScheduledSyncScanner` 在新内容完成分块前就覆盖旧 `file_key` 对象；后续入库失败时，原文已是新版，而分块和向量仍是旧版。这违反 PRD 中“展示与当前分块一致的快照”的要求。应改成“新版本写新 Key → 入库任务绑定该 Key → 分块/向量成功后在数据库事务中切换 active Key”。
5. **逻辑删除先保留原文件，但必须补明确的永久清理机制。** 不应依赖当前 RustFS 的 Lifecycle 作为正确性机制：RustFS 官方仓库仍把 Lifecycle Management 标为 “Under Testing”。当前实现用应用侧分页审计：宽限期内保留恢复能力，过期对象先报告；只有显式开启永久清理才删除。扫描范围固定为本模块的新旧 Key 前缀，不会触碰同一 Bucket 中其他模块的对象。
6. **测试对象泄漏已经发生，不能再只当未来风险。** 2026-07-28 本地实测 RustFS 有 1,833 个对象目录、1,825 个一级 `kbId` 前缀、约 316 MB；PostgreSQL 只有 4 条知识库记录（2 条 active）和 12 条带 `file_key` 的文档记录。至少 1,821 个对象不可能被当前 `source_document.file_key` 引用。主要来源是集成测试回滚数据库事务但不回滚真实 RustFS PUT，以及测试/业务删除均不清理对象。应立即隔离测试前缀或 Bucket，并在测试套件结束后清理。

## 1. 指定文章到底建议了什么

文章描述的是另一套实现：

- 创建知识库时同时在 MySQL 插记录、在 S3 创建同名 Bucket、在 Milvus 创建 collection。
- `Collection Name` 同时作为 Milvus 集合和 S3 Bucket 的标识，因此不可改名。
- 知识库下还有文档时拒绝删除。
- 禁用文档时从 Milvus 删除向量，重新启用时重建。

来源：[指定文章](https://articles.zsxq.com/id_h1kl1jobp952.html)“知识库管理”章节。

这些是 **Ragent 的产品和技术栈选择**，不是 S3 或向量数据库的通用强制要求。xrag 使用 PostgreSQL + pgvector，不使用 Milvus；因此“每个知识库一个 Milvus collection”的成本模型和事务模型不能直接套用。“知识库有文档时拒绝删除”也属于防误删产品策略，与向量必须怎样存放无直接关系。

## 2. RustFS / S3 对象 Key

### 2.1 官方事实

- S3 Bucket 内的数据模型是扁平的；对象由完整 Key 唯一标识，并不存在真实目录。控制台看到的“文件夹”是 Key 前缀和 `/` delimiter 推导出的界面效果。[AWS：Naming Amazon S3 objects](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-keys.html)
- `ListObjects` 可以用 `Prefix` 只列出某一前缀，用 `Delimiter` 把下一层相同前缀折叠成 `CommonPrefixes`，从而按层级浏览。[AWS：Organizing objects using prefixes](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-prefixes.html)
- RustFS 官方说明其兼容 S3 协议，并推荐使用官方 AWS SDK；因此上述 Key / Prefix 语义适用于本项目。[RustFS：SDK Overview](https://docs.rustfs.com/developer/sdk/)
- S3 没有原地改 Key 的“rename”。改名本质是复制到新 Key，再删除旧对象；知识库改名不应触发全量对象迁移。[AWS：Copying, moving, and renaming objects](https://docs.aws.amazon.com/AmazonS3/latest/userguide/copy-object.html)

### 2.2 xrag 当前状态

FILE 与 URL 初次导入都生成：

```text
knowledge-base/{kbId}/{UUID}.{extension}
```

证据：

- [DocumentService.java](../../backend/src/main/java/com/app/knowledge/service/DocumentService.java#L114-L138)
- [DocumentService.java](../../backend/src/main/java/com/app/knowledge/service/DocumentService.java#L183-L197)

因此当前并非“所有文件完全混在一起”：`kbId` 已经是可用于列举的隔离前缀。但控制台只显示数字知识库 ID 和随机文件名，确实缺少人可读性。

2026-07-28 对本地容器做了只读盘点：

| 项目 | 数量 |
|---|---:|
| `/data/app/knowledge-base` 对象目录（深度 2） | 1,833 |
| 一级 `kbId` 前缀 | 1,825 |
| 占用空间 | 316 MB |
| PostgreSQL `knowledge_base` 全部 / active | 4 / 2 |
| PostgreSQL `source_document` 全部 / 有 `file_key` | 12 / 12 |

盘点命令使用容器内 `find` / `du` 和 PostgreSQL `count(*)`；这是本地开发数据快照，不是生产容量指标。它说明对象隔离和回收已经是现实问题，而不是纯粹的控制台体验问题。

### 2.3 建议的 Key 设计

```text
knowledge-bases/{kbId}-{storageAlias}/
  documents/{documentObjectId}/
    versions/{contentVersion}/{safeOriginalName}
```

字段语义：

| 段 | 建议 |
|---|---|
| `kbId` | 数据库主键，保证不因同名知识库冲突 |
| `storageAlias` | 从创建时名称生成、全局不要求唯一、创建后不可变，仅用于控制台辨认 |
| `documentObjectId` | 上传前生成的 UUID，作为文档对象身份；不要只靠原文件名 |
| `contentVersion` | 内容哈希或 UUID；URL 每次内容变化写新版本 |
| `safeOriginalName` | 清洗并截断后的原始文件名，只用于可读性，不作为身份 |

示例：

```text
knowledge-bases/12-product-manual/
  documents/43847be5-c293-4ad3-a321-238bf7463acc/
    versions/sha256-a01c.../iphone-setup-guide.pdf
```

设计理由：

- 第一层就能在 RustFS 控制台识别知识库。
- 展示名改名不会触发对象搬迁；`storageAlias` 是存储别名，不承诺始终等于最新展示名。
- UUID 保证身份稳定，原文件名重复不会覆盖。
- 每版内容使用新 Key，支持精确指向“当前分块对应的原文”。
- 相关对象可以按知识库、文档或版本前缀列举。

不建议只改成 `knowledge-base/{知识库名称}/{原文件名}`：知识库与文件均可重名、名称可修改，且恶意或异常文件名会直接污染 Key 结构。

### 2.4 是否每个知识库建一个 Bucket

**当前不建议。** RustFS 把 Bucket 定义为对象的逻辑容器，Bucket 之间可以做数据隔离；如果未来不同知识库需要独立 IAM、加密、保留策略或合规边界，Bucket 才是合理边界。[RustFS：Architecture / Key Concepts](https://docs.rustfs.com/concepts/architecture)

现阶段只为控制台可读性建立 Bucket，会引入：

- 创建知识库时的跨系统部分失败与补偿；
- Bucket 命名、改名和冲突规则；
- 每个 Bucket 单独配置策略、备份和巡检；
- 删除知识库时更高风险的整 Bucket 操作。

Prefix 已能解决当前问题，成本更低。

### 2.5 旧对象如何迁移

不需要为了目录美观立即搬全部旧对象。`source_document.file_key` 保存完整 Key，读取链路并不要求所有对象遵循同一代格式，因此可以：

1. 新上传和新 URL 版本开始使用 V2 Key；
2. 旧记录继续按旧 `file_key` 读取；
3. 若以后迁移，按“复制新 Key → 校验对象 → 数据库切换指针 → 删除旧 Key”执行，并记录可重试状态。

这是因为 S3 的“重命名”本来就是 copy + delete，而非廉价元数据修改。[AWS：Copying, moving, and renaming objects](https://docs.aws.amazon.com/AmazonS3/latest/userguide/copy-object.html)

## 3. 向量表与知识库隔离

### 3.1 xrag 当前状态

所有向量位于同一张 `document_chunk_embedding` 表，通过 `kb_id` 区分知识库；`embedding` 上有一个全局 HNSW 余弦索引，`kb_id` 另有 B-tree 索引：

- [V2__knowledge_base_schema.sql](../../backend/src/main/resources/db/migration/V2__knowledge_base_schema.sql#L105-L115)
- [architecture.md](../../tech/knowledge-base/architecture.md#L138-L144)

该表是派生索引，分块/文档禁用或删除时物理删除向量；业务表和向量表同在 PostgreSQL，可在同一事务中更新。这一点应保留。

### 3.2 HNSW + `kb_id` 过滤的真实行为

pgvector 官方说明：

- 精确检索默认可提供完整召回；近似 HNSW / IVFFlat 用召回率换速度。
- 对近似索引查询，过滤条件在索引扫描候选之后应用。假设过滤条件只匹配 10% 行，默认 `hnsw.ef_search = 40` 时平均只剩约 4 条匹配结果。
- pgvector 0.8.0 起支持 iterative index scan，可继续扫描，直到获得足够结果或达到扫描上限。
- 过滤值只有少数几个时可考虑 partial index；过滤值很多时可考虑分区。

来源：[pgvector 官方 README：Filtering / Iterative Index Scans](https://github.com/pgvector/pgvector#filtering)。

这意味着“表上同时存在 `kb_id` B-tree 和 HNSW”不等于 HNSW 在图遍历前已经按知识库裁剪。单表方案仍然可行，但必须在检索实现和测试里处理过滤召回问题。

### 3.3 当前建议

**保持单表，先不按知识库动态建表或独立 collection。** Phase 3 检索侧至少加入以下验收：

1. 所有相似度查询必须带 `WHERE kb_id = ?`。
2. 显式启用 `hnsw.iterative_scan = strict_order`（或经过验证后使用 `relaxed_order`）。
3. 对“一个超大知识库 + 多个小知识库”的倾斜数据测 top-k 召回，不能只测均匀数据。
4. 用 `EXPLAIN (ANALYZE, BUFFERS)` 观察实际执行计划，并比较精确检索与 HNSW。
5. 对分块量很小的知识库，精确检索可能已经足够；不要为了使用 HNSW 而使用 HNSW。

### 3.4 何时再考虑分区或分表

PostgreSQL 官方建议仅在表足够大、访问模式能有效裁剪分区时使用分区；分区过多会增加规划时间和每个会话的内存消耗。官方给出的经验是：表规模通常应大到超过数据库服务器物理内存，分区收益才更可能值得。[PostgreSQL：Table Partitioning](https://www.postgresql.org/docs/current/ddl-partitioning.html)

建议触发条件：

| 现象 | 下一步 |
|---|---|
| 当前个人项目规模、召回和延迟正常 | 保持单表 |
| 少数几个超大知识库，`kb_id` 过滤后 HNSW 召回明显不足 | 评估按 `kb_id` LIST 分区，让分区有独立 HNSW |
| 知识库数量很多 | 不要机械地“一库一分区”；评估固定数量 HASH 分区，或继续单表 |
| 需要同时支持不同向量维度 | 按维度/模型分表，比按知识库分表更自然 |
| 每个知识库有独立权限、备份、SLA 或物理隔离要求 | 才评估独立表、schema、数据库或真正的独立向量服务 |

文章中的 Milvus collection 是 Milvus 的原生隔离单位；在 pgvector 中不必为了概念对齐而人为模拟成每知识库一张表。

## 4. 删除、版本与生命周期一致性

### 4.1 当前正确的部分

- 分块和向量同在 PostgreSQL；入库、禁用和删除可以在一个数据库事务内保持原子性。
- 向量是可重建的派生索引，物理删除合理。
- 原文件是恢复所需的主数据，逻辑删除时保留合理。

当前知识库删除按 `kb_id` 物理删除向量、逻辑删除分块和文档：

- [KnowledgeBaseRepository.java](../../backend/src/main/java/com/app/knowledge/repository/KnowledgeBaseRepository.java#L95-L121)

### 4.2 最高优先级缺口：URL 同步覆盖 active 原文

当前 URL 同步确认内容变化后：

1. 用原 `document.fileKey()` 覆盖 RustFS 对象；
2. 更新内容哈希；
3. 再 CAS 抢占并创建异步入库任务。

证据：[ScheduledSyncScanner.java](../../backend/src/main/java/com/app/knowledge/ingestion/ScheduledSyncScanner.java#L100-L127)。

如果 CAS 没抢到、任务失败或进程崩溃：

- `source_document.file_key` 指向的对象已经是新内容；
- `document_chunk` 和 `document_chunk_embedding` 仍可能是旧内容；
- 用户打开“原文”看到的内容与当前检索内容不一致。

建议流程：

```text
抓取并计算 hash
  → PUT 到新的不可变 version Key
  → ingestion_run 记录 input_file_key / input_content_hash
  → 任务始终读取本次 run 绑定的 Key
  → 文本提取、分块、Embedding
  → 一个 PostgreSQL 事务：
       替换分块
       替换向量
       source_document.active_file_key = input_file_key
       更新 active_content_hash / revision / status
  → 旧 active 对象进入保留或待清理状态
```

若任务失败，active Key 不变，新对象作为 pending/orphan 留给重试或清理。即使开启 S3 Versioning，也仍需在数据库记录具体 `versionId` 或使用唯一 Key，否则业务层无法知道哪一版对象对应当前分块。

### 4.3 上传成功、数据库 INSERT 失败的孤儿

当前上传顺序是“PUT 对象 → INSERT `source_document`”，设计文档也明确接受 INSERT 失败产生孤儿对象：

- [DocumentService.java](../../backend/src/main/java/com/app/knowledge/service/DocumentService.java#L117-L139)
- [architecture.md](../../tech/knowledge-base/architecture.md#L40-L50)

可以继续接受这种非原子性，但应兑现对账能力：

- 上传失败时已知 Key 的对象先 best-effort 删除；
- 定期列举 `knowledge-bases/`，与未删除文档、仍在恢复宽限期的已删除文档和活动任务 Key 对账；
- 只清理超过安全窗口且未被任何记录引用的对象；
- 对账过程要分页、可中断、可重试，先报告再删除。

本地 1,833 个对象对 12 条 `file_key` 的悬殊比例还暴露了测试隔离问题：

- [DocumentUploadIntegrationTests.java](../../backend/src/test/java/com/app/knowledge/DocumentUploadIntegrationTests.java#L29-L37) 使用真实 RustFS，同时类级 `@Transactional` 让数据库在用例结束后回滚。
- PostgreSQL 回滚不会撤销外部 S3 PUT；该测试也没有 `@AfterEach` 删除对象，于是数据库知识库/文档消失，对象继续保留。
- 其他测试即使在 `@AfterEach` 物理删除数据库记录，只要不删除对应对象，也会制造同类孤儿。[UrlDocumentIntegrationTests.java](../../backend/src/test/java/com/app/knowledge/UrlDocumentIntegrationTests.java#L91-L113)

测试建议：

1. 测试使用独立 Bucket，或至少统一使用 `tests/{suiteRunId}/...` 前缀；不能和业务的 `knowledge-base/` 共用命名空间。
2. `suiteRunId` 在整个测试进程内唯一，套件结束时只删除该前缀，避免误删开发数据。
3. 单测失败或进程被中断时，下一次测试启动先清理由该测试身份创建、且超过短宽限期的旧测试前缀。
4. 保留至少一个“真实 RustFS + 真实数据库”的端到端用例，但它必须显式登记并清理本次创建的所有 Key；其余不验证 S3 协议本身的测试可以用隔离实现，减少对象堆积。
5. 业务孤儿对账与测试清理分开实现：业务对账必须以数据库全量引用集为准并设置更长宽限期，不能用“不是 active 知识库前缀”这种粗糙规则删除。

### 4.4 逻辑删除与永久清理

建议把两个动作分开：

1. **业务删除**：逻辑删除知识库/文档/分块，立即物理删除向量；原始对象保留，可恢复。
2. **永久清理**：超过保留期后，由显式 purge 任务删除对象和已逻辑删除业务数据；这是不可逆操作，应有审计、dry-run 和重试。

S3 Versioning 可以通过 delete marker 保留旧版本，并可结合 Lifecycle 管理非当前版本。[AWS：S3 Versioning](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Versioning.html) [AWS：Lifecycle configuration elements](https://docs.aws.amazon.com/AmazonS3/latest/userguide/intro-lifecycle-rules.html)

但 xrag 当前固定使用 `rustfs/rustfs:1.0.0-alpha.72`，而 RustFS 官方仓库当前仍把 Lifecycle Management 标为 “Under Testing”。因此：

- 可以在独立测试环境验证 Versioning；
- 不要让 Lifecycle 成为“不会误删 active 原文”的唯一保护；
- 当前更稳妥的是数据库驱动的 purge/reconciliation 任务。

来源：[RustFS 官方仓库 Feature & Status](https://github.com/rustfs/rustfs#feature--status)；项目版本见 [docker-compose.yml](../../docker-compose.yml#L33-L45)。

### 4.5 知识库删除保护

指定文章选择“只要还有文档就拒绝删除”，这是合理的高风险操作保护，但不是唯一正确策略。xrag 当前选择确认后级联逻辑删除。

建议至少补两层保护：

1. 有 `RUNNING` / `QUEUED` 入库任务时返回 `409`，先取消或等待任务进入终态，防止删除后任务重新写入向量。
2. 有文档时要求显式强制语义，例如 `DELETE ...?force=true`，并在 UI 显示文档/分块数量和输入知识库名称确认；若产品更重视保守性，也可以直接采用文章的“先清空文档”规则。

选择哪种交互是产品决策；存储层不要求为了删除保护改成每知识库一个 Bucket 或一张向量表。

## 5. 推荐实施顺序

| 优先级 | 调整 | 验证标准 |
|---|---|---|
| P0 | URL 同步改成不可变版本 Key + 成功后切换 active 指针 | 模拟 Embedding 失败后，原文、分块、向量仍全部指向旧版本 |
| P0 | 集成测试使用独立 Bucket/前缀并在 suite 后清理 | 连续运行完整测试多次，业务前缀对象数不增长；失败中断后能清理旧测试前缀 |
| P1 | 新对象采用可读且不可变的知识库前缀 | RustFS 控制台第一层可识别知识库；知识库改名不搬对象 |
| P1 | 删除知识库阻止正在执行/排队的任务 | 删除后不存在任务回写分块或向量 |
| P1 | 孤儿对象对账任务 | dry-run 用含逻辑删除记录的全量 Key 集合，列出无任何数据库引用且超过安全窗口的对象 |
| P2 | Phase 3 检索加入 HNSW iterative scan 与召回测试 | 倾斜知识库数据下 top-k 数量、召回和延迟达到约定阈值 |
| P3 | 数据量证明确有必要后再做向量分区 | 用基准测试证明收益高于分区维护成本 |
| P3 | 明确保留期后再做永久 purge | 可审计、可重试、默认 dry-run，不误删 active/pending 对象 |

## 6. 最终决策建议

- **改对象 Key：是。**
- **每知识库新建 Bucket：当前否。**
- **每知识库新建 pgvector 表/collection：当前否。**
- **保留单表 + `kb_id`：是，但 Phase 3 必须补 iterative scan 和召回基准。**
- **URL 同步改不可变版本对象：必须，且优先级高于目录可读性。**
- **逻辑删除保留原文件：可以，但必须补 purge 与孤儿对账。**
- **测试 Bucket/前缀隔离与 suite 清理：必须立即补；当前本地已经积累约 316 MB 对象。**
- **知识库删除保护：至少阻止活跃任务；是否强制先清空文档由产品选择。**
