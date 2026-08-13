# ADR 0011：语义评测隔离在框架无关 Seam 后

- `status`: proposed
- `date`: 2026-08-13

## Context

RAGAS 有真实价值，也存在版本破坏、中文未验证、Python 运行时与 Judge 偏置。若把其类和字段写进领域、
Schema 或 HTTP，框架升级会扩散；若完全不设 seam，又无法用 fake 做确定性测试。

## Decision

Java 深模块拥有数据集、运行、同源录制、确定性评分、聚合、报告和 review。语义评分只依赖批量
`SemanticEvaluator` interface；候选 RAGAS worker 与测试 fake 是两个 adapter。Step D 先用 12 条中文
校准集 PoC，再由项目所有者决定生产 adapter；RAGAS 类型不得越过 seam。

## Consequences

- 框架可替换，主系统状态与恢复留在已有 Spring/PostgreSQL 运行时。
- 增加一个外部 worker 的部署/协议成本，仅在 PoC 通过后承担。
- PoC 未通过时第一版仍可交付确定性指标，不伪造语义分。
- 需要保存 adapter、Prompt、模型、依赖锁和逐轮 verdict 身份。
