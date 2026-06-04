package com.altrix.orchestrator.infrastructure.leak;

import com.altrix.orchestrator.domain.model.leak.PubSubLeakKind;
import com.altrix.orchestrator.domain.model.leak.PubSubLeakViolation;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Output-gate scanner — surfaces every Google Cloud Pub/Sub artifact that
 * survived migration.
 *
 * <p>The migration target is Apache Kafka.  Anything matching here is a
 * migration failure: by the time this runs the migrator has already
 * processed the artifact AND the contract repair loop has stabilised
 * cross-file references.  Whatever Google Pub/Sub residue remains is
 * because the per-file AI call silently failed (rate limit, network,
 * output rejected by the markdown / structural guards) and the file
 * was left byte-for-byte original.
 *
 * <p>Three detection layers, each backed by JavaParser AST so the
 * validator does not get fooled by string occurrences in comments /
 * Javadoc / string literals:
 * <ol>
 *   <li><b>Imports</b> — any {@code import com.google.api.services.pubsub.*},
 *       {@code com.google.cloud.pubsub.*}, {@code com.google.pubsub.*},
 *       {@code org.springframework.cloud.gcp.pubsub.*}.</li>
 *   <li><b>Type references</b> — code-position use of a Google-only simple
 *       name ({@code Pubsub}, {@code PubsubMessage}, {@code ReceivedMessage},
 *       {@code PublishRequest}, {@code PullRequest}, {@code PullResponse},
 *       {@code PublishResponse}, {@code AcknowledgeRequest},
 *       {@code PubSubTemplate}) NOT shadowed by a project-declared type
 *       of the same simple name.</li>
 *   <li><b>Method chains</b> — Pub/Sub-only call patterns:
 *       {@code *.projects().topics()...}, {@code *.projects().subscriptions()...},
 *       {@code testIamPermissions(...)}, {@code .execute()} on a chain
 *       beginning with a Pub/Sub-named receiver.</li>
 * </ol>
 *
 * <p>Two additional non-Java passes cover build & bootstrap files (these
 * are NOT AST — XML / YAML / properties live below JavaParser's domain,
 * so we use targeted regexes):
 * <ul>
 *   <li><b>Dependencies</b> — {@code pom.xml}, {@code build.gradle},
 *       {@code build.gradle.kts}, {@code ivy.xml} containing
 *       {@code google-api-services-pubsub} / {@code google-cloud-pubsub} /
 *       {@code spring-cloud-gcp(-starter)?-pubsub}.</li>
 *   <li><b>Bootstrap config</b> — {@code application.yml} / {@code .yaml} /
 *       {@code .properties} containing {@code spring.cloud.gcp.pubsub.*} /
 *       {@code gcp.pubsub.*} / {@code GOOGLE_APPLICATION_CREDENTIALS} /
 *       {@code PUBSUB_EMULATOR_HOST}.</li>
 * </ul>
 *
 * <p>The validator is pure / stateless / thread-safe.  Spring default
 * singleton bean scope.
 */
@Slf4j
@Component
public class PubSubLeakValidator {

    // ── package + simple-name allow / deny lists ────────────────────────────

    /** Java import prefixes that are 100% Pub/Sub — any match is a leak. */
    private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
            "com.google.api.services.pubsub",
            "com.google.cloud.pubsub",
            "com.google.pubsub",
            "org.springframework.cloud.gcp.pubsub"
    );

    /**
     * Simple type names that exclusively belong to the Google Pub/Sub API
     * surface.  Validator only flags them when no project-declared type
     * shadows the name — so a hand-written {@code class Subscription} in
     * the user's domain is left alone.
     */
    private static final Set<String> FORBIDDEN_TYPE_NAMES = Set.of(
            "Pubsub",
            "PubsubMessage",
            "ReceivedMessage",
            "PublishRequest",
            "PullRequest",
            "PullResponse",
            "PublishResponse",
            "AcknowledgeRequest",
            "PubSubTemplate",
            "Subscriber",
            "Publisher",
            "TopicAdminClient",
            "SubscriptionAdminClient"
    );

    /** Method names that are Pub/Sub-only when chained on a Google client. */
    private static final Set<String> FORBIDDEN_METHOD_NAMES = Set.of(
            "testIamPermissions",
            "getOrCreateSubscription"
    );

    /**
     * Maven/Gradle dependency-name fragments.  Match anywhere in pom /
     * build script content — when present the build artifact still pulls
     * in Google Pub/Sub libraries even if no Java source references them.
     */
    private static final List<String> FORBIDDEN_DEP_FRAGMENTS = List.of(
            "google-api-services-pubsub",
            "google-cloud-pubsub",
            "spring-cloud-gcp-pubsub",
            "spring-cloud-gcp-starter-pubsub"
    );

    /** Bootstrap-config keys that target Pub/Sub. */
    private static final List<String> FORBIDDEN_CONFIG_KEYS = List.of(
            "spring.cloud.gcp.pubsub",
            "gcp.pubsub.",
            "GOOGLE_APPLICATION_CREDENTIALS",
            "PUBSUB_EMULATOR_HOST"
    );

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Scans every file in {@code files} and returns one violation list.
     * Empty list means the artifact is Pub/Sub-free and may proceed to
     * sandbox compile.
     *
     * @param files  path → source content map of the migrated artifact.
     *               Java files are AST-parsed; build / bootstrap files
     *               are scanned with targeted regexes (the formats are
     *               below JavaParser's domain).  Other file types are
     *               skipped.
     */
    public List<PubSubLeakViolation> validate(Map<String, String> files) {
        if (files == null || files.isEmpty()) return List.of();

        // Build the project type index once — used by the type-reference
        // check to avoid flagging user-declared types that happen to share
        // a Google simple name.
        Set<String> projectTypes = indexProjectTypeNames(files);

        List<PubSubLeakViolation> all = new ArrayList<>();
        JavaParser parser = new JavaParser();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String path = entry.getKey();
            String content = entry.getValue();
            if (path == null || content == null || content.isBlank()) continue;
            String lower = path.toLowerCase();

            if (lower.endsWith(".java")) {
                ParseResult<CompilationUnit> r;
                try {
                    r = parser.parse(content);
                } catch (Exception e) {
                    log.debug("PubSubLeakValidator: could not parse '{}' ({})", path, e.getMessage());
                    continue;
                }
                r.getResult().ifPresent(cu -> {
                    scanImports(path, cu, all);
                    scanTypeReferences(path, cu, projectTypes, all);
                    scanMethodChains(path, cu, all);
                });
            } else if (isPom(lower) || isGradle(lower) || isIvy(lower)) {
                scanDependencies(path, content, all);
            } else if (isYaml(lower) || isProperties(lower)) {
                scanConfigKeys(path, content, all);
            }
        }
        return Collections.unmodifiableList(all);
    }

    public boolean isClean(Map<String, String> files) {
        return validate(files).isEmpty();
    }

    public Map<String, List<PubSubLeakViolation>> groupByFile(List<PubSubLeakViolation> violations) {
        if (violations == null || violations.isEmpty()) return Map.of();
        Map<String, List<PubSubLeakViolation>> grouped = new LinkedHashMap<>();
        for (PubSubLeakViolation v : violations) {
            grouped.computeIfAbsent(v.filePath(), k -> new ArrayList<>()).add(v);
        }
        return grouped;
    }

    /** Diagnostic — formats every violation as one line. */
    public String render(List<PubSubLeakViolation> violations) {
        if (violations == null || violations.isEmpty()) return "(clean)";
        StringBuilder sb = new StringBuilder();
        for (PubSubLeakViolation v : violations) sb.append(v.toLine()).append('\n');
        return sb.toString();
    }

    public Set<PubSubLeakKind> kinds(List<PubSubLeakViolation> violations) {
        if (violations == null || violations.isEmpty()) return Set.of();
        Set<PubSubLeakKind> kinds = new HashSet<>();
        for (PubSubLeakViolation v : violations) kinds.add(v.kind());
        return kinds;
    }

    /** Test convenience — get the first violation of a given kind, or {@code null}. */
    public PubSubLeakViolation firstByKindUtil(List<PubSubLeakViolation> violations,
                                               PubSubLeakKind kind) {
        if (violations == null) return null;
        for (PubSubLeakViolation v : violations) if (v.kind() == kind) return v;
        return null;
    }

    // ── Java-level checks ──────────────────────────────────────────────────

    private void scanImports(String path, CompilationUnit cu, List<PubSubLeakViolation> out) {
        for (ImportDeclaration imp : cu.getImports()) {
            String fqn = imp.getNameAsString();
            for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
                if (fqn.equals(prefix) || fqn.startsWith(prefix + ".")) {
                    out.add(new PubSubLeakViolation(
                            PubSubLeakKind.GOOGLE_IMPORT,
                            path,
                            imp.getBegin().map(p -> p.line).orElse(-1),
                            fqn,
                            "Imports Google Pub/Sub package '" + prefix + "'.",
                            suggestImportReplacement(fqn)));
                    break;
                }
            }
        }
    }

    private void scanTypeReferences(String path, CompilationUnit cu,
                                    Set<String> projectTypes,
                                    List<PubSubLeakViolation> out) {
        cu.findAll(ClassOrInterfaceType.class).forEach(t -> {
            String simple = t.getNameAsString();
            if (!FORBIDDEN_TYPE_NAMES.contains(simple)) return;
            // Project shadows the name? Then leave it alone.
            if (projectTypes.contains(simple)) return;
            out.add(new PubSubLeakViolation(
                    PubSubLeakKind.GOOGLE_TYPE_REFERENCE,
                    path,
                    t.getBegin().map(p -> p.line).orElse(-1),
                    simple,
                    "Type '" + simple + "' is a Google Pub/Sub API class.",
                    suggestTypeReplacement(simple)));
        });
    }

    private void scanMethodChains(String path, CompilationUnit cu, List<PubSubLeakViolation> out) {
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            String name = call.getNameAsString();
            // Direct forbidden names.
            if (FORBIDDEN_METHOD_NAMES.contains(name)) {
                out.add(new PubSubLeakViolation(
                        PubSubLeakKind.GOOGLE_METHOD_CHAIN,
                        path,
                        call.getBegin().map(p -> p.line).orElse(-1),
                        name,
                        "Method '" + name + "(...)' is Google Pub/Sub-specific.",
                        suggestMethodReplacement(name)));
                return;
            }
            // Pattern: .projects().{topics|subscriptions}(...).{publish|pull|acknowledge}(...)
            // We detect this by walking up the scope chain.
            String full = renderChain(call);
            if (full != null && (
                    full.contains(".projects().topics().publish")
                 || full.contains(".projects().topics().get")
                 || full.contains(".projects().subscriptions().pull")
                 || full.contains(".projects().subscriptions().acknowledge")
                 || full.contains(".projects().subscriptions().get")
            )) {
                out.add(new PubSubLeakViolation(
                        PubSubLeakKind.GOOGLE_METHOD_CHAIN,
                        path,
                        call.getBegin().map(p -> p.line).orElse(-1),
                        full,
                        "Method chain '" + full + "' is Google Pub/Sub REST v1.",
                        suggestChainReplacement(full)));
            }
        });
    }

    /** Renders the receiver chain leading into {@code call} as dotted text. */
    private static String renderChain(MethodCallExpr call) {
        StringBuilder sb = new StringBuilder();
        MethodCallExpr cursor = call;
        while (cursor != null) {
            sb.insert(0, "." + cursor.getNameAsString() + "()");
            if (cursor.getScope().isPresent() && cursor.getScope().get() instanceof MethodCallExpr s) {
                cursor = s;
            } else {
                cursor = null;
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ── Non-Java checks ────────────────────────────────────────────────────

    private void scanDependencies(String path, String content, List<PubSubLeakViolation> out) {
        for (String frag : FORBIDDEN_DEP_FRAGMENTS) {
            int idx = content.indexOf(frag);
            if (idx < 0) continue;
            int line = lineOf(content, idx);
            out.add(new PubSubLeakViolation(
                    PubSubLeakKind.GOOGLE_DEPENDENCY,
                    path, line, frag,
                    "Build file still declares Pub/Sub dependency '" + frag + "'.",
                    "Remove this dependency entirely.  Pub/Sub libraries must not appear in a Kafka project."));
        }
    }

    private void scanConfigKeys(String path, String content, List<PubSubLeakViolation> out) {
        // (a) Direct dotted-key / env-var occurrences anywhere in the file.
        for (String key : FORBIDDEN_CONFIG_KEYS) {
            int idx = content.indexOf(key);
            if (idx < 0) continue;
            int line = lineOf(content, idx);
            out.add(new PubSubLeakViolation(
                    PubSubLeakKind.GOOGLE_CONFIG_KEY,
                    path, line, key,
                    "Config file still declares Pub/Sub bootstrap key '" + key + "'.",
                    "Replace with the equivalent Kafka key (e.g. spring.kafka.bootstrap-servers, spring.kafka.consumer.group-id)."));
        }
        // (b) YAML nested-key form — real app configs almost never use the
        // dotted form.  Look for a `pubsub:` key sitting under a `gcp:` key
        // (any level of indentation between), which is the canonical Spring
        // Cloud GCP Pub/Sub block.
        if (path.toLowerCase().endsWith(".yml") || path.toLowerCase().endsWith(".yaml")) {
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String trimmed = lines[i].stripLeading();
                if (!trimmed.startsWith("pubsub:") && !trimmed.equals("pubsub:")) continue;
                // Walk up to see if any ancestor key is `gcp:` (heuristic — the
                // YAML's nested-block intent).
                boolean underGcp = false;
                int pubsubIndent = lines[i].length() - trimmed.length();
                for (int j = i - 1; j >= 0; j--) {
                    String aboveTrim = lines[j].stripLeading();
                    if (aboveTrim.isEmpty() || aboveTrim.startsWith("#")) continue;
                    int aboveIndent = lines[j].length() - aboveTrim.length();
                    if (aboveIndent >= pubsubIndent) continue;
                    if (aboveTrim.startsWith("gcp:")) underGcp = true;
                    break;
                }
                if (underGcp) {
                    out.add(new PubSubLeakViolation(
                            PubSubLeakKind.GOOGLE_CONFIG_KEY,
                            path, i + 1, "pubsub:",
                            "YAML block 'gcp.pubsub.*' is a Pub/Sub bootstrap section.",
                            "Replace the entire gcp.pubsub: block with spring.kafka.* keys (bootstrap-servers, consumer.group-id, ...)."));
                }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static int lineOf(String content, int charIndex) {
        int line = 1;
        for (int i = 0; i < charIndex && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static boolean isPom(String lower)        { return lower.endsWith("pom.xml"); }
    private static boolean isGradle(String lower)     { return lower.endsWith(".gradle") || lower.endsWith(".gradle.kts"); }
    private static boolean isIvy(String lower)        { return lower.endsWith("ivy.xml"); }
    private static boolean isYaml(String lower)       { return lower.endsWith(".yml") || lower.endsWith(".yaml"); }
    private static boolean isProperties(String lower) { return lower.endsWith(".properties"); }

    private static Set<String> indexProjectTypeNames(Map<String, String> files) {
        Pattern p = Pattern.compile(
                "(?:^|\\s)(?:public\\s+|final\\s+|abstract\\s+|static\\s+|sealed\\s+|non-sealed\\s+|private\\s+|protected\\s+)*"
                        + "(?:class|interface|enum|record|@interface)\\s+(\\w+)",
                Pattern.MULTILINE);
        Set<String> names = new HashSet<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (!e.getKey().toLowerCase().endsWith(".java")) continue;
            Matcher m = p.matcher(e.getValue() == null ? "" : e.getValue());
            while (m.find()) names.add(m.group(1));
        }
        return names;
    }

    // ── Kafka-replacement suggestions ──────────────────────────────────────

    private static String suggestImportReplacement(String fqn) {
        if (fqn.contains("PullRequest") || fqn.contains("PullResponse") || fqn.contains("ReceivedMessage")) {
            return "Remove this import.  Use org.apache.kafka.clients.consumer.{KafkaConsumer, ConsumerRecord, ConsumerRecords}.";
        }
        if (fqn.contains("PublishRequest") || fqn.contains("PublishResponse") || fqn.contains("PubsubMessage")) {
            return "Remove this import.  Use org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}.";
        }
        if (fqn.contains("AcknowledgeRequest") || fqn.contains("Empty")) {
            return "Remove this import.  Kafka commits offsets via consumer.commitSync() / commitAsync() — no acknowledge request type exists.";
        }
        if (fqn.endsWith(".Pubsub")) {
            return "Remove this import.  Inject org.apache.kafka.clients.producer.KafkaProducer and/or org.apache.kafka.clients.consumer.KafkaConsumer instead of the Google Pubsub client.";
        }
        if (fqn.contains("PubSubTemplate")) {
            return "Remove this import.  Use org.springframework.kafka.core.KafkaTemplate.";
        }
        return "Remove this import — Pub/Sub libraries must not appear in a Kafka project.";
    }

    private static String suggestTypeReplacement(String simple) {
        return switch (simple) {
            case "Pubsub" -> "Replace with KafkaProducer<String,String> or KafkaConsumer<String,String> depending on usage.";
            case "PubsubMessage" -> "Replace with ProducerRecord<String,String> for sends, ConsumerRecord<String,String> for receives.";
            case "ReceivedMessage" -> "Replace with ConsumerRecord<String,String>.";
            case "PublishRequest", "PublishResponse" -> "Drop — KafkaProducer.send(ProducerRecord) returns Future<RecordMetadata>; no request/response types are needed.";
            case "PullRequest", "PullResponse" -> "Drop — KafkaConsumer.poll(Duration) returns ConsumerRecords<K,V>; no request/response types are needed.";
            case "AcknowledgeRequest" -> "Drop — Kafka commits are direct calls (commitSync/commitAsync), not request objects.";
            case "PubSubTemplate" -> "Replace with org.springframework.kafka.core.KafkaTemplate<String,String>.";
            case "Subscriber", "Publisher" -> "Replace with the Kafka equivalent: @KafkaListener (Spring) / KafkaConsumer (raw) for Subscriber; KafkaTemplate / KafkaProducer for Publisher.";
            case "TopicAdminClient", "SubscriptionAdminClient" -> "Replace with org.apache.kafka.clients.admin.AdminClient for topic management; consumer groups for subscription-like semantics.";
            default -> "Replace with the appropriate Kafka client type.";
        };
    }

    private static String suggestMethodReplacement(String name) {
        return switch (name) {
            case "testIamPermissions" -> "Kafka uses ACLs via AdminClient (AclBinding + AclOperation).  If no equivalent is needed in your test, return an empty list with a // TODO altrix comment.";
            case "getOrCreateSubscription" -> "Kafka subscriptions = consumer groups.  Drop this call and configure consumer.group.id when constructing the KafkaConsumer.";
            default -> "Replace with the equivalent Kafka call.";
        };
    }

    private static String suggestChainReplacement(String chain) {
        if (chain.contains("subscriptions().pull"))         return "Replace with consumer.poll(Duration.ofSeconds(N)) returning ConsumerRecords<String,String>.";
        if (chain.contains("subscriptions().acknowledge")) return "Replace with consumer.commitSync() (or commitAsync(callback)).";
        if (chain.contains("topics().publish"))             return "Replace with producer.send(new ProducerRecord<>(topic, key, value)).get() (or async without .get()).";
        if (chain.contains("topics().get") || chain.contains("subscriptions().get")) {
            return "Replace with AdminClient.describeTopics(...) or simply remove if Kafka auto-creation is enabled.";
        }
        return "Replace this chain with the equivalent Kafka client call.";
    }

    // ── Type declarations the project itself owns ──────────────────────────

    private record TypeFingerprint(String name, String pkg) {}

    private static List<TypeFingerprint> declaredTypes(CompilationUnit cu) {
        List<TypeFingerprint> list = new ArrayList<>();
        String pkg = cu.getPackageDeclaration().map(p -> p.getName().toString()).orElse("");
        for (TypeDeclaration<?> td : cu.getTypes()) {
            list.add(new TypeFingerprint(td.getNameAsString(), pkg));
        }
        return list;
    }
}
