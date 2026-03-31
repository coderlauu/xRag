# Prototype Workspace

`prototype/` 用于存放各阶段的 HTML 原型交付物，按版本隔离，避免后续迭代覆盖已有成果。

## Current Version

- `v1/`
  对应当前的 Phase 1A 原型，实现 `导入 -> 搜索 -> 详情` 的 MVP 闭环。

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

