# Prototype Workspace

`prototype/` 用于存放各阶段的 HTML 原型交付物，按版本隔离，避免后续迭代覆盖已有成果。

## Current Version

- `v1/`
  对应当前的 Phase 1A 原型，实现 `导入 -> 搜索 -> 详情` 的 MVP 闭环。

## Related Docs

- [Phase 1A PRD](/Users/christina/Documents/xRag/docs/prd/2026-03-31-xrag-phase-1a-prd.md)
- [v1 Scope 与取舍](/Users/christina/Documents/xRag/docs/decisions/2026-03-31-v1-scope-and-tradeoffs.md)
- [v1 交互说明](/Users/christina/Documents/xRag/design/spec/2026-03-31-v1-interaction-spec.md)
- [产品交付 SOP](/Users/christina/Documents/xRag/docs/process/product-delivery-sop.md)

## Conventions

- 每个版本目录都保持 `HTML 页面 + assets + mock-data` 的固定结构。
- 版本之间不直接覆盖，通过新增 `v2/`、`v3/` 扩展。
- 通用资源优先沉淀到 `shared/`。

## How To Open

可以直接在浏览器打开 [prototype/v1/index.html](/Users/christina/Documents/xRag/prototype/v1/index.html)。

如果希望用本地服务预览，也可以在仓库根目录运行：

```bash
python3 -m http.server 8000
```

然后访问 `http://localhost:8000/prototype/v1/index.html`。
