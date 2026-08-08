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

启动：

```bash
python3 -m http.server 4174 --directory prototype/qa-pipeline/conversation
```

打开：

```text
http://127.0.0.1:4174/?variant=A
```

底部可切换演示场景；最下方悬浮切换器或键盘 `←` / `→` 切换 A/B/C。输入框聚焦时不会拦截方向键。
