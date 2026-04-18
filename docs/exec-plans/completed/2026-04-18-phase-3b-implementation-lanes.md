# Phase 3B Implementation Lanes

## 1. Metadata

- `plan_id`: `phase-3b-implementation-lanes`
- `status`: `completed`
- `owner`: `codex`
- `current_lane`: `completed`
- `related_docs`: [v8 handoff](/Users/coderlauu/xRag/docs/handoff/v8.md), [v8 status](/Users/coderlauu/xRag/docs/status/v8-phase-3b.md), [Phase 3B implementation freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-18-phase-3b-implementation-freeze.md), [Phase 3B contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-contract-freeze.md), [Phase 3B API design](/Users/coderlauu/xRag/tech/api/2026-04-18-phase-3b-api.md), [Phase 3B data model](/Users/coderlauu/xRag/tech/data-model/2026-04-18-phase-3b-data-model.md), [Phase 3B architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-architecture.md)

## 2. Objective

Implement `v8 / Phase 3B` in lanes on top of the frozen recovery contract. `Lane 0: Contract To Code` and `Lane A: API Recovery Candidates, Preview, Rollback Plan` have landed; downstream API/Web/Test lanes may now proceed but may not modify frozen schema, shared-types, API paths, DTO fields, generated OpenAPI, API client shapes, or recovery action state-machine semantics without returning to `contract-guard`.

## 3. Completed Lanes

### Lane 0: Contract To Code

- `status`: `completed`
- `owner`: main thread
- `goal`: land the frozen recovery contract into schema, shared-types, DTO/controller skeleton, OpenAPI, API client, and Web API adapter

#### Write Scope

1. `apps/api/src/database/schema.ts`
2. `apps/api/src/database/migrations/*`
3. `apps/api/src/database/migrations/meta/*`
4. `packages/shared-types/src/index.ts`
5. `apps/api/src/ops/ops.dto.ts`
6. `apps/api/src/ops/ops.controller.ts`
7. `apps/api/src/ops/ops.service.ts`
8. `apps/api/src/openapi.ts`
9. `docs/generated/openapi/phase-2a-api.json`
10. `packages/api-client/src/index.ts`
11. `apps/web/src/lib/api.ts`

#### Required Contract Outputs

1. Added `operator_recovery_action_type`, `operator_recovery_target_type`, and `operator_recovery_action_status` DB enums.
2. Added `operator_recovery_actions` table with idempotency, preview, before/after facts, queue refs, and timestamps.
3. Added frozen `OpsRecovery*` shared types and rollback plan types.
4. Added `/api/v1/ops/recovery/*` DTOs and controller endpoints.
5. Added API client wrappers and Web adapter wrappers.
6. Regenerated OpenAPI.

#### Out Of Scope

- Implementing real candidate derivation
- Executing document retry/reindex through recovery action service
- Web recovery UI
- Worker changes
- Answer generation/retrieval/citation/freshness/refusal behavior changes

### Lane A: API Recovery Candidates, Preview, Rollback Plan

- `status`: `completed`
- `owner`: main thread unless explicitly delegated later
- `goal`: replace Lane 0 skeleton responses with real read-model derivation from v7 diagnostic facts, real preview preconditions, and rollback-plan evidence shaping
- `write_scope`: `apps/api/src/ops/ops.recovery-candidates.ts`, `apps/api/src/ops/ops.service.ts`, `apps/api/src/ops/ops.controller.ts`, `apps/api/test/integration/ops.phase-3b.integration.test.ts`

#### Completed Outputs

1. Candidate derivation from diagnostic samples, answer session replay, document replay, and deployment compare facts.
2. Preview rules with deterministic `preview_id`, `idempotency_key`, preconditions, blocked reasons, and estimated blast radius.
3. Rollback plan response backed by deployment compare evidence and manual checklist output.
4. Integration coverage for candidate listing, preview, rollback plan, and validation errors.

### Lane B: API Recovery Action Execution And Reconciliation

- `status`: `completed`
- `owner`: main thread unless explicitly delegated later
- `goal`: create durable operator recovery actions, run document retry/reindex actions through existing queues, and reconcile terminal action state from downstream jobs without changing the frozen recovery state machine
- `write_scope`: `apps/api/src/ops/ops.recovery-actions.ts`, `apps/api/src/ops/ops.service.ts`, `apps/api/test/integration/ops.phase-3b.integration.test.ts`

#### Completed Outputs

1. Persisted `operator_recovery_actions` rows with actor, reason, idempotency key, preview snapshot, before facts, and durable status.
2. Executed `document_retry` and `document_reindex` through existing document mutation and queue paths.
3. Kept `answer_diagnostic_rerun` read-only in P0 by returning blocked/manual-follow-up audit state instead of mutating answer facts.
4. Reconciled action status from downstream job refs into `queued / running / succeeded / failed / blocked` states with terminal after-fact snapshots.
5. Added integration coverage for create/status/audit reconciliation and blocked/idempotent answer diagnostic rerun.

## 4. Current Lane

### Lane C: Web Ops Recovery Workflow

- `status`: `completed`
- `write_scope`: `apps/web/src/features/ops/pages/ops-page.tsx`, `apps/web/src/features/ops/components/**`, optional `apps/web/src/features/ops/lib/**`

#### Completed Outputs

1. Added `/ops` recovery workflow UI on top of the frozen recovery API surfaces, including candidate selection, preview-first confirmation, explicit operator reason capture, and read-only handling for `answer_diagnostic_rerun`.
2. Added action status / audit presentation with service-driven polling stop conditions, stuck fallback messaging, queue ref visibility, status timeline, before/after facts, and manual follow-up rendering.
3. Added guarded rollback plan presentation beside deployment compare, including confidence, missing evidence, smoke summary, and manual checklist without any automatic rollback trigger.
4. Kept the Web lane within the frozen contract by reusing existing API-client / Web adapter wrappers instead of altering DTOs, enums, or OpenAPI artifacts.

## 5. Final Lane

### Lane D: Integration, E2E, Smoke

- `status`: `completed`
- `write_scope`: recovery integration tests, Web E2E, smoke script updates

#### Completed Outputs

1. Extended recovery integration coverage by reconciling the existing API test surface with the final Phase 3B action/audit workflow.
2. Added Web E2E coverage for recovery preview / confirm / audit / rollback-plan flow and for the manual-only `answer_diagnostic_rerun` path.
3. Updated the smoke script to include the new Phase 3B recovery E2E spec and aligned the existing Phase 3A ops diagnostic E2E mocks with the Lane C recovery UI.

## 6. Validation

### Lane 0 Minimum Gate

1. `git diff --check`: passed
2. `pnpm docs:check`: passed after Lane 0 status update
3. `pnpm --filter @xrag/shared-types typecheck`: passed
4. `pnpm --filter @xrag/api typecheck`: passed
5. `pnpm --filter @xrag/api-client typecheck`: passed
6. `pnpm --filter @xrag/web typecheck`: passed
7. `pnpm --filter @xrag/api openapi:generate`: passed
8. `pnpm test:integration`: passed, 20 tests
9. `pnpm contract:check`: expected to report the generated OpenAPI diff until the contract artifact is included in the Lane 0 commit

### Lane A Gate

1. `pnpm --filter @xrag/api typecheck`: passed
2. `pnpm test:integration`: passed, 21 tests

### Lane B Gate

1. `pnpm --filter @xrag/api typecheck`: passed
2. `pnpm --filter @xrag/api build:test`: passed
3. `node --test --test-concurrency=1 apps/api/dist-integration/apps/api/test/integration/ops.phase-3b.integration.test.js`: passed, 3 tests

### Lane C Gate

1. `pnpm --filter @xrag/web typecheck`: passed
2. `git diff --check`: passed
3. `pnpm docs:check`: passed

### Lane D Gate

1. `pnpm test:integration`: passed, 23 tests
2. `pnpm test:e2e`: passed, 10 tests
3. `pnpm e2e:smoke`: passed, 10 tests
4. Focused `e2e/phase-3b-ops-recovery.spec.ts`: passed, 2 tests

### Later Release Gate

- `pnpm validate`
- `pnpm test:unit`
- `pnpm test:integration`
- `pnpm test:e2e`
- `pnpm e2e:smoke`

## 7. Contract Drift Guardrails

- No lane may rename `OpsRecovery*` DTO fields without first updating [Phase 3B Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-contract-freeze.md).
- No lane may add recovery action status values outside `queued / running / succeeded / failed / cancelled / blocked`.
- No lane may add automatic rollback or automatic remediation endpoints.
- `answer_diagnostic_rerun` remains read-only in P0.
- Existing v7 diagnostic endpoints and document retry/reindex endpoints remain backward compatible.

## 8. Decision Log

- `2026-04-18`: Implementation lanes opened. `Lane 0: Contract To Code` starts first and remains main-thread owned.
- `2026-04-18`: `Lane 0: Contract To Code` completed. Recovery schema/migration, shared types, DTO/controller surfaces, API client, Web adapter, and generated OpenAPI were landed. `Lane A` is now the current lane.
- `2026-04-18`: `Lane A: API Recovery Candidates, Preview, Rollback Plan` completed. Candidate derivation now uses v7 diagnostic samples, answer replay, document replay, and deployment compare facts; preview now loads current target facts and emits deterministic preview/idempotency values. `Lane B` is now the current lane.
- `2026-04-18`: `Lane B: API Recovery Action Execution And Reconciliation` completed. Durable recovery actions now execute through existing document retry/reindex paths, reconcile terminal state from downstream jobs, expose audit responses, and keep `answer_diagnostic_rerun` blocked/read-only in P0. `Lane C` is now the current lane.
- `2026-04-18`: `Lane C: Web Ops Recovery Workflow` completed. `/ops` now renders recovery candidates, preview / confirm, action status / audit, stuck fallback, and guarded rollback plan on top of frozen recovery API surfaces. `Lane D` is now the current lane.
- `2026-04-18`: `Lane D: Integration, E2E, Smoke` completed. Recovery integration coverage, Web E2E coverage, and smoke wiring now cover the Phase 3B recovery workflow. Implementation lanes are complete; release-readiness is now the active exec plan.
