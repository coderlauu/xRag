import fs from "node:fs/promises";
import path from "node:path";
import { createOpenApiSpec } from "./openapi-spec";

async function main() {
  const document = createOpenApiSpec();
  const outputPath = path.resolve(process.cwd(), "../../docs/generated/openapi/phase-1a-api.json");
  await fs.mkdir(path.dirname(outputPath), { recursive: true });
  await fs.writeFile(outputPath, JSON.stringify(document, null, 2));
  process.stdout.write(`${outputPath}\n`);
}

void main();
