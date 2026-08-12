# 3B 浏览器走查资产

`seed.sql` 造出 ui-spec §13.11 的演示场景，供 `3B-UI-01~08` 的手工走查复现。

## 为什么是 seed 而不是走真实链路

走查要看的是**界面表达**，而界面数据来自 API 响应。走真实链路需要 3A 的意图评分，那是
真实 Chat 调用、**按次计费**；而且无密钥时 3A 会短路成 `NO_MATCH`，检索压根不执行
（见[工单 14](../issues/14-privacy-e2e.md) 的实测）。所以直接造落库结果，零模型调用。

这与原型底部那个演示控制台是同一个思路，区别是这次跑在真实前端 + 真实 API 上。

## 用法

```bash
scripts/test-env-up.sh                     # 确保 Postgres 就位
docker exec -i xrag-postgres-1 psql -U app -d app -v ON_ERROR_STOP=1 < .scratch/qa-pipeline/3b/walkthrough/seed.sql
```

脚本开头会先删掉 `走查%` 前缀的旧数据，可重复执行。前端起在任意端口（5173 被占用时用
`.claude/launch.json` 的 `frontend-walkthrough`），打开 `/chat` → 对话列表里选场景。

## 场景与用例的对应

| 对话 | 覆盖 |
|---|---|
| 走查 1 正常来源 | `3B-UI-01`（三项齐全、无分数无名次）、`3B-API-04`（文档已删除仍返回、禁用跳转） |
| 走查 2 未精排 | `3B-UI-02`（摘要行与抽屉标题同时可见） |
| 走查 3 部分失败 | `3B-UI-03`（点名失败库 + 两个动作） |
| 走查 4 无结果 | `3B-UI-04`（与 3A `NO_MATCH` 可区分、无"通用知识"入口） |
| 走查 5 工具待确认 | `3B-UI-05`（不抢焦点、Tab 先到取消、Esc 不取消） |
| 走查 6~9 | 工具已完成 / 失败 / 不可用 / 已中止，`3B-UI-06`、`3B-UI-08` |
| 走查 10 工具执行中 | `3B-UI-06` 的"执行中输入区禁用且无中止入口" |

## 已知：工具显示的是内部名、参数表为空

`submit-return-ticket` 而不是 `提交退货工单`，确认卡的参数表也是空的。**这不是界面缺陷**：
运行时 `app.qa.tools.whitelist` 为空，`TurnResultViewAssembler` 因此拿不到 `displayName` /
`description` / 参数元信息，回落到内部名。

而白名单**配不上**——工单 09 的启动校验要求每个白名单工具都有对应的 `ToolExecutor` bean，
`backend/src/main/java/` 里目前一个实现都没有。3B 建好了工具框架，真实工具属于后续阶段。

后果值得记住：确认卡的参数表是"用户确认前唯一能核对的东西"（ui-spec §13.7.2），在当前部署
形态下它是空的。等第一个真实工具落地时，这条要重新走查一遍。
