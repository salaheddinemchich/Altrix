package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.model.contract.ContractViolation;
import com.altrix.orchestrator.domain.model.leak.PubSubLeakViolation;
import com.altrix.orchestrator.domain.model.semantic.SemanticValidationReport;
import com.altrix.orchestrator.domain.model.semantic.SemanticValidationReport.Category;
import com.altrix.orchestrator.domain.model.semantic.SemanticValidationReport.Finding;
import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.orchestrator.infrastructure.config.KafkaMigrationKnowledgeBase;
import com.altrix.orchestrator.infrastructure.contract.ContractValidator;
import com.altrix.orchestrator.infrastructure.leak.PubSubLeakValidator;
import com.altrix.orchestrator.infrastructure.semantic.DependencyValidator;
import com.altrix.orchestrator.infrastructure.semantic.DeterministicRepairEngine;
import com.altrix.orchestrator.infrastructure.semantic.JavaxToJakartaRewriter;
import com.altrix.orchestrator.infrastructure.semantic.LombokConstructorReconciler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic Validator — the phase between Core Migrator and Sandbox
 * Compiler.  Verifies generated code semantically BEFORE the expensive
 * compile, so compilation becomes a final check rather than the primary
 * debugging mechanism.
 *
 * <p><b>Stage 3 (this class): report-only.</b>  It runs the existing
 * {@link ContractValidator} (cross-file consistency) + {@link PubSubLeakValidator}
 * (surviving GCP residue) + a {@link KafkaMigrationKnowledgeBase}-driven
 * forbidden-import scan, aggregates everything into a
 * {@link SemanticValidationReport}, logs it, and returns the artifact
 * UNCHANGED.  Later stages add the deterministic + AI auto-repair that
 * mutates the artifact (decision: the validator is allowed to modify).
 *
 * <p>Typed {@code MigrationArtifact → MigrationArtifact} so it slots
 * inline in the pipeline without changing the data flowing into the
 * sandbox.
 */
@Slf4j
@Component("semanticValidatorAgent")
@RequiredArgsConstructor
public class SemanticValidatorAgent implements MigrationAgent<MigrationArtifact, MigrationArtifact> {

    private final ContractValidator contractValidator;
    private final PubSubLeakValidator pubSubLeakValidator;
    private final KafkaMigrationKnowledgeBase knowledgeBase;
    private final DeterministicRepairEngine deterministicRepairEngine;
    private final DependencyValidator dependencyValidator;
    private final JavaxToJakartaRewriter javaxToJakartaRewriter;
    private final LombokConstructorReconciler lombokConstructorReconciler;

    private static final Pattern IMPORT_LINE = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);

    @Override
    public String getName() {
        return "Semantic Validator";
    }

    @Override
    public int getOrder() {
        // Runs between Core Migrator (3) and Sandbox Validator (4).  The
        // graph wires agents by qualifier, not by this value, so it is
        // informational only.
        return 3;
    }

    @Override
    public MigrationArtifact execute(MigrationArtifact input) {
        if (input == null) {
            throw new AgentFailureException(getName(), "input MigrationArtifact was null");
        }
        Map<String, String> javaFiles = javaFiles(input.files());
        String pomXml = pomOf(input);
        log.info("[{}] validating {} Java file(s) for project '{}'",
                getName(), javaFiles.size(), input.projectId());
        MigrationArtifact output = input;

        // ── javax → jakarta namespace rewrite (deterministic, no AI) ─────
        // Gated on the project actually targeting Jakarta EE.  Fixes the
        // migrator's "import javax.enterprise.context.*" slip BEFORE the
        // sandbox so it costs zero retries.  Runs first so the import-hygiene
        // engine below sees the corrected namespaces.
        if (javaxToJakartaRewriter.targetsJakarta(javaFiles, pomXml, null)) {
            var jakarta = javaxToJakartaRewriter.rewrite(javaFiles);
            if (jakarta.changedAnything()) {
                log.info("[{}] javax→jakarta rewrite applied to {} file(s): {}",
                        getName(), jakarta.changedPaths().size(), jakarta.changedPaths());
                javaFiles = jakarta.rewrittenFiles();
                output = rebuildWithRepairedFiles(output, javaFiles,
                        "Semantic deterministic repair (javax→jakarta namespace)");
            }
        }

        // ── Lombok constructor collision repair (deterministic, AST) ─────
        // Drop a redundant @RequiredArgsConstructor/@AllArgsConstructor when an
        // explicit constructor with the same signature exists, so the build
        // doesn't fail with "constructor already defined".
        var lombok = lombokConstructorReconciler.reconcile(javaFiles);
        if (lombok.changedAnything()) {
            log.info("[{}] Lombok constructor reconcile applied to {} file(s): {}",
                    getName(), lombok.changedPaths().size(), lombok.changedPaths());
            javaFiles = lombok.reconciledFiles();
            output = rebuildWithRepairedFiles(output, javaFiles,
                    "Semantic deterministic repair (Lombok constructor collision)");
        }

        // ── Deterministic repair (no AI) ─────────────────────────────────
        // Apply mechanically-certain fixes from the knowledge base (import
        // hygiene) BEFORE reporting, so the report reflects what actually
        // remains for the sandbox / AI tier to handle.
        var repair = deterministicRepairEngine.repair(javaFiles);
        if (repair.changedAnything()) {
            log.info("[{}] deterministic repair applied {} fix(es): {}",
                    getName(), repair.actions().size(),
                    repair.actions().stream().map(a -> a.type().name()).distinct().toList());
            javaFiles = repair.repairedFiles();
            output = rebuildWithRepairedFiles(output, javaFiles,
                    "Semantic deterministic repair (import hygiene)");
        }

        // ── Validate (post-repair) ───────────────────────────────────────
        SemanticValidationReport report = validate(input.projectId(), javaFiles, pomXml);
        if (report.clean()) {
            log.info("[{}] {}", getName(), report.summary());
        } else {
            log.warn("[{}] {} — contract={}, leak={}, forbidden-import={}, missing-dep={} (after {} deterministic fix(es))",
                    getName(), report.summary(),
                    report.countOf(Category.CONTRACT),
                    report.countOf(Category.PUBSUB_LEAK),
                    report.countOf(Category.FORBIDDEN_IMPORT),
                    report.countOf(Category.MISSING_DEPENDENCY),
                    repair.actions().size());
            if (log.isDebugEnabled()) {
                report.findings().forEach(f -> log.debug("[{}]   {}:{} [{}] {} {}",
                        getName(), f.filePath(), f.line(), f.category(), f.symbol(), f.message()));
            }
        }
        // ── Per-run quality snapshot (no reference needed) ───────────────
        // Surfaces the two honest signals: cleanliness (Pub/Sub residue) and
        // the cascade-trigger count.  A compile-error SPIKE with non-zero
        // cascade triggers is cascade noise (one syntax error inflated 50-200x
        // by aborted annotation processing), NOT a real quality regression.
        long cascadeTriggers = repair.actions().stream()
                .filter(DeterministicRepairEngine.RepairAction::isCascadeTrigger).count();
        boolean clean = report.countOf(Category.PUBSUB_LEAK) == 0;
        log.info("[{}] RUN QUALITY — cleanliness(pubsub-residue)={}, cascade-triggers-neutralised={}, "
                        + "semantic-findings={}{}",
                getName(), clean ? "OK(0)" : "LEAK(" + report.countOf(Category.PUBSUB_LEAK) + ")",
                cascadeTriggers, report.findings().size(),
                cascadeTriggers > 0
                        ? " ⚠ neutralised cascade trigger(s) that would otherwise inflate the compile-error count ~50-200x"
                        : "");

        // Remaining issues escalate to the AI repair tier (wired in a later
        // stage) and ultimately to the sandbox compile.
        return output;
    }

    /**
     * Produces a new artifact where the repaired Java files replace the
     * originals.  Non-Java files + unchanged Java files pass through by
     * identity; only files whose content the engine actually changed are
     * re-emitted as MODIFIED.
     */
    private MigrationArtifact rebuildWithRepairedFiles(MigrationArtifact input,
                                                       Map<String, String> repairedJava,
                                                       String diffSummary) {
        List<MigratedFile> rebuilt = new ArrayList<>(input.files().size());
        for (MigratedFile f : input.files()) {
            String path = f.newPath() != null ? f.newPath() : f.originalPath();
            String repaired = path != null ? repairedJava.get(path) : null;
            if (repaired == null || repaired.equals(f.content())) {
                rebuilt.add(f);
                continue;
            }
            rebuilt.add(MigratedFile.builder()
                    .originalPath(f.originalPath())
                    .newPath(f.newPath())
                    .content(repaired)
                    .changeType(FileChangeType.MODIFIED)
                    .diffSummary(diffSummary)
                    .build());
        }
        return new MigrationArtifact(input.projectId(), rebuilt, input.summary());
    }

    /**
     * Convenience overload without a pom — the dependency check is skipped.
     * Package-private so unit tests can assert contract/leak/forbidden
     * findings on a java-only file map.
     */
    SemanticValidationReport validate(String projectId, Map<String, String> javaFiles) {
        return validate(projectId, javaFiles, null);
    }

    /**
     * Runs every semantic check and aggregates into one report.
     *
     * @param pomXml the project's pom.xml content, or null to skip the
     *               dependency check (Gradle / no build file).
     */
    SemanticValidationReport validate(String projectId, Map<String, String> javaFiles, String pomXml) {
        if (javaFiles.isEmpty()) {
            return SemanticValidationReport.clean(projectId, 0);
        }
        List<Finding> findings = new ArrayList<>();

        // 1 — Contract (cross-file consistency).
        for (ContractViolation v : contractValidator.validate(javaFiles)) {
            findings.add(new Finding(Category.CONTRACT, v.filePath(), v.line(), v.symbol(),
                    "[" + v.kind() + "] " + v.message()));
        }
        // 2 — Pub/Sub leak (surviving GCP residue).
        for (PubSubLeakViolation v : pubSubLeakValidator.validate(javaFiles)) {
            findings.add(new Finding(Category.PUBSUB_LEAK, v.filePath(), v.line(), v.symbol(),
                    "[" + v.kind() + "] " + v.reason()
                            + (v.suggestion().isBlank() ? "" : " -> " + v.suggestion())));
        }
        // 3 — KnowledgeBase forbidden-import scan.
        findings.addAll(scanForbiddenImports(javaFiles));
        // 4 — Dependency validation (Kafka class referenced but no dep on the build).
        findings.addAll(dependencyValidator.validate(javaFiles, pomXml));

        if (findings.isEmpty()) {
            return SemanticValidationReport.clean(projectId, javaFiles.size());
        }
        String summary = "Semantic validation found %d issue(s) across %d file(s)"
                .formatted(findings.size(), javaFiles.size());
        return new SemanticValidationReport(projectId, false, findings, summary);
    }

    /** Extract the pom.xml content from the artifact, or null when absent. */
    private static String pomOf(MigrationArtifact artifact) {
        for (MigratedFile f : artifact.files()) {
            String path = f.newPath() != null ? f.newPath() : f.originalPath();
            if (path != null && (path.equals("pom.xml") || path.endsWith("/pom.xml"))) {
                return f.content();
            }
        }
        return null;
    }

    /**
     * Scans every Java file's imports against
     * {@link KafkaMigrationKnowledgeBase#allForbiddenImports()}.  Honours
     * trailing {@code .*} as a package wildcard — same semantics as
     * {@code CoreMigratorAgent.firstDeniedImport}.
     */
    private List<Finding> scanForbiddenImports(Map<String, String> javaFiles) {
        List<Finding> out = new ArrayList<>();
        var forbidden = knowledgeBase.allForbiddenImports();
        if (forbidden.isEmpty()) return out;

        for (Map.Entry<String, String> e : javaFiles.entrySet()) {
            Matcher m = IMPORT_LINE.matcher(e.getValue());
            while (m.find()) {
                String fqn = m.group(1);
                for (String denied : forbidden) {
                    if (denied == null || denied.isBlank()) continue;
                    boolean hit;
                    if (denied.endsWith(".*")) {
                        String prefix = denied.substring(0, denied.length() - 2);
                        hit = fqn.startsWith(prefix + ".");
                    } else {
                        hit = fqn.equals(denied);
                    }
                    if (hit) {
                        out.add(new Finding(Category.FORBIDDEN_IMPORT, e.getKey(), -1, fqn,
                                "Import '" + fqn + "' is forbidden by the Kafka migration knowledge base "
                                        + "(matched rule '" + denied + "')"));
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static Map<String, String> javaFiles(List<MigratedFile> files) {
        Map<String, String> out = new LinkedHashMap<>();
        for (MigratedFile f : files) {
            String path = f.newPath() != null ? f.newPath() : f.originalPath();
            if (path != null && path.toLowerCase().endsWith(".java") && f.content() != null) {
                out.put(path, f.content());
            }
        }
        return out;
    }
}
