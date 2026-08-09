-- 工具裁决的幂等键。
--
-- V9 漏了它：API §10 冻结了 "同 clientRequestId 重复 CONFIRM 只执行一次"，而这条对**有副作用的
-- 工具是硬要求**——网络抖动导致的重试不该变成两张工单。只靠状态 CAS 不够：CAS 能保证只执行一次，
-- 但第二次请求会拿到 409，而契约说的是拿到同一个结果。
--
-- 用向前修复迁移而不是改写 V9：V9 已经应用到本地开发库与测试 schema（data-model §17）。
alter table qa_tool_call add column confirm_request_id uuid;

-- 一个裁决键只能对应一次裁决。加在 (turn_id, confirm_request_id) 而不是全局：
-- clientRequestId 由客户端在一次对话里生成，跨 turn 复用不是错误。
create unique index uq_qa_tool_confirm_request
    on qa_tool_call (turn_id, confirm_request_id)
    where confirm_request_id is not null;

-- 只有被裁决过的行才有键；未裁决的（待确认、不可用、已中止）不该有。
alter table qa_tool_call add constraint ck_qa_tool_confirm_request check (
    confirm_request_id is null
    or status in ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'));
