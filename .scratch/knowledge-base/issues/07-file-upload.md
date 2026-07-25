# 07 — 本地文件上传（含内存实测）

**What to build:** 用户能在文档列表页选一个本地文件上传，上传成功后文档以「待处理」状态出现在列表里。上传只存文件和元数据，**不触发分块**——界面必须明确展示"待处理"，不能让用户以为已经能检索了。

这张工单包含**可能推翻技术方案的那项验证**：上传 30MB 文件时的堆内存峰值。优先把这项做掉，再投入前端上传交互的细节，避免方案变了要改两遍。

**Blocked by:** 05（知识库 CRUD——文档必须挂在知识库下）

**Status:** done（`2026-07-25`）

- [x] 后端 `POST /api/v1/knowledge-bases/{kbId}/documents/file`（multipart），行为与 [api.md](../../../tech/knowledge-base/api.md) §3 一致
- [x] 扩展名白名单 `.txt` `.md` `.pdf` `.docx`，白名单外在**上传接口就拒绝**（`415`），不等到分块阶段才失败
- [x] 超出 `max-file-size` 返回 `413`
- [x] 实现路径：`MultipartFile.transferTo(临时文件)` → `s3Client.putObject(req, RequestBody.fromFile(tempFile))` → 写 `source_document(status='PENDING', revision=0)` → **删除临时文件（`finally` 中，异常路径也要删）**
- [x] **不加 `@Transactional`**：方法内唯一的数据库操作是最后一条 INSERT，本身原子；前面是耗时 IO，加事务只会长时间占用连接
- [x] 后端 `GET /api/v1/knowledge-bases/{kbId}/documents` 分页列表，支持 `status`/`enabled` 过滤
- [x] 前端文档列表页：分页列表 + 状态过滤 + 上传入口，上传成功后列表刷新并显示「待处理」
- [x] **【最高优先级手工验证】** 上传 30MB 文件，用 `jcmd <pid> GC.heap_info` 或 JFR 观察堆峰值。若确认存在 [学习笔记 03-03](../../../learning/ragent-column/03-knowledge-base/03-03-为什么上传30MB文件占了100MB内存.md) 记录的内存放大，**停下来更新技术方案改用预签名 URL 方案**，不要默默继续
- [x] 实测结果（无论达标与否）记录进 `test-matrix.md` 和任务书 Decision Log
- [x] 集成测试：上传成功后 `source_document` 有对应记录且 `status='PENDING'`；不支持的扩展名返回 `415`；临时文件在成功和失败路径下都已清理
- [x] `./mvnw -q -B verify` 与 `pnpm build && pnpm lint` 通过

## 完成记录

### M-01 内存实测：**通过，技术方案保持不变**

这是本模块唯一可能推翻技术方案的用例，先做掉了再动前端。完整数据见 [test-matrix.md §9 M-01](../../../tech/knowledge-base/test-matrix.md)，结论：

**把文件从工单写的 30MB 加到了 48MB。** 理由是 30MB 按学习笔记 03-03 记录的约 3.3 倍放大也只有 ~100MB，仍装得进 256MB 堆——"没 OOM"证明不了什么，判定不够尖锐。48MB 是 50MB 上限内的最大值，若被整体缓冲会逼近甚至超过堆上限。

最有说服力的不是"没 OOM"，而是**堆 committed 全程停在 61.4MB 一次都没扩张**（上传前 used 38.7MB，连续 4 次 48MB 上传后峰值 51.4MB）。JVM 连要更多堆的必要都没有，说明请求体压根没进过堆。`RequestBody.fromFile` 的行为与 architecture.md §7 的预期一致，**不需要退回预签名 URL 方案**。

对象存储侧核对过实际落盘：`part.1` 50,333,184 字节（源文件 50,331,648 + RustFS 分片对齐），内容完整，不是传了个截断的。

### 顺带补上了一处漏掉的表结构

`source_document` 缺 `chunk_strategy` / `chunk_size` / `chunk_overlap` 三列——api.md 一直接受并回传它们，V2 建表时漏了。用 **`V3__source_document_chunk_config.sql` 增列而不是改 V2**：V2 已实际执行过，改它会破坏 Flyway 校验和，而已执行迁移不可变正是 Flyway 的价值。data-model.md §3.2 已同步。列名用 `chunk_overlap` 而非 `overlap`，避免与 SQL 的 `overlaps` 谓词形近读错。

### 实现要点

- **上传方法上没有 `@Transactional`**，这是有意的：唯一的数据库操作是最后一条 INSERT（本身原子），前面是落盘 + 对象存储上传两段耗时 IO，包进事务只会让一个连接被占用整个上传时长。
- **`MaxUploadSizeExceededException` 必须显式处理**，否则 413 返回的是容器默认错误页而不是契约里的 `{error, message}`。它由 multipart 解析器在控制器方法之前抛出，所以 `@RestControllerAdvice` **不能限定 basePackages**——那时 handler 还没解析出来。实测 55MB 文件返回的是契约体。
- **临时文件在 `finally` 里删**，成功和失败路径都覆盖，有对应用例（比对上传前后 `xrag-upload-*` 残留文件列表）。删失败只记警告——为一个残留文件再抛异常会把真正的原因盖掉。
- 白名单复用工单 09 的 `TextExtractor.isSupported`，在上传入口就返回 415。

### 验证

8 条集成测试（真实 Postgres + 真实 RustFS，不用假存储——上传路径的核心就是落盘/上传/落库三段）。浏览器里走通：空态 → 上传 `.txt` → 列表出现「待处理」+ 底部"有 1 篇文档待处理"提示条 → 上传 `.pptx` 触发 415，页面显示"不支持这种文件格式。目前支持 .txt、.md、.pdf、.docx。"且已有列表未被破坏。

**PENDING 按 ui-spec 当重点处理**：上传接口返回后文档并没有被分块，只显示一个灰标签的话用户会以为系统在后台自动处理然后一直等下去。

### 未做

- 状态筛选的"筛选后为空"走的是 ui-spec §8 要求的独立文案 + 清除筛选链接，不是空态。
- 触发分块、启用/禁用、删除、URL 来源都不在本工单，分别是工单 10 / 12 / 16。
