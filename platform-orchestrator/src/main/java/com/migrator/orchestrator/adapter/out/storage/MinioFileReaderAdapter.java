package com.migrator.orchestrator.adapter.out.storage;

import com.migrator.orchestrator.domain.port.out.FileReaderPort;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Secondary adapter — reads source files from MinIO ZIP.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinioFileReaderAdapter implements FileReaderPort {

    private final MinioClient minioClient;

    @Value("${minio.bucket.projects}")
    private String bucket;

    private static final Set<String> TARGET_EXTENSIONS = Set.of(
            ".java", ".kt", ".yml", ".yaml", ".properties",
            ".gradle", ".gradle.kts", "pom.xml"
    );

    private static final int MAX_FILE_BYTES = 128 * 1024; // 128 KB per file

    @Override
    public Map<String, String> readSourceFiles(String storageKey) {
        Map<String, String> files = new HashMap<>();

        try (InputStream raw = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(storageKey).build());
             ZipInputStream zip = new ZipInputStream(raw)) {

            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && isTargetFile(entry.getName())) {
                    byte[] bytes = zip.readNBytes(MAX_FILE_BYTES);
                    files.put(entry.getName(),
                            new String(bytes, StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to read source files from storage key '" + storageKey + "': "
                    + e.getMessage(), e);
        }

        log.debug("Read {} source files from '{}'", files.size(), storageKey);
        return Map.copyOf(files);
    }

    private boolean isTargetFile(String name) {
        String lower = name.toLowerCase();
        return TARGET_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}
