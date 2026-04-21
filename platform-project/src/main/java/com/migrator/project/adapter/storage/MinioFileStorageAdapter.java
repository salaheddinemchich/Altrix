package com.migrator.project.adapter.storage;

import com.migrator.project.domain.port.out.FileStoragePort;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.UUID;

/**
 * Secondary adapter — implements {@link FileStoragePort} using MinIO.
 *
 * <p>The domain service never imports MinIO. It calls {@link FileStoragePort}.
 * This adapter is the only class in the entire codebase that imports MinIO SDK.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinioFileStorageAdapter implements FileStoragePort {

    private final MinioClient minioClient;

    @Value("${minio.bucket.projects}")
    private String bucket;

    @Override
    public String store(InputStream content, long contentLength, String contentType) {
        String objectKey = "uploads/" + UUID.randomUUID() + ".zip";

        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .stream(content, contentLength, -1)
                            .contentType(contentType)
                            .build()
            );
            log.debug("Stored object '{}' in bucket '{}'", objectKey, bucket);
            return objectKey;

        } catch (Exception e) {
            throw new RuntimeException("Failed to store file in MinIO: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream retrieve(String storageKey) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucket)
                            .object(storageKey)
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to retrieve file '" + storageKey + "' from MinIO: " + e.getMessage(), e);
        }
    }
}
