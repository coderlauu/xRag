-- Phase 3C 生成任务与助手答案（tech/qa-pipeline/data-model.md §18~§21）。
-- 只做向前扩展；旧 turn/message 数据不改写。

alter table qa_turn drop constraint ck_qa_turn_status;
alter table qa_turn add constraint ck_qa_turn_status check (status in (
    'SAVED', 'PROCESSING', 'ACTIONS_READY', 'WAITING_CLARIFICATION',
    'REJECTED_TOO_MANY_TOPICS', 'QUEUED', 'GENERATING', 'COMPLETED',
    'STOPPED', 'FAILED', 'TIMED_OUT'));

alter table qa_turn drop constraint ck_qa_turn_terminal_time;
alter table qa_turn add constraint ck_qa_turn_terminal_time check (
    (status in ('SAVED', 'PROCESSING', 'QUEUED', 'GENERATING') and completed_at is null)
    or (status not in ('SAVED', 'PROCESSING', 'QUEUED', 'GENERATING') and completed_at is not null));

drop index uq_qa_turn_one_active;
create unique index uq_qa_turn_one_active
    on qa_turn (conversation_id)
    where deleted = false and status in ('SAVED', 'PROCESSING', 'QUEUED', 'GENERATING');

alter table qa_turn_stage drop constraint ck_qa_stage_name;
alter table qa_turn_stage add constraint ck_qa_stage_name check (stage in (
    'SAVE', 'HISTORY', 'SUMMARY', 'REWRITE', 'INTENT_SCORE', 'AMBIGUITY',
    'ACTION_MAP', 'FINALIZE', 'PROMPT', 'QUEUE', 'GENERATE', 'PERSIST'));

alter table qa_message add column incomplete boolean not null default false;
alter table qa_message drop constraint ck_qa_message_type;
alter table qa_message add constraint ck_qa_message_type check (message_type in (
    'USER_QUERY', 'CLARIFICATION_CHOICE', 'CLARIFICATION_GUIDANCE',
    'SYSTEM_NOTICE', 'ASSISTANT_ANSWER'));
alter table qa_message add constraint ck_qa_message_incomplete check (
    not incomplete or message_type = 'ASSISTANT_ANSWER');
alter table qa_message add constraint ck_qa_message_assistant_answer check (
    message_type <> 'ASSISTANT_ANSWER' or role = 'ASSISTANT');

create unique index uq_qa_message_assistant_answer
    on qa_message (turn_id)
    where message_type = 'ASSISTANT_ANSWER' and deleted = false;

create table qa_generation_task (
    id                    uuid        primary key,
    turn_id               uuid        not null unique references qa_turn(id),
    conversation_id       uuid        not null references qa_conversation(id),
    status                varchar(24) not null,
    terminal_reason       varchar(64),
    queued_at             timestamptz,
    generation_started_at timestamptz,
    completed_at          timestamptz,
    queued_once           boolean     not null default false,
    answer_char_count     integer     not null default 0,
    assistant_message_id  uuid references qa_message(id),
    answer_saved          boolean,
    logical_call_id       uuid,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    constraint ck_qa_generation_status check (status in (
        'QUEUED', 'GENERATING', 'COMPLETED', 'STOPPED', 'FAILED', 'TIMED_OUT')),
    constraint ck_qa_generation_terminal check (
        (status in ('QUEUED', 'GENERATING') and completed_at is null and terminal_reason is null)
        or (status in ('COMPLETED', 'STOPPED', 'FAILED', 'TIMED_OUT') and completed_at is not null)),
    constraint ck_qa_generation_count check (answer_char_count >= 0),
    constraint ck_qa_generation_saved check (
        (status in ('QUEUED', 'GENERATING', 'TIMED_OUT')
            and answer_saved is null and assistant_message_id is null)
        or (status in ('COMPLETED', 'STOPPED', 'FAILED')
            and answer_saved is not null
            and answer_saved = (assistant_message_id is not null)))
);

create index idx_qa_generation_active
    on qa_generation_task (created_at, id)
    where status in ('QUEUED', 'GENERATING');
