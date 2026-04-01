---
name: xrag-iteration-governor
description: "Govern xRag iteration workflow: classify new requests as quick change, medium feature, or new version; decide whether to create exec plans or versioned handoff and status docs; scaffold the required repo artifacts; update current.md; and drive release-readiness and archive steps. Use when starting a new xRag requirement, evaluating whether a change needs a new version, generating handoff, status, or exec-plan docs, or closing and archiving an iteration."
---

# xrag-iteration-governor

## Overview

Use this skill to run repeatable xRag iteration governance inside this repository.

Keep project facts in repo docs. Use this skill to decide process, generate the minimum required artifacts, and keep version handoff current.

## Workflow

### 1. Load core context

- Read [AGENTS.md](/Users/coderlauu/xRag/AGENTS.md)
- Read [docs/handoff/current.md](/Users/coderlauu/xRag/docs/handoff/current.md)
- Read the active `docs/status/*.md` for the current version when it exists
- Read [docs/process/2026-03-31-harness-engineering-playbook.md](/Users/coderlauu/xRag/docs/process/2026-03-31-harness-engineering-playbook.md)
- Read [docs/process/version-lifecycle.md](/Users/coderlauu/xRag/docs/process/version-lifecycle.md)
- Read only the affected PRD, scope, interaction, or `tech/` docs after that

### 2. Classify the request

Use [references/artifact-matrix.md](references/artifact-matrix.md) to decide whether the request is:

- `quick-change`
- `medium-feature`
- `new-version`

If the case is ambiguous, explain the threshold you chose before creating artifacts.

### 3. Create the minimum artifact set

- `quick-change`
  - Usually do not create a new version handoff
  - Usually update the active version status only if the milestone state materially changed
  - Update behavior docs if user-facing behavior changes
- `medium-feature`
  - Create an exec plan in `docs/exec-plans/active/`
  - Update the active version `docs/status/*.md`
- `new-version`
  - Create `docs/handoff/vN.md`
  - Create `docs/status/vN-*.md`
  - Usually create an exec plan
  - Update `docs/handoff/current.md`

Use `scripts/init_iteration.py` when you need to scaffold a new handoff, status file, and optional exec plan.

### 4. Keep repo docs authoritative

- Keep `current.md` short and current
- Keep the active version `status` file current enough to resume work without chat history
- Put detailed technical facts in `tech/`
- Put workflow rules in `docs/process/`
- Do not duplicate project facts into the skill

### 5. Define validation

Choose the smallest sufficient validation set:

- unit
- integration
- e2e
- smoke

Follow the project playbook and state what evidence is still missing if you cannot run it.

### 6. Close the iteration

- Move finished exec plans from `active` to `completed`
- Update the active version status to reflect the latest milestone state
- Update handoff or playbook docs if process changed
- Archive obsolete version handoff states when the project advances

## Rules

- Reuse the same process for each new request; do not redefine the workflow every time
- Prefer the smallest artifact set that still keeps scope, validation, and rollback clear
- Treat repo docs as the system of record; do not leave critical decisions only in chat
- Update `current.md` only when the active version truly changes
- Every active version should have both a handoff and a status file
- If a change affects architecture, API, or data model, update the matching `tech/` doc in the same task

## Resources

- `references/artifact-matrix.md`
  - Use for classification and required artifact selection
- `scripts/init_iteration.py`
  - Use to scaffold a new handoff, status file, and optional exec plan, and optionally update `current.md`
- `scripts/install_to_codex_home.sh`
  - Use to symlink this project skill into the user's Codex skills directory for auto-discovery
