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

@Slf4j
@Component
@RequiredArgsConstructor
public class MinioFileReaderAdapter implements FileReaderPort {

    private final MinioClient minioClient;

    @Value("${minio.bucket.projects}")
    private String bucket;

    // Only files that are relevant to PubSub → Kafka migration
    private static final Set<String> TARGET_EXTENSIONS = Set.of(
            ".java", ".kt", ".yml", ".yaml", ".properties",
            ".gradle", ".kts"
    );

    // Skip these paths — they're not migration-relevant
    private static final Set<String> SKIP_PATH_FRAGMENTS = Set.of(
            "/.idea/", "/out/", "/build/", "/.gradle/",
            "/test/", "/gradlew", "gradlew.bat",
            "gradle-wrapper.jar", "gradle-wrapper.properties"
    );

    // Max bytes per file — keeps token count manageable for Groq free tier
    private static final int MAX_FILE_BYTES = 32 * 1024; // 32 KB

    // Max total files sent to AI — prevents token overflow
    private static final int MAX_FILES = 15;

    @Override
    public Map<String, String> readSourceFiles(String storageKey) {
        Map<String, String> files = new HashMap<>();

        try (InputStream raw = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(storageKey).build());
             ZipInputStream zip = new ZipInputStream(raw)) {

            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (files.size() >= MAX_FILES) {
                    log.debug("Reached max file limit ({}), stopping read", MAX_FILES);
                    break;
                }
                if (!entry.isDirectory()
                        && isTargetFile(entry.getName())
                        && !shouldSkip(entry.getName())) {
                    byte[] bytes = zip.readNBytes(MAX_FILE_BYTES);
                    files.put(entry.getName(),
                            new String(bytes, StandardCharsets.UTF_8));
                    log.debug("Read file: {} ({} bytes)", entry.getName(), bytes.length);
                }
                zip.closeEntry();
            }

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to read source files from storage key '" + storageKey + "': "
                    + e.getMessage(), e);
        }

        log.info("Read {} source files from '{}'", files.size(), storageKey);
        return Map.copyOf(files);
    }

    private boolean isTargetFile(String name) {
        String lower = name.toLowerCase();
        // Include pom.xml explicitly
        if (lower.endsWith("pom.xml")) return true;
        return TARGET_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private boolean shouldSkip(String name) {
        String lower = name.toLowerCase();
        return SKIP_PATH_FRAGMENTS.stream().anyMatch(lower::contains);
    }
}
