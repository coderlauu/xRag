import { Injectable, OnApplicationShutdown } from "@nestjs/common";
import {
  CompleteMultipartUploadCommand,
  CreateBucketCommand,
  CreateMultipartUploadCommand,
  GetObjectCommand,
  HeadBucketCommand,
  HeadObjectCommand,
  PutObjectCommand,
  S3Client,
  UploadPartCommand
} from "@aws-sdk/client-s3";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";
import { loadApiEnv } from "../config/env";

interface PresignedUploadParams {
  objectKey: string;
  contentType: string;
}

interface PresignedMultipartPartParams {
  objectKey: string;
  uploadId: string;
  partNumber: number;
}

interface CompleteMultipartUploadPart {
  etag: string;
  partNumber: number;
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
  private readonly publicClient = new S3Client({
    region: this.env.storageRegion,
    endpoint: this.env.storagePublicUrl,
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

    const signedUrl = await getSignedUrl(this.publicClient, command, {
      expiresIn: this.env.uploadUrlExpiresIn
    });

    return {
      uploadUrl: signedUrl,
      expiresIn: this.env.uploadUrlExpiresIn
    };
  }

  async createMultipartUpload(params: PresignedUploadParams) {
    await this.ensureBucketExists();

    const response = await this.client.send(
      new CreateMultipartUploadCommand({
        Bucket: this.env.storageBucket,
        Key: params.objectKey,
        ContentType: params.contentType
      })
    );

    if (!response.UploadId) {
      throw new Error("storage did not return multipart upload id");
    }

    return {
      uploadId: response.UploadId,
      expiresIn: this.env.uploadUrlExpiresIn
    };
  }

  async createPresignedUploadPart(params: PresignedMultipartPartParams) {
    await this.ensureBucketExists();

    const command = new UploadPartCommand({
      Bucket: this.env.storageBucket,
      Key: params.objectKey,
      UploadId: params.uploadId,
      PartNumber: params.partNumber
    });

    const uploadUrl = await getSignedUrl(this.publicClient, command, {
      expiresIn: this.env.uploadUrlExpiresIn
    });

    return {
      uploadUrl,
      expiresIn: this.env.uploadUrlExpiresIn
    };
  }

  async completeMultipartUpload(params: {
    objectKey: string;
    uploadId: string;
    parts: CompleteMultipartUploadPart[];
  }) {
    await this.ensureBucketExists();

    await this.client.send(
      new CompleteMultipartUploadCommand({
        Bucket: this.env.storageBucket,
        Key: params.objectKey,
        UploadId: params.uploadId,
        MultipartUpload: {
          Parts: params.parts.map((part) => ({
            ETag: part.etag,
            PartNumber: part.partNumber
          }))
        }
      })
    );
  }

  async assertObjectExists(objectKey: string): Promise<void> {
    await this.getObjectMetadata(objectKey);
  }

  async getObjectMetadata(objectKey: string): Promise<{ size: number | null }> {
    await this.ensureBucketExists();
    const response = await this.client.send(
      new HeadObjectCommand({
        Bucket: this.env.storageBucket,
        Key: objectKey
      })
    );

    return {
      size: typeof response.ContentLength === "number" ? response.ContentLength : null
    };
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

  async onApplicationShutdown(): Promise<void> {
    this.client.destroy();
    this.publicClient.destroy();
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
