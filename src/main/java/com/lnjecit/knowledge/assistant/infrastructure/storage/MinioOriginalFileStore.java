package com.lnjecit.knowledge.assistant.infrastructure.storage;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MinioOriginalFileStore implements OriginalFileStore {
    private final MinioClient client;
    private final String bucket;

    public MinioOriginalFileStore(
            @Value("${assistant.minio.endpoint}") String endpoint,
            @Value("${assistant.minio.access-key}") String accessKey,
            @Value("${assistant.minio.secret-key}") String secretKey,
            @Value("${assistant.minio.bucket}") String bucket) {
        this.bucket = bucket;
        // 未配置凭据时允许离线启动，实际上传明确失败；构造客户端不进行网络请求。
        this.client = accessKey.isBlank() || secretKey.isBlank()
                ? null
                : MinioClient.builder()
                        .endpoint(endpoint)
                        .credentials(accessKey, secretKey)
                        .build();
    }

    private MinioClient client() {
        if (client == null) throw new IllegalStateException("Object storage is not configured");
        return client;
    }

    public void copy(String sourceKey, String targetKey) {
        if (sourceKey == null
                || !sourceKey.startsWith("staging/")
                || targetKey == null
                || !targetKey.startsWith("originals/")
                || sourceKey.length() == 8
                || targetKey.length() == 10) throw new IllegalArgumentException("Invalid object copy prefixes");
        try {
            client().copyObject(io.minio.CopyObjectArgs.builder()
                    .bucket(bucket)
                    .object(targetKey)
                    .source(io.minio.CopySource.builder()
                            .bucket(bucket)
                            .object(sourceKey)
                            .build())
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("Object copy failed");
        }
    }

    public void put(String key, byte[] bytes) {
        try {
            client().putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(
                            new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("application/octet-stream")
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("Object upload failed");
        }
    }

    public byte[] read(String key) {
        try (InputStream stream = client().getObject(
                        GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return stream.readAllBytes();
        } catch (Exception ex) {
            throw new IllegalStateException("Object read failed");
        }
    }

    public void delete(String key) {
        try {
            client().removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("Object cleanup failed");
        }
    }
}
