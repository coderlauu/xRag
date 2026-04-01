import { Injectable, OnApplicationShutdown } from "@nestjs/common";
import {
  CreateBucketCommand,
  GetObjectCommand,
  HeadBucketCommand,
  HeadObjectCommand,
  PutObjectCommand,
  S3Client
} from "@aws-sdk/client-s3";
import { formatUrl } from "@aws-sdk/util-format-url";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";
import { loadApiEnv } from "../config/env";

interface PresignedUploadParams {
  objectKey: string;
  contentType: string;
}

@Injectable()
export class StorageService implements OnApplicationShutdown {
  private readonly env = loadApiEnv();
  private readonly client = new S3Client({
    region: this.env.storageRegion,
    endpoint: this.env.storageEndpoint,
    forcePathStyle: this.env.storageForcePathStyle,
    credentials: {
      accessKeyId: this.env.storageAccessKeyId,
      secretAccessKey: this.env.storageSecretAccessKey
    }
  });

  async createPresignedUpload(params: PresignedUploadParams) {
    await this.ensureBucketExists();

    const command = new PutObjectCommand({
      Bucket: this.env.storageBucket,
      Key: params.objectKey,
      ContentType: params.contentType
    });

    const signedUrl = await getSignedUrl(this.client, command, {
      expiresIn: this.env.uploadUrlExpiresIn
    });

    return {
      uploadUrl: signedUrl,
      expiresIn: this.env.uploadUrlExpiresIn
    };
  }

  async assertObjectExists(objectKey: string): Promise<void> {
    await this.ensureBucketExists();
    await this.client.send(
      new HeadObjectCommand({
        Bucket: this.env.storageBucket,
        Key: objectKey
      })
    );
  }

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

  async checkConnection(): Promise<void> {
    await this.ensureBucketExists();
    await this.client.send(
      new HeadBucketCommand({
        Bucket: this.env.storageBucket
      })
    );
  }

  getPublicObjectUrl(objectKey: string): string {
    const baseUrl = this.env.storagePublicUrl.replace(/\/$/, "");
    return `${baseUrl}/${this.env.storageBucket}/${objectKey}`;
  }

  async onApplicationShutdown(): Promise<void> {
    this.client.destroy();
  }

  private async ensureBucketExists(): Promise<void> {
    try {
      await this.client.send(
        new HeadBucketCommand({
          Bucket: this.env.storageBucket
        })
      );
    } catch {
      await this.client.send(
        new CreateBucketCommand({
          Bucket: this.env.storageBucket
        })
      );
    }
  }
}
