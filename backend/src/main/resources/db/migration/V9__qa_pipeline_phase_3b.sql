-- Phase 3B 检索与工具（tech/qa-pipeline/data-model.md §13~§17、ADR 0008）。
-- 只新建表与索引：不修改 V7/V8 任何列，不修改 qa_turn.status 的 check，
-- 也不动 Phase 1 的 document_chunk / document_chunk_embedding。

-- 关键词派生索引表。形态与 document_chunk_embedding 逐条对齐（同一身份键、物理删除、
-- 同样三条不变量），对齐是刻意的：两张表由同一处写入、同增同删，形态差异会变成"漏写一张"
-- 的温床，而漏写没有任何报错，症状只是关键词通道悄悄少召回。
--   1. 只做物理删除，无 deleted 列；
--   2. 每行都对应一个未删除、已启用、且所属文档也未删除已启用的分块；
--   3. chunk_id 作主键 —— 一个分块最多一条关键词索引。
-- tsv 由应用侧切词后写入：to_tsvector('simple', '退货 政策 材料')。用 'simple' 是因为文本
-- 进库前已经切好词，不需要 Postgres 再做语言处理 —— 它也做不了中文。
create table document_chunk_keyword (
    chunk_id    bigint      primary key references document_chunk (id),
    kb_id       bigint      not null references knowledge_base (id),
    doc_id      bigint      not null references source_document (id),
    tsv         tsvector    not null,
    create_time timestamptz not null default now()
);

create index idx_chunk_keyword_kb on document_chunk_keyword (kb_id);
create index idx_chunk_keyword_tsv on document_chunk_keyword using gin (tsv);

-- 一个 turn 一行的 3B 阶段状态。
-- 不复用 qa_turn.status：3A 冻结了 ACTIONS_READY 是理解阶段的稳定终态，把它降级成中间态
-- 会让所有 3A 历史 turn 的语义随代码改变。没有这一行 = 这个 turn 从未进过 3B，
-- 不需要给旧数据编造状态。
create table qa_turn_retrieval (
    turn_id        uuid        primary key references qa_turn (id),
    status         varchar(24) not null,
    partial        boolean     not null default false,
    rerank_applied boolean     not null default false,
    degradation    varchar(48),
    channel_hits   integer     not null default 0,
    after_dedup    integer     not null default 0,
    after_rerank   integer     not null default 0,
    started_at     timestamptz not null default now(),
    completed_at   timestamptz,
    -- NO_RESULT 与 RETRIEVAL_FAILED 是两个状态：查了没有 vs 没查成。合并会让用户做错
    -- 下一步（PRD §12.3 / §12.4）。
    constraint ck_qa_retrieval_status check (status in (
        'RETRIEVING', 'RETRIEVED', 'NO_RESULT', 'RETRIEVAL_FAILED')),
    -- 与 com.app.ai 的 RerankResult 构造期不变量同形。端口那边靠 Java 断言，这边靠数据库：
    -- "降级了却带着分数"一旦落库就再也分辨不出来。
    constraint ck_qa_retrieval_rerank check (rerank_applied = (degradation is null)),
    constraint ck_qa_retrieval_funnel check (
        channel_hits >= after_dedup and after_dedup >= after_rerank),
    constraint ck_qa_retrieval_counts check (
        channel_hits >= 0 and after_dedup >= 0 and after_rerank >= 0)
);

-- 一条来源一行。按 (turn_id, subquestion_id, action_id) 关联，兑现架构 §15 的承诺：
-- 3B 结果引用动作，不塞回意图快照表。
-- 不存分数、不存 chunk 正文 —— 既然 PRD §12.2 冻结了"不展示任何原始分数"，
-- 存下来只会诱使某天有人把它渲染出去；排序信息由 ordinal 承载。
create table qa_retrieval_source (
    id             uuid        primary key,
    turn_id        uuid        not null references qa_turn (id),
    subquestion_id uuid        not null references qa_subquestion (id),
    action_id      uuid        not null references qa_retrieval_action (id),
    ordinal        smallint    not null,
    chunk_id       bigint      not null,
    kb_id          bigint      not null,
    doc_id         bigint      not null,
    origin_channel varchar(24) not null,
    created_at     timestamptz not null default now(),
    constraint uq_qa_source_ordinal unique (turn_id, ordinal),
    -- 产品语义的数据库表达：同一片段在一轮里只展示一次（PRD §12.2）。放在约束上而不是
    -- 只靠去重代码，是因为"命中两次显得更可信"这个错误一旦出现在界面上，用户察觉不到。
    constraint uq_qa_source_chunk unique (turn_id, chunk_id),
    constraint ck_qa_source_ordinal check (ordinal >= 0),
    constraint ck_qa_source_channel check (origin_channel in (
        'INTENT_TARGETED', 'KEYWORD', 'GLOBAL_VECTOR'))
);

create index idx_qa_source_turn on qa_retrieval_source (turn_id, ordinal);

-- 每个动作对应的检索目标一行，承载部分失败的显式状态。
-- partial 由本表算出（存在 FAILED 且存在 SUCCEEDED），不由来源条数倒推（PRD §12.4）。
create table qa_retrieval_target (
    id           uuid        primary key,
    turn_id      uuid        not null references qa_turn (id),
    action_id    uuid        not null references qa_retrieval_action (id),
    kb_id        bigint,
    status       varchar(16) not null,
    failure_code varchar(48),
    latency_ms   integer,
    created_at   timestamptz not null default now(),
    constraint uq_qa_target unique (turn_id, action_id),
    constraint ck_qa_target_status check (status in ('SUCCEEDED', 'FAILED')),
    -- failure_code 是归一化闭集，不是异常 message。
    constraint ck_qa_target_failure check ((status = 'FAILED') = (failure_code is not null)),
    constraint ck_qa_target_latency check (latency_ms is null or latency_ms >= 0)
);

create index idx_qa_target_turn on qa_retrieval_target (turn_id);

-- 工具调用。arguments_json 存的是复核通过后的参数（白名单 + Schema 校验后），不是模型
-- 原始输出：编造的字段既不该执行，也不该被当成历史记录展示。
-- result_text 是不可信内容，按纯文本存取。failure_code 是归一化闭集 —— 异常 message
-- 不入库，它可能带内部主机名、端口与堆栈片段（PRD §12.6）。
create table qa_tool_call (
    id             uuid        primary key,
    turn_id        uuid        not null references qa_turn (id),
    ordinal        smallint    not null,
    tool_name      varchar(64) not null,
    side_effect    boolean     not null,
    status         varchar(20) not null,
    arguments_json text,
    result_text    text,
    failure_code   varchar(48),
    latency_ms     integer,
    confirmed_at   timestamptz,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    constraint uq_qa_tool_ordinal unique (turn_id, ordinal),
    constraint ck_qa_tool_ordinal check (ordinal >= 0),
    constraint ck_qa_tool_status check (status in (
        'PENDING_CONFIRM', 'RUNNING', 'SUCCEEDED', 'FAILED',
        'CANCELLED', 'UNAVAILABLE', 'ABORTED')),
    -- "确认前一律不执行"的数据库表达（PRD §12.7）。有副作用的工具只要到达 RUNNING 及之后
    -- 就必须有确认时间戳；没有确认时间戳却在跑，插不进去。价值在于即使某天服务层写错一个
    -- 分支，数据库也不让它落地。
    constraint ck_qa_tool_confirm check (
        (confirmed_at is not null) = (side_effect and status in ('RUNNING', 'SUCCEEDED', 'FAILED'))),
    constraint ck_qa_tool_failure check ((status = 'FAILED') = (failure_code is not null)),
    -- 工具压根不在白名单时既没有复核过的参数也没有结果。
    constraint ck_qa_tool_unavailable check (
        status <> 'UNAVAILABLE' or (arguments_json is null and result_text is null)),
    constraint ck_qa_tool_latency check (latency_ms is null or latency_ms >= 0)
);

create index idx_qa_tool_turn on qa_tool_call (turn_id, ordinal);
