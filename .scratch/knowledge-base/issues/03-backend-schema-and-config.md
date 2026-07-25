# 03 — 后端：数据库表结构与配置基线

**What to build:** 建好知识库模块的 5 张表，补齐本模块需要的全部配置项。这张工单本身不产生任何用户可见行为，是后续所有后端工单的地基（"先让改动变容易，再做容易的改动"）。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [ ] 新增 `V2__knowledge_base_schema.sql`，内容与 [data-model.md](../../../tech/knowledge-base/data-model.md) §3 的 DDL 完全一致（5 张表 + 索引 + check 约束）
- [ ] 建好 `com.app.knowledge` 包结构（`web`/`service`/`repository`/`model`/`ingestion`/`embedding`/`vector`）与枚举类型，术语对齐 [CONTEXT.md](../../../CONTEXT.md)
- [ ] `application.properties` 补齐四组配置：multipart 三项大小限制（**含容易漏掉的 `server.tomcat.max-swallow-size`**）、Embedding 五项、`app.upload.max-concurrent`、`app.knowledge.sync.*`
- [ ] `pom.xml` 新增 Apache Tika（只引 `tika-core` + `tika-parsers-standard-package`，不引全量 `tika-app`）
- [ ] `docker compose up -d postgres` 后启动应用，Flyway 日志显示成功迁移到 v2
- [ ] `psql` 确认 5 张表存在、`document_chunk_embedding.embedding` 列类型是 `vector(1024)`、HNSW 索引已建
- [ ] 部分唯一索引 `uk_knowledge_base_name ... where deleted = false` 生效：手工插两条同名知识库报错，把第一条标记 `deleted = true` 后再插同名成功
- [ ] `ApplicationTests` 仍保持 `spring.flyway.enabled=false` 不变，`./mvnw -q -B verify` 通过
