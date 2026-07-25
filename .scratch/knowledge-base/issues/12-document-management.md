# 12 — 文档配置更新、启用/禁用、删除

**What to build:** 用户能改文档名和分块参数、临时下线一份有问题的文档（禁用）、删除不需要的文档。

「禁用」这个功能的正确性有一个不直观的要求：**必须物理删除向量库里的数据，而不是只打个标记位**。检索是直接查向量表的，只改数据库标记的话文档内容照样会被检索到，"禁用"就完全没有效果。

**Blocked by:** 10（异步入库——启用需要重算向量，复用同一套 Embedding 链路）

**Status:** ready-for-agent

- [ ] 后端 `PUT /api/v1/documents/{docId}`：全字段可选，只更新传了的字段；`RUNNING` 中返回 `409`
- [ ] `sourceUri`/`syncEnabled`/`syncCron` 仅 URL 来源可改，FILE 来源传了返回 `400`
- [ ] 更新分块参数**不自动重新分块**，响应带 `needsRechunk: true` 让前端引导用户显式触发（不替用户决定要不要花一次 Embedding 的钱）
- [ ] 后端 `PATCH /api/v1/documents/{docId}/enabled`：
  - 禁用 → **物理删除**该文档全部分块的向量，分块记录的 `enabled` **不动**（保留用户此前对个别分块的选择）
  - 启用 → 只为 `enabled=true and deleted=false` 的分块重算向量写回（这正是上一条不动 `enabled` 的意义）
  - 已是目标状态 → 直接返回，不做任何操作（幂等）
  - `revision=0`（从未成功分块）时启用 → 无向量可写，正常返回不报错
- [ ] 启用时的 Embedding 调用在**事务外**完成后再开事务写向量
- [ ] 后端 `DELETE /api/v1/documents/{docId}`：按 [data-model.md §4](../../../tech/knowledge-base/data-model.md) 的 SQL 执行，逻辑删除文档与分块、物理删除向量、顺手关掉 `sync_enabled`；对象存储原始文件**保留不删**
- [ ] 前端：文档列表的编辑/启禁用/删除操作；`RUNNING` 时这些按钮按 `ui-spec.md` 禁用并给出提示
- [ ] 集成测试：禁用文档后 `document_chunk_embedding` 中该文档的行数为 0，`document_chunk` 记录仍存在且 `enabled` 未变
- [ ] 集成测试：禁用再启用后，向量行数恢复到与启用分块数一致
- [ ] 集成测试：禁用前先手工禁用其中一个分块，走完"禁用文档 → 启用文档"后，那个分块**仍然是禁用状态且没有向量**
- [ ] 集成测试：`RUNNING` 中的文档，删除/更新/触发分块三种操作都返回 `409`
- [ ] 集成测试：删除文档后其分块在分块列表接口中不再返回；对象存储中的文件仍然存在
- [ ] `./mvnw -q -B verify` 与 `pnpm build && pnpm lint` 通过
