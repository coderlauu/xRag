# API 契约：AI 知识库建设模块

- `status`: approved（`2026-07-25`）
- `related_docs`: [架构方案](architecture.md)、[数据模型](data-model.md)、[界面规格](ui-spec.md)、[测试矩阵](test-matrix.md)、[PRD](../../docs/prd/2026-07-25-knowledge-base-prd.md)

## 1. 通用约定

### 路径风格

```
/api/v1/knowledge-bases                       集合：创建、列表
/api/v1/knowledge-bases/{kbId}                单个知识库
/api/v1/knowledge-bases/{kbId}/documents      从属集合：必须在父资源下才有意义
/api/v1/documents/{docId}                     单个文档：id 全局唯一，不必带父路径
/api/v1/chunks/{chunkId}                      单个分块：同上
```

约定是：**从属集合嵌套在父资源下，单个资源用扁平路径**。理由是单资源操作只需要一个全局唯一 id，强行带上父路径会引入"父子 id 不匹配"这种本可以不存在的错误情况；而集合操作必须知道父资源才能确定范围。

### 响应包装

成功响应直接返回业务对象，不套 `{code, data, message}` 外壳——HTTP 状态码已经承担了这个职责，再套一层就是两套并存的错误语义。

分页响应统一形状：

```json
{ "items": [], "page": 1, "size": 20, "total": 137 }
```

`page` 从 1 开始，`size` 默认 20、上限 100。

### 错误响应

```json
{ "error": "DOCUMENT_PROCESSING", "message": "文档正在处理中，请等待处理完成后再操作" }
```

| HTTP | `error` | 触发场景 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 参数校验失败（缺字段、格式错、超范围） |
| 404 | `NOT_FOUND` | 资源不存在，**或已被逻辑删除**（对外表现完全一致，不泄露"曾经存在过"） |
| 409 | `DOCUMENT_PROCESSING` | 目标文档处于 `RUNNING`，拒绝删除/更新/重复触发 |
| 409 | `INVALID_STATE` | 其他状态冲突（如给已禁用文档新增分块） |
| 409 | `KB_HAS_ACTIVE_RUNS` | 知识库仍有排队中或处理中的入库任务 |
| 409 | `KB_NOT_EMPTY` | 知识库下仍有未删除文档 |
| 413 | `FILE_TOO_LARGE` | 超出 `max-file-size` |
| 415 | `UNSUPPORTED_FILE_TYPE` | 扩展名不在白名单 |
| 429 | `UPLOAD_BUSY` | 并发上传许可耗尽 |
| 502 | `EMBEDDING_FAILED` | Embedding API 调用失败（同步接口路径上才会出现） |

`404` 对"不存在"和"已逻辑删除"返回同一个结果，是逻辑删除的必然要求——否则调用方能通过状态码差异推断出记录曾经存在，逻辑删除就变成了半透明的。

**`INVALID_STATE` 的 `message` 必须是可直接展示给用户的完整句子**，不能是 `"invalid state"` 这类开发者向的字符串。原因是这一个错误码对应至少三种情况（给禁用文档触发分块、给禁用文档新增分块、启用分块但父文档禁用），前端从错误码上无法区分，只能依赖 `message`。之所以不为每种情况拆独立错误码：三者的修复动作是同一个（先启用文档），前端不需要按码分支，拆码只会多一层映射表要维护。三处的成品文案见 [ui-spec.md §7](ui-spec.md)。

## 2. 知识库

### `POST /api/v1/knowledge-bases`

```json
{ "name": "产品文档库", "description": "内部产品说明与 FAQ" }
```

`201` →
```json
{
  "id": 1, "name": "产品文档库", "description": "内部产品说明与 FAQ",
  "embeddingModel": "text-embedding-v3", "embeddingDimensions": 1024,
  "documentCount": 0, "chunkCount": 0,
  "createTime": "2026-07-25T10:00:00+08:00"
}
```

- `embeddingModel` / `embeddingDimensions` **不接受客户端传入**，由服务端从全局配置写入（[architecture.md §4.1](architecture.md)：第一版全局单一 Embedding 配置）。请求体里带了也忽略。
- `name` 必填、1~128 字符、同名冲突返回 `400 INVALID_REQUEST`（部分唯一索引兜底，Service 层先查一次给出友好提示）。

### `GET /api/v1/knowledge-bases?page=1&size=20`

`200` → 分页包装，每项形状同上。`documentCount` / `chunkCount` 通过聚合查询得到（都带 `deleted = false`）。

### `GET /api/v1/knowledge-bases/{kbId}` · `PUT` · `DELETE`

- `PUT` 只接受 `name` / `description`。传 `embeddingModel` 一律忽略——[data-model.md §3.1](data-model.md) 说明了为什么它不可改。
- `DELETE` 只允许删除空知识库。活动任务优先返回 `409 KB_HAS_ACTIVE_RUNS`；没有活动任务但仍有文档时返回 `409 KB_NOT_EMPTY`；通过保护检查后返回 `204`。具体清理 SQL 见 [data-model.md §4](data-model.md)。

## 3. 文档

### `POST /api/v1/knowledge-bases/{kbId}/documents/file`

`multipart/form-data`：

| 字段 | 必填 | 说明 |
|---|---|---|
| `file` | 是 | 扩展名须在 `.txt` `.md` `.pdf` `.docx` 白名单内 |
| `name` | 否 | 缺省用原始文件名 |
| `chunkStrategy` | 否 | `FIXED_SIZE` \| `RECURSIVE`，默认 `RECURSIVE` |
| `chunkSize` | 否 | 默认 1000 |
| `overlap` | 否 | 默认 100，须 < `chunkSize` |

`201` →
```json
{
  "id": 11, "kbId": 1, "name": "产品手册.pdf", "sourceType": "FILE",
  "fileSize": 2451234, "contentType": "application/pdf",
  "status": "PENDING", "revision": 0, "chunkCount": 0, "enabled": true,
  "chunkStrategy": "RECURSIVE", "chunkConfig": { "chunkSize": 1000, "overlap": 100 },
  "syncEnabled": false,
  "createTime": "2026-07-25T10:05:00+08:00"
}
```

**接口返回后文档还没有被分块**——`status` 是 `PENDING`，需要调用方显式触发（PRD §7 决策 1）。前端上传成功后应当明确展示"待处理"，而不是让用户以为已经可以检索了。

这个接口是并发限流的拦截目标：许可耗尽时由 `Filter` 直接返回 `429`，请求体不会被读取（[architecture.md §7](architecture.md)）。

### `POST /api/v1/knowledge-bases/{kbId}/documents/url`

```json
{
  "sourceUri": "https://example.com/handbook.md",
  "name": "员工手册",
  "chunkStrategy": "RECURSIVE",
  "chunkConfig": { "chunkSize": 1000, "overlap": 100 },
  "syncEnabled": true,
  "syncCron": "0 0 3 * * ?"
}
```

`201` → 同上形状，`sourceType: "URL"`，多出 `sourceUri` / `syncEnabled` / `syncCron` / `nextSyncTime`。

- `sourceUri` 必须是**文件直链**，不能是网页地址。服务端无法可靠判别（Content-Type 可以撒谎），因此：服务端只校验协议为 http/https 且抓取到的内容能被 Tika 提取出文本；前端负责给出明确提示。抓取到 HTML 时不报错——Tika 能从 HTML 提取正文，但结果通常包含导航和页脚噪声，这属于用户输入质量问题，不是系统错误。
- `syncCron` 有**最短间隔限制**：解析出的相邻两次执行间隔不得小于 `app.knowledge.sync.min-interval`（默认 10 分钟），否则 `400`。防的是用户配出 `* * * * * ?` 这种每秒执行、把 Embedding 费用打穿的表达式。
- 创建时即同步抓取一次文件存入对象存储（和 FILE 来源对齐，让后续重新分块不必重新下载），抓取失败返回 `400` 且不落库。

### `GET /api/v1/knowledge-bases/{kbId}/documents?page=1&size=20&status=&enabled=`

`status` / `enabled` 可选过滤（PRD §4.2）。`200` → 分页包装。

### `GET /api/v1/documents/{docId}`

`200` → 单文档全字段，额外带最近一次入库任务的摘要，避免前端为了显示"上次同步结果"再发一个请求：

```json
{
  "id": 11, "status": "SUCCESS", "revision": 3, "chunkCount": 142,
  "lastSyncTime": "2026-07-25T03:00:12+08:00",
  "nextSyncTime": "2026-07-26T03:00:00+08:00",
  "latestRun": {
    "id": 57, "triggerSource": "SCHEDULED", "status": "SKIPPED",
    "phase": null, "finishedTime": "2026-07-25T03:00:12+08:00"
  }
}
```

### `GET /api/v1/documents/{docId}/file`

`2026-07-26` 增补（PRD §4.2「查看源文档」）。返回对象存储里的原始文件本体，不是 JSON。

- `Content-Type` 取落库时记下的 `contentType`，缺失时回退 `application/octet-stream`。
- `Content-Disposition: inline; filename*=UTF-8''{文档名}`。**用 `inline` 而不是 `attachment`**：PDF 与纯文本让浏览器直接显示，`.docx` 这类浏览器无法渲染的格式它自己会退化成下载。写死 `attachment` 则连 PDF 都要先存到本地再打开，多一步且没有收益。
- 文档不存在或已逻辑删除 → `404`（与其余接口一致，不泄露"曾经存在过"）。
- `file_key` 为空或对象存储里已经找不到该对象 → `404`，`message` 说明是源文件缺失而不是文档不存在。这两种情况对用户的意义完全不同。
- URL 定时同步期间始终返回上一个**成功入库版本**；新版本只有在分块和向量写入成功后才成为当前原文。
- **不校验文档的 `enabled` 状态**。禁用针对的是"参与检索"，与"能不能看原件"无关；禁用一份文档之后连原文都打不开，会让用户没法判断该不该重新启用它。

**为什么是后端流式转发而不是预签名 URL**：与上传方向保持同一套存储访问方式（[architecture.md §7](architecture.md) 有意避开了预签名）。下载方向预签名的收益只是省一点后端带宽，而代价是引入第二套凭据路径与过期时间管理。文件上限 50MB，转发的内存开销由固定大小的缓冲区决定，与文件大小无关。

### `PUT /api/v1/documents/{docId}`

```json
{
  "name": "员工手册（2026版）",
  "chunkStrategy": "FIXED_SIZE",
  "chunkConfig": { "chunkSize": 800, "overlap": 80 },
  "sourceUri": "https://example.com/handbook-v2.md",
  "syncEnabled": true,
  "syncCron": "0 0 4 * * ?"
}
```

- 全部字段可选，只更新传了的字段。
- `sourceUri` / `syncEnabled` / `syncCron` **仅 URL 来源可改**，FILE 来源传了返回 `400`（数据库 check 约束再兜一道，见 [data-model.md §3.2](data-model.md)）。
- 文档处于 `RUNNING` → `409 DOCUMENT_PROCESSING`。
- **更新分块参数不会自动重新分块**（PRD §4.2）。响应里带 `"needsRechunk": true` 提示前端引导用户显式触发，而不是后端偷偷替用户决定要不要花一次 Embedding 的钱。

### `DELETE /api/v1/documents/{docId}`

`204`。逻辑删除文档与其全部分块、物理删除向量、关闭定时同步；对象存储原始文件保留（PRD §7.6）。`RUNNING` 中 → `409`。

### `PATCH /api/v1/documents/{docId}/enabled`

```json
{ "enabled": false }
```

`200` → 更新后的文档。

- **禁用**：物理删除该文档全部分块的向量，分块记录的 `enabled` 不动（保留用户此前对个别分块的启用/禁用选择，重新启用时能恢复原状）。
- **启用**：只为 `enabled = true and deleted = false` 的分块重新计算向量并写回。这就是上一条"分块记录 `enabled` 不动"的意义所在。
- 已是目标状态 → 直接返回，不做任何操作（幂等）。
- 启用时若文档从未成功分块过（`revision = 0`）→ 无向量可写，正常返回，不报错。

### `POST /api/v1/documents/{docId}/ingestion-runs`

触发分块。无请求体。`202` →

```json
{ "runId": 58, "docId": 11, "status": "QUEUED", "triggerSource": "MANUAL" }
```

- `202 Accepted` 而不是 `200`：任务只是被接受排队，处理并未完成。
- 文档已在 `RUNNING` → `409 DOCUMENT_PROCESSING`（CAS 影响行数为 0，见 [architecture.md §3.2](architecture.md)）。
- 文档 `enabled = false` → `409 INVALID_STATE`。给禁用文档分块会写入向量，与"禁用即不参与检索"直接矛盾。
- 无论 `PENDING`（首次）、`SUCCESS`（重新分块）、`FAILED`（重试）都走同一条路径，服务端不区分。
- 接受任务时会把当前原文版本写入 `ingestion_run.input_*`；执行器不会在稍后运行时重新读取一个可能已经变化的 `file_key`。

### `GET /api/v1/documents/{docId}/ingestion-runs?page=1&size=20`

入库任务历史，按 id 倒序。`200` →

```json
{
  "items": [{
    "id": 58, "triggerSource": "MANUAL", "status": "FAILED", "phase": "EMBED",
    "revision": null, "chunkCount": null,
    "errorMessage": "Embedding API 返回 429：rate limit exceeded",
    "startedTime": "2026-07-25T10:10:00+08:00",
    "finishedTime": "2026-07-25T10:10:37+08:00"
  }],
  "page": 1, "size": 20, "total": 12
}
```

`phase` 让"失败了"变成"在哪一步失败了"，这是 CONTEXT.md 对「入库任务」的明确要求。前端应把它展示出来，否则用户面对失败只能重试碰运气。

## 4. 分块

### `GET /api/v1/documents/{docId}/chunks?page=1&size=20&enabled=`

按 `chunk_index` **升序**，不按 id/创建时间——用户是照着原文顺序浏览的，按插入顺序排会把手动新增的分块甩到列表末尾，即使它逻辑上属于文档中段（[data-model.md §3.3](data-model.md)）。

```json
{
  "items": [{
    "id": 901, "docId": 11, "revision": 3, "chunkIndex": 0,
    "content": "……", "charCount": 987, "tokenCount": 640,
    "enabled": true, "createTime": "…", "updateTime": "…"
  }],
  "page": 1, "size": 20, "total": 142
}
```

### `POST /api/v1/documents/{docId}/chunks`

```json
{ "content": "第 7.3 条 加班补偿按……", "chunkIndex": 42 }
```

`201` → 新分块对象。

三重前置校验，缺一不可：
1. 文档存在且未删除 → 否则 `404`
2. 文档不在 `RUNNING` → 否则 `409 DOCUMENT_PROCESSING`
3. **文档 `enabled = true`** → 否则 `409 INVALID_STATE`

第 3 条最容易漏：新增分块会立刻写向量参与检索，如果父文档是禁用状态，就出现"文档整体不参与检索、但它里面新加的这段却能被检索到"的矛盾。

`chunkIndex` 缺省时取当前 `max(chunkIndex) + 1`（不是 `count`，原因见 [data-model.md §3.3](data-model.md)）。指定时不重排其他分块。

### `PUT /api/v1/chunks/{chunkId}`

```json
{ "content": "修正后的内容……" }
```

`200` → 更新后的分块。`charCount` / `tokenCount` / `contentHash` 由服务端重算，客户端传了忽略。

**内容与库中完全一致 → 直接返回，不触发任何向量操作。** 两个理由：省下按 token 计费的 Embedding 调用；避免"删旧插新"期间该分块短暂不在向量库里的检索空窗。

向量"更新"底层就是**删旧插新**——向量库存的是 `(chunk_id, embedding)`，内容变了向量完全不同，没有"改部分维度"这种操作。

### `DELETE /api/v1/chunks/{chunkId}`

`204`。逻辑删除分块、物理删除其向量、文档 `chunkCount` 递减（带非负保护，见 [data-model.md §4](data-model.md)）。只影响这一个分块，同文档其他分块的向量不动。

### `PATCH /api/v1/chunks/{chunkId}/enabled`

```json
{ "enabled": true }
```

- **启用时**才校验父文档是否 `enabled`（不通过 → `409 INVALID_STATE`，前端提示"请先启用文档"）；**禁用时不校验**——禁用不需要父文档处于任何特定状态。
- 已是目标状态 → 直接返回（幂等）。
- 启用 → 重算 Embedding 写入；禁用 → 物理删除向量。

### `PATCH /api/v1/documents/{docId}/chunks/enabled`

批量。

```json
{ "chunkIds": [901, 902, 905], "enabled": false }
```

`200` →
```json
{ "requested": 3, "changed": 2, "alreadyInTargetState": 1 }
```

五条约束，每条都有明确理由：

1. **`chunkIds` 必填，不支持"全部启用/禁用"**——那个需求应该用文档级的 `PATCH /documents/{docId}/enabled`，语义更准确。
2. **单次上限 500**：超过后批量 Embedding + 向量写入的总耗时会让接口响应时间失控。500 是经验值，真要更多就分批调。超限 → `400`。
3. **任一 id 无效或不属于该文档 → 整批失败返回 `400`**，不静默跳过。放宽成"跳过无效的继续处理"看起来友好，但调用方会以为 500 个都处理了、实际只处理了 498 个，这种模糊比直接报错难排查得多。
4. **只处理真正需要变更的分块**（`enabled <> 目标值`），已是目标状态的计入 `alreadyInTargetState`。全部都已是目标状态时正常返回 `changed: 0`，不报错——批量操作里"部分已达目标"是常态，为此报错会让前端不得不先查一遍状态再调用。
5. **必须用编程式事务**，方法本身不能加 `@Transactional`（[architecture.md §3.5](architecture.md)）。

> 幂等检查的边界要说清：它保证"重复调用不产生额外副作用"，但**不解决真正的并发竞态**。两个请求同时通过幂等检查、同时进入写入路径是可能的。本模块对此的处理是——这些操作最终都是 `UPDATE ... WHERE id = ?` 加行锁，重复写入的结果一致、不会产生脏数据，所以不额外加防重复提交机制。这是明确评估后接受的，不是遗漏。

## 5. 前端需要的接口都在这里吗

对照 PRD §5 交互要点逐条核对：

| 交互要点 | 对应接口 | 满足 |
|---|---|---|
| 文档列表展示四种状态、`RUNNING` 时禁用操作按钮 | `GET .../documents` 返回 `status` | ✅ |
| 分块编辑子页面展示顺序上下文 | `GET .../chunks` 按 `chunkIndex` 升序分页 | ✅ |
| 定时同步展示下次执行时间/上次结果/失败历史 | `GET /documents/{docId}` 的 `nextSyncTime`+`latestRun`；`GET .../ingestion-runs` 完整历史 | ✅ |
| 批量操作反馈"选中 N 个，其中 M 个会变化" | 前端可从已加载的分块列表本地算出 M；接口响应再回报实际 `changed` 二次确认 | ✅ |
| 处理进度轮询 | `GET /documents/{docId}` 轮询 `status`；建议 2s 间隔，`SUCCESS`/`FAILED` 后停止 | ✅ |
