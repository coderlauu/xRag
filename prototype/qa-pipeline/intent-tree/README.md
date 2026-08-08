# 意图树管理页原型

> **PROTOTYPE — 一次性界面验证资产，不是生产实现。**

验证问题：意图树管理页应优先围绕“树结构 + 单节点编辑”“全局运营表格”还是“路由关系画布”组织？

原型提供三种结构不同的方案：

- A — Tree + Inspector
- B — Operational Outline
- C — Route Canvas（2026-08-07 已选择）

## 评审结论

项目所有者选择 **C — Route Canvas**：正式页面采用路由关系画布与节点检查器；接受大树、键盘操作和
窄屏适配成本，并要求后续在 Step C / 测试矩阵中正面覆盖。A/B 继续保留为本次决策证据。

启动：

```bash
python3 -m http.server 4175 --directory prototype/qa-pipeline/intent-tree
```

打开：

```text
http://127.0.0.1:4175/?variant=C
```

底部可切换停用子树、启用受阻、删除、版本冲突和搜索场景；最下方切换器或键盘 `←` / `→` 切换方案。
