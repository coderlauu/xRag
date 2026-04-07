# Phase 1B PDF Parser Runtime Hotfix

## 1. Metadata

- `plan_id`: `phase-1b-pdf-parser-runtime-hotfix`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: [current handoff](/Users/coderlauu/xRag/docs/handoff/current.md), [v2 status](/Users/coderlauu/xRag/docs/status/v2-phase-1b.md), [Phase 1B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-02-xrag-phase-1b-prd.md), [Phase 1B architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-06-phase-1b-architecture.md), [Phase 1B api design](/Users/coderlauu/xRag/tech/api/2026-04-06-phase-1b-api.md)

## 2. Objective

修复生产环境上传 PDF 后出现的 `Cannot transfer object of unsupported type.` 解析错误，并补齐能够覆盖真实解析器运行时行为的回归证据。

## 3. Scope

### In Scope

- 定位 `pdf-parse` 在当前 worker 运行环境中的真实异常来源
- 修复 `PDFParse` 调用方式或替换为稳定的解析适配层
- 将该错误从泛化的 `pdf_parse_unsupported` 误分类中拆分出来，至少形成准确的 runtime diagnosis
- 增加能覆盖真实解析器运行时行为的单元或集成回归
- 更新状态文档与必要的技术事实

### Out Of Scope

- 扫描版 PDF OCR
- 链接正文抓取
- 新版本需求定义
- 全量重写 worker 架构

## 4. Assumptions

- 该问题属于 `v2 / Phase 1B` 已上线功能的生产缺陷，不应并入 `Phase 1C` 新功能范围
- 当前代码中的 integration 证据通过注入解析结果完成，未覆盖真实 `pdf-parse` runtime 行为
- 生产环境报错 `Cannot transfer object of unsupported type.` 很可能来自 `pdf-parse` 内部 worker/structured clone 行为

## 5. Risks

- 若继续把所有 PDF runtime 错误归类为 `pdf_parse_unsupported`，会误导排查
- 若只修线上代码而不补真实回归证据，后续同类问题还会再次漏过
- 若替换解析适配层涉及返回结构变化，容易影响搜索和详情投影

## 6. Plan

1. 在本地和测试环境复现真实 `pdf-parse` runtime 错误，确认最小触发条件
2. 修复 `apps/worker/src/jobs/pdf-parser.ts` 的解析器调用方式，必要时替换适配实现
3. 调整 `document-processing` 的 diagnosis 映射，区分 runtime 错误与“文件确实不支持”
4. 补一条使用真实解析器的验证证据，并重跑 `worker/api/e2e` 相关验证
5. 更新 `v2` 状态，确认热修完成后再重新关闭 `Phase 1B`

## 7. Validation

- 单元测试：覆盖真实 parser runtime 错误映射与成功路径
- 集成测试：至少一条真实 PDF 上传 -> complete -> worker 解析 -> detail/search 投影
- E2E / smoke：保持现有 `pnpm validate`、`./scripts/run-e2e-smoke.sh` 和 GitHub Actions 绿灯

## 8. Rollback

- 若修复导致 PDF 解析链更不稳定，可先回退到当前稳定发布版本，并把 diagnosis 保持为明确 runtime failure
- 若第三方解析器不稳定，可先将该错误降级为受控失败并保留文档入库与任务可见性，不影响非 PDF 主链路

## 9. Decision Log

- `2026-04-07`: 将生产 PDF 解析 bug 归类为 `medium-feature` 级热修，不新开版本，继续挂在 `v2 / Phase 1B` 下处理
- `2026-04-07`: 已确认根因是同一 `PDFParse` 实例并发调用 `getText()` 和 `getInfo()` 触发 `DataCloneError`；热修改为顺序读取，并新增 `pdf_parse_runtime_error` 诊断码与真实解析回归测试
