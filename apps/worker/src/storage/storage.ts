import { GetObjectCommand, S3Client } from "@aws-sdk/client-s3";
import { loadWorkerEnv } from "../config/env";

export class WorkerStorageService {
  private readonly env = loadWorkerEnv();
  private readonly client = new S3Client({
    region: this.env.storageRegion,
    endpoint: this.env.storageEndpoint,
    forcePathStyle: this.env.storageForcePathStyle,
    credentials: {
      accessKeyId: this.env.storageAccessKeyId,
      secretAccessKey: this.env.storageSecretAccessKey
    }
  });

  async getObjectBody(objectKey: string): Promise<string> {
    const bytes = await this.getObjectBytes(objectKey);
    return Buffer.from(bytes).toString("utf8");
  }

  async getObjectBytes(objectKey: string): Promise<Uint8Array> {
    const response = await this.client.send(
      new GetObjectCommand({
        Bucket: this.env.storageBucket,
        Key: objectKey
      })
    );

    if (!response.Body) {
      return new Uint8Array();
    }

    return response.Body.transformToByteArray();
  }

  destroy() {
    this.client.destroy();
  }
}
