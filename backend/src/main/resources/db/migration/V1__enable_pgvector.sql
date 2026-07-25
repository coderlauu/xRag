-- pgvector 是 xrag 存储层的正式向量存储决策（见 docs/adr/），业务表随后续
-- 阶段的迁移文件逐步添加，本文件只负责让扩展在数据库里可用。
CREATE EXTENSION IF NOT EXISTS vector;
