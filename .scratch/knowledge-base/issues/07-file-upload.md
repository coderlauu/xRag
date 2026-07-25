# 07 — 本地文件上传（含内存实测）

**What to build:** 用户能在文档列表页选一个本地文件上传，上传成功后文档以「待处理」状态出现在列表里。上传只存文件和元数据，**不触发分块**——界面必须明确展示"待处理"，不能让用户以为已经能检索了。

这张工单包含**可能推翻技术方案的那项验证**：上传 30MB 文件时的堆内存峰值。优先把这项做掉，再投入前端上传交互的细节，避免方案变了要改两遍。

**Blocked by:** 05（知识库 CRUD——文档必须挂在知识库下）

**Status:** ready-for-agent

- [ ] 后端 `POST /api/v1/knowledge-bases/{kbId}/documents/file`（multipart），行为与 [api.md](../../../tech/knowledge-base/api.md) §3 一致
- [ ] 扩展名白名单 `.txt` `.md` `.pdf` `.docx`，白名单外在**上传接口就拒绝**（`415`），不等到分块阶段才失败
- [ ] 超出 `max-file-size` 返回 `413`
- [ ] 实现路径：`MultipartFile.transferTo(临时文件)` → `s3Client.putObject(req, RequestBody.fromFile(tempFile))` → 写 `source_document(status='PENDING', revision=0)` → **删除临时文件（`finally` 中，异常路径也要删）**
- [ ] **不加 `@Transactional`**：方法内唯一的数据库操作是最后一条 INSERT，本身原子；前面是耗时 IO，加事务只会长时间占用连接
- [ ] 后端 `GET /api/v1/knowledge-bases/{kbId}/documents` 分页列表，支持 `status`/`enabled` 过滤
- [ ] 前端文档列表页：分页列表 + 状态过滤 + 上传入口，上传成功后列表刷新并显示「待处理」
- [ ] **【最高优先级手工验证】** 上传 30MB 文件，用 `jcmd <pid> GC.heap_info` 或 JFR 观察堆峰值。若确认存在 [学习笔记 03-03](../../../learning/ragent-column/03-knowledge-base/03-03-为什么上传30MB文件占了100MB内存.md) 记录的内存放大，**停下来更新技术方案改用预签名 URL 方案**，不要默默继续
- [ ] 实测结果（无论达标与否）记录进 `test-matrix.md` 和任务书 Decision Log
- [ ] 集成测试：上传成功后 `source_document` 有对应记录且 `status='PENDING'`；不支持的扩展名返回 `415`；临时文件在成功和失败路径下都已清理
- [ ] `./mvnw -q -B verify` 与 `pnpm build && pnpm lint` 通过
