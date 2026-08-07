# Phase 2 大模型调度引擎里程碑状态

## 1. Metadata

- `version`: xrag Phase 2
- `phase`: 大模型调度引擎 / Step G
- `status`: **awaiting-owner-confirmation**（Step A~F 已交付，Step E/G 闸门待项目所有者确认）
- `owner`: liuqiang
- `updated_at`: 2026-08-07
- `step_g_confirmed_at`: —
- `closed_at`: —
- `closure`: 未关闭。工单 23（真实 Provider 准入）因缺合规计费 Key 保持 blocked
- `retrospective`: [Phase 2 复盘](../retro/2026-08-07-phase-2-model-scheduling-retrospective.md)
- `exec_plan`: [Phase 2 执行计划](../exec-plans/active/2026-07-28-model-scheduling-phase-plan.md)

## 2. 当前结论

Phase 2 的 23 张原子工单中，**22 张已完成并通过 `scripts/ci-validate.sh`**。上层业务现在只面向
四个稳定能力端口调用 Chat、流式 Chat、Embedding 和 Rerank；调度层负责能力匹配、确定性路由、
预算、熔断、故障转移与可诊断记录，并有一个只读 Trace Console 能回答"选了谁、为什么切换、耗时多少"。

**里程碑尚未关闭**，两个原因，都不是实现缺陷：

1. **工单 23 卡在外部条件**——需要一把合规的通用按量计费 Key。现有 `EMBEDDING_API_KEY` 是
   火山方舟 Coding Plan 凭据，官方条款禁止直接 API 调用，architecture §13 明确不得扩展到
   其他能力。没有 Key 时它维持 blocked：不改代码伪造支持，也不算作通过。
2. **Step E / Step G 是计划里写死的确认闸门**，需要项目所有者签字。测试矩阵的执行结果已回写
   （test-matrix §14），但其中标注的待办项需要所有者裁决后才能关闭。

## 3. 里程碑进度

| Step | 内容 | 状态 |
|---|---|---|
| A | 8 篇学习整理 + 检查点 + 一手资料复核 | completed（2026-07-28） |
| B | PRD、界面规格、A/B/C 可点击原型，选定 A — Trace Console | completed（2026-07-28） |
| C | 架构/数据模型/API/测试矩阵、ADR 0003~0005、任务书、23 张工单 | completed（2026-08-02） |
| D | 22/23 张工单完成；23 因缺合规 Key blocked | **partially complete** |
| E | 测试矩阵执行结果已回写（§14），6 项手工用例待补、23 条 A 用例待逐条确认 | **awaiting confirmation** |
| F | CI 与部署门禁（工单 22）已完成 | completed（2026-08-07） |
| G | 复盘与里程碑状态已产出，待所有者确认 | **awaiting confirmation** |

## 4. 已交付能力

| 能力 | 交付内容 |
|---|---|
| 领域与配置 | 不可变模型目录、启动校验、不含密钥值的 config fingerprint |
| 同步 Chat | 单候选调用、有界原地重试、跨候选故障转移、429 独立配额边界 |
| 流式 Chat | WebClient/SSE adapter、单次订阅守卫、探测/提交/唯一终态、六阶段超时、取消传播 |
| Embedding | 按最小批量上限切 work unit、逐批独立恢复、mixed execution、Phase 1 调用点原子迁移 |
| Rerank | 稳定 candidate ID 端口、`/reranks` 适配器、三种原因的显式 Noop 降级 |
| 路由与熔断 | 确定性候选排序、monotonic Deadline、attempt 预算、Resilience4j 三态 breaker |
| 诊断 | 完成态异步写入、五张表、按 7 天/10,000 条物理清理、隐私标记串审计 |
| 诊断 API | overview、calls 列表、calls 详情三个只读接口 |
| 前端 | A — Trace Console：摘要条 + 最近调用 + 详情与真实事件时间线 |
| 前端测试 | vitest + testing-library（仓库首次），14 条覆盖 UI-04/05/08/09/10，已进 `ci-validate.sh` |
| 运维 | env example 登记 Phase 2 配置、部署 README 的四条运维须知、CI 全程假 Provider |

## 5. 验证状态

### 本地最新验证（2026-08-07）

| 项 | 结果 |
|---|---|
| `scripts/ci-validate.sh` | 通过 |
| backend（clean 构建） | 399 tests，0 failures，**2 skipped** |
| 2 个 skipped | `RealEmbeddingApiTests`，无 Key 自动跳过。**这两条 skipped 掩盖了一个缺口**：该测试在工单 15 之后已改为走 Phase 2 调度层，但从迁移起就一直是 skipped，等于迁移后的真实 Embedding 链路从未被验证过——2026-08-07 一次实测是失败的，见 [test-matrix §14.4.1](../../tech/model-scheduling/test-matrix.md) |
| frontend | `pnpm build` + `pnpm lint` 通过；`pnpm test` **14 passed / 0 failed**（2026-08-07 接入） |
| 文档链接 | `scripts/check-doc-links.mjs` 通过 |
| 浏览器 | `/diagnostics` 在 1440px 与 375px 两档实测，详见 test-matrix §14.3 |

> **计数必须 clean 构建后统计。** `target/surefire-reports/` 会残留已删除测试类的旧报告，
> 本阶段前期因此把总数虚报过 11。

### 远端验证

本阶段未触发远端 CI（不 push）。Phase 1 遗留的远端 `validate` 红灯（递延项 B-01）与本阶段无关，
不因 Phase 2 的本地绿灯而改写。

## 6. 当前工作节点

- Step D 已无可自主推进的工单。
- 下一步取决于项目所有者：确认 Step E/G，或提供合规 Key 解锁工单 23。

## 7. 递延项与待办

| 编号 | 内容 | 阻塞原因 | 影响 |
|---|---|---|---|
| P2-01 | 工单 23 真实 Provider 准入 | 缺合规通用计费 Key | `verifiedCapabilities` 恒为空数组（"尚未验证"，非"不支持"）。**范围比原先记的更大**：迁移后的 Embedding 真实链路同样未验证，且 2026-08-07 一次实测是失败的，见 [test-matrix §14.4.1](../../tech/model-scheduling/test-matrix.md) |
| ~~P2-02~~ | ~~前端测试运行器~~ | **已解除（2026-08-07）** | vitest + testing-library 落地并接进 `ci-validate.sh`，UI-04/05/08/09/10 有可重放自动化（14 passed），断言经三次变异验证。详见 [test-matrix §14.6](../../tech/model-scheduling/test-matrix.md) |
| P2-03 | 4 项纯手工用例未执行 | UI-01、UI-03、UI-06、DIA-18 | 见 test-matrix §14.3。UI-05/UI-10 的自动化已补，只剩低优先级的真实环境确认 |
| P2-04 | 23 条 A 用例待逐条确认 | 无同名测试方法，很可能已被描述性命名的测试覆盖 | 见 test-matrix §14.2 |
| P2-05 | api.md §7 的 Embedding `validation` 三标志 | V6 Schema 未存储，失败调用无法归因 | 当前成功时返回三个 true、其余返回 null，不猜；需 V7 加列 |

## 8. 已知风险与保留项

- **单实例假设**：breaker 存在于当前 JVM（ADR 0005）。多实例下各实例各算各的，诊断页显示的是
  你连到的那个实例；保留清理也应只在一个实例开启。横向扩容前必须回到 ADR 0005 重新决策。
- **配置无热更新**：catalog 是启动时快照，改配置或补密钥都要重启。
- **诊断 best effort**：队列满会丢、写库失败会计数，两者都不影响模型调用，但意味着页面可能缺记录。
  overview 会显示"部分调用诊断可能缺失"及两个进程内计数。
- **Rerank 缺 secret 是硬失败**：目录里没有 RERANK deployment 可以降级为 Noop，但声明了却缺密钥
  会直接以 AUTHENTICATION 失败。`ai-models.yml` 里的 rerank deployment 因此默认 `enabled: false`。
- **`/reranks` 线路形状未经真实验证**：适配器按 Cohere/Jina/百炼共用形状实现，
  路径、字段名与 `max-documents: 100` 都是待工单 23 核实的占位。

## 9. Phase 3 准入条件

Phase 3（AI 知识问答核心链路）消费的是四个稳定能力端口，不依赖工单 23。因此：

- **不阻塞**：Phase 3 可在 P2-01 未解除的情况下开始，用假 Provider 或已配置的 Embedding 凭据开发。
- **需知悉**：Phase 3 不得绕过 `com.app.ai` 直接调用 Provider SDK；Rerank 可能返回 Noop 降级，
  调用方必须区分 `rerankApplied=false` 与普通成功。
- ~~**建议先解除**：P2-02（前端测试运行器）~~ — **已于 2026-08-07 解除**。Phase 3 的前端工作
  直接沿用这套栈：测试文件与被测组件同目录，`pnpm test` 已在 `ci-validate.sh` 内。

## 10. 关联资产

- [Phase 2 执行计划](../exec-plans/active/2026-07-28-model-scheduling-phase-plan.md)
- [分角色任务书](../exec-plans/active/2026-07-28-model-scheduling-implementation-lanes.md)
- [23 张原子工单](../../.scratch/model-scheduling/README.md)
- [技术方案](../../tech/model-scheduling/architecture.md) / [数据模型](../../tech/model-scheduling/data-model.md) / [API 契约](../../tech/model-scheduling/api.md)
- [测试矩阵（含 §14 执行回写）](../../tech/model-scheduling/test-matrix.md)
- [界面规格](../../tech/model-scheduling/ui-spec.md) / [可点击原型](../../prototype/model-scheduling/index.html)
- ADR [0003](../adr/0003-separate-model-capability-ports.md) / [0004](../adr/0004-reactive-streaming-runtime.md) / [0005](../adr/0005-separate-model-runtime-state.md)
