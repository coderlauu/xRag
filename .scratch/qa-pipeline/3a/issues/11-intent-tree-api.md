# 11 — 意图树管理 API 与影响预览

**What to build:** 暴露当前完整快照、绑定知识库、节点 CRUD 命令、子树影响预览和 expected revision 冲突契约。

**Blocked by:** 04
**Status:** complete

## Slice

- GET tree 与 bindable KB；返回虚拟根、current revision、effective state 和规模。
- POST create、PUT fields、impact、enable/disable/delete command endpoints。
- 所有命令要求 expectedRevisionId；冲突返回 current revision 和结构化 violations。
- 新节点默认停用；API DTO 不接受 parent/type/enabled 的越权更新。

## Acceptance

- [x] 3A-API-07~10、3A-TREE-16 自动化通过。
- [x] 影响预览不承诺锁；同 revision 结果与真正命令一致。
- [x] 任一领域错误不产生部分 revision，ProblemDetail 不泄露 SQL/节点 Prompt。
- [x] API 无 MCP、拖拽换父、版本回滚或 Prompt 编辑入口。

## Evidence

- `QaIntentTreeApiIntegrationTests` 覆盖完整当前快照/虚拟根/effective state、所有命令的 expected revision、
  默认停用与越权字段拒绝、影响预览/删除一致性，以及 bindable KB 的可用性和隐私白名单。
- `IntentTreeStoreTests` 继续证明同 revision 预览/命令、过期 revision 零写入、不可变旧快照和缓存恢复。
- `2026-08-08`: 全部 `com.app.qa` 86 tests，0 failure / 0 error；全套 500 项中的 3 个既有知识库异步失败均已单独复跑通过。

**Design:** [API §4~5](../../../../tech/qa-pipeline/api.md)
