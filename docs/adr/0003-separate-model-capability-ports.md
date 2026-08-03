# ADR 0003：按能力分离模型端口，共享内部调度骨架

- 状态：已接受（2026-08-02）
- 日期：2026-07-28
- 相关：[Phase 2 PRD](../prd/2026-07-28-model-scheduling-prd.md)、[技术方案](../../tech/model-scheduling/architecture.md)

## 背景

Chat、流式 Chat、Embedding 和 Rerank 都需要选路、预算、熔断、故障转移和诊断，但请求和结果不变量明显不同。把它们做成一个 `invoke(ModelRequest)` 会迫使业务层使用大量可空字段、类型判断和供应商泄漏；为每种能力各写一套完整执行器又会让重试和健康策略漂移。

## 决策

xrag 对业务层暴露四个按能力分开的端口：`ChatClient`、`StreamingChatClient`、Phase 1 的 `EmbeddingClient` 和 `RerankClient`。`com.app.ai` 内部共享候选规划、attempt 预算、错误分类、熔断、故障转移和诊断；能力专属请求、结果校验和 Provider DTO 不越过各自 seam。

Provider SPI 同样按能力分开，并至少有生产协议适配器与可编程假适配器两个实际实现。普通业务端口不接受精确 Provider/model；调试和评测使用独立内部入口。

## 后果

- Phase 3 不依赖供应商 SDK 或错误结构，能力契约仍有静态类型保护。
- 重试和故障转移规则只有一个实现位置，能力差异通过包内操作/校验回调注入。
- 新增一种能力要新增端口和 Provider SPI，不能只往万能 DTO 加字段；这是有意的演进成本。
- Phase 1 的 `EmbeddingClient` 继续作为知识模块 seam，但 interface 在一个原子工单中收敛为 `embed(EmbeddingCommand) -> EmbeddingResult`；现有生产实现、未配置实现、测试假实现、Bean 装配、启动维度校验和全部知识库调用点同时迁移，不能依赖“新增抽象方法后旧实现仍可编译”的假设，也不长期保留两套重载。
- Embedding 的结果元数据保持能力专属：向量、固定 `ModelIdentity`、各批次贡献 deployment 和聚合计数不塞进 Chat/Rerank 共用的万能 DTO。
