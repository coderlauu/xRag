# 12 — 单候选流状态机与单次订阅

**What to build:** 在单 deployment 流上实现 PROBING/COMMITTED/终态状态机、有界探测、阶段超时、取消和公共流单次订阅契约。

**Blocked by:** 04, 08, 11

**Status:** blocked

## Slice

- 定义能力专用流事件与终态互斥规则；首有效文本/工具/拒答后才提交。
- 探测最多 32 events/64KiB；连接、响应头、首事件、首有效、idle、total 超时分别记录 failure phase。
- API 返回的同一个 logical stream 只允许一次订阅；重复订阅在发 HTTP 前稳定失败，不能创建第二个收费调用。
- cancel 传播到 WebClient；终态 CAS 保证资源和诊断只结算一次。
- 所有候选在提交前耗尽时使用冻结的公共失败契约；提交后才允许 `STREAM_FAILED(incomplete=true)`。

## Acceptance

- [ ] STR-10~STR-13、STR-15~STR-27 中单候选状态机项自动化；STR-16 锁定 tool delta 身份与顺序，STR-20/21 锁定类型化 finish/usage，并把重复订阅期望改为“拒绝第二次订阅”。
- [ ] STR-27、STR-29、STR-30、STR-35 显式自动化：二次订阅和 Reactor retry 不创建调用，提交后失败只发一个 StreamFailed 后正常结束，total deadline 的提交前后 termination precedence 唯一。
- [ ] 提交后断流只发一次 `STREAM_FAILED`；提交前失败不伪造已提交事件。
- [ ] 取消/完成/超时竞态循环测试无重复终态或资源泄漏。
- [ ] 慢下游无无界 buffer。
- [ ] 本工单不实现候选切换。

**Design:** [architecture §9.3~9.4](../../../tech/model-scheduling/architecture.md#93-探测提交与缓冲)
