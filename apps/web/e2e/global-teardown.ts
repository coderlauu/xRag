import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, "../../..");
const pidsFile = path.join(repoRoot, ".tmp", "phase-2a-e2e", "pids.json");

async function terminate(pid: number | undefined) {
  if (!pid || !Number.isInteger(pid)) {
    return;
  }

  try {
    process.kill(pid, "SIGTERM");
  } catch {
    return;
  }

  await new Promise((resolve) => setTimeout(resolve, 500));

  try {
    process.kill(pid, 0);
  } catch {
    return;
  }

  try {
    process.kill(pid, "SIGKILL");
  } catch {
    return;
  }
}

async function main() {
  try {
    const raw = await fs.readFile(pidsFile, "utf8");
    const parsed = JSON.parse(raw) as { providerPid?: number; workerPid?: number };
    await terminate(parsed.providerPid);
    await terminate(parsed.workerPid);
    await fs.rm(pidsFile, { force: true });
  } catch {
    // Nothing to clean up.
  }
}

export default main;
