# 13 — 分块查看与编辑

**What to build:** 用户能按原文顺序浏览一份文档的分块，发现某个分块把一段完整描述切断了，直接改掉它的内容，改完后向量同步更新。

这是「手动干预」这个永久兜底能力的第一块（PRD §4.4：自动分块无法覆盖所有情况，手动干预不是临时方案）。

**Blocked by:** 10（异步入库——要先有分块才能查看）

**Status:** ready-for-agent

- [ ] 后端 `GET /api/v1/documents/{docId}/chunks`：按 `chunk_index` **升序**分页（不按 id 或创建时间——用户是照原文顺序浏览的，按插入顺序排会把手动新增的分块甩到末尾），支持 `enabled` 过滤
- [ ] 后端 `PUT /api/v1/chunks/{chunkId}`：只接受 `content`；`charCount`/`tokenCount`/`contentHash` 服务端重算
- [ ] **内容与库中完全一致时直接返回，不触发任何向量操作**——省下按 token 计费的调用，也避免"删旧插新"期间该分块短暂不在向量库里的检索空窗
- [ ] 向量"更新"实现为**删旧插新**（同一个 `chunk_id`），不是原地改
- [ ] Embedding 调用在事务外完成后再开事务写库
- [ ] 后端 `DELETE /api/v1/chunks/{chunkId}`：逻辑删除分块 + 物理删除向量 + 文档 `chunk_count` 递减，递减用 `case when chunk_count > 0 then chunk_count - 1 else 0 end` 保证不出现负数
- [ ] 前端分块管理页（子页面或抽屉）：按顺序分页展示分块内容、字符数、token 数、启用状态；编辑与删除操作
- [ ] 集成测试：提交与库中完全相同的内容时，向量表对应行的 `create_time` **未发生变化**（这是"没有触发删旧插新"的直接证据）
- [ ] 集成测试：修改内容后向量表该行的 `create_time` 已更新，且 `document_chunk.content_hash` 已重算
- [ ] 集成测试：删除分块后文档 `chunk_count` 递减 1；把 `chunk_count` 手工改成 0 再删一个分块，确认结果是 0 而不是 -1
- [ ] 浏览器中完整走通：查看分块列表 → 编辑一个分块 → 内容已更新
- [ ] `./mvnw -q -B verify` 与 `pnpm build && pnpm lint` 通过
