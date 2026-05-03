package com.altrix.orchestrator.adapter.out.storage;

import com.altrix.common.domain.model.MigratedFile;
import com.altrix.orchestrator.domain.port.out.MigratedFileStoragePort;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Secondary adapter — packs migrated files into a ZIP and stores in MinIO.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinioMigratedFileStorageAdapter implements MigratedFileStoragePort {

    private final MinioClient minioClient;

    @Value("${minio.bucket.projects}")
    private String bucket;

    @Override
    public String storeMigratedZip(String jobId, List<MigratedFile> migratedFiles) {
        String objectKey = "migrated/" + jobId + "/output.zip";

        try {
            byte[] zipBytes = buildZip(migratedFiles);

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .stream(new ByteArrayInputStream(zipBytes),
                                    zipBytes.length, -1)
                            .contentType("application/zip")
                            .build()
            );

            log.info("Stored migrated ZIP at '{}' ({} bytes)", objectKey, zipBytes.length);
            return objectKey;

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to store migrated ZIP for job '" + jobId + "': "
                    + e.getMessage(), e);
        }
    }

    private byte[] buildZip(List<MigratedFile> files) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (MigratedFile file : files) {
                ZipEntry entry = new ZipEntry(file.newPath());
                zos.putNextEntry(entry);
                zos.write(file.content().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }
}
