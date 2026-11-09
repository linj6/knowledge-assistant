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
    private final int maxFileBytes;

    public MinioOriginalFileStore(String endpoint, String accessKey, String secretKey, String bucket) {
        this(
                endpoint,
                accessKey,
                secretKey,
                bucket,
                new com.lnjecit.knowledge.assistant.infrastructure.config.UploadProperties());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MinioOriginalFileStore(
            @Value("${assistant.minio.endpoint}") String endpoint,
            @Value("${assistant.minio.access-key}") String accessKey,
            @Value("${assistant.minio.secret-key}") String secretKey,
            @Value("${assistant.minio.bucket}") String bucket,
            com.lnjecit.knowledge.assistant.infrastructure.config.UploadProperties uploads) {
        uploads.afterPropertiesSet();
        this.maxFileBytes = uploads.getMaxFileBytes();
        this.bucket = bucket;
        // 未配置凭据时允许离线启动，实际上传明确失败；构造客户端不进行网络请求。
        this.client = accessKey.isBlank() || secretKey.isBlank()
                ? null
                : MinioClient.builder()
                        .endpoint(endpoint)
                        .credentials(accessKey, secretKey)
                        .httpClient(new okhttp3.OkHttpClient.Builder()
                                .connectTimeout(java.time.Duration.ofSeconds(5))
                                .readTimeout(java.time.Duration.ofSeconds(30))
                                .writeTimeout(java.time.Duration.ofSeconds(30))
                                .callTimeout(java.time.Duration.ofSeconds(60))
                                .build())
                        .build();
    }

    private MinioClient client() {
        if (client == null) throw new IllegalStateException("Object storage is not configured");
        return client;
    }

    public void copy(String sourceKey, String targetKey) {
        if (sourceKey == null
                || !sourceKey.startsWith(ObjectKeyPrefixes.STAGING)
                || targetKey == null
                || !targetKey.startsWith(ObjectKeyPrefixes.ORIGINALS)
                || sourceKey.length() == ObjectKeyPrefixes.STAGING.length()
                || targetKey.length() == ObjectKeyPrefixes.ORIGINALS.length())
            throw new IllegalArgumentException("Invalid object copy prefixes");
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
            return readLimited(stream, maxFileBytes);
        } catch (Exception ex) {
            if (ex instanceof java.net.SocketTimeoutException || ex instanceof java.net.ConnectException)
                throw new com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionException(
                        com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionException.Code
                                .INFRASTRUCTURE_TEMPORARY);
            throw new IllegalStateException("Object read failed");
        }
    }

    // 多读一个字节即可判定超限，避免损坏或异常对象耗尽堆内存。
    static byte[] readLimited(InputStream stream, int maximum) throws java.io.IOException {
        byte[] bytes = stream.readNBytes(maximum + 1);
        if (bytes.length > maximum) throw new IllegalStateException("Object exceeds upload limit");
        return bytes;
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
