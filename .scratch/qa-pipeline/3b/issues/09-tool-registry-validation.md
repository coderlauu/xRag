# 09 — 工具白名单、Schema 复核与状态机

**What to build:** 工具注册表、代码侧 Schema 复核与七态状态机；不接 Chat 调用循环。

**Blocked by:** 02
**Status:** blocked

## Slice

- 启动期加载白名单；重复 `name` 启动失败。
- 复核五道：字段名白名单 → 类型 → 枚举/取值 → 必填 → 禁止默认值兜底。
- 状态闭集 `PENDING_CONFIRM/RUNNING/SUCCEEDED/FAILED/CANCELLED/UNAVAILABLE/ABORTED`。
- 未注册工具进 `UNAVAILABLE`，不静默跳过。

## Acceptance

- [ ] 3B-TOOL-01~08 自动化通过。
- [ ] 模型编造的字段不入库也不发给工具。
- [ ] 缺必填参数不产生任何外部调用。
- [ ] 提示文案给允许值，不给 Schema 原文。
- [ ] 本工单不实现确认 API 与工具执行。
