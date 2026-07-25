# 01 — 产品：知识库模块界面规格说明

**What to build:** 一份界面规格文档，让前端能照着实现三个页面（知识库列表、文档列表、分块管理）而不需要回来追问需求。核心是把「状态怎么展示」和「什么条件下操作不可用」这两类规则一次定清楚——这些规则散落在实现里就会前后不一致。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [ ] 产出 `tech/knowledge-base/ui-spec.md`，覆盖三个页面的布局结构与数据状态规则
- [ ] 文档四种状态（`PENDING`/`RUNNING`/`SUCCESS`/`FAILED`）有确定的中文表述和视觉区分方式
- [ ] 明确 `RUNNING` 状态下哪些操作按钮必须禁用，禁用时的提示文案是什么
- [ ] `ingestion_run` 的 `SKIPPED` 状态有专门措辞——它不是失败，是"检查过、内容没变、无需处理"，不能让用户以为出了问题
- [ ] `phase` 五个取值（`DOWNLOAD`/`EXTRACT`/`CHUNK`/`EMBED`/`PERSIST`）有中文映射，让失败信息对用户有意义
- [ ] 批量启用/禁用有明确的操作反馈文案（"已选中 N 个，其中 M 个将发生状态变化"）
- [ ] 413/415/429/409 四种错误各有对应的用户提示文案（这四种都是用户能自己解决的问题，不能只显示"请求失败"）
- [ ] 空态（知识库为空、文档为空、分块为空）各有说明文案与引导操作
- [ ] `node scripts/check-doc-links.mjs` 通过
