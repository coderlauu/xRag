# 问答对话页原型

> **PROTOTYPE — 一次性界面验证资产，不是生产实现。**

验证问题：在 Phase 3A 只有历史、改写、意图与歧义、还没有最终答案时，问答页应如何同时服务自然对话与链路验收？

原型提供三种结构不同的方案：

- A — Conversation + Inspector（2026-08-07 已选择）
- B — Pipeline Workbench
- C — Focused Chat

## 评审结论

项目所有者最终确认 **A — Conversation + Inspector**：正式页面采用左侧对话列表、中央消息主区与
可收起的右侧“本轮处理”面板；窄屏下两侧区域降级为抽屉。B/C 继续保留为本次决策证据。

## 3B 增量（2026-08-08，draft，待确认）

新增九个 `3B` 场景，验证的问题是：检索来源、工具四态与三类降级如何在不新增页面的前提下并入这一页。
规格是 [ui-spec §13](../../../tech/qa-pipeline/conversation/ui-spec.md)，产品语义冻结在
[PRD §12](../../../docs/prd/2026-08-07-qa-pipeline-prd.md)。

- 右栏拆成「本轮结果」（来源 + 工具）与「处理详情」（3A 验收信息）两组。
- 可点的活路径：工具确认 → 执行中 → 已完成；取消；失败后重试**重新出确认卡**；
  部分失败的重试与「就现有结果继续」。
- **3B 场景只在 A 方案上演示**（B/C 只是 3A 的决策留档），点 3B 场景会自动切到 A 并说明原因。

启动：

```bash
python3 -m http.server 4174 --directory prototype/qa-pipeline/conversation
```

打开：

```text
http://127.0.0.1:4174/?variant=A
```

底部可切换演示场景；最下方悬浮切换器或键盘 `←` / `→` 切换 A/B/C。输入框聚焦时不会拦截方向键。
