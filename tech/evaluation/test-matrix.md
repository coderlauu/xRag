# 测试矩阵：RAG 评测体系

- `status`: Step C review
- `date`: 2026-08-13
- `prd`: [PRD](../../docs/prd/2026-08-13-rag-evaluation-prd.md)
- `architecture`: [架构](architecture.md)

## 1. 执行规则

- `A` 自动化；`M` Step E 人工/真实调用；默认 CI 不执行 M。
- 深模块接口 + Testcontainers 是主测试面；HTTP 只补契约，CLI 只补薄客户端。
- 每条负向隐私/失败用例必须有对应正向功能断言，不能只断言“没泄漏”。

## 2. Dataset 与身份（EV-D01～D16）

| ID | 场景 | 断言 | 类型 |
|---|---|---|---|
| EV-D01 | canonical 字段顺序不同 | sample hash 相同 | A |
| EV-D02 | reference/gold/split 改变 | 新 sample revision/hash | A |
| EV-D03 | 同 sample 跨 split | 发布失败 | A |
| EV-D04 | SYSTEM 无 gold docs | 合法且检索指标不适用 | A |
| EV-D05 | reference/rubric 缺失 | 相关 metric 资格失败，不阻止其他指标 | A |
| EV-D06 | 外部 Judge 未授权 | 语义评分跳过且有稳定原因 | A |
| EV-D07 | 发布后修改请求 | 旧 revision 不变 | A |
| EV-D08 | retire dataset | 新 run 禁止，旧 run 可读 | A |
| EV-D09 | dataset member 顺序变化 | dataset hash 变化 | A |
| EV-D10 | manifest hash 重算 | 与持久化一致 | A |
| EV-D11 | 近重复跨 holdout | 校验告警/阻断按策略 | A |
| EV-D12 | 12+36+12 容量上限 | 超限发布失败 | A |
| EV-D13 | 非法 risk/provenance | 发布失败且字段级错误稳定 | A |
| EV-D14 | Unicode NFC/LF 等价文本 | canonical hash 相同 | A |
| EV-D15 | 新 dataset 引用 retired sample | 发布失败，历史 dataset 不受影响 | A |
| EV-D16 | provenance 不允许外部发送却声明 Judge | 校验失败，不靠运行时侥幸拦截 | A |

## 3. Fixture 与录制（EV-R01～R14）

| ID | 场景 | 断言 | 类型 |
|---|---|---|---|
| EV-R01 | 文档未分块 | preview 返回 FIXTURE_NOT_READY | A |
| EV-R02 | 向量模型 identity 不符 | 阻止运行 | A |
| EV-R03 | 意图 revision 缺失 | 阻止运行 | A |
| EV-R04 | 单样本成功 | 唯一 conversation/turn 与 recording | A |
| EV-R05 | 答案与 contexts | 均来自同一 turn，无第二次检索 | A |
| EV-R06 | 多子问题 | 全部子问题/意图保序录制 | A |
| EV-R07 | Rerank Noop | degradation 明确录制 | A |
| EV-R08 | SYSTEM 直答 | 无伪造 contexts | A |
| EV-R09 | 工具确认夹具 | 选择/参数/确认/终态录制 | A |
| EV-R10 | 流失败有部分回答 | response + incomplete + 终态一致 | A |
| EV-R11 | 取消 | 不提交新样本，当前调用收到取消 | A |
| EV-R12 | 重启时已有 terminal turn | 装配一次，不重提问 | A |
| EV-R13 | 实际证据无法重建 | MISSING_EVIDENCE，不近似补齐 | A |
| EV-R14 | 真实 qwen-plus 单样本 | turn/trace/model/usage 可追溯 | M |

## 4. 状态、预算与恢复（EV-S01～S14）

| ID | 场景 | 断言 | 类型 |
|---|---|---|---|
| EV-S01 | Idempotency-Key 重放 | 同一 run ID | A |
| EV-S02 | 两协调器 claim | 单个获胜 | A |
| EV-S03 | CI 环境有真实 Key | 仍使用 fake | A |
| EV-S04 | 真实模式少任一预算 | 422 | A |
| EV-S05 | 请求/Token/金额/时间任一耗尽 | 停止新调用并保留结果 | A |
| EV-S06 | usage 无法计价 | COST_UNKNOWN，不算 0 | A |
| EV-S07 | 单样本失败 | 后续样本继续，run PARTIAL | A |
| EV-S08 | cancel 重放 | 幂等且唯一终态 | A |
| EV-S09 | 应用重启遗留 RUNNING | 安全恢复为 pending/assemble | A |
| EV-S10 | 终态后迟到 usage | 不改预算/状态 | A |
| EV-S11 | 并发=1 性能 baseline | 无评测自身并发排队污染 | A |
| EV-S12 | run capacity 满 | 429，不无限排队 | A |
| EV-S13 | wall deadline 竞态 | 单一终态，无额外请求 | A |
| EV-S14 | 真实付费预算 | 实际用量不超上限 | M |

## 5. 指标与聚合（EV-M01～M16）

| ID | 场景 | 断言 | 类型 |
|---|---|---|---|
| EV-M01 | intent Top-1 | 子问题级分母正确 | A |
| EV-M02 | Hit@K | 至少一项 gold 命中 | A |
| EV-M03 | Recall@K must/nice | 两种口径分开 | A |
| EV-M04 | MRR | 首个相关排名正确 | A |
| EV-M05 | 空 gold | NOT_APPLICABLE | A |
| EV-M06 | 缺 recording | MISSING_EVIDENCE | A |
| EV-M07 | scorer 抛错 | EVALUATION_FAILED | A |
| EV-M08 | 正确拒答 | 不进入检索分母 | A |
| EV-M09 | 误拒/过召回 | 行为指标互不混用 | A |
| EV-M10 | canceled/failed | 可靠性计数，不静默跳过 | A |
| EV-M11 | TTFT 与 total | 单位/定义分开 | A |
| EV-M12 | Rerank 降级切片 | 与 applied 分开聚合 | A |
| EV-M13 | 有效/失败/跳过 | 每项分母展示 | A |
| EV-M14 | 同 recording 重算 | 确定性结果一致 | A |
| EV-M15 | overall/intent/sample | 三层聚合一致 | A |
| EV-M16 | 无综合总分 | schema/report 均不存在该字段 | A |

## 6. Semantic evaluator（EV-J01～J12）

| ID | 场景 | 断言 | 类型 |
|---|---|---|---|
| EV-J01 | fake 批量评分 | 原始 repetition 保留 | A |
| EV-J02 | 单项 NaN/解析失败 | 仅该 observation 失败 | A |
| EV-J03 | worker 整批失败 | 确定性指标仍存在 | A |
| EV-J04 | 超预算前 | 不发下一 Judge 请求 | A |
| EV-J05 | Prompt/模型变化 | metric revision 改变 | A |
| EV-J06 | 实际响应模型 | 写入 evaluator manifest | A |
| EV-J07 | 未授权正文 | payload 中不存在 | A |
| EV-J08 | 重试 | 调用/Token/费用均累计 | A |
| EV-J09 | 取消 | worker 调用终止 | A |
| EV-J10 | RAGAS adapter contract | 不泄露框架类型到领域/API | A |
| EV-J11 | 12 条中文校准 | 一致率、误判、NaN、费用完整 | M |
| EV-J12 | 默认 vs adapted Prompt | 分开 metric revision，不混 baseline | M |

## 7. 报告、diff 与 review（EV-P01～P14）

| ID | 场景 | 断言 | 类型 |
|---|---|---|---|
| EV-P01 | render 重跑 | 内容 hash 稳定 | A |
| EV-P02 | artifact 生成失败 | DB 事实不丢失 | A |
| EV-P03 | failures 多原因 | 同一样本保留完整原因链 | A |
| EV-P04 | 严格可比 A/B | paired delta 正确 | A |
| EV-P05 | dataset/fixture/Judge 不同 | NOT_STRICTLY_COMPARABLE + 差异 | A |
| EV-P06 | review append | 原自动分不变 | A |
| EV-P07 | stale observation review | 409 | A |
| EV-P08 | 最新人工采用 | 聚合可重算且 override 数可见 | A |
| EV-P09 | 修改 gold 走 review | 拒绝，要求新 sample revision | A |
| EV-P10 | baseline 不完整 run | 拒绝 | A |
| EV-P11 | Markdown/CSV/JSONL | 可互相追到 run/sample/metric | A |
| EV-P12 | 老正文已清理 | 报告仍显示身份/聚合，正文 unavailable | A |
| EV-P13 | CLI exit 4 | REVIEW_REQUIRED 映射正确 | A |
| EV-P14 | 项目所有者报告复核 | 可从 failure 跳到 turn/trace | M |

## 8. 隐私、保留与 HTTP（EV-H01～H14）

| ID | 场景 | 断言 | 类型 |
|---|---|---|---|
| EV-H01 | 列表查询 | 无 input/reference/context/response |
| EV-H02 | ProblemDetail | 无 Provider 原始错误/秘密 |
| EV-H03 | 日志扫描 | 无正文、Key、Authorization、思维链 |
| EV-H04 | 未授权详情 | 正文不返回 |
| EV-H05 | recording 到期 | 只清正文，事实/审计保留 |
| EV-H06 | 最近 20 与 30 天 | 先到条件正确 |
| EV-H07 | dataset retire | 逻辑废弃，不物理删除 |
| EV-H08 | run 列表游标 | 稳定无重复遗漏 |
| EV-H09 | sample 分页 | 稳定 ordinal 顺序 |
| EV-H10 | command idempotency | 并发重放单资源 |
| EV-H11 | invalid state | 409 稳定码 |
| EV-H12 | CI/部署配置 | 真实开关默认 false |
| EV-H13 | 文档链接/DDL 闭集 | CI 校验通过 |
| EV-H14 | 全量 `ci-validate` | 默认零付费、全绿 |

EV-H01～H14 均为 A；Step E 另需人工审查一份真实 artifact，确认不存在未授权正文。

## 9. 覆盖汇总

- 自动化：94 条（D16 + R13 + S13 + M16 + J10 + P13 + H14）。
- 显式人工/真实：6 条（R14、S14、J11、J12、P14、隐私 artifact 审查）。
- 合计：100 条；Step C 确认后逐工单映射，Step E 必须逐项回写结果。
