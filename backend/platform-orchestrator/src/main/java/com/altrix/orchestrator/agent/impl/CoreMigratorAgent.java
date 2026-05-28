package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.enums.DocumentType;
import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.ApprovedPlan;
import com.altrix.common.domain.model.DocumentChunk;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.model.PrunedContext;
import com.altrix.orchestrator.domain.model.rag.FileProvenance;
import com.altrix.orchestrator.domain.model.rag.FileProvenance.DocReference;
import com.altrix.orchestrator.domain.model.sandbox.SandboxContext;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.AiPort;
import com.altrix.orchestrator.domain.port.out.EmbeddingStorePort;
import com.altrix.orchestrator.domain.port.out.FileMigrationCachePort;
import com.altrix.orchestrator.domain.port.out.FileProvenanceRepository;
import com.altrix.orchestrator.domain.port.out.FileReaderPort;
import com.altrix.orchestrator.infrastructure.ai.ContextPruner;
import com.altrix.orchestrator.infrastructure.ai.PubSubDetector;
import com.altrix.orchestrator.infrastructure.migration.PomSanitizer;
import com.altrix.orchestrator.infrastructure.migration.ProjectSymbolValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 3 — Core Migrator (typed pipeline variant).
 *
 * <p>Reads source files from MinIO via the {@code storageKey} stored in
 * the approved plan, prunes the file set via {@link ContextPruner} (#27),
 * then rewrites each Java file that contains Pub/Sub code using the
 * powerful AI model. Non-Java files and files without Pub/Sub code are
 * passed through unchanged.
 *
 * <p>When a {@code retryContext} is present in the approved plan (#48) it is
 * prepended to the system prompt so the model can fix previously detected issues.
 *
 * <p>Per-file AI failures are non-fatal: the original file is kept and the
 * migration summary notes the skip, so the pipeline completes even when the
 * AI is intermittently unavailable.
 */
@Slf4j
@Component("typedCoreMigratorAgent")
@RequiredArgsConstructor
public class CoreMigratorAgent implements MigrationAgent<ApprovedPlan, MigrationArtifact> {

    private static final String SYSTEM_PROMPT = """
            You are a migration expert.  Rewrite the following file (Java source,
            Maven pom.xml, Gradle build script, application.yml / application.properties,
            or Jakarta EE / Spring config XML) to migrate from Google Cloud Pub/Sub
            to Apache Kafka.

            Recognise BOTH styles of GCP Pub/Sub a project may use:

            (A) Modern Spring Cloud GCP — package org.springframework.cloud.gcp.pubsub.*:
                - @PubSubListener / @SubscriberHandler  →  @KafkaListener
                - PubSubTemplate / MessagePublisher     →  KafkaTemplate<String, String>
                - imports under google.cloud.pubsub.*   →  imports under org.springframework.kafka.*

            (B) Legacy GCP Pub/Sub REST v1 — package com.google.api.services.pubsub.*:
                - com.google.api.services.pubsub.Pubsub client
                    →  KafkaProducer<String, String>  +  KafkaConsumer<String, String>
                - com.google.api.services.pubsub.model.PubsubMessage
                    →  ProducerRecord<String, String>
                - com.google.api.services.pubsub.model.ReceivedMessage
                    →  ConsumerRecord<String, String>
                - Pubsub.Projects.Topics.publish(...)
                    →  kafkaTemplate.send(topic, value)  (or producer.send(new ProducerRecord<>(...)))
                - Pubsub.Projects.Subscriptions.pull(...)
                    →  consumer.poll(Duration.ofSeconds(N))  (or annotate the method @KafkaListener)
                - User-defined wrappers named "PubsubService", "PubSubService",
                  "PubsubClient", "PubSubClient" — replace topic/subscription
                  create / get / publish / pull calls with their Kafka equivalents.

            (C) Maven pom.xml — replace dependency declarations:
                - <artifactId>google-api-services-pubsub</artifactId>     →  REMOVE
                - <artifactId>google-cloud-pubsub</artifactId>            →  REMOVE
                - <artifactId>spring-cloud-gcp-pubsub</artifactId>        →  REMOVE
                - <artifactId>spring-cloud-gcp-starter-pubsub</artifactId> →  REMOVE
                For Spring Boot projects ADD:
                  <dependency>
                    <groupId>org.springframework.kafka</groupId>
                    <artifactId>spring-kafka</artifactId>
                  </dependency>
                For Jakarta EE / plain Java projects ADD:
                  <dependency>
                    <groupId>org.apache.kafka</groupId>
                    <artifactId>kafka-clients</artifactId>
                    <version>3.7.1</version>
                  </dependency>
                Preserve every unrelated dependency, plugin, property, and the
                surrounding XML structure exactly as is.

            (D) Gradle build.gradle / build.gradle.kts — same swap as above using
                the Gradle DSL (implementation 'org.springframework.kafka:spring-kafka'
                or implementation 'org.apache.kafka:kafka-clients:3.7.1').

            (E) application.yml / application.properties — bootstrap config:
                - REMOVE: spring.cloud.gcp.pubsub.*, GOOGLE_APPLICATION_CREDENTIALS,
                          PUBSUB_EMULATOR_HOST, gcp.pubsub.* keys
                - ADD: spring.kafka.bootstrap-servers (default: localhost:9092)
                       spring.kafka.consumer.group-id (= the application name)
                       spring.kafka.consumer.auto-offset-reset = earliest
                       spring.kafka.producer.key-serializer / value-serializer
                For a .properties file use dotted keys; for a .yaml file use the
                nested-map form.  Preserve every unrelated property/setting.

            (F) Java config classes that wire topics, publishers, subscribers (e.g.
                a "PubsubConfig" class with constants for topic names, or @Bean
                methods returning PubSubTemplate / Pubsub clients):
                - Topic-name constants stay (they're still valid Kafka topic names).
                - @Bean PubSubTemplate / @Bean Pubsub                →  @Bean KafkaTemplate
                - Topic + subscription creation via Pubsub.Projects.* → @Bean NewTopic
                  (Spring Kafka auto-creates topics declared as NewTopic beans).
                - Subscription objects → KafkaListener configuration

            Rules:
            - Preserve ALL business logic exactly.  Preserve every existing Javadoc
              and inline comment that is NOT Pub/Sub-specific.
            - Preserve package declarations, class names, and method signatures unless
              the migration requires a different argument or return type (e.g.
              ReceivedMessage → ConsumerRecord<String, String>).
            - Replace EVERY Pub/Sub import with the corresponding Kafka import.
            - For Jakarta EE / EJB @Singleton @Startup @Schedule classes, keep the
              lifecycle annotations and replace the manual pull loop body with the
              equivalent Kafka consumer call.
            - If the file genuinely contains NO Pub/Sub references after this analysis,
              return its content exactly as provided.

            HARD CONSTRAINTS — violating any of these breaks the build for the user:
            * REPLACE the implementation INLINE.  Do NOT leave the original Pub/Sub
              code (or alternative Kafka approaches) as commented-out blocks.  The
              method body must contain the real, runnable Kafka code, not a sketch
              with several options in comments.
            * Do NOT add narrative comments like `// Renamed from PubsubService`,
              `// Kafka auto-creates topics`, `// RECOMMENDED APPROACH`, or any
              other meta-commentary about the migration itself.  The output is
              source code, not a migration report.
            * Do NOT use markdown formatting inside comments — no **bold**, no
              _italic_, no `#` headers.  Comments must be valid plain Java / XML /
              YAML comments.
            * Do NOT wrap the output in markdown code fences (```java, ```xml,
              ```yaml, ```).  Return raw file content only.
            * Return the COMPLETE file.  If the file is large, prioritize finishing
              the implementation over preserving comments — but NEVER emit a
              partial file ending in `...`, `// truncated`, or an open XML tag.
            * For Kafka consumers in Java code, assume the default StringDeserializer
              for value().  Therefore `record.value()` is a String — do NOT call
              `.getAttributes()` or other Pub/Sub message methods on it.  If the
              original code used message attributes, switch to record.headers() or
              parse the value string accordingly.

            Return ONLY the complete rewritten file content (matching the original
            file's format: Java, XML, YAML, .properties).  No explanations, no
            markdown fences, no leading file path.
            """;

    /**
     * Hard rules appended on every migration call so the AI doesn't break
     * cross-file references by renaming things.  The migrator processes
     * files in isolation — if {@code PubsubConfig} becomes {@code KafkaConfig}
     * in one file, every other file still imports the old name and the
     * compile blows up.  Keep names stable; only the implementations swap.
     */
    private static final String IDENTITY_PRESERVATION_RULES = """

            CROSS-FILE IDENTITY RULES — violating any of these guarantees a broken build:
            * Do NOT rename classes.  PubsubConfig stays named PubsubConfig.
              PubsubService stays named PubsubService.  PubsubServiceImpl
              stays named PubsubServiceImpl.  Only their internal
              implementations switch from Pub/Sub to Kafka.
            * Do NOT rename packages.  com.example.altrix.pubsub stays
              com.example.altrix.pubsub.  Do NOT introduce a new
              com.example.altrix.kafka package.
            * Do NOT rename public constants or fields.
              PubsubConfig.ORDERS_CREATED stays PubsubConfig.ORDERS_CREATED.
              Topic names (the VALUES of those constants) can change if
              they need a Kafka-valid form, but the Java identifier must not.
            * Do NOT rename enum types.  PaymentStatus stays PaymentStatus
              with the same value names.
            * Do NOT delete files.  If a file has no Pub/Sub code, return
              it unchanged byte-for-byte.
            * For XML files (pom.xml, beans.xml, web.xml): a comment body
              MUST NOT contain `--` — that's invalid XML and Maven will
              refuse to parse the POM.  Preserve the original characters in
              comments exactly (including em-dashes `—`); do not normalise
              them to `--`.
            * Do NOT invent class names.  Every type you reference in
              imports, field types, parameter types, or method calls MUST
              either (a) be present in the file you were given, (b) be
              present in another file of this project that already has
              that exact simple name, or (c) be a real, documented class
              of the kafka-clients / Jakarta EE standard library.  When in
              doubt — when no real Kafka equivalent exists for a Pub/Sub
              concept (IAM permissions, push subscriptions, etc.) — leave
              the original code in place and add a `// TODO altrix:` comment
              explaining what manual follow-up is needed.  Inventing a
              plausible-sounding class name guarantees a "cannot find
              symbol" compile failure.
            * NEVER explain your decision in prose.  Do NOT write phrases
              like "Here is the file", "Since the provided file …", or
              "The file does not require any modifications".  If the file
              needs no changes, return the original file BYTES exactly —
              and ONLY those bytes.  For a `.java` file the output must
              start with `package` (or with an `import` / comment / blank
              line that precedes the package declaration); for `pom.xml`
              it must start with `<?xml` or `<project`.  A response that
              starts with English prose will be rejected and the original
              kept.
            """;

    /** Inserted at the FRONT of the system prompt when the project is plain
     *  Jakarta EE (no Spring on the classpath).  Forbids Spring annotations
     *  / Spring Kafka and mandates raw kafka-clients + Jakarta lifecycle. */
    private static final String JAKARTA_EE_PREFIX = """
            DETECTED STACK: Jakarta EE 10 (NO Spring on the classpath).

            This project uses jakarta.platform:jakarta.jakartaee-api with
            EJB / CDI / JAX-RS.  The migration MUST stay on Jakarta APIs.

            FORBIDDEN — these will not compile (no Spring deps exist):
              * org.springframework.* (any package — kafka, stereotype, messaging, beans, …)
              * @Component, @Service, @Autowired, @Configuration, @Bean
              * @KafkaListener, KafkaTemplate, @SendTo, @Payload, @Header,
                KafkaHeaders, Acknowledgment (org.springframework.kafka.support)

            REQUIRED for Pub/Sub → Kafka here:
              * org.apache.kafka:kafka-clients — KafkaProducer<String,String>,
                KafkaConsumer<String,String>, ProducerRecord<>, ConsumerRecord<>
              * @Singleton + @Startup + @Schedule (jakarta.ejb.*) for the
                poller — keep the existing EJB scheduling pattern,
                replacing the pubsubService.pull(...) body with
                consumer.poll(Duration.ofSeconds(N)).
              * @ApplicationScoped (jakarta.enterprise.context.*) + a
                @Produces method (jakarta.enterprise.inject.Produces) for
                wiring the KafkaProducer / KafkaConsumer singletons —
                exactly how PubsubClientProducer wires the Pubsub client today.
              * @Inject from jakarta.inject.* — NEVER @Autowired.

            """;

    /** Default prefix when the project IS Spring Boot.  Keep terse — the
     *  rest of the prompt already assumes Spring + spring-kafka. */
    private static final String SPRING_BOOT_PREFIX = """
            DETECTED STACK: Spring Boot.

            """;

    private final AiPort aiPort;
    private final FileReaderPort fileReader;
    private final ContextPruner contextPruner;
    private final FileMigrationCachePort migrationCache;
    /** #1 — RAG retrieval per file.  Optional: when the vector store is
     *  unavailable, every call returns an empty list and we fall back to
     *  the static system prompt only. */
    private final EmbeddingStorePort embeddingStore;
    /** #1 — persists the per-file provenance once a migration loop completes. */
    private final FileProvenanceRepository fileProvenanceRepository;
    /** Strips hallucinated dependency entries from migrated pom.xml output. */
    private final PomSanitizer pomSanitizer;
    /** Cross-file consistency check — catches files referencing intra-project
     *  classes that don't exist anywhere in the artifact (typical model
     *  failure: renames {@code PubsubService} → {@code KafkaService} in
     *  importers without creating the new class).  Such files revert to
     *  the original so the build can still proceed. */
    private final ProjectSymbolValidator projectSymbolValidator;

    /** How many doc chunks to retrieve per file.  Small on purpose so the
     *  prompt doesn't balloon; the AI gets enough to anchor on without
     *  blowing the context window. */
    private static final int RAG_TOP_K = 3;
    /** Max chars of each chunk we include in the prompt; also the length
     *  of the snippet we persist for the UI. */
    private static final int RAG_SNIPPET_CHARS = 400;

    @Override
    public String getName() {
        return "Core Migrator";
    }

    @Override
    public int getOrder() {
        return 3;
    }

    @Override
    public MigrationArtifact execute(ApprovedPlan input) {
        if (input == null) throw new AgentFailureException(getName(), "input ApprovedPlan was null");

        String projectId = input.plan().projectId();
        String storageKey = input.plan().storageKey();
        log.info("[{}] migrating project '{}' (approved by '{}'{})",
                getName(), projectId, input.approvedBy(),
                input.retryContext() != null ? ", retry" : "");

        if (storageKey == null || storageKey.isBlank()) {
            log.warn("[{}] no storageKey in plan — returning empty artifact", getName());
            return new MigrationArtifact(projectId, List.of(), "No files to migrate (storageKey missing)");
        }

        try {
            Map<String, String> allFiles = fileReader.readSourceFiles(storageKey);
            PrunedContext pruned = contextPruner.prune(allFiles, input.plan());

            // Detect Jakarta EE vs Spring Boot from the pom and prepend the
            // appropriate stack-specific prefix to the system prompt.
            // Without this, the AI defaulted to Spring annotations
            // (@KafkaListener, KafkaTemplate, @Component, etc.) on plain
            // Jakarta EE projects with no Spring on the classpath — the
            // result was 30+ "package org.springframework.* does not exist"
            // compile errors in the sandbox.  Detection is cheap: just a
            // substring scan of pom.xml.
            boolean isJakarta = isJakartaProject(allFiles);
            String stackPrefix = isJakarta ? JAKARTA_EE_PREFIX : SPRING_BOOT_PREFIX;
            String baseSystemPrompt = stackPrefix + SYSTEM_PROMPT + IDENTITY_PRESERVATION_RULES;
            String effectiveSystemPrompt = input.retryContext() != null && !input.retryContext().isBlank()
                    ? input.retryContext() + "\n\n" + baseSystemPrompt
                    : baseSystemPrompt;
            // perFileProvenance accumulates which doc chunks the embedding
            // store handed back for each file we actually migrated.  Insertion
            // order matters (LinkedHashMap) so the UI renders files in the
            // order they were touched, which matches the timeline.
            // Wrapped synchronized because the per-file workers write into
            // it concurrently when MAX_CONCURRENT_FILE_MIGRATIONS > 1.
            Map<String, List<DocReference>> perFileProvenance =
                    java.util.Collections.synchronizedMap(new LinkedHashMap<>());
            List<MigratedFile> migrated = migrateFiles(pruned.files(), effectiveSystemPrompt, perFileProvenance, isJakarta);

            // Include unchanged versions of files excluded by the pruner
            List<MigratedFile> result = new ArrayList<>(migrated);
            for (Map.Entry<String, String> entry : allFiles.entrySet()) {
                if (!pruned.files().containsKey(entry.getKey())) {
                    result.add(unchanged(entry.getKey(), entry.getValue(), "Excluded by context pruner"));
                }
            }

            // Cross-file consistency pass: revert any file whose intra-project
            // imports reference classes that don't exist in the artifact (the
            // model invented `KafkaService` etc. without creating the class).
            // Reverted files keep the original content, so the build can
            // proceed with the per-file failures the retry loop can actually
            // act on, instead of dying on "cannot find symbol".
            result = revertFilesWithUnresolvedImports(result, allFiles);

            persistProvenance(perFileProvenance);

            long modifiedCount = result.stream()
                    .filter(f -> f.changeType() == FileChangeType.MODIFIED).count();
            String summary = "Migrated %d/%d file(s) for project '%s' (pruned %d file(s))"
                    .formatted(modifiedCount, result.size(), projectId, pruned.prunedFiles());
            return new MigrationArtifact(projectId, result, summary);
        } catch (Exception e) {
            log.error("[{}] migration failed for project '{}': {}", getName(), projectId, e.getMessage());
            throw new AgentFailureException(getName(), "file migration failed: " + e.getMessage());
        }
    }

    /**
     * Max concurrent AI calls during a single migration run.  Bounded
     * conservatively to stay under provider rate limits; raising it past
     * 4 tends to trip 429s on Groq's free tier.  The validator-retry
     * loop already handles transient failures, so this cap is the
     * primary throttle.
     */
    private static final int MAX_CONCURRENT_FILE_MIGRATIONS = 4;

    /**
     * Per-file migration loop.  AI calls happen in parallel up to
     * {@link #MAX_CONCURRENT_FILE_MIGRATIONS}; the cheap classifier
     * checks ({@code PubSubDetector}) stay on the calling thread.
     *
     * <p>Was sequential before — a 10-file Pub/Sub project would queue
     * 10 × ~30 s AI calls back-to-back (~5 minutes just for migration).
     * Parallel-4 cuts that to roughly N/4 of the slowest call, which is
     * the dominant speedup for small-to-medium projects.
     */
    private List<MigratedFile> migrateFiles(Map<String, String> sourceFiles, String systemPrompt,
                                            Map<String, List<DocReference>> perFileProvenance,
                                            boolean isJakarta) {
        // Pre-classify: files that don't need an AI call drop straight into
        // the result list as UNCHANGED.  Only the genuine migration targets
        // are submitted to the executor — saves us from spinning up threads
        // just to short-circuit.
        List<MigratedFile> straightThrough = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Map.Entry<String, String>> toMigrate = new ArrayList<>();
        for (Map.Entry<String, String> entry : sourceFiles.entrySet()) {
            String path = entry.getKey();
            String content = entry.getValue();
            if (!PubSubDetector.isMigratableFile(path)) {
                straightThrough.add(unchanged(path, content, "Not a migratable file type"));
            } else if (!PubSubDetector.hasPubSubCode(content)) {
                straightThrough.add(unchanged(path, content, "No Pub/Sub code detected"));
            } else {
                toMigrate.add(entry);
            }
        }

        if (toMigrate.isEmpty()) return new ArrayList<>(straightThrough);

        int poolSize = Math.min(MAX_CONCURRENT_FILE_MIGRATIONS, toMigrate.size());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                poolSize,
                r -> {
                    Thread t = new Thread(r, "migrator-file-worker");
                    t.setDaemon(true);
                    return t;
                });

        List<java.util.concurrent.Future<MigratedFile>> futures = new ArrayList<>(toMigrate.size());
        try {
            for (Map.Entry<String, String> entry : toMigrate) {
                futures.add(pool.submit(() ->
                        migrateOneFile(entry.getKey(), entry.getValue(), systemPrompt, perFileProvenance, isJakarta)));
            }

            List<MigratedFile> result = new ArrayList<>(straightThrough);
            for (java.util.concurrent.Future<MigratedFile> f : futures) {
                try {
                    result.add(f.get());
                } catch (Exception e) {
                    log.warn("[{}] worker failed unexpectedly: {}", getName(), e.getMessage());
                    // Worker swallows its own failures and returns an UNCHANGED
                    // MigratedFile — getting here means a JVM-level fault
                    // (OOM, interrupt).  Skip; the file is missing from the
                    // result, which sandbox compile will surface clearly.
                }
            }
            return result;
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Migrate a single file.  Runs on a worker thread — must be reentrant
     * and not depend on caller-thread state.  Provenance map is shared but
     * only written here; the map type is supplied as a synchronised
     * LinkedHashMap by the caller so put() + iteration are thread-safe.
     */
    private MigratedFile migrateOneFile(String path, String content, String systemPrompt,
                                        Map<String, List<DocReference>> perFileProvenance,
                                        boolean isJakarta) {
        // #1 — pull the most-relevant doc chunks for this file BEFORE the
        // cache check so the prompt is RAG-augmented on every call.
        // Including chunk-content hashes in the cache key means a doc
        // update invalidates the cache automatically.
        List<DocumentChunk> ragChunks = retrieveDocs(content, isJakarta);
        String ragSection = buildRagSection(ragChunks);
        // Record provenance per file regardless of cache outcome — the
        // user wants to see which docs informed THIS file's migration,
        // even when the rewrite came from cache.  Persisting after EACH
        // file (not at the end of the loop) is what makes the panel
        // "stream" in the UI — the auto-refresh on the frontend sees
        // entries appear as they're produced.
        if (!ragChunks.isEmpty()) {
            synchronized (perFileProvenance) {
                perFileProvenance.put(path, toDocReferences(ragChunks));
            }
            persistProvenance(perFileProvenance);
        }

        // #28 — content-addressed cache.  Key includes the system prompt so
        // a prompt tweak forces a fresh AI call.  Cuts iterative-dev cost to
        // zero when nothing in the source changed.
        String cacheKey = computeCacheKey(systemPrompt + ragSection, path, content);
        Optional<String> cached = migrationCache.get(cacheKey);
        if (cached.isPresent()) {
            String migrated = cached.get();
            FileChangeType changeType = migrated.equals(content)
                    ? FileChangeType.UNCHANGED : FileChangeType.MODIFIED;
            return MigratedFile.builder()
                    .originalPath(path).newPath(path).content(migrated)
                    .changeType(changeType).diffSummary("Migrated Pub/Sub → Kafka (cache hit)")
                    .build();
        }

        try {
            String userMessage = "File: " + path + "\n\n" + content + ragSection;
            String raw = aiPort.chat(systemPrompt, userMessage);
            String migrated = stripLeadingProse(stripMarkdownFences(raw), path);

            // POM-specific guard: drop any new <dependency> the AI inserted
            // that the original didn't have and isn't on the configured
            // allow-list (e.g. kafka-clients).  Caught real production case
            // where the model hallucinated `hibernate-entitymanager`.
            if (isPom(path) && pomSanitizer != null) {
                migrated = pomSanitizer.stripHallucinatedDependencies(content, migrated);
            }

            if (looksTruncated(migrated, content)) {
                log.warn("[{}] AI output for '{}' looks truncated ({} chars vs {} original) — keeping original",
                        getName(), path, migrated.length(), content.length());
                return unchanged(path, content, "Migration skipped — AI output truncated");
            }

            // Structural safety net: a malformed build/config file fails the
            // ENTIRE Maven/Gradle build before any source compiles
            // ("Non-parseable POM ...").  That's strictly worse than not
            // migrating the file at all.  For XML we actually PARSE the
            // result; if it fails we try a known auto-repair (the model
            // often normalises em-dashes inside <!-- comments --> to "--",
            // which is illegal XML) before falling back to the original.
            if (looksStructurallyBroken(migrated, path)) {
                String repaired = repairXmlCommentDashes(migrated);
                if (!repaired.equals(migrated) && !looksStructurallyBroken(repaired, path)) {
                    log.info("[{}] auto-repaired XML comment dashes in '{}'", getName(), path);
                    migrated = repaired;
                } else {
                    log.warn("[{}] migrated '{}' failed a structural sanity check — keeping original", getName(), path);
                    return unchanged(path, content, "Migration skipped — output failed structural check");
                }
            }

            FileChangeType changeType = migrated.equals(content)
                    ? FileChangeType.UNCHANGED : FileChangeType.MODIFIED;
            // Only cache successful migrations — never cache an unchanged
            // pass-through (saves no AI call) nor a truncation fallback.
            if (changeType == FileChangeType.MODIFIED) {
                migrationCache.put(cacheKey, migrated);
            }
            return MigratedFile.builder()
                    .originalPath(path).newPath(path).content(migrated)
                    .changeType(changeType).diffSummary("Migrated Pub/Sub → Kafka")
                    .build();
        } catch (Exception e) {
            log.warn("[{}] AI failed for '{}', keeping original: {}", getName(), path, e.getMessage());
            return unchanged(path, content, "Migration skipped — AI unavailable");
        }
    }

    /**
     * Scans pom.xml / build.gradle* to decide whether this is a plain
     * Jakarta EE project (no Spring on the classpath).  Substring scan is
     * intentional — we only need a strong signal about which Kafka API to
     * target, not a full POM parse.
     */
    /**
     * Cross-file consistency post-pass.  Builds a path → content map of the
     * MODIFIED files, asks {@link ProjectSymbolValidator} to flag imports
     * that don't resolve inside the artifact, and reverts each flagged file
     * back to its original content.  Reverted files come from {@code allFiles}.
     *
     * <p>Conservative on purpose: the symbol index is built from the entire
     * artifact (modified + unchanged), so a file is only reverted when the
     * intra-project import is unresolved against EVERYTHING — not when one
     * sibling-file happens to also drop the same import.
     */
    private List<MigratedFile> revertFilesWithUnresolvedImports(List<MigratedFile> migratedFiles,
                                                                Map<String, String> allFiles) {
        if (projectSymbolValidator == null || migratedFiles.isEmpty()) return migratedFiles;

        Map<String, String> snapshot = new LinkedHashMap<>();
        for (MigratedFile f : migratedFiles) {
            String path = f.newPath() != null ? f.newPath() : f.originalPath();
            if (path != null && f.content() != null) snapshot.put(path, f.content());
        }
        String basePackage = projectSymbolValidator.inferBasePackage(snapshot);
        if (basePackage == null || basePackage.isBlank()) return migratedFiles;

        Map<String, java.util.Set<String>> unresolved =
                projectSymbolValidator.findUnresolvedImports(basePackage, snapshot);
        if (unresolved.isEmpty()) return migratedFiles;

        List<MigratedFile> repaired = new ArrayList<>(migratedFiles.size());
        for (MigratedFile f : migratedFiles) {
            String path = f.newPath() != null ? f.newPath() : f.originalPath();
            java.util.Set<String> bad = unresolved.get(path);
            if (bad == null || bad.isEmpty()
                    || f.changeType() != FileChangeType.MODIFIED
                    || !allFiles.containsKey(path)) {
                repaired.add(f);
                continue;
            }
            log.warn("[{}] reverting '{}' — intra-project import(s) reference missing classes: {}",
                    getName(), path, bad);
            repaired.add(unchanged(path, allFiles.get(path),
                    "Reverted — imports reference classes not present in the artifact: " + bad));
        }
        return repaired;
    }

    private boolean isJakartaProject(Map<String, String> allFiles) {
        String pom = allFiles.getOrDefault("pom.xml", "");
        String gradle = allFiles.getOrDefault("build.gradle", "");
        String gradleKts = allFiles.getOrDefault("build.gradle.kts", "");
        String all = pom + "\n" + gradle + "\n" + gradleKts;
        boolean isSpringBoot = all.contains("spring-boot-starter");
        boolean isJakartaEe = all.contains("jakarta.jakartaee-api")
                           || all.contains("javax.javaee-api")
                           || all.contains("microprofile");
        boolean jakarta = isJakartaEe && !isSpringBoot;
        log.info("[{}] detected stack: {}", getName(), jakarta ? "Jakarta EE (kafka-clients)" : "Spring Boot");
        return jakarta;
    }

    /**
     * Retrieves top-K documentation chunks most relevant to the file's
     * content.  Returns an empty list when the embedding store is
     * unavailable / disabled / errors — the migration still runs, just
     * without RAG anchoring.
     *
     * <p>On a Jakarta project we DROP Spring-specific doc chunks: feeding the
     * model Spring-Kafka reference material (e.g. docs.spring.io/spring-kafka)
     * was actively pushing it to emit {@code org.springframework.kafka.*}
     * code that can't compile against a plain Jakarta classpath.  We over-
     * fetch then filter so a Jakarta file still gets up to RAG_TOP_K
     * non-Spring chunks.
     */
    private List<DocumentChunk> retrieveDocs(String fileContent, boolean isJakarta) {
        if (embeddingStore == null) return List.of();
        try {
            int fetch = isJakarta ? RAG_TOP_K * 3 : RAG_TOP_K; // over-fetch to survive filtering
            List<DocumentChunk> chunks = embeddingStore.findRelevant(
                    fileContent,
                    null, // null projectId = search across the shared documentation corpus
                    List.of(DocumentType.DOCUMENTATION),
                    fetch);
            if (isJakarta) {
                chunks = chunks.stream()
                        .filter(c -> !isSpringDoc(c))
                        .limit(RAG_TOP_K)
                        .toList();
            }
            return chunks;
        } catch (Exception e) {
            log.debug("[{}] RAG retrieval skipped ({})", getName(), e.getMessage());
            return List.of();
        }
    }

    /** True when a doc chunk is Spring-specific (by logical path or source URL). */
    private static boolean isSpringDoc(DocumentChunk c) {
        String path = c.filePath() != null ? c.filePath().toLowerCase() : "";
        String url  = c.sourceUrl() != null ? c.sourceUrl().toLowerCase() : "";
        return path.contains("spring") || url.contains("spring");
    }

    /**
     * Formats retrieved chunks as a "Reference documentation" appendix to
     * the user message.  Kept clearly delimited so the AI doesn't confuse
     * reference material with the file to rewrite.
     */
    private String buildRagSection(List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n--- Reference documentation (use as guidance, do not copy verbatim) ---\n");
        for (DocumentChunk c : chunks) {
            String text = c.text() != null ? c.text() : "";
            if (text.length() > RAG_SNIPPET_CHARS) text = text.substring(0, RAG_SNIPPET_CHARS) + "...";
            sb.append("\n[")
              .append(c.filePath() != null ? c.filePath() : "doc")
              .append("] (")
              .append(c.sourceUrl() != null ? c.sourceUrl() : "")
              .append(")\n")
              .append(text)
              .append('\n');
        }
        return sb.toString();
    }

    /** Map domain chunks to lightweight UI-facing references. */
    private List<DocReference> toDocReferences(List<DocumentChunk> chunks) {
        List<DocReference> refs = new ArrayList<>(chunks.size());
        for (DocumentChunk c : chunks) {
            String text = c.text() != null ? c.text() : "";
            String snippet = text.length() > RAG_SNIPPET_CHARS
                    ? text.substring(0, RAG_SNIPPET_CHARS) + "..."
                    : text;
            refs.add(new DocReference(
                    c.filePath() != null ? c.filePath() : "doc",
                    c.sourceUrl() != null ? c.sourceUrl() : "",
                    snippet));
        }
        return refs;
    }

    /**
     * Best-effort persistence of the per-file provenance.  Skips silently
     * when the session context isn't set (unit tests, or the migrator
     * is being invoked outside the workflow) or persistence fails — the
     * migration result must not depend on observability succeeding.
     */
    private void persistProvenance(Map<String, List<DocReference>> perFile) {
        if (perFile.isEmpty() || fileProvenanceRepository == null) return;
        String sessionId = SandboxContext.currentSessionId();
        if (sessionId == null || sessionId.isBlank()) return;
        // Snapshot under the map's monitor — workers may be doing put() on
        // another thread at the same time, which would otherwise throw a
        // ConcurrentModificationException during serialization.
        Map<String, List<DocReference>> snapshot;
        synchronized (perFile) {
            snapshot = new LinkedHashMap<>(perFile);
        }
        try {
            WorkflowSessionId id = new WorkflowSessionId(UUID.fromString(sessionId));
            fileProvenanceRepository.save(id,
                    new FileProvenance(sessionId, snapshot, Instant.now()));
        } catch (Exception e) {
            log.warn("[{}] could not persist file provenance: {}", getName(), e.getMessage());
        }
    }

    /**
     * Content-addressed cache key — {@code SHA-256(systemPrompt + "|" + path
     * + "|" + content)}.  Including the prompt means tweaking it
     * automatically invalidates every cached entry; including the path keeps
     * two unrelated files with identical content from sharing a cache entry.
     */
    static String computeCacheKey(String systemPrompt, String path, String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(systemPrompt.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(content.getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JRE — falling back here is fine.
            return Integer.toHexString((systemPrompt + path + content).hashCode());
        }
    }

    private static boolean isPom(String path) {
        if (path == null) return false;
        String p = path.toLowerCase();
        return p.equals("pom.xml") || p.endsWith("/pom.xml");
    }

    private static MigratedFile unchanged(String path, String content, String reason) {
        return MigratedFile.builder()
                .originalPath(path).newPath(path).content(content)
                .changeType(FileChangeType.UNCHANGED).diffSummary(reason)
                .build();
    }

    /**
     * Extracts the migrated file content from a raw AI response, removing any
     * markdown code fences ({@code ```java} / {@code ```}) and any prose the
     * model wrote outside the first code block.
     *
     * <p>Handles three real-world response shapes the migrator has hit:
     * <ol>
     *   <li><b>Plain content</b> — raw starts with {@code package} / {@code <?xml}
     *       and has no fences.  Returned as-is.</li>
     *   <li><b>Single fenced block</b> — {@code ```java\n...code...\n```}.
     *       The opening fence and matching closing fence are stripped.</li>
     *   <li><b>Code followed by prose (and sometimes a second block)</b> —
     *       the model writes the code first, then closes it with {@code ```}
     *       and continues with markdown analysis like
     *       {@code **Rationale**: …} possibly followed by another
     *       {@code ```java …``` } block.  We keep ONLY the content up to
     *       the first stray fence; anything after is markdown garbage that
     *       would break the compile (was producing {@code illegal character: '`'}).
     *       </li>
     * </ol>
     */
    static String stripMarkdownFences(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        String s = raw.strip();

        if (s.startsWith("```")) {
            // Shape 2: drop the opening fence (and optional language tag)
            int firstNewline = s.indexOf('\n');
            if (firstNewline < 0) return "";
            s = s.substring(firstNewline + 1);
            // …then cut at the next ``` (closing fence + any trailing prose
            // or second block).  If there's no closing fence we keep what we
            // have — the trim() below tidies trailing whitespace.
            int closing = s.indexOf("```");
            if (closing >= 0) s = s.substring(0, closing);
        } else if (s.contains("```")) {
            // Shape 3: code first, fence-then-prose afterwards.  Everything
            // after the first stray fence is markdown garbage — drop it.
            int firstFence = s.indexOf("```");
            s = s.substring(0, firstFence);
        }

        return s.stripTrailing();
    }

    /**
     * Strips any leading natural-language preamble the model prepended despite
     * the prompt forbidding it — e.g. "Here is the migrated pom.xml:" before
     * the actual {@code <?xml ...>}.  That preamble is what produced the
     * "Non-parseable POM ... seen H..." build failure (the 'H' of "Here").
     *
     * <p>Extension-aware so we only cut when we know what the real content
     * must start with:
     * <ul>
     *   <li><b>XML</b> (pom.xml, *.xml) — content must start at the first
     *       {@code <}; drop anything before it.</li>
     *   <li><b>Java</b> — drop leading lines until the first plausible Java
     *       start ({@code package} / {@code import} / comment / annotation /
     *       type declaration).</li>
     * </ul>
     * Other formats are returned untouched — we don't have a reliable anchor
     * and a wrong cut would do more harm than good.
     */
    static String stripLeadingProse(String content, String path) {
        if (content == null || content.isEmpty() || path == null) return content;
        String lower = path.toLowerCase();

        // XML family — must begin with '<'.  Trim only if a non-blank prefix
        // precedes the first '<' (otherwise leave well-formed content alone).
        if (lower.endsWith(".xml") || lower.endsWith("pom.xml")) {
            int lt = content.indexOf('<');
            if (lt > 0 && !content.substring(0, lt).isBlank()) {
                return content.substring(lt);
            }
            return content;
        }

        // Java — find the first line that looks like real Java and drop
        // anything above it.
        if (lower.endsWith(".java")) {
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String t = lines[i].strip();
                if (t.isEmpty()) continue;
                if (looksLikeJavaStart(t)) {
                    return i == 0 ? content
                            : String.join("\n", java.util.Arrays.copyOfRange(lines, i, lines.length));
                }
                // First non-empty line is NOT Java — it's prose; keep scanning.
            }
        }
        return content;
    }

    private static boolean looksLikeJavaStart(String trimmedLine) {
        return trimmedLine.startsWith("package ")
            || trimmedLine.startsWith("import ")
            || trimmedLine.startsWith("//")
            || trimmedLine.startsWith("/*")
            || trimmedLine.startsWith("*")
            || trimmedLine.startsWith("@")
            || trimmedLine.startsWith("public ")
            || trimmedLine.startsWith("final ")
            || trimmedLine.startsWith("abstract ")
            || trimmedLine.startsWith("class ")
            || trimmedLine.startsWith("interface ")
            || trimmedLine.startsWith("enum ")
            || trimmedLine.startsWith("record ");
    }

    /**
     * Returns true when the migrated content can't possibly be a valid file
     * of its kind, so we should keep the original rather than break the build.
     * Conservative: only flags the cases we're certain about (XML that doesn't
     * start with {@code <} or has no closing tag).  A broken build file is the
     * worst failure mode because it stops the build before any code compiles.
     */
    static boolean looksStructurallyBroken(String migrated, String path) {
        if (migrated == null || path == null) return false;
        String lower = path.toLowerCase();
        if (lower.endsWith(".xml") || lower.endsWith("pom.xml")) {
            String t = migrated.strip();
            if (t.isEmpty() || !t.startsWith("<") || !t.contains("</")) return true;
            // Real parse — catches the cases a regex misses, including
            // invalid -- inside <!-- comment --> bodies (which Maven
            // rejects as a "Non-parseable POM").
            return !parsesAsXml(migrated);
        }
        if (lower.endsWith(".java")) {
            return hasMarkdownContamination(migrated);
        }
        return false;
    }

    /**
     * Conservative content-shape check for files the migrator claims are
     * Java source.  Triggers on patterns that have zero chance of appearing
     * in clean Java and high chance of appearing in an AI response that
     * bled markdown / English prose into the output:
     * <ul>
     *   <li>A literal triple-backtick anywhere — never valid in Java.</li>
     *   <li>A line that starts with {@code **} — markdown bold heading.</li>
     *   <li>No {@code package} declaration in the file at all — every
     *       legitimate Java source file under {@code src/main/java} declares
     *       its package, so its absence is a strong signal the AI returned
     *       prose instead of code (e.g. "Since the provided file …").</li>
     *   <li>No type declaration ({@code class} / {@code interface} /
     *       {@code enum} / {@code record}) anywhere — a Java source without
     *       any type is structurally empty.</li>
     * </ul>
     */
    static boolean hasMarkdownContamination(String javaSource) {
        if (javaSource == null || javaSource.isBlank()) return true;
        if (javaSource.contains("```")) return true;
        for (String line : javaSource.split("\n", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("**") && !trimmed.startsWith("*/")) return true;
        }
        if (!PACKAGE_DECL.matcher(javaSource).find()) return true;
        if (!TYPE_DECL.matcher(javaSource).find())    return true;
        return false;
    }

    /** Matches a {@code package x.y.z;} line.  Anchored so an `import` or
     *  string literal that happens to contain "package" doesn't satisfy it. */
    private static final java.util.regex.Pattern PACKAGE_DECL =
            java.util.regex.Pattern.compile("^\\s*package\\s+[\\w.]+\\s*;", java.util.regex.Pattern.MULTILINE);

    /** Matches a top-level Java type declaration of any kind. */
    private static final java.util.regex.Pattern TYPE_DECL =
            java.util.regex.Pattern.compile(
                    "(?:^|\\s)(?:public\\s+|final\\s+|abstract\\s+|static\\s+|sealed\\s+|non-sealed\\s+|private\\s+|protected\\s+)*"
                    + "(?:class|interface|enum|record)\\s+\\w+",
                    java.util.regex.Pattern.MULTILINE);

    /**
     * Strict XML well-formedness check.  External entities + DOCTYPE are
     * disabled (XXE protection) — we never need them for build config.
     */
    static boolean parsesAsXml(String content) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            dbf.setValidating(false);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setExpandEntityReferences(false);
            dbf.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(content)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Replaces every {@code --} inside an XML {@code <!-- ... -->} comment
     * body with a single {@code -}.  XML forbids {@code --} in a comment
     * (only the closing {@code -->} may contain it), but the model
     * frequently produces it when normalising em-dashes ({@code —}) from
     * the source.  Targeted: leaves everything outside comments untouched.
     */
    static String repairXmlCommentDashes(String xml) {
        if (xml == null || xml.isEmpty()) return xml;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<!--(.*?)-->", java.util.regex.Pattern.DOTALL)
                .matcher(xml);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String body = m.group(1);
            while (body.contains("--")) body = body.replace("--", "-");
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement("<!--" + body + "-->"));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Defence-in-depth against silently truncated AI output: if the migrated
     * content is implausibly short compared to the input AND the input wasn't
     * trivial to begin with, assume the model hit max_tokens mid-stream and
     * keep the original file.  A truncated rewrite (e.g. a pom.xml with no
     * closing tag, a Java file with no closing brace) is strictly worse than
     * no rewrite — it breaks the build instead of preserving it.
     */
    static boolean looksTruncated(String migrated, String original) {
        if (migrated == null || original == null) return false;
        int origLen = original.length();
        int migLen  = migrated.length();
        // Don't trip the guard on small files — they legitimately shrink a lot
        // (e.g. a 200-byte pom snippet that loses one dependency block).
        if (origLen < 500) return false;
        // Less than 30 % the original size is the red flag.
        return migLen < origLen * 0.30;
    }
}
