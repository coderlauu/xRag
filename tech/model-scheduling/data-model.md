# 数据模型：大模型调度引擎

- `status`: approved（2026-08-02）
- `date`: 2026-07-28
- `architecture`: [技术方案](architecture.md)
- `prd`: [大模型调度引擎 PRD](../../docs/prd/2026-07-28-model-scheduling-prd.md)

## 1. 状态所有权

模型调度同时存在“期望配置”“短期运行态”“历史事实”三类状态，第一版刻意分开：

| 状态 | 唯一来源 | 是否跨重启 | 原因 |
|---|---|---:|---|
| Provider、deployment、能力、等级、优先级、限制 | `ai-models.yml` + 环境变量 | 是 | 版本控制、只读、重启生效 |
| 熔断滑动窗口、HALF_OPEN 许可 | JVM 内 Resilience4j registry | 否 | 当前单实例；热路径不依赖数据库 |
| 当前 Provider 是否缺密钥 | 运行时配置快照 | 重算 | 页面只能看到 `CONFIGURED/UNCONFIGURED`，看不到秘密 |
| 完成态逻辑调用、候选、路由事件、attempt | PostgreSQL | 是 | 支持 7 天复盘与分页查询 |
| breaker 状态变化历史 | PostgreSQL | 是（7 天） | 当前状态仍以 JVM registry 为准 |
| 正在进行的调用 | 调用内存 | 否 | 第一版不为实时进度增加同步写放大 |

因此数据库中不建 `provider`、`model`、`deployment`、`route_policy` 或 `circuit_breaker_state` 表。诊断页展示的模型目录来自配置快照，调用历史里的字段是调用发生时的反规范化快照。

## 2. 表清单

| 表 | 作用 | 生命周期 |
|---|---|---|
| `ai_logical_call` | 一次调用的请求约束、最终结果和聚合指标 | 7 天或最近 10,000 条 |
| `ai_route_candidate` | 当次配置快照中每个 deployment 的进入/过滤结果 | 随 logical call 级联物理删除 |
| `ai_route_event` | 没有发出 HTTP 请求的真实调度决策与预算事件 | 随 logical call 级联物理删除 |
| `ai_attempt` | 每次真实 Provider HTTP 请求 | 随 logical call 级联物理删除 |
| `ai_breaker_transition` | 熔断主状态变化历史 | 7 天；独立于 logical call |

诊断数据不是用户创建和维护的业务实体，而是有强制保留期的运维事实，因此采用到期物理删除。这是全系统“业务数据逻辑删除”规则的显式例外；诊断页没有删除按钮，也不允许按用户操作删除单条记录。

## 3. 全局约定

- 主调用 ID 用应用生成的 UUID，route event/attempt/transition 用数据库 identity。
- 展示时间快照用 `timestamptz`；所有耗时和事件 offset 都由 monotonic ticker 从逻辑调用开始计算为非负毫秒，不能用两个 wall-clock 快照相减。
- 需要由当前 Schema 限制的闭集状态使用 `varchar + check`，不使用 PostgreSQL 原生 enum；外部扩展状态和反规范化快照保留 `varchar`，由领域构造器校验。
- Provider/model/deployment 字段保存调用时快照，后续改配置不回写历史。
- `failure_phase` 使用 API 契约中的稳定 `FailurePhase`；未进入 Provider attempt 的调用级错误可为 `null`。
- `termination_reason` 表达调度器为何结束 logical call，与作为根因的 `normalized_error_type` 分开；中间 attempt 的下一步动作由 route event 表达。
- 原始 Prompt、消息、文档、输出、工具参数、Authorization 和 Provider 错误体没有对应列。
- Provider 请求 ID 进入 Repository 前已经脱敏，表字段名显式带 `_masked`。
- 诊断写入是一次独立短事务，不参与模型调用或知识库事务。

## 4. DDL

### 4.1 `ai_logical_call`

```sql
create table ai_logical_call (
    id                           uuid primary key,
    request_id                   varchar(128) not null,
    capability                   varchar(24)  not null,
    service_tier                 varchar(16),
    required_model_identity      varchar(160),
    stream                       boolean      not null,
    policy_version               varchar(64)  not null,
    config_fingerprint           varchar(64)  not null,

    outcome                      varchar(24)  not null,
    actual_provider_id           varchar(96),
    actual_deployment_id         varchar(128),
    actual_model_identity        varchar(160),
    provider_request_id_masked   varchar(160),
    normalized_error_type        varchar(48),
    failure_phase                varchar(48),
    termination_reason           varchar(64)  not null,
    http_status                  integer,
    finish_reason                varchar(64),

    attempt_count                integer      not null,
    retry_count                  integer      not null,
    failover_count               integer      not null,
    mixed_execution              boolean      not null,
    contributing_deployment_count integer     not null,
    committed                    boolean      not null,
    rerank_applied               boolean,
    degraded_reason              varchar(64),

    input_token_count            integer,
    output_token_count           integer,
    embedding_input_count        integer,
    embedding_batch_count        integer,
    embedding_dimensions         integer,
    rerank_document_count        integer,
    rerank_top_n                 integer,
    stream_event_count           integer,

    route_duration_ms            bigint,
    first_upstream_event_ms      bigint,
    first_meaningful_chunk_ms    bigint,
    downstream_commit_ms         bigint,
    total_duration_ms            bigint      not null,
    started_at                   timestamptz not null,
    completed_at                 timestamptz not null,
    diagnostic_schema_version    integer     not null default 1,

    constraint ck_ai_call_capability
        check (capability in ('CHAT', 'STREAM_CHAT', 'EMBEDDING', 'RERANK')),
    constraint ck_ai_call_tier
        check (service_tier is null or service_tier in ('ECONOMY', 'BALANCED', 'QUALITY')),
    constraint ck_ai_call_outcome
        check (outcome in ('SUCCEEDED', 'DEGRADED', 'FAILED', 'CANCELED', 'STREAM_FAILED')),
    constraint ck_ai_call_embedding_identity
        check (capability <> 'EMBEDDING' or required_model_identity is not null),
    constraint ck_ai_call_stream
        check (stream = (capability = 'STREAM_CHAT')),
    constraint ck_ai_call_finish_reason
        check (
            (finish_reason is null or finish_reason in (
                'STOP',
                'LENGTH',
                'TOOL_CALLS',
                'CONTENT_FILTER',
                'UNKNOWN'
            ))
            and (capability in ('CHAT', 'STREAM_CHAT') or finish_reason is null)
        ),
    constraint ck_ai_call_rerank_result
        check (
            (
                capability = 'RERANK'
                and (
                    (
                        outcome = 'SUCCEEDED'
                        and rerank_applied is true
                        and degraded_reason is null
                    )
                    or
                    (
                        outcome = 'DEGRADED'
                        and rerank_applied is false
                        and degraded_reason in (
                            'CAPABILITY_NOT_DECLARED',
                            'NO_ELIGIBLE_CANDIDATE',
                            'RECOVERABLE_CANDIDATES_EXHAUSTED'
                        )
                    )
                    or
                    (
                        outcome in ('FAILED', 'CANCELED')
                        and rerank_applied is null
                        and degraded_reason is null
                    )
                )
            )
            or
            (
                capability <> 'RERANK'
                and rerank_applied is null
                and degraded_reason is null
            )
        ),
    constraint ck_ai_call_counts
        check (
            attempt_count >= 0
            and retry_count >= 0
            and failover_count >= 0
            and contributing_deployment_count >= 0
        ),
    constraint ck_ai_call_execution_summary
        check (
            mixed_execution = (contributing_deployment_count > 1)
            and (
                contributing_deployment_count = 1
                or provider_request_id_masked is null
            )
            and (
                (
                    contributing_deployment_count = 1
                    and actual_provider_id is not null
                    and actual_deployment_id is not null
                    and actual_model_identity is not null
                )
                or
                (
                    contributing_deployment_count <> 1
                    and actual_provider_id is null
                    and actual_deployment_id is null
                    and actual_model_identity is null
                )
            )
        ),
    constraint ck_ai_call_termination_reason
        check (termination_reason in (
            'COMPLETED',
            'NOOP_DEGRADED',
            'INVALID_REQUEST',
            'NO_ELIGIBLE_MODEL',
            'NON_RECOVERABLE_ERROR',
            'ALL_CANDIDATES_EXHAUSTED',
            'DEADLINE_EXCEEDED',
            'CALLER_CANCELED',
            'STREAM_INTERRUPTED_AFTER_COMMIT'
        )),
    constraint ck_ai_call_error_type
        check (
            normalized_error_type is null or normalized_error_type in (
                'INVALID_REQUEST',
                'NO_ELIGIBLE_MODEL',
                'AUTHENTICATION',
                'PERMISSION',
                'RATE_LIMITED',
                'NETWORK',
                'SERVER_ERROR',
                'TIMEOUT',
                'PROTOCOL_CONTRACT',
                'CANCELED',
                'STREAM_INTERRUPTED'
            )
        ),
    constraint ck_ai_call_failure_phase
        check (
            failure_phase is null or failure_phase in (
                'CONNECT',
                'RESPONSE_HEADERS',
                'UPSTREAM_RESPONSE',
                'FIRST_UPSTREAM_EVENT',
                'FIRST_MEANINGFUL_CHUNK',
                'STREAM_IDLE',
                'UPSTREAM_STREAM',
                'PROVIDER_RESPONSE_VALIDATION',
                'TOTAL_DEADLINE'
            )
        ),
    constraint ck_ai_call_duration
        check (total_duration_ms >= 0)
);

create index idx_ai_call_started
    on ai_logical_call (started_at desc, id desc);

create index idx_ai_call_capability_started
    on ai_logical_call (capability, started_at desc);

create index idx_ai_call_outcome_started
    on ai_logical_call (outcome, started_at desc);

create index idx_ai_call_provider_started
    on ai_logical_call (actual_provider_id, started_at desc);

create index idx_ai_call_request_id
    on ai_logical_call (request_id);
```

`request_id` 不设唯一约束：它可以由调用方提供，错误复用不应让诊断写入失败。`id` 才是调度器生成的唯一逻辑调用 ID。

`attempt_count` 等于该 logical call 的真实 `ai_attempt` 行数；`retry_count` 与 `failover_count` 分别只统计 `attempt_kind=RETRY/FAILOVER` 的已 dispatch 请求。只发生 `RETRY_SCHEDULED`、`FAILOVER_SELECTED`、breaker 许可拒绝或预算终止而没有发出请求时，route event 仍保留，但三个 attempt 计数不增加。

`started_at/completed_at` 只是用于展示和检索的 wall-clock 快照；极端 NTP/人工校时回拨时允许 `completed_at < started_at`。总耗时、attempt/route event 排序与 offset 一律来自 monotonic ticker，分页使用已经保存的 `started_at + id` 稳定键，不根据 `completed_at - started_at` 推导任何运行事实。

`contributing_deployment_count` 是真正贡献下游结果的不同 deployment 数量，不是 attempt 数：有限响应来自 `SUCCEEDED` attempt；流式响应来自已经 `COMMITTED` 的 attempt，因此提交后 `STREAM_FAILED` 或调用方取消仍有一个贡献者；成功的 Embedding call 才从每批最终成功 attempt 汇总。若任一 Embedding 批次最终失败，或完整结果返回前被取消，整个调用没有下游结果，调用级 contributing count 固定为 0、`mixed_execution=false`、`actual_*` 全部为 `null`；此前成功批次只作为 `SUCCEEDED` attempts 保留在详情。只有 contributing count 为 1 时才填写调用级 `actual_*`。提交前失败、Noop 降级或零输入的值为 0；成功的多批 Embedding 由多个等价 deployment 共同完成时该值大于 1、`mixed_execution=true` 且调用级 `actual_*` 全部为 `null`。各批实际来源见 `ai_attempt` 和能力详情。

调用级 `provider_request_id_masked` 只复制唯一贡献 attempt 的脱敏请求 ID，Provider 未返回时允许为 `null`。提交前失败、Noop、零输入和 mixed execution 一律为 `null`；多个 attempt 的完整脱敏 ID 只保存在 `ai_attempt`，不能任取一个冒充调用级事实。

`stream` 是用于列表的冗余摘要，必须精确等于 `capability=STREAM_CHAT`。`finish_reason` 只允许 Chat/流式 Chat 使用公开 `ChatFinishReason` 闭集，失败或 Provider 没有完成原因时可为 `null`。Rerank 成功、Noop 降级和失败/取消分别写入 `true + null`、`false + 稳定降级原因`、`null + null`；非 Rerank 能力两列均为 `null`。

`service_tier` 对 Embedding 为 `null`；Chat/流式 Chat/Rerank 必须由应用层写入默认后的最终等级。DB 不写跨字段 check，以免将来为某个内部 Rerank 场景增加新等级时要先改历史约束；这条由领域构造器保证。

### 4.2 `ai_route_candidate`

```sql
create table ai_route_candidate (
    logical_call_id              uuid         not null
        references ai_logical_call (id) on delete cascade,
    candidate_ordinal            integer      not null,
    provider_id                  varchar(96)  not null,
    deployment_id                varchar(128) not null,
    model_identity               varchar(160) not null,
    capability                   varchar(24)  not null,
    service_tier                 varchar(16),
    quota_domain                 varchar(128) not null,

    decision                     varchar(24)  not null,
    rejection_reason             varchar(64),
    planned_order                integer,
    default_candidate            boolean      not null,
    priority                     integer      not null,
    breaker_state_snapshot       varchar(24),

    primary key (logical_call_id, candidate_ordinal),
    constraint ck_ai_candidate_decision
        check (decision in ('ELIGIBLE', 'FILTERED')),
    constraint ck_ai_candidate_reason
        check (
            (decision = 'ELIGIBLE' and rejection_reason is null and planned_order is not null)
            or
            (decision = 'FILTERED' and rejection_reason is not null and planned_order is null)
        ),
    constraint ck_ai_candidate_order
        check (candidate_ordinal >= 0 and (planned_order is null or planned_order >= 0)),
    constraint ck_ai_candidate_priority
        check (priority >= 0)
);

create unique index uk_ai_candidate_planned_order
    on ai_route_candidate (logical_call_id, planned_order)
    where planned_order is not null;
```

`candidate_ordinal` 是脱敏配置快照中的稳定遍历序号；`planned_order` 是过滤后的候选链序号。两者都从 0 开始。

稳定过滤原因：

```text
DISABLED
CONFIG_INCOMPLETE
CAPABILITY_UNSUPPORTED
FEATURE_UNSUPPORTED
SERVICE_TIER_MISMATCH
MODEL_IDENTITY_MISMATCH
DATA_BOUNDARY_MISMATCH
CALLER_CONSTRAINT_MISMATCH
BREAKER_OPEN
BUDGET_INSUFFICIENT
```

半开许可竞争发生在真正 attempt 前，不修改这张快照；它作为 `BREAKER_PERMISSION_REJECTED` 写入 `ai_route_event`。

### 4.3 `ai_route_event`

```sql
create table ai_route_event (
    id                           bigint generated by default as identity primary key,
    logical_call_id              uuid         not null
        references ai_logical_call (id) on delete cascade,
    sequence_no                  integer      not null,
    occurred_offset_ms           bigint       not null,
    event_type                   varchar(48)  not null,

    work_unit_index              integer,
    attempt_no                   integer,
    candidate_order              integer,
    provider_id                  varchar(96),
    deployment_id                varchar(128),
    reason                       varchar(64),
    wait_duration_ms             bigint,
    remaining_budget_ms          bigint,
    eligible_candidate_count     integer,

    constraint uk_ai_route_event_sequence
        unique (logical_call_id, sequence_no),
    constraint ck_ai_route_event_type
        check (event_type in (
            'ROUTE_COMPLETED',
            'BREAKER_PERMISSION_REJECTED',
            'RETRY_SCHEDULED',
            'RETRY_WAIT_STARTED',
            'FAILOVER_SELECTED',
            'BUDGET_EXHAUSTED',
            'CALL_TERMINATED'
        )),
    constraint ck_ai_route_event_numbers
        check (
            sequence_no > 0
            and occurred_offset_ms >= 0
            and (work_unit_index is null or work_unit_index >= 0)
            and (attempt_no is null or attempt_no > 0)
            and (candidate_order is null or candidate_order >= 0)
            and (wait_duration_ms is null or wait_duration_ms >= 0)
            and (remaining_budget_ms is null or remaining_budget_ms >= 0)
            and (eligible_candidate_count is null or eligible_candidate_count >= 0)
        )
);

create index idx_ai_route_event_call
    on ai_route_event (logical_call_id, sequence_no);
```

这不是自由形态的日志表。`event_type` 只覆盖没有真实 HTTP attempt 却影响路由解释的稳定事件；可选列按事件类型使用，`reason` 只能写稳定分类，不能写异常消息或 Provider 原始错误。至少遵守：

- `ROUTE_COMPLETED` 填 `eligible_candidate_count`；
- `BREAKER_PERMISSION_REJECTED` 填候选与 deployment 快照；
- `RETRY_SCHEDULED` 填前一 attempt、`reason` 与计划等待时长；
- `RETRY_WAIT_STARTED` 在等待真正开始时写入，填 `wait_duration_ms` 与等待前 `remaining_budget_ms`；
- `FAILOVER_SELECTED` 填下一候选、`reason` 与 work unit；
- `BUDGET_EXHAUSTED` 填 work unit、`reason` 与剩余预算；
- `CALL_TERMINATED` 填与调用级 `termination_reason` 相同的 `reason`。

`sequence_no` 由调用内的单调序列分配器在事件发生时分配；Repository 不根据 candidate、attempt 间隙或最终结果补造事件。详情 API 按真实保存的 sequence/offset 返回这些记录。

### 4.4 `ai_attempt`

```sql
create table ai_attempt (
    id                           bigint generated by default as identity primary key,
    logical_call_id              uuid         not null
        references ai_logical_call (id) on delete cascade,
    attempt_no                   integer      not null,
    work_unit_index              integer      not null,
    input_from_inclusive         integer,
    input_to_exclusive           integer,
    candidate_order              integer      not null,
    attempt_kind                 varchar(16)  not null,
    started_sequence_no          integer      not null,
    completed_sequence_no        integer      not null,

    provider_id                  varchar(96)  not null,
    deployment_id                varchar(128) not null,
    model_identity               varchar(160) not null,
    quota_domain                 varchar(128) not null,
    breaker_state_before         varchar(24)  not null,
    breaker_state_after          varchar(24),

    outcome                      varchar(24)  not null,
    normalized_error_type        varchar(48),
    failure_phase                varchar(48),
    http_status                  integer,
    provider_request_id_masked   varchar(160),

    started_offset_ms            bigint       not null,
    first_upstream_event_ms      bigint,
    first_meaningful_chunk_ms    bigint,
    downstream_commit_ms         bigint,
    duration_ms                  bigint       not null,
    input_token_count            integer,
    output_token_count           integer,
    stream_event_count           integer,

    constraint uk_ai_attempt_no unique (logical_call_id, attempt_no),
    constraint ck_ai_attempt_kind
        check (attempt_kind in ('INITIAL', 'RETRY', 'FAILOVER')),
    constraint ck_ai_attempt_outcome
        check (outcome in (
            'SUCCEEDED', 'FAILED', 'RATE_LIMITED', 'TIMED_OUT',
            'CANCELED', 'EMPTY_STREAM', 'STREAM_FAILED'
        )),
    constraint ck_ai_attempt_error_type
        check (
            normalized_error_type is null or normalized_error_type in (
                'INVALID_REQUEST',
                'NO_ELIGIBLE_MODEL',
                'AUTHENTICATION',
                'PERMISSION',
                'RATE_LIMITED',
                'NETWORK',
                'SERVER_ERROR',
                'TIMEOUT',
                'PROTOCOL_CONTRACT',
                'CANCELED',
                'STREAM_INTERRUPTED'
            )
        ),
    constraint ck_ai_attempt_numbers
        check (
            attempt_no > 0
            and work_unit_index >= 0
            and candidate_order >= 0
            and started_sequence_no > 0
            and completed_sequence_no > started_sequence_no
            and started_offset_ms >= 0
            and duration_ms >= 0
        ),
    constraint ck_ai_attempt_input_range
        check (
            (input_from_inclusive is null and input_to_exclusive is null)
            or
            (
                input_from_inclusive is not null
                and input_to_exclusive is not null
                and input_from_inclusive >= 0
                and input_to_exclusive > input_from_inclusive
            )
        ),
    constraint ck_ai_attempt_failure_phase
        check (
            failure_phase is null or failure_phase in (
                'CONNECT',
                'RESPONSE_HEADERS',
                'UPSTREAM_RESPONSE',
                'FIRST_UPSTREAM_EVENT',
                'FIRST_MEANINGFUL_CHUNK',
                'STREAM_IDLE',
                'UPSTREAM_STREAM',
                'PROVIDER_RESPONSE_VALIDATION',
                'TOTAL_DEADLINE'
            )
        )
);

create index idx_ai_attempt_call
    on ai_attempt (logical_call_id, attempt_no);

create index idx_ai_attempt_provider_call
    on ai_attempt (provider_id, logical_call_id);

create index idx_ai_attempt_provider_request_id
    on ai_attempt (provider_request_id_masked)
    where provider_request_id_masked is not null;
```

只有真正发出 Provider HTTP 请求才产生 `ai_attempt`。熔断许可被拒、预算不足、重试和故障转移决策不伪装成 attempt，也不能从 attempt 间隙推导；它们按真实发生时间写入 `ai_route_event`。

Embedding 的 `work_unit_index` 是批次序号，每个成功或失败 attempt 都保存同一 work unit 的 `input_from_inclusive/input_to_exclusive`；其他能力的 work unit 固定为 0、两个范围字段固定为 `null`。范围由调用开始时的 work-unit 规划快照产生，不能在查询时根据当前 deployment 配置重算。由于 attempt 表不重复存 capability，数据库只约束范围成对且合法；`CompletedCallDiagnostic` 领域构造器负责 Embedding/非 Embedding 的条件约束。`attempt_no` 在整个逻辑调用内单调递增。`started_sequence_no` 和 `completed_sequence_no` 与 route event 共用调用级序列分配器；详情时间线先按 offset 排序，同毫秒事件再按 sequence 排序，因此不会靠数组拼接猜测真实顺序。

`attempt_kind` 以 work unit 为边界：没有发生运行时候选切换时的首个 dispatch 为 `INITIAL`；同一候选再次 dispatch 为 `RETRY`；运行时失败或许可拒绝后向另一候选 dispatch 为 `FAILOVER`，即使被拒候选从未形成 attempt。仅有候选切换决策但没有任何后续 dispatch 时，不伪造 attempt 或增加 failover count。

Embedding capability detail 按 `work_unit_index + input range` 对 attempt 分组，并只用该组最终成功、真正贡献结果的 attempt 组装 batch execution；此前失败 attempt 仍保留相同范围并完整出现在 attempt 时间线。第一版不增加 contribution 表。

### 4.5 `ai_breaker_transition`

```sql
create table ai_breaker_transition (
    id                           bigint generated by default as identity primary key,
    breaker_key                  varchar(384) not null,
    provider_id                  varchar(96)  not null,
    deployment_id                varchar(128) not null,
    model_identity               varchar(160) not null,
    capability                   varchar(24)  not null,
    from_state                   varchar(24)  not null,
    to_state                     varchar(24)  not null,
    transition_reason            varchar(64)  not null,
    transitioned_at              timestamptz not null,

    constraint ck_ai_breaker_transition
        check (from_state <> to_state)
);

create index idx_ai_breaker_key_time
    on ai_breaker_transition (breaker_key, transitioned_at desc);

create index idx_ai_breaker_time
    on ai_breaker_transition (transitioned_at desc);
```

`transition_reason` 只存稳定分类，例如 `FAILURE_RATE`、`SLOW_CALL_RATE`、`WAIT_ELAPSED`、`HALF_OPEN_SUCCESS`、`HALF_OPEN_FAILURE`；不存异常消息。它不引用 logical call：transition 可能先于完成态 call 异步写入，加 FK 会引入无意义的写入顺序约束；触发它的 attempt 已在调用详情中保存 breaker 前后状态。

## 5. 写入模型

调用线程只构造内存聚合：

```text
CallDiagnosticBuilder
  ├─ RouteCandidateSnapshot[]
  ├─ RouteEventDiagnostic[]
  ├─ AttemptDiagnostic[]
  └─ FinalOutcome
            │ terminal
            ▼
CompletedCallDiagnostic
            │ non-blocking offer
            ▼
bounded queue → single writer → one DB transaction
```

单条 `CompletedCallDiagnostic` 在同一事务内：

1. 插入 `ai_logical_call`；
2. batch insert `ai_route_candidate`；
3. batch insert `ai_route_event`；
4. batch insert `ai_attempt`；
5. 提交。

任何一步失败都回滚整条调用，避免列表有记录而详情缺半条。写入器捕获异常、递增 `diagnosticWriteFailures` 并打节流 WARN，不向调用线程回传。

队列只接收已经最小化的数据结构；正文不能“先入队、写库前再脱敏”，否则堆转储和日志仍可能泄露。

## 6. 查询与分页

最近调用最多保留 10,000 条，使用现有 `{items,page,size,total}` 页码模型足够，默认 20、最大 100。列表先只从 `ai_logical_call` 取得一页和总数，不在分页 SQL 中 join candidate/attempt；再用本页 logical call IDs 一次批量查询各自 `attempt_no` 最大的 target，组装只读 `lastAttemptTarget`。详情分别查询 call、candidate、route event 和 attempt。

筛选与索引：

- 时间、能力、结果优先使用对应 `(field, started_at desc)` 索引；
- Provider 用 `exists` 查询 `ai_attempt.provider_id`；这既包含最终成功 Provider，也包含失败后发生切换的 Provider，mixed Embedding 不依赖调用级单值；
- 请求 ID 精确匹配 `ai_logical_call.request_id`，或用 `exists` 精确匹配 `ai_attempt.provider_request_id_masked`，不做 `%keyword%` 模糊搜索；
- `lastAttemptTarget` 只取最后一个真实 outbound attempt 的 Provider/deployment/model 快照，无 attempt 时为 `null`；它是展示字段，不是成功贡献者，也不参与 `actual_*`、mixed 或 contributing count 约束；
- tier 过滤量小，第一版不单建索引；有慢查询证据再加；
- 详情所有子表都以 logical call ID 为首列。

总览 24 小时统计只扫描 `ai_logical_call.started_at >= now() - interval '24 hours'`；`failovers24h` 使用 `count(*) filter (where failover_count > 0)`，与 calls 的 `failoverOccurred=true` 总数同口径，不对单次调用内的 failover count 求和。当前候选和 breaker 状态不查历史表，由配置快照与 registry 直接组装。

## 7. 保留与清理

每小时执行一次，单实例下用本地 `@Scheduled` 足够：

```sql
delete from ai_logical_call
where id in (
    select id
    from ai_logical_call
    where started_at < now() - interval '7 days'
    order by started_at, id
    limit 1000
);

delete from ai_logical_call
where id in (
    select id
    from ai_logical_call
    order by started_at desc, id desc
    offset 10000
    limit 1000
);

delete from ai_breaker_transition
where id in (
    select id
    from ai_breaker_transition
    where transitioned_at < now() - interval '7 days'
    order by transitioned_at, id
    limit 1000
);
```

每次语句最多删除 1,000 条并循环到本轮无数据，避免一个大事务长时间持锁。logical call 的子表通过 `on delete cascade` 清理。breaker transition 不受“最近 10,000 次调用”约束，只按 7 天清理，因为它不等同于逻辑调用。

清理失败只告警并在下一小时重试，不影响模型调用。页面显示实际最早记录时间，不声称一定有完整 7 天数据。

## 8. 隐私与完整性检查

自动化检查必须覆盖：

1. 五张表均不存在正文类列名；
2. 完成态 DTO 序列化不含 request message、document text、tool arguments、raw error 或 secret；
3. Provider request ID 经过统一 `IdentifierMasker` 后才能构造 DTO；
4. 原始错误体只在适配器栈内参与分类，异常跨 seam 前转成枚举 + HTTP 状态；
5. 诊断写入失败不改变主调用返回值；
6. 队列满时不阻塞调用线程；
7. 超过 7 天和超过 10,000 条两种清理条件各有集成测试；
8. 删除 logical call 会级联候选/route event/attempt，独立的 transition 历史不受影响。

## 9. Migration 规划

新增：

```text
backend/src/main/resources/db/migration/
└── V6__model_scheduling_diagnostics.sql
```

V6 一次创建五张内聚诊断表和索引，不修改 Phase 1 业务表。`knowledge_base.embedding_model` 不做 Schema 变更；现有值保留为内部 identity key，不把字符串本身当作“向量空间完全等价”的证明。模型目录必须为该 key 固定 artifact/version、预处理 fingerprint 和 dimensions；无法证明等价的 legacy identity 只允许绑定一个 deployment，不做危险 backfill，也不允许跨 Provider 故障转移。

V6 上线前后的兼容性：

- 旧应用看不到新表，不受影响；
- 新应用在 V6 未执行时诊断 writer 报不可用，但主模型能力仍可工作；
- 回滚新应用时五张空闲诊断表可保留；需要清理可手工 drop，不影响知识库表；
- 不写 down migration，沿用项目当前 Flyway 约定。
