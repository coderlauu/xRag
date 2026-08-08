# ADR 0007：用整树 revision 管理稳定 UUID 意图树

- `status`: accepted
- `accepted_at`: 2026-08-07
- `date`: 2026-08-07
- `decision_owner`: liuqiang
- `scope`: Phase 3A 意图组织、在线维护、评分快照与后续检索动作
- `related`: [Phase 3 Step A 检查点](../../learning/ragent-column/PHASE-3-STEP-A-CHECKPOINT.md)、[PRD](../prd/2026-08-07-qa-pipeline-prd.md)、[架构](../../tech/qa-pipeline/architecture.md)、[数据模型](../../tech/qa-pipeline/data-model.md)

## Context

Phase 3 需要把用户问题路由到具体 xrag 知识库或受控系统场景。扁平的“知识检索/闲聊”分类无法解释应该
查哪个库；让中间分类也参与评分又会把“可组织但不可执行”的节点与真正目标放在同一分数空间。

意图配置必须在线维护：运营者保存后新请求应立即看到新配置，但已经开始的请求不能在评分一半时混入新节点。
父节点停用/删除也必须与全部后代原子一致，不能出现“界面显示停用、叶子仍在匹配”。同时，历史 turn 需要能
解释当时选择了哪个路径和知识库，不能只关联会不断变化的当前节点行。

xrag 当前是单实例、PostgreSQL + pgvector、业务数据逻辑删除。原课程的 Milvus Collection、Redis 整树缓存和
固定三级 level 不可直接照搬。

## Decision drivers

- 只有具备执行目标的节点参与评分。
- 在途请求对一份不可变配置运行，运营修改只影响后续请求。
- 子树写入要么完整成功，要么不生效；并发编辑必须可察觉。
- 叶子绑定 xrag 知识库 ID，不引入 Collection 名或可执行字符串。
- 历史结果能关联当时配置，但第一版不建设完整审计/回滚产品。
- 不为多实例预付 Redis、Pub/Sub 或分布式锁复杂度。
- 评分必须一次看到同一份全部启用叶子；不能静默截断或拆批破坏分数可比性。

## Considered options

### A. 单表可变邻接树 + 每次读取当前行

优点是写入小、SQL 直观。缺点是在途评分可能混用更新前后节点；父子级联需要复杂锁顺序；历史 turn 即使保存
nodeId，也无法重现当时 description、路径和绑定。拒绝。

### B. 扁平分类或向量化意图路由

扁平分类在知识库增长后失去组织与运营边界；向量化路由需要额外 embedding 生命周期、阈值评测和跨模型身份
治理，且不能自然表达子树启停与 SYSTEM 目标。它们可作为未来大规模树的候选，不作为第一版事实模型。

### C. 事件溯源，按节点事件重建任意版本

审计和回放最强，但命令、投影、幂等、修复和查询复杂度显著超过当前需要。项目没有通用事件存储，也没有可信
actor 身份。拒绝。

### D. 稳定 UUID 邻接结构 + 每次写完整 revision（采用）

每个 revision 保存一份完整快照，树头只指向最新版本。请求开始时捕获不可变快照；写命令锁树头并检查 expected
revision，验证完整新树后一次提交。树规模受启用叶子 64 KiB Prompt 预算约束，当前可接受 O(N) 写放大。

## Decision

### 1. 身份、结构与深度

- 节点使用应用生成的稳定 UUID；同一业务节点跨 revision 保持 nodeId。
- 系统虚拟根使用固定 UUID `00000000-0000-0000-0000-000000000001`，不落节点表、不可编辑、启停或删除。
- 虚拟根下使用 adjacency `parentId`，允许 1~3 层；不把 DOMAIN/CATEGORY/TOPIC 固化成数据库 level enum。
- 节点类型是 `GROUP / KB / SYSTEM`。`GROUP` 只组织且不参与评分；`KB/SYSTEM` 必须是无子节点的可评分叶子。
- 两层 KB 叶子的歧义品类是自己；三层 KB 叶子的品类是直接 GROUP 父节点。这条语义进入测试矩阵。

### 2. 执行绑定

- KB 叶子绑定一个 `knowledge_base.id`；不存 Collection、表名、SQL 或任意检索表达式。
- SYSTEM 叶子绑定服务端闭集 action，第一版为 `GREETING / ASSISTANT_IDENTITY / CAPABILITY_HELP`。
- `description + examples` 服务匹配；`promptSnippet` 是 3C 的追加规则。snippet 不参与 3A 评分 Prompt，也永远不能
  替换系统安全模板。
- GROUP 不允许目标或 promptSnippet，避免一个节点同时承担组织和执行职责。

### 3. Revision 与并发

- 每次成功创建、编辑、启停或删除都创建一个不可变整树 revision，并原子推进 singleton tree head。
- 命令必须携带 `expectedRevisionId`。不匹配返回 409 和当前 revision；不自动 merge、last-write-wins 或部分提交。
- 请求开始时固定一个 `IntentTreeSnapshot` 对象，所有子问题共享；在途不重新读 current head。
- turn、match 和 clarification option 保存 `intentRevisionId`；match 反规范化当时路径、类型和目标。
- 第一版不提供回滚 UI。未来回滚通过“用旧内容创建新 revision”完成，不倒退 revision 编号。

### 4. 启停与删除

- 新节点默认停用。
- 启用叶子要求全部祖先启用、匹配字段完整且目标可用。
- 停用或删除 GROUP 只能对整个子树执行；不允许只改父节点后留下有效后代。
- 启用 GROUP 可选仅当前或整个“全部配置完整”的子树；子树中任一阻塞使整体失败，不能跳过后宣称成功。
- 删除通过新 revision 把子树标为 `deleted=true, enabled=false`；旧 revision 保留。业务页面不提供物理删除。

### 5. Prompt 暴露、预算与封顶

- 评分 Prompt 只含当前 revision 全部启用叶子的 ID、类型、路径、description 和 examples；不含 KB 正文、snippet、
  已删除节点或知识库秘密。
- 节点按 UUID 稳定排序后一次编码。UTF-8 渲染结果上限默认 65,536 bytes。
- 超预算显式失败，不能随机截节点、只取局部分支或拆成多次评分；后者会让分数不再来自同一候选空间。
- 每个子问题最多保留 3 个候选，全 turn 最多 3 个，并先保证每个有候选的子问题一个名额。

### 6. 缓存与多实例边界

- PostgreSQL 是事实来源；单实例用 `AtomicReference` 缓存当前完整快照，成功写后原子替换。
- 不使用 Redis、TTL、Pub/Sub 或分布式锁。缓存异常时清空并从 tree head 重载。
- 多实例部署前必须新增 revision 传播/校验方案；当前方案不能宣称跨实例即时一致。

## Consequences

### Positive

- 读路径一次拿到不可变树，评分、歧义与动作使用同一配置事实。
- expected revision 和单事务消除静默覆盖、半个子树与父停子不停。
- 历史 turn 能解释当时路径与目标；当前运营修改不篡改历史。
- 知识库 ID 绑定与 pgvector 形态一致，意图树不泄露存储实现。
- 本地缓存满足当前单实例，且数据库仍是可重建事实来源。

### Negative / accepted costs

- 每次写复制 O(N) 节点，数据库保留多个 revision，空间大于可变单表。
- Route Canvas 的每次保存都可能因并发 revision 冲突，需要保留本地表单并让用户比较。
- 虚拟根和图不变量无法只靠简单 FK/check 完整表达，必须有整树领域校验与集成测试。
- 当前缓存一致性不支持多实例；扩容前必须补设计。
- 64 KiB 预算限制了单次平铺评分的规模；扩大树前要先做 Phase 4 质量评测，不是简单调大数字。

## Rejected shortcuts

- 不把找不到父节点的节点当新根兜底；写入时拒绝孤儿，避免结构错误悄悄上线。
- 不把 `parentId` 改成任意字符串或路径作为身份。
- 不让前端计算级联后直接逐节点调用 update；服务端接收一个子树命令并原子完成。
- 不在 Prompt 超限时 `limit(N)`，也不把 LLM 返回的未知节点当“以后可能存在”。
- 不预留 `MCP` 节点枚举；3B 确认工具产品语义后通过 migration/契约增量加入真实执行路径。

## Migration path

1. 初始 migration 创建 tree head、revision 和 node snapshot 表，head 从 revision 0 空树开始。
2. 3A 所有管理命令只写 revision 路径，不提供可变节点表旁路。
3. 若树规模接近预算，用 Phase 4 数据评估分层 LLM 路由、embedding 路由或域预筛；新方案必须明确如何保持
   历史 revision 和候选可比性。
4. 若升级多实例，优先按 revision number 做通知 + 读取校验；不把完整树正文复制到消息通道。
5. 若引入 MCP，新增真实 `MCP` 目标类型、Schema 和执行闭环后再扩闭集；不能先存不可执行节点。

## Validation

- 图约束：孤儿、环、四层、叶子有子、目标错配、同父重名均拒绝且不产生 revision。
- 并发：两个命令同 expected revision 只有一个成功，失败者不部分写。
- 快照：旧请求在新 revision 提交后仍只见旧节点；新请求完整看见新 revision。
- 级联：停用/删除父节点覆盖全部后代；启用不完整子树整体失败。
- 历史：旧 turn 在当前节点改名/删除后仍展示当时路径和目标。
- 预算：中文、多字节 emoji 和边界值按 UTF-8 精确计算，超限不截节点。
- 隐私：评分 Prompt 与 API 不含 KB 正文、snippet、secret、模型 reason 或已删除节点。
