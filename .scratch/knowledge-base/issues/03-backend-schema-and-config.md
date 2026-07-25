# 03 — 后端：数据库表结构与配置基线

**What to build:** 建好知识库模块的 5 张表，补齐本模块需要的全部配置项。这张工单本身不产生任何用户可见行为，是后续所有后端工单的地基（"先让改动变容易，再做容易的改动"）。

**Blocked by:** None — can start immediately

**Status:** done（`2026-07-25`）

- [x] 新增 `V2__knowledge_base_schema.sql`，内容与 [data-model.md](../../../tech/knowledge-base/data-model.md) §3 的 DDL 完全一致（5 张表 + 索引 + check 约束）
- [x] 建好 `com.app.knowledge` 包结构（`web`/`service`/`repository`/`model`/`ingestion`/`embedding`/`vector`）与枚举类型，术语对齐 [CONTEXT.md](../../../CONTEXT.md)
- [x] `application.properties` 补齐四组配置：multipart 三项大小限制（**含容易漏掉的 `server.tomcat.max-swallow-size`**）、Embedding 五项、`app.upload.max-concurrent`、`app.knowledge.sync.*`
- [x] `pom.xml` 新增 Apache Tika（只引 `tika-core` + `tika-parsers-standard-package`，不引全量 `tika-app`）
- [x] `docker compose up -d postgres` 后启动应用，Flyway 日志显示成功迁移到 v2
- [x] `psql` 确认 5 张表存在、`document_chunk_embedding.embedding` 列类型是 `vector(1024)`、HNSW 索引已建
- [x] 部分唯一索引 `uk_knowledge_base_name ... where deleted = false` 生效：手工插两条同名知识库报错，把第一条标记 `deleted = true` 后再插同名成功
- [x] `ApplicationTests` 仍保持 `spring.flyway.enabled=false` 不变，`./mvnw -q -B verify` 通过

## 完成记录

**实际偏离清单**（都是有意的，不是遗漏）：

1. **只建了 `model` 一个子包，其余六个空包没建。** Java 里空包没有意义（也不被 git 跟踪），包会随它的第一个类自然出现。分层纪律改为写进 `com/app/knowledge/package-info.java`——开发者打开这个包时第一眼就能看到，比六个空目录有用。
2. **没加 `app.upload.acquire-timeout`。** 架构文档只说 `tryAcquire` 超时默认 5s，没要求它可配；由工单 08 决定要不要提成配置项，不预先加。
3. **枚举比工单预期多一个 `ChunkStrategy`。** 它同样是 API 契约里的固定取值集（`FIXED_SIZE`/`RECURSIVE`），和其他五个枚举性质一致，一并建掉。

**实测确认的行为**（不只是"配置写了"，而是真撞过一次）：

| 断言 | 结果 |
|---|---|
| 迁移执行 | `Successfully applied 1 migration to schema "public", now at version v2` |
| `embedding` 列类型 | `format_type` 返回 `vector(1024)` |
| HNSW 索引 | `USING hnsw (embedding vector_cosine_ops)` 已建 |
| 同名知识库 | 第二条插入被 `uk_knowledge_base_name` 拒绝 |
| 逻辑删除后重用名字 | 第一条标 `deleted=true` 后同名插入成功，表里 2 行同名（1 行已删） |
| `FILE` 来源开定时同步 | 被 `ck_source_document_sync_fields` 拒绝 |
| `URL` 来源缺 `source_uri` | 被 `ck_source_document_url_fields` 拒绝 |

后两条超出了工单原有清单，但它们是 PRD §4.3 的硬性边界在最底层的兜底，值得同时验掉。测试全程在一个事务里做，结束回滚，库里没留数据。

**顺带修掉的文档 bug**：[architecture.md](../../../tech/knowledge-base/architecture.md) §3.4 把心跳列写成了 `heartbeat_at`，实际 DDL 与其余四处文档都是 `heartbeat_time`。已改正。

**Tika 版本**：`3.0.0`。工单只说了引哪两个 artifact 没说版本，Spring Boot BOM 也不管 Tika，所以显式写死版本号。
