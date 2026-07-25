# 15 — 分块批量启用/禁用

**What to build:** 用户勾选一批分块，一次性启用或禁用。

这是本模块设计含量最高的一个接口：批量 Embedding 调用的耗时随数量线性增长，因此**必须用编程式事务**把它挪到事务外，用 `@Transactional` 注解会让数据库连接在等待 API 响应期间被长时间占用。

**Blocked by:** 14（分块新增与单条启禁用）

**Status:** ready-for-agent

- [ ] 后端 `PATCH /api/v1/documents/{docId}/chunks/enabled`，返回 `{requested, changed, alreadyInTargetState}`
- [ ] **`chunkIds` 必填**，不支持"全部启用/禁用"（那个需求应该用文档级接口，语义更准确）
- [ ] 单次上限 500 条，超限返回 `400`
- [ ] **任一 id 无效或不属于该文档 → 整批失败返回 `400`**，不静默跳过。放宽成"跳过无效的继续处理"会让调用方以为 500 个都处理了、实际只处理了 498 个
- [ ] 只处理 `enabled <> 目标值` 的分块，已是目标状态的计入 `alreadyInTargetState`；**全部已是目标状态时正常返回 `changed: 0` 而不报错**
- [ ] 启用/禁用两条路径都是"精准写入/精准删除"，只处理本次变更的分块，**不做全量重建**（必须与单条接口的策略一致：如果全量重建对批量是必要的，单条也该做；如果不必要，批量也没必要）
- [ ] **用 `TransactionOperations` 编程式事务**，批量 Embedding 在事务外算完，事务内只有数据库写入 + 向量操作
- [ ] **方法本身不能加 `@Transactional`**——否则内部的 `executeWithoutResult` 会加入外层事务，精确控制事务范围的意图就落空了。这一点在代码注释中说明
- [ ] 前端：分块列表多选 + 批量操作按钮，操作前展示"已选中 N 个，其中 M 个将发生状态变化"，操作后用响应的 `changed` 二次确认
- [ ] 集成测试：501 条返回 `400`；含一个不存在的 id 时整批失败且**数据库无任何变更**；含一个属于其他文档的 id 时同样整批失败
- [ ] 集成测试：3 个分块中 1 个已是目标状态，返回 `{requested:3, changed:2, alreadyInTargetState:1}`
- [ ] 集成测试：全部已是目标状态时返回 `changed: 0` 且 HTTP `200`（不是错误）
- [ ] `./mvnw -q -B verify` 与 `pnpm build && pnpm lint` 通过
