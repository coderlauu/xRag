# 02 V12 评测事实 Schema

- `status`: blocked-on-step-c-review
- `depends_on`: Step C
- 新增 data-model 表、约束、索引与迁移测试；不修改 V7～V11。
- 验收：状态/外键/JSON/正文清理约束；`git diff --check` 与后端 verify。
