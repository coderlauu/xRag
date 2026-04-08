---
name: technical-writer
description: Write or update technical documentation such as README files, API docs, setup guides, migration notes, changelogs, and developer/user docs. Use when the task involves documenting existing code or repo behavior and accuracy matters.
---

# Technical Writer

Document what exists. Do not invent behavior or promise work that has not shipped.

## When To Use

- Writing or updating `README` content
- Documenting APIs, request/response shapes, or usage examples
- Explaining setup, deployment, validation, or troubleshooting workflows
- Writing migration notes, release notes, or operator runbooks
- Tightening existing docs for clarity, structure, and factual accuracy

If the task is a large proposal, design doc, RFC, or decision memo, prefer `doc-coauthoring`.

## Workflow

1. Read the current source of truth first.
   - Existing docs in the repo
   - Code paths being documented
   - Tests, schemas, or generated artifacts when relevant
2. Extract facts from code and documents before drafting.
3. Update existing docs in place when possible instead of creating duplicates.
4. Include examples only when they match the real implementation.
5. Call out missing verification, missing examples, or known uncertainty explicitly.

## Writing Rules

- Prefer concise, task-oriented structure.
- Use stable headings and short sections.
- Explain prerequisites, steps, expected outcomes, and failure cases.
- Keep terminology consistent with the codebase.
- Avoid marketing language, filler, and vague claims.
- If behavior changed, document the new behavior and remove stale statements nearby.

## xRag Repo Notes

When used inside xRag:

- Treat repo docs as the system of record.
- Read `AGENTS.md`, `docs/handoff/current.md`, and the active `docs/status/*.md` before major doc work.
- If API behavior changes, ensure matching OpenAPI and SDK docs are considered.
- If user-facing or operator-facing behavior changes, update the corresponding docs in the same task.

## Done Criteria

- The doc matches current implementation and terminology.
- Examples and commands are internally consistent.
- Related stale wording is removed.
- Any missing evidence or unverified claims are called out plainly.
