package com.altrix.orchestrator.adapter.out.storage;

import com.altrix.orchestrator.domain.port.out.FileReaderPort;
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

    // Max bytes per file — keeps token count manageable for Groq free tier.
    // This is the REAL per-AI-call token guard (the migrator sends one file
    // per call), so it stays tight.
    private static final int MAX_FILE_BYTES = 32 * 1024; // 32 KB

    /**
     * Max source files read from the project ZIP.  Crucially this is NOT a
     * per-prompt limit — the migrator processes one file per AI call, and the
     * analyzer applies its OWN {@code MAX_PROMPT_FILES} cap before building a
     * prompt.  This cap exists only to bound memory on a pathologically huge
     * upload.  It MUST be high enough to include every source file, because
     * any file dropped here is simply absent from the migrated artifact and
     * the sandbox compile then fails with "package/class does not exist".
     *
     * <p>Was hard-coded to 15, which silently truncated any project larger
     * than 15 files (e.g. dropped an entire package) — the cause of the
     * missing-symbol compile failures.  Now configurable, defaulting high.
     */
    @Value("${migration.max-source-files:1000}")
    private int maxFiles;

    @Override
    public Map<String, String> readSourceFiles(String storageKey) {
        Map<String, String> files = new HashMap<>();

        try (InputStream raw = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(storageKey).build());
             ZipInputStream zip = new ZipInputStream(raw)) {

            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (files.size() >= maxFiles) {
                    log.warn("Reached max source-file limit ({}) for '{}' — remaining files skipped. "
                            + "Raise migration.max-source-files if the project is larger.", maxFiles, storageKey);
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

    @Override
    public Map<String, String> readAllFiles(String storageKey) {
        Map<String, String> files = new HashMap<>();
        try (InputStream raw = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(storageKey).build());
             ZipInputStream zip = new ZipInputStream(raw)) {

            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && !shouldSkip(entry.getName())) {
                    byte[] bytes = zip.readNBytes(MAX_FILE_BYTES);
                    // Only index files that decoded cleanly as UTF-8 text
                    String content = new String(bytes, StandardCharsets.UTF_8);
                    if (!content.isBlank()) {
                        files.put(entry.getName(), content);
                    }
                }
                zip.closeEntry();
            }
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to read all files from storage key '" + storageKey + "': "
                            + e.getMessage(), e);
        }
        log.info("Read {} total files from '{}' for RAG indexing", files.size(), storageKey);
        return Map.copyOf(files);
    }

    private boolean shouldSkip(String name) {
        String lower = name.toLowerCase();
        return SKIP_PATH_FRAGMENTS.stream().anyMatch(lower::contains);
    }

    @Override
    public java.util.Set<String> listAllPaths(String storageKey) {
        java.util.Set<String> paths = new java.util.LinkedHashSet<>();
        try (InputStream raw = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(storageKey).build());
             ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    paths.add(entry.getName());
                }
                zip.closeEntry();
            }
        } catch (Exception e) {
            log.warn("Could not list paths from '{}': {}", storageKey, e.getMessage());
        }
        return paths;
    }

    /**
     * Issue #119 — single-file extraction.  Streams the ZIP entry-by-entry
     * and returns the first match, so memory usage stays O(1) per request
     * regardless of archive size.  Reads up to a generous 1 MB cap per file
     * (the AI-side {@link #MAX_FILE_BYTES} 32 KB cap is irrelevant here —
     * we are serving the raw file for human review, not feeding the LLM).
     */
    @Override
    public String readSingleFile(String storageKey, String path) {
        if (path == null || path.isBlank()) return null;
        final int diffMaxBytes = 1024 * 1024;

        try (InputStream raw = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(storageKey).build());
             ZipInputStream zip = new ZipInputStream(raw)) {

            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && path.equals(entry.getName())) {
                    byte[] bytes = zip.readNBytes(diffMaxBytes);
                    return new String(bytes, StandardCharsets.UTF_8);
                }
                zip.closeEntry();
            }
            return null;

        } catch (Exception e) {
            log.warn("Could not read '{}' from '{}': {}", path, storageKey, e.getMessage());
            return null;
        }
    }
}
