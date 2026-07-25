# 16 — URL 来源文档

**What to build:** 用户能贴一个文件直链把远程文档加进知识库，之后的分块、管理、分块编辑全部与本地上传的文档一致。

**Blocked by:** 07（文件上传——共用对象存储与元数据落库路径）、10（异步入库）

**Status:** ready-for-agent

- [ ] 后端 `POST /api/v1/knowledge-bases/{kbId}/documents/url`，行为与 [api.md §3](../../../tech/knowledge-base/api.md) 一致
- [ ] **抓取必须先落本地临时文件再上传对象存储**：远程响应的 `Content-Length` 不可信（可能缺失、可能是 chunked、也可能撒谎），只有真正写完文件才知道实际大小，才能做大小校验和哈希计算
- [ ] 抓取到的文件同样存进对象存储（有了本地副本，后续重新分块不必重新下载）
- [ ] 抓取失败返回 `400` 且**不落库**（不留下一条永远无法处理的记录）
- [ ] 记录 `http_etag` 与 `http_last_modified`，供工单 17 的变更检测使用
- [ ] `sourceUri` 只校验协议为 http/https；抓到 HTML 时**不报错**（Tika 能提取正文，噪声多是用户输入质量问题，不是系统错误），前端负责提示"应填文件直链而非网页地址"
- [ ] 大小限制与格式白名单与文件上传保持一致
- [ ] 临时文件在成功与失败路径下都清理（`finally`）
- [ ] 前端：文档列表页的"添加 URL 来源"表单，含直链提示
- [ ] 集成测试：抓取成功后 `source_document` 记录 `source_type='URL'`、`file_key` 有值、`content_hash` 已计算
- [ ] 集成测试：URL 不可达时返回 `400` 且数据库无新记录
- [ ] 手工验证：URL 来源文档能正常触发分块并成功（证明它与 FILE 来源共用同一条执行链路）
- [ ] `./mvnw -q -B verify` 与 `pnpm build && pnpm lint` 通过
