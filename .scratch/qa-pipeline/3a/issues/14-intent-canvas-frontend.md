# 14 — 意图树 C：Route Canvas

**What to build:** 按 approved C 落地关系画布 + 右侧检查器，支持搜索、创建/编辑、影响预览、子树启停/删除和 revision 冲突恢复。

**Blocked by:** 11, 12
**Status:** complete

## Slice

- 完整快照布局、缩放/平移/选中、路径和状态标签；不靠位置保存树结构。
- GROUP/KB/SYSTEM 表单、KB 选择器、examples、snippet 说明和 dirty guard。
- 先 impact 后命令；enable 两种 scope；冲突保留表单并刷新 current revision。
- 720/375 降级为可完成任务的列表→详情，不强迫移动端操作无限画布。

## Acceptance

- [x] 3A-UI-09~16 具备可构造状态；组件测试覆盖 conflict/dirty/impact。
- [x] 前端不自行判断“可以启用”后绕过服务端，成功后以新完整 snapshot 原子替换。
- [x] 键盘可选节点、打开菜单、返回检查器；状态有文字/标签。
- [x] 不实现拖拽换父、MCP、版本回滚、导入导出或 AI 优化整树。

**Design:** [intent-tree ui-spec](../../../../tech/qa-pipeline/intent-tree/ui-spec.md)、[API §4~5](../../../../tech/qa-pipeline/api.md)

## Evidence（2026-08-08）

`frontend/src/pages/IntentTreePage.test.tsx` 15 例，全量 `pnpm test --run` 51 项通过，`pnpm build`
与 `pnpm lint` 通过（零告警）。同样在全局 `fetch` 层断言，因此"每条写命令都带 expectedRevisionId、
危险命令先发 impact、成功后用返回的整套 nodes 替换"都是在真实请求上验证的。

| 矩阵项 | 落点 |
|---|---|
| 3A-UI-09 | 画布按角色/状态标签渲染；GROUP/KB/SYSTEM 三种表单差异（示例、KB 选择器、系统场景闭集、附加规则）|
| 3A-UI-10 | 新建弹层写明“新节点强制停用”，创建后自动选中新节点继续完善 |
| 3A-UI-11 | 检查器停用与节点菜单删除都先 `POST /impact`，确认后才发 disable/delete，scope 恒为 SUBTREE |
| 3A-UI-12 | 叶子只给“仅启用当前节点”，GROUP 另有“启用整个已配置完整的子树”并先预览 |
| 3A-UI-13 | 409 保留本地表单、展示服务端当前版本号、刷新快照供比较，画布不被本地输入覆盖 |
| 3A-UI-14 | 搜索保留祖先路径、无结果给“清除搜索”；绑定失效显示阻塞提示且不静默换绑 |
| 3A-UI-15 | 缩放不影响键盘选中；375px 画布与检查器变列表→详情，返回后节点与展开状态仍在 |
| 3A-UI-16 | 状态用文字标签（已启用/已停用/祖先已停用/配置不完整）；危险确认默认焦点在“取消” |

实现说明与偏离：

- 画布用 DOM 嵌套 = 树层级的左右展开布局，缩放是 CSS `scale`、平移用容器滚动，不做无限画布拖拽——
  自造拖拽会同时废掉 `tree/treeitem` 的键盘语义（ui-spec §13 要求键盘能走完整棵树）。
- ui-spec §8.2 的“父节点是已绑定叶子时阻止新增子节点并解释”改为**不给入口**：叶子在 3A 根本不能有
  子节点，菜单里只对 GROUP 显示“新增子节点”，没有需要解释的失败路径。
- 阻塞节点目前只展示数量与说明，不逐条列出路径：API 的 `blockingNodes` 只返回 nodeId，逐条展示需要
  在当前快照里反查，且被阻塞节点可能已不在当前 revision。逐条列表留给工单 16 的实机走查后再定。

浏览器实机走查与矩阵回写属工单 16，本工单不代签。
