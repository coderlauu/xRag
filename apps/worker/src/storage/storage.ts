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
    const response = await this.client.send(
      new GetObjectCommand({
        Bucket: this.env.storageBucket,
        Key: objectKey
      })
    );

    if (!response.Body) {
      return "";
    }

    return response.Body.transformToString();
  }

  destroy() {
    this.client.destroy();
  }
}
