import fs from "node:fs/promises";
import path from "node:path";
import { DocumentBuilder, SwaggerModule } from "@nestjs/swagger";
import { createApp } from "./bootstrap";

async function main() {
  const app = await createApp();
  await app.init();

  try {
    const config = new DocumentBuilder()
      .setTitle("xRag API")
      .setVersion("0.3.0")
      .setDescription("Phase 1B API contract")
      .addServer("http://localhost:3001")
      .build();
    const document = SwaggerModule.createDocument(app, config);
    const outputPath = path.resolve(process.cwd(), "../../docs/generated/openapi/phase-1b-api.json");
    await fs.mkdir(path.dirname(outputPath), { recursive: true });
    await fs.writeFile(outputPath, JSON.stringify(document, null, 2));
    process.stdout.write(`${outputPath}\n`);
  } finally {
    await app.close();
  }
}

void main();
