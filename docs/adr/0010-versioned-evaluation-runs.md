# ADR 0010：评测采用不可变三层身份与同源 Turn 录制

- `status`: proposed
- `date`: 2026-08-13

## Context

只给 JSONL 命名 `v1` 无法解释样本、知识库、Prompt 或模型变化；课程用生产 SSE 与第二次检索旁路拼接，
答案和证据可能来自不同执行。

## Decision

分离 sample revision、dataset revision 与 evaluation run manifest。每条样本通过正常 Phase 3 链路只创建
一个 turn；意图、检索、工具、生成、终态和实际模型证据都从该 turn/trace 装配。证据缺失标不可评，
不发第二次检索近似补齐。已发布 revision 与 run manifest 不原地修改。

## Consequences

- A/B 先判断身份可比，能定位知识、系统或 evaluator 变化。
- 需要为实际 Prompt 证据顺序和配置补足可追溯 identity。
- 评测与 QA 模块新增一个窄执行 seam，但不复制 Pipeline。
- 历史数据缺身份时仍可查看，但不能宣称严格可比。
