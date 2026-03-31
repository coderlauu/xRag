# xRag Phase 1A Data Model

**日期：** 2026-03-31  
**版本：** `v1 / Phase 1A`  
**状态：** draft  
**对应架构：** [Phase 1A Architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md)

---

## 1. 设计目标

- 让原型中的 `document` 对象映射到真实数据库
- 支撑 `pending / processing / success / failed`
- 支撑标签、搜索、上传、失败重试
- 不为 `RAG / OCR / 多租户` 提前过度设计，但保留扩展位

---

## 2. 核心实体

### 2.1 `documents`

主业务对象，对应原型里的文档详情。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `uuid` | 主键 |
| `owner_id` | `uuid nullable` | 预留，多用户时启用 |
| `title` | `varchar(255)` | 标题 |
| `content_raw` | `text nullable` | 原始正文，手动输入时直接保存 |
| `content_clean` | `text nullable` | 清洗后的正文，用于搜索与展示 |
| `content_preview` | `text nullable` | 搜索结果摘要 |
| `search_text` | `text nullable` | 供 `pg_trgm` 搜索的拼接字段 |
| `search_vector` | `tsvector nullable` | 供英文/短词补充排序 |
| `source_type` | `enum('text','file','link')` | 内容类型，`link` 先预留 |
| `source_origin` | `enum('manual_input','upload','link')` | 来源入口 |
| `source_url` | `text nullable` | 原始链接 |
| `file_name` | `text nullable` | 上传文件名 |
| `mime_type` | `varchar(255) nullable` | MIME |
| `file_size` | `bigint nullable` | 文件大小 |
| `object_key` | `text nullable` | 对象存储 key |
| `content_sha256` | `char(64) nullable` | 内容摘要，供去重使用 |
| `parse_status` | `enum('pending','processing','success','failed')` | 当前解析状态 |
| `parse_error_message` | `text nullable` | 最后一次失败原因 |
| `created_at` | `timestamptz` | 内容创建时间 |
| `imported_at` | `timestamptz` | 被系统接收时间 |
| `updated_at` | `timestamptz` | 最后更新时间 |

### 2.2 `tags`

标签字典表，按 ADR-001 走“预定义选择 + 可新增”。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `uuid` | 主键 |
| `owner_id` | `uuid nullable` | 预留 |
| `name` | `varchar(64)` | 展示名 |
| `normalized_name` | `varchar(64)` | 归一化名称，唯一索引 |
| `status` | `enum('active','archived')` | 软删除支持 |
| `created_at` | `timestamptz` | 创建时间 |
| `updated_at` | `timestamptz` | 更新时间 |

### 2.3 `document_tags`

文档与标签多对多关系表。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `document_id` | `uuid` | FK -> documents.id |
| `tag_id` | `uuid` | FK -> tags.id |
| `created_at` | `timestamptz` | 关联创建时间 |

联合主键：`(document_id, tag_id)`

### 2.4 `uploads`

记录前端直传对象存储的上传会话。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `uuid` | 主键 |
| `owner_id` | `uuid nullable` | 预留 |
| `file_name` | `text` | 原始文件名 |
| `mime_type` | `varchar(255)` | MIME |
| `file_size` | `bigint` | 文件大小 |
| `object_key` | `text` | 对象存储路径 |
| `checksum_sha256` | `char(64) nullable` | 完整性校验 |
| `status` | `enum('initiated','uploaded','completed','expired')` | 上传状态 |
| `created_at` | `timestamptz` | 创建时间 |
| `completed_at` | `timestamptz nullable` | 完成时间 |

### 2.5 `document_parse_jobs`

承接状态推进与审计，不让 BullMQ 里的瞬时状态成为唯一事实来源。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `uuid` | 主键 |
| `document_id` | `uuid` | FK -> documents.id |
| `queue_job_id` | `varchar(128)` | BullMQ job id |
| `job_type` | `enum('parse_document','reparse_document','refresh_search_projection')` | 任务类型 |
| `status` | `enum('queued','running','succeeded','failed','dead')` | 任务状态 |
| `attempt` | `integer` | 第几次尝试 |
| `error_code` | `varchar(64) nullable` | 失败编码 |
| `error_message` | `text nullable` | 失败详情 |
| `started_at` | `timestamptz nullable` | 开始时间 |
| `finished_at` | `timestamptz nullable` | 结束时间 |
| `created_at` | `timestamptz` | 创建时间 |

---

## 3. 关系图

```text
documents 1 --- n document_parse_jobs
documents n --- n tags (through document_tags)
uploads 1 --- 0..1 documents
```

说明：

- 一个上传会话最终可以生成一条文档
- 一条文档会经历多次解析任务
- 标签通过中间表关联，支持未来的标签库治理

---

## 4. 状态机

### 4.1 上传状态

`initiated -> uploaded -> completed`

异常时：

- 长时间未完成 -> `expired`

### 4.2 文档解析状态

`pending -> processing -> success | failed`

规则：

- 手动文本录入创建后可以直接写成 `success`
- 文件上传完成后先写 `pending`
- Worker 开始处理后改为 `processing`
- 文本提取与索引写入完成后改为 `success`
- 不可恢复失败改为 `failed`

---

## 5. 搜索字段设计

### 5.1 `search_text`

由应用层拼接：

```text
title + '\n' + content_clean + '\n' + tag_names + '\n' + file_name + '\n' + source_url
```

用途：

- 走 `ILIKE` / `%query%`
- 走 `pg_trgm` 相似度与子串搜索

### 5.2 `search_vector`

用于补充英文与短词排序，可按下面方式更新：

```sql
setweight(to_tsvector('simple', coalesce(title, '')), 'A') ||
setweight(to_tsvector('simple', coalesce(content_clean, '')), 'B')
```

### 5.3 索引建议

```sql
create extension if not exists pg_trgm;

create index idx_documents_search_text_trgm
  on documents using gin (search_text gin_trgm_ops);

create index idx_documents_search_vector
  on documents using gin (search_vector);

create index idx_documents_parse_status
  on documents (parse_status);

create index idx_documents_imported_at
  on documents (imported_at desc);

create unique index idx_tags_normalized_name
  on tags (owner_id, normalized_name);
```

---

## 6. 标签模型与 ADR-001 的落地

当前原则：

- 文档上的标签必须落到 `tags` 字典表
- 新增自由输入标签时，先做归一化，再插入 `tags`
- 删除标签走 `archived`，不级联删除 `document_tags`

这意味着：

- 历史文档标签不会因为标签库维护而丢失
- 可以逐步把原型中的自由输入迁移到“推荐选择 + 新增”

---

## 7. 去重与幂等

### 7.1 上传幂等

- `uploads` 以 `checksum_sha256 + file_size + owner_id` 做弱去重判断
- 相同文件重复上传时，前端可提示“可能已存在”

### 7.2 文档幂等

- 手动文本录入不强制去重
- 文件导入可基于 `content_sha256` 做后台检测
- Phase 1A 先做提示，不做自动合并

---

## 8. 数据保留与删除

### 8.1 Phase 1A 建议

- 删除文档时先做软删除或归档删除，避免误删
- 原始文件在对象存储保留一段时间后再异步清理
- 任务审计表建议至少保留 `30-90` 天

### 8.2 未来扩展位

- `document_chunks`
- `document_embeddings`
- `document_versions`
- `owners / workspaces`

这些表在 `Phase 1A` 不落地，避免模型失焦。

---

## 9. 读写边界

### 9.1 强一致主路径

- 文档详情页
- 标签编辑
- 状态展示

直接读 `PostgreSQL`。

### 9.2 任务瞬时状态

BullMQ 只负责执行，不作为页面直接读取的数据源；页面看到的状态以 `PostgreSQL` 为准。

这点很关键，因为：

- Redis 中的 job 状态是易失的
- 数据库中的状态才可审计、可重放、可搜索

---

## 10. 结论

这套数据模型直接承接了原型中的 `document` 语义，同时把正式系统需要的：

- 上传会话
- 对象存储键
- 任务审计
- 标签字典
- 搜索索引字段

都补齐了，而且没有提前引入对 `RAG` 阶段才需要的复杂对象。
