alter table qa_turn
    add column rewritten_question text,
    add column summary_used boolean,
    add column recent_complete_turns smallint;

alter table qa_turn
    add constraint ck_qa_turn_rewritten_question check (
        rewritten_question is null
        or char_length(btrim(rewritten_question)) between 1 and 20000),
    add constraint ck_qa_turn_summary_used check (
        (summary_used is null and recent_complete_turns is null)
        or (summary_used is not null and recent_complete_turns between 0 and 8));
