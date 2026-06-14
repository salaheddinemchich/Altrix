package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.ProjectContext;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.model.blueprint.*;
import com.altrix.orchestrator.domain.model.sandbox.SandboxContext;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.FileReaderPort;
import com.altrix.orchestrator.domain.port.out.ProjectBlueprintRepository;
import com.altrix.orchestrator.infrastructure.blueprint.FeatureClassifier;
import com.altrix.orchestrator.infrastructure.blueprint.LstSemanticGraphBuilder;
import com.altrix.orchestrator.infrastructure.blueprint.MavenClasspathResolver;
import com.altrix.orchestrator.infrastructure.blueprint.MigrationOrderResolver;
import com.altrix.orchestrator.infrastructure.blueprint.OpenRewriteLstParser;
import com.altrix.orchestrator.infrastructure.blueprint.StackDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Project Mapper — the combined Index + Analyse phase.
 *
 * <p>Reads the uploaded project, parses it into OpenRewrite LSTs, builds a
 * type-attributed {@link SemanticGraph}, classifies each file's Pub/Sub
 * features (joined to the Kafka knowledge base), detects the stack +
 * integrations, computes a leaves-first migration order, and assembles a
 * {@link ProjectBlueprint} — the project-wide map the migrator reads so it
 * stops rewriting files blind.
 *
 * <p>Stage S2c: the assembler runs as a standalone, tested agent.  It is
 * NOT yet wired into the workflow graph (that cutover replaces the
 * context-analyzer node in a later stage) and does NOT yet embed source
 * into pgvector (the indexing fold-in happens at cutover).
 *
 * <p>Persistence is best-effort: when a session id is visible on the
 * {@link SandboxContext} the blueprint is saved; otherwise it is returned
 * but not persisted (unit tests, or invocation outside the workflow).
 */
@Slf4j
@Component("projectMapperAgent")
@RequiredArgsConstructor
public class ProjectMapperAgent implements MigrationAgent<ProjectContext, ProjectBlueprint> {

    private final FileReaderPort fileReader;
    private final OpenRewriteLstParser lstParser;
    private final LstSemanticGraphBuilder graphBuilder;
    private final FeatureClassifier featureClassifier;
    private final StackDetector stackDetector;
    private final MigrationOrderResolver orderResolver;
    private final ProjectBlueprintRepository blueprintRepository;
    private final MavenClasspathResolver classpathResolver;

    @Override
    public String getName() {
        return "Project Mapper";
    }

    @Override
    public int getOrder() {
        return 1; // combined Index + Analyse — first phase
    }

    @Override
    public ProjectBlueprint execute(ProjectContext input) {
        if (input == null) throw new AgentFailureException(getName(), "input ProjectContext was null");
        String projectId = input.projectId();
        String storageKey = input.storageKey();
        log.info("[{}] mapping project '{}'", getName(), projectId);

        if (storageKey == null || storageKey.isBlank()) {
            log.warn("[{}] no storageKey — returning empty blueprint", getName());
            return emptyBlueprint(projectId, sessionIdOrSynthetic());
        }

        try {
            Map<String, String> allFiles = fileReader.readAllFiles(storageKey);
            Map<String, String> javaFiles = javaOnly(allFiles);

            // Resolve the dependency classpath (best-effort) so the LST
            // attributes EXTERNAL types (kafka-clients, jakarta-api), not just
            // intra-project ones.  Empty on any failure → intra-project-only,
            // same as before.
            var classpath = classpathResolver.resolve(pomOf(allFiles));
            var units = lstParser.parse(javaFiles, classpath);
            SemanticGraph graph = graphBuilder.build(units);
            Map<String, List<BlueprintFeature>> featuresByFile = featureClassifier.classify(javaFiles);
            DetectedStack stack = stackDetector.detect(allFiles);
            List<DetectedIntegration> integrations = detectIntegrations(graph, javaFiles);
            List<BlueprintFile> files = assembleFiles(graph, featuresByFile);
            List<String> migrationOrder = orderResolver.resolve(graph);
            List<String> riskNotes = collectRiskNotes(graph, files);

            String sessionId = sessionIdOrSynthetic();
            ProjectBlueprint blueprint = new ProjectBlueprint(
                    projectId, sessionId, Instant.now(),
                    ProjectBlueprint.CURRENT_SCHEMA_VERSION,
                    stack, integrations, files, graph,
                    List.of(),               // docReferences — populated by doc-linking stage
                    migrationOrder, riskNotes);

            persistBestEffort(blueprint);
            log.info("[{}] blueprint for '{}': {} file(s), {} with features, {} integration(s)",
                    getName(), projectId, files.size(),
                    blueprint.migrationRelevantFileCount(), integrations.size());
            return blueprint;

        } catch (Exception e) {
            log.error("[{}] mapping failed for '{}': {}", getName(), projectId, e.getMessage());
            throw new AgentFailureException(getName(), "project mapping failed: " + e.getMessage());
        }
    }

    // ── assembly ─────────────────────────────────────────────────────────────

    private List<BlueprintFile> assembleFiles(SemanticGraph graph,
                                              Map<String, List<BlueprintFeature>> featuresByFile) {
        // Group the graph's classes by their declaring file; the first
        // (outermost) class is the file's primary type.
        Map<String, List<ClassNode>> byFile = graph.classesByFile();
        List<BlueprintFile> out = new ArrayList<>(byFile.size());

        for (Map.Entry<String, List<ClassNode>> e : byFile.entrySet()) {
            String path = e.getKey();
            List<ClassNode> classes = e.getValue();
            ClassNode primary = classes.get(0);
            List<BlueprintFeature> features = featuresByFile.getOrDefault(path, List.of());

            BlueprintRelationships rels = relationshipsFor(primary.fqn(), graph);
            String role = inferRole(primary, features);
            String notes = migrationNotes(features);

            out.add(new BlueprintFile(
                    path, primary.simpleName(), primary.kind(), primary.packageName(),
                    role, rels, features, notes));
        }
        return out;
    }

    private BlueprintRelationships relationshipsFor(String fqn, SemanticGraph graph) {
        String extendsType = null;
        List<String> implementsTypes = new ArrayList<>();
        for (InheritanceEdge ie : graph.inheritance()) {
            if (!ie.subtypeFqn().equals(fqn)) continue;
            if (ie.kind() == InheritanceEdge.Kind.EXTENDS) extendsType = ie.supertypeFqn();
            else implementsTypes.add(ie.supertypeFqn());
        }
        List<String> dependsOn = new ArrayList<>(graph.calleesOf(fqn));
        List<String> calledBy = new ArrayList<>(graph.callersOf(fqn));
        return new BlueprintRelationships(extendsType, implementsTypes, dependsOn, calledBy);
    }

    /** Heuristic role label from annotations + features. */
    private String inferRole(ClassNode c, List<BlueprintFeature> features) {
        var ann = c.annotations();
        boolean hasPubSubFeature = !features.isEmpty();
        if (ann.contains("Path")) return "REST Resource";
        if (ann.contains("Singleton") && ann.contains("Startup")) return "EJB Lifecycle/Poller";
        if (ann.contains("Produces")) return "CDI Producer";
        if (c.kind() == BlueprintFile.Kind.INTERFACE) {
            return hasPubSubFeature ? "Messaging Port" : "Interface";
        }
        if (hasPubSubFeature) return "Messaging Adapter";
        if (ann.contains("ApplicationScoped") || ann.contains("Stateless") || ann.contains("Service")) {
            return "Service";
        }
        if (c.kind() == BlueprintFile.Kind.ENUM) return "Enum";
        if (c.kind() == BlueprintFile.Kind.RECORD) return "Record";
        return "Class";
    }

    private String migrationNotes(List<BlueprintFeature> features) {
        if (features.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("Migrate: ");
        for (int i = 0; i < features.size(); i++) {
            BlueprintFeature f = features.get(i);
            if (i > 0) sb.append("; ");
            sb.append(f.id());
            if (f.kafkaTarget() != null) sb.append(" -> ").append(f.kafkaTarget());
        }
        return sb.toString();
    }

    private List<DetectedIntegration> detectIntegrations(SemanticGraph graph, Map<String, String> javaFiles) {
        List<String> evidence = new ArrayList<>();
        for (ImportEdge ie : graph.imports()) {
            String t = ie.targetFqn();
            if (t.startsWith("com.google.api.services.pubsub")
                    || t.startsWith("com.google.cloud.pubsub")
                    || t.startsWith("com.google.pubsub")
                    || t.startsWith("org.springframework.cloud.gcp.pubsub")) {
                // Map importer FQN back to a file path via the graph classes.
                graph.classes().stream()
                        .filter(c -> c.fqn().equals(ie.importerFqn()) && c.filePath() != null)
                        .findFirst()
                        .ifPresent(c -> { if (!evidence.contains(c.filePath())) evidence.add(c.filePath()); });
            }
        }
        if (evidence.isEmpty()) return List.of();
        return List.of(new DetectedIntegration("GCP Pub/Sub", "messaging", evidence));
    }

    private List<String> collectRiskNotes(SemanticGraph graph, List<BlueprintFile> files) {
        List<String> notes = new ArrayList<>();
        if (orderResolver.hasCycle(graph)) {
            notes.add("Project has a class dependency cycle — migration order is best-effort for cyclic classes.");
        }
        files.stream()
                .flatMap(f -> f.features().stream())
                .filter(ft -> ft.id().equals("pubsub.test-iam"))
                .findFirst()
                .ifPresent(ft -> notes.add(
                        "IAM permission test detected — no clean Kafka equivalent; will need a manual TODO."));
        return notes;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** The project's pom.xml content (root or nested), or null when absent. */
    private static String pomOf(Map<String, String> files) {
        for (Map.Entry<String, String> e : files.entrySet()) {
            String path = e.getKey();
            if (path != null && (path.equals("pom.xml") || path.endsWith("/pom.xml"))) {
                return e.getValue();
            }
        }
        return null;
    }

    private static Map<String, String> javaOnly(Map<String, String> files) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase().endsWith(".java") && e.getValue() != null) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private void persistBestEffort(ProjectBlueprint blueprint) {
        String sid = SandboxContext.currentSessionId();
        if (sid == null || sid.isBlank()) {
            log.debug("[{}] no session in context — blueprint not persisted", getName());
            return;
        }
        try {
            blueprintRepository.save(blueprint);
        } catch (Exception e) {
            log.warn("[{}] could not persist blueprint: {}", getName(), e.getMessage());
        }
    }

    private static String sessionIdOrSynthetic() {
        String sid = SandboxContext.currentSessionId();
        return (sid != null && !sid.isBlank()) ? sid : new WorkflowSessionId(UUID.randomUUID()).value().toString();
    }

    private static ProjectBlueprint emptyBlueprint(String projectId, String sessionId) {
        return new ProjectBlueprint(projectId, sessionId, Instant.now(),
                ProjectBlueprint.CURRENT_SCHEMA_VERSION, DetectedStack.unknown(),
                List.of(), List.of(), SemanticGraph.empty(), List.of(), List.of(), List.of());
    }
}
