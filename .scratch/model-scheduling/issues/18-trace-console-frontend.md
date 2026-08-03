# 18 — A — Trace Console 前端

**What to build:** 按 approved A 方向完成 `/diagnostics` 前端：顶部紧凑状态条、左侧最近调用、右侧固定详情和真实事件时间线。

**Blocked by:** 05, 17

**Status:** blocked

## Slice

- 并行加载 readiness、overview、calls；overview 页面可见时 30s 刷新。
- calls 筛选、分页和 URL query 同步；支持 failover 与多个 outcome 下钻。
- detail 展示候选过滤、route events、attempts、failure phase、Stream 时刻、由成功 attempts 派生的 Embedding 贡献视图与 Rerank Noop。
- 宽屏双栏、普通桌面抽屉、窄屏覆盖/卡片。
- 加载/空/错误/过期状态；键盘打开、Escape 关闭、焦点返回；状态不只靠颜色。

## Acceptance

- [ ] UI-01~UI-10 按矩阵自动化/浏览器验证；摘要下钻生成冻结的筛选语义，诊断丢弃/写失败显示独立警告。
- [ ] 六种正式场景均可由假 Provider 构造，四个摘要入口均能精确下钻。
- [ ] 页面不推测缺失事件，不展示正文、原始错误、完整 Provider request ID 或 secret；内部 request ID 仍可按契约复制。
- [ ] 无 B/C 布局、CRUD、Key、手动 breaker 或 JSON 导出。
- [ ] 前端 build/lint 与浏览器实测通过。

**Design:** [ui-spec A](../../../tech/model-scheduling/ui-spec.md#10-原型三方案)
