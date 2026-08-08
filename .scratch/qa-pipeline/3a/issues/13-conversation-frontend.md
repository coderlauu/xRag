# 13 — 问答 A：Conversation + Inspector

**What to build:** 按 approved A 落地正式问答页：对话列表、消息主区、输入、歧义选择和可收起 3A Inspector；只展示真实 3A 状态。

**Blocked by:** 05, 10, 12
**Status:** complete

## Slice

- 首问/追问、幂等 clientRequestId、活动 turn 防重复提交和刷新恢复。
- 对话分页/切换/重命名/删除，消息 cursor prepend。
- ACTIONS_READY、WAITING_CLARIFICATION、TOO_MANY、FAILED 与 degradations。
- Inspector 展示批准字段；1440 三栏、720 抽屉、375 核心路径与键盘/焦点。

## Acceptance

- [x] 3A-UI-01~08 具备可构造状态；相关组件自动化通过。
- [x] 聊天区不展示 Inspector 为助手思维，不出现伪答案/来源/停止/排队。
- [x] 相同提交重试复用 clientRequestId，UI 不因网络重试追加重复气泡。
- [x] 错误说明失败点与可做动作；颜色不是唯一状态信号。

**Design:** [conversation ui-spec](../../../../tech/qa-pipeline/conversation/ui-spec.md)、[API §2~3](../../../../tech/qa-pipeline/api.md)

## Evidence（2026-08-08）

`frontend/src/pages/ChatPage.test.tsx` 16 例，全量 `pnpm test --run` 36 项通过，`pnpm build`
与 `pnpm lint` 通过。mock 边界是全局 `fetch`，因此"翻页带 before/cursor、重命名带
expectedVersion、重试复用 clientRequestId"都是在真实请求 URL/正文上断言的。

| 矩阵项 | 落点 |
|---|---|
| 3A-UI-01 | 空态首问建对话 + 打开已有对话刷新恢复消息与 ACTIONS_READY Inspector |
| 3A-UI-02 | 改写 `NORMALIZED_FALLBACK` 显示"使用原问题"、`NO_MATCH` 与降级事实按文字展示 |
| 3A-UI-03 | `REJECTED_TOO_MANY_TOPICS` 提示拆分，Inspector 无子问题区块 |
| 3A-UI-04 | 2 个方向 + 都不是这些单选，提交结构化 `CLARIFICATION_CHOICE` |
| 3A-UI-05 | 提交进行中输入/发送禁用；`MESSAGE_PERSISTENCE_FAILED` 保留输入并点明可做动作 |
| 3A-UI-06 | 重命名 409 保留表单、删除二次确认后回到空白新对话、切换对话保留草稿 |
| 3A-UI-07 | 1440 常驻可收起、1024 抽屉、375 覆盖层；Esc 关闭且焦点回到触发按钮 |
| 3A-UI-08 | 断言页面无来源/停止/排队控件，3A 完成提示带 `3A 验收` 标签 |

浏览器实机走查与矩阵回写属工单 16，本工单不代签。
