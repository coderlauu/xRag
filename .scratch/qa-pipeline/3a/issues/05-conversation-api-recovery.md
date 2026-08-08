# 05 — 对话生命周期 API 与中断恢复

**What to build:** 交付对话/消息/turn 的 JSON API、ProblemDetail、游标和 stale PROCESSING 恢复；理解编排仍由后续工单注入假 seam。

**Blocked by:** 03
**Status:** complete

## Slice

- GET conversations/detail/messages/turn，PATCH title，DELETE conversation。
- POST turn 的请求校验、首问自动建对话、幂等返回与 busy 冲突。
- 启动恢复过期 PROCESSING 为稳定失败，不重放收费工作。
- 用临时 `TurnProcessor` seam 连接工单 10；没有空成功或假答案实现。

## Acceptance

- [x] 3A-CONV-07~12、3A-API-01~05 自动化通过。
- [x] 未知字段、跨归属读取、版本/幂等冲突返回契约 code。
- [x] 用户消息保存失败时 `TurnProcessor` 零调用。
- [x] 恢复只做 CAS 状态收敛，不在启动线程调用模型。

## Evidence

- `QaConversationApiIntegrationTests` 覆盖列表/消息不透明游标、详情与 turn 刷新、标题 version CAS、活动本轮删除
  冲突、逻辑删除 404、未知字段/媒体类型/UUID/枚举、幂等/busy、真实数据库写失败零 processor 调用，以及 processor
  异常后的稳定 `FAILED` 收敛。
- `ConversationPersistenceServiceTests` 覆盖 stale `PROCESSING` 到 `FAILED/PROCESS_INTERRUPTED`、分页 tie-break、终态
  CAS 和删除传播；`ProcessingTurnRecovery` 只调用状态恢复端口，不持有 `TurnProcessor` 或 Chat 依赖。
- `./mvnw -Dtest=QaConversationApiIntegrationTests,ConversationPersistenceServiceTests,ApplicationTests test`：20 tests，
  0 failures，0 errors，0 skipped。

**Design:** [API §1~3](../../../../tech/qa-pipeline/api.md)
