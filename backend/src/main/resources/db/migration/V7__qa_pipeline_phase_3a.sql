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

create table qa_intent_tree_head (
    singleton_id        smallint    primary key,
    current_revision_id uuid,
    current_revision_no bigint      not null default 0,
    updated_at          timestamptz not null default now(),
    constraint ck_qa_intent_tree_singleton check (singleton_id = 1),
    constraint ck_qa_intent_head_revision_no check (current_revision_no >= 0),
    constraint ck_qa_intent_head_pointer check (
        (current_revision_no = 0 and current_revision_id is null)
        or (current_revision_no > 0 and current_revision_id is not null))
);

insert into qa_intent_tree_head (singleton_id) values (1);

create table qa_intent_tree_revision (
    id                  uuid primary key,
    revision_no         bigint      not null unique,
    parent_revision_id  uuid references qa_intent_tree_revision(id),
    change_type         varchar(24) not null,
    changed_node_id     uuid,
    node_count          integer     not null,
    enabled_leaf_count  integer     not null,
    created_at          timestamptz not null default now(),
    constraint ck_qa_intent_revision_no check (revision_no > 0),
    constraint ck_qa_intent_change check (change_type in (
        'CREATE','UPDATE','ENABLE_SUBTREE','DISABLE_SUBTREE','DELETE_SUBTREE')),
    constraint ck_qa_intent_counts check (
        node_count >= 0 and enabled_leaf_count >= 0 and enabled_leaf_count <= node_count)
);

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
    constraint ck_qa_intent_system_action check (
        system_action is null or system_action in ('GREETING','ASSISTANT_IDENTITY','CAPABILITY_HELP')),
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

create table qa_turn (
    id                       uuid primary key,
    conversation_id          uuid         not null references qa_conversation(id),
    client_request_id        uuid         not null,
    user_message_id          uuid         not null,
    status                   varchar(40)  not null,
    intent_revision_id       uuid references qa_intent_tree_revision(id),
    rewrite_mode             varchar(24),
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
    constraint ck_qa_turn_degradation_codes check (jsonb_typeof(degradation_codes) = 'array'),
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
        'USER_QUERY','CLARIFICATION_CHOICE','CLARIFICATION_GUIDANCE','SYSTEM_NOTICE')),
    constraint ck_qa_message_content check (char_length(btrim(content)) between 1 and 20000),
    constraint ck_qa_message_deleted check (deleted = (deleted_at is not null))
);

create index idx_qa_message_conversation
    on qa_message (conversation_id, sequence_no desc) where deleted = false;

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
        score <> 'NaN'::double precision
        and score <> 'Infinity'::double precision
        and score <> '-Infinity'::double precision
        and score between 0.0 and 1.0),
    constraint ck_qa_intent_match_rank check (rank between 1 and 3),
    constraint ck_qa_intent_match_target check (
        (node_type = 'KB' and knowledge_base_id is not null and system_action is null)
        or (node_type = 'SYSTEM' and knowledge_base_id is null and system_action is not null)),
    constraint ck_qa_intent_match_deleted check (deleted = (deleted_at is not null))
);

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
    constraint ck_qa_clarification_label check (char_length(btrim(label)) between 1 and 1000),
    constraint ck_qa_clarification_deleted check (deleted = (deleted_at is not null))
);

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
    constraint ck_qa_action_ordinal check (ordinal between 1 and 4),
    constraint ck_qa_action_type check (action_type in (
        'KB_TARGETED','GLOBAL_FALLBACK','SYSTEM','NO_MATCH')),
    constraint ck_qa_action_target check (
        (action_type = 'KB_TARGETED' and intent_node_id is not null and knowledge_base_id is not null and system_action is null)
        or (action_type = 'SYSTEM' and intent_node_id is not null and knowledge_base_id is null and system_action is not null)
        or (action_type in ('GLOBAL_FALLBACK','NO_MATCH') and intent_node_id is null and knowledge_base_id is null and system_action is null)),
    constraint ck_qa_action_deleted check (deleted = (deleted_at is not null))
);

create table qa_turn_stage (
    id                 uuid         primary key,
    turn_id            uuid         not null references qa_turn(id),
    stage              varchar(32)  not null,
    work_unit_ordinal  smallint     not null default 0,
    outcome            varchar(16)  not null,
    reason_code        varchar(64),
    logical_call_id    uuid,
    intent_revision_id uuid references qa_intent_tree_revision(id),
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
