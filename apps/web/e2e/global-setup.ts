import fs from "node:fs/promises";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, "../../..");
const tmpDir = path.join(repoRoot, ".tmp", "phase-2a-e2e");
const pidsFile = path.join(tmpDir, "pids.json");
const providerPort = 4010;

async function waitForHealth(url: string) {
  for (let attempt = 0; attempt < 30; attempt += 1) {
    try {
      const response = await fetch(url);
      if (response.ok) {
        return;
      }
    } catch {
      // Retry.
    }

    await new Promise((resolve) => setTimeout(resolve, 1000));
  }

  throw new Error(`timed out waiting for ${url}`);
}

async function main() {
  await fs.mkdir(tmpDir, { recursive: true });

  const provider = spawn(path.join(repoRoot, "apps/web/node_modules/.bin/tsx"), ["e2e/mock-openai-provider.ts"], {
    cwd: path.join(repoRoot, "apps/web"),
    detached: true,
    stdio: "ignore",
    env: {
      ...process.env,
      XRAG_E2E_PROVIDER_PORT: String(providerPort)
    }
  });

  const worker = spawn(path.join(repoRoot, "apps/worker/node_modules/.bin/tsx"), ["src/main.ts"], {
    cwd: path.join(repoRoot, "apps/worker"),
    detached: true,
    stdio: "ignore",
    env: {
      ...process.env,
      EMBEDDING_PROVIDER_BASE_URL: `http://127.0.0.1:${providerPort}/v1`,
      ANSWER_PROVIDER_BASE_URL: `http://127.0.0.1:${providerPort}/v1`,
      EMBEDDING_MODEL: "mock-embedding-model",
      ANSWER_MODEL: "mock-answer-model",
      LOG_LEVEL: "warn",
      AI_PROVIDER_MAX_RETRIES: "0"
    }
  });

  provider.unref();
  worker.unref();

  await fs.writeFile(
    pidsFile,
    JSON.stringify(
      {
        providerPid: provider.pid,
        workerPid: worker.pid
      },
      null,
      2
    )
  );

  await waitForHealth(`http://127.0.0.1:${providerPort}/health`);
  await new Promise((resolve) => setTimeout(resolve, 2000));
}

export default main;
