---
name: answer-quality-gate
description: Protect retrieval and answer quality for evidence-based question answering systems. Use when a task affects answer generation, citations, scope control, refusal behavior, retrieval quality, freshness, or evaluation contracts.
---

# Answer Quality Gate

Use this skill when the task changes how the system retrieves evidence or produces answers.

## When To Use

- Editing answer generation or orchestration
- Changing citation behavior, evidence linking, or refusal logic
- Changing retrieval scope, ranking, freshness, or fallback behavior
- Reviewing whether an AI answer feature still matches its evaluation contract

## Workflow

1. Identify the user-visible answer contract first.
2. Check the affected evidence chain:
   - retrieval inputs
   - ranking or filtering
   - citation mapping
   - refusal conditions
3. Preserve explicit scope boundaries and no-evidence refusal behavior.
4. Update evaluation assumptions when answer behavior materially changes.
5. Validate with the smallest evidence-oriented check available.

## Guardrails

- Do not trade answer quality for surface-level fluency.
- Prefer explicit refusal over unsupported claims.
- Keep citations and answer text aligned.
- If freshness or indexing affects answer quality, surface the dependency instead of hiding it.

## xRag Repo Notes

Inside xRag, this skill applies to `citation / scope / eval contract`, mixed retrieval, answer refusal, and indexing freshness diagnostics.
