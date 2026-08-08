# 数据模型：AI 知识问答核心链路（Phase 3A）

- `status`: approved
- `approved_at`: 2026-08-07
- `date`: 2026-08-07
- `architecture`: [技术方案](architecture.md)
- `api`: [API 契约](api.md)
- `adr`: [ADR 0007 — 版本化意图树](../../docs/adr/0007-versioned-intent-tree.md)

## 1. 状态所有权

| 状态 | 唯一来源 | 跨重启 | 删除口径 |
|---|---|---:|---|
| 对话、消息、摘要、本轮与理解结果 | PostgreSQL `qa_*` | 是 | 业务逻辑删除 |
| 意图树当前指针与完整 revision | PostgreSQL | 是 | 新 revision 标记删除；历史不改 |
| 当前意图树缓存 | JVM `AtomicReference` | 否 | 从数据库重建 |
| 子问题评分线程与 Future | JVM 有界执行器 | 否 | 请求结束释放 |
| 模型路由、attempt、breaker 与模型诊断 | `com.app.ai` | 按 ADR 0005 | 7 天诊断保留 |
| 3C 的排队许可/取消 token | 尚未建设，未来 JVM 内 | 否 | 不落库轮询 |

问答业务表可以保存用户授权保存的正文；模型诊断表和 `qa_turn_stage` 不保存正文。两类表不能因为都叫
“Trace”就互相复制数据。

## 2. 表与关系

```text
qa_conversation 1 ── n qa_turn 1 ── n qa_message
       │                  ├── n qa_subquestion
       │                  │       ├── n qa_intent_match
       │                  │       └── n qa_retrieval_action
       │                  └── n qa_turn_stage
       └── n qa_conversation_summary

qa_intent_tree_head 1 ── n qa_intent_tree_revision
                                  └── n qa_intent_node_snapshot
```

| 表 | 作用 |
|---|---|
| `qa_conversation` | 对话标题、活动时间、逻辑删除和乐观版本 |
| `qa_turn` | 一次用户提交到稳定终态；承载幂等键、树 revision 和降级摘要 |
| `qa_message` | 用户、引导及后续助手消息；按对话单调序号排序 |
| `qa_conversation_summary` | 摘要版本、覆盖水位线和逻辑替换历史 |
| `qa_subquestion` | 改写后的有序子问题及来源 |
| `qa_intent_match` | 经过代码复核与封顶后保留的候选快照 |
| `qa_retrieval_action` | 3A 给 3B 的声明式动作，3A 不执行 |
| `qa_clarification_option` | `WAITING_CLARIFICATION` 的稳定选项与显示路径 |
| `qa_turn_stage` | 不含正文的阶段结果与 `logicalCallId` 关联 |
| `qa_intent_tree_head` | 单例树头与当前 revision 指针 |
| `qa_intent_tree_revision` | 每次成功树变更的版本元数据 |
| `qa_intent_node_snapshot` | 一个 revision 的完整节点快照 |

## 3. 全局约定

- 业务主键使用应用生成的 UUID；数据库 identity 只用于无业务含义的内部行序号。
- 时间使用 `timestamptz`；排序使用显式序号，不用时间戳猜先后。
- 闭集用 `varchar + check`，Java 侧使用 enum；不使用 PostgreSQL 原生 enum。
- 业务实体都有 `created_at`、`updated_at`、`deleted`、`deleted_at`；`deleted=true` 与 `deleted_at is not null`
  由 check 约束保持一致。
- 乐观并发版本是非负 `bigint`。API 的 `expectedVersion/expectedRevision` 不能由服务端静默忽略。
- 用户问题、摘要、子问题属于业务正文；模型原始响应、Prompt、评分 reason、供应商错误体不落库。
- 分数用 `double precision` 并校验有限且 `[0,1]`；不能把 `NaN` 写进 PostgreSQL 后绕过业务比较。
- 所有外键都指向稳定业务主键。逻辑删除父实体时不依赖 `ON DELETE CASCADE` 模拟业务删除。

## 4. 对话、消息与本轮

### 4.1 `qa_conversation`

```sql
create table qa_conversation (
    id              uuid primary key,
    title           varchar(200) not null,
    version         bigint       not null default 0,
    last_active_at  timestamptz  not null,
    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now(),
    deleted         boolean      not null default false,
    deleted_at      timestamptz,
    constraint ck_qa_conversation_title check (char_length(btrim(title)) between 1 and 200),
    constraint ck_qa_conversation_version check (version >= 0),
    constraint ck_qa_conversation_deleted check (deleted = (deleted_at is not null))
);

create index idx_qa_conversation_active
    on qa_conversation (last_active_at desc, id desc) where deleted = false;
```

默认标题由首条问题的确定性前 40 个 Unicode code point 生成，不额外调用模型。重命名/删除按 `version` CAS。

### 4.2 `qa_turn`

```sql
create table qa_turn (
    id                       uuid primary key,
    conversation_id          uuid         not null references qa_conversation(id),
    client_request_id        uuid         not null,
    user_message_id          uuid         not null,
    status                   varchar(40)  not null,
    intent_revision_id       uuid,
    rewrite_mode             varchar(24),
    rewritten_question       text,
    summary_used             boolean,
    recent_complete_turns    smallint,
    degradation_codes        jsonb        not null default '[]'::jsonb,
    terminal_reason          varchar(64),
    source_turn_id           uuid references qa_turn(id),
    selected_intent_node_id  uuid,
    started_at               timestamptz,
    completed_at             timestamptz,
    created_at               timestamptz  not null default now(),
    updated_at               timestamptz  not null default now(),
    deleted                  boolean      not null default false,
    deleted_at               timestamptz,
    constraint uq_qa_turn_request unique (conversation_id, client_request_id),
    constraint uq_qa_turn_user_message unique (user_message_id),
    constraint ck_qa_turn_status check (status in (
        'SAVED','PROCESSING','ACTIONS_READY','WAITING_CLARIFICATION',
        'REJECTED_TOO_MANY_TOPICS','FAILED')),
    constraint ck_qa_turn_rewrite check (
        rewrite_mode is null or rewrite_mode in ('MODEL','NORMALIZED_FALLBACK')),
    constraint ck_qa_turn_rewritten_question check (
        rewritten_question is null
        or char_length(btrim(rewritten_question)) between 1 and 20000),
    constraint ck_qa_turn_summary_used check (
        (summary_used is null and recent_complete_turns is null)
        or (summary_used is not null and recent_complete_turns between 0 and 8)),
    constraint ck_qa_turn_terminal_time check (
        (status in ('SAVED','PROCESSING') and completed_at is null)
        or (status not in ('SAVED','PROCESSING') and completed_at is not null)),
    constraint ck_qa_turn_clarification check (
        (source_turn_id is null and selected_intent_node_id is null)
        or source_turn_id is not null),
    constraint ck_qa_turn_deleted check (deleted = (deleted_at is not null))
);

create unique index uq_qa_turn_one_active
    on qa_turn (conversation_id)
    where deleted = false and status in ('SAVED','PROCESSING');

create index idx_qa_turn_conversation
    on qa_turn (conversation_id, created_at desc, id desc) where deleted = false;
```

`rewritten_question`、`summary_used` 和 `recent_complete_turns` 是问答页 Inspector 的可恢复验收状态；不保存摘要正文或
Prompt。`degradation_codes` 是闭集字符串数组，只保存如 `SUMMARY_UNAVAILABLE`、`REWRITE_FALLBACK`，不保存异常消息。
同一幂等键收到不同正文由应用比较其 `user_message_id` 对应正文 hash 后返回冲突；hash 仅用于比较，不进入 API。

### 4.3 `qa_message`

```sql
create table qa_message (
    id               uuid primary key,
    conversation_id  uuid         not null references qa_conversation(id),
    turn_id           uuid         not null references qa_turn(id),
    sequence_no       bigint       not null,
    role              varchar(16)  not null,
    message_type      varchar(32)  not null,
    content           text         not null,
    created_at        timestamptz  not null default now(),
    updated_at        timestamptz  not null default now(),
    deleted           boolean      not null default false,
    deleted_at        timestamptz,
    constraint uq_qa_message_sequence unique (conversation_id, sequence_no),
    constraint ck_qa_message_sequence check (sequence_no > 0),
    constraint ck_qa_message_role check (role in ('USER','ASSISTANT')),
    constraint ck_qa_message_type check (message_type in (
        'USER_QUERY','CLARIFICATION_CHOICE','CLARIFICATION_GUIDANCE',
        'SYSTEM_NOTICE')),
    constraint ck_qa_message_content check (char_length(btrim(content)) between 1 and 20000),
    constraint ck_qa_message_deleted check (deleted = (deleted_at is not null))
);

create index idx_qa_message_conversation
    on qa_message (conversation_id, sequence_no desc) where deleted = false;
```

`SYSTEM_NOTICE` 用于“问题主题超过 3 个”等固定业务反馈。`ANSWER` 到 3C 才有生产写入路径，届时用向前
migration 扩展 check；3A 的数据库和 Java 闭集都不预留没有执行路径的答案类型。

序号通过锁定对话行并递增持久化，不能用 `max(sequence_no)+1` 无锁竞争。删除对话时同一事务把所属 turn、
message、summary 和处理结果标记逻辑删除；`ai_*` 诊断不级联。

## 5. 摘要

### 5.1 `qa_conversation_summary`

```sql
create table qa_conversation_summary (
    id                    uuid primary key,
    conversation_id       uuid         not null references qa_conversation(id),
    revision              bigint       not null,
    content               text         not null,
    watermark_message_id  uuid         not null references qa_message(id),
    watermark_sequence_no bigint       not null,
    covered_turn_count    integer      not null,
    created_at            timestamptz  not null default now(),
    updated_at            timestamptz  not null default now(),
    deleted               boolean      not null default false,
    deleted_at            timestamptz,
    constraint uq_qa_summary_revision unique (conversation_id, revision),
    constraint ck_qa_summary_revision check (revision > 0),
    constraint ck_qa_summary_watermark check (watermark_sequence_no > 0),
    constraint ck_qa_summary_turns check (covered_turn_count > 0),
    constraint ck_qa_summary_content check (char_length(btrim(content)) between 1 and 20000),
    constraint ck_qa_summary_deleted check (deleted = (deleted_at is not null))
);

create unique index uq_qa_summary_current
    on qa_conversation_summary (conversation_id) where deleted = false;
```

安装新摘要的事务：锁当前有效摘要（无则锁 conversation）→ 比较期望 revision 与水位线 → 插入下一 revision →
逻辑删除旧有效行。旧行保留用于审计，但普通读取只见一个有效摘要。

水位线同时保存消息 UUID 与 sequence，UUID 用于引用、sequence 用于范围查询和断言。两者必须指向同一消息；
Repository 集成测试验证，不用触发器增加写入复杂度。

## 6. 子问题、匹配、引导与动作

### 6.1 `qa_subquestion`

```sql
create table qa_subquestion (
    id                   uuid primary key,
    turn_id              uuid        not null references qa_turn(id),
    ordinal              smallint    not null,
    text                 text        not null,
    source               varchar(24) not null,
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now(),
    deleted              boolean     not null default false,
    deleted_at           timestamptz,
    constraint uq_qa_subquestion_ordinal unique (turn_id, ordinal),
    constraint ck_qa_subquestion_ordinal check (ordinal between 0 and 2),
    constraint ck_qa_subquestion_source check (source in ('MODEL','REWRITTEN','NORMALIZED_FALLBACK')),
    constraint ck_qa_subquestion_text check (char_length(btrim(text)) between 1 and 20000),
    constraint ck_qa_subquestion_deleted check (deleted = (deleted_at is not null))
);
```

### 6.2 `qa_intent_match`

```sql
create table qa_intent_match (
    id                 uuid             primary key,
    turn_id            uuid             not null references qa_turn(id),
    subquestion_id     uuid             not null references qa_subquestion(id),
    intent_revision_id uuid             not null references qa_intent_tree_revision(id),
    node_id            uuid             not null,
    node_type          varchar(16)      not null,
    full_path_snapshot varchar(1000)    not null,
    knowledge_base_id  bigint,
    system_action      varchar(40),
    score              double precision not null,
    rank               smallint         not null,
    category_node_id   uuid             not null,
    created_at         timestamptz      not null default now(),
    deleted            boolean          not null default false,
    deleted_at         timestamptz,
    constraint uq_qa_intent_match_rank unique (subquestion_id, rank),
    constraint uq_qa_intent_match_node unique (subquestion_id, node_id),
    constraint ck_qa_intent_match_type check (node_type in ('KB','SYSTEM')),
    constraint ck_qa_intent_match_score check (
        isfinite(score) and score >= 0.0 and score <= 1.0),
    constraint ck_qa_intent_match_rank check (rank between 1 and 3),
    constraint ck_qa_intent_match_target check (
        (node_type = 'KB' and knowledge_base_id is not null and system_action is null)
        or (node_type = 'SYSTEM' and knowledge_base_id is null and system_action is not null)),
    constraint ck_qa_intent_match_deleted check (deleted = (deleted_at is not null))
);
```

不保存模型 `reason`；路径、目标和类型是当时 revision 的反规范化快照，便于树后来改变后仍解释本轮。

### 6.3 `qa_clarification_option`

```sql
create table qa_clarification_option (
    id                 uuid          primary key,
    turn_id            uuid          not null references qa_turn(id),
    ordinal            smallint      not null,
    intent_revision_id uuid          not null references qa_intent_tree_revision(id),
    node_id            uuid          not null,
    category_node_id   uuid          not null,
    label              varchar(1000) not null,
    created_at         timestamptz   not null default now(),
    deleted            boolean       not null default false,
    deleted_at         timestamptz,
    constraint uq_qa_clarification_ordinal unique (turn_id, ordinal),
    constraint uq_qa_clarification_node unique (turn_id, node_id),
    constraint ck_qa_clarification_ordinal check (ordinal between 1 and 3),
    constraint ck_qa_clarification_deleted check (deleted = (deleted_at is not null))
);
```

“都不是这些”是 API 固定动作，不伪装成 node row。用户选择必须引用前 turn 和选项 nodeId；后端验证该 turn
仍为 `WAITING_CLARIFICATION` 且属于同一对话。

### 6.4 `qa_retrieval_action`

```sql
create table qa_retrieval_action (
    id                 uuid         primary key,
    turn_id            uuid         not null references qa_turn(id),
    subquestion_id     uuid         not null references qa_subquestion(id),
    ordinal            smallint     not null,
    action_type        varchar(32)  not null,
    intent_node_id     uuid,
    knowledge_base_id  bigint,
    system_action      varchar(40),
    reason_code        varchar(64)  not null,
    created_at         timestamptz  not null default now(),
    updated_at         timestamptz  not null default now(),
    deleted            boolean      not null default false,
    deleted_at         timestamptz,
    constraint uq_qa_action_ordinal unique (subquestion_id, ordinal),
    constraint ck_qa_action_type check (action_type in (
        'KB_TARGETED','GLOBAL_FALLBACK','SYSTEM','NO_MATCH')),
    constraint ck_qa_action_target check (
        (action_type = 'KB_TARGETED' and intent_node_id is not null and knowledge_base_id is not null and system_action is null)
        or (action_type = 'SYSTEM' and intent_node_id is not null and knowledge_base_id is null and system_action is not null)
        or (action_type in ('GLOBAL_FALLBACK','NO_MATCH') and intent_node_id is null and knowledge_base_id is null and system_action is null)),
    constraint ck_qa_action_deleted check (deleted = (deleted_at is not null))
);
```

`WAITING_CLARIFICATION` 是 turn 终态，不写伪动作。3B 只消费 `ACTIONS_READY` turn 的有效 action。

## 7. 不含正文的阶段记录

### 7.1 `qa_turn_stage`

```sql
create table qa_turn_stage (
    id                 uuid         primary key,
    turn_id            uuid         not null references qa_turn(id),
    stage              varchar(32)  not null,
    work_unit_ordinal  smallint     not null default 0,
    outcome            varchar(16)  not null,
    reason_code        varchar(64),
    logical_call_id    uuid,
    intent_revision_id uuid,
    started_at         timestamptz  not null,
    completed_at       timestamptz  not null,
    duration_ms        bigint       not null,
    constraint uq_qa_turn_stage unique (turn_id, stage, work_unit_ordinal),
    constraint ck_qa_stage_name check (stage in (
        'SAVE','HISTORY','SUMMARY','REWRITE','INTENT_SCORE','AMBIGUITY','ACTION_MAP','FINALIZE')),
    constraint ck_qa_stage_outcome check (outcome in ('SUCCEEDED','DEGRADED','FAILED','SKIPPED')),
    constraint ck_qa_stage_ordinal check (work_unit_ordinal between 0 and 3),
    constraint ck_qa_stage_duration check (duration_ms >= 0),
    constraint ck_qa_stage_time check (completed_at >= started_at)
);

create index idx_qa_turn_stage_call
    on qa_turn_stage (logical_call_id) where logical_call_id is not null;
```

耗时由 monotonic ticker 计算后落非负毫秒；wall clock 只作展示。一个子问题一次 `INTENT_SCORE`，ordinal 为
1~3；其他阶段通常为 0。表没有 `payload/error_message/prompt/response` 字段。

## 8. 版本化意图树

### 8.1 `qa_intent_tree_head`

```sql
create table qa_intent_tree_head (
    singleton_id        smallint primary key,
    current_revision_id uuid,
    current_revision_no bigint   not null default 0,
    updated_at          timestamptz not null default now(),
    constraint ck_qa_intent_tree_singleton check (singleton_id = 1),
    constraint ck_qa_intent_revision_no check (current_revision_no >= 0)
);
```

迁移插入唯一 `singleton_id=1`。首个 revision 创建后再补 FK 到 revision，或在同一 migration 用可延迟约束处理
循环引用；实现优先选择无循环的“revision 引用 head、head 指向 UUID 不建 FK + Repository 校验”形态。

### 8.2 `qa_intent_tree_revision`

```sql
create table qa_intent_tree_revision (
    id                uuid primary key,
    revision_no       bigint       not null unique,
    parent_revision_id uuid references qa_intent_tree_revision(id),
    change_type       varchar(24)  not null,
    changed_node_id   uuid,
    node_count        integer      not null,
    enabled_leaf_count integer     not null,
    created_at        timestamptz  not null default now(),
    constraint ck_qa_intent_revision_no check (revision_no > 0),
    constraint ck_qa_intent_change check (change_type in (
        'CREATE','UPDATE','ENABLE_SUBTREE','DISABLE_SUBTREE','DELETE_SUBTREE')),
    constraint ck_qa_intent_counts check (
        node_count >= 0 and enabled_leaf_count >= 0 and enabled_leaf_count <= node_count)
);
```

不保存操作者，因为当前产品是单用户且没有可信身份来源；未来引入认证后再加 actor，而不是写固定假用户。

### 8.3 `qa_intent_node_snapshot`

```sql
create table qa_intent_node_snapshot (
    revision_id       uuid          not null references qa_intent_tree_revision(id),
    node_id           uuid          not null,
    parent_id         uuid,
    depth             smallint      not null,
    sort_order        integer       not null,
    node_type         varchar(16)   not null,
    name              varchar(120)  not null,
    description       varchar(2000),
    examples          jsonb         not null default '[]'::jsonb,
    knowledge_base_id bigint references knowledge_base(id),
    system_action     varchar(40),
    prompt_snippet    varchar(2000),
    enabled           boolean       not null default false,
    deleted           boolean       not null default false,
    created_at        timestamptz   not null default now(),
    primary key (revision_id, node_id),
    constraint ck_qa_intent_depth check (depth between 1 and 3),
    constraint ck_qa_intent_sort check (sort_order >= 0),
    constraint ck_qa_intent_type check (node_type in ('GROUP','KB','SYSTEM')),
    constraint ck_qa_intent_name check (char_length(btrim(name)) between 1 and 120),
    constraint ck_qa_intent_description check (
        description is null or char_length(btrim(description)) between 1 and 2000),
    constraint ck_qa_intent_examples check (
        jsonb_typeof(examples) = 'array' and jsonb_array_length(examples) <= 20),
    constraint ck_qa_intent_target check (
        (node_type = 'GROUP' and knowledge_base_id is null and system_action is null and prompt_snippet is null)
        or (node_type = 'KB' and knowledge_base_id is not null and system_action is null)
        or (node_type = 'SYSTEM' and knowledge_base_id is null and system_action is not null)),
    constraint ck_qa_intent_deleted_disabled check (not deleted or not enabled)
);

create index idx_qa_intent_snapshot_parent
    on qa_intent_node_snapshot (revision_id, parent_id, sort_order, node_id);
create index idx_qa_intent_snapshot_enabled_leaf
    on qa_intent_node_snapshot (revision_id, node_type, node_id)
    where deleted = false and enabled = true and node_type in ('KB','SYSTEM');
```

`parent_id is null` 表示直接挂在虚拟根下；虚拟根不是表中 node。跨行约束由写入服务在插入前对完整快照验证：

- 每个非顶层 parent 在同 revision 存在且类型为 GROUP；
- 无环，depth 等于实际路径且不超过 3；
- 有效同父名称唯一；KB/SYSTEM 无子节点；
- 启用节点的所有祖先启用；启用叶子的 description/examples/目标完整；
- examples 每项是 1~500 字符字符串且规范化后不重复；
- SYSTEM action 第一版闭集为 `GREETING`、`ASSISTANT_IDENTITY`、`CAPABILITY_HELP`。

PostgreSQL check 负责单行不变量，领域验证负责图不变量；两层都要测，不能只靠 Prompt 或前端。

## 9. Revision 写入算法

一个管理命令在单事务内：

1. `select ... from qa_intent_tree_head where singleton_id=1 for update`；
2. 比较 `expectedRevisionId`，不等则抛 409，不做任何写入；
3. 读取当前完整快照并在内存应用单节点/子树命令；
4. 校验图、知识库可用性、字段上限、节点上限和启用叶子 Prompt 预算；
5. 插入 `revision_no + 1` 元数据和该 revision 的所有 node snapshot；
6. 更新 head 指针；提交；
7. 事务提交后原子替换 JVM snapshot。若替换失败，清空缓存，让下一读从新 head 重建。

一次写是 O(N) 行复制，换来 revision 的不可变和简单读取。单事务不对模型评分持锁。Repository 不提供“只 update
某一行同时沿用旧 revision”的旁路。

## 10. 逻辑删除传播

- 删除对话：conversation、turn、message、summary、subquestion、match、option、action 逻辑删除；stage 作为该
  业务事实的一部分同步隐藏但可保留物理行。模型诊断按自己的保留策略，不因对话删除而修改。
- 删除意图子树：新 revision 中目标和全部后代 `deleted=true, enabled=false`；旧 revision 不变。
- 删除知识库：Phase 1 仍是知识库事实来源。已有意图历史快照保留 KB ID；新 revision 中绑定失效叶子不能启用，
  Route Canvas 显示阻塞。知识库删除流程不跨模块静默改写意图树。
- 普通列表统一显式 `deleted=false`；Repository 方法名区分 active/history，禁止靠调用者记住过滤条件。

## 11. 迁移与回滚

- Phase 3A 使用 V7 新建 `qa_*`，V8 只为 `qa_turn` 增加可恢复 Inspector 字段；都不修改 Phase 1/2 表结构。
- 初始树头 revision 0 允许空树；首个节点命令生成 revision 1。
- 回退应用可以保留新增表，不影响知识库或模型调度。若必须重做 migration，只允许在无 3A 业务数据环境中
  明确 drop；生产式回滚不删除已保存对话。
- 第一版不提供用户界面版本回滚。以后若需要，回滚也是“以旧 snapshot 内容创建一个新 revision”，绝不把
  current pointer 直接倒退并重用旧 revision 号。

## 12. Repository 验收不变量

- 每个对话最多一个活动 turn；相同幂等键不会创建第二条用户消息。
- 每个对话最多一个有效摘要，水位线严格前移。
- 一个 turn 最多 3 个子问题和 3 个全局保留 match；每个子问题顺序稳定。
- `ACTIONS_READY` 至少一个子问题，且每个子问题至少一个 action；`WAITING_CLARIFICATION` 有 2~3 个选项且无 action。
- turn 的 `intent_revision_id` 与 match/option 使用同一 revision。
- 树头只前进；revision 不 update/delete；一个 revision 能重建一棵无孤儿、无环、深度不超过 3 的树。
- `qa_turn_stage.logical_call_id` 能关联模型诊断，但两边都不复制正文。
