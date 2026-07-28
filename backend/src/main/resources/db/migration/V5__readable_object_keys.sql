-- 对象 key 的业务定位信息必须是稳定数据，不能在每次上传时临时从可变名称推导。
alter table knowledge_base add column storage_alias varchar(64);

update knowledge_base
   set storage_alias = left(trim(both '-' from
       regexp_replace(lower(trim(name)), '[^[:alnum:]]+', '-', 'g')), 48);

update knowledge_base
   set storage_alias = 'kb-' || id
 where storage_alias is null or storage_alias = '';

alter table knowledge_base alter column storage_alias set not null;

-- UUID 由应用在上传前生成，因此不依赖数据库扩展；迁移前文档用稳定的 legacy-id 回填。
alter table source_document add column storage_object_id varchar(36);

update source_document set storage_object_id = 'legacy-' || id;

alter table source_document alter column storage_object_id set not null;
create unique index uk_source_document_storage_object_id
    on source_document (storage_object_id);
