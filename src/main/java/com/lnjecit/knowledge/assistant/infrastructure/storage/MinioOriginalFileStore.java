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

    public ObjectPage listOriginals(String token, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid listing limit");
        try {
            java.util.List<StoredObject> objects = new java.util.ArrayList<>();
            // 单页请求限制网络扫描量，并保存游标，避免被保留对象长期挡住后续对象。
            String last = null;
            java.util.Iterator<io.minio.Result<io.minio.messages.Item>> items = client().listObjects(
                            io.minio.ListObjectsArgs.builder()
                                    .bucket(bucket)
                                    .prefix("originals/")
                                    .recursive(true)
                                    .startAfter(token)
                                    .maxKeys(limit)
                                    .build())
                    .iterator();
            int scanned = 0;
            while (scanned < limit && items.hasNext()) {
                io.minio.messages.Item item = items.next().get();
                scanned++;
                last = item.objectName();
                if (!item.isDir())
                    objects.add(new StoredObject(
                            item.objectName(), item.lastModified().toInstant()));
            }
            return new ObjectPage(java.util.List.copyOf(objects), scanned == limit ? last : null);
        } catch (Exception ex) {
            throw new IllegalStateException("Object listing failed");
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
