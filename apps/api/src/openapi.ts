import fs from "node:fs/promises";
import path from "node:path";
import { DocumentBuilder, SwaggerModule } from "@nestjs/swagger";
import { createApp } from "./bootstrap";

function isIgnorableShutdownError(error: unknown): boolean {
  return error instanceof Error && error.message === "Connection is closed.";
}

async function main() {
  const app = await createApp();
  await app.init();

  try {
    const config = new DocumentBuilder()
      .setTitle("xRag API")
      .setVersion("0.7.0")
      .setDescription("Phase 3A API contract")
      .addServer("http://localhost:3001")
      .build();
    const document = SwaggerModule.createDocument(app, config);
    const outputPath = path.resolve(process.cwd(), "../../docs/generated/openapi/phase-2a-api.json");
    await fs.mkdir(path.dirname(outputPath), { recursive: true });
    await fs.writeFile(outputPath, JSON.stringify(document, null, 2));
    process.stdout.write(`${outputPath}\n`);
  } finally {
    await app.close();
  }
}

process.on("uncaughtException", (error) => {
  if (isIgnorableShutdownError(error)) {
    return;
  }

  throw error;
});

process.on("unhandledRejection", (reason) => {
  if (isIgnorableShutdownError(reason)) {
    return;
  }

  throw reason;
});

void main();
