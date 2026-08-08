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
| 3A-CFG-01 | A | 默认值精确为 3 子问题、3 总意图、8/12 历史、65536 UTF-8 bytes、五个已确认阈值 | passed (2026-08-08, verify 508) |
| 3A-CFG-02 | A | `summaryStartTurns <= historyKeepTurns` 启动失败并指出完整配置路径 | passed (2026-08-08, verify 508) |
| 3A-CFG-03 | A | `maxTotalIntents < maxSubQuestions` 启动失败 | passed (2026-08-08, verify 508) |
| 3A-CFG-04 | A | 阈值非有限、越界或不满足 retain≤targeted≤fallback≤supplement 时启动失败 | passed (2026-08-08, verify 508) |
| 3A-CFG-05 | A | ambiguity margin ≤0 或 ≥0.5 启动失败 | passed (2026-08-08, verify 508) |
| 3A-CFG-06 | A | Prompt 文件缺失、空白、未知占位符或缺必需占位符时启动失败 | passed (2026-08-08, verify 508) |
| 3A-CFG-07 | A | Prompt 渲染只替换命名占位符，不执行 SpEL/include/脚本 | passed (2026-08-08, verify 508) |
| 3A-CFG-08 | A | 中文与 emoji 按 UTF-8 字节精确计数；65536 通过、65537 失败 | passed (2026-08-08, verify 508) |
| 3A-CFG-09 | A | 评分候选按 UUID 稳定排序，相同输入产生字节相同 Prompt | passed (2026-08-08, verify 508) |
| 3A-CFG-10 | A | `BALANCED` Chat 无 sampling profile 或 catalog tier 不匹配时启动失败 | passed (2026-08-08, verify 508) |
| 3A-CFG-11 | A | 3A 所有模型命令只带 BALANCED，业务对象不出现 temperature/topP | passed (2026-08-08, verify 508) |
| 3A-CFG-12 | A | 线程池大小/队列为有界值且线程名可诊断，不使用 common pool | passed (2026-08-08, verify 508) |

## 3. 3A-DB — Schema、Repository 与逻辑删除

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-DB-01 | A | 空库 Flyway 升级创建全部 `qa_*` 表、索引和 check，旧迁移仍可重复验证 | passed (2026-08-08, verify 508) |
| 3A-DB-02 | A | conversation/title/version/deleted 不变量由数据库拒绝非法直写 | passed (2026-08-08, verify 508) |
| 3A-DB-03 | A | 同一对话只能有一个 SAVED/PROCESSING turn，竞争时只有一条提交 | passed (2026-08-08, verify 508) |
| 3A-DB-04 | A | `(conversationId, clientRequestId)` 唯一，相同请求不生成第二条消息 | passed (2026-08-08, verify 508) |
| 3A-DB-05 | A | message sequence 在并发写下单调唯一，无 `max+1` 冲突 | passed (2026-08-08, verify 508) |
| 3A-DB-06 | A | 每个对话只有一个有效 summary；新 revision 安装后旧行逻辑删除 | passed (2026-08-08, verify 508) |
| 3A-DB-07 | A | summary 水位 UUID/sequence 与真实消息一致且只前进 | passed (2026-08-08, verify 508) |
| 3A-DB-08 | A | subquestion ordinal 只允许 0~2；match rank/score/target check 生效 | passed (2026-08-08, verify 508) |
| 3A-DB-09 | A | WAITING_CLARIFICATION 持久化 2~3 选项且无 action；ACTIONS_READY 反之 | passed (2026-08-08, verify 508) |
| 3A-DB-10 | A | 删除对话后所有业务查询隐藏所属数据，`ai_*` 诊断记录不被改写 | passed (2026-08-08, verify 508) |
| 3A-DB-11 | A | Repository 普通查询显式过滤 deleted，history 方法才可读取旧 revision | passed (2026-08-08, verify 508) |
| 3A-DB-12 | A | `qa_turn_stage` Schema 无正文/错误体列，duration 与 ordinal check 生效 | passed (2026-08-08, verify 508) |

## 4. 3A-CONV — 对话、本轮、幂等与恢复

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-CONV-01 | A | 首问原子创建对话、用户消息、SAVED turn，标题为确定性首问截取 | passed (2026-08-08, verify 508) |
| 3A-CONV-02 | A | 用户消息保存失败不调用 Chat，不返回“已发送” | passed (2026-08-08, verify 508) |
| 3A-CONV-03 | A | 相同 key + 相同正文重放原结果且模型调用次数不增加 | passed (2026-08-08, verify 508) |
| 3A-CONV-04 | A | 相同 key + 不同正文返回 409 `IDEMPOTENCY_KEY_REUSED` | passed (2026-08-08, verify 508) |
| 3A-CONV-05 | A | 活动 turn 存在时第二次不同提交返回 `CONVERSATION_BUSY` | passed (2026-08-08, verify 508) |
| 3A-CONV-06 | A | PROCESSING 终态 CAS 只有一个获胜，失败/成功回调不能互相覆盖 | passed (2026-08-08, verify 508) |
| 3A-CONV-07 | A | 启动恢复把过期 PROCESSING 标成 FAILED/PROCESS_INTERRUPTED，不重放模型 | passed (2026-08-08, verify 508) |
| 3A-CONV-08 | A | 终态落库失败不返回临时模型成功，turn 可见为稳定失败或明确 503 | passed (2026-08-08, verify 508) |
| 3A-CONV-09 | A | 重命名 expectedVersion 冲突保留旧标题；成功 version +1 | passed (2026-08-08, verify 508) |
| 3A-CONV-10 | A | 有活动 turn 删除对话返回冲突；无活动 turn 逻辑删除成功 | passed (2026-08-08, verify 508) |
| 3A-CONV-11 | A | 列表同时间戳用 UUID tie-break，翻页无重复/遗漏 | passed (2026-08-08, verify 508) |
| 3A-CONV-12 | A | 消息倒序取页、页内正序，刷新恢复稳定终态和 Inspector | passed (2026-08-08, verify 508) |

## 5. 3A-MEM — 历史快照、摘要与水位线

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-MEM-01 | A | 无摘要时加载最多最近 8 个完整轮次 + 当前用户消息 | passed (2026-08-08, verify 508) |
| 3A-MEM-02 | A | 有摘要时只加载水位线之后 8 轮，摘要与原文无重叠 | passed (2026-08-08, verify 508) |
| 3A-MEM-03 | A | 未完成/FAILED 半轮不计入可摘要完整轮次 | passed (2026-08-08, verify 508) |
| 3A-MEM-04 | A | 未解决的澄清引导与选项不进入摘要且保留在改写上下文 | passed (2026-08-08, verify 508) |
| 3A-MEM-05 | A | 未达 12 个可摘要轮次不调用摘要模型 | passed (2026-08-08, verify 508) |
| 3A-MEM-06 | A | 达 12 后只压缩最近 8 轮之前的完整轮次，水位推进到被压缩末消息 | passed (2026-08-08, verify 508) |
| 3A-MEM-07 | A | 新摘要以旧摘要 + 新滑出轮次生成，不把最近 8 轮重复输入为已压缩事实 | passed (2026-08-08, verify 508) |
| 3A-MEM-08 | A | 摘要模型失败/超时保留旧摘要并继续改写，记录 SUMMARY_UNAVAILABLE | passed (2026-08-08, verify 508) |
| 3A-MEM-09 | A | 两个摘要安装竞争只有一个 CAS 成功，失败者不清空已安装摘要 | passed (2026-08-08, verify 508) |
| 3A-MEM-10 | A | 近期历史加载失败按 PRD 使用摘要+当前问题并记录可见降级 | passed (2026-08-08, verify 508) |
| 3A-MEM-11 | A | 摘要 stage 只记录 logicalCallId/枚举/耗时，不记录摘要正文 | passed (2026-08-08, verify 508) |
| 3A-MEM-12 | A | 对话逻辑删除后摘要不可从普通 API 读取，历史行仍可审计 | passed (2026-08-08, verify 508) |

## 6. 3A-RW — 归一化、改写与拆分

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-RW-01 | A | NFKC、空白合并和 trim 确定性，空白结果拒绝 | passed (2026-08-08, verify 508) |
| 3A-RW-02 | A | 合法 JSON 保持 rewrittenQuestion 与子问题原顺序 | passed (2026-08-08, verify 508) |
| 3A-RW-03 | A | 单层 json/JSON Markdown code fence 可剥离 | passed (2026-08-08, verify 508) |
| 3A-RW-04 | A | 非 JSON、顶层错误、rewrite 空或字段类型错误退归一化单问题 | passed (2026-08-08, verify 508) |
| 3A-RW-05 | A | subQuestions 空时使用 rewrittenQuestion 作为唯一子问题 | passed (2026-08-08, verify 508) |
| 3A-RW-06 | A | 子问题 trim、去空、完全重复去重但不按分数重排 | passed (2026-08-08, verify 508) |
| 3A-RW-07 | A | 合法返回 1~3 个独立问题正常；4 个或更多不截断并进入显式拒绝 | passed (2026-08-08, verify 508) |
| 3A-RW-08 | A | 模型调用异常/超时退归一化问题，其他阶段继续 | passed (2026-08-08, verify 508) |
| 3A-RW-09 | A | fallback 也使用归一化结果，不回退未规范化原字符串 | passed (2026-08-08, verify 508) |
| 3A-RW-10 | A | Prompt 带摘要与近期历史但不带模型诊断、SYSTEM 内部消息或 KB 正文 | passed (2026-08-08, verify 508) |
| 3A-RW-11 | A | rewrite stage 关联一次 logicalCallId；fallback 原因稳定 | passed (2026-08-08, verify 508) |
| 3A-RW-12 | A | 可编程假模型试图补造限制时 golden case 能暴露输出，代码不将 reason 入库 | passed (2026-08-08, verify 508) |

## 7. 3A-TREE — 意图树 revision、约束与缓存

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-TREE-01 | A | 空树 head=0；首个 GROUP 创建 revision 1，新节点默认停用 | passed (2026-08-08, verify 508) |
| 3A-TREE-02 | A | node UUID 跨 revision 稳定；虚拟根不落表且不可改/删/停 | passed (2026-08-08, verify 508) |
| 3A-TREE-03 | A | 孤儿、环、自父、四层节点全部拒绝且不创建 revision | passed (2026-08-08, verify 508) |
| 3A-TREE-04 | A | KB/SYSTEM 有子节点、GROUP 有目标/snippet、目标类型错配均拒绝 | passed (2026-08-08, verify 508) |
| 3A-TREE-05 | A | 同父有效节点规范化名称重复拒绝，不同父同名允许且路径可区分 | passed (2026-08-08, verify 508) |
| 3A-TREE-06 | A | 启用叶子缺 description/example/目标、KB 不可用或祖先停用时整体拒绝 | passed (2026-08-08, verify 508) |
| 3A-TREE-07 | A | 停用 GROUP 必须 SUBTREE，提交后全部后代 disabled | passed (2026-08-08, verify 508) |
| 3A-TREE-08 | A | 启用 GROUP NODE_ONLY 不恢复后代；CONFIGURED_SUBTREE 遇坏节点整体失败 | passed (2026-08-08, verify 508) |
| 3A-TREE-09 | A | 删除子树在新 revision 标 deleted+disabled，旧 revision 完整不变 | passed (2026-08-08, verify 508) |
| 3A-TREE-10 | A | 两个命令同 expected revision 只有一个成功，失败者返回 current revision | passed (2026-08-08, verify 508) |
| 3A-TREE-11 | A | 普通字段保存不能改 parent/type/enabled；换父/角色请求拒绝 | passed (2026-08-08, verify 508) |
| 3A-TREE-12 | A | examples 数量、单项长度、去重及 description/snippet 上限一致 | passed (2026-08-08, verify 508) |
| 3A-TREE-13 | A | SYSTEM action 只接受闭集；3A 不接受 MCP 类型 | passed (2026-08-08, verify 508) |
| 3A-TREE-14 | A | 成功提交后缓存原子替换；替换故障清缓存并从新 head 重载 | passed (2026-08-08, verify 508) |
| 3A-TREE-15 | A | 在途请求持旧 snapshot，新请求完整持新 snapshot，不出现混合节点 | passed (2026-08-08, verify 508) |
| 3A-TREE-16 | A | 影响预览与同 revision 下实际子树命令计数一致；过期预览命令冲突 | passed (2026-08-08, verify 508) |

## 8. 3A-INT — 评分解析、并行与封顶

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-INT-01 | A | Prompt 只含同 revision 全部启用 KB/SYSTEM 叶子所允许字段 | passed (2026-08-08, verify 508) |
| 3A-INT-02 | A | 已删/停用/GROUP/KB 正文/promptSnippet 不进入评分 Prompt | passed (2026-08-08, verify 508) |
| 3A-INT-03 | A | 每子问题恰好一次评分调用，三个子问题共享同一树 snapshot | passed (2026-08-08, verify 508) |
| 3A-INT-04 | A | 三个慢假调用在专用池并行，总延迟接近 max 而非 sum | passed (2026-08-08, verify 508) |
| 3A-INT-05 | A | 线程池饱和走 caller-runs 不丢任务；中断恢复标志并失败 | passed (2026-08-08, verify 508) |
| 3A-INT-06 | A | 合法数组和 code fence 解析成功；非数组/非法 JSON 降级空候选 | passed (2026-08-08, verify 508) |
| 3A-INT-07 | A | 未知、停用、非叶子 ID 丢弃 | passed (2026-08-08, verify 508) |
| 3A-INT-08 | A | NaN/Infinity/字符串/越界分数丢弃，0 与 1 边界合法 | passed (2026-08-08, verify 508) |
| 3A-INT-09 | A | 同一 node 重复取最高合法分，稳定 tie-break 使用 nodeId | passed (2026-08-08, verify 508) |
| 3A-INT-10 | A | score<0.35 丢弃，等于 0.35 保留；单子问题最多 3 | passed (2026-08-08, verify 508) |
| 3A-INT-11 | A | 总候选≤3 原样按子问题结构重建，不丢空子问题位置 | passed (2026-08-08, verify 508) |
| 3A-INT-12 | A | 8 候选封顶先给每个有命中的子问题 1 个，再全局按分数补 | passed (2026-08-08, verify 508) |
| 3A-INT-13 | A | 同分输入多次运行得到相同 3 个节点和顺序 | passed (2026-08-08, verify 508) |
| 3A-INT-14 | A | 一个子问题调用失败只让该问题空候选，其他问题结果保留 | passed (2026-08-08, verify 508) |
| 3A-INT-15 | A | 全部评分失败形成可察觉 NO_MATCH，不伪造成成功定向 | passed (2026-08-08, verify 508) |
| 3A-INT-16 | A | 评分 Prompt 65537 bytes 整轮显式失败且零评分调用/零节点截断 | passed (2026-08-08, verify 508) |

## 9. 3A-AMB — 歧义与选择闭环

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-AMB-01 | A | 多子问题不做跨问题歧义，分别产动作 | passed (2026-08-08, verify 508) |
| 3A-AMB-02 | A | SYSTEM 或同品类 KB 候选不触发歧义 | passed (2026-08-08, verify 508) |
| 3A-AMB-03 | A | 最高分<0.6 即使 gap 很小也不展示低质量精确选项 | passed (2026-08-08, verify 508) |
| 3A-AMB-04 | A | 两层 KB 叶子以自身为品类；三层以直接 GROUP 父为品类 | passed (2026-08-08, verify 508) |
| 3A-AMB-05 | A | gap=0.15 直接引导；gap=0.150...1 进入灰区 | passed (2026-08-08, verify 508) |
| 3A-AMB-06 | A | 0.15<gap≤0.30 调一次灰区确认；>0.30 不调用 | passed (2026-08-08, verify 508) |
| 3A-AMB-07 | A | 灰区模型确认失败/非法输出保守触发引导 | passed (2026-08-08, verify 508) |
| 3A-AMB-08 | A | 引导最多三个品类代表，路径可读、固定文案且不调用检索/生成 | passed (2026-08-08, verify 508) |
| 3A-AMB-09 | A | 结构化选择必须属于同对话来源 turn；伪造/过期 node 拒绝 | passed (2026-08-08, verify 508) |
| 3A-AMB-10 | A | “都不是这些”生成补充方向消息；有效选择进入下一 turn 且不重复同一歧义 | passed (2026-08-08, verify 508) |

## 10. 3A-ACT — 声明式动作映射

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-ACT-01 | A | KB score=0.4 产生定向动作，0.399... 不产生 | passed (2026-08-08, verify 508) |
| 3A-ACT-02 | A | 无候选产生 GLOBAL_FALLBACK/NO_MATCH 并区分稳定原因 | passed (2026-08-08, verify 508) |
| 3A-ACT-03 | A | 最高 KB score<0.6 补全局动作；=0.6 不因该条件补 | passed (2026-08-08, verify 508) |
| 3A-ACT-04 | A | 单 KB 候选 score<0.8 补全局；=0.8 不补 | passed (2026-08-08, verify 508) |
| 3A-ACT-05 | A | 多 KB 候选不误套单意图补充规则 | passed (2026-08-08, verify 508) |
| 3A-ACT-06 | A | 全部子问题只有同一 SYSTEM 主候选才形成 SYSTEM 动作 | passed (2026-08-08, verify 508) |
| 3A-ACT-07 | A | SYSTEM+KB 复合问题不短路吞掉 KB 子问题 | passed (2026-08-08, verify 508) |
| 3A-ACT-08 | A | 3A 只写 action，无 Embedding/pgvector/Rerank/答案生成调用 | passed (2026-08-08, verify 508) |

## 11. 3A-API — HTTP 契约与隐私

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-API-01 | A | 未知 JSON 字段、错误 Content-Type、非法 UUID/枚举统一 ProblemDetail | passed (2026-08-08, verify 508) |
| 3A-API-02 | A | 新 turn 返回 201，幂等重放 200，业务稳定拒绝/失败仍可刷新读取 | passed (2026-08-08, verify 508) |
| 3A-API-03 | A | 404/409/422/503 的 code、title 与扩展字段符合契约 | passed (2026-08-08, verify 508) |
| 3A-API-04 | A | conversation/messages cursor 在边界值、空页、非法 cursor 下行为稳定 | passed (2026-08-08, verify 508) |
| 3A-API-05 | A | deleted conversation/turn 跨归属访问均为 404 | passed (2026-08-08, verify 508) |
| 3A-API-06 | A | Inspector 只返回批准字段，不含摘要全文、reason、logicalCallId、ExecutionInfo | passed (2026-08-08, verify 508) |
| 3A-API-07 | A | intent-tree 返回虚拟根、完整当前 revision、effectiveEnabled 和计数 | passed (2026-08-08, verify 508) |
| 3A-API-08 | A | 所有树命令缺 expectedRevision 均拒绝，冲突不产生新 revision | passed (2026-08-08, verify 508) |
| 3A-API-09 | A | create 强制 disabled；PUT 不能换父/type；delete 使用命令端点 | passed (2026-08-08, verify 508) |
| 3A-API-10 | A | bindable KB 只返回可用知识库，不暴露存储表/Collection | passed (2026-08-08, verify 508) |
| 3A-API-11 | A | 标记串扫描全部 `/api/qa` 响应不含 Prompt、secret、原始错误体和内部字段 | passed (2026-08-08, verify 508) |
| 3A-API-12 | A | API 日志捕获不含用户问题/摘要/模型输出，只有 ID、枚举和脱敏 requestId | passed (2026-08-08, verify 508) |

## 12. 3A-UI — 双界面手工验收

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-UI-01 | M | 问答 A：首问自动建对话，刷新恢复消息与 ACTIONS_READY Inspector | passed (2026-08-08, 所有者走查) |
| 3A-UI-02 | M | 问答 A：改写 fallback、摘要未使用、无匹配状态不伪装成答案 | passed (2026-08-08, 所有者走查) |
| 3A-UI-03 | M | 问答 A：4+ 主题提示拆分，不展示被截断结果 | passed (2026-08-08, 所有者走查) |
| 3A-UI-04 | M | 问答 A：2~3 个歧义单选 + 都不是，选择后闭环 | passed (2026-08-08, 所有者走查) |
| 3A-UI-05 | M | 问答 A：活动 turn 防重复提交，保存失败明确可重试 | passed (2026-08-08, 所有者走查) |
| 3A-UI-06 | M | 问答 A：重命名、逻辑删除二次确认、切换不丢已保存消息 | passed (2026-08-08, 所有者走查) |
| 3A-UI-07 | M | ~~问答 A：1440/720/375px Inspector 收起/抽屉与键盘焦点可用~~ | **invalidated (2026-08-08)**：问答页改判为 C — Focused Chat，本条验的是三栏收起行为，已不存在。按 C 重走的替代项是 `3B-UI-07`，见 §12.1 |
| 3A-UI-08 | M | 问答 A：3A 页面无伪来源、伪答案、停止/排队控件 | passed (2026-08-08, 所有者走查) |
| 3A-UI-09 | M | 意图树 C：画布选择节点、检查器编辑 KB/SYSTEM/GROUP | passed (2026-08-08, 所有者走查) |
| 3A-UI-10 | M | 意图树 C：创建默认停用，完整后显式启用 | passed (2026-08-08, 所有者走查) |
| 3A-UI-11 | M | 意图树 C：停用/删除父节点先预览并处理整个子树 | passed (2026-08-08, 所有者走查) |
| 3A-UI-12 | M | 意图树 C：启用 NODE_ONLY 与 CONFIGURED_SUBTREE 语义可辨 | passed (2026-08-08, 所有者走查) |
| 3A-UI-13 | M | 意图树 C：revision 冲突保留表单，刷新后可比较重试 | passed (2026-08-08, 所有者走查) |
| 3A-UI-14 | M | 意图树 C：同名节点搜索保留完整路径，失效 KB 显示阻塞 | passed (2026-08-08, 所有者走查) |
| 3A-UI-15 | M | 意图树 C：大树缩放/平移不阻塞键盘与 375px 列表→详情核心路径 | passed (2026-08-08, 所有者走查) |
| 3A-UI-16 | M | 两页颜色非唯一状态、弹层焦点约束、危险按钮非默认焦点 | passed (2026-08-08, 所有者走查) |

### 12.1 布局改判对 3A-UI 证据的影响（2026-08-08）

问答页从 A — Conversation + Inspector 改判为 C — Focused Chat（[PRD §11.1](../../docs/prd/2026-08-07-qa-pipeline-prd.md)）。
逐条核对结果，**不做整批作废，也不把作废的说成仍然有效**：

| 条目 | 结论 |
|---|---|
| `3A-UI-07` | **invalidated**。它断言的是三栏下 Inspector 的收起与抽屉降级，C 没有常驻栏 |
| `3A-UI-01~06`、`3A-UI-08` | **仍然有效**。断言的是建对话与刷新恢复、降级不伪装成答案、拆分提示、歧义闭环、防重复提交、重命名与逻辑删除、无伪造控件，都不依赖分栏方式 |
| `3A-UI-09~15` | **不受影响**。本次只改问答页，意图树仍是 C — Route Canvas |
| `3A-UI-16` | **仍然有效**，但 3B 的工具确认卡是新增的危险确认点，需在 3B 分区补一条 |
| 130 条 `A` 自动化用例 | **不受影响**，无一条断言布局 |

替代与新增项（`3B-UI-*`）在 3B Step C 建分区时一并写入，本次不预先编号占位。

`3A-UI-07` 作废的那部分能力，前端组件测试已接住一半：`ChatPage.test.tsx` 用一条 `it.each`
在 1440/1024/375 三档跑同一套断言——**没有常驻面板、两个抽屉都能开关、`aria-expanded` 跟随**。
它替代不了手工走查（真实浏览器的焦点、软键盘遮挡、滚动都验不到），但能挡住"某一档又冒出常驻栏"
这类回归，不必等到 Step E 才发现。

## 13. 3A-E2E — 垂直链路与回归

| ID | 类型 | 场景 / 断言 | 状态 |
|---|:---:|---|---|
| 3A-E2E-01 | A | 连续追问加载历史→改写独立问题→固定 revision→定向动作全链路 | passed (2026-08-08, verify 508) |
| 3A-E2E-02 | A | 复合问题三路并行评分→多样性封顶→每问题动作不丢 | passed (2026-08-08, verify 508) |
| 3A-E2E-03 | A | 摘要失败 + 改写失败 + 单问题评分失败组合仍产可解释稳定结果 | passed (2026-08-08, verify 508) |
| 3A-E2E-04 | A | 运营更新树与用户提问竞争，各 turn 完整使用一个 revision | passed (2026-08-08, verify 508) |
| 3A-E2E-05 | A | 歧义短路→刷新→选择→第二轮动作，消息与关联均跨重启恢复 | passed (2026-08-08, verify 508) |
| 3A-E2E-06 | A | 删除对话不删除 AI 诊断；诊断故障不改变问答业务终态 | passed (2026-08-08, verify 508) |
| 3A-E2E-07 | A | 现有知识库/模型调度后端测试不因 V7/新 Spring Bean 回归 | passed (2026-08-08, verify 508) |
| 3A-E2E-08 | A | `scripts/ci-validate.sh` 无收费 Key 全绿，测试无网络依赖 | passed (2026-08-08, ci-validate.sh 全链路；后端 508 项 0 失败，无收费 Key，5 项真实 API 用例自动跳过) |

## 14. Step E 回写模板

执行时在对应状态写 `passed (YYYY-MM-DD, 证据)`；失败写实际错误与 issue，不得改期望绕过。手工项至少记录：

```text
环境：commit / 浏览器 / viewport / 后端配置
步骤：实际点击或请求序列
结果：passed | failed | blocked
证据：截图、测试报告或精确日志位置（不含秘密/正文）
偏离：与规格不一致之处及裁决
```

## 15. Step E 执行记录（3A 自动化部分，2026-08-08）

### 15.1 环境

- commit：`c400147` 之后的工作树（3A 全部提交已落盘）
- JDK 17、Postgres + Redis + RustFS 经 `docker compose` 拉起
- **`DASHSCOPE_API_KEY` 与 `EMBEDDING_API_KEY` 均未设置**：5 项真实 API 用例自动跳过，
  全程零收费调用

### 15.2 结果

| 范围 | 结果 |
|---|---|
| 后端 `./mvnw -B verify` | 508 项，0 失败 0 错误，5 跳过 |
| 前端 `pnpm build` / `lint` / `test` | 51 项通过，构建与 lint 无告警 |
| `check-doc-links` / `check-deploy-contract` | 通过 |
| 130 条 `A` 项 | 全部有测试方法落点并随上述执行通过 |
| 16 条 `M` 项 | 项目所有者走查，16 条全部 passed（见 15.6） |

### 15.3 落点核对方式

112 条由方法名前缀机械对上（`db01_`、`conv09到12_`、`int11到14_` 这类命名本身就是落点声明）。
其余 18 条逐条人工核对，其中 `3A-CFG` 全组用中文方法名、无 ID 前缀，映射如下：

| 用例 | 落点 |
|---|---|
| CFG-01 | `QaUnderstandingConfigurationTests.默认配置与已批准的3A边界完全一致` |
| CFG-02 | 同类 `摘要水位配置矛盾会阻止启动并指出完整路径` |
| CFG-03 | 同类 `总意图少于子问题会阻止启动` |
| CFG-04 | 同类 `阈值乱序会阻止启动` |
| CFG-05 | 同类 `非法歧义差值会阻止启动` |
| CFG-06 | `PromptTemplateCatalogTests.缺文件空文件未知占位符和缺必需占位符都会被拒绝` |
| CFG-07 | 同类 `渲染只替换命名占位符而不解释表达式或include` |
| CFG-08 | 同类 `意图评分预算按UTF8字节在65536边界拒绝` |
| CFG-09 | `IntentCatalogEncoderTests.候选按UUID稳定排序且相同集合产生字节相同JSON` |
| CFG-10 | `QaModelCatalogRequirementsTests.启用的BalancedChat必须有对应采样profile` |
| CFG-11 | `QaDomainVocabularyTests.问答模型命令固定使用Balanced且不携带采样参数` |
| CFG-12 | `QaUnderstandingConfigurationTests.评分执行器只有三个线程和三个排队位且有诊断名称` |

另有 DB-10 记在 `e2e06_删除对话隐藏业务数据但不动ai诊断记录`、E2E-07 由全量 verify 本身承担、
MEM-12「摘要不可从普通 API 读取」由隐私审计的响应扫描覆盖——这些是命名归属问题，不是缺口。

### 15.4 本轮补齐的真实缺口

**3A-API-05「跨归属 turn 返回 404」此前无任何断言。** 已补
`QaConversationApiIntegrationTests.api05_本轮不属于当前对话时返回404而不是泄露它存在`：
本轮真实存在、只是不属于该对话时，响应必须与"根本不存在"完全一致，否则调用方能推断出
这个 `turnId` 是有效的。

### 15.5 偏差与未执行项

- 同日一次 `ci-validate.sh` 执行曾因既有知识库异步串扰退出非零
  （`ScheduledSyncIntegrationTests`），单独复跑 7/7 通过，随后的全量 verify 亦全绿。
  根因是 [ADR 0002](../../docs/adr/0002-knowledge-base-async-and-concurrency.md) 的单实例假设，
  与 3A 无关，未因此改动知识库业务代码。

## 16. Step E 手工走查记录（3A-UI-01~16，2026-08-08）

### 16.1 环境

- 执行人：项目所有者本人
- 浏览器：Chrome
- viewport：1440 / 720 / 375px 三档
- 后端配置：**真实 DashScope Key**（`backend/.env`），走真实模型调用，
  因此 Inspector 展示的是真实改写与评分结果，不是假 Chat 的确定性输出

### 16.2 结果

**16 条全部 passed**，无 failed、无 blocked、无与规格的偏离裁决。

> **后续影响（2026-08-08 晚些时候）**：问答页改判为 C — Focused Chat，`3A-UI-07` 因此作废。
> 本节记录的是当天走查的真实结果，不改；作废结论记在 §12.1，两处一起读才是完整事实。

- 3A-UI-01~08（问答 A）：首问建对话与刷新恢复、改写 fallback 与无匹配不伪装成答案、
  4+ 主题提示拆分、歧义单选闭环、活动 turn 防重复提交、重命名与逻辑删除二次确认、
  1440/720/375px 的收起与抽屉及键盘焦点、无伪来源/伪答案/停止排队控件
- 3A-UI-09~16（意图树 C）：画布选节点与三种角色编辑、创建默认停用、停用/删除先预览整棵子树、
  两种 enable scope 语义可辨、revision 冲突保留表单、同名搜索保留路径与失效 KB 阻塞、
  大树缩放与 375px 列表→详情、两页颜色非唯一状态与危险按钮非默认焦点

### 16.3 证据与其边界

证据形式是**项目所有者本人的走查确认**，不含截图。这一点如实记录而不美化：
本文件不引用不存在的截图文件，也不把"所有者确认"写成"已归档证据"。

走查用的是真实 Key 而非 `ProgrammableFakeProvider`，因此同时验证了真实模型链路下的
表现——这比 CI 的确定性假 Chat 更接近生产，但也意味着它**不可重放**：同一问题下次
改写与评分结果未必逐字相同，回归仍以 130 条自动化用例为准。
