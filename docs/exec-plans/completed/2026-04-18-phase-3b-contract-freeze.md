# Phase 3B Contract Freeze

## 1. Metadata

- `plan_id`: `phase-3b-contract-freeze`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: [v8 handoff](/Users/coderlauu/xRag/docs/handoff/v8.md), [v8 status](/Users/coderlauu/xRag/docs/status/v8-phase-3b.md), [Phase 3B contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-contract-freeze.md), [Phase 3B architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-architecture.md), [Phase 3B data model](/Users/coderlauu/xRag/tech/data-model/2026-04-18-phase-3b-data-model.md), [Phase 3B api design](/Users/coderlauu/xRag/tech/api/2026-04-18-phase-3b-api.md), [Phase 3B contract freeze prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-18-phase-3b-contract-freeze-prerequisites.md), [Phase 3B technical evaluation](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-18-phase-3b-technical-evaluation.md)

## 2. Objective

Freeze `v8 / Phase 3B` contract before implementation: schema, shared-types, API paths, request/response DTOs, recovery action state machine, queue reconciliation semantics, OpenAPI/API client impact, and answer-quality boundaries.

当前计划已完成并归档；后续 implementation freeze 与 [Phase 3B implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-18-phase-3b-implementation-lanes.md) 也已完成，当前进入 [Phase 3B release readiness](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-18-phase-3b-release-readiness.md)。

## 3. Scope

### In Scope

- Write [Phase 3B Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-contract-freeze.md)
- Freeze `operator_recovery_actions` schema direction and migration impact
- Freeze recovery action statuses, action types, target types, risk/recommendation states and idempotency semantics
- Freeze `ops/recovery` API path, query, request and response shapes
- Freeze shared-types additions and downstream DTO/API client/OpenAPI obligations
- Freeze action liveness and queue reconciliation rules
- Freeze answer diagnostic rerun answer-quality boundary

### Out Of Scope

- Runtime implementation
- Database migration authoring
- OpenAPI generation
- API client implementation
- Web UI implementation
- Automatic remediation, automatic rerun, automatic production rollback

## 4. Assumptions

- Technical evaluation has concluded Phase 3B can enter contract freeze.
- Candidate and rollback plan remain read models.
- Operator action and audit require durable facts.
- Existing document retry/reindex API remains compatible but is not the `/ops` recovery primary path.
- Any answer rerun that changes answer facts must be deferred or explicitly gated by `answer-quality-gate`.

## 5. Risks

- Freezing action status too loosely will make worker/API/Web lanes reinterpret terminal semantics.
- Freezing recovery action without idempotency would permit duplicate reruns.
- Treating rollback plan as mutation would pull deployment control into v8 P0.
- Allowing answer diagnostic rerun to rewrite answer facts would reopen citation/freshness/refusal boundaries.

## 6. Plan

1. Freeze schema and state machine:
   - `operator_recovery_actions`
   - action type, target type, status, idempotency key
2. Freeze API and shared types:
   - `ops/recovery` resources
   - preview/create/status/audit/rollback plan DTOs
3. Freeze liveness and reconciliation:
   - active/terminal status
   - queue failure mapping
   - frontend polling stop condition
4. Freeze answer-quality boundary:
   - read-only answer diagnostic rerun by default
   - no answer fact rewrite in P0 unless separately gated
5. Decide whether Phase 3B can enter implementation-freeze.

## 7. Validation

- `pnpm docs:check`
- `git diff --check`
- `pnpm contract:check` only after generated contract artifacts are touched in later phases

## 8. Rollback

- If contract freeze reveals unacceptable API/schema blast radius, return to technical evaluation and reduce P0 to candidate/read-only recovery planning.
- If answer diagnostic rerun cannot stay read-only, defer it or create an explicit answer-quality gate record before implementation.

## 9. Decision Log

- `2026-04-18`: Phase 3B technical evaluation completed; contract freeze started.
- `2026-04-18`: Phase 3B contract freeze completed; [Phase 3B Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-contract-freeze.md) is now the source of truth for implementation freeze.
