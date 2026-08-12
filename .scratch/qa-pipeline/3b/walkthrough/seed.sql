-- 3B 走查演示数据（ui-spec §13.11 的九个场景）。
-- 直接造落库结果，不走 3A 理解链路——走查要看的是界面表达，而 3A 需要真实 Chat 调用会计费。
begin;

delete from qa_retrieval_source where turn_id in (select id from qa_turn where conversation_id in (select id from qa_conversation where title like '走查%'));
delete from qa_retrieval_target where turn_id in (select id from qa_turn where conversation_id in (select id from qa_conversation where title like '走查%'));
delete from qa_turn_retrieval where turn_id in (select id from qa_turn where conversation_id in (select id from qa_conversation where title like '走查%'));
delete from qa_tool_call where turn_id in (select id from qa_turn where conversation_id in (select id from qa_conversation where title like '走查%'));
delete from qa_retrieval_action where turn_id in (select id from qa_turn where conversation_id in (select id from qa_conversation where title like '走查%'));
delete from qa_subquestion where turn_id in (select id from qa_turn where conversation_id in (select id from qa_conversation where title like '走查%'));
delete from qa_message where turn_id in (select id from qa_turn where conversation_id in (select id from qa_conversation where title like '走查%'));
delete from qa_turn where conversation_id in (select id from qa_conversation where title like '走查%');
delete from qa_conversation where title like '走查%';
delete from document_chunk_keyword where kb_id in (select id from knowledge_base where name like '走查-%');
delete from document_chunk_embedding where kb_id in (select id from knowledge_base where name like '走查-%');
delete from document_chunk where kb_id in (select id from knowledge_base where name like '走查-%');
delete from source_document where kb_id in (select id from knowledge_base where name like '走查-%');
delete from knowledge_base where name like '走查-%';

-- 两个知识库：一个正常、一个用来演示"没查成"
insert into knowledge_base (id, name, storage_alias, embedding_model, embedding_dimensions)
values (900001, '走查-售后服务知识库', 'walkthrough-aftersales', 'test', 1024),
       (900002, '走查-海外仓运营手册', 'walkthrough-overseas', 'test', 1024);

insert into source_document (id, kb_id, name, source_type, storage_object_id)
values (900101, 900001, '退货与退款操作手册 v3.docx', 'FILE', 'walkthrough-doc-1'),
       (900102, 900001, '已删除的历史手册 v1.docx', 'FILE', 'walkthrough-doc-2');

insert into document_chunk (id, kb_id, doc_id, revision, chunk_index, content, char_count, token_count, content_hash)
values (900201, 900001, 900101, 1, 12, '退货需要提供订单号与凭证照片，签收后 7 日内可发起。', 25, 1, repeat('0',64)),
       (900202, 900001, 900101, 1, 27, '退款到账时间：银行卡通常三到五个工作日。', 20, 1, repeat('0',64)),
       (900203, 900001, 900102, 1, 4,  '这一条属于已删除文档，用来演示 documentDeleted。', 24, 1, repeat('0',64));

-- 文档 900102 逻辑删除，用来演示"文档已删除"且禁用跳转
update source_document set deleted = true where id = 900102;

insert into qa_conversation (id, title, last_active_at) values
 ('aaaa0001-0000-0000-0000-000000000001', '走查 1 正常来源', now()),
 ('aaaa0002-0000-0000-0000-000000000002', '走查 2 未精排', now()),
 ('aaaa0003-0000-0000-0000-000000000003', '走查 3 部分失败', now()),
 ('aaaa0004-0000-0000-0000-000000000004', '走查 4 无结果', now()),
 ('aaaa0005-0000-0000-0000-000000000005', '走查 5 工具待确认', now()),
 ('aaaa0006-0000-0000-0000-000000000006', '走查 6 工具已完成', now()),
 ('aaaa0007-0000-0000-0000-000000000007', '走查 7 工具失败', now()),
 ('aaaa0008-0000-0000-0000-000000000008', '走查 8 工具不可用', now()),
 ('aaaa0009-0000-0000-0000-000000000009', '走查 9 参数抽取中止', now());

insert into qa_turn (id, conversation_id, client_request_id, user_message_id, status, terminal_reason, completed_at)
values ('bbbb0001-0000-0000-0000-000000000001', 'aaaa0001-0000-0000-0000-000000000001', gen_random_uuid(), 'eeee0001-0000-0000-0000-000000000001', 'ACTIONS_READY', 'ACTIONS_MAPPED', now());
insert into qa_message (id, conversation_id, turn_id, sequence_no, role, message_type, content)
values ('eeee0001-0000-0000-0000-000000000001', 'aaaa0001-0000-0000-0000-000000000001', 'bbbb0001-0000-0000-0000-000000000001', 1, 'USER', 'USER_QUERY', '退货需要哪些材料？');
insert into qa_subquestion (id, turn_id, ordinal, text, source)
values ('cccc0001-0000-0000-0000-000000000001', 'bbbb0001-0000-0000-0000-000000000001', 0, '退货需要哪些材料', 'MODEL');
insert into qa_retrieval_action (id, turn_id, subquestion_id, ordinal, action_type, intent_node_id, knowledge_base_id, reason_code)
values ('dddd0001-0000-0000-0000-000000000001', 'bbbb0001-0000-0000-0000-000000000001', 'cccc0001-0000-0000-0000-000000000001', 1, 'KB_TARGETED', gen_random_uuid(), 900001, 'SCORE_AT_OR_ABOVE_TARGETED');
insert into qa_turn_retrieval (turn_id, status, partial, rerank_applied, degradation, channel_hits, after_dedup, after_rerank, completed_at)
values ('bbbb0001-0000-0000-0000-000000000001', 'RETRIEVED', false, true, null, 2, 2, 2, now());
insert into qa_retrieval_source (id, turn_id, subquestion_id, action_id, ordinal, chunk_id, kb_id, doc_id, origin_channel)
values (gen_random_uuid(), 'bbbb0001-0000-0000-0000-000000000001', 'cccc0001-0000-0000-0000-000000000001', 'dddd0001-0000-0000-0000-000000000001', 0, 900201, 900001, 900101, 'INTENT_TARGETED');
insert into qa_retrieval_source (id, turn_id, subquestion_id, action_id, ordinal, chunk_id, kb_id, doc_id, origin_channel)
values (gen_random_uuid(), 'bbbb0001-0000-0000-0000-000000000001', 'cccc0001-0000-0000-0000-000000000001', 'dddd0001-0000-0000-0000-000000000001', 1, 900203, 900001, 900102, 'INTENT_TARGETED');
insert into qa_retrieval_target (id, turn_id, action_id, kb_id, status, failure_code, latency_ms)
values (gen_random_uuid(), 'bbbb0001-0000-0000-0000-000000000001', 'dddd0001-0000-0000-0000-000000000001', 900001, 'SUCCEEDED', null, 120);

insert into qa_turn (id, conversation_id, client_request_id, user_message_id, status, terminal_reason, completed_at)
values ('bbbb0002-0000-0000-0000-000000000002', 'aaaa0002-0000-0000-0000-000000000002', gen_random_uuid(), 'eeee0002-0000-0000-0000-000000000002', 'ACTIONS_READY', 'ACTIONS_MAPPED', now());
insert into qa_message (id, conversation_id, turn_id, sequence_no, role, message_type, content)
values ('eeee0002-0000-0000-0000-000000000002', 'aaaa0002-0000-0000-0000-000000000002', 'bbbb0002-0000-0000-0000-000000000002', 1, 'USER', 'USER_QUERY', '退货需要哪些材料？');
insert into qa_subquestion (id, turn_id, ordinal, text, source)
values ('cccc0002-0000-0000-0000-000000000002', 'bbbb0002-0000-0000-0000-000000000002', 0, '退货需要哪些材料', 'MODEL');
insert into qa_retrieval_action (id, turn_id, subquestion_id, ordinal, action_type, intent_node_id, knowledge_base_id, reason_code)
values ('dddd0002-0000-0000-0000-000000000002', 'bbbb0002-0000-0000-0000-000000000002', 'cccc0002-0000-0000-0000-000000000002', 1, 'KB_TARGETED', gen_random_uuid(), 900001, 'SCORE_AT_OR_ABOVE_TARGETED');
insert into qa_turn_retrieval (turn_id, status, partial, rerank_applied, degradation, channel_hits, after_dedup, after_rerank, completed_at)
values ('bbbb0002-0000-0000-0000-000000000002', 'RETRIEVED', false, false, 'RERANK_UNAVAILABLE', 1, 1, 1, now());
insert into qa_retrieval_source (id, turn_id, subquestion_id, action_id, ordinal, chunk_id, kb_id, doc_id, origin_channel)
values (gen_random_uuid(), 'bbbb0002-0000-0000-0000-000000000002', 'cccc0002-0000-0000-0000-000000000002', 'dddd0002-0000-0000-0000-000000000002', 0, 900201, 900001, 900101, 'INTENT_TARGETED');
insert into qa_retrieval_target (id, turn_id, action_id, kb_id, status, failure_code, latency_ms)
values (gen_random_uuid(), 'bbbb0002-0000-0000-0000-000000000002', 'dddd0002-0000-0000-0000-000000000002', 900001, 'SUCCEEDED', null, 120);

insert into qa_turn (id, conversation_id, client_request_id, user_message_id, status, terminal_reason, completed_at)
values ('bbbb0003-0000-0000-0000-000000000003', 'aaaa0003-0000-0000-0000-000000000003', gen_random_uuid(), 'eeee0003-0000-0000-0000-000000000003', 'ACTIONS_READY', 'ACTIONS_MAPPED', now());
insert into qa_message (id, conversation_id, turn_id, sequence_no, role, message_type, content)
values ('eeee0003-0000-0000-0000-000000000003', 'aaaa0003-0000-0000-0000-000000000003', 'bbbb0003-0000-0000-0000-000000000003', 1, 'USER', 'USER_QUERY', '退货需要哪些材料？');
insert into qa_subquestion (id, turn_id, ordinal, text, source)
values ('cccc0003-0000-0000-0000-000000000003', 'bbbb0003-0000-0000-0000-000000000003', 0, '退货需要哪些材料', 'MODEL');
insert into qa_retrieval_action (id, turn_id, subquestion_id, ordinal, action_type, intent_node_id, knowledge_base_id, reason_code)
values ('dddd0003-0000-0000-0000-000000000003', 'bbbb0003-0000-0000-0000-000000000003', 'cccc0003-0000-0000-0000-000000000003', 1, 'KB_TARGETED', gen_random_uuid(), 900001, 'SCORE_AT_OR_ABOVE_TARGETED');
insert into qa_turn_retrieval (turn_id, status, partial, rerank_applied, degradation, channel_hits, after_dedup, after_rerank, completed_at)
values ('bbbb0003-0000-0000-0000-000000000003', 'RETRIEVED', true, true, null, 1, 1, 1, now());
insert into qa_retrieval_source (id, turn_id, subquestion_id, action_id, ordinal, chunk_id, kb_id, doc_id, origin_channel)
values (gen_random_uuid(), 'bbbb0003-0000-0000-0000-000000000003', 'cccc0003-0000-0000-0000-000000000003', 'dddd0003-0000-0000-0000-000000000003', 0, 900201, 900001, 900101, 'INTENT_TARGETED');
insert into qa_retrieval_target (id, turn_id, action_id, kb_id, status, failure_code, latency_ms)
values (gen_random_uuid(), 'bbbb0003-0000-0000-0000-000000000003', 'dddd0003-0000-0000-0000-000000000003', 900001, 'SUCCEEDED', null, 120);
insert into qa_retrieval_action (id, turn_id, subquestion_id, ordinal, action_type, intent_node_id, knowledge_base_id, reason_code)
values ('dddd03ff-0000-0000-0000-000000000003', 'bbbb0003-0000-0000-0000-000000000003', 'cccc0003-0000-0000-0000-000000000003', 2, 'KB_TARGETED', gen_random_uuid(), 900002, 'SCORE_AT_OR_ABOVE_TARGETED');
insert into qa_retrieval_target (id, turn_id, action_id, kb_id, status, failure_code, latency_ms)
values (gen_random_uuid(), 'bbbb0003-0000-0000-0000-000000000003', 'dddd03ff-0000-0000-0000-000000000003', 900002, 'FAILED', 'RETRIEVAL_TIMEOUT', 9800);

insert into qa_turn (id, conversation_id, client_request_id, user_message_id, status, terminal_reason, completed_at)
values ('bbbb0004-0000-0000-0000-000000000004', 'aaaa0004-0000-0000-0000-000000000004', gen_random_uuid(), 'eeee0004-0000-0000-0000-000000000004', 'ACTIONS_READY', 'ACTIONS_MAPPED', now());
insert into qa_message (id, conversation_id, turn_id, sequence_no, role, message_type, content)
values ('eeee0004-0000-0000-0000-000000000004', 'aaaa0004-0000-0000-0000-000000000004', 'bbbb0004-0000-0000-0000-000000000004', 1, 'USER', 'USER_QUERY', '退货需要哪些材料？');
insert into qa_subquestion (id, turn_id, ordinal, text, source)
values ('cccc0004-0000-0000-0000-000000000004', 'bbbb0004-0000-0000-0000-000000000004', 0, '退货需要哪些材料', 'MODEL');
insert into qa_retrieval_action (id, turn_id, subquestion_id, ordinal, action_type, intent_node_id, knowledge_base_id, reason_code)
values ('dddd0004-0000-0000-0000-000000000004', 'bbbb0004-0000-0000-0000-000000000004', 'cccc0004-0000-0000-0000-000000000004', 1, 'KB_TARGETED', gen_random_uuid(), 900001, 'SCORE_AT_OR_ABOVE_TARGETED');
insert into qa_turn_retrieval (turn_id, status, partial, rerank_applied, degradation, channel_hits, after_dedup, after_rerank, completed_at)
values ('bbbb0004-0000-0000-0000-000000000004', 'NO_RESULT', false, true, null, 1, 1, 0, now());
insert into qa_retrieval_target (id, turn_id, action_id, kb_id, status, failure_code, latency_ms)
values (gen_random_uuid(), 'bbbb0004-0000-0000-0000-000000000004', 'dddd0004-0000-0000-0000-000000000004', 900001, 'SUCCEEDED', null, 120);

insert into qa_turn (id, conversation_id, client_request_id, user_message_id, status, terminal_reason, completed_at)
values ('bbbb0005-0000-0000-0000-000000000005', 'aaaa0005-0000-0000-0000-000000000005', gen_random_uuid(), 'eeee0005-0000-0000-0000-000000000005', 'ACTIONS_READY', 'ACTIONS_MAPPED', now());
insert into qa_message (id, conversation_id, turn_id, sequence_no, role, message_type, content)
values ('eeee0005-0000-0000-0000-000000000005', 'aaaa0005-0000-0000-0000-000000000005', 'bbbb0005-0000-0000-0000-000000000005', 1, 'USER', 'USER_QUERY', '退货需要哪些材料？');
insert into qa_subquestion (id, turn_id, ordinal, text, source)
values ('cccc0005-0000-0000-0000-000000000005', 'bbbb0005-0000-0000-0000-000000000005', 0, '退货需要哪些材料', 'MODEL');
insert into qa_retrieval_action (id, turn_id, subquestion_id, ordinal, action_type, intent_node_id, knowledge_base_id, reason_code)
values ('dddd0005-0000-0000-0000-000000000005', 'bbbb0005-0000-0000-0000-000000000005', 'cccc0005-0000-0000-0000-000000000005', 1, 'KB_TARGETED', gen_random_uuid(), 900001, 'SCORE_AT_OR_ABOVE_TARGETED');
insert into qa_tool_call (id, turn_id, ordinal, tool_name, side_effect, status, arguments_json, result_text, failure_code, latency_ms, confirmed_at)
values (gen_random_uuid(), 'bbbb0005-0000-0000-0000-000000000005', 0, 'submit-return-ticket', true, 'PENDING_CONFIRM', '{"orderNo":"ORD-20260806-1183","reason":"质量问题"}', null, null, null, null);

insert into qa_turn (id, conversation_id, client_request_id, user_message_id, status, terminal_reason, completed_at)
values ('bbbb0006-0000-0000-0000-000000000006', 'aaaa0006-0000-0000-0000-000000000006', gen_random_uuid(), 'eeee0006-0000-0000-0000-000000000006', 'ACTIONS_READY', 'ACTIONS_MAPPED', now());
insert into qa_message (id, conversation_id, turn_id, sequence_no, role, message_type, content)
values ('eeee0006-0000-0000-0000-000000000006', 'aaaa0006-0000-0000-0000-000000000006', 'bbbb0006-0000-0000-0000-000000000006', 1, 'USER', 'USER_QUERY', '退货需要哪些材料？');
insert into qa_subquestion (id, turn_id, ordinal, text, source)
values ('cccc0006-0000-0000-0000-000000000006', 'bbbb0006-0000-0000-0000-000000000006', 0, '退货需要哪些材料', 'MODEL');
insert into qa_retrieval_action (id, turn_id, subquestion_id, ordinal, action_type, intent_node_id, knowledge_base_id, reason_code)
values ('dddd0006-0000-0000-0000-000000000006', 'bbbb0006-0000-0000-0000-000000000006', 'cccc0006-0000-0000-0000-000000000006', 1, 'KB_TARGETED', gen_random_uuid(), 900001, 'SCORE_AT_OR_ABOVE_TARGETED');
insert into qa_turn_retrieval (turn_id, status, partial, rerank_applied, degradation, channel_hits, after_dedup, after_rerank, completed_at)
values ('bbbb0006-0000-0000-0000-000000000006', 'RETRIEVED', false, true, null, 1, 1, 1, now());
insert into qa_retrieval_source (id, turn_id, subquestion_id, action_id, ordinal, chunk_id, kb_id, doc_id, origin_channel)
values (gen_random_uuid(), 'bbbb0006-0000-0000-0000-000000000006', 'cccc0006-0000-0000-0000-000000000006', 'dddd0006-0000-0000-0000-000000000006', 0, 900202, 900001, 900101, 'INTENT_TARGETED');
insert into qa_retrieval_target (id, turn_id, action_id, kb_id, status, failure_code, latency_ms)
values (gen_random_uuid(), 'bbbb0006-0000-0000-0000-000000000006', 'dddd0006-0000-0000-0000-000000000006', 900001, 'SUCCEEDED', null, 120);
insert into qa_tool_call (id, turn_id, ordinal, tool_name, side_effect, status, arguments_json, result_text, failure_code, latency_ms, confirmed_at)
values (gen_random_uuid(), 'bbbb0006-0000-0000-0000-000000000006', 0, 'submit-return-ticket', true, 'SUCCEEDED', '{"orderNo":"ORD-20260806-1183","reason":"质量问题"}', '工单已创建：RT-20260811-0042，预计 1 个工作日内处理。', null, 1240, now());

insert into qa_turn (id, conversation_id, client_request_id, user_message_id, status, terminal_reason, completed_at)
values ('bbbb0007-0000-0000-0000-000000000007', 'aaaa0007-0000-0000-0000-000000000007', gen_random_uuid(), 'eeee0007-0000-0000-0000-000000000007', 'ACTIONS_READY', 'ACTIONS_MAPPED', now());
insert into qa_message (id, conversation_id, turn_id, sequence_no, role, message_type, content)
values ('eeee0007-0000-0000-0000-000000000007', 'aaaa0007-0000-0000-0000-000000000007', 'bbbb0007-0000-0000-0000-000000000007', 1, 'USER', 'USER_QUERY', '退货需要哪些材料？');
insert into qa_subquestion (id, turn_id, ordinal, text, source)
values ('cccc0007-0000-0000-0000-000000000007', 'bbbb0007-0000-0000-0000-000000000007', 0, '退货需要哪些材料', 'MODEL');
insert into qa_retrieval_action (id, turn_id, subquestion_id, ordinal, action_type, intent_node_id, knowledge_base_id, reason_code)
values ('dddd0007-0000-0000-0000-000000000007', 'bbbb0007-0000-0000-0000-000000000007', 'cccc0007-0000-0000-0000-000000000007', 1, 'KB_TARGETED', gen_random_uuid(), 900001, 'SCORE_AT_OR_ABOVE_TARGETED');
insert into qa_tool_call (id, turn_id, ordinal, tool_name, side_effect, status, arguments_json, result_text, failure_code, latency_ms, confirmed_at)
values (gen_random_uuid(), 'bbbb0007-0000-0000-0000-000000000007', 0, 'submit-return-ticket', true, 'FAILED', '{"orderNo":"ORD-20260806-1183","reason":"质量问题"}', null, 'EXECUTION_TIMEOUT', 10000, now());

insert into qa_turn (id, conversation_id, client_request_id, user_message_id, status, terminal_reason, completed_at)
values ('bbbb0008-0000-0000-0000-000000000008', 'aaaa0008-0000-0000-0000-000000000008', gen_random_uuid(), 'eeee0008-0000-0000-0000-000000000008', 'ACTIONS_READY', 'ACTIONS_MAPPED', now());
insert into qa_message (id, conversation_id, turn_id, sequence_no, role, message_type, content)
values ('eeee0008-0000-0000-0000-000000000008', 'aaaa0008-0000-0000-0000-000000000008', 'bbbb0008-0000-0000-0000-000000000008', 1, 'USER', 'USER_QUERY', '退货需要哪些材料？');
insert into qa_subquestion (id, turn_id, ordinal, text, source)
values ('cccc0008-0000-0000-0000-000000000008', 'bbbb0008-0000-0000-0000-000000000008', 0, '退货需要哪些材料', 'MODEL');
insert into qa_retrieval_action (id, turn_id, subquestion_id, ordinal, action_type, intent_node_id, knowledge_base_id, reason_code)
values ('dddd0008-0000-0000-0000-000000000008', 'bbbb0008-0000-0000-0000-000000000008', 'cccc0008-0000-0000-0000-000000000008', 1, 'KB_TARGETED', gen_random_uuid(), 900001, 'SCORE_AT_OR_ABOVE_TARGETED');
insert into qa_tool_call (id, turn_id, ordinal, tool_name, side_effect, status, arguments_json, result_text, failure_code, latency_ms, confirmed_at)
values (gen_random_uuid(), 'bbbb0008-0000-0000-0000-000000000008', 0, 'submit-return-ticket', true, 'UNAVAILABLE', null, null, null, null, null);

insert into qa_turn (id, conversation_id, client_request_id, user_message_id, status, terminal_reason, completed_at)
values ('bbbb0009-0000-0000-0000-000000000009', 'aaaa0009-0000-0000-0000-000000000009', gen_random_uuid(), 'eeee0009-0000-0000-0000-000000000009', 'ACTIONS_READY', 'ACTIONS_MAPPED', now());
insert into qa_message (id, conversation_id, turn_id, sequence_no, role, message_type, content)
values ('eeee0009-0000-0000-0000-000000000009', 'aaaa0009-0000-0000-0000-000000000009', 'bbbb0009-0000-0000-0000-000000000009', 1, 'USER', 'USER_QUERY', '退货需要哪些材料？');
insert into qa_subquestion (id, turn_id, ordinal, text, source)
values ('cccc0009-0000-0000-0000-000000000009', 'bbbb0009-0000-0000-0000-000000000009', 0, '退货需要哪些材料', 'MODEL');
insert into qa_retrieval_action (id, turn_id, subquestion_id, ordinal, action_type, intent_node_id, knowledge_base_id, reason_code)
values ('dddd0009-0000-0000-0000-000000000009', 'bbbb0009-0000-0000-0000-000000000009', 'cccc0009-0000-0000-0000-000000000009', 1, 'KB_TARGETED', gen_random_uuid(), 900001, 'SCORE_AT_OR_ABOVE_TARGETED');
insert into qa_tool_call (id, turn_id, ordinal, tool_name, side_effect, status, arguments_json, result_text, failure_code, latency_ms, confirmed_at)
values (gen_random_uuid(), 'bbbb0009-0000-0000-0000-000000000009', 0, 'submit-return-ticket', true, 'ABORTED', '{"orderNo":"ORD-20260806-1183"}', null, null, null, null);
commit;

-- 场景 10：工具执行中（验 3B-UI-06 的"执行中输入区禁用且无中止入口"）
begin;
insert into qa_conversation (id, title, last_active_at) values ('aaaa0010-0000-0000-0000-000000000010', '走查 10 工具执行中', now());
insert into qa_turn (id, conversation_id, client_request_id, user_message_id, status, terminal_reason, completed_at)
values ('bbbb0010-0000-0000-0000-000000000010', 'aaaa0010-0000-0000-0000-000000000010', gen_random_uuid(), 'eeee0010-0000-0000-0000-000000000010', 'ACTIONS_READY', 'ACTIONS_MAPPED', now());
insert into qa_message (id, conversation_id, turn_id, sequence_no, role, message_type, content)
values ('eeee0010-0000-0000-0000-000000000010', 'aaaa0010-0000-0000-0000-000000000010', 'bbbb0010-0000-0000-0000-000000000010', 1, 'USER', 'USER_QUERY', '帮我提交退货工单');
insert into qa_subquestion (id, turn_id, ordinal, text, source)
values ('cccc0010-0000-0000-0000-000000000010', 'bbbb0010-0000-0000-0000-000000000010', 0, '提交退货工单', 'MODEL');
insert into qa_tool_call (id, turn_id, ordinal, tool_name, side_effect, status, arguments_json, result_text, failure_code, latency_ms, confirmed_at)
values (gen_random_uuid(), 'bbbb0010-0000-0000-0000-000000000010', 0, 'submit-return-ticket', true, 'RUNNING', '{"orderNo":"ORD-1"}', null, null, null, now());
commit;
