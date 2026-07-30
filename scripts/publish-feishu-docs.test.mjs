import assert from "node:assert/strict";
import test from "node:test";

import {
  documentHash,
  missingRemoteDirectories,
  orphanedDocuments,
  parseArgs,
  parseJsonEnvelope,
  prepareMarkdown,
  remoteDirectoryDisplayPath,
  remoteDirectoryToken,
  remoteDirectoriesFor,
  remoteFolderName,
  rewriteRelativeLinks,
  targetType,
  wikiMigrationPlan
} from "./publish-feishu-docs.mjs";

const config = {
  branch: "main",
  repositoryUrl: "https://github.com/coderlauu/xRag"
};

test("prepareMarkdown uses and removes the first H1 title", () => {
  const prepared = prepareMarkdown(
    "# Project\n\nRead [the API](tech/api.md).\n",
    "README.md",
    config
  );

  assert.equal(prepared.title, "Project");
  assert.equal(
    prepared.body,
    "Read [the API](https://github.com/coderlauu/xRag/blob/main/tech/api.md).\n"
  );
});

test("prepareMarkdown applies an explicit Feishu document title", () => {
  const prepared = prepareMarkdown(
    "# App\n\nProject content.\n",
    "README.md",
    {
      ...config,
      documentTitles: {
        "README.md": "xRag 项目说明"
      }
    }
  );

  assert.equal(prepared.title, "xRag 项目说明");
  assert.equal(prepared.body, "Project content.\n");
});

test("relative links are rewritten outside fenced code blocks", () => {
  const markdown = [
    "[ADR](../docs/adr/0001.md#decision)",
    "![diagram](./diagram.png)",
    "```md",
    "[example](local.md)",
    "```",
    "[external](https://example.com)"
  ].join("\n");

  assert.equal(
    rewriteRelativeLinks(markdown, "tech/module/api.md", config),
    [
      "[ADR](https://github.com/coderlauu/xRag/blob/main/tech/docs/adr/0001.md#decision)",
      "![diagram](https://raw.githubusercontent.com/coderlauu/xRag/main/tech/module/diagram.png)",
      "```md",
      "[example](local.md)",
      "```",
      "[external](https://example.com)"
    ].join("\n")
  );
});

test("parseJsonEnvelope ignores CLI preamble lines", () => {
  assert.deepEqual(
    parseJsonEnvelope('Creating document...\n{"ok":true,"data":{"id":"1"}}\n'),
    { ok: true, data: { id: "1" } }
  );
});

test("batch writes require an explicit acknowledgement", () => {
  assert.throws(() => parseArgs(["--sync"]), /--sync requires --yes/);
  assert.equal(parseArgs(["--sync", "--dry-run"]).dryRun, true);
  assert.equal(parseArgs(["--sync", "--yes"]).yes, true);
  assert.equal(parseArgs(["--all", "--yes"]).sync, true);
  assert.throws(
    () =>
      parseArgs([
        "--migrate-to-wiki",
        "--space-id",
        "space",
        "--parent-node-token",
        "parent"
      ]),
    /--migrate-to-wiki requires --yes/
  );
  assert.equal(
    parseArgs([
      "--migrate-to-wiki",
      "--space-id",
      "space",
      "--parent-node-token",
      "parent",
      "--dry-run"
    ]).rootTitle,
    "xRag"
  );
});

test("prune is limited to an acknowledged sync or a dry-run preview", () => {
  assert.throws(
    () => parseArgs(["--file", "README.md", "--prune"]),
    /--prune can only be used with --sync/
  );
  assert.equal(parseArgs(["--sync", "--prune", "--dry-run"]).prune, true);
  assert.throws(
    () => parseArgs(["--sync", "--prune"]),
    /--sync requires --yes/
  );
});

test("remote directories are ordered parent before child", () => {
  const sourcePaths = [
    "README.md",
    "learning/ragent/notes/one.md",
    "docs/adr/two.md"
  ];

  assert.deepEqual(remoteDirectoriesFor(sourcePaths), [
    "docs",
    "learning",
    "docs/adr",
    "learning/ragent",
    "learning/ragent/notes"
  ]);
  assert.deepEqual(
    missingRemoteDirectories(sourcePaths, {
      folders: {
        ".": { token: "root" },
        docs: { token: "docs" }
      }
    }),
    ["learning", "docs/adr", "learning/ragent", "learning/ragent/notes"]
  );
});

test("Wiki targets use node tokens instead of legacy Drive folder tokens", () => {
  const wikiConfig = {
    target: { type: "wiki" },
    folders: {
      docs: { nodeToken: "wiki-docs", token: "drive-docs" }
    }
  };

  assert.equal(targetType({}), "drive");
  assert.equal(targetType(wikiConfig), "wiki");
  assert.equal(remoteDirectoryToken("docs", wikiConfig), "wiki-docs");
  assert.equal(
    remoteDirectoryToken("docs", {
      folders: { docs: { token: "drive-docs" } }
    }),
    "drive-docs"
  );
});

test("remote directory display names preserve the source hierarchy", () => {
  const folderConfig = {
    folderNames: {
      learning: "02-RAG学习专栏",
      "learning/ragent-column": "企业级RAG实战",
      "learning/ragent-column/01-rag-fundamentals": "01-RAG基础"
    }
  };

  assert.equal(remoteFolderName("learning", folderConfig), "02-RAG学习专栏");
  assert.equal(remoteFolderName("docs/other", folderConfig), "other");
  assert.equal(
    remoteDirectoryDisplayPath(
      "learning/ragent-column/01-rag-fundamentals",
      folderConfig
    ),
    "02-RAG学习专栏/企业级RAG实战/01-RAG基础"
  );
});

test("mapped documents missing from the approved source set become orphans", () => {
  const orphans = orphanedDocuments(["README.md"], {
    documents: {
      "README.md": { documentId: "keep" },
      "docs/old.md": { documentId: "delete", url: "https://example.test/old" }
    }
  });

  assert.deepEqual(orphans, [
    {
      documentId: "delete",
      sourcePath: "docs/old.md",
      url: "https://example.test/old"
    }
  ]);
});

test("documentHash includes both title and body", () => {
  assert.notEqual(
    documentHash("One", "Same body\n"),
    documentHash("Two", "Same body\n")
  );
});

test("Wiki migration preview preserves mapped directory names", () => {
  const migrationConfig = {
    folderNames: {
      docs: "01-项目文档",
      "docs/adr": "01-架构决策（ADR）"
    },
    folders: {
      ".": { token: "drive-root" },
      docs: { token: "drive-docs" },
      "docs/adr": { token: "drive-adr" }
    },
    documents: {
      "README.md": { documentId: "readme", title: "xRag 项目说明" },
      "docs/adr/0001.md": { documentId: "adr" }
    }
  };

  const plan = wikiMigrationPlan(
    ["README.md", "docs/adr/0001.md"],
    migrationConfig
  );

  assert.deepEqual(
    plan.directories.map(({ action, targetPath }) => ({ action, targetPath })),
    [
      { action: "mkdir", targetPath: "xRag/" },
      { action: "mkdir", targetPath: "xRag/01-项目文档/" },
      {
        action: "mkdir",
        targetPath: "xRag/01-项目文档/01-架构决策（ADR）/"
      }
    ]
  );
  assert.deepEqual(
    plan.documents.map(({ action, sourcePath, targetPath }) => ({
      action,
      sourcePath,
      targetPath
    })),
    [
      { action: "move", sourcePath: "README.md", targetPath: "xRag/" },
      {
        action: "move",
        sourcePath: "docs/adr/0001.md",
        targetPath: "xRag/01-项目文档/01-架构决策（ADR）"
      }
    ]
  );
});
