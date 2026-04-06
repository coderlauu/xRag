import fs from "node:fs/promises";
import path from "node:path";

const repoRoot = path.resolve(import.meta.dirname, "..");
const errors = [];

async function collectFiles(dir, predicate) {
  const entries = await fs.readdir(dir, { withFileTypes: true });
  const files = [];

  for (const entry of entries) {
    const resolved = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await collectFiles(resolved, predicate)));
      continue;
    }

    if (predicate(resolved)) {
      files.push(resolved);
    }
  }

  return files;
}

function extractImportSpecifiers(source) {
  const specifiers = new Set();
  const pattern =
    /\bimport\s+(?:type\s+)?(?:[^"'()]+?\s+from\s+)?["']([^"']+)["']|\bexport\s+[^"'()]+?\s+from\s+["']([^"']+)["']|\bimport\s*\(\s*["']([^"']+)["']\s*\)/g;

  for (const match of source.matchAll(pattern)) {
    const specifier = match[1] ?? match[2] ?? match[3];
    if (specifier) {
      specifiers.add(specifier);
    }
  }

  return [...specifiers];
}

function toPosix(value) {
  return value.split(path.sep).join("/");
}

function isTsSource(filePath) {
  return filePath.endsWith(".ts") || filePath.endsWith(".tsx");
}

async function checkWebBoundary() {
  const files = await collectFiles(path.join(repoRoot, "apps/web/src"), isTsSource);
  const allowedApiClientImporter = path.join(repoRoot, "apps/web/src/lib/api.ts");

  for (const file of files) {
    const source = await fs.readFile(file, "utf8");
    const imports = extractImportSpecifiers(source);

    if (file !== allowedApiClientImporter && imports.includes("@xrag/api-client")) {
      errors.push(
        `${toPosix(path.relative(repoRoot, file))}: web layer must access backend through apps/web/src/lib/api.ts, not import @xrag/api-client directly`
      );
    }
  }
}

function isForbiddenControllerImport(specifier) {
  return specifier.endsWith(".repository") || specifier.endsWith(".repository.ts");
}

function isForbiddenServiceImport(specifier) {
  return specifier.endsWith(".controller") || specifier.endsWith(".controller.ts");
}

function isForbiddenRepositoryImport(specifier) {
  if (specifier.endsWith("/database/database.service") || specifier === "../database/database.service") {
    return false;
  }

  return (
    specifier.endsWith(".controller") ||
    specifier.endsWith(".controller.ts") ||
    specifier.endsWith(".service") ||
    specifier.endsWith(".service.ts")
  );
}

async function checkApiLayers() {
  const files = await collectFiles(path.join(repoRoot, "apps/api/src"), isTsSource);

  for (const file of files) {
    const source = await fs.readFile(file, "utf8");
    const imports = extractImportSpecifiers(source);
    const rel = toPosix(path.relative(repoRoot, file));

    if (file.endsWith(".controller.ts")) {
      for (const specifier of imports) {
        if (isForbiddenControllerImport(specifier)) {
          errors.push(`${rel}: controller must not import repository layer directly (${specifier})`);
        }
      }
    }

    if (file.endsWith(".service.ts")) {
      for (const specifier of imports) {
        if (isForbiddenServiceImport(specifier)) {
          errors.push(`${rel}: service must not import controller layer (${specifier})`);
        }
      }
    }

    if (file.endsWith(".repository.ts")) {
      for (const specifier of imports) {
        if (isForbiddenRepositoryImport(specifier)) {
          errors.push(`${rel}: repository must not import controller/service layer (${specifier})`);
        }
      }
    }
  }
}

async function checkWorkerBoundary() {
  const files = await collectFiles(path.join(repoRoot, "apps/worker/src"), isTsSource);
  const forbiddenImports = ["@nestjs/", "@fastify/", "express", "fastify"];

  for (const file of files) {
    const source = await fs.readFile(file, "utf8");
    const imports = extractImportSpecifiers(source);
    const rel = toPosix(path.relative(repoRoot, file));

    if (file.endsWith(".controller.ts")) {
      errors.push(`${rel}: worker must not define HTTP controllers`);
    }

    for (const specifier of imports) {
      if (forbiddenImports.some((prefix) => specifier === prefix || specifier.startsWith(prefix))) {
        errors.push(`${rel}: worker must not depend on HTTP/server framework imports (${specifier})`);
      }
    }
  }
}

await checkWebBoundary();
await checkApiLayers();
await checkWorkerBoundary();

if (errors.length > 0) {
  console.error("Structure check failed:");
  for (const error of errors) {
    console.error(`- ${error}`);
  }
  process.exit(1);
}

console.log("Structure checks passed.");
