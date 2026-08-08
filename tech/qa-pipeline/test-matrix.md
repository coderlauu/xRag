# 测试矩阵：AI 知识问答核心链路（Phase 3A）

- `status`: approved
- `approved_at`: 2026-08-07
- `date`: 2026-08-07
- `scope`: 本文件当前只批准 `3A-*`；3B/3C 到各自 Step C 增量追加
- `architecture`: [技术方案](architecture.md)
- `data_model`: [数据模型](data-model.md)
- `api`: [API 契约](api.md)

## 1. 执行约定

| 标记 | 含义 |
|---|---|
| `A` | 自动化测试，必须进入普通 CI；模型使用可编程假 Chat，不收费 |
| `M` | 浏览器/人工走查，Step E 回写日期、环境、结果和证据 |
| `P` | planned，尚未执行 |
| `passed/failed/blocked` | Step E 的真实结果；没有证据不能标 passed |

每个自动化 case 必须断言业务结果和副作用，不能只断言 HTTP 2xx。真实 Provider 不替代假 Provider 契约测试；
3A 不要求收费 API 才能完成。工单引用用例 ID，工单关闭前其负责的 `A` 项必须有测试方法落点。

## 2. 3A-CFG — 配置、Prompt 与启动校验

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-CFG-01 | A | 默认值精确为 3 子问题、3 总意图、8/12 历史、65536 UTF-8 bytes、五个已确认阈值 | P |
| 3A-CFG-02 | A | `summaryStartTurns <= historyKeepTurns` 启动失败并指出完整配置路径 | P |
| 3A-CFG-03 | A | `maxTotalIntents < maxSubQuestions` 启动失败 | P |
| 3A-CFG-04 | A | 阈值非有限、越界或不满足 retain≤targeted≤fallback≤supplement 时启动失败 | P |
| 3A-CFG-05 | A | ambiguity margin ≤0 或 ≥0.5 启动失败 | P |
| 3A-CFG-06 | A | Prompt 文件缺失、空白、未知占位符或缺必需占位符时启动失败 | P |
| 3A-CFG-07 | A | Prompt 渲染只替换命名占位符，不执行 SpEL/include/脚本 | P |
| 3A-CFG-08 | A | 中文与 emoji 按 UTF-8 字节精确计数；65536 通过、65537 失败 | P |
| 3A-CFG-09 | A | 评分候选按 UUID 稳定排序，相同输入产生字节相同 Prompt | P |
| 3A-CFG-10 | A | `BALANCED` Chat 无 sampling profile 或 catalog tier 不匹配时启动失败 | P |
| 3A-CFG-11 | A | 3A 所有模型命令只带 BALANCED，业务对象不出现 temperature/topP | P |
| 3A-CFG-12 | A | 线程池大小/队列为有界值且线程名可诊断，不使用 common pool | P |

## 3. 3A-DB — Schema、Repository 与逻辑删除

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-DB-01 | A | 空库 Flyway 升级创建全部 `qa_*` 表、索引和 check，旧迁移仍可重复验证 | P |
| 3A-DB-02 | A | conversation/title/version/deleted 不变量由数据库拒绝非法直写 | P |
| 3A-DB-03 | A | 同一对话只能有一个 SAVED/PROCESSING turn，竞争时只有一条提交 | P |
| 3A-DB-04 | A | `(conversationId, clientRequestId)` 唯一，相同请求不生成第二条消息 | P |
| 3A-DB-05 | A | message sequence 在并发写下单调唯一，无 `max+1` 冲突 | P |
| 3A-DB-06 | A | 每个对话只有一个有效 summary；新 revision 安装后旧行逻辑删除 | P |
| 3A-DB-07 | A | summary 水位 UUID/sequence 与真实消息一致且只前进 | P |
| 3A-DB-08 | A | subquestion ordinal 只允许 0~2；match rank/score/target check 生效 | P |
| 3A-DB-09 | A | WAITING_CLARIFICATION 持久化 2~3 选项且无 action；ACTIONS_READY 反之 | P |
| 3A-DB-10 | A | 删除对话后所有业务查询隐藏所属数据，`ai_*` 诊断记录不被改写 | P |
| 3A-DB-11 | A | Repository 普通查询显式过滤 deleted，history 方法才可读取旧 revision | P |
| 3A-DB-12 | A | `qa_turn_stage` Schema 无正文/错误体列，duration 与 ordinal check 生效 | P |

## 4. 3A-CONV — 对话、本轮、幂等与恢复

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-CONV-01 | A | 首问原子创建对话、用户消息、SAVED turn，标题为确定性首问截取 | P |
| 3A-CONV-02 | A | 用户消息保存失败不调用 Chat，不返回“已发送” | P |
| 3A-CONV-03 | A | 相同 key + 相同正文重放原结果且模型调用次数不增加 | P |
| 3A-CONV-04 | A | 相同 key + 不同正文返回 409 `IDEMPOTENCY_KEY_REUSED` | P |
| 3A-CONV-05 | A | 活动 turn 存在时第二次不同提交返回 `CONVERSATION_BUSY` | P |
| 3A-CONV-06 | A | PROCESSING 终态 CAS 只有一个获胜，失败/成功回调不能互相覆盖 | P |
| 3A-CONV-07 | A | 启动恢复把过期 PROCESSING 标成 FAILED/PROCESS_INTERRUPTED，不重放模型 | P |
| 3A-CONV-08 | A | 终态落库失败不返回临时模型成功，turn 可见为稳定失败或明确 503 | P |
| 3A-CONV-09 | A | 重命名 expectedVersion 冲突保留旧标题；成功 version +1 | P |
| 3A-CONV-10 | A | 有活动 turn 删除对话返回冲突；无活动 turn 逻辑删除成功 | P |
| 3A-CONV-11 | A | 列表同时间戳用 UUID tie-break，翻页无重复/遗漏 | P |
| 3A-CONV-12 | A | 消息倒序取页、页内正序，刷新恢复稳定终态和 Inspector | P |

## 5. 3A-MEM — 历史快照、摘要与水位线

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-MEM-01 | A | 无摘要时加载最多最近 8 个完整轮次 + 当前用户消息 | P |
| 3A-MEM-02 | A | 有摘要时只加载水位线之后 8 轮，摘要与原文无重叠 | P |
| 3A-MEM-03 | A | 未完成/FAILED 半轮不计入可摘要完整轮次 | P |
| 3A-MEM-04 | A | 未解决的澄清引导与选项不进入摘要且保留在改写上下文 | P |
| 3A-MEM-05 | A | 未达 12 个可摘要轮次不调用摘要模型 | P |
| 3A-MEM-06 | A | 达 12 后只压缩最近 8 轮之前的完整轮次，水位推进到被压缩末消息 | P |
| 3A-MEM-07 | A | 新摘要以旧摘要 + 新滑出轮次生成，不把最近 8 轮重复输入为已压缩事实 | P |
| 3A-MEM-08 | A | 摘要模型失败/超时保留旧摘要并继续改写，记录 SUMMARY_UNAVAILABLE | P |
| 3A-MEM-09 | A | 两个摘要安装竞争只有一个 CAS 成功，失败者不清空已安装摘要 | P |
| 3A-MEM-10 | A | 近期历史加载失败按 PRD 使用摘要+当前问题并记录可见降级 | P |
| 3A-MEM-11 | A | 摘要 stage 只记录 logicalCallId/枚举/耗时，不记录摘要正文 | P |
| 3A-MEM-12 | A | 对话逻辑删除后摘要不可从普通 API 读取，历史行仍可审计 | P |

## 6. 3A-RW — 归一化、改写与拆分

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-RW-01 | A | NFKC、空白合并和 trim 确定性，空白结果拒绝 | P |
| 3A-RW-02 | A | 合法 JSON 保持 rewrittenQuestion 与子问题原顺序 | P |
| 3A-RW-03 | A | 单层 json/JSON Markdown code fence 可剥离 | P |
| 3A-RW-04 | A | 非 JSON、顶层错误、rewrite 空或字段类型错误退归一化单问题 | P |
| 3A-RW-05 | A | subQuestions 空时使用 rewrittenQuestion 作为唯一子问题 | P |
| 3A-RW-06 | A | 子问题 trim、去空、完全重复去重但不按分数重排 | P |
| 3A-RW-07 | A | 合法返回 1~3 个独立问题正常；4 个或更多不截断并进入显式拒绝 | P |
| 3A-RW-08 | A | 模型调用异常/超时退归一化问题，其他阶段继续 | P |
| 3A-RW-09 | A | fallback 也使用归一化结果，不回退未规范化原字符串 | P |
| 3A-RW-10 | A | Prompt 带摘要与近期历史但不带模型诊断、SYSTEM 内部消息或 KB 正文 | P |
| 3A-RW-11 | A | rewrite stage 关联一次 logicalCallId；fallback 原因稳定 | P |
| 3A-RW-12 | A | 可编程假模型试图补造限制时 golden case 能暴露输出，代码不将 reason 入库 | P |

## 7. 3A-TREE — 意图树 revision、约束与缓存

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-TREE-01 | A | 空树 head=0；首个 GROUP 创建 revision 1，新节点默认停用 | P |
| 3A-TREE-02 | A | node UUID 跨 revision 稳定；虚拟根不落表且不可改/删/停 | P |
| 3A-TREE-03 | A | 孤儿、环、自父、四层节点全部拒绝且不创建 revision | P |
| 3A-TREE-04 | A | KB/SYSTEM 有子节点、GROUP 有目标/snippet、目标类型错配均拒绝 | P |
| 3A-TREE-05 | A | 同父有效节点规范化名称重复拒绝，不同父同名允许且路径可区分 | P |
| 3A-TREE-06 | A | 启用叶子缺 description/example/目标、KB 不可用或祖先停用时整体拒绝 | P |
| 3A-TREE-07 | A | 停用 GROUP 必须 SUBTREE，提交后全部后代 disabled | P |
| 3A-TREE-08 | A | 启用 GROUP NODE_ONLY 不恢复后代；CONFIGURED_SUBTREE 遇坏节点整体失败 | P |
| 3A-TREE-09 | A | 删除子树在新 revision 标 deleted+disabled，旧 revision 完整不变 | P |
| 3A-TREE-10 | A | 两个命令同 expected revision 只有一个成功，失败者返回 current revision | P |
| 3A-TREE-11 | A | 普通字段保存不能改 parent/type/enabled；换父/角色请求拒绝 | P |
| 3A-TREE-12 | A | examples 数量、单项长度、去重及 description/snippet 上限一致 | P |
| 3A-TREE-13 | A | SYSTEM action 只接受闭集；3A 不接受 MCP 类型 | P |
| 3A-TREE-14 | A | 成功提交后缓存原子替换；替换故障清缓存并从新 head 重载 | P |
| 3A-TREE-15 | A | 在途请求持旧 snapshot，新请求完整持新 snapshot，不出现混合节点 | P |
| 3A-TREE-16 | A | 影响预览与同 revision 下实际子树命令计数一致；过期预览命令冲突 | P |

## 8. 3A-INT — 评分解析、并行与封顶

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-INT-01 | A | Prompt 只含同 revision 全部启用 KB/SYSTEM 叶子所允许字段 | P |
| 3A-INT-02 | A | 已删/停用/GROUP/KB 正文/promptSnippet 不进入评分 Prompt | P |
| 3A-INT-03 | A | 每子问题恰好一次评分调用，三个子问题共享同一树 snapshot | P |
| 3A-INT-04 | A | 三个慢假调用在专用池并行，总延迟接近 max 而非 sum | P |
| 3A-INT-05 | A | 线程池饱和走 caller-runs 不丢任务；中断恢复标志并失败 | P |
| 3A-INT-06 | A | 合法数组和 code fence 解析成功；非数组/非法 JSON 降级空候选 | P |
| 3A-INT-07 | A | 未知、停用、非叶子 ID 丢弃 | P |
| 3A-INT-08 | A | NaN/Infinity/字符串/越界分数丢弃，0 与 1 边界合法 | P |
| 3A-INT-09 | A | 同一 node 重复取最高合法分，稳定 tie-break 使用 nodeId | P |
| 3A-INT-10 | A | score<0.35 丢弃，等于 0.35 保留；单子问题最多 3 | P |
| 3A-INT-11 | A | 总候选≤3 原样按子问题结构重建，不丢空子问题位置 | P |
| 3A-INT-12 | A | 8 候选封顶先给每个有命中的子问题 1 个，再全局按分数补 | P |
| 3A-INT-13 | A | 同分输入多次运行得到相同 3 个节点和顺序 | P |
| 3A-INT-14 | A | 一个子问题调用失败只让该问题空候选，其他问题结果保留 | P |
| 3A-INT-15 | A | 全部评分失败形成可察觉 NO_MATCH，不伪造成成功定向 | P |
| 3A-INT-16 | A | 评分 Prompt 65537 bytes 整轮显式失败且零评分调用/零节点截断 | P |

## 9. 3A-AMB — 歧义与选择闭环

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-AMB-01 | A | 多子问题不做跨问题歧义，分别产动作 | P |
| 3A-AMB-02 | A | SYSTEM 或同品类 KB 候选不触发歧义 | P |
| 3A-AMB-03 | A | 最高分<0.6 即使 gap 很小也不展示低质量精确选项 | P |
| 3A-AMB-04 | A | 两层 KB 叶子以自身为品类；三层以直接 GROUP 父为品类 | P |
| 3A-AMB-05 | A | gap=0.15 直接引导；gap=0.150...1 进入灰区 | P |
| 3A-AMB-06 | A | 0.15<gap≤0.30 调一次灰区确认；>0.30 不调用 | P |
| 3A-AMB-07 | A | 灰区模型确认失败/非法输出保守触发引导 | P |
| 3A-AMB-08 | A | 引导最多三个品类代表，路径可读、固定文案且不调用检索/生成 | P |
| 3A-AMB-09 | A | 结构化选择必须属于同对话来源 turn；伪造/过期 node 拒绝 | P |
| 3A-AMB-10 | A | “都不是这些”生成补充方向消息；有效选择进入下一 turn 且不重复同一歧义 | P |

## 10. 3A-ACT — 声明式动作映射

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-ACT-01 | A | KB score=0.4 产生定向动作，0.399... 不产生 | P |
| 3A-ACT-02 | A | 无候选产生 GLOBAL_FALLBACK/NO_MATCH 并区分稳定原因 | P |
| 3A-ACT-03 | A | 最高 KB score<0.6 补全局动作；=0.6 不因该条件补 | P |
| 3A-ACT-04 | A | 单 KB 候选 score<0.8 补全局；=0.8 不补 | P |
| 3A-ACT-05 | A | 多 KB 候选不误套单意图补充规则 | P |
| 3A-ACT-06 | A | 全部子问题只有同一 SYSTEM 主候选才形成 SYSTEM 动作 | P |
| 3A-ACT-07 | A | SYSTEM+KB 复合问题不短路吞掉 KB 子问题 | P |
| 3A-ACT-08 | A | 3A 只写 action，无 Embedding/pgvector/Rerank/答案生成调用 | P |

## 11. 3A-API — HTTP 契约与隐私

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-API-01 | A | 未知 JSON 字段、错误 Content-Type、非法 UUID/枚举统一 ProblemDetail | P |
| 3A-API-02 | A | 新 turn 返回 201，幂等重放 200，业务稳定拒绝/失败仍可刷新读取 | P |
| 3A-API-03 | A | 404/409/422/503 的 code、title 与扩展字段符合契约 | P |
| 3A-API-04 | A | conversation/messages cursor 在边界值、空页、非法 cursor 下行为稳定 | P |
| 3A-API-05 | A | deleted conversation/turn 跨归属访问均为 404 | P |
| 3A-API-06 | A | Inspector 只返回批准字段，不含摘要全文、reason、logicalCallId、ExecutionInfo | P |
| 3A-API-07 | A | intent-tree 返回虚拟根、完整当前 revision、effectiveEnabled 和计数 | P |
| 3A-API-08 | A | 所有树命令缺 expectedRevision 均拒绝，冲突不产生新 revision | P |
| 3A-API-09 | A | create 强制 disabled；PUT 不能换父/type；delete 使用命令端点 | P |
| 3A-API-10 | A | bindable KB 只返回可用知识库，不暴露存储表/Collection | P |
| 3A-API-11 | A | 标记串扫描全部 `/api/qa` 响应不含 Prompt、secret、原始错误体和内部字段 | P |
| 3A-API-12 | A | API 日志捕获不含用户问题/摘要/模型输出，只有 ID、枚举和脱敏 requestId | P |

## 12. 3A-UI — 双界面手工验收

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-UI-01 | M | 问答 A：首问自动建对话，刷新恢复消息与 ACTIONS_READY Inspector | P |
| 3A-UI-02 | M | 问答 A：改写 fallback、摘要未使用、无匹配状态不伪装成答案 | P |
| 3A-UI-03 | M | 问答 A：4+ 主题提示拆分，不展示被截断结果 | P |
| 3A-UI-04 | M | 问答 A：2~3 个歧义单选 + 都不是，选择后闭环 | P |
| 3A-UI-05 | M | 问答 A：活动 turn 防重复提交，保存失败明确可重试 | P |
| 3A-UI-06 | M | 问答 A：重命名、逻辑删除二次确认、切换不丢已保存消息 | P |
| 3A-UI-07 | M | 问答 A：1440/720/375px Inspector 收起/抽屉与键盘焦点可用 | P |
| 3A-UI-08 | M | 问答 A：3A 页面无伪来源、伪答案、停止/排队控件 | P |
| 3A-UI-09 | M | 意图树 C：画布选择节点、检查器编辑 KB/SYSTEM/GROUP | P |
| 3A-UI-10 | M | 意图树 C：创建默认停用，完整后显式启用 | P |
| 3A-UI-11 | M | 意图树 C：停用/删除父节点先预览并处理整个子树 | P |
| 3A-UI-12 | M | 意图树 C：启用 NODE_ONLY 与 CONFIGURED_SUBTREE 语义可辨 | P |
| 3A-UI-13 | M | 意图树 C：revision 冲突保留表单，刷新后可比较重试 | P |
| 3A-UI-14 | M | 意图树 C：同名节点搜索保留完整路径，失效 KB 显示阻塞 | P |
| 3A-UI-15 | M | 意图树 C：大树缩放/平移不阻塞键盘与 375px 列表→详情核心路径 | P |
| 3A-UI-16 | M | 两页颜色非唯一状态、弹层焦点约束、危险按钮非默认焦点 | P |

## 13. 3A-E2E — 垂直链路与回归

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-E2E-01 | A | 连续追问加载历史→改写独立问题→固定 revision→定向动作全链路 | P |
| 3A-E2E-02 | A | 复合问题三路并行评分→多样性封顶→每问题动作不丢 | P |
| 3A-E2E-03 | A | 摘要失败 + 改写失败 + 单问题评分失败组合仍产可解释稳定结果 | P |
| 3A-E2E-04 | A | 运营更新树与用户提问竞争，各 turn 完整使用一个 revision | P |
| 3A-E2E-05 | A | 歧义短路→刷新→选择→第二轮动作，消息与关联均跨重启恢复 | P |
| 3A-E2E-06 | A | 删除对话不删除 AI 诊断；诊断故障不改变问答业务终态 | P |
| 3A-E2E-07 | A | 现有知识库/模型调度后端测试不因 V7/新 Spring Bean 回归 | P |
| 3A-E2E-08 | A | `scripts/ci-validate.sh` 无收费 Key 全绿，测试无网络依赖 | P |

## 14. Step E 回写模板

执行时在对应状态写 `passed (YYYY-MM-DD, 证据)`；失败写实际错误与 issue，不得改期望绕过。手工项至少记录：

```text
环境：commit / 浏览器 / viewport / 后端配置
步骤：实际点击或请求序列
结果：passed | failed | blocked
证据：截图、测试报告或精确日志位置（不含秘密/正文）
偏离：与规格不一致之处及裁决
```
