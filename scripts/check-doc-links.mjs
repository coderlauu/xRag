import fs from "node:fs/promises";
import path from "node:path";

const repoRoot = path.resolve(import.meta.dirname, "..");
const markdownRoots = [
  path.join(repoRoot, "AGENTS.md"),
  path.join(repoRoot, "docs"),
  path.join(repoRoot, "tech"),
  path.join(repoRoot, "design"),
  path.join(repoRoot, "deploy")
];
const errors = [];

async function collectMarkdownFiles(target) {
  const stats = await fs.stat(target);
  if (stats.isFile()) {
    return target.endsWith(".md") ? [target] : [];
  }

  const entries = await fs.readdir(target, { withFileTypes: true });
  const files = [];

  for (const entry of entries) {
    const resolved = path.join(target, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await collectMarkdownFiles(resolved)));
      continue;
    }

    if (resolved.endsWith(".md")) {
      files.push(resolved);
    }
  }

  return files;
}

function stripFragment(target) {
  return target.split("#")[0];
}

function resolveRepoAbsoluteTarget(target) {
  const normalized = path.normalize(target);

  if (!path.isAbsolute(normalized)) {
    return null;
  }

  const candidateNames = [
    path.basename(repoRoot),
    path.basename(path.dirname(repoRoot))
  ].filter(Boolean);

  for (const repoName of candidateNames) {
    const marker = `${path.sep}${repoName}${path.sep}`;
    const markerIndex = normalized.lastIndexOf(marker);

    if (markerIndex === -1) {
      continue;
    }

    const repoRelativePath = normalized.slice(markerIndex + marker.length);
    return path.join(repoRoot, repoRelativePath);
  }

  return null;
}

function isSkippable(target) {
  return (
    target.startsWith("http://") ||
    target.startsWith("https://") ||
    target.startsWith("mailto:") ||
    target.startsWith("data:")
  );
}

function resolveTarget(filePath, target) {
  if (target.startsWith("/")) {
    const stripped = stripFragment(target);
    return resolveRepoAbsoluteTarget(stripped) ?? stripped;
  }

  return path.resolve(path.dirname(filePath), stripFragment(target));
}

function toPosix(value) {
  return value.split(path.sep).join("/");
}

const linkPattern = /!?\[[^\]]*]\(([^)]+)\)/g;
const files = [];
for (const root of markdownRoots) {
  files.push(...(await collectMarkdownFiles(root)));
}

for (const file of files) {
  const source = await fs.readFile(file, "utf8");
  for (const match of source.matchAll(linkPattern)) {
    const target = match[1].trim();
    if (!target || isSkippable(target)) {
      continue;
    }

    const resolved = resolveTarget(file, target);

    try {
      await fs.access(resolved);
    } catch {
      errors.push(`${toPosix(path.relative(repoRoot, file))}: missing markdown target ${target}`);
    }
  }
}

if (errors.length > 0) {
  console.error("Documentation link check failed:");
  for (const error of errors) {
    console.error(`- ${error}`);
  }
  process.exit(1);
}

console.log("Documentation links passed.");
