---
name: lark-sync-project-docs
description: Synchronize xRag repository Markdown to My Document Library/project/xRag in Feishu with preview-first Wiki migration, create, overwrite, readable Chinese node mapping, orphan detection, and explicit pruning. Use when the user asks to publish, migrate, sync, update, preview, compare, clean, or delete project documents in Feishu, including README.md, CONTEXT.md, docs/**, learning/**, and tech/**.
---

# Sync xRag project documents to Feishu Wiki

Use the bundled entry point. Keep
`scripts/publish-feishu-docs.mjs` as the single implementation and
`.feishu-docs.local.json` as the local remote-object mapping.
Use its `folderNames` and `documentTitles` mappings; do not infer alternate
remote names during a sync.

```bash
skill_script=".agents/skills/lark-sync-project-docs/scripts/sync.sh"
```

## Fixed Wiki target

- Path: `我的文档库/project/xRag`
- Personal library space ID: `7605889658102254529`
- `project` parent node token: `PYLswmNnRidMgckjlorc64lEnSf`
- Use Wiki nodes for the root and all mapped directories.
- Keep each document's underlying docx token and mapped Wiki node token.
- The legacy Drive folder is migration input only; after migration, all new
  directories and documents must be created under the Wiki target.

## Safety rules

- Treat Git Markdown as the source of truth. Warn that overwriting a Feishu
  document can remove remote-only content and comments.
- Run a dry-run before every batch write or prune.
- Summarize exact `mkdir`, `create`, `update`, `orphan`, and `delete` counts.
  List every update or delete target with its Feishu URL.
- Stop after preview and obtain explicit confirmation for the displayed batch
  before using `--yes`. A general request to "sync docs" is not confirmation
  of an unseen overwrite or delete plan.
- Never add `--prune` unless the user explicitly confirms the displayed delete
  targets. Pruning moves mapped documents to the Feishu recycle bin.
- Never delete mirrored directory nodes automatically. Leave empty nodes and
  legacy Drive folders in place.
- Keep Feishu writes and deletes serial. The publisher already enforces this.
- Never print, commit, or hand-edit `.feishu-docs.local.json`.
- Before live Feishu writes, follow the installed `lark-shared`, `lark-doc`,
  and `lark-drive` authentication and safety instructions.

## Choose the operation

Preview the one-time migration from the mapped Drive tree:

```bash
"$skill_script" --migrate-to-wiki \
  --space-id 7605889658102254529 \
  --parent-node-token PYLswmNnRidMgckjlorc64lEnSf \
  --root-title xRag \
  --dry-run
```

After the user confirms that exact preview, create the Wiki directory nodes,
move the mapped documents serially, update the local mapping after every
success, and then apply any already-confirmed local content updates:

```bash
"$skill_script" --migrate-to-wiki \
  --space-id 7605889658102254529 \
  --parent-node-token PYLswmNnRidMgckjlorc64lEnSf \
  --root-title xRag \
  --yes \
  --allow-overwrite
```

List the configured scope without writing:

```bash
"$skill_script" --list
```

Preview all changes:

```bash
"$skill_script" --sync --dry-run
```

After the user confirms that exact preview, create new documents and overwrite
the listed changed documents:

```bash
"$skill_script" --sync --yes --allow-overwrite
```

Preview local deletions or renames. In this mode, mapped documents missing from
the approved local scope are printed as `delete`:

```bash
"$skill_script" --sync --dry-run --prune
```

After the user explicitly confirms every delete target and the other displayed
batch changes:

```bash
"$skill_script" --sync --yes --allow-overwrite --prune
```

Preview one approved file:

```bash
"$skill_script" --file path/to/document.md --dry-run
```

Publish one approved file. Add `--allow-overwrite` only when the preview shows
`update` and the user confirms replacement:

```bash
"$skill_script" --file path/to/document.md
"$skill_script" --file path/to/document.md --allow-overwrite
```

## Interpret results

- `mkdir`: create the mapped, human-readable Feishu Wiki directory node shown
  after `->`.
- `move`: migrate an existing mapped Drive document into its Wiki node.
- `create`: create a new Feishu document.
- `update`: overwrite a mapped document whose title or body changed.
- `skip`: local content matches the last successful publish.
- `orphan`: keep a mapped Feishu document whose local source is absent.
- `delete`: with `--prune`, delete that mapped Wiki document node. This is
  high-risk and requires confirmation of every displayed target.

A rename is intentionally represented as one `create` plus one
`orphan`/`delete`.

Keep source paths stable while using the configured Feishu names:

- `docs/**` -> `01-项目文档/**`
- `learning/**` -> `02-RAG学习专栏/**`
- `tech/**` -> `03-技术设计/**`
- `README.md` -> `xRag 项目说明`
- `CONTEXT.md` -> `xRag 项目上下文`

## Verify

After a live run, execute:

```bash
"$skill_script" --sync --dry-run
```

Expect published documents to be `skip` and no unexpected `orphan` entries.
For the one-time migration, also list the Wiki tree under the mapped xRag root
and confirm that every document has a Wiki node mapping.
Report partial failures rather than rolling back successful items; the publisher
saves mapping state after every successful folder or document operation, so a
new preview is safe.

If `.feishu-docs.local.json` is missing, stop. Use
`.feishu-docs.example.json` only as a template and ask for the intended Feishu
root folder token before creating a new mapping. If authentication is invalid,
repair user authentication before retrying; do not switch to bot identity
silently.
