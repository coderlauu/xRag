# 测试用例矩阵：AI 知识库建设模块

- `status`: approved（`2026-07-25`）
- `related_docs`: [架构方案](architecture.md)、[数据模型](data-model.md)、[API 契约](api.md)、[界面规格](ui-spec.md)、[PRD](../../docs/prd/2026-07-25-knowledge-base-prd.md)

本文件在后端实现开始之前写完。顺序不是形式问题：先写清"什么算对"，实现才有明确目标；反过来先实现再补测试，测试往往只会验证已经写出来的行为，而不是应该有的行为——那种测试全绿的时候最危险。

## 1. 怎么读这份矩阵

**编号**：`<域>-<序号>`，域前缀固定为 KB（知识库）/ DOC（文档）/ ING（入库任务）/ CHK（分块）/ SYNC（定时同步）/ DEL（逻辑删除一致性）/ LIM（上传安全与限流）/ EMB（Embedding）。编号一经分配不复用，删掉的用例留空号并注明原因，这样工单和提交信息里引用的编号永远指向同一件事。

**层级**：

| 标记 | 含义 | 依赖 |
|---|---|---|
| `单测` | 纯函数，无 Spring 上下文 | 无 |
| `集成` | `@SpringBootTest` + 真实 Postgres，显式开启 Flyway，Embedding 用确定性假实现 | `docker compose up -d postgres` |
| `手工` | 需要浏览器、真实 API Key，或需要观察进程级指标 | 见 §6 逐条说明 |

`集成` 层为什么必须用真实 Postgres 而不是 H2：本模块的正确性有相当一部分**落在数据库里**——部分唯一索引 `where deleted = false`、check 约束、`vector(1024)` 类型、HNSW 索引、`insert ... on conflict`。H2 一条都不支持，用它跑出来的绿灯没有意义。

**Embedding 假实现**：`集成` 层统一注入一个由文本内容确定性派生向量的假实现（同样文本必得同样向量，不同文本必得不同向量）。这让"向量是否被正确写入/删除/更新"可断言，同时不产生任何真实费用。真实 API 只在 §6 手工项里验一次。

## 2. KB — 知识库管理

| 编号 | 前置状态 | 操作 | 预期结果 | 层级 |
|---|---|---|---|---|
| KB-01 | 空库 | `POST` 创建，name="产品库" | `201`，`embeddingModel`/`embeddingDimensions` 为服务端配置值，`documentCount`/`chunkCount` 均为 0 | 集成 |
| KB-02 | 已有 name="产品库" | `POST` 同名 | `400 INVALID_REQUEST`，库中仍只有 1 条 | 集成 |
| KB-03 | 已有 name="产品库" 且已逻辑删除 | `POST` 同名 | `201` 成功。表里两行同名，一行 `deleted=true` | 集成 |
| KB-04 | — | `POST`，请求体带 `embeddingModel="伪造模型"` | `201`，落库值仍为配置值（客户端传入被忽略，不是报错） | 集成 |
| KB-05 | — | `POST`，name 为空串 / 129 字符 | 两次都 `400` | 集成 |
| KB-06 | 3 个知识库，其中 1 个已逻辑删除 | `GET` 列表 | `total=2`，已删除的不出现 | 集成 |
| KB-07 | 知识库下 5 篇文档（1 篇已逻辑删除）、共 100 个分块（10 个已逻辑删除） | `GET` 列表 | `documentCount=4`，`chunkCount=90` | 集成 |
| KB-08 | — | `GET` 不存在的 kbId | `404 NOT_FOUND` | 集成 |
| KB-09 | 知识库已逻辑删除 | `GET` 它 | `404 NOT_FOUND`，响应体与 KB-08 **完全一致** | 集成 |
| KB-10 | — | `PUT` 改 name + description | `200`，两个字段都更新，`updateTime` 变化 | 集成 |
| KB-11 | — | `PUT` 传 `embeddingModel` | `200`，该字段未变（忽略而非报错，与 KB-04 一致） | 集成 |
| KB-12 | 知识库下 2 篇文档、20 个分块、20 条向量 | `DELETE` | `204`；知识库/文档/分块全部 `deleted=true`，向量表该 kb_id 行数为 **0**（物理删除） | 集成 |
| KB-13 | 同上，删除后 | `GET` 该 kb 的文档列表 | `404`（父资源已删，不是返回空列表——空列表会让调用方以为知识库还在） | 集成 |
| KB-14 | 知识库下有文档 | `DELETE` | 不因"下面有文档"而拒绝，直接成功。逻辑删除下"防误删"没有必要性 | 集成 |

## 3. DOC — 文档管理

### 3.1 上传

| 编号 | 前置状态 | 操作 | 预期结果 | 层级 |
|---|---|---|---|---|
| DOC-01 | 空知识库 | 上传 1KB `.txt` | `201`，`status=PENDING`、`revision=0`、`chunkCount=0`；对象存储中存在该 `file_key`；**分块表为空**（上传绝不触发分块） | 集成 |
| DOC-02 | — | 上传 `.exe` | `415 UNSUPPORTED_FILE_TYPE`，不落库、不写对象存储 | 集成 |
| DOC-03 | — | 上传扩展名大写 `.TXT` | `201`（白名单大小写不敏感） | 集成 |
| DOC-04 | — | 上传无扩展名文件 | `415`（不靠 Content-Type 猜，它可以撒谎） | 集成 |
| DOC-05 | — | 上传 51MB 文件（超 `max-file-size`） | `413 FILE_TOO_LARGE`，不落库；见 LIM-03 的连接行为断言 | 集成 |
| DOC-06 | — | 上传时不传 `name` | `201`，`name` 取原始文件名 | 集成 |
| DOC-07 | — | 上传时 `overlap=1000`、`chunkSize=1000` | `400`（须 `overlap < chunkSize`，相等也不行——相等意味着每个分块与前一个完全重叠，切不出进展） | 集成 |
| DOC-08 | — | 上传同一份文件两次 | 两次都 `201`，得到两个独立文档。**文档名不做唯一约束**是有意的（同一文件用不同分块参数各处理一份是合理场景） | 集成 |
| DOC-09 | 对象存储不可达 | 上传 | `5xx`，且**数据库中不留记录**（先传存储后写库的顺序保证了这一点）。**`2026-07-28` 执行：通过** —— `docker compose stop rustfs` 后上传得到 `500` + `{"error":"UPLOAD_FAILED","message":"文件保存失败，请稍后重试。"}`（契约形状，不是容器默认错误页），该库文档数上传前后均为 1 | 手工 |

### 3.2 URL 来源

| 编号 | 前置状态 | 操作 | 预期结果 | 层级 |
|---|---|---|---|---|
| DOC-10 | 本地 mock HTTP 服务返回一个 md 文件 | `POST .../documents/url`，`syncEnabled=false` | `201`，`sourceType=URL`；创建时已抓取并存入对象存储（`file_key` 非空） | 集成 |
| DOC-11 | mock 服务返回 404 | 同上 | `400`，**不落库**（抓取失败不留半成品记录） | 集成 |
| DOC-12 | — | `sourceUri` 为 `ftp://…` | `400`（只接受 http/https） | 集成 |
| DOC-13 | — | `syncEnabled=true`、`syncCron` 缺省 | `400`（开同步必须给 cron，数据库 check 约束也会兜） | 集成 |
| DOC-14 | — | `syncEnabled=true`、`syncCron="0 * * * * ?"`（每分钟） | `400`（间隔 1 分钟 < `min-interval` 10 分钟） | 集成 |
| DOC-15 | — | `syncCron="0 0 3 * * ?"` | `201`，`nextSyncTime` 为下一个 03:00 | 集成 |
| DOC-16 | — | `syncCron="不是cron"` | `400`（解析失败，与 DOC-14 的间隔超限区分开：两者都 400，但 message 不同） | 集成 |
| DOC-17 | mock 返回 HTML 页面 | 创建 URL 文档 | `201` **不报错**。Tika 能从 HTML 提取正文，噪声多是用户输入质量问题，不是系统错误 | 集成 |

### 3.3 查询与更新

| 编号 | 前置状态 | 操作 | 预期结果 | 层级 |
|---|---|---|---|---|
| DOC-18 | 5 篇文档，状态各异 | `GET` 列表带 `status=FAILED` | 只返回 FAILED 的 | 集成 |
| DOC-19 | 同上 | `GET` 列表带 `enabled=false` | 只返回禁用的 | 集成 |
| DOC-20 | 文档有 3 条入库任务历史 | `GET /documents/{id}` | 返回 `latestRun` 为 id 最大的那条 | 集成 |
| DOC-21 | 文档从未触发过分块 | `GET /documents/{id}` | `latestRun` 为 `null`，不报错 | 集成 |
| DOC-22 | FILE 来源文档 | `PUT` 传 `syncEnabled=true` | `400`（仅 URL 来源可改同步配置） | 集成 |
| DOC-23 | 文档 `status=RUNNING` | `PUT` 改 name | `409 DOCUMENT_PROCESSING` | 集成 |
| DOC-24 | 文档已成功分块（`revision=2`） | `PUT` 改 `chunkSize` | `200`，响应含 `needsRechunk=true`；**分块表内容未变**（不自动重新分块） | 集成 |
| DOC-25 | — | `PUT` 只传 `name` | `200`，其他字段（含分块参数）保持原值 | 集成 |
| DOC-26 | 文档 `status=RUNNING` | `DELETE` | `409 DOCUMENT_PROCESSING` | 集成 |
| DOC-27 | 文档有 30 个分块 + 30 条向量、`syncEnabled=true` | `DELETE` | `204`；文档与 30 个分块 `deleted=true`，向量 0 条，`sync_enabled=false`；**对象存储文件仍存在** | 集成 |

### 3.4 启用 / 禁用

| 编号 | 前置状态 | 操作 | 预期结果 | 层级 |
|---|---|---|---|---|
| DOC-28 | 文档启用，10 个分块（其中 3 个分块自身 `enabled=false`），向量 7 条 | 禁用文档 | `200`；向量 **0 条**；10 个分块的 `enabled` 字段**一个都没变**（保留用户此前的个别选择） | 集成 |
| DOC-29 | 承接 DOC-28 | 重新启用文档 | `200`；向量恢复为 **7 条**（只为 `enabled=true` 的分块重算）。这就是 DOC-28 不动分块 `enabled` 的意义 | 集成 |
| DOC-30 | 文档已禁用 | 再次禁用 | `200` 幂等返回，不报错，无向量操作 | 集成 |
| DOC-31 | 文档 `revision=0`（从未成功分块） | 启用 | `200` 正常返回，无向量可写也不报错 | 集成 |

## 4. ING — 触发分块与异步入库

| 编号 | 前置状态 | 操作 | 预期结果 | 层级 |
|---|---|---|---|---|
| ING-01 | 文档 `PENDING` | `POST .../ingestion-runs` | `202` 返回 `runId`，`ingestion_run.status=QUEUED`，文档 `status=RUNNING`（CAS 已抢占） | 集成 |
| ING-02 | 文档 `RUNNING` | 再次触发 | `409 DOCUMENT_PROCESSING`，**不新增 run 记录** | 集成 |
| ING-03 | 文档 `enabled=false` | 触发 | `409 INVALID_STATE`（给禁用文档分块会写向量，与"禁用即不参与检索"直接矛盾） | 集成 |
| ING-04 | 文档 `SUCCESS`，已有 20 个分块 + 20 条向量，`revision=1` | 触发重新分块，等待完成 | `revision=2`；旧 20 个分块 `deleted=true`、其向量物理删除；新分块 `revision=2`、向量条数与新分块数相等；`chunk_count` 等于新分块数 | 集成 |
| ING-05 | 文档 `FAILED` | 触发 | `202` 成功（`FAILED` 可直接重试，不必重新上传） | 集成 |
| ING-06 | 首次分块（旧分块集合为空） | 触发 | 走的是与 ING-04 **完全相同的代码路径**，无 if 分支区分首次/重复 | 集成 |
| ING-07 | 任务执行完成 | 查 run | `status=SUCCESS`，`revision`/`chunk_count` 已回填，`started_time`/`finished_time` 非空 | 集成 |
| ING-08 | Embedding 假实现被配置为抛异常 | 触发并等待 | run `status=FAILED`、`phase=EMBED`、`error_message` 非空；文档 `status=FAILED`；**分块表与向量表都没有该文档的新数据**（失败前没有部分写入） | 集成 |
| ING-09 | 承接 ING-08 | 检查 run 的失败信息 | 失败信息**确实落库了**（写在独立新事务里，没被外层回滚冲掉）。这条专门守住 architecture.md §3.2 那个"必须是新事务"的要求 | 集成 |
| ING-10 | 对象存储里的文件被手工删除后触发 | 触发并等待 | run `phase=DOWNLOAD`、`status=FAILED` | 集成 |
| ING-11 | 上传一个内容为 `.pdf` 但实际是乱码二进制的文件后触发 | 触发并等待 | run `phase=EXTRACT`、`status=FAILED`，错误信息可读 | 集成 |
| ING-12 | 队列中 5 条 `QUEUED` | 等待轮询 | 5 条都被执行；**没有任何一条被执行两次**（逐条 CAS `where status='QUEUED'` 保证） | 集成 |
| ING-13 | 手工插入 `RUNNING` 且 `heartbeat_time` 为 6 分钟前的 run | 触发超时回收 | run 与其文档都变 `FAILED`，`error_message` 说明是超时回收，且**不再声称失败发生在某一步**（`phase` 清空，见 ING-18） | 集成 |
| ING-14 | 手工插入 `RUNNING` 的 run 后重启应用 | 启动回收 | run 与其文档都变 `FAILED`，原因写明"进程重启"。**单实例假设下这个回收完全准确**（ADR 0002） | 集成 |
| ING-15 | 任务执行中 | 观察 `heartbeat_time` | 执行期间该字段持续被刷新（间隔约 10s） | 集成 |
| ING-16 | 大量分块的文档 | 触发并观察数据库连接 | 事务持续时间只覆盖 PERSIST 阶段；Embedding 调用发生在事务**外**。**`2026-07-28` 执行：通过**，与 M-03 是同一条用例的两个编号，完整数据见 §9 M-03 | 手工 |
| ING-17 | 执行中抛出 `Error`（非 `Exception`）| 触发并等待 | 文档与 run 都变 `FAILED`，且 `error_message` 是**真实原因**。只 `catch (Exception)` 的话任务会永远停在 `RUNNING`，五分钟后被超时兜底改写成一句"卡死"，真实原因永久丢失 | 集成 |
| ING-18 | `RUNNING` 的 run，心跳只存活了 1 秒就停 | 触发超时回收 | 消息指向"进程被停止或重启"而非"卡死"，并写明最后记录到的步骤；`phase` 被**清空** | 集成 |
| ING-19 | `RUNNING` 的 run，心跳正常跳了 2 小时后才停 | 触发超时回收 | 消息仍用"卡死"措辞（这才是真的卡在某一步），与 ING-18 形成对照 | 集成 |

## 5. CHK — 分块管理

### 5.1 分块算法（纯函数，无外部依赖）

| 编号 | 输入 | 预期结果 | 层级 |
|---|---|---|---|
| CHK-01 | `FIXED_SIZE`，空字符串 | 返回空列表，不抛异常，不产生 1 个空分块 | 单测 |
| CHK-02 | `FIXED_SIZE`，1 个字符，`chunkSize=1000` | 1 个分块，内容为该字符 | 单测 |
| CHK-03 | `FIXED_SIZE`，文本长度**恰好等于** `chunkSize` | **1 个分块**，不是 2 个（不产生尾部空分块） | 单测 |
| CHK-04 | `FIXED_SIZE`，长度 = `chunkSize + 1`，`overlap=100` | 2 个分块，第 2 个长度为 `101`（含重叠） | 单测 |
| CHK-05 | `FIXED_SIZE`，`overlap=0` | 分块无重叠，拼接后与原文逐字符相等 | 单测 |
| CHK-06 | `FIXED_SIZE`，`chunkSize=1000`、`overlap=999` | 能终止（每次至少前进 1 个字符），不死循环 | 单测 |
| CHK-07 | `RECURSIVE`，含 `\n\n` 段落且每段都短于 `chunkSize` | 按段落切，不在段落中间断开 | 单测 |
| CHK-08 | `RECURSIVE`，单个段落远超 `chunkSize` 且无任何分隔符 | 最终硬切，每块不超过 `chunkSize` | 单测 |
| CHK-09 | `RECURSIVE`，中文文本以 `。` 分句 | 在句号处切分 | 单测 |
| CHK-10 | 任意策略 | 每个分块的 `charCount` 等于其内容实际长度 | 单测 |
| CHK-11 | 纯中文 / 纯英文 / 混合文本 | `tokenCount` 按 1:1 / 4:1 / 2:1 规则估算。**它只用于界面展示，不参与任何逻辑判断** | 单测 |
| CHK-12 | 相同内容切两次 | `contentHash` 相同；内容差 1 个字符则不同 | 单测 |

### 5.2 分块的增删改

| 编号 | 前置状态 | 操作 | 预期结果 | 层级 |
|---|---|---|---|---|
| CHK-13 | 文档有 142 个分块，`chunk_index` 0~141 | `GET` 分块列表第 1 页 | 按 `chunk_index` **升序**，不是按 id | 集成 |
| CHK-14 | 分块 5 已逻辑删除 | `GET` 列表 | `total` 少 1，该分块不出现 | 集成 |
| CHK-15 | 文档启用、不在 RUNNING | `POST` 新增分块，不传 `chunkIndex` | `201`，`chunkIndex` 为 `max+1` | 集成 |
| CHK-16 | 文档最大 `chunk_index=10`，其中 index=10 的分块**已被逻辑删除** | `POST` 新增分块 | `chunkIndex=11`。若实现误用 `count(*)` 会算出 10，与已删除分块的序号撞车——这条就是为守住 `max+1` 而存在的 | 集成 |
| CHK-17 | 文档 `enabled=false` | `POST` 新增分块 | `409 INVALID_STATE`。这条最容易漏：否则会出现"文档整体不参与检索、但新加的这段能被检索到"的矛盾 | 集成 |
| CHK-18 | 文档 `RUNNING` | `POST` 新增分块 | `409 DOCUMENT_PROCESSING` | 集成 |
| CHK-19 | — | `POST` 新增分块，指定已存在的 `chunkIndex=42` | `201`，两条 index=42 的分块共存，**其他分块序号不被重排** | 集成 |
| CHK-20 | 分块已启用有向量 | `PUT` 改成不同内容 | `200`；`charCount`/`tokenCount`/`contentHash` 已重算；向量表该 `chunk_id` 仍只有 **1 行**（delete+insert 同一主键），且向量值已变化 | 集成 |
| CHK-21 | 同上 | `PUT` 传**与库中完全相同**的内容 | `200`；**未发生任何向量操作**（省下按 token 计费的调用，也避免删旧插新期间的检索空窗）。断言手段：假 Embedding 实现记录调用次数，本次为 0 | 集成 |
| CHK-22 | 分块 `enabled=false`（无向量） | `PUT` 改内容 | `200`，内容更新，**仍然没有向量**（禁用状态不因编辑而恢复） | 集成 |
| CHK-23 | 文档 `chunk_count=10` | `DELETE` 一个分块 | `204`；该分块 `deleted=true`、其向量物理删除；文档 `chunk_count=9`；**同文档其他分块的向量不受影响** | 集成 |
| CHK-24 | 手工把文档 `chunk_count` 改成 0，仍有分块存在 | `DELETE` 一个分块 | `chunk_count` 保持 0，**不变成 -1**（`case when chunk_count > 0` 防护） | 集成 |
| CHK-25 | 分块禁用、父文档启用 | 启用该分块 | `200`，向量被写入 | 集成 |
| CHK-26 | 分块禁用、**父文档禁用** | 启用该分块 | `409 INVALID_STATE` | 集成 |
| CHK-27 | 分块启用、**父文档禁用** | 禁用该分块 | `200` 成功。**禁用时不校验父文档**——禁用不需要父文档处于任何特定状态 | 集成 |
| CHK-28 | 分块已是目标状态 | 再次设为同一状态 | `200` 幂等，无向量操作 | 集成 |
| CHK-37 | 分块已启用，Embedding 供应商故障 | `PUT` 改内容 | `502 EMBEDDING_FAILED`，且分块内容**原封不动**。这是唯一一条同步调用 Embedding 的路径（入库那条是异步的，失败写进任务记录），也是"Embedding 在事务外算完才开事务"最直接的证据——失败时事务根本没开始，不存在"内容改了但向量还是旧的"这种半截状态 | 集成 |
| CHK-38 | 文档 `RUNNING` | `PUT` 改内容 / `DELETE` 分块 | 两者均 `409 DOCUMENT_PROCESSING`，`message` 与界面禁用按钮的 tooltip 逐字相同（[ui-spec.md](ui-spec.md) §3） | 集成 |

### 5.3 批量启用/禁用

| 编号 | 前置状态 | 操作 | 预期结果 | 层级 |
|---|---|---|---|---|
| CHK-29 | 3 个分块，其中 1 个已是目标状态 | 批量设置 | `200`，`{requested:3, changed:2, alreadyInTargetState:1}` | 集成 |
| CHK-30 | — | `chunkIds` 为空数组 | `400`（不支持"全部"语义，那个需求该用文档级接口） | 集成 |
| CHK-31 | 501 个合法分块 | 批量设置 501 个 | `400`（超单次上限 500） | 集成 |
| CHK-32 | **恰好 500** 个合法分块 | 批量设置 | `200`，全部处理成功（边界包含） | 集成 |
| CHK-33 | 500 个合法 id + 1 个属于**其他文档**的 id | 批量设置 | `400` **整批失败**，一个都没改。不静默跳过——否则调用方以为 501 个都处理了、实际 500 个，比直接报错难排查得多 | 集成 |
| CHK-34 | 含 1 个已逻辑删除的分块 id | 批量设置 | `400` 整批失败（已删除等同不存在） | 集成 |
| CHK-35 | 全部分块都已是目标状态 | 批量设置 | `200`，`changed:0`，**不报错**（"部分已达目标"是批量操作常态） | 集成 |
| CHK-36 | 批量**启用** | 观察事务行为 | Embedding 调用发生在事务外；该方法上**没有** `@Transactional` 注解（否则内层编程式事务会加入外层，精确控制事务范围的意图落空）。**`2026-07-25` 由手工改为集成**：假 Embedding 实现在被调用时记录 `TransactionSynchronizationManager.isActualTransactionActive()`，一条用例同时覆盖编辑分块 / 新增分块 / 批量启用三条同步路径。已验证过它的区分力——临时给方法加上 `@Transactional` 后该用例立刻失败 | 集成 |

## 6. SYNC — 定时同步

| 编号 | 前置状态 | 操作 | 预期结果 | 层级 |
|---|---|---|---|---|
| SYNC-01 | URL 文档已成功分块，mock 服务返回**相同 ETag** | 触发扫描 | 新增 run `status=SKIPPED`；`next_sync_time` 推进；**分块与向量完全未变**；`revision` 未变 | 集成 |
| SYNC-02 | mock 服务**不返回 ETag/Last-Modified**，但内容与 `content_hash` 一致 | 触发扫描 | 同样 `SKIPPED`。第二级（内容哈希）兜住了第一级不可用的情况 | 集成 |
| SYNC-03 | mock 服务返回**新 ETag 且内容确实变了** | 触发扫描 | 走完整入库链路，`revision+1`，旧分块逻辑删除、新分块与新向量写入 | 集成 |
| SYNC-04 | mock 服务返回**新 ETag 但内容没变** | 触发扫描 | `SKIPPED`（ETag 变了但哈希相同，第二级拦住了误更新） | 集成 |
| SYNC-05 | `sync_enabled=false` 的 URL 文档 | 触发扫描 | 不被扫到 | 集成 |
| SYNC-06 | 已逻辑删除的 URL 文档，`sync_enabled` 仍为 true | 触发扫描 | 不被扫到（扫描 SQL 带 `deleted=false`） | 集成 |
| SYNC-07 | 文档 `status=RUNNING` | 触发扫描 | 跳过，不产生 run（与手动触发抢的是同一行同一个状态字段，先到先得） | 集成 |
| SYNC-08 | `next_sync_time` 在未来 | 触发扫描 | 不被扫到 | 集成 |
| SYNC-09 | mock 服务超时 | 触发扫描 | run `phase=DOWNLOAD`、`status=FAILED`；`next_sync_time` 仍然推进（否则每次扫描都重试同一个坏 URL） | 集成 |
| SYNC-10 | FILE 来源文档 | 触发扫描 | 不被扫到（部分索引与 SQL 都限定 `source_type='URL'`） | 集成 |
| SYNC-11 | 同一文档同时被手动触发和定时扫描命中 | 并发执行 | 只有一个成功抢占，另一个跳过；**不产生两条 RUNNING 的 run** | 集成 |

## 7. DEL — 逻辑删除一致性

这一组是横向的，**故意违反"按垂直切片组织用例"的原则**。理由：`repository` 层每个查询方法都必须带 `deleted = false`，这是一条靠约定维持的纪律，漏写不会有任何编译或运行时报错，只会让已删除数据静默地重新出现在某一个接口里。散在各功能域里的用例只覆盖各自的主路径，没有任何一处会系统性地检查"每个查询方法都过滤了吗"。

**方法**：建一个夹具，往每张表插入成对的数据（一条正常、一条 `deleted=true`），然后逐个调用所有查询入口，断言已删除的那条一处都不出现。

| 编号 | 查询入口 | 预期结果 | 层级 |
|---|---|---|---|
| DEL-01 | 知识库列表 | 已删除知识库不出现 | 集成 |
| DEL-02 | 知识库详情 | `404` | 集成 |
| DEL-03 | 知识库的 `documentCount` 聚合 | 不计入已删除文档 | 集成 |
| DEL-04 | 知识库的 `chunkCount` 聚合 | 不计入已删除分块 | 集成 |
| DEL-05 | 文档列表（含带 `status`/`enabled` 过滤的分支） | 已删除文档不出现。**过滤分支要单独验**——`deleted=false` 很容易只加在无过滤的那条 SQL 上 | 集成 |
| DEL-06 | 文档详情 | `404` | 集成 |
| DEL-07 | 分块列表（含带 `enabled` 过滤的分支） | 已删除分块不出现 | 集成 |
| DEL-08 | `max(chunk_index)` 查询 | 见 CHK-16。这里**恰恰相反**：必须**包含**已删除分块，否则新分块序号会与已删除分块撞车 | 集成 |
| DEL-09 | 批量分块归属校验 | 已删除分块 id 视为无效 → `400`（同 CHK-34） | 集成 |
| DEL-10 | 定时同步扫描 | 已删除文档不被扫到（同 SYNC-06） | 集成 |
| DEL-11 | 入库任务历史列表 | 文档删除后其 run 历史**仍可查**（run 表本身不做逻辑删除，它是执行记录；但要确认这不会成为一条读取已删除文档内容的旁路——run 表不含文档内容） | 集成 |
| DEL-12 | 向量表 | 全表扫描断言：**每一行都对应一个未删除、已启用、且所属文档也未删除已启用的分块**。这是 Phase 3 检索侧直接依赖的不变量，必须由本模块保证 | 集成 |

DEL-08 是这一组里最值得注意的一条：它说明 `deleted = false` **不是**可以机械套用到每条 SQL 上的模板。查询"下一个可用序号"时必须看见已删除的行，否则就会分配出撞车的序号。把纪律当模板套，恰好会在这里出错。

DEL-12 应该作为**每个集成测试类的通用收尾断言**，而不只是一条独立用例——任何一个操作破坏了这条不变量，都应该在那个测试里立刻暴露，而不是等跑到 DEL-12 才发现。

### 7.1 逐方法审计结果（`2026-07-26`，工单 18）

清点了 `repository` 与 `vector` 两个包的**全部** 5 个类，逐条 SQL 核对 `deleted = false`。

**结论：一处漏写都没有。** 包括最容易漏的两个地方——`KnowledgeBaseRepository.SELECT_WITH_COUNTS` 里统计 `documentCount` / `chunkCount` 的两个**子查询**（漏了的话界面上会显示一个永远对不上的数字），以及 `findDueForSync` / `findRunningIds` 这两条**后台任务用的查询**（漏了的话已删除文档会被反复抓取或被写上失败信息，而这两条路径没有用户会去点，bug 可以潜伏很久）。

**两处有意不带 `deleted = false`**，都写在各自的方法注释里，并各有一条用例把它们钉住（防止后来者"顺手补上"）：

| 位置 | 为什么必须不带 |
|---|---|
| `DocumentChunkRepository.maxChunkIndex` | 已删除分块的序号必须继续被占着，否则新分块与它撞号，序号"在原文中的位置"这个含义就乱了 |
| `IngestionRunRepository` 全部查询 | 日志性质的记录，那张表压根没有 `deleted` 列，也没有"删除"这个操作 |

`document_chunk_embedding` 整张表只做物理删除（PRD §7.6 例外 1），不适用这条纪律。

落成 `LogicalDeleteConsistencyTests` 共 12 条，**方法清单写进了该类的 Javadoc**——新增查询方法时对照补用例，比在这份文档里维护一份会过期的副本可靠。

## 8. LIM — 上传安全与限流

| 编号 | 前置状态 | 操作 | 预期结果 | 层级 |
|---|---|---|---|---|
| LIM-01 | `max-concurrent=1` | 两个上传请求并发 | 一个成功，另一个 `429 UPLOAD_BUSY`（等待超时后）。**`2026-07-26` 改为单测**：集成测试里没有可靠手段把一个上传精确卡在持有许可的状态，只能传大文件赌它比超时更慢，那是典型的 flaky；改用阻塞在 `CountDownLatch` 上的假 `FilterChain`，时序完全确定 | 单测 |
| LIM-02 | `max-concurrent=1`，第一个上传完成后 | 再发一个上传 | 成功（许可已释放） | 单测 |
| LIM-03 | 上传中途抛异常 | 观察后续请求 | 许可仍被正确释放，后续上传不受影响。**这条验的是 finally 释放**——漏了 finally 的信号量会在第一次异常后永久少一个许可，几次之后上传彻底不可用，症状却是"偶尔上传不了"这种最难查的形态 | 单测 |
| LIM-06 | `max-concurrent=0` | 发一个上传 + 三个非上传请求 | 上传 `429` 且响应体是契约形状；**三个非上传请求全部 200**。这条守的是单测守不住的两件事：Filter 真的被注册进了 servlet 过滤器链（`@Component` 自动注册，哪天被关掉单测照样全绿）、以及路径判断没写错（写错的话许可为 0 时**全站所有接口都会 429**） | 集成 |
| LIM-04 | — | 限流触发时观察请求体是否被读取 | 请求体**未被读取**。许可在 `Filter` 里获取，multipart 由 `DispatcherServlet` 解析（时机在 Filter 之后），所以拒绝发生在读盘之前。**`2026-07-26` 由手工改为单测**：断言被拒的请求**根本没进入 filter chain**——multipart 正是由 chain 下游的 `DispatcherServlet` 解析的，chain 没被调用就意味着请求体没被读、临时文件无从产生。这比去 `/tmp` 数文件更强：数不到也可能只是刚好被清理了 | 单测 |
| LIM-05 | 上传 51MB 文件 | 观察连接行为 | 服务端不读完整个 51MB 就断开。**`2026-07-28` 执行：先不通过，查出 `max-request-size=60MB` 让 50~60MB 的文件绕过 `Content-Length` 预判、被完整读完（25.1s）；改为 51MB 后 0.036s 拒绝。§9 M-05 有完整数据** | 手工 |

## 9. 手工验证项

手工不等于"有空再做"，这些是正式验收清单的一部分。每条都写清了用什么命令看、什么数值算通过——没有明确判定标准的手工项等于没做。**有判定标准却没记结果的，同样等于没做**，所以每条下面都有一段"执行结果"。

### 执行状态一览（`2026-07-28`）

| 编号 | 内容 | 状态 |
|---|---|---|
| M-01 | 上传 48MB 的堆内存 | ✅ 通过 `2026-07-25` |
| M-02 | URL 下载 48MB 的堆内存 | ✅ 通过 `2026-07-28` |
| M-03 | 事务范围不含外部 IO（= ING-16） | ✅ 通过 `2026-07-28` |
| M-04 | 限流拦截早于读请求体（= LIM-04） | ✅ 已转为单测 `2026-07-26`，不再手工执行 |
| M-05 | 超限请求不被读完（= LIM-05） | ⚠️ **首次不通过，查出配置缺陷，修正后通过** `2026-07-28` |
| M-06 | 真实 Embedding API 连通性 | ✅ 通过 `2026-07-28`（并由此发现 §10.1 的限流缺陷） |
| M-07 | 未配置 Key 时应用仍能启动 | ✅ 通过 `2026-07-28` |
| M-08 | 维度不一致时启动失败 | ✅ 通过 `2026-07-28` |
| M-09 | 三个页面的浏览器实测 | ✅ 通过 `2026-07-28`（项目所有者执行）——**并揪出 `Modal.tsx` 在 StrictMode 下弹层静默失效**，163 条接口测试一条都发现不了 |
| M-10 | 处理进行中终止进程（工单 11 遗留） | ✅ 通过 `2026-07-28` |
| DOC-09 | 对象存储不可达时上传 | ✅ 通过 `2026-07-28` |

**十条执行掉的手工项里，三条抓到了真问题**——M-05 的 `max-request-size`、M-06 顺带发现的出站限流、M-09 的 `Modal` StrictMode 缺陷，都已修复并回归。这个比例值得记下来：如果它们继续以"有空再做"的状态挂着，三个缺陷都会活到生产。

尤其 **M-09 是唯一一条机器替不了的**：前端没有测试脚本，163 条后端测试全绿也证明不了"弹层真的出现在屏幕上"。它抓到的恰恰是三个缺陷里症状最迷惑的一个（所有按钮看起来没反应、控制台一片干净）。

### M-01 上传 30MB 文件的堆内存 —— 最高优先级

**这是唯一一条可能推翻技术方案的用例。** [architecture.md §7](architecture.md) 赌的是 `RequestBody.fromFile` 不会把整个文件读进堆（与学习笔记 03-03 里 `fromBytes`/`fromInputStream` 的堆放大问题不同）。如果赌错，上传链路必须退到预签名 URL 方案，前端也要跟着改。

判定标准做成**二元**的，而不是让人对着堆曲线目测：

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw -q -B package
```

```bash
java -Xmx256m -XX:+HeapDumpOnOutOfMemoryError \
  -XX:StartFlightRecording=filename=/tmp/upload.jfr,settings=profile \
  -jar backend/target/backend-0.0.1-SNAPSHOT.jar
```

```bash
head -c 31457280 /dev/urandom | base64 > /tmp/big.txt && ls -lh /tmp/big.txt
```

```bash
curl -i -F "file=@/tmp/big.txt" http://localhost:3001/api/v1/knowledge-bases/1/documents/file
```

- **通过**：`201`，进程没有 OOM。堆上限 256MB 远小于"文件 30MB + SigV4 摘要放大"所需，能在这个上限下跑完，就说明请求体确实没有被整体读进堆。
- **不通过**：`OutOfMemoryError`（会自动落 heap dump）→ 按 architecture.md §7 记录的退路改用预签名 URL 方案，并回头更新技术方案与 PRD。

峰值数据留档备查：

```bash
jfr print --events jdk.GCHeapSummary /tmp/upload.jfr | grep -A3 heapUsed | tail -40
```

注意 `-Xmx256m` 只是把判定变尖锐的手段，不是生产配置建议。

> #### 实测结果（`2026-07-25`）：**通过。技术方案不需要退回预签名 URL 方案。**
>
> 执行时把文件从 30MB **加到 48MB**（`max-file-size` 上限 50MB 以内的最大值），因为 30MB 按学习笔记 03-03 记录的约 3.3 倍放大也只有 ~100MB，仍然装得进 256MB 堆——那样的话"没 OOM"证明不了什么。48MB 若被整体缓冲，加上 SigV4 摘要放大会逼近甚至超过堆上限，判定才真正尖锐。
>
> | 观测点 | 数值 |
> |---|---|
> | 上传前堆 used | 38.7 MB |
> | 单次 48MB 上传后堆 used | 34.8 MB |
> | 连续 4 次 48MB 上传，堆 used 峰值 | 51.4 MB |
> | **堆 total（committed）全程** | **61.4 MB，一次都没扩张** |
> | HTTP / 耗时 | `201`，0.72s |
> | OOM / heap dump | 无 |
> | 对象存储实际落盘 | `part.1` 50,333,184 字节（源文件 50,331,648 + RustFS 分片对齐），内容完整 |
>
> 最有说服力的不是"没 OOM"，而是**堆 committed 全程停在 61MB 没有扩张过**：JVM 连要更多堆的必要都没有，说明 48MB 的请求体压根没进过堆。`RequestBody.fromFile` 的行为与 [architecture.md §7](architecture.md) 的预期一致。
>
> 顺带验掉了超限路径：55MB 文件返回 `413`，响应体是契约里的 `{"error":"FILE_TOO_LARGE","message":"文件超过 50MB 上限，无法上传。请压缩或拆分后重试。"}`，**不是容器默认错误页**——这需要显式处理 `MaxUploadSizeExceededException`，因为它在控制器方法之前由 multipart 解析器抛出。

### M-02 URL 来源下载 30MB 文件的堆内存

同 M-01 的进程与堆上限，改为创建一个指向 30MB 文件的 URL 文档。这条路径的风险点不同：下载走的是自己写的流式落盘代码，而不是 SDK。**通过标准同样是不 OOM**。

> **执行结果 `2026-07-28`：通过。** 与 M-01 同样把文件加到 **48MB**（理由见 M-01：30MB 即使按 3.3 倍放大也装得进 256MB 堆，那样"没 OOM"证明不了什么）。源站用 `python3 -m http.server` 起在本地——这里验的是自己写的流式落盘代码，与对端是谁无关。
>
> | 观测点 | 结果 |
> |---|---|
> | 堆上限 | `-Xmx256m` |
> | HTTP / 耗时 | `201`，0.61s |
> | 落库 `fileSize` | 50,331,600 字节，与源文件一致 |
> | OOM / heap dump | **无** |

### M-03 事务范围不含外部 IO

守 [architecture.md](architecture.md) 那条被学习笔记反复强调五次的纪律。触发一个能切出 200+ 分块的文档，然后：

```bash
docker compose exec -T postgres psql -U app -d app -c "select pid, state, now() - xact_start as xact_age, left(query, 60) from pg_stat_activity where datname='app' and xact_start is not null order by xact_age desc;"
```

在 Embedding 阶段（日志显示正在批量调用）反复执行上面这条命令。

- **通过**：Embedding 期间**没有**任何 `xact_age` 持续增长的连接；只在 PERSIST 阶段出现一个短命事务。
- **不通过**：存在一个横跨整个 Embedding 阶段的长事务 → 事务边界画错了，把外部 IO 圈进去了。

**CHK-36 已不再用这个手段**（`2026-07-25`）。同步接口那三条路径（编辑分块 / 新增分块 / 批量启用）改成了确定性的集成断言：假 Embedding 实现在被调用的那一刻记录 `TransactionSynchronizationManager.isActualTransactionActive()`，为 `true` 即失败。理由是这条纪律最典型的破坏方式是**给方法加一个 `@Transactional`**——加了之后内部的编程式事务会静默地加入外层事务，把网络调用圈进事务里却没有任何报错，而盯 `pg_stat_activity` 既贵又容易漏。本节的手工手段仍然适用于**异步入库链路**（M-03 本身），那条路径上没有可注入的断言点。

> **执行结果 `2026-07-28`：通过。** 用一份切出 700 分块的文档（1.6MB 纯文本）触发真实入库，在 `EMBED` 阶段每秒采样一次，**连续 25 次采样的最长事务年龄全部为 `0.00s`**。若事务边界画错、把 Embedding 圈进事务，这里会看到一个随时间单调增长的 `xact_age`。
>
> `PERSIST` 阶段**没有采样到**——0.5 秒的采样间隔抓不住它。这不是缺口而是佐证：该阶段全是本地写入，短到抓不着，正是 [architecture.md §3.2](architecture.md) 期望的形状。

### M-04 限流拦截发生在读请求体之前（LIM-04）

把 `max-concurrent` 设为 1，先发一个慢上传占住许可，再发第二个 30MB 上传，同时观察磁盘临时目录：

```bash
ls -l $TMPDIR | grep -i tomcat
```

- **通过**：第二个请求返回 `429`，且临时目录里**没有**为它产生新的临时文件。
- **不通过**：出现了第二个临时文件 → 限流拦得太晚（多半是错放在了 `HandlerInterceptor` 里），保护磁盘和 IO 的目的已经落空。

> **本条已于 `2026-07-26` 由手工改为单测（LIM-04），不再需要手工执行。** 新判据是"被拒的请求**根本没进入 filter chain**"——multipart 正是由 chain 下游的 `DispatcherServlet` 解析的，chain 没被调用就意味着请求体没被读、临时文件无从产生。这比去临时目录数文件更强：数不到也可能只是刚好被清理了。上面的手工步骤保留作为原理说明。

### M-05 超限请求不被读完（LIM-05）

```bash
curl -i --limit-rate 2M -F "file=@/tmp/big51.txt" http://localhost:3001/api/v1/knowledge-bases/1/documents/file
```

- **通过**：远早于 51MB 传完就收到 `413` 或连接被断开。
- **不通过**：服务端老老实实读完 51MB 才回 `413` → `server.tomcat.max-swallow-size` 没生效。

> **执行结果 `2026-07-28`：先不通过，查出配置缺陷，修正后通过。这是 §9 里唯一一条真正抓到问题的用例。**
>
> | `max-request-size` | 已上传字节 | 耗时 | 结果 |
> |---|---|---|---|
> | **60MB**（原默认值） | 52,494,336 | **25.1s** | ❌ 传完 51MB 需 25.5s，等于全读了 |
> | **51MB**（改后） | **65,536** | **0.036s** | ✅ |
>
> **相差 800 倍。** 根因不是 `max-swallow-size` 失效——它一直是生效的（50MB 文件体 + 2MB swallow ≈ 52.5MB，与实测吻合）。真正的问题是**两个上限的检查时机完全不同**：
>
> - `max-request-size` 由 Tomcat 用 **`Content-Length` 头预判**，超了立即拒绝，请求体一个字节都不读；
> - `max-file-size` 只能**流式**检查，必须真读满 50MB 才知道超限。
>
> 原先 `max-request-size` 留了 10MB 余量（60MB），于是 50~60MB 的文件 `Content-Length` 全部落在缝里，绕过头部预判、走进慢路径。`max-swallow-size` 这时只能省下最后 2MB，**意图被架空**。
>
> 修正为 `max-request-size=51MB`（紧贴 `max-file-size`）。1MB 余量足够覆盖 multipart 边界与表单字段（实际开销不到 1KB），回归验证 48MB 合法文件仍正常上传（`201`，0.57s）。
>
> 这条用例的价值正在于此：**只看状态码的话两种配置都返回 `413`，完全看不出区别**——差别在"付出了多少带宽和时间才拒绝掉"。

### M-06 真实 Embedding API 连通性

**外部阻塞项：需要一个可用的 `EMBEDDING_API_KEY`。** 集成测试全程用假实现，因此这是唯一一次验证真实供应商契约的机会。

- 配好 Key 启动，上传一个小 txt 并触发分块。
- **通过**：向量成功写入，且 `select vector_dims(embedding) from document_chunk_embedding limit 1` 返回 **1024**，与 `vector(1024)` 一致。
- 顺带核实 `app.embedding.batch-size` 默认值：构造一个能切出明显多于 batch-size 的分块数的文档，确认分批串行请求全部成功。**超上限的症状是"大文档第一批就整体失败、小文档完全正常"**，很容易被误判成偶发网络问题。

> **执行结果 `2026-07-28`：通过。**
>
> | 检查项 | 结果 |
> |---|---|
> | 真实 Key 调用 | `RealEmbeddingApiTests` 两条全绿 |
> | `vector_dims(embedding)` | **1024**，与 `vector(1024)` 一致 |
> | 真实业务文档端到端 | 一份 1.8MB PDF：`SUCCESS`，8 分块 / 8 向量 |
> | 分批串行（远超 batch-size） | 一份 **700 分块**的文档全部成功，700 分块 / 700 向量 |
>
> **正是这条"顺带核实"抓出了 §10.1 那个限流缺陷**：700 分块要串行发 70 次请求，第一次跑在 8.4 秒后整体失败于 `429`。工单原文预设的失败症状是"超 batch-size 上限"，实际撞上的是**频率上限**——两者症状相似（大文档整体失败、小文档正常），但原因和修法完全不同。
>
> 同时修掉了一条本来就会随机变红的断言：余弦阈值 0.999 低于实测的抖动下界 0.9977，改为 0.95（依据见该用例注释）。

### M-07 未配置 API Key 时应用仍能启动

```bash
EMBEDDING_API_KEY= JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw -q -B spring-boot:run
```

- **通过**：应用正常启动，`GET /api/v1/health` 返回 200；触发分块时得到一个**明确说明"未配置 API Key"**的错误，而不是 NPE 或超时。
- 这是在守 `FlywayConfig` / `ensureStorageBucket` 已经建立的既有模式：依赖不可用不阻塞启动，能力不可用时给出明确错误。

> **执行结果 `2026-07-28`：通过。** 应用正常启动、`/api/v1/health` 返回 `200`，启动日志为 `app.embedding.api-key 未配置，向量化能力不可用（应用其余部分正常）`。触发分块后：
>
> ```
> 文档 status = FAILED   run phase = EMBED
> 错误信息 = Embedding 能力未配置：请设置环境变量 EMBEDDING_API_KEY（app.embedding.api-key）
>            后重启应用。未配置时文档可以上传，但无法完成向量化。
> ```
>
> **顺带验证了 `backend/.env` 的优先级**：本机 `.env` 里有真实 Key，而命令行 `EMBEDDING_API_KEY=` 成功把它覆盖成空——环境变量高于 `spring.config.import` 导入的文件，与 `.env.example` 里的说明一致。

### M-08 维度不一致时启动失败

```bash
EMBEDDING_DIMENSIONS=512 JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw -q -B spring-boot:run
```

- **通过**：应用**启动失败**，错误信息明确指出配置维度与数据库向量列维度不一致。
- 这一条与 M-07 有意相反：维度不匹配会让所有向量写入在运行时才报错，越早暴露越好，所以这里选择"启动就失败"。

> **执行结果 `2026-07-28`：通过。** 进程以 `BUILD FAILURE` 退出，`3001` 未监听：
>
> ```
> java.lang.IllegalStateException: Embedding 维度不一致：app.embedding.dimensions=512，
> 但 document_chunk_embedding.embedding 是 vector(1024)。改配置（EMBEDDING_DIMENSIONS）
> 使其与建表维度一致，或新增一个 migration 修改列维度——两者必须相等，否则每次写向量
> 都会在运行时报错。
> ```
>
> 日志里 `Started Application` **先于**这条异常出现，端口短暂绑定过——这是校验放在 `ApplicationRunner`（与 `ensureStorageBucket` 同模式）的已知代价，工单 06 已如实记录并接受。要更早失败得改成 `InitializingBean`，届时数据库访问会提前到上下文刷新期。

### M-10 处理进行中终止进程（工单 11 的遗留手工项）

工单 11 一直挂着这条：**处理进行到中途 `kill -9`，重启后确认文档是 `FAILED` 而不是 `RUNNING`**。此前无法执行——本机没有 Embedding Key，处理会在 `EMBED` 步直接失败，根本没有可供终止的窗口。`2026-07-28` Key 到位后补做。

制造窗口的办法：上传一份切得出 700 分块的文档，`EMBED` 阶段要串行发 70 次请求，窗口足够精确下手。**探测与终止必须写在同一个循环里**——先探测、再回来发一条 `kill`，中间几秒足以让任务跑完（第一次尝试就是这样扑空的，150 分块的文档在探测到 `EMBED` 后已经结束了）。

> **执行结果 `2026-07-28`：通过。**
>
> | 时刻 | 观察到的状态 |
> |---|---|
> | `kill -9` 之前 | run `RUNNING` / `phase=EMBED` |
> | `kill -9` 之后（无人收尾） | run 仍 `RUNNING`/`EMBED`，文档 `RUNNING`，0 分块 0 向量，端口已释放 |
> | 重启应用 | 日志 `启动回收：1 篇文档从 RUNNING 重置为 FAILED` |
> | 回收后 | run `FAILED`「上一次处理因应用重启而中断。」，文档 `FAILED`「…请重新触发。」 |
> | 重新触发 | `202`，CAS 不再被挡 |
>
> 中间那一行是这条用例真正的价值所在：**它证明了"永久卡住"确实会发生**——进程消失后没有任何机制会自动收尾，文档就停在 `RUNNING`，CAS 抢不到、删除和更新都被 `409` 挡住。启动回收不是锦上添花。
>
> 这里**保留 `phase=EMBED` 是对的**，与心跳超时回收清空 `phase`（ING-18）不矛盾：启动回收确切知道原因（进程重启），`phase` 是真实的最后阶段；心跳超时则不知道原因，填任何值都是猜。

### M-09 三个页面的浏览器实测

按 [ui-spec.md](ui-spec.md) 逐项核对：四种文档状态的展示与 `RUNNING` 时的按钮禁用、`SKIPPED` 的措辞、`phase` 的中文映射、四种错误码的用户提示、三处空态。**不是只看接口通不通，是看用户能不能理解界面在说什么。**

> **执行结果 `2026-07-28`：由项目所有者执行并验收通过**，未提出规格层面的异议。
>
> **走查最大的产出是揪出一个此前所有测试都测不到的前端缺陷**：`Modal.tsx` 在 `StrictMode` 下所有弹层静默失效。
>
> 原实现在 effect 的 cleanup 里调 `dialog.close()`，而 `close()` 会派发原生 `close` 事件、被接到了 `onClose` 上——也就是"关闭"会回调父组件的 `setDialog(null)`。StrictMode 开发模式故意双调用 effect（挂载 → 立刻 cleanup → 再挂载），于是：
>
> ```
> showModal() → cleanup 的 close() → 派发 close 事件 → onClose()
> → 父组件 setDialog(null) → Modal 当场卸载
> ```
>
> 弹层在一帧内开了又没。**症状是所有弹层类操作看起来"点击毫无反应"，且控制台没有任何报错**——DOM 里查不到 `<dialog>`、onClick 明明绑定着、React 也确实挂载了，每一项单看都正常。
>
> 这条用例的价值在此得到印证：**接口层的 163 条测试全绿，它们一条都发现不了这个问题**。前端没有测试脚本（`package.json` 无 test），弹层是否真的出现在屏幕上，只有人打开浏览器点一下才知道。
>
> 修复：去掉 cleanup 的 `close()`（React 移除 `<dialog>` 节点时浏览器自会退出 top layer），并加 `!dialog.open` 守卫防止 StrictMode 第二次 `showModal()` 抛 `InvalidStateError`。**原写法在生产构建里"碰巧"不出问题**（StrictMode 不做双调用），它本来就是个真实的副作用缺陷，只是被 StrictMode 提前暴露。理由已写进 `Modal.tsx` 的注释，避免有人把 cleanup 加回去。

## 10. PRD §8 验收标准追溯

| PRD 验收标准 | 覆盖用例 |
|---|---|
| 能创建知识库、上传文件、触发分块，内容在数据库和向量库中可查 | KB-01、DOC-01、ING-01、ING-06、ING-07、M-06 |
| URL 定时同步：未变化不重新分块，变化后正确刷新 | SYNC-01 ~ SYNC-04 |
| 超限文件被正确拒绝，且不导致内存/磁盘异常增长 | DOC-05、LIM-05、M-01、M-05 |
| 删除文档后各接口不再可见、向量物理删除、定时同步不再执行 | DOC-27、SYNC-06、DEL-05 ~ DEL-07、DEL-10、DEL-12 |
| 禁用文档后内容不出现在检索结果中（本模块只保证向量被清理） | DOC-28、DOC-29、DEL-12 |
| 手工编辑/新增/删除分块后向量库与数据库保持一致 | CHK-20 ~ CHK-24、DEL-12 |
| 处理中的文档拒绝并发的删除/更新/重复触发 | DOC-23、DOC-26、ING-02、SYNC-07、SYNC-11、CHK-18 |

`DEL-12`（向量表不变量）出现在其中三条里。它不是凑数——"向量数据被正确清理"这件事在 PRD 里被表述成了三个不同角度的要求，而它们在实现上收敛到同一条不变量。

## 10.1 执行手工项时发现的缺陷（`2026-07-28`）

**Embedding API 会因请求过于频繁返回 `429`，客户端没有任何退避重试，大文档必然整体失败。**

实测：一份切出 700 分块的文档，`OpenAiCompatibleEmbeddingClient` 按 `batch-size=10` **串行**发出约 70 次请求，**8.4 秒后**收到：

```
Embedding API 返回 429：{"error":{"code":"AccountRateLimitExceeded",
"message":"Requests are too frequent. Please reduce your request frequency, ..."}}
```

整个入库任务随即 `FAILED`。用户重试也没用——重试还是一次性把几十个请求打出去，会再撞一次。

**为什么此前没被发现**：假 Embedding 实现不会限流，集成测试永远碰不到这条路径；而在拿到真实 Key 之前，本地根本跑不到 `EMBED` 阶段之后。这是"外部依赖解除后才暴露出来"的典型。

**为什么设计阶段也漏了**：[architecture.md](architecture.md) 里所有关于"限流"的讨论——包括学习笔记 03-04 / 03-05 那两篇——**讲的全是入站限流**（用信号量保护自己的磁盘与 IO）。**出站方向被供应商限流这件事，一处都没提到。** 概念是同一个词，方向相反。

**影响面**：一份百页 PDF 轻易就是几百个分块。也就是说**当时的实现只能处理小文档**，这与 PRD 的验收标准直接冲突。

> **`2026-07-28` 已修复并验证。** `OpenAiCompatibleEmbeddingClient` 内加了两个手段，都不影响调用方：
>
> - **批次间最小间隔**（`app.embedding.batch-interval`，默认 200ms）——把速率从实测被限的约 8.3 次/秒压到约 5 次/秒，从源头降低触发概率。
> - **`429` 指数退避重试**（`max-retries` 默认 3，`retry-base-delay` 默认 1s，逐次翻倍）——处理仍然撞上的情况。
>
> 只做后者不行：大文档会每批都撞一次再退避一次，总耗时反而更长。`429` 单独成 `EmbeddingRateLimitException`，因为**它是唯一值得重试的失败**——维度不符、模型不存在、Key 无效重试多少次结果都一样，还多花几次调用的钱（`doesNotRetryNonRateLimitErrors` 守这一条，判据是只发生了一次请求）。
>
> **同一份 700 分块的文档回归验证：`SUCCESS`，700 分块 / 700 向量，耗时 6 分 02 秒**（修复前 8.4 秒整体失败）。
>
> 附带验证了另一件事：**6 分 02 秒 > 5 分钟的心跳超时阈值，任务却没有被误判为卡死**——心跳线程独立于执行线程，退避期间照常每 10 秒跳一次。这正是 `IngestionDispatcher.submit` 里"心跳必须独立于执行线程"那段注释所主张的，此前只有推理，现在有实证。
>
> 退避时会打一条 WARN 日志。没有它，运维看到的只是"入库怎么这么慢"，而慢的真正原因无从查起。

## 11. 这份矩阵没有覆盖什么

- **检索效果**：向量是否"检索得准"不属于本模块，需要 Phase 3 检索链路和 Phase 4 评测体系才能衡量。本模块只保证向量**存在且一致**。
- **多实例并发**：启动回收在多实例下会误伤其他实例正在跑的任务（[ADR 0002](../../docs/adr/0002-knowledge-base-async-and-concurrency.md) 明确记录了这个失效点）。既然当前是单实例部署，就不为一个已知不成立的前提写用例。
- **性能压测**：没有明确的性能目标（PRD 未提出），压测数字无从判定通过与否。写一个没有判定标准的压测用例，只会产生一份没人看的数字报告。
- **权限与多用户**：PRD 非目标。
