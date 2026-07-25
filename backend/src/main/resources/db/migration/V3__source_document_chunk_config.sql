-- 补上 source_document 缺失的分块配置三列。
--
-- 为什么是漏的：api.md §3 的上传接口一直接受 chunkStrategy / chunkSize / overlap，
-- 文档响应体也回传 chunkConfig，示例里不同文档用了不同取值（1000/100 与 800/80）——
-- 设计意图明确是"每文档可配"。但 V2 建表时没有对应的列，data-model.md §3.2 同样漏了。
-- 定时同步重新分块时必须沿用文档原本的策略，所以这三列是必需的，不能退化成全局配置。
--
-- 为什么新开 V3 而不是改 V2：V2 已经实际执行过，改它会让 Flyway 校验和不一致。
-- 已执行的迁移不可变，正是 Flyway 的价值所在。
--
-- 列名用 chunk_overlap 而不是 overlap：后者与 SQL 的 overlaps 谓词形近，容易读错。
alter table source_document
    add column chunk_strategy varchar(16) not null default 'RECURSIVE',
    add column chunk_size     integer     not null default 1000,
    add column chunk_overlap  integer     not null default 100;

alter table source_document
    add constraint ck_source_document_chunk_strategy
        check (chunk_strategy in ('FIXED_SIZE', 'RECURSIVE')),
    -- overlap >= chunkSize 会让分块算法不终止（步进 <= 0），在最底层也挡一道。
    add constraint ck_source_document_chunk_params
        check (chunk_size > 0 and chunk_overlap >= 0 and chunk_overlap < chunk_size);
