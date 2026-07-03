package com.altrix.orchestrator.infrastructure.hybrid;

import com.altrix.orchestrator.infrastructure.leak.PubSubLeakValidator;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Detects the hand-rolled Pub/Sub client glue that is dead code by construction
 * in the {@code SPRING_KAFKA_HYBRID} target, so {@code CoreMigratorAgent} can
 * DELETE it instead of sending it to the LLM.
 *
 * <p><b>Why delete.</b> After {@link HybridConsumerTransformer} turns every
 * {@code @Schedule} poller into a Spring {@code @KafkaListener} component and
 * {@link HybridPublishRewriter} rewrites publishers onto the bridged
 * {@code KafkaTemplate}, nothing in the project should call the wrapper service
 * anymore.  Keeping it sends the wrapper to the LLM, which reliably invents a
 * raw kafka-clients re-implementation with type errors (job d3fa6347:
 * {@code Iterable} vs {@code List} on {@code consumer.poll().records()}).
 * Deleting it also arms {@code revertFilesWithUnresolvedImports}: any LLM
 * output still importing the wrapper reverts to the original source, which
 * then flows into {@link HybridPublishRewriter}'s deterministic rewrite.
 *
 * <p><b>What qualifies as glue</b> (per file, JavaParser, simple-name
 * resolution — same documented limitation as the Project Semantic Index):
 * <ol>
 *   <li><b>Client producer</b> — a CDI {@code @Produces} method whose return
 *       type is a raw Pub/Sub client type ({@code PubsubClientProducer}).</li>
 *   <li><b>Wrapper service</b> — a class that {@code @Inject}s a raw client
 *       type AND declares the {@code publish}/{@code consume} seam methods the
 *       rest of {@code infrastructure/hybrid} keys on ({@code PubSubService}).</li>
 * </ol>
 * "Raw client type" is anchored on {@link PubSubLeakValidator}'s public
 * forbidden-import prefixes and type names — the two gates share one authority
 * and can never disagree on what counts as the Pub/Sub client surface.
 *
 * <p>Business classes that merely touch a client type without matching either
 * shape are left for the LLM; the leak validator still gates the output.
 */
@Slf4j
@Component
public class PubSubWrapperRemover {

    /** Seam method names the wrapper exposes — the same names
     *  {@link HybridConsumerTransformer} ({@code pubsub.consume(...)}) and
     *  {@link HybridPublishRewriter} ({@code pubsub.publish(...)}) key on. */
    private static final Set<String> WRAPPER_SEAM_METHODS = Set.of("publish", "consume");

    /**
     * Returns the paths of files that are raw Pub/Sub client glue and should be
     * deleted from the hybrid artifact.  Insertion-ordered; empty when nothing
     * matches.  Unparseable files are skipped, never failed.
     */
    public Set<String> detect(Map<String, String> allFiles) {
        Set<String> toDelete = new LinkedHashSet<>();
        if (allFiles == null || allFiles.isEmpty()) return toDelete;

        for (Map.Entry<String, String> e : allFiles.entrySet()) {
            String path = e.getKey();
            String content = e.getValue();
            if (path == null || !path.endsWith(".java") || content == null) continue;
            // Cheap pre-filter before parsing: no forbidden package string, no glue.
            boolean touchesClient = false;
            for (String prefix : PubSubLeakValidator.FORBIDDEN_IMPORT_PREFIXES) {
                if (content.contains(prefix)) { touchesClient = true; break; }
            }
            if (!touchesClient) continue;

            String reason = classifyGlue(content);
            if (reason != null) {
                toDelete.add(path);
                log.info("[PubSubWrapperRemover] '{}' → delete ({})", path, reason);
            }
        }
        return toDelete;
    }

    /** Non-null human-readable reason when the file matches a glue shape. */
    private String classifyGlue(String content) {
        CompilationUnit cu;
        try {
            cu = new JavaParser().parse(content).getResult().orElse(null);
        } catch (Exception ex) {
            return null;
        }
        if (cu == null) return null;

        ClassOrInterfaceDeclaration cls = cu.findFirst(ClassOrInterfaceDeclaration.class).orElse(null);
        if (cls == null || cls.isInterface()) return null;

        // Simple names of types imported from the forbidden client packages.
        // A wildcard client import contributes the validator's known client
        // type names instead (no simple name to harvest from the import).
        Set<String> clientTypes = new LinkedHashSet<>();
        cu.getImports().forEach(imp -> {
            String name = imp.getNameAsString();
            for (String prefix : PubSubLeakValidator.FORBIDDEN_IMPORT_PREFIXES) {
                if (!name.startsWith(prefix)) continue;
                if (imp.isAsterisk()) {
                    clientTypes.addAll(PubSubLeakValidator.FORBIDDEN_TYPE_NAMES);
                } else {
                    clientTypes.add(name.substring(name.lastIndexOf('.') + 1));
                }
                break;
            }
        });
        if (clientTypes.isEmpty()) return null;

        // (1) CDI producer of a raw client type.
        for (MethodDeclaration m : cls.getMethods()) {
            if (!m.isAnnotationPresent("Produces")) continue;
            if (clientTypes.contains(simpleType(m.getTypeAsString()))) {
                return "CDI @Produces " + m.getTypeAsString() + " — raw Pub/Sub client producer";
            }
        }

        // (2) Wrapper service: injects a raw client type + declares the seam methods.
        boolean injectsClient = cls.getFields().stream().anyMatch(f ->
                f.isAnnotationPresent("Inject")
                        && clientTypes.contains(simpleType(f.getElementType().asString())));
        if (injectsClient) {
            boolean hasSeam = cls.getMethods().stream()
                    .anyMatch(m -> WRAPPER_SEAM_METHODS.contains(m.getNameAsString()));
            if (hasSeam) {
                return "wrapper service — @Inject raw client + publish/consume seam";
            }
        }
        return null;
    }

    private static String simpleType(String type) {
        int lt = type.indexOf('<');
        String raw = lt >= 0 ? type.substring(0, lt) : type;
        int dot = raw.lastIndexOf('.');
        return (dot >= 0 ? raw.substring(dot + 1) : raw).trim();
    }
}
