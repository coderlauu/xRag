# Prototype Workspace

`prototype/` 用于存放各阶段的 HTML 原型交付物，按版本隔离，避免后续迭代覆盖已有成果。

## Current Version

- `v3/`
  对应当前的 `Phase 1C` 原型，强化扫描件 OCR、链接抓取、搜索命中解释和文档处理时间线。
- `v2/`
  对应上一阶段 `Phase 1B` 原型，强化 `pdf` 真实解析、multipart 上传、失败诊断和运维可见性。
- `v1/`
  对应当前的 Phase 1A 原型，实现 `导入 -> 搜索 -> 详情` 的 MVP 闭环。

## Related Docs

- [当前版本 Handoff](/Users/coderlauu/xRag/docs/handoff/current.md)
- [Phase 1C PRD](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-1c-prd.md)
- [v3 交互说明](/Users/coderlauu/xRag/design/spec/2026-04-07-v3-interaction-spec.md)
- [Phase 1B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-02-xrag-phase-1b-prd.md)
- [v2 交互说明](/Users/coderlauu/xRag/design/spec/2026-04-06-v2-interaction-spec.md)
- [Phase 1A PRD](/Users/coderlauu/xRag/docs/prd/2026-03-31-xrag-phase-1a-prd.md)
- [v1 Scope 与取舍](/Users/coderlauu/xRag/docs/decisions/2026-03-31-v1-scope-and-tradeoffs.md)
- [v1 交互说明](/Users/coderlauu/xRag/design/spec/2026-03-31-v1-interaction-spec.md)
- [产品交付 SOP](/Users/coderlauu/xRag/docs/process/product-delivery-sop.md)

## Conventions

- 每个版本目录都保持 `HTML 页面 + assets + mock-data` 的固定结构。
- 版本之间不直接覆盖，通过新增 `v2/`、`v3/` 扩展。
- 通用资源优先沉淀到 `shared/`。

## How To Open

可以直接在浏览器打开：

- [prototype/v3/index.html](/Users/coderlauu/xRag/prototype/v3/index.html)
- [prototype/v2/index.html](/Users/coderlauu/xRag/prototype/v2/index.html)
- [prototype/v1/index.html](/Users/coderlauu/xRag/prototype/v1/index.html)

如果希望用本地服务预览，也可以在仓库根目录运行：

```bash
python3 -m http.server 8000
```

然后访问：

- `http://localhost:8000/prototype/v3/index.html`
- `http://localhost:8000/prototype/v2/index.html`
- `http://localhost:8000/prototype/v1/index.html`
