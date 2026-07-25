package com.app.config;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

@Configuration
public class StorageConfig {

    @Bean
    public S3Client s3Client(
            @Value("${app.storage.endpoint}") String endpoint,
            @Value("${app.storage.region}") String region,
            @Value("${app.storage.access-key-id}") String accessKeyId,
            @Value("${app.storage.secret-access-key}") String secretAccessKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageConfig.class);

    /**
     * RustFS/MinIO 都不会在启动时自动建好业务 Bucket；readiness 和业务代码都假定
     * Bucket 已存在，所以在应用启动时补一次幂等的"不存在则创建"检查。
     *
     * 对象存储在启动这一刻不可达是正常情况（例如本地容器还没起），因此这里只记录
     * 警告、不让异常向上传播——是否真正可用由 /api/v1/health/ready 反映，启动阶段
     * 不应该因为依赖未就绪而让整个应用崩溃退出。
     */
    @Bean
    public ApplicationRunner ensureStorageBucket(
            S3Client s3Client, @Value("${app.storage.bucket}") String bucket) {
        return (ApplicationArguments args) -> {
            try {
                try {
                    s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
                } catch (NoSuchBucketException notFound) {
                    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                }
            } catch (Exception exception) {
                LOGGER.warn("Could not ensure storage bucket '{}' exists at startup: {}", bucket,
                        exception.toString());
            }
        };
    }
}
