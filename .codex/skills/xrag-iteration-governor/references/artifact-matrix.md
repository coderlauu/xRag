# xRag Iteration Artifact Matrix

Use this file when a request lands and you need to decide the minimum required process.

## 1. Classification

### `quick-change`

Use when all conditions are true:

- estimated work is less than half a day
- no new version scope
- no cross-module redesign
- no new API boundary
- no architecture decision change

Typical examples:

- copy tweak
- tiny UI polish
- isolated bug fix
- doc correction

Required artifacts:

- code change
- affected doc update if behavior changed
- update active `docs/status/*.md` only if milestone or release state changed

Usually not required:

- new `vN` handoff
- exec plan

### `medium-feature`

Use when any of these is true:

- estimated work is half a day to two days
- affects one module or one API contract
- introduces moderate testing or rollout risk
- needs explicit validation and rollback notes

Typical examples:

- add one API resource
- adjust parsing state flow
- refactor one page into formal module structure

Required artifacts:

- exec plan in `docs/exec-plans/active/`
- update active `docs/status/*.md`
- code, tests, and doc updates

Usually not required:

- new `vN` handoff, unless the product scope changes

### `new-version`

Use when any of these is true:

- changes product scope or acceptance standard
- spans multiple modules or pages
- changes current version target
- adds or removes major capabilities
- requires new PRD/scope/interaction alignment

Typical examples:

- start `v2`
- introduce a new workflow across inbox, search, and detail
- shift from prototype-only to formal engineering delivery

Required artifacts:

- new `docs/handoff/vN.md`
- new `docs/status/vN-*.md`
- update `docs/handoff/current.md`
- usually create an exec plan
- update affected `docs/`, `design/`, and `tech/` assets

## 2. Decision Defaults

If you are unsure:

- choose `medium-feature` over `quick-change`
- choose `new-version` only when scope, target, or acceptance clearly changed

## 3. Validation Minimums

### `quick-change`

- local verification or targeted test

### `medium-feature`

- unit or integration coverage
- update validation notes in exec plan

### `new-version`

- explicit validation strategy in exec plan
- release/readiness checklist
- affected docs updated before implementation fan-out
