# 08 — 流式提交与停止 API（真实接线）

**Blocked by:** 07  
**Status:** complete（2026-08-12；目标测试与 `./mvnw -q -B verify` 通过）

实现 `POST /turns:stream`、带归属 stop、GET turn 3C 扩展，并把现有 3A/3B 链路接进 generation task。

**Acceptance:** `3C-API-01~04`、`3C-STOP-01~04`；accepted 前后错误载体正确；3B 形状逐字段不变。
