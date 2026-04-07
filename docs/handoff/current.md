# Current Handoff

当前默认入口始终指向“当前正在推进的版本”。

当前有效版本：[v3 / Phase 1C](/Users/coderlauu/xRag/docs/handoff/v3.md)  
当前版本状态：[v3 / Phase 1C Status](/Users/coderlauu/xRag/docs/status/v3-phase-1c.md)

上一阶段归档：

- [Phase 1A Retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-02-phase-1a-retrospective.md)
- [v1 / Phase 1A Status](/Users/coderlauu/xRag/docs/status/v1-phase-1a.md)
- [v1 Handoff](/Users/coderlauu/xRag/docs/handoff/v1.md)
- [v2 / Phase 1B Status](/Users/coderlauu/xRag/docs/status/v2-phase-1b.md)
- [v2 Handoff](/Users/coderlauu/xRag/docs/handoff/v2.md)

当前基线技术资产：

- [Phase 1A Architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md)
- [Phase 1A Data Model](/Users/coderlauu/xRag/tech/data-model/2026-03-31-phase-1a-data-model.md)
- [Phase 1A API Design](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)
- [Harness Engineering Playbook](/Users/coderlauu/xRag/docs/process/2026-03-31-harness-engineering-playbook.md)

当前活跃版本资产：

- [Phase 1C PRD](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-1c-prd.md)
- [Phase 1C Prototype](/Users/coderlauu/xRag/prototype/v3/index.html)
- [v3 Interaction Spec](/Users/coderlauu/xRag/design/spec/2026-04-07-v3-interaction-spec.md)
- [Phase 1C Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-1c-architecture.md)
- [Phase 1C Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-1c-data-model.md)
- [Phase 1C API Design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-1c-api.md)
- [Phase 1C OpenAPI](/Users/coderlauu/xRag/docs/generated/openapi/phase-1c-api.json)
- [Phase 1C Planning Exec Plan](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-07-phase-1c-planning-and-design.md)
- [Phase 1C Implementation Lanes](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-07-phase-1c-implementation-lanes.md)
- [CI 自动修复闭环方案](/Users/coderlauu/xRag/docs/process/2026-04-06-ci-auto-repair-loop.md)
- [Phase 1B Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-06-phase-1b-architecture.md)
- [Phase 1B Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-06-phase-1b-data-model.md)
- [Phase 1B API Design](/Users/coderlauu/xRag/tech/api/2026-04-06-phase-1b-api.md)

---

## 1. 当前版本一句话目标

在 `Phase 1B` 已稳定的导入与解析基线上，扩展扫描件与链接内容接入能力，并提升检索质量与处理过程可观测性。

---

## 2. 当前版本边界

### 必须实现

- 扫描版 PDF OCR 解析闭环
- 链接正文抓取与入库闭环
- 搜索排序、匹配解释与结果可读性增强
- 文档处理时间线与诊断可见性增强

### 明确不做

- AI 问答
- 向量检索 / 语义召回
- 浏览器插件
- 团队协作
- 移动端

### 当前阶段依赖的既有基线

- `Inbox / Search / Detail` 三页闭环已在 `Phase 1A` 完成
- `web + api + worker + db + storage + queue` 工程基线已完成
- production 已可访问，不再重复搭骨架

---

## 3. 建议阅读顺序

1. [v3 Handoff](/Users/coderlauu/xRag/docs/handoff/v3.md)
2. [v3 Status](/Users/coderlauu/xRag/docs/status/v3-phase-1c.md)
3. [Phase 1C PRD](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-1c-prd.md)
4. [Phase 1C Prototype](/Users/coderlauu/xRag/prototype/v3/index.html)
5. [v3 Interaction Spec](/Users/coderlauu/xRag/design/spec/2026-04-07-v3-interaction-spec.md)
6. [Phase 1C Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-1c-architecture.md)
7. [Phase 1C Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-1c-data-model.md)
8. [Phase 1C API Design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-1c-api.md)
9. [Phase 1C Implementation Lanes](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-07-phase-1c-implementation-lanes.md)

---

## 4. 执行规则

1. 当前 `Phase 1C` 已完成需求、原型、交互、增量技术方案和主线程 contract freeze，下一步是按 lane 进入实现
2. 新版本的真实进度统一写入 `docs/status/`
3. 复杂任务先写 `docs/exec-plans/active/*.md`
4. 历史版本只归档，不覆盖

---

## 5. 本文件用途

以后恢复开发时，先读这三份：

1. `docs/handoff/current.md`
2. 当前版本 `docs/status/*.md`
3. 当前活跃 `exec plan`

如果需要再深入，再看对应 `tech/*` 和 retrospective。
