# 06 — Embedding 客户端接入

**What to build:** 一个能把文本列表变成向量列表的客户端。没有 UI，但它是异步入库的必要前置。

关键约束是**未配置 API Key 时应用仍必须能启动**——沿用 `FlywayConfig` / `ensureStorageBucket` 已建立的模式（依赖不可用时记录警告或抛明确异常，绝不阻塞启动）。

**Blocked by:** 03（配置项）

**Status:** done（`2026-07-25`，含真实 API 验证）

- [x] 定义 `EmbeddingClient` 接口：`List<float[]> embed(List<String> texts)` + `int dimensions()`
- [x] 实现走 OpenAI 兼容的 `/v1/embeddings` 协议，用 Spring 自带 `RestClient`，不引第三方 HTTP 库
- [x] **先查一次阿里云百炼官方文档**，确认 `text-embedding-v3` 单次请求的文本条数上限，把 `batch-size` 默认值调到正确数字并在配置注释里写明来源。超上限的症状是"大文档第一批就整体失败、小文档完全正常"，很容易被误判成偶发问题
- [x] 按 `app.embedding.batch-size` 分批串行请求
- [x] 返回向量的顺序与输入文本严格一一对应（供应商可能返回带 `index` 的乱序结果，必须按 `index` 重排而不是假定有序）
- [x] 未配置 `api-key` 时注入一个抛明确异常的实现，**应用正常启动**、`/api/v1/health` 仍返回 200
- [x] 启动时校验 `app.embedding.dimensions` 与数据库向量列维度一致，不一致直接启动失败并说明原因
- [x] **不把 Embedding API 加进 readiness 探测**（它按次计费，探活会产生真实费用）
- [x] 提供一个确定性的假实现（返回固定维度伪向量）供测试与 CI 使用，见工单 19
- [x] 手工验证：配好真实 API Key 后调用一次，确认返回向量维度与配置一致 —— `2026-07-25` 用火山方舟 Ark 真实 Key 验证通过
- [x] `./mvnw -q -B verify` 通过

## 完成记录

**前置动作的查证结果：`batch-size = 10` 是 DashScope 的硬上限，不是保守取值。** 官方文档《[通用文本向量同步接口 API 详情](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api)》OpenAI 兼容一节写明 `text-embedding-v3`/`v4`「输入为字符串列表或文件：最多支持 10 条（行）」，单条 8,192 token，维度可取 1024（默认）/768/512/256/128/64。已把来源链接写进 `application.properties` 注释与 architecture.md §6——下次换供应商的人需要知道这个数字是查出来的、不是拍的。

**实现要点**

- `EmbeddingClient` / `EmbeddingException` / `EmbeddingProperties` / `OpenAiCompatibleEmbeddingClient`（包级私有）/ `UnconfiguredEmbeddingClient`（包级私有）/ `EmbeddingConfig`，全在 `com.app.knowledge.embedding`。两个实现都不对外暴露，调用方只拿到接口。
- **`dimensions` 始终显式发送**：这是让返回宽度与配置一致的唯一手段。若某供应商不认这个参数会立刻 400，好过悄悄返回另一个维度的向量。
- 维度校验用 `format_type(atttypid, atttypmod)` 读 `document_chunk_embedding.embedding` 的真实类型。**两种失败区别对待**：维度不一致（确定性配置错误）直接让应用起不来；数据库不可达（环境状态）只记警告，沿用既有模式。

**实测**（真实 Postgres，`vector(1024)`）

| 场景 | 结果 |
|---|---|
| `EMBEDDING_DIMENSIONS=768` 启动 | 启动失败，`IllegalStateException: Embedding 维度不一致：app.embedding.dimensions=768，但 document_chunk_embedding.embedding 是 vector(1024)。…` |
| 默认 1024 启动 | `Embedding 维度校验通过：1024 维`，`/api/v1/health/ready` 三项全 ok |
| 未配 `api-key` 启动 | 应用正常启动，只记一条 WARN |

一处如实记录：校验放在 `ApplicationRunner`（与 `ensureStorageBucket` 同模式），因此日志里会先出现 `Started Application` 再抛异常退出——端口短暂绑定过。为与既有模式一致接受了这一点；要更早失败得改成 `InitializingBean`，届时数据库访问会提前到上下文刷新期。

**8 条单测**（`MockRestServiceServer` 绑定 `RestClient.Builder`，不发真实请求）：按 index 重排乱序结果、分批后全局顺序、维度不符、条数不符、HTTP 错误体透出、空输入不发请求，以及未配置实现的两条。其中**乱序重排**是最值得测的一条——不重排的话每个分块都配到别人的向量，全程无报错，只是检索结果莫名其妙。

**确定性假实现**放在 `src/test/java`（不是主源码）：它是测试替身，不该进生产 jar。集成测试用 `@TestConfiguration` 覆盖 `embeddingClient` Bean 即可。种子取内容的完整字节而不是 `String.hashCode()`，后者碰撞太容易，会让"两个不同分块拿到同一向量"这类断言失效。

## 补充：供应商换成火山方舟 Ark（`2026-07-25`）

用户提供的是**火山方舟 Ark 的 Coding Plan Key**，不是原计划的阿里云百炼。四点实测确认，每一点都容易被"顺手改错"：

> **`2026-07-28` 补记：这套配置违反 Coding Plan 的使用条款。** 官方文档写明该 Key「仅能在官方支持的 AI 编程工具中使用，不能用于直接 API 调用」，违规可能导致订阅停用或账号封禁。下面这些实测结论在**技术上**都成立且已复现两次，但它们描述的是一条**不合规的路径**——本工单当时没有查证条款，直接把它记成了推荐基线。合规替代方案与订正见 [architecture.md §6](../../../tech/knowledge-base/architecture.md)。

| 项 | 结论 |
|---|---|
| `base-url` | **`/api/plan/v3`，不是常见的 `/api/v3`**。Plan 类 Key 打到标准路径直接 401。看着像笔误，其实不是，别改回去 |
| 模型 | `doubao-embedding-vision` 是**唯一可用**的向量模型，不是偏好选择——`doubao-embedding-large` / `doubao-embedding` / `doubao-embedding-text-240715` 全部返回 *"The requested model does not support the agent plan feature"* |
| 维度 | **原生输出 2048 维**，1024 是靠请求里的 `dimensions` 参数降下来的。这正是"`dimensions` 始终显式发送"那条决定的用武之地——去掉它会拿到 2048 维、每次写 `vector(1024)` 都在运行时报错。启动校验拦的是"配置与建表不一致"，拦不住"配置对但请求少发了参数" |
| `batch-size` | **10 是硬上限**，实测传 32 条被拒：`Embeddings API input limit exceeded: max 10, got 32`。与百炼巧合地一样，但这是实测来的不是沿用 |

**一个影响测试写法的发现：模型输出不是逐位确定的。** 同一段文本两次调用，向量有 1e-3 量级的抖动（大概率是低精度推理）。所以：

- 任何拿真实 API 断言"向量相等"的测试都会 flaky。`RealEmbeddingApiTests` 改用**余弦相似度**：单独算的向量与它在批次里那条要 `> 0.999`，与相邻文本的要 `< 0.999`——后者才是真正要防的分批错位。
- 顺带强化了一条既有设计的理由：architecture.md §3.5「内容没变就不重算向量」不只是省钱，也避免了同样内容在库里反复得到略微不同的向量。

**真实 API 测试的执行方式**：`RealEmbeddingApiTests` 用 `@EnabledIfEnvironmentVariable(named = "EMBEDDING_API_KEY")` 守门，无 Key 时自动跳过。这不是偷偷降级——真实调用按次计费，CI 用确定性假实现覆盖，真实链路靠本地手工跑，取舍要写进 `deploy/README.md`（工单 19），否则后来者会以为 CI 已经验过真实 API。

```bash
EMBEDDING_API_KEY=<你的 Key> ./mvnw -B test -Dtest=RealEmbeddingApiTests
```

**顺带修掉两条测试**：`KnowledgeBaseIntegrationTests` 里把模型名写死成 `text-embedding-v3` 了，换供应商后失败。改成对照 `EmbeddingProperties` 的配置值——这两条要证明的是"值来自服务端配置"，写死字面量只会让每次换供应商都无谓地弄红一次测试。
