# 08 — 有界并行意图评分与全局封顶

**What to build:** 对固定 tree snapshot 的启用叶子做每子问题一次评分，有界并行、UTF-8 Prompt 硬预算、返回复核和多样性封顶。

**Blocked by:** 01, 04
**Status:** complete

## Slice

- 编码允许字段并稳定排序；65536 bytes 前完成预算判断。
- 1~3 子问题提交到专用 executor，lambda 内隔离单问题失败。
- 清理 code fence、校验数组/UUID/启用叶子/有限分数、重复取最高。
- 应用 0.35、单问题 3、全局 3；先每问题一席再按分数稳定补位。

## Acceptance

- [x] 3A-INT-01~16 自动化通过。
- [x] 超预算零评分调用、零随机截断；三个子问题共享同一 revision 对象。
- [x] 饱和/中断语义明确，不使用 parallelStream/common pool/Reactor。
- [x] 每个评分 stage 至多一个 logicalCallId，stage 无正文。

## Evidence

- `IntentScoringServiceTests` 覆盖固定 snapshot、三问题并行、返回白名单复核、稳定封顶、单问题失败隔离、
  Prompt 超预算零调用和中断恢复。
- `QaUnderstandingConfigurationTests` 固定专用线程池为 3 worker、3 queue、`CallerRunsPolicy`；
  `ConversationPersistenceServiceTests` 验证同一评分工作单元至多一个且无正文的 stage。
- `ConversationPersistenceServiceTests,IntentScoringServiceTests,QaUnderstandingConfigurationTests` 共 22 项通过。

**Design:** [architecture §5.3, §9](../../../../tech/qa-pipeline/architecture.md)
