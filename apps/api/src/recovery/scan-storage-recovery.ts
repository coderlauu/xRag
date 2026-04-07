import { ListObjectsV2Command, S3Client } from "@aws-sdk/client-s3";
import fs from "node:fs";
import path from "node:path";
import { loadApiEnv } from "../config/env";

interface RecoveryObject {
  bucket: string;
  objectKey: string;
  sizeBytes: number;
  lastModified: string | null;
  etag: string | null;
  uploadId: string | null;
  fileName: string;
  sourceType: "pdf" | "file";
}

interface RecoveryManifest {
  generatedAt: string;
  bucket: string;
  prefix: string;
  totalObjects: number;
  totalBytes: number;
  items: RecoveryObject[];
}

function parseArgs(argv: string[]) {
  const parsed = {
    prefix: "uploads/",
    outputPath: ""
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];

    if (arg === "--prefix") {
      parsed.prefix = argv[index + 1] || parsed.prefix;
      index += 1;
      continue;
    }

    if (arg === "--output") {
      parsed.outputPath = argv[index + 1] || "";
      index += 1;
      continue;
    }
  }

  return parsed;
}

function inferSourceType(fileName: string): "pdf" | "file" {
  return fileName.toLowerCase().endsWith(".pdf") ? "pdf" : "file";
}

function inferUploadMetadata(objectKey: string) {
  const pattern = /^uploads\/\d{4}\/\d{2}\/\d{2}\/([0-9a-f-]{36})\/(.+)$/i;
  const matched = objectKey.match(pattern);

  if (!matched) {
    return {
      uploadId: null,
      fileName: path.posix.basename(objectKey)
    };
  }

  return {
    uploadId: matched[1],
    fileName: matched[2]
  };
}

async function listRecoverableObjects(prefix: string): Promise<RecoveryManifest> {
  const env = loadApiEnv();
  const client = new S3Client({
    region: env.storageRegion,
    endpoint: env.storageEndpoint,
    forcePathStyle: env.storageForcePathStyle,
    credentials: {
      accessKeyId: env.storageAccessKeyId,
      secretAccessKey: env.storageSecretAccessKey
    }
  });

  try {
    const items: RecoveryObject[] = [];
    let continuationToken: string | undefined;

    do {
      const response = await client.send(
        new ListObjectsV2Command({
          Bucket: env.storageBucket,
          Prefix: prefix,
          ContinuationToken: continuationToken
        })
      );

      for (const object of response.Contents || []) {
        if (!object.Key) {
          continue;
        }

        const inferred = inferUploadMetadata(object.Key);
        items.push({
          bucket: env.storageBucket,
          objectKey: object.Key,
          sizeBytes: object.Size ?? 0,
          lastModified: object.LastModified?.toISOString() || null,
          etag: object.ETag || null,
          uploadId: inferred.uploadId,
          fileName: inferred.fileName,
          sourceType: inferSourceType(inferred.fileName)
        });
      }

      continuationToken = response.IsTruncated ? response.NextContinuationToken : undefined;
    } while (continuationToken);

    const totalBytes = items.reduce((sum, item) => sum + item.sizeBytes, 0);

    return {
      generatedAt: new Date().toISOString(),
      bucket: env.storageBucket,
      prefix,
      totalObjects: items.length,
      totalBytes,
      items: items.sort((left, right) => left.objectKey.localeCompare(right.objectKey))
    };
  } finally {
    client.destroy();
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const manifest = await listRecoverableObjects(args.prefix);
  const serialized = `${JSON.stringify(manifest, null, 2)}\n`;

  if (args.outputPath) {
    fs.writeFileSync(args.outputPath, serialized, "utf8");
    process.stdout.write(
      `Recovery manifest written to ${args.outputPath} (${manifest.totalObjects} objects, ${manifest.totalBytes} bytes)\n`
    );
    return;
  }

  process.stdout.write(serialized);
}

void main();
