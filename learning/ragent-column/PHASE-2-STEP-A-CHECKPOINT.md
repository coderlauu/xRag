# Phase 2 Step A 对齐检查点

日期：2026-07-28

状态：**complete（2026-07-28）**。本文件冻结 Step A 学习验收时点；Phase 2 后续步骤的当前状态以 [Phase 2 执行计划](../../docs/exec-plans/completed/2026-07-28-model-scheduling-phase-plan.md) 为准。

## 1. 覆盖率

| 范围 | 完成 | 结果 |
|---|---:|---|
| 04-model-scheduling | 8/8 | 完成 |
| Phase 2 Step A | 8/8 | 完成 |
| 专栏正文总计 | 59/91 | 后续阶段继续 |

每篇均包含原文链接、一句话概括、核心提炼、术语、与 xrag 的关系、设计取舍与风险。索引以 [INDEX.md](INDEX.md) 为唯一进度基线。

补充产出：

- [Phase 2 大模型调度引擎一手资料复核](../../docs/research/2026-07-28-model-scheduling-primary-source-review.md)
- [Phase 2 执行计划](../../docs/exec-plans/completed/2026-07-28-model-scheduling-phase-plan.md)

## 2. 课程方案与 xrag 基线

| 主题 | 课程方案 | xrag 当前事实 | Step A 结论 |
|---|---|---|---|
| 能力接口 | Chat、Embedding、Rerank 三层接口 | 只有 `knowledge.embedding.EmbeddingClient` | 保留独立能力端口，共享调度骨架 |
| 路由 | 首选提升、优先级排序、健康预筛 | 无模型路由 | 先硬过滤再确定性排序，学习型路由递延 |
| 熔断 | 连续失败、三态、单半开探测 | 无模型熔断 | key 至少隔离到 Provider+模型+能力 |
| 故障转移 | 通用执行器捕获异常后换候选 | Embedding 只对 429 同候选重试 | 错误分类驱动策略；重试与换候选共享预算 |
| 同步 Chat | OpenAI 风格模板方法 | 无 Chat | 可复用同步 HTTP 经验，不复用 Embedding DTO |
| SSE | 阻塞读取放专用线程池，按行解析 | 无流式客户端 | 必须补完整事件语法、背压、取消和清理 |
| 首包探测 | 缓冲到首内容后提交，失败切换 | 无流式路由 | 拆分首事件、首有效块、下游提交三个时刻 |
| Embedding | 分批调用并复用路由 | 已有保序、维度、重试和事务外调用 | 以 `RoutedEmbeddingClient` 兼容迁移，保持回归 |
| Rerank | 百炼客户端 + Noop 降级 | 无 Rerank | Noop 必须显式标记“未重排” |
| 诊断 | 课程以日志为主 | 无调度诊断 | 建设已确认的轻量诊断页，只存调度事实 |

## 3. 已收敛的设计原则

1. **统一调度骨架，保留独立能力契约。** Chat、流式 Chat、Embedding、Rerank 共享 Provider/模型注册、路由、预算、错误分类、熔断与诊断，不使用万能请求/响应 DTO。
2. **先做确定性路由。** 先过滤能力、协议、配置和健康状态，再按首选、显式优先级和稳定 ID 排序。Phase 4 有评测数据后再考虑学习型路由。
3. **路由、同候选重试和故障转移分开。** 三者共同消耗逻辑调用的总截止时间和最大尝试数，避免嵌套重试导致请求和延迟倍增。
4. **错误分类必须影响策略。** 网络/5xx、429、鉴权、请求配置、用户取消和流提交后中断的处理不同；不能把所有异常都计入熔断。
5. **熔断按故障域隔离。** 第一版 key 至少包含 Provider、模型和能力/端点；单实例可先使用进程内状态，但不得描述为多实例全局熔断。
6. **流式提交点是故障转移边界。** 提交前可有界缓冲并切换候选；提交后只能明确终止，不把另一模型的回答拼入同一流。
7. **流式异步必须包含取消、背压和统一清理。** “线程池 + 阻塞 readLine”本身不构成完整流式架构。
8. **Embedding 模型身份是知识库不变量。** 同维度不同模型不能互相故障转移；只有同一模型制品、版本和预处理的等价部署可以进入同一候选组。
9. **诊断分逻辑调用和上游 attempt。** 最终回退成功不能覆盖首选 Provider 持续失败；默认不存 Prompt、文档正文或模型输出。

## 4. Phase 1 Embedding 迁移保护线

以下现有行为必须在 Phase 2 迁移前后保持：

- `EmbeddingClient` 返回数量和顺序与输入严格一致；
- 响应按 `index` 重排，并拒绝越界、重复和条数不符；
- 向量维度与数据库 `vector(1024)` 配置一致；
- 外部 Embedding HTTP 调用位于数据库事务外；
- 分批限制和间隔属于具体 Provider 配置；
- 429 重试不扩展成对所有错误的盲目重试；
- 中断标志和应用停机传播不丢失；
- CI 使用确定性假实现，真实 Provider 测试由显式环境变量守门。

推荐兼容路径：

```text
knowledge 模块
  └─ EmbeddingClient（稳定端口）
       └─ RoutedEmbeddingClient（Phase 2 实现）
            └─ 共享调度骨架 + Embedding Provider 适配器
```

## 5. Step A 闸门结论

Step A 的 8 篇学习整理、一手资料复核、现状对照和迁移保护线已于 2026-07-28 经项目所有者确认。此后的 PRD、技术设计、实现拆分和交付状态不回写本历史检查点，统一在 [Phase 2 执行计划](../../docs/exec-plans/completed/2026-07-28-model-scheduling-phase-plan.md) 中维护。
