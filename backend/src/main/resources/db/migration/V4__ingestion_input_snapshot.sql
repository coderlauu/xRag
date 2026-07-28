-- 入库任务必须绑定创建任务时确定的原文版本，不能在异步执行时重新读取
-- source_document.file_key。否则定时同步覆盖原对象后，一旦解析或向量化失败，
-- 就会出现“原文是新版、分块和向量还是旧版”的混合状态。
alter table ingestion_run
    add column input_revision           integer,
    add column input_file_key           varchar(512),
    add column input_content_hash       varchar(64),
    add column input_file_size          bigint,
    add column input_content_type       varchar(128),
    add column input_http_etag          varchar(256),
    add column input_http_last_modified varchar(128);

-- 已存在的排队任务继续处理它创建迁移时文档正在使用的版本。历史完成记录是否精确
-- 绑定已不可恢复，但补齐这些值可以让统一的读模型兼容迁移前数据。
update ingestion_run r
   set input_revision = d.revision,
       input_file_key = d.file_key,
       input_content_hash = d.content_hash,
       input_file_size = d.file_size,
       input_content_type = d.content_type,
       input_http_etag = d.http_etag,
       input_http_last_modified = d.http_last_modified
  from source_document d
 where d.id = r.doc_id
   and r.input_file_key is null;
