#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

const repoRoot = path.resolve(import.meta.dirname, "..");

function usage() {
  return `Usage:
  node scripts/publish-feishu-docs.mjs --list
  node scripts/publish-feishu-docs.mjs --file <repo-relative.md> [--dry-run] [--allow-overwrite]
  node scripts/publish-feishu-docs.mjs --sync [--dry-run] [--yes] [--allow-overwrite] [--prune]
  node scripts/publish-feishu-docs.mjs --migrate-to-wiki --space-id <id> --parent-node-token <token> [--root-title <title>] [--dry-run|--yes]

Options:
  --list              List the approved Markdown scope without writing to Feishu.
  --file <path>       Publish one approved Markdown file.
  --sync              Mirror approved Markdown files and their directories, serially.
  --migrate-to-wiki   Move mapped Drive documents into a Wiki tree, serially.
  --space-id <id>     Wiki space ID used by --migrate-to-wiki.
  --parent-node-token <token>
                      Wiki parent node used by --migrate-to-wiki.
  --root-title <title>
                      Wiki root node title (default: xRag).
  --all               Deprecated alias for --sync.
  --yes               Required with --sync or --migrate-to-wiki for batch writes.
  --dry-run           Show mkdir/create/update/skip/orphan/delete decisions only.
  --allow-overwrite   Allow a changed local file to replace its mapped Feishu document.
  --prune             With --sync, delete mapped documents missing locally.
  --config <path>     Use another repo-relative config file.`;
}

export function parseArgs(argv) {
  const options = {
    allowOverwrite: false,
    config: ".feishu-docs.local.json",
    dryRun: false,
    file: null,
    list: false,
    migrateToWiki: false,
    parentNodeToken: null,
    prune: false,
    rootTitle: "xRag",
    spaceId: null,
    sync: false,
    yes: false
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--all" || arg === "--sync") {
      options.sync = true;
    } else if (arg === "--migrate-to-wiki") {
      options.migrateToWiki = true;
    } else if (arg === "--allow-overwrite") {
      options.allowOverwrite = true;
    } else if (arg === "--dry-run") {
      options.dryRun = true;
    } else if (arg === "--list") {
      options.list = true;
    } else if (arg === "--prune") {
      options.prune = true;
    } else if (arg === "--yes") {
      options.yes = true;
    } else if (
      arg === "--file" ||
      arg === "--config" ||
      arg === "--space-id" ||
      arg === "--parent-node-token" ||
      arg === "--root-title"
    ) {
      const value = argv[index + 1];
      if (!value) {
        throw new Error(`${arg} requires a value`);
      }
      index += 1;
      if (arg === "--file") {
        options.file = value;
      } else if (arg === "--config") {
        options.config = value;
      } else if (arg === "--space-id") {
        options.spaceId = value;
      } else if (arg === "--parent-node-token") {
        options.parentNodeToken = value;
      } else {
        options.rootTitle = value;
      }
    } else if (arg === "--help" || arg === "-h") {
      options.help = true;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }

  const actions = [
    options.sync,
    options.file !== null,
    options.list,
    options.migrateToWiki
  ].filter(Boolean);
  if (!options.help && actions.length !== 1) {
    throw new Error("Choose exactly one of --list, --file, or --sync");
  }
  if (
    (options.sync || options.migrateToWiki) &&
    !options.yes &&
    !options.dryRun
  ) {
    throw new Error(
      `${options.migrateToWiki ? "--migrate-to-wiki" : "--sync"} requires --yes; use --dry-run to preview without it`
    );
  }
  if (options.prune && !options.sync) {
    throw new Error("--prune can only be used with --sync");
  }
  if (
    options.migrateToWiki &&
    (!options.spaceId || !options.parentNodeToken || !options.rootTitle.trim())
  ) {
    throw new Error(
      "--migrate-to-wiki requires --space-id, --parent-node-token, and a non-empty --root-title"
    );
  }

  return options;
}

function resolveInsideRepo(relativePath) {
  const absolutePath = path.resolve(repoRoot, relativePath);
  const relative = path.relative(repoRoot, absolutePath);
  if (relative.startsWith("..") || path.isAbsolute(relative)) {
    throw new Error(`Path escapes the repository: ${relativePath}`);
  }
  return absolutePath;
}

function toPosix(value) {
  return value.split(path.sep).join("/");
}

async function collectFromRoot(root) {
  const absoluteRoot = resolveInsideRepo(root);
  const stats = await fs.stat(absoluteRoot);
  if (stats.isFile()) {
    return absoluteRoot.endsWith(".md") ? [absoluteRoot] : [];
  }

  const files = [];
  const entries = await fs.readdir(absoluteRoot, { withFileTypes: true });
  entries.sort((left, right) => left.name.localeCompare(right.name));

  for (const entry of entries) {
    const relativeEntry = path.join(root, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await collectFromRoot(relativeEntry)));
    } else if (entry.isFile() && entry.name.endsWith(".md")) {
      files.push(resolveInsideRepo(relativeEntry));
    }
  }
  return files;
}

export async function collectMarkdownFiles(roots) {
  const files = [];
  for (const root of roots) {
    files.push(...(await collectFromRoot(root)));
  }
  return [...new Set(files.map((file) => toPosix(path.relative(repoRoot, file))))].sort();
}

export function remoteDirectoriesFor(sourcePaths) {
  const directories = new Set();

  for (const sourcePath of sourcePaths) {
    let directory = path.posix.dirname(sourcePath);
    while (directory !== ".") {
      directories.add(directory);
      directory = path.posix.dirname(directory);
    }
  }

  return [...directories].sort((left, right) => {
    const depthDifference =
      left.split("/").length - right.split("/").length;
    return depthDifference || left.localeCompare(right);
  });
}

export function missingRemoteDirectories(sourcePaths, config) {
  return remoteDirectoriesFor(sourcePaths).filter(
    (directory) => !remoteDirectoryToken(directory, config)
  );
}

export function targetType(config) {
  return config.target?.type ?? "drive";
}

export function remoteDirectoryToken(directory, config) {
  const entry = config.folders?.[directory];
  return targetType(config) === "wiki" ? entry?.nodeToken : entry?.token;
}

export function orphanedDocuments(sourcePaths, config) {
  const sourceSet = new Set(sourcePaths);
  return Object.entries(config.documents ?? {})
    .filter(([sourcePath]) => !sourceSet.has(sourcePath))
    .map(([sourcePath, document]) => ({ ...document, sourcePath }))
    .sort((left, right) => left.sourcePath.localeCompare(right.sourcePath));
}

export function remoteFolderName(directory, config) {
  return config.folderNames?.[directory] ?? path.posix.basename(directory);
}

export function remoteDirectoryDisplayPath(directory, config) {
  if (directory === ".") {
    return ".";
  }

  const sourceSegments = directory.split("/");
  const displaySegments = [];
  for (let index = 0; index < sourceSegments.length; index += 1) {
    const sourceDirectory = sourceSegments.slice(0, index + 1).join("/");
    displaySegments.push(remoteFolderName(sourceDirectory, config));
  }
  return displaySegments.join("/");
}

function wikiUrl(config, nodeToken) {
  const baseUrl =
    config.target?.baseUrl ??
    (config.folderUrl ? new URL(config.folderUrl).origin : null);
  return baseUrl && nodeToken ? `${baseUrl}/wiki/${nodeToken}` : undefined;
}

function splitTarget(target) {
  const hashIndex = target.indexOf("#");
  if (hashIndex === -1) {
    return { pathname: target, fragment: "" };
  }
  return {
    pathname: target.slice(0, hashIndex),
    fragment: target.slice(hashIndex)
  };
}

function githubTarget(config, sourcePath, target, isImage) {
  if (
    target.startsWith("#") ||
    target.startsWith("/") ||
    /^[a-z][a-z0-9+.-]*:/i.test(target)
  ) {
    return target;
  }

  const { pathname, fragment } = splitTarget(target);
  if (!pathname) {
    return target;
  }

  const resolved = path.posix.normalize(
    path.posix.join(path.posix.dirname(sourcePath), pathname)
  );
  if (resolved === ".." || resolved.startsWith("../")) {
    return target;
  }

  const repositoryUrl = config.repositoryUrl.replace(/\/+$/, "");
  const branch = encodeURIComponent(config.branch);
  const encodedPath = resolved
    .split("/")
    .map((segment) => encodeURIComponent(segment))
    .join("/");

  if (isImage) {
    const githubMatch = repositoryUrl.match(
      /^https:\/\/github\.com\/([^/]+)\/([^/]+?)(?:\.git)?$/
    );
    if (githubMatch) {
      const [, owner, repository] = githubMatch;
      return `https://raw.githubusercontent.com/${owner}/${repository}/${branch}/${encodedPath}${fragment}`;
    }
  }

  const view = pathname.endsWith("/") ? "tree" : "blob";
  return `${repositoryUrl}/${view}/${branch}/${encodedPath}${fragment}`;
}

export function rewriteRelativeLinks(markdown, sourcePath, config) {
  const lines = markdown.split("\n");
  let fence = null;

  return lines
    .map((line) => {
      const fenceMatch = line.match(/^\s*(```+|~~~+)/);
      if (fenceMatch) {
        const marker = fenceMatch[1][0];
        fence = fence === null ? marker : fence === marker ? null : fence;
        return line;
      }
      if (fence !== null) {
        return line;
      }

      return line.replace(
        /(!?)\[([^\]]*)\]\(([^)\s]+)\)/g,
        (match, imageMarker, label, target) => {
          const rewritten = githubTarget(
            config,
            sourcePath,
            target,
            imageMarker === "!"
          );
          return `${imageMarker}[${label}](${rewritten})`;
        }
      );
    })
    .join("\n");
}

function plainTitle(markdownTitle) {
  return markdownTitle
    .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1")
    .replace(/[*_`~]/g, "")
    .trim();
}

export function prepareMarkdown(source, sourcePath, config) {
  const lines = source.replace(/\r\n/g, "\n").split("\n");
  const firstContentIndex = lines.findIndex((line) => line.trim() !== "");
  const firstHeading =
    firstContentIndex >= 0 ? lines[firstContentIndex].match(/^#\s+(.+?)\s*$/) : null;

  let inferredTitle;
  if (firstHeading) {
    inferredTitle = plainTitle(firstHeading[1]);
    lines.splice(firstContentIndex, 1);
    while (lines[0] === "") {
      lines.shift();
    }
  } else {
    inferredTitle = path.posix.basename(sourcePath, path.posix.extname(sourcePath));
  }

  const title = config.documentTitles?.[sourcePath] ?? inferredTitle;
  const body = rewriteRelativeLinks(lines.join("\n").trimEnd(), sourcePath, config);
  return { body: `${body}\n`, title };
}

export function documentHash(title, body) {
  return createHash("sha256").update(title).update("\0").update(body).digest("hex");
}

export function parseJsonEnvelope(output) {
  const lines = output.split("\n");
  for (let index = 0; index < lines.length; index += 1) {
    if (!lines[index].trimStart().startsWith("{")) {
      continue;
    }
    const candidate = lines.slice(index).join("\n").trim();
    try {
      return JSON.parse(candidate);
    } catch {
      // Keep looking in case a log line happened to start with "{".
    }
  }
  throw new Error(`lark-cli did not return a JSON envelope:\n${output.trim()}`);
}

function larkCli(args, input) {
  const result = spawnSync("lark-cli", args, {
    cwd: repoRoot,
    encoding: "utf8",
    env: {
      ...process.env,
      LARKSUITE_CLI_NO_SKILLS_NOTIFIER: "1",
      LARKSUITE_CLI_NO_UPDATE_NOTIFIER: "1"
    },
    input,
    maxBuffer: 20 * 1024 * 1024
  });

  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(result.stderr.trim() || result.stdout.trim());
  }

  const envelope = parseJsonEnvelope(result.stdout);
  if (envelope.ok !== true) {
    throw new Error(JSON.stringify(envelope.error ?? envelope, null, 2));
  }
  return envelope;
}

async function loadConfig(configPath) {
  const absolutePath = resolveInsideRepo(configPath);
  const config = JSON.parse(await fs.readFile(absolutePath, "utf8"));
  if (!Array.isArray(config.roots) || config.roots.length === 0) {
    throw new Error(
      `${configPath} must define a non-empty roots array`
    );
  }
  if (!config.repositoryUrl || !config.branch) {
    throw new Error(`${configPath} must define repositoryUrl and branch`);
  }
  config.documents ??= {};
  config.folders ??= {};
  if (targetType(config) === "wiki") {
    if (
      !config.target?.spaceId ||
      !config.target?.parentNodeToken ||
      !config.target?.rootNodeToken
    ) {
      throw new Error(
        `${configPath} wiki target must define spaceId, parentNodeToken, and rootNodeToken`
      );
    }
    config.folders["."] ??= {
      name: config.target.rootTitle,
      nodeToken: config.target.rootNodeToken,
      objToken: config.target.rootObjToken,
      url: config.target.rootUrl
    };
  } else {
    if (!config.folderToken) {
      throw new Error(`${configPath} drive target must define folderToken`);
    }
    config.folders["."] ??= {
      token: config.folderToken,
      url: config.folderUrl
    };
  }
  return { absolutePath, config };
}

async function saveConfig(absolutePath, config) {
  const temporaryPath = `${absolutePath}.tmp`;
  await fs.writeFile(temporaryPath, `${JSON.stringify(config, null, 2)}\n`, {
    mode: 0o600
  });
  await fs.rename(temporaryPath, absolutePath);
}

function documentFromEnvelope(envelope) {
  const document = envelope.data?.document;
  if (!document?.document_id || !document?.url) {
    throw new Error(
      `Unexpected docs +create response: ${JSON.stringify(envelope, null, 2)}`
    );
  }
  return document;
}

function folderFromEnvelope(envelope) {
  const folder = envelope.data?.folder ?? envelope.data;
  if (!folder?.folder_token) {
    throw new Error(
      `Unexpected drive +create-folder response: ${JSON.stringify(envelope, null, 2)}`
    );
  }
  return folder;
}

function wikiNodeFromEnvelope(envelope, operation) {
  const node = envelope.data?.node ?? envelope.data;
  const nodeToken = node?.node_token ?? node?.wiki_token;
  if (!nodeToken) {
    throw new Error(
      `Unexpected ${operation} response: ${JSON.stringify(envelope, null, 2)}`
    );
  }
  return { ...node, node_token: nodeToken };
}

async function planOne(sourcePath, config) {
  const source = await fs.readFile(resolveInsideRepo(sourcePath), "utf8");
  const { body, title } = prepareMarkdown(source, sourcePath, config);
  const contentHash = documentHash(title, body);
  const existing = config.documents[sourcePath];
  const action =
    existing?.contentHash === contentHash
      ? "skip"
      : existing
        ? "update"
        : "create";

  return {
    action,
    body,
    contentHash,
    existing,
    sourcePath,
    title,
    url: existing?.url
  };
}

async function ensureRemoteDirectory(directory, configPath, config) {
  const existing = config.folders[directory];
  const existingToken = remoteDirectoryToken(directory, config);
  if (existingToken) {
    return existingToken;
  }

  const parentDirectory = path.posix.dirname(directory);
  const parentToken = await ensureRemoteDirectory(
    parentDirectory,
    configPath,
    config
  );
  const name = remoteFolderName(directory, config);
  if (targetType(config) === "wiki") {
    const envelope = larkCli([
      "wiki",
      "+node-create",
      "--as",
      "user",
      "--space-id",
      config.target.spaceId,
      "--parent-node-token",
      parentToken,
      "--title",
      name,
      "--format",
      "json"
    ]);
    const node = wikiNodeFromEnvelope(envelope, "wiki +node-create");
    config.folders[directory] = {
      createdAt: new Date().toISOString(),
      legacyDrive: existing?.token
        ? { folderToken: existing.token, url: existing.url }
        : existing?.legacyDrive,
      name,
      nodeToken: node.node_token,
      objToken: node.obj_token,
      parent: parentDirectory,
      url: wikiUrl(config, node.node_token)
    };
  } else {
    const envelope = larkCli([
      "drive",
      "+create-folder",
      "--as",
      "user",
      "--folder-token",
      parentToken,
      "--name",
      name,
      "--format",
      "json"
    ]);
    const folder = folderFromEnvelope(envelope);
    config.folders[directory] = {
      createdAt: new Date().toISOString(),
      name,
      parent: parentDirectory,
      token: folder.folder_token,
      url: folder.url
    };
  }
  await saveConfig(configPath, config);
  return remoteDirectoryToken(directory, config);
}

async function publishOne(plan, configPath, config) {
  const {
    action,
    body,
    contentHash,
    existing,
    sourcePath,
    title
  } = plan;

  if (action === "skip") {
    return { action: "skip", sourcePath, title, url: existing.url };
  }

  let documentId = existing?.documentId;
  let documentUrl = existing?.url;
  let nodeToken = existing?.nodeToken;
  let revisionId;

  if (existing) {
    const envelope = larkCli(
      [
        "docs",
        "+update",
        "--as",
        "user",
        "--doc",
        documentId,
        "--command",
        "overwrite",
        "--doc-format",
        "markdown",
        "--content",
        "-",
        "--format",
        "json"
      ],
      body
    );
    revisionId = envelope.data?.document?.revision_id;
    if (existing.title !== title) {
      const wikiTarget = targetType(config) === "wiki";
      larkCli([
        "drive",
        "files",
        "patch",
        "--as",
        "user",
        "--file-token",
        wikiTarget ? nodeToken : documentId,
        "--type",
        wikiTarget ? "wiki" : "docx",
        "--data",
        JSON.stringify({ new_title: title }),
        "--format",
        "json"
      ]);
    }
  } else {
    const parentDirectory = path.posix.dirname(sourcePath);
    const parentToken = await ensureRemoteDirectory(
      parentDirectory,
      configPath,
      config
    );
    const envelope = larkCli(
      [
        "docs",
        "+create",
        "--as",
        "user",
        "--doc-format",
        "markdown",
        "--title",
        title,
        "--parent-token",
        parentToken,
        "--content",
        "-",
        "--format",
        "json"
      ],
      body
    );
    const document = documentFromEnvelope(envelope);
    documentId = document.document_id;
    documentUrl = document.url;
    revisionId = document.revision_id;
    if (targetType(config) === "wiki") {
      const nodeEnvelope = larkCli([
        "wiki",
        "+node-get",
        "--as",
        "user",
        "--node-token",
        documentId,
        "--obj-type",
        "docx",
        "--space-id",
        config.target.spaceId,
        "--format",
        "json"
      ]);
      const node = wikiNodeFromEnvelope(nodeEnvelope, "wiki +node-get");
      nodeToken = node.node_token;
      documentUrl = wikiUrl(config, nodeToken);
    }
  }

  config.documents[sourcePath] = {
    ...existing,
    contentHash,
    documentId,
    nodeToken,
    publishedAt: new Date().toISOString(),
    revisionId,
    title,
    url: documentUrl
  };
  await saveConfig(configPath, config);
  return { action, sourcePath, title, url: documentUrl };
}

export function wikiMigrationPlan(sourcePaths, config, rootTitle = "xRag") {
  const directories = [".", ...remoteDirectoriesFor(sourcePaths)];
  return {
    directories: directories.map((directory) => ({
      action: config.folders?.[directory]?.nodeToken ? "skip" : "mkdir",
      directory,
      sourcePath: directory === "." ? "./" : `${directory}/`,
      targetPath:
        directory === "."
          ? `${rootTitle}/`
          : `${rootTitle}/${remoteDirectoryDisplayPath(directory, config)}/`
    })),
    documents: sourcePaths.map((sourcePath) => {
      const document = config.documents?.[sourcePath];
      const parentDirectory = path.posix.dirname(sourcePath);
      return {
        action: document?.nodeToken
          ? "skip"
          : document?.documentId
            ? "move"
            : "create",
        sourcePath,
        targetPath:
          parentDirectory === "."
            ? `${rootTitle}/`
            : `${rootTitle}/${remoteDirectoryDisplayPath(
                parentDirectory,
                config
              )}`,
        title: document?.title,
        url: document?.url
      };
    })
  };
}

function assertWikiMigrationTarget(config, options) {
  if (targetType(config) !== "wiki") {
    return;
  }
  const expected = {
    parentNodeToken: options.parentNodeToken,
    rootTitle: options.rootTitle,
    spaceId: options.spaceId
  };
  for (const [key, value] of Object.entries(expected)) {
    if (config.target[key] !== value) {
      throw new Error(
        `Configured Wiki target ${key}=${config.target[key]} does not match requested ${value}`
      );
    }
  }
}

async function initializeWikiTarget(options, configPath, config) {
  assertWikiMigrationTarget(config, options);
  if (targetType(config) === "wiki") {
    return config.target.rootNodeToken;
  }

  const previousRoot = config.folders["."];
  const baseUrl = config.folderUrl
    ? new URL(config.folderUrl).origin
    : undefined;
  const envelope = larkCli([
    "wiki",
    "+node-create",
    "--as",
    "user",
    "--space-id",
    options.spaceId,
    "--parent-node-token",
    options.parentNodeToken,
    "--title",
    options.rootTitle,
    "--format",
    "json"
  ]);
  const node = wikiNodeFromEnvelope(envelope, "wiki +node-create");
  const rootUrl = baseUrl ? `${baseUrl}/wiki/${node.node_token}` : undefined;

  config.target = {
    baseUrl,
    migratedFrom: {
      folderToken: config.folderToken,
      folderUrl: config.folderUrl
    },
    parentNodeToken: options.parentNodeToken,
    rootNodeToken: node.node_token,
    rootObjToken: node.obj_token,
    rootTitle: options.rootTitle,
    rootUrl,
    spaceId: options.spaceId,
    type: "wiki"
  };
  config.folders["."] = {
    createdAt: new Date().toISOString(),
    legacyDrive: previousRoot?.token
      ? { folderToken: previousRoot.token, url: previousRoot.url }
      : previousRoot?.legacyDrive,
    name: options.rootTitle,
    nodeToken: node.node_token,
    objToken: node.obj_token,
    parent: "project",
    url: rootUrl
  };
  await saveConfig(configPath, config);
  return node.node_token;
}

async function migrateDocumentToWiki(sourcePath, configPath, config) {
  const existing = config.documents[sourcePath];
  if (!existing?.documentId) {
    return {
      action: "create",
      sourcePath,
      targetPath: remoteDirectoryDisplayPath(
        path.posix.dirname(sourcePath),
        config
      )
    };
  }
  if (existing.nodeToken) {
    return {
      action: "skip",
      sourcePath,
      title: existing.title,
      url: existing.url
    };
  }

  const parentDirectory = path.posix.dirname(sourcePath);
  const parentNodeToken = remoteDirectoryToken(parentDirectory, config);
  let envelope;
  if (existing.migrationTaskId) {
    envelope = larkCli([
      "drive",
      "+task_result",
      "--as",
      "user",
      "--scenario",
      "wiki_move",
      "--task-id",
      existing.migrationTaskId,
      "--format",
      "json"
    ]);
  } else {
    envelope = larkCli([
      "wiki",
      "+move",
      "--as",
      "user",
      "--obj-type",
      "docx",
      "--obj-token",
      existing.documentId,
      "--target-space-id",
      config.target.spaceId,
      "--target-parent-token",
      parentNodeToken,
      "--format",
      "json"
    ]);
  }

  const result = envelope.data ?? {};
  if (result.failed) {
    throw new Error(
      `Wiki migration failed for ${sourcePath}: ${result.status_msg ?? "unknown error"}`
    );
  }
  if (result.ready === false) {
    if (result.task_id) {
      config.documents[sourcePath] = {
        ...existing,
        migrationTaskId: result.task_id
      };
      await saveConfig(configPath, config);
    }
    throw new Error(
      `Wiki migration is still processing for ${sourcePath}. Re-run the same command to continue.`
    );
  }

  const node = wikiNodeFromEnvelope(envelope, "wiki +move");
  config.documents[sourcePath] = {
    ...existing,
    documentUrl: existing.documentUrl ?? existing.url,
    migratedAt: new Date().toISOString(),
    nodeToken: node.node_token,
    parentNodeToken,
    spaceId: config.target.spaceId,
    url: wikiUrl(config, node.node_token)
  };
  delete config.documents[sourcePath].migrationTaskId;
  await saveConfig(configPath, config);
  return {
    action: "move",
    sourcePath,
    title: existing.title,
    url: config.documents[sourcePath].url
  };
}

async function migrateToWiki(options, configPath, config, approvedFiles) {
  const rootWasMissing = !config.folders?.["."]?.nodeToken;
  await initializeWikiTarget(options, configPath, config);
  if (rootWasMissing) {
    printResult({
      action: "mkdir",
      sourcePath: "./",
      targetPath: `${options.rootTitle}/`
    });
  }
  for (const directory of remoteDirectoriesFor(approvedFiles)) {
    const wasMissing = !remoteDirectoryToken(directory, config);
    await ensureRemoteDirectory(directory, configPath, config);
    if (wasMissing) {
      printResult({
        action: "mkdir",
        sourcePath: `${directory}/`,
        targetPath: `${options.rootTitle}/${remoteDirectoryDisplayPath(
          directory,
          config
        )}/`
      });
    }
  }
  for (const sourcePath of approvedFiles) {
    printResult(await migrateDocumentToWiki(sourcePath, configPath, config));
  }
}

async function deleteOrphan(orphan, configPath, config) {
  if (targetType(config) === "wiki") {
    if (!orphan.nodeToken) {
      throw new Error(
        `Cannot prune ${orphan.sourcePath}: missing mapped Wiki node token`
      );
    }
    larkCli([
      "wiki",
      "+node-delete",
      "--as",
      "user",
      "--node-token",
      orphan.nodeToken,
      "--obj-type",
      "wiki",
      "--space-id",
      config.target.spaceId,
      "--include-children=false",
      "--yes",
      "--format",
      "json"
    ]);
  } else {
    larkCli([
      "drive",
      "+delete",
      "--as",
      "user",
      "--file-token",
      orphan.documentId,
      "--type",
      "docx",
      "--yes",
      "--format",
      "json"
    ]);
  }

  delete config.documents[orphan.sourcePath];
  await saveConfig(configPath, config);
  return {
    action: "delete",
    sourcePath: orphan.sourcePath,
    title: orphan.title,
    url: orphan.url
  };
}

function printResult(result) {
  const suffix = result.url ? ` ${result.url}` : "";
  const target = result.targetPath ? ` -> ${result.targetPath}` : "";
  console.log(
    `${result.action.padEnd(6)} ${result.sourcePath}${target}${suffix}`
  );
}

async function main() {
  let options;
  try {
    options = parseArgs(process.argv.slice(2));
  } catch (error) {
    console.error(error.message);
    console.error(usage());
    process.exitCode = 1;
    return;
  }

  if (options.help) {
    console.log(usage());
    return;
  }

  const { absolutePath: configPath, config } = await loadConfig(options.config);
  const approvedFiles = await collectMarkdownFiles(config.roots);

  if (options.list) {
    for (const sourcePath of approvedFiles) {
      const published = config.documents[sourcePath] ? "published" : "local";
      console.log(`${published.padEnd(9)} ${sourcePath}`);
    }
    console.log(`\n${approvedFiles.length} Markdown files in approved scope.`);
    return;
  }

  let selectedFiles = approvedFiles;
  if (options.file) {
    const normalized = toPosix(path.normalize(options.file));
    if (!approvedFiles.includes(normalized)) {
      throw new Error(`${options.file} is outside the approved Markdown scope`);
    }
    selectedFiles = [normalized];
  }

  const plans = [];
  for (const sourcePath of selectedFiles) {
    plans.push(await planOne(sourcePath, config));
  }

  const changedDocuments = plans.filter((plan) => plan.action === "update");
  if (
    !options.dryRun &&
    changedDocuments.length > 0 &&
    !options.allowOverwrite
  ) {
    const paths = changedDocuments.map((plan) => `  ${plan.sourcePath}`).join("\n");
    throw new Error(
      `Changed documents require --allow-overwrite before any writes are made:\n${paths}`
    );
  }

  const missingDirectories = missingRemoteDirectories(selectedFiles, config);
  const orphans = options.sync || options.migrateToWiki
    ? orphanedDocuments(approvedFiles, config)
    : [];

  if (options.migrateToWiki) {
    assertWikiMigrationTarget(config, options);
    const migrationPlan = wikiMigrationPlan(
      approvedFiles,
      config,
      options.rootTitle
    );
    if (options.dryRun) {
      for (const directory of migrationPlan.directories) {
        printResult(directory);
      }
      for (const document of migrationPlan.documents) {
        printResult(document);
      }
      for (const plan of plans.filter((candidate) => candidate.action === "update")) {
        printResult(plan);
      }
      for (const orphan of orphans) {
        printResult({
          action: "orphan",
          sourcePath: orphan.sourcePath,
          url: orphan.url
        });
      }
      return;
    }
    if (orphans.length > 0) {
      const paths = orphans.map((orphan) => `  ${orphan.sourcePath}`).join("\n");
      throw new Error(
        `Resolve mapped orphan documents before migration:\n${paths}`
      );
    }

    await migrateToWiki(options, configPath, config, approvedFiles);
    for (const sourcePath of approvedFiles) {
      const postMigrationPlan = await planOne(sourcePath, config);
      if (postMigrationPlan.action !== "skip") {
        printResult(await publishOne(postMigrationPlan, configPath, config));
      }
    }
    return;
  }

  if (options.dryRun) {
    for (const directory of missingDirectories) {
      printResult({
        action: "mkdir",
        sourcePath: `${directory}/`,
        targetPath: `${remoteDirectoryDisplayPath(directory, config)}/`
      });
    }
    for (const plan of plans) {
      printResult(plan);
    }
    for (const orphan of orphans) {
      printResult({
        action: options.prune ? "delete" : "orphan",
        sourcePath: orphan.sourcePath,
        url: orphan.url
      });
    }
    return;
  }

  for (const directory of missingDirectories) {
    await ensureRemoteDirectory(directory, configPath, config);
    printResult({
      action: "mkdir",
      sourcePath: `${directory}/`,
      targetPath: `${remoteDirectoryDisplayPath(directory, config)}/`
    });
  }
  for (const plan of plans) {
    printResult(await publishOne(plan, configPath, config));
  }
  for (const orphan of orphans) {
    if (options.prune) {
      printResult(await deleteOrphan(orphan, configPath, config));
    } else {
      printResult({
        action: "orphan",
        sourcePath: orphan.sourcePath,
        url: orphan.url
      });
    }
  }
}

const entryPoint = process.argv[1]
  ? pathToFileURL(path.resolve(process.argv[1])).href
  : null;
if (entryPoint === import.meta.url) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
