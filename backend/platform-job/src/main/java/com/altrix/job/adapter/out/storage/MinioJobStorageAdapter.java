package com.altrix.job.adapter.out.storage;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * Secondary adapter — retrieves migrated ZIP from MinIO for download.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinioJobStorageAdapter {

    private final MinioClient minioClient;

    @Value("${minio.bucket.projects}")
    private String bucket;

    public InputStream getMigratedZip(String storageKey) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucket)
                            .object(storageKey)
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to retrieve migrated ZIP '" + storageKey + "': " + e.getMessage(), e);
        }
    }
}
