# Phase 1 AI 知识库建设里程碑状态

## 1. Metadata

- `version`: xrag Phase 1
- `phase`: AI 知识库建设 / Step G
- `status`: in-progress
- `owner`: liuqiang
- `updated_at`: 2026-07-28
- `step_g_confirmed_at`: 2026-07-28
- `closure`: 等待 Step F 远端 CI 跑绿
- `retrospective`: [Phase 1 复盘](../retro/2026-07-28-phase-1-knowledge-base-retrospective.md)

## 2. 当前结论

Phase 1 的学习、产品、设计、实现和测试验收已经完成；交付门禁尚未完成。当前可称为**功能完成**，不能称为**里程碑已关闭**。

## 3. 里程碑进度

| Step | 产出 | 状态 | 证据 |
|---|---|---|---|
| A | 知识库专题学习整理 | completed | [学习索引：03 组 11/11](../../learning/ragent-column/INDEX.md) |
| B | PRD、界面规格、可点击原型 | completed | [PRD](../prd/2026-07-25-knowledge-base-prd.md)、[界面规格](../../tech/knowledge-base/ui-spec.md)、[原型](../../prototype/knowledge-base/index.html) |
| C | 技术方案、ADR、任务书、工单、测试矩阵 | completed | [技术方案](../../tech/knowledge-base/architecture.md)、[任务书](../exec-plans/active/2026-07-25-knowledge-base-implementation-lanes.md)、[工单索引](../../.scratch/knowledge-base/README.md) |
| D | 前后端实现 | completed | 20 张工单全部完成；三个业务页面和完整知识库入库链路已落地 |
| E | 自动化与手工验收 | completed | [测试矩阵 §9](../../tech/knowledge-base/test-matrix.md) |
| F | CI/CD 与部署配置 | in-progress | 配置已落地；远端 `validate` 红灯由 Claude 并行修复 |
| G | 复盘与里程碑状态 | completed | 本文及已确认的 [Phase 1 复盘](../retro/2026-07-28-phase-1-knowledge-base-retrospective.md) |

## 4. 已交付能力

- 知识库创建、查询、编辑和受保护删除。
- 本地文件及远程 URL 文档导入。
- 固定长度与递归分隔符两种分块策略。
- 异步文本提取、分块、Embedding 和持久化。
- 启动回收、心跳超时回收和失败后重试。
- URL 定时同步、内容未变化跳过、变化后版本化重建。
- 文档编辑、启禁用、删除、处理历史和查看源文件。
- 分块查看、筛选、分页、编辑、新增、删除及批量启禁用。
- 不可变对象版本、任务输入快照、向量知识库隔离和对象存储审计。

## 5. 验证状态

### 本地最新验证

- `scripts/ci-validate.sh`: passed
- 前端生产构建与类型检查: passed
- 前端 lint: passed
- 后端测试: 176 tests / 0 failures / 0 errors / 2 skipped
- 手工验收: 10 条 M 系列用例及 `DOC-09` 全部完成

两条跳过项是 `RealEmbeddingApiTests` 中受 `EMBEDDING_API_KEY` 环境变量保护的真实供应商用例；真实链路已在 Step E 单独执行并记录通过，CI 使用确定性假实现，不产生费用。

### 远端最新验证

- GitHub Actions run: [30321368139](https://github.com/coderlauu/xRag/actions/runs/30321368139)
- `infra`: passed
- `validate`: failed
- 镜像构建及后续部署: skipped

本地与远端结果不一致是当前唯一直接阻止 Phase 1 关闭的交付门禁。

## 6. 当前工作节点

- `now`:
  - Claude 并行修复 Step F 的远端 CI 问题。
  - Step G 文档已获项目所有者确认。
- `next`:
  1. Step F 提供一次 `validate` 绿灯及运行链接。
  2. 将本文的 CI 状态与最终提交 SHA 更新为真实结果。
  3. 更新总计划与分角色任务书状态，并把 Phase 1 执行计划归档。
  4. 进入 Phase 2：大模型调度引擎。

## 7. 阻塞项

### B-01 远端质量门禁失败

- `status`: in-progress
- `impact`: Phase 1 不能关闭，不能用远端流水线作为可重复交付证据
- `owner`: Claude（并行处理）
- `exit`: GitHub `validate` 成功，并记录对应 commit SHA 与 run URL

## 8. 已知风险与保留项

| 风险/保留项 | 当前处理 |
|---|---|
| Coding Plan Key 不允许直接业务 API 调用 | 个人学习场景知情保留；共享或生产部署前必须替换 |
| 单实例入库调度 | 由 ADR 0002 明确约束；多实例部署前重新设计 |
| 对象存储永久清理默认关闭 | 当前只审计和报告，不自动删除历史对象 |
| 前端无自动化测试 | 当前由类型检查、lint 和浏览器手工验收覆盖；Phase 2 重新决策 |
| 生产域名、账号和密钥仍是占位值 | 符合当前“不做真实生产上线”的项目范围 |
| 尚无检索与问答能力 | 属于 Phase 3，不计入 Phase 1 缺陷 |

## 9. Phase 2 准入条件

- [ ] Step F 远端 `validate` 绿灯。
- [x] Step G 已于 2026-07-28 获项目所有者确认。
- [ ] 总计划和 Phase 1 任务书更新为最终事实，不再保留“152 条测试、剩余 3 项手工验证”等过时状态。
- [ ] 明确 Phase 2 是否有用户界面；若有，继续执行 PRD、界面规格和可点击原型闸门。
- [ ] Phase 2 使用任何外部模型 API 前，先完成供应商条款与限额准入检查。

## 10. 关联资产

- [总体执行计划](../exec-plans/active/2026-07-24-ragent-column-learning-and-system-build.md)
- [Phase 1 分角色任务书](../exec-plans/active/2026-07-25-knowledge-base-implementation-lanes.md)
- [Phase 1 PRD](../prd/2026-07-25-knowledge-base-prd.md)
- [架构方案](../../tech/knowledge-base/architecture.md)
- [数据模型](../../tech/knowledge-base/data-model.md)
- [API 契约](../../tech/knowledge-base/api.md)
- [界面规格](../../tech/knowledge-base/ui-spec.md)
- [测试矩阵](../../tech/knowledge-base/test-matrix.md)
- [存储与向量布局复核](../research/2026-07-28-storage-and-vector-layout-review.md)
