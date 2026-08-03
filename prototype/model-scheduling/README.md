# 模型调度诊断页原型

> **PROTOTYPE — 一次性界面验证资产，不是生产实现。**

验证问题：模型调度诊断页应优先围绕“单次调用追踪”“全局运行态”还是“候选路由关系”组织信息？

## 评审结论

项目所有者于 2026-07-28 选择 **A — Trace Console**：

- 正式实现采用“最近调用列表 + 固定调用详情/attempt 时间线”的双栏主结构；
- 顶部保留紧凑 Provider/熔断摘要；
- 不采用 B 的大面积 KPI 仪表盘，也不采用 C 作为默认信息架构；
- B/C 继续留在本原型中，作为这次界面决策的原始证据，不复制到正式前端。

原型提供三种结构不同的方案，通过 `?variant=A|B|C` 或底部切换器选择：

- A — Trace Console（已选定）
- B — Operations Overview
- C — Route Map

启动：

```bash
python3 -m http.server 4173 --directory prototype/model-scheduling
```

打开：

```text
http://127.0.0.1:4173/?variant=A
```

页面顶部可切换六个演示场景；键盘 `←` / `→` 可切换方案。
