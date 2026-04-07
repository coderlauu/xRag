# Current Handoff

当前默认入口始终指向“当前正在推进的版本”。

当前有效版本：[v2 / Phase 1B](/Users/coderlauu/xRag/docs/handoff/v2.md)  
当前版本状态：[v2 / Phase 1B Status](/Users/coderlauu/xRag/docs/status/v2-phase-1b.md)（已完成）

上一阶段归档：

- [Phase 1A Retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-02-phase-1a-retrospective.md)
- [v1 / Phase 1A Status](/Users/coderlauu/xRag/docs/status/v1-phase-1a.md)
- [v1 Handoff](/Users/coderlauu/xRag/docs/handoff/v1.md)

当前基线技术资产：

- [Phase 1A Architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md)
- [Phase 1A Data Model](/Users/coderlauu/xRag/tech/data-model/2026-03-31-phase-1a-data-model.md)
- [Phase 1A API Design](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)
- [Harness Engineering Playbook](/Users/coderlauu/xRag/docs/process/2026-03-31-harness-engineering-playbook.md)

当前最近完成版本资产：

- [Phase 1B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-02-xrag-phase-1b-prd.md)
- [Phase 1B Planning Exec Plan](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-02-phase-1b-planning-and-design.md)
- [CI Failure Loop Exec Plan](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-04-ci-failure-loop.md)
- [CI Auto Repair Loop Exec Plan](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-06-ci-auto-repair-loop.md)
- [Phase 1B Prototype & Interaction Exec Plan](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-06-phase-1b-prototype-and-interaction.md)
- [Phase 1B Harness Hardening Exec Plan](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-06-phase-1b-harness-hardening.md)
- [Phase 1B Interaction Spec](/Users/coderlauu/xRag/design/spec/2026-04-06-v2-interaction-spec.md)
- [Phase 1B Prototype](/Users/coderlauu/xRag/prototype/v2/index.html)
- [Phase 1B Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-06-phase-1b-architecture.md)
- [Phase 1B Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-06-phase-1b-data-model.md)
- [Phase 1B API Design](/Users/coderlauu/xRag/tech/api/2026-04-06-phase-1b-api.md)
- [Phase 1B Implementation Lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-06-phase-1b-implementation-lanes.md)
- [CI 自动修复闭环方案](/Users/coderlauu/xRag/docs/process/2026-04-06-ci-auto-repair-loop.md)

---

## 1. 当前版本一句话目标

在 `Phase 1A` 已可用的生产基线上，完成导入链路与生产稳态能力增强，重点解决 `pdf` 真实解析、multipart 上传和 observability。

---

## 2. 当前版本边界

### 必须实现

- 支持 `pdf` 真实解析闭环
- 支持 multipart 大文件上传
- 补齐解析失败诊断与重试可见性
- 建立 production 观测与回滚基线

### 明确不做

- AI 问答
- 语义搜索
- OCR
- 浏览器插件
- 团队协作
- 移动端

### 当前阶段依赖的既有基线

- `Inbox / Search / Detail` 三页闭环已在 `Phase 1A` 完成
- `web + api + worker + db + storage + queue` 工程基线已完成
- production 已可访问，不再重复搭骨架

---

## 3. 建议阅读顺序

1. [v2 Handoff](/Users/coderlauu/xRag/docs/handoff/v2.md)
2. [v2 Status](/Users/coderlauu/xRag/docs/status/v2-phase-1b.md)
3. [Phase 1B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-02-xrag-phase-1b-prd.md)
4. [Phase 1B Prototype](/Users/coderlauu/xRag/prototype/v2/index.html)
5. [Phase 1B Interaction Spec](/Users/coderlauu/xRag/design/spec/2026-04-06-v2-interaction-spec.md)
6. [Phase 1B Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-06-phase-1b-architecture.md)
7. [Phase 1B Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-06-phase-1b-data-model.md)
8. [Phase 1B API Design](/Users/coderlauu/xRag/tech/api/2026-04-06-phase-1b-api.md)
9. [Phase 1B Implementation Lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-06-phase-1b-implementation-lanes.md)
10. [Phase 1B Planning Exec Plan](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-02-phase-1b-planning-and-design.md)

---

## 4. 执行规则

1. 当前 `Phase 1B` 已完成主线程的 `schema / shared-types / API contract / diagnosis code` 冻结，并已重新生成 [Phase 1B OpenAPI](/Users/coderlauu/xRag/docs/generated/openapi/phase-1b-api.json)
2. 当前没有活跃中的 `exec plan`；如进入下一轮需求，先按版本流程创建新的 `handoff / status / exec plan`
3. 版本的真实进度统一写入 `docs/status/`
4. 复杂任务先写 `docs/exec-plans/active/*.md`
5. 历史版本只归档，不覆盖

---

## 5. 本文件用途

以后恢复开发时，先读这三份：

1. `docs/handoff/current.md`
2. 当前版本 `docs/status/*.md`
3. 当前活跃 `exec plan`

如果需要再深入，再看对应 `tech/*` 和 retrospective。
