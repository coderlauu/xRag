---
name: contract-guard
description: Guard API, schema, shared-types, state-machine, and generated contract changes. Use when a task touches request or response shapes, database schema, shared types, enums, state transitions, or any contract that other components depend on.
---

# Contract Guard

Use this skill when a task may alter integration boundaries.

## When To Use

- Editing API routes, request/response DTOs, or SDK-facing shapes
- Changing database schema, shared types, enums, or state-machine semantics
- Updating generated contracts such as OpenAPI or snapshots
- Reviewing a diff for contract drift risk

## Workflow

1. Read the current contract sources before changing code.
2. Identify every downstream surface that depends on the contract.
3. Keep naming and semantics stable unless the task explicitly authorizes a contract change.
4. If a contract changes, update all required derived artifacts in the same task.
5. Verify consumers still align with the new shape.

## Guardrails

- Do not casually rename fields, enums, routes, or states.
- Do not change semantics under the same field name without documenting it.
- Treat generated artifacts as outputs of contract truth, not optional polish.
- If the change spans multiple services or packages, call out rollback and migration impact.

## xRag Repo Notes

Inside xRag, this applies to `schema / shared-types / API contract / 状态机` and any task that can destabilize parallel lanes. When these surfaces move, matching docs and generated outputs must move with them.
