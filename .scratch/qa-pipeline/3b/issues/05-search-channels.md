# 05 — `ChunkSearchPort` 与三个通道

**What to build:** 知识库侧的窄查询端口与三个 `SearchChannel` 实现；不做融合与精排。

**Blocked by:** 01
**Status:** complete

## Slice

- `ChunkSearchPort` 暴露定向向量、全局向量、关键词三个只读查询，由知识库模块实现。
- `IntentTargetedChannel` / `GlobalVectorChannel` / `KeywordChannel`，各有唯一 `priority`。
- 激活条件由 3A 动作决定；两个向量通道不同时激活。
- 检索用改写后的问题，不用用户原话。

## Acceptance

- [x] 3B-CH-01~07 自动化通过。
- [x] 定向激活时全局通道零 SQL（`RecordingPort` 断言调用列表为空）。
- [x] 通道只有一套 `priority`（`SearchChannelType` 枚举自带，无第二套）。
- [x] `com.app.qa` 不出现 chunk 表 SQL——全部经 `ChunkSearchPort`。
- [x] 没有 `isEnabled(context)` 占位；激活判据是 `isActive(RetrievalScope)`，读 3A 动作。

**Evidence:** `SearchChannelActivationTests`（8 项）、`ChunkKeywordIndexIntegrationTests`
里的真实 SQL 路径（多库定向 / 全局 / 关键词范围三种形态）。

**端口放在知识库侧**（`com.app.knowledge.search.ChunkSearchPort`），不是问答侧。
与 3A 的 `KnowledgeBaseLookup`（接口和 JDBC 实现都在 qa）不同，理由是关键词查询必须与索引期
共用同一个切词器——把实现放知识库侧，这条一致性才是结构性的。

**发现一处既有的正确性隐患，已记录未修**：查询向量用的是
`app.knowledge.default-embedding-identity`，而 `knowledge_base.embedding_model` 是按库记录的。
两者不一致时向量距离在比较两个模型的输出。这不是 3B 引入的——单表单 HNSW 索引本身就隐含
"全库向量同源"，跨身份的全局检索在存储层就不成立。3B 只是第一个真正去读它的人。
`EmbeddingQueryVectorSupplier` 的 javadoc 写明了这一点，真正的修法属 Phase 4。
