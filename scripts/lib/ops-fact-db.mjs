import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { writeFile, rm, readFile } from "node:fs/promises";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { createRequire } from "node:module";

const require = createRequire(new URL("../../apps/api/package.json", import.meta.url));
const { Client } = require("pg");

function parseEnvContent(content) {
  const entries = {};

  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim();

    if (!line || line.startsWith("#")) {
      continue;
    }

    const separatorIndex = line.indexOf("=");
    if (separatorIndex === -1) {
      continue;
    }

    const key = line.slice(0, separatorIndex).trim();
    const value = line.slice(separatorIndex + 1);
    entries[key] = value;
  }

  return entries;
}

async function loadDeployEnvMap() {
  if (process.env.DEPLOY_ENV_FILE) {
    return parseEnvContent(process.env.DEPLOY_ENV_FILE);
  }

  if (process.env.DEPLOY_ENV_FILE_PATH) {
    const content = await readFile(process.env.DEPLOY_ENV_FILE_PATH, "utf8");
    return parseEnvContent(content);
  }

  return null;
}

async function findFreePort() {
  return await new Promise((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.on("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (!address || typeof address === "string") {
        server.close(() => reject(new Error("Unable to allocate local port for SSH tunnel.")));
        return;
      }

      const { port } = address;
      server.close((error) => {
        if (error) {
          reject(error);
          return;
        }

        resolve(port);
      });
    });
  });
}

async function waitForTunnel(port, sshProcess) {
  const deadline = Date.now() + 15_000;

  while (Date.now() < deadline) {
    if (sshProcess.exitCode !== null) {
      throw new Error(`SSH tunnel exited early with code ${sshProcess.exitCode}.`);
    }

    try {
      await new Promise((resolve, reject) => {
        const socket = net.connect({ host: "127.0.0.1", port }, () => {
          socket.destroy();
          resolve();
        });
        socket.on("error", reject);
      });
      return;
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
  }

  throw new Error("Timed out waiting for SSH tunnel to become ready.");
}

function buildDatabaseUrlFromEnvMap(envMap, port) {
  const user = envMap.POSTGRES_USER;
  const password = envMap.POSTGRES_PASSWORD;
  const database = envMap.POSTGRES_DB;

  if (!user || !password || !database) {
    throw new Error("DEPLOY_ENV_FILE is missing POSTGRES_USER, POSTGRES_PASSWORD, or POSTGRES_DB.");
  }

  const url = new URL(`postgresql://${encodeURIComponent(user)}:${encodeURIComponent(password)}@127.0.0.1:${port}/${encodeURIComponent(database)}`);
  return url.toString();
}

async function createTunnelConnection() {
  const envMap = await loadDeployEnvMap();
  if (!envMap) {
    throw new Error("Set DATABASE_URL or provide DEPLOY_ENV_FILE / DEPLOY_ENV_FILE_PATH plus SSH credentials.");
  }

  const sshHost = process.env.SSH_HOST;
  const sshUser = process.env.SSH_USER;
  const sshPort = process.env.SSH_PORT || "22";
  const sshPrivateKey = process.env.SSH_PRIVATE_KEY;

  if (!sshHost || !sshUser || !sshPrivateKey) {
    throw new Error("SSH_HOST, SSH_USER, and SSH_PRIVATE_KEY are required when DATABASE_URL is not set.");
  }

  const localPort = await findFreePort();
  const keyPath = path.join(os.tmpdir(), `xrag-ssh-key-${randomUUID()}`);
  await writeFile(keyPath, `${sshPrivateKey.replace(/\r/g, "")}\n`, { mode: 0o600 });

  const sshArgs = [
    "-N",
    "-L",
    `${localPort}:127.0.0.1:5432`,
    "-i",
    keyPath,
    "-p",
    sshPort,
    "-o",
    "BatchMode=yes",
    "-o",
    "ExitOnForwardFailure=yes",
    "-o",
    "StrictHostKeyChecking=no",
    "-o",
    "ServerAliveInterval=15",
    "-o",
    "ServerAliveCountMax=3",
    `${sshUser}@${sshHost}`
  ];

  const sshProcess = spawn("ssh", sshArgs, {
    stdio: ["ignore", "pipe", "pipe"]
  });

  let stderr = "";
  sshProcess.stderr.on("data", (chunk) => {
    stderr += chunk.toString();
  });

  try {
    await waitForTunnel(localPort, sshProcess);
  } catch (error) {
    sshProcess.kill("SIGTERM");
    await rm(keyPath, { force: true });
    const message = stderr.trim();
    throw new Error(message ? `${error instanceof Error ? error.message : String(error)} ${message}` : (error instanceof Error ? error.message : String(error)));
  }

  const client = new Client({
    connectionString: buildDatabaseUrlFromEnvMap(envMap, localPort)
  });

  await client.connect();

  return {
    client,
    async cleanup() {
      await client.end();
      if (sshProcess.exitCode === null) {
        sshProcess.kill("SIGTERM");
        await new Promise((resolve) => sshProcess.on("exit", resolve));
      }
      await rm(keyPath, { force: true });
    }
  };
}

export async function openOpsFactDatabase() {
  if (process.env.DATABASE_URL) {
    const client = new Client({
      connectionString: process.env.DATABASE_URL
    });
    await client.connect();
    return {
      client,
      async cleanup() {
        await client.end();
      }
    };
  }

  return await createTunnelConnection();
}
