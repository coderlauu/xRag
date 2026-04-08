---
name: implementation-lane
description: Implement scoped feature work inside an existing architecture and frozen delivery plan. Use when the task is to build or modify application code in a defined lane, especially after requirements, contracts, or milestones are already documented.
---

# Implementation Lane

Use this skill when the task is implementation work rather than product discovery or freeform planning.

## When To Use

- Building a scoped feature from an existing PRD, handoff, exec plan, or status doc
- Continuing work in a defined implementation lane
- Editing application code that must stay within existing API, schema, or workflow boundaries
- Resuming work from repo docs instead of chat history

If the task is primarily about version planning or artifact scaffolding, prefer `xrag-iteration-governor`.

## Workflow

1. Load the repo source of truth first.
   - `AGENTS.md`
   - `docs/handoff/current.md`
   - active `docs/status/*.md`
   - active `docs/exec-plans/active/*.md` when relevant
2. Read only the affected `tech/*`, `docs/decisions/*`, or generated contract files after that.
3. Keep changes inside the documented lane boundary.
4. Do not silently redefine upstream contracts during implementation.
5. Run the smallest useful validation before closing the task.

## Guardrails

- Prefer extending existing modules over creating parallel abstractions.
- If implementation conflicts with a frozen contract, stop and surface the mismatch instead of improvising.
- Keep behavior docs, generated contracts, and code aligned in the same task when behavior changes.
- When parallel work is involved, preserve clear ownership and avoid contract drift.

## xRag Repo Notes

Inside xRag, this skill is most relevant after implementation freeze, when work proceeds lane by lane on top of existing contracts, status, and exec-plan documents.
