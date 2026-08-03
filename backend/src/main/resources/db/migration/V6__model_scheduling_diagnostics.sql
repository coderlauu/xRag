-- Phase 2 大模型调度引擎的完成态诊断 Schema。
--
-- 五张表只保存调度事实，不保存 Prompt、文档、输出、工具参数、密钥或 Provider 错误体。
-- 它们是有强制保留期的运维事实，后续由保留任务物理删除，不使用业务表的逻辑删除列。

create table ai_logical_call (
    id                            uuid primary key,
    request_id                    varchar(128) not null,
    capability                    varchar(24)  not null,
    service_tier                  varchar(16),
    required_model_identity       varchar(160),
    stream                        boolean      not null,
    policy_version                varchar(64)  not null,
    config_fingerprint            varchar(64)  not null,

    outcome                       varchar(24)  not null,
    actual_provider_id            varchar(96),
    actual_deployment_id          varchar(128),
    actual_model_identity         varchar(160),
    provider_request_id_masked    varchar(160),
    normalized_error_type         varchar(48),
    failure_phase                 varchar(48),
    termination_reason            varchar(64)  not null,
    http_status                   integer,
    finish_reason                 varchar(64),

    attempt_count                 integer      not null,
    retry_count                   integer      not null,
    failover_count                integer      not null,
    mixed_execution               boolean      not null,
    contributing_deployment_count integer      not null,
    committed                     boolean      not null,
    rerank_applied                boolean,
    degraded_reason               varchar(64),

    input_token_count             integer,
    output_token_count            integer,
    embedding_input_count         integer,
    embedding_batch_count         integer,
    embedding_dimensions          integer,
    rerank_document_count         integer,
    rerank_top_n                  integer,
    stream_event_count            integer,

    route_duration_ms             bigint,
    first_upstream_event_ms       bigint,
    first_meaningful_chunk_ms     bigint,
    downstream_commit_ms          bigint,
    total_duration_ms             bigint      not null,
    started_at                    timestamptz not null,
    completed_at                  timestamptz not null,
    diagnostic_schema_version     integer     not null default 1,

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
