import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync, rmSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repoRoot = path.dirname(fileURLToPath(new URL("../package.json", import.meta.url)));
const workerDistDir = path.join(repoRoot, "apps/worker/dist");
const emittedEntrypoint = path.join(workerDistDir, "apps/worker/src/main.js");
const legacyEntrypoint = path.join(workerDistDir, "main.js");

test("worker runtime entrypoints point at emitted build output", () => {
  const workerPackageJson = JSON.parse(
    readFileSync(path.join(repoRoot, "apps/worker/package.json"), "utf8")
  );
  const dockerfile = readFileSync(path.join(repoRoot, "deploy/docker/worker.Dockerfile"), "utf8");

  assert.equal(workerPackageJson.scripts.start, "node dist/apps/worker/src/main.js");
  assert.match(dockerfile, /CMD \["node", "apps\/worker\/dist\/apps\/worker\/src\/main\.js"\]/);
});

test("worker build emits only the configured runtime entrypoint", () => {
  rmSync(workerDistDir, { recursive: true, force: true });

  const build = spawnSync("pnpm", ["--filter", "@xrag/worker", "build"], {
    cwd: repoRoot,
    encoding: "utf8"
  });

  assert.equal(build.status, 0, `${build.stdout}\n${build.stderr}`);
  assert.equal(existsSync(emittedEntrypoint), true, "expected emitted worker entrypoint to exist");
  assert.equal(
    existsSync(legacyEntrypoint),
    false,
    "legacy dist/main.js should not exist after a clean worker build"
  );
});
