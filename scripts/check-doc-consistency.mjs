import fs from "node:fs/promises";
import path from "node:path";

const repoRoot = path.resolve(import.meta.dirname, "..");
const errors = [];

function toPosix(value) {
  return value.split(path.sep).join("/");
}

function resolveRepoPath(target) {
  const normalized = path.normalize(target);

  if (!path.isAbsolute(normalized)) {
    return path.resolve(repoRoot, normalized);
  }

  try {
    return path.relative(repoRoot, normalized).startsWith("..")
      ? resolveRepoAbsoluteTarget(normalized) ?? normalized
      : normalized;
  } catch {
    return resolveRepoAbsoluteTarget(normalized) ?? normalized;
  }
}

function resolveRepoAbsoluteTarget(target) {
  const repoName = path.basename(repoRoot);
  const marker = `${path.sep}${repoName}${path.sep}`;
  const markerIndex = target.lastIndexOf(marker);

  if (markerIndex === -1) {
    return null;
  }

  const repoRelativePath = target.slice(markerIndex + marker.length);
  return path.join(repoRoot, repoRelativePath);
}

async function readFile(filePath) {
  return fs.readFile(filePath, "utf8");
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function extractLink(content, label) {
  const pattern = new RegExp(`${escapeRegExp(label)}[^[]*\\[[^\\]]+\\]\\(([^)]+)\\)`);
  const match = content.match(pattern);
  return match ? match[1] : null;
}

function extractMetadataValue(content, key) {
  const pattern = new RegExp(`- \`${escapeRegExp(key)}\`: \`([^\\\`]+)\``);
  const match = content.match(pattern);
  return match ? match[1] : null;
}

async function collectPlanFiles(dir) {
  const entries = await fs.readdir(dir, { withFileTypes: true });
  return entries
    .filter((entry) => entry.isFile() && entry.name.endsWith(".md"))
    .map((entry) => path.join(dir, entry.name))
    .sort();
}

function ensure(condition, message) {
  if (!condition) {
    errors.push(message);
  }
}

function contentIncludesPathLink(content, targetPath) {
  const absolutePosix = toPosix(targetPath);
  const repoRelativePosix = toPosix(path.relative(repoRoot, targetPath));

  return content.includes(absolutePosix) || content.includes(repoRelativePosix);
}

const currentPath = path.join(repoRoot, "docs/handoff/current.md");
const currentContent = await readFile(currentPath);
const currentVersionHandoff = extractLink(currentContent, "当前有效版本：");
const currentStatus = extractLink(currentContent, "当前版本状态：");

ensure(Boolean(currentVersionHandoff), "docs/handoff/current.md: missing current version handoff link");
ensure(Boolean(currentStatus), "docs/handoff/current.md: missing current status link");

if (currentVersionHandoff && currentStatus) {
  const statusPath = resolveRepoPath(currentStatus);
  const statusContent = await readFile(statusPath);

  const linkedCurrentHandoff = extractLink(statusContent, "`current_handoff`:");
  const linkedVersionHandoff = extractLink(statusContent, "`version_handoff`:");
  const updatedAt = extractMetadataValue(statusContent, "updated_at");

  ensure(
    resolveRepoPath(linkedCurrentHandoff) === currentPath,
    `docs/status consistency: current_handoff should point to ${toPosix(currentPath)}`
  );
  ensure(
    resolveRepoPath(linkedVersionHandoff) === resolveRepoPath(currentVersionHandoff),
    `docs/status consistency: version_handoff should point to ${currentVersionHandoff}`
  );

  const activePlans = await collectPlanFiles(path.join(repoRoot, "docs/exec-plans/active"));
  for (const planPath of activePlans) {
    const planContent = await readFile(planPath);
    const rel = toPosix(path.relative(repoRoot, planPath));
    const planStatus = extractMetadataValue(planContent, "status");
    ensure(planStatus === "active", `${rel}: active exec plan must declare \`status: active\``);
    ensure(
      contentIncludesPathLink(statusContent, planPath),
      `docs/status consistency: status file must link active exec plan ${rel}`
    );
    ensure(
      contentIncludesPathLink(currentContent, planPath),
      `docs/handoff/current.md must link active exec plan ${rel}`
    );
  }

  const completedPlans = await collectPlanFiles(path.join(repoRoot, "docs/exec-plans/completed"));
  for (const planPath of completedPlans) {
    const planContent = await readFile(planPath);
    const rel = toPosix(path.relative(repoRoot, planPath));
    const planStatus = extractMetadataValue(planContent, "status");
    ensure(planStatus !== "active", `${rel}: completed exec plan must not declare \`status: active\``);
  }

  const newestPlanDate = activePlans
    .map((planPath) => path.basename(planPath).match(/^(\d{4}-\d{2}-\d{2})/)?.[1] ?? null)
    .filter(Boolean)
    .sort()
    .at(-1);

  if (updatedAt && newestPlanDate) {
    ensure(
      updatedAt >= newestPlanDate,
      `docs freshness: active status updated_at (${updatedAt}) must be >= newest active exec plan date (${newestPlanDate})`
    );
  }

  if (statusPath.endsWith("v2-phase-1b.md")) {
    const openApiArtifact = path.join(repoRoot, "docs/generated/openapi/phase-1b-api.json");
    try {
      await fs.access(openApiArtifact);
    } catch {
      errors.push("docs consistency: missing docs/generated/openapi/phase-1b-api.json for active Phase 1B status");
    }
  }

  if (statusPath.endsWith("v3-phase-1c.md")) {
    const openApiArtifact = path.join(repoRoot, "docs/generated/openapi/phase-1c-api.json");
    try {
      await fs.access(openApiArtifact);
    } catch {
      errors.push("docs consistency: missing docs/generated/openapi/phase-1c-api.json for active Phase 1C status");
    }
  }

  if (statusPath.endsWith("v4-phase-2a.md")) {
    const openApiArtifact = path.join(repoRoot, "docs/generated/openapi/phase-2a-api.json");
    try {
      await fs.access(openApiArtifact);
    } catch {
      errors.push("docs consistency: missing docs/generated/openapi/phase-2a-api.json for active Phase 2A status");
    }
  }
}

if (errors.length > 0) {
  console.error("Documentation consistency check failed:");
  for (const error of errors) {
    console.error(`- ${error}`);
  }
  process.exit(1);
}

console.log("Documentation consistency checks passed.");
