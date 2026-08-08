# 11 — 3B API 契约与隐私边界

**What to build:** `retrieval` / `tools` 两个响应块、重试端点与 3B 错误码；含隐私扫描。

**Blocked by:** 08, 10
**Status:** blocked

## Slice

- 扩展 turn 响应，3A 字段逐字段不变；未进 3B 时 `retrieval=null`、`tools=[]`。
- `POST .../retrieval/retry`；无失败目标返回 409 `NOTHING_TO_RETRY`。
- 七个 3B 错误码；`NO_RESULT` 是 200 不是错误。

## Acceptance

- [ ] 3B-API-01~04 自动化通过。
- [ ] 响应扫描不出现分数、chunk 正文、通道名、Schema 原文、异常 message。
- [ ] 同一 turn 的 `sources` 内 `chunkId` 不重复。
- [ ] `documentDeleted=true` 时条目仍返回，正文不返回。
