# 18 — A — Trace Console 前端

**What to build:** 按 approved A 方向完成 `/diagnostics` 前端：顶部紧凑状态条、左侧最近调用、右侧固定详情和真实事件时间线。

**Blocked by:** 05, 17

**Status:** done（2026-08-07）

## Slice

- 并行加载 readiness、overview、calls；overview 页面可见时 30s 刷新。
- calls 筛选、分页和 URL query 同步；支持 failover 与多个 outcome 下钻。
- detail 展示候选过滤、route events、attempts、failure phase、Stream 时刻、由成功 attempts 派生的 Embedding 贡献视图与 Rerank Noop。
- 宽屏双栏、普通桌面抽屉、窄屏覆盖/卡片。
- 加载/空/错误/过期状态；键盘打开、Escape 关闭、焦点返回；状态不只靠颜色。

## Acceptance

- [x] UI-01 双栏/抽屉、UI-02 响应式、UI-04 URL 同步、UI-06 刷新失败保留旧数据、UI-07 键盘与 Escape、UI-08 隐私、UI-09 四个摘要下钻、UI-10 dropped/write-failure 警告，均已在浏览器实测（1440px 与 375px 两档）。
- [x] UI-09 的关键语义已用网络请求证据确认：点"24h 失败/流中断"真实发出
      `?outcome=FAILED&outcome=STREAM_FAILED`，不是过滤当前已加载的一页。
- [x] 页面不推测缺失事件：时间线只渲染持久化的 route event 与 attempt，两者都为空时明说"没有持久化的调度事件"。
- [x] 隐私实测：页面文本含脱敏 `req_****1b7c`，不含密钥/Authorization/Prompt/正文；内部 request ID 可复制。
- [x] 无 B/C 布局、CRUD、Key、手动 breaker 或 JSON 导出。
- [x] `pnpm build` 与 `pnpm lint` 通过；`./scripts/ci-validate.sh` 通过（clean 构建后 backend 390 tests，0 failures，2 skipped）。
- [ ] **UI-04/05/08/09/10 的"A（自动化）"部分未落地**，原因见下方遗留。

**Design:** [ui-spec A](../../../tech/model-scheduling/ui-spec.md#10-原型三方案)

## 实现记录（2026-08-07）

`frontend/src/api/diagnostics.ts`（三个接口的类型与请求）、`src/pages/DiagnosticsPage.tsx`（重写）、
`src/pages/diagnostics/{OverviewBar,CallDetailPanel,labels}.tsx`，以及 `index.css` 的 Trace Console 段。

浏览器实测中发现并修掉了四个真实缺陷，都不是靠读代码能发现的：

1. **列表"首个有效块"列恒为 `—`。** 列表 DTO 根本没有这个字段，而 ui-spec §6.2 要求这一列。
   后端 `AiDiagnosticCall` 增加 `firstMeaningfulChunkMs`（只有流式非空），前端直接用，不为一列去拉详情。
   顺带把测试 fixture 改真实——原来 fixture 给同步 Chat 也塞了流式时刻。
2. **1280px 双栏把列表挤成竖排折行。** 表格有 8 列，1280px 下分栏后每个单元格都在折行，关键状态
   反而更难读。双栏断点提到 1600px，之下改为右侧固定抽屉（正是 ui-spec §8.2 的"普通桌面用抽屉"）。
3. **375px 下页头被挤成一列单字，且卡片仍要横向滚动。** 页头改竖排，卡片模式取消
   `overflow-x`，实测 `scrollWidth === innerWidth`。
4. **可点击行与摘要格子没有可访问名。** 两者都是 `role="button"`，读屏只会念出"button"。
   分别补了完整句子的 `aria-label`。

**遗留 — 前端自动化测试未落地：** 仓库从来没有前端测试运行器（`package.json` 无 test 脚本）。
本工单尝试引入 vitest + testing-library 时被 pnpm store 版本不一致挡住：`node_modules` 由
pnpm 10.7.1 装出，当前环境是 10.12.3，`pnpm add` 报 `ERR_PNPM_UNEXPECTED_STORE`，
要解决必须整体重装依赖。**这属于工具链/CI 决策，不该在一张前端功能工单里单方面做掉**，
已留给工单 22（CI 与部署门禁）连同"把 `pnpm test` 接进 ci-validate"一起处理；
在那之前 UI-04/05/08/09/10 只有浏览器实测证据，没有可重放的自动化。

> **已解除（2026-08-07）**，见 [test-matrix §14.6](../../../tech/model-scheduling/test-matrix.md)。
>
> **上面那句成因记错了。** 复现出来的报错不是版本号不一致——全局 pnpm、corepack 解析版本与
> `packageManager` 字段本就都是 10.7.1。真正的冲突是 store **目录版本**：`node_modules`
> 链接自 `store/v11`，当前 pnpm 要用 `store/v10`。修法碰巧一样（整体重装），但按版本号去查
> 会一路查空。原文保留在上面，方便对照这条误判是怎么传下去的。
