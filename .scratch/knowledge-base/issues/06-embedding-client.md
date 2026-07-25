# 06 — Embedding 客户端接入

**What to build:** 一个能把文本列表变成向量列表的客户端。没有 UI，但它是异步入库的必要前置。

关键约束是**未配置 API Key 时应用仍必须能启动**——沿用 `FlywayConfig` / `ensureStorageBucket` 已建立的模式（依赖不可用时记录警告或抛明确异常，绝不阻塞启动）。

**Blocked by:** 03（配置项）

**Status:** ready-for-agent

- [ ] 定义 `EmbeddingClient` 接口：`List<float[]> embed(List<String> texts)` + `int dimensions()`
- [ ] 实现走 OpenAI 兼容的 `/v1/embeddings` 协议，用 Spring 自带 `RestClient`，不引第三方 HTTP 库
- [ ] **先查一次阿里云百炼官方文档**，确认 `text-embedding-v3` 单次请求的文本条数上限，把 `batch-size` 默认值调到正确数字并在配置注释里写明来源。超上限的症状是"大文档第一批就整体失败、小文档完全正常"，很容易被误判成偶发问题
- [ ] 按 `app.embedding.batch-size` 分批串行请求
- [ ] 返回向量的顺序与输入文本严格一一对应（供应商可能返回带 `index` 的乱序结果，必须按 `index` 重排而不是假定有序）
- [ ] 未配置 `api-key` 时注入一个抛明确异常的实现，**应用正常启动**、`/api/v1/health` 仍返回 200
- [ ] 启动时校验 `app.embedding.dimensions` 与数据库向量列维度一致，不一致直接启动失败并说明原因
- [ ] **不把 Embedding API 加进 readiness 探测**（它按次计费，探活会产生真实费用）
- [ ] 提供一个确定性的假实现（返回固定维度伪向量）供测试与 CI 使用，见工单 19
- [ ] 手工验证：配好真实 API Key 后调用一次，确认返回向量维度与配置一致
- [ ] `./mvnw -q -B verify` 通过
