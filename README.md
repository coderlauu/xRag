# xRag

`xRag` 是一个面向个人知识沉淀场景的知识收件箱原型项目，当前重点是验证 `采集 -> 存储 -> 检索 -> 找回` 的 MVP 闭环。

## Repository Layout

项目目录按 `产品文档 / 设计方案 / 技术方案 / 原型交付` 四层拆分，便于后续回顾、协作和版本迭代。

```text
xRag/
├── docs/                    # 全程积累的产品与过程文档
│   ├── prd/                 # 产品需求文档
│   ├── meeting/             # 会议纪要与过程讨论记录
│   ├── retro/               # 阶段复盘
│   └── decisions/           # 关键决策记录，便于后续追溯
├── design/                  # 设计方案与设计资产
│   ├── ui/                  # 线框图、界面稿、视觉探索
│   └── spec/                # 设计规范、交互说明
├── tech/                    # 技术方案沉淀
│   ├── architecture/        # 架构设计与模块分层
│   ├── api/                 # API 设计与接口约定
│   └── data-model/          # 业务模型、索引模型、字段说明
├── prototype/               # 可直接打开的 HTML 原型交付物
│   ├── README.md            # 原型层说明、运行方式、版本说明
│   ├── v1/                  # Phase 1A 当前版本原型
│   │   ├── index.html       # 导入页 / Inbox
│   │   ├── search.html      # 搜索页 / Search
│   │   ├── detail.html      # 详情页 / Detail
│   │   ├── assets/
│   │   │   ├── css/         # 当前版本样式
│   │   │   ├── js/          # 当前版本交互逻辑
│   │   │   └── images/      # 当前版本图片资源
│   │   └── mock-data/
│   │       ├── documents.json
│   │       ├── search-results.json
│   │       └── states.json
│   └── shared/
│       └── assets/          # 多个原型版本可复用的资源
├── .gitignore
└── README.md
```

## Directory Notes

- `docs/`
  项目推进过程中的原始文档沉淀区。PRD、会议纪要、复盘、关键决策都放在这里，适合做回顾和对齐。
- `design/`
  产品和交互设计输出区。`ui/` 放视觉稿和线框图，`spec/` 放界面结构、交互规则、组件说明。
- `tech/`
  面向研发实现的技术沉淀区。架构、接口、数据模型分开保存，避免技术方案混杂在 PRD 或设计稿里。
- `prototype/`
  当前阶段的实际交付物。采用版本化管理，`v1/` 对应本次 Phase 1A 闭环原型；后续迭代新增 `v2/`、`v3/` 即可，不覆盖旧版本。
- `prototype/shared/`
  放多个版本都可能复用的资源，比如通用图标、基础样式、通用脚本或设计 token。

## When Each Area Is Produced

| Directory | Main Content | Typical Timing |
| --- | --- | --- |
| `docs/` | PRD、纪要、复盘、决策记录 | 全程持续积累 |
| `design/ui/` | 线框图、界面稿、视觉方案 | 设计阶段 |
| `design/spec/` | 交互规则、页面说明、组件规范 | 设计阶段 |
| `tech/architecture/` | 架构设计、模块边界 | 技术方案阶段 |
| `tech/api/` | 接口设计、请求响应约定 | 技术方案阶段 |
| `tech/data-model/` | 数据模型、索引模型、字段定义 | 技术方案阶段 |
| `prototype/` | HTML 原型与演示资源 | 原型阶段与迭代阶段 |

## Prototype Versioning Rules

- 每个原型版本都放在 `prototype/vN/` 下，避免覆盖历史版本。
- 每个版本内部继续保持 `页面 / assets / mock-data` 的固定结构，便于快速对比。
- 公共资源优先沉淀到 `prototype/shared/`，减少跨版本复制。
- 如果后续进入正式前端工程阶段，可新增独立的应用目录，但保留 `prototype/` 作为产品验证资产。
