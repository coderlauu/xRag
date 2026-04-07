# Current Handoff

当前默认入口始终指向“当前正在推进的版本”。

- 当前有效版本：[v4 / Phase 2A](/Users/coderlauu/xRag/docs/handoff/v4.md)
- 当前版本状态：[v4 / Phase 2A Status](/Users/coderlauu/xRag/docs/status/v4-phase-2a.md)

上一阶段归档：

- [v3 / Phase 1C Status](/Users/coderlauu/xRag/docs/status/v3-phase-1c.md)
- [v3 Handoff](/Users/coderlauu/xRag/docs/handoff/v3.md)
- [Phase 1A Retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-02-phase-1a-retrospective.md)
- [v1 / Phase 1A Status](/Users/coderlauu/xRag/docs/status/v1-phase-1a.md)
- [v1 Handoff](/Users/coderlauu/xRag/docs/handoff/v1.md)
- [v2 / Phase 1B Status](/Users/coderlauu/xRag/docs/status/v2-phase-1b.md)
- [v2 Handoff](/Users/coderlauu/xRag/docs/handoff/v2.md)

当前基线技术资产：

- [Phase 1C Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-1c-architecture.md)
- [Phase 1C Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-1c-data-model.md)
- [Phase 1C API Design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-1c-api.md)
- [Phase 1C OpenAPI](/Users/coderlauu/xRag/docs/generated/openapi/phase-1c-api.json)
- [Deploy README](/Users/coderlauu/xRag/deploy/README.md)
- [Production Inspection Guide](/Users/coderlauu/xRag/deploy/production-inspection-guide.md)
- [Harness Engineering Playbook](/Users/coderlauu/xRag/docs/process/2026-03-31-harness-engineering-playbook.md)

当前活跃版本资产：

- [Phase 2A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-prd.md)
- [Phase 2A Planning Exec Plan](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-07-phase-2a-planning-and-design.md)
- [v4 Interaction Spec](/Users/coderlauu/xRag/design/spec/2026-04-07-v4-interaction-spec.md)
- [Phase 2A Prototype](/Users/coderlauu/xRag/prototype/v4/index.html)
- [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)
- [v3 / Phase 1C Status](/Users/coderlauu/xRag/docs/status/v3-phase-1c.md)
- [v3 Handoff](/Users/coderlauu/xRag/docs/handoff/v3.md)
- [CI 自动修复闭环方案](/Users/coderlauu/xRag/docs/process/2026-04-06-ci-auto-repair-loop.md)

---

## 1. 当前版本一句话目标

在统一导入、检索与可观测性基线上，引入可引用的 AI 问答与混合检索，让资料从可找回走向可直接复用。

---

## 2. 当前版本边界

### 必须实现

- 基于个人知识库的 `AI 问答`
- `keyword + semantic` 混合检索
- 问答范围控制
- 答案证据链与无证据拒答
- 导入内容到向量索引的 freshness、诊断与回补基线

### 明确不做

- 开放互联网联网回答
- 浏览器插件
- 团队协作
- 移动端
- 全自动 agent 工作流

### 当前阶段依赖的既有基线

- `text / file / link / OCR` 统一导入链路已在 `Phase 1C` 完成
- `search explain / timeline / ops` 诊断基线已存在
- `web + api + worker + db + storage + queue` 工程基线已完成
- production 已可访问，且具备 `db.xrag.coderlau.cn` 与 PostgreSQL 回环映射的排查入口

---

## 3. 建议阅读顺序

1. [v4 Handoff](/Users/coderlauu/xRag/docs/handoff/v4.md)
2. [v4 Status](/Users/coderlauu/xRag/docs/status/v4-phase-2a.md)
3. [Phase 2A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-prd.md)
4. [Phase 2A Planning Exec Plan](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-07-phase-2a-planning-and-design.md)
5. [v4 Interaction Spec](/Users/coderlauu/xRag/design/spec/2026-04-07-v4-interaction-spec.md)
6. [Phase 2A Prototype](/Users/coderlauu/xRag/prototype/v4/index.html)
7. [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)
8. [v3 Status](/Users/coderlauu/xRag/docs/status/v3-phase-1c.md)
9. [Phase 1C Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-1c-architecture.md)
10. [Phase 1C Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-1c-data-model.md)
11. [Phase 1C API Design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-1c-api.md)
12. [Phase 1C OpenAPI](/Users/coderlauu/xRag/docs/generated/openapi/phase-1c-api.json)

---

## 4. 执行规则

1. 当前活跃版本为 `v4 / Phase 2A`，当前已完成 `PRD / interaction spec / prototype / evaluation plan` 的 planning 资产冻结与一致性校验，尚未进入正式技术方案设计与实现 fan-out
2. `v4` 的第一优先级是定义可信问答层的产品边界、交互边界与信任边界，不是继续扩张输入渠道或提前冻结工程实现细节
3. 复杂任务继续先写 `docs/exec-plans/active/*.md`，当前版本真实进度统一写入 `docs/status/v4-phase-2a.md`
4. 当前 `Phase 2A` 尚未创建正式 `tech/*` 文档；`v3 / Phase 1C` 继续作为实现基线与技术参考

---

## 5. 本文件用途

以后恢复开发时，先读这三份：

1. `docs/handoff/current.md`
2. 当前版本 `docs/status/*.md`
3. 当前活跃 `exec plan`

如果需要再深入，再看对应 `tech/*` 和 retrospective。
