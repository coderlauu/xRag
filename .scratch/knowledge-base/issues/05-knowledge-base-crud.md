# 05 — 知识库增删改查全链路

**What to build:** 用户能在浏览器里创建知识库、看到列表、改名、删除。这是第一条真正打通"数据库 → 接口 → 界面"的路径，它跑通了就证明整条技术栈是通的，后面的功能都是在这条路径上加内容。

删除是逻辑删除且级联到文档与分块——虽然此时还没有文档功能，级联逻辑也要一次写对，否则后面补容易漏。

**Blocked by:** 03（表结构与配置）、04（前端基础设施）

**Status:** ready-for-agent

- [ ] 后端实现 5 个接口：`POST`/`GET 列表`/`GET 详情`/`PUT`/`DELETE`，行为与 [api.md](../../../tech/knowledge-base/api.md) §2 一致
- [ ] `embeddingModel`/`embeddingDimensions` 由服务端从全局配置写入，客户端传了忽略；`PUT` 只接受 `name`/`description`
- [ ] 同名知识库返回 `400 INVALID_REQUEST` 并带友好提示（Service 层先查，部分唯一索引兜底）
- [ ] `DELETE` 按 [data-model.md](../../../tech/knowledge-base/data-model.md) §4 的完整 SQL 执行级联逻辑删除 + 向量物理删除，一个事务内完成
- [ ] `documentCount`/`chunkCount` 聚合查询带 `deleted = false`
- [ ] 前端知识库列表页：列表分页、创建表单、改名、删除（二次确认弹层）
- [ ] 空态展示：没有任何知识库时按 `ui-spec.md` 展示引导创建的提示
- [ ] 集成测试：创建后能查到；逻辑删除后列表与详情接口都返回不到它（详情返回 `404`）；删除已删除的知识库返回 `404`
- [ ] 浏览器中完整走通：创建 → 列表可见 → 改名 → 删除 → 列表不再可见
- [ ] `./mvnw -q -B verify` 与 `pnpm build && pnpm lint` 通过
