package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.enums.DocumentType;
import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.enums.JakartaMessagingTarget;
import com.altrix.common.domain.model.ApprovedPlan;
import com.altrix.common.domain.model.DocumentChunk;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.model.PrunedContext;
import com.altrix.orchestrator.domain.model.blueprint.BlueprintFile;
import com.altrix.orchestrator.domain.model.blueprint.ProjectBlueprint;
import com.altrix.orchestrator.domain.port.out.ProjectBlueprintPort;
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
import com.altrix.orchestrator.infrastructure.contract.ContractRepairer;
import com.altrix.orchestrator.infrastructure.contract.ContractValidator;
import com.altrix.orchestrator.infrastructure.leak.PubSubLeakRepairer;
import com.altrix.orchestrator.infrastructure.leak.PubSubLeakValidator;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
            * FILE = CLASS NAME.  The Java compiler refuses to compile a
              file named `Foo.java` whose public type is `Bar`.  You are
              given one file at a time and you cannot rename the file.
              Therefore: do NOT rename the public class / interface /
              enum / record inside the file.  Keep the public type name
              EXACTLY as it appears at the top of the input.  If the
              original is `IGoogleErrorConverter`, the output must still
              declare `public interface IGoogleErrorConverter` — only
              the body / Javadoc may change.
            * EVERY type used must be imported.  When you reference
              `KafkaProducer<String, String>` as a field, parameter, or
              return type — even ONCE — you MUST add `import
              org.apache.kafka.clients.producer.KafkaProducer;` at the
              top.  Same for `KafkaConsumer`, `ProducerRecord`,
              `ConsumerRecord`, `OffsetAndMetadata`, `TopicPartition`,
              and `java.time.Duration`.  Missing imports turn the whole
              file into "cannot find symbol".
            * DO NOT use Lombok's experimental `onConstructor_ = @Inject`
              parameter on `@AllArgsConstructor` / `@RequiredArgsConstructor`
              / `@NoArgsConstructor`.  It requires a special compile-time
              configuration most projects do not enable.  Use a plain
              `@AllArgsConstructor` without `onConstructor_`, or just
              write the constructor by hand annotated with `@Inject`.
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

            EXACT CDI annotation packages — using the wrong one breaks compile:
              * @Produces lives at jakarta.enterprise.inject.Produces.
                It does NOT live at jakarta.inject.Produces — that import is
                wrong and will fail to resolve.  @Inject DOES live in
                jakarta.inject.Inject, which is why the confusion happens;
                do not collapse the two packages.
              * @Named lives at jakarta.inject.Named (correct).
              * @ApplicationScoped / @Dependent / @Singleton (CDI scopes) live
                under jakarta.enterprise.context.*.

            EXACT Kafka API surface — use ONLY these, do not invent variants:
              * org.apache.kafka.common.KafkaException  (NOT org.apache.kafka.common.errors.KafkaException — that package
                does not exist; only specific subclasses live under .errors.* like RetriableException, SerializationException.)
              * For committing offsets after manual ack of a Pub/Sub pull:
                  consumer.commitAsync(new OffsetCommitCallback() {
                      @Override
                      public void onComplete(Map<TopicPartition, OffsetAndMetadata> offsets, Exception exception) { ... }
                  });
                  // OffsetAndMetadata lives in org.apache.kafka.clients.consumer.
                  // The 2nd callback parameter is java.lang.Exception, NOT Throwable.
                  // There is NO class called OffsetCommitResult — do not import it.
                  // There is NO class called ConsumerException — catch RuntimeException
                  // or specific subclasses of org.apache.kafka.common.errors.*.
              * For synchronous commit: consumer.commitSync();
              * Imports you typically need together: org.apache.kafka.clients.consumer.KafkaConsumer,
                ConsumerRecord, ConsumerRecords, OffsetAndMetadata, OffsetCommitCallback,
                org.apache.kafka.common.TopicPartition.  Always import KafkaConsumer explicitly
                when you declare a field of that type — the compiler does NOT auto-discover it.

            FORBIDDEN packages — they do NOT exist in kafka-clients, never import from them:
              * org.apache.kafka.common.security.auth.permission.*   (you cannot map Pub/Sub IAM permissions
                to Kafka by inventing this; for ACLs use org.apache.kafka.common.acl.AclOperation +
                org.apache.kafka.common.resource.ResourcePattern instead, or — preferred — leave the
                original method body and add a `// TODO altrix: ...` comment.)

            When NO real Kafka equivalent exists for a Pub/Sub concept (IAM
            permission tests, ack-id-based acknowledge, push subscriptions):
              * Do NOT invent a placeholder type name like {@code Topic},
                {@code Subscription}, {@code AdminPermission} as a return /
                parameter / field type.  Inventing it guarantees
                "cannot find symbol".
              * Instead: keep the ORIGINAL return / parameter types
                (`PubsubTopic`, `PubsubSubscription`, etc.) and put a
                `// TODO altrix: ...` comment in the method body explaining
                that the Kafka equivalent requires manual implementation
                (e.g. via AdminClient, an external IAM system, etc.).
              * OR change the return type to `void` / `String` / `boolean`
                if the body can return a sensible primitive.

            """;

    /** Default prefix when the project IS Spring Boot.  Keep terse — the
     *  rest of the prompt already assumes Spring + spring-kafka.  The
     *  consumer/producer rules below prevent the two boot-breaking shapes the
     *  migrator otherwise emits: @Value read during construction (boot NPE) and
     *  hand-wired listener containers that duplicate Boot auto-config. */
    private static final String SPRING_BOOT_PREFIX = """
            DETECTED STACK: Spring Boot.

            SPRING-KAFKA CONSUMER — use this exact minimal shape.  Spring Boot
            auto-configures the ConsumerFactory and the listener container factory
            from the spring.kafka.* properties in application.yml; do NOT rebuild them.

                @Service
                public class XxxSubscriber {
                    @KafkaListener(topics = "...", groupId = "...")
                    public void handle(@Payload String payload) {
                        // process
                    }
                }

            FORBIDDEN in a @KafkaListener consumer class (these break or duplicate
            Spring Boot auto-configuration):
              * @Autowired / field of KafkaMessageListenerContainer,
                ConcurrentMessageListenerContainer or MessageListenerContainer —
                Spring exposes NO such bean for injection; it fails at startup with
                UnsatisfiedDependencyException.  @KafkaListener manages its own
                container — you never start/stop one manually.
              * A @Bean ConsumerFactory / DefaultKafkaConsumerFactory /
                KafkaListenerContainerFactory / ConcurrentKafkaListenerContainerFactory
                declared inside the @Service — Boot already provides these.  Only add
                a custom @Bean factory if the SOURCE explicitly required non-default
                behaviour, and then put it in a @Configuration class, not the @Service.
              * Hardcoded bootstrap.servers / group.id / deserializer Properties —
                these belong in application.yml (spring.kafka.consumer.*), not in code.

            SPRING-KAFKA PRODUCER — inject config via the CONSTRUCTOR, never read a
            @Value field during construction.  Spring sets @Value FIELDS only AFTER
            the constructor returns, so reading one in the constructor (directly OR via
            a helper the constructor calls) yields null → NullPointerException on boot.
              * Prefer KafkaTemplate<String,String> (Boot auto-configured) over a
                hand-built KafkaProducer.
              * If you must build a KafkaProducer, take the value as a constructor
                PARAMETER: public Pub(@Value("${spring.kafka.bootstrap-servers}") String servers).

            """;

    /** Opt-in only — selected by the user's explicit
     *  {@code JakartaMessagingTarget.SPRING_KAFKA_HYBRID} choice on the plan,
     *  NEVER inferred from source.  Used in place of {@link #JAKARTA_EE_PREFIX}
     *  when the project is Jakarta EE AND the user asked for spring-kafka.
     *
     *  <p>Grounded in a hand-built, proven-working reference project
     *  ({@code kb-test-jakarta-springkafka}) — every constraint below fixes a
     *  real bug hit and fixed there, not a guess: the WELD-001435
     *  "not proxyable" CDI deployment failure ({@code @Dependent}, not
     *  {@code @ApplicationScoped}, on the {@code KafkaTemplate} producer
     *  method — {@code KafkaTemplate} has no no-arg constructor so Weld can't
     *  generate a client proxy for a normal scope); the bootstrapper
     *  null-before-close ordering bug; and {@code UNKNOWN_TOPIC_OR_PARTITION}
     *  warnings on every consumer until a producer happens to fire first
     *  (fixed there with {@code KafkaAdmin}/{@code NewTopic} beans). */
    private static final String JAKARTA_EE_SPRING_KAFKA_PREFIX = """
            DETECTED STACK: Jakarta EE 10 + Spring Kafka hybrid (user-selected).

            The project is Jakarta EE (CDI/EJB/JAX-RS) but the user explicitly
            chose spring-kafka for messaging instead of raw kafka-clients.
            Jakarta EE has no Spring ApplicationContext by default, so this
            migration MUST manually bootstrap one, side by side with the CDI
            container, bridged in both directions.  This is plain Spring
            Framework (spring-context + spring-kafka) — NOT Spring Boot.
            NEVER add any spring-boot-starter-* dependency.

            You MUST generate ALL of the following files — they are not
            optional extras, the bridge does not work without every one of
            them:

            1. SpringKafkaConfig.java
               @Configuration @EnableKafka, with:
               - @ComponentScan restricted to the actual listener packages in
                 this project (not a wildcard scan of everything).
               - producerFactory / kafkaTemplate / consumerFactory /
                 kafkaListenerContainerFactory beans.
               - bootstrap.servers read from an environment variable via
                 @Value — NEVER hardcoded.
               - A KafkaAdmin bean PLUS a NewTopic @Bean for every topic this
                 project produces or consumes.  Without this, every consumer
                 logs UNKNOWN_TOPIC_OR_PARTITION until a producer happens to
                 fire first and the broker's auto-create kicks in — always
                 declare topics explicitly.

            2. SpringContextBootstrapper.java
               Static start()/stop()/context() holding an
               AnnotationConfigApplicationContext.
               - start() MUST force-close any existing context first (if
                 context != null, close it before creating the new one) —
                 never just warn and continue.
               - stop() MUST null out the static context reference BEFORE
                 calling close() on it, not after.  If close() throws, a
                 stale-but-still-referenced context is worse than a null one;
                 null first so a failure can't leave dangling state.

            3. AppStartupListener.java
               @WebListener implementing ServletContextListener.
               contextInitialized -> SpringContextBootstrapper.start().
               contextDestroyed   -> SpringContextBootstrapper.stop().

            4. SpringBeanBridge.java (CDI -> Spring)
               A CDI class with:
                   @Produces
                   @Dependent   // NOT @ApplicationScoped — see below
                   public KafkaTemplate<String,String> kafkaTemplate() {
                       return SpringContextBootstrapper.context().getBean(KafkaTemplate.class);
                   }
               @Dependent IS REQUIRED here.  KafkaTemplate has no no-arg
               constructor, only a ProducerFactory-arg one, so CDI/Weld
               cannot generate a client proxy for a normal scope like
               @ApplicationScoped — that fails deployment with
               WELD-001435 ("type is not proxyable"). @Dependent is a CDI
               pseudo-scope that needs no proxy, so it works. Do NOT
               "fix" this back to @ApplicationScoped.

            5. CdiLookup.java (Spring -> CDI)
                   public static <T> T get(Class<T> type) {
                       return CDI.current().select(type).get();
                   }

            6. EVERY former Pub/Sub consumer becomes a plain Spring
               @Component (NOT a CDI bean) with @KafkaListener methods. Any
               call from inside that listener into a CDI-managed
               persistence/business class MUST go through
               CdiLookup.get(SomeClass.class) — NEVER `new SomeClass()`, and
               NEVER @Inject (CDI injection does not cross into a Spring
               bean). A @KafkaListener method with no surrounding @Component
               on its class is a guaranteed silent failure — component scan
               will never find it.

            7. Any CDI persistence/business class reached from a listener via
               CdiLookup.get(...) (the equivalent of an OrderStore /
               PaymentStore from the original Pub/Sub code) MUST be migrated
               to a @Stateless EJB (jakarta.ejb.Stateless) with
               @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
               on the methods called from listener threads — NOT left as
               plain @ApplicationScoped. A Kafka consumer thread carries no
               JTA transaction; only an EJB proxy boundary creates one
               regardless of which thread calls it. This is required, not
               optional polish.

            FORBIDDEN:
              * Any spring-boot-starter-* dependency (this is plain Spring
                Framework, manually bootstrapped — Spring Boot's
                auto-configuration does not apply and does not run here).
              * @SpringBootApplication, @EnableAutoConfiguration, or any
                annotation that assumes a Spring Boot–managed context.
              * Leaving a CDI persistence class reached via CdiLookup as
                @ApplicationScoped when it is invoked from a listener thread.

            """;

    /** User-selected (never inferred) — converts CDI persistence classes reached
     *  from a Spring {@code @KafkaListener} via {@code CdiLookup} to
     *  {@code @Stateless} EJBs, since a Kafka consumer thread carries no JTA
     *  transaction and only an EJB proxy boundary creates one. Invoked only
     *  for {@link com.altrix.common.domain.enums.JakartaMessagingTarget#SPRING_KAFKA_HYBRID}. */
    private final com.altrix.orchestrator.infrastructure.hybrid.CdiStatelessConverter cdiStatelessConverter;
    /** Hybrid-only — adds the 5 mandatory Spring Kafka bridge classes
     *  (SpringKafkaConfig / SpringContextBootstrapper / AppStartupListener /
     *  SpringBeanBridge / CdiLookup) that a genuine Pub/Sub project has no
     *  source file to map from. The ONLY pipeline component that ADDS files
     *  rather than transforming existing ones; runs last so its @ComponentScan
     *  reflects the final, repaired listener set. */
    private final com.altrix.orchestrator.infrastructure.hybrid.HybridScaffoldingGenerator hybridScaffoldingGenerator;
    /** Hybrid-only — deterministically converts Pub/Sub consumers to Spring
     *  {@code @KafkaListener @Component} classes from SOURCE, before the LLM
     *  pass, and removes them from the LLM's input. The only transform that
     *  runs source-in / pre-migration (everything else post-processes migrated
     *  output); see its Javadoc. Producing real listeners is also what lets
     *  {@link #hybridScaffoldingGenerator} fire. */
    private final com.altrix.orchestrator.infrastructure.hybrid.HybridConsumerTransformer hybridConsumerTransformer;
    /** Hybrid-only — identifies the Pub/Sub topic-bootstrap class (@Singleton
     *  @Startup) so it can be DELETED (its topic-creation job moves into the
     *  generated SpringKafkaConfig's KafkaAdmin/NewTopic beans) and harvests the
     *  topic constants those beans need. */
    private final com.altrix.orchestrator.infrastructure.hybrid.TopicBootstrapAnchor topicBootstrapAnchor;
    /** Hybrid-only — rewrites the Pub/Sub constants class (PubSubConfig shape) to
     *  a pure constants holder before the LLM sees it, removing the @Configuration
     *  /@Bean boilerplate and GCP path helpers the model otherwise hallucinates
     *  into a second, conflicting Kafka config. */
    private final com.altrix.orchestrator.infrastructure.hybrid.PubSubConfigAnchor pubSubConfigAnchor;
    /** Hybrid-only — post-revert deterministic fix for non-consumer files (e.g. JAX-RS
     *  resources) that call {@code pubsub.publish(PubSubConfig.topic(CONST), msg)}:
     *  rewrites them to {@code kafkaTemplate.send(PubSubConfig.CONST, msg)} via CDI
     *  injection of the KafkaTemplate produced by {@code SpringBeanBridge}.  Runs after
     *  {@link #revertFilesWithUnresolvedImports} so it sees the reverted original source
     *  (which still carries the GCP publish pattern). */
    private final com.altrix.orchestrator.infrastructure.hybrid.HybridPublishRewriter hybridPublishRewriter;
    /** Hybrid-only — detects the hand-rolled Pub/Sub wrapper service and its raw-client
     *  CDI producer so they can be DELETED instead of LLM-migrated: with consumers
     *  converted to {@code @KafkaListener} and publishers on the bridged KafkaTemplate
     *  they are dead code, and the LLM reliably re-implements them with type errors
     *  (job d3fa6347: {@code Iterable} vs {@code List}). */
    private final com.altrix.orchestrator.infrastructure.hybrid.PubSubWrapperRemover pubSubWrapperRemover;

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
    /** Configurable deny-list of fully-qualified imports the model is known
     *  to hallucinate ({@code OffsetCommitResult},
     *  {@code org.apache.kafka.common.errors.KafkaException}, …).  Migrated
     *  files importing one of those revert to the original. */
    private final com.altrix.orchestrator.infrastructure.config.MigrationConfig migrationConfig;
    /** Project Semantic Index — flags cross-file inconsistencies (interface
     *  drift, unknown method calls, constructor arity, file/class mismatch)
     *  before the artifact reaches the sandbox.  Replaces the symptom-level
     *  per-file revert with a structured violation list the repair loop
     *  can act on. */
    private final ContractValidator contractValidator;
    /** Minimal-patch LLM loop driven by {@link #contractValidator}.  Runs
     *  bounded iterations of "validate → patch each violating file →
     *  re-validate" so the artifact reaches the sandbox contract-clean
     *  whenever the model can see the fix. */
    private final ContractRepairer contractRepairer;
    /** Output-gate scanner — finds GCP Pub/Sub artifacts that survived
     *  migration (typically because the per-file AI call silently failed
     *  on rate-limit / contamination guards and the file was kept original).
     *  Runs AFTER the contract pass so cross-file structure is stable
     *  before we attempt to rewrite leaked Google API calls. */
    private final PubSubLeakValidator pubSubLeakValidator;
    /** Minimal-patch LLM loop fed by {@link #pubSubLeakValidator}.  Each
     *  iteration rewrites every file with surviving Pub/Sub references,
     *  using the validator's pre-computed Kafka replacement suggestions
     *  to constrain the model's design freedom to zero. */
    private final PubSubLeakRepairer pubSubLeakRepairer;
    /** Read-side access to the project-wide semantic map produced by the
     *  ProjectMapper phase.  Optional: when no blueprint exists for the
     *  session (mapper disabled, parse failed, or unit tests), every lookup
     *  returns empty and the migrator falls back to its file-by-file
     *  behaviour — the blueprint only ever ENRICHES the per-file prompt. */
    private final ProjectBlueprintPort projectBlueprintPort;
    /** Groups coupled files (interface + implementors) so they can be migrated
     *  in one LLM call — keeping the shared contract consistent by construction
     *  instead of letting independent per-file rewrites diverge. */
    private final com.altrix.orchestrator.infrastructure.migration.MigrationClusterPlanner clusterPlanner;
    /** Deterministic (no-AI) final guarantee for the pom.xml dependency
     *  swap.  Runs last, after every other repair pass, so it reconciles
     *  pom.xml against whatever Kafka classes the FINAL Java files import —
     *  closing the gap where the LLM's own pom.xml rewrite failed its
     *  structural check and reverted to the original (still-GCP) file
     *  while the Java side was correctly migrated to Kafka. */
    private final com.altrix.orchestrator.infrastructure.migration.PomDependencyReconciler pomDependencyReconciler;

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
            return new MigrationArtifact(projectId, List.of(), "No files to migrate (storageKey missing)",
                    input.plan().jakartaMessagingTarget());
        }

        try {
            Map<String, String> allFiles = new LinkedHashMap<>(fileReader.readSourceFiles(storageKey));
            // On retry: overlay the previous attempt's migrated content so we start from
            // that checkpoint instead of the original source.  This preserves files that
            // were correctly migrated in attempt N even when AI providers are unavailable
            // on attempt N+1 (which would otherwise regress them to the original).
            if (input.previousArtifact() != null) {
                int overlaid = 0;
                for (MigratedFile f : input.previousArtifact().files()) {
                    if (f.content() != null && !f.content().isBlank()
                            && allFiles.containsKey(f.originalPath())) {
                        allFiles.put(f.originalPath(), f.content());
                        overlaid++;
                    }
                }
                if (overlaid > 0) {
                    log.info("[{}] retry checkpoint: overlaid {}/{} file(s) from previous attempt",
                            getName(), overlaid, allFiles.size());
                }
            }
            PrunedContext pruned = contextPruner.prune(allFiles, input.plan());

            // Detect Jakarta EE vs Spring Boot from the pom and prepend the
            // appropriate stack-specific prefix to the system prompt.
            // Without this, the AI defaulted to Spring annotations
            // (@KafkaListener, KafkaTemplate, @Component, etc.) on plain
            // Jakarta EE projects with no Spring on the classpath — the
            // result was 30+ "package org.springframework.* does not exist"
            // compile errors in the sandbox.  Detection is cheap: just a
            // substring scan of pom.xml.
            //
            // Jakarta-vs-Spring-Boot detection stays source-derived (legitimate
            // — it's "what IS this project"). The sub-choice WITHIN Jakarta
            // (native kafka-clients vs the spring-kafka hybrid) is NEVER
            // inferred here — it comes only from the user's explicit choice,
            // threaded onto the plan all the way from job creation.
            boolean isJakarta = isJakartaProject(allFiles);
            JakartaMessagingTarget jakartaMessagingTarget = input.plan().jakartaMessagingTarget();
            String stackPrefix = !isJakarta
                    ? SPRING_BOOT_PREFIX
                    : jakartaMessagingTarget == JakartaMessagingTarget.SPRING_KAFKA_HYBRID
                            ? JAKARTA_EE_SPRING_KAFKA_PREFIX
                            : JAKARTA_EE_PREFIX;
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
            // Resolve the project blueprint ONCE here, on the calling thread,
            // where the SandboxContext sessionId ThreadLocal is still valid.
            // The per-file workers run on a pool thread that can't see the
            // ThreadLocal, so we pass the resolved (immutable) blueprint down
            // rather than re-reading the context inside each worker.  Null when
            // no blueprint exists → migrator falls back to file-by-file.
            ProjectBlueprint blueprint = resolveBlueprint();

            // ── Hybrid-only, SOURCE-IN: deterministically convert Pub/Sub
            // consumers to Spring @KafkaListener @Component BEFORE the LLM pass,
            // and remove them from the LLM's input so the model can't invent
            // divergent structure for them (the proven, model-independent
            // failure mode). Bindings are read from the full source set (the
            // TopicBootstrap class may itself be pruned), but only files that
            // were going to be migrated are partitioned out. On retry the
            // already-converted files no longer match the @Schedule poller
            // shape, so they fall through to the LLM with the targeted
            // CONSUMER_NOT_CONVERTED retry instruction (e.g. the category-C
            // publish-call rewrite).
            Map<String, String> toMigrate = new LinkedHashMap<>(pruned.files());
            List<MigratedFile> deterministicConsumers = new ArrayList<>();
            // Files we DELETE from the artifact entirely (e.g. the Pub/Sub
            // TopicBootstrap, whose job moves into generated KafkaAdmin/NewTopic
            // beans). Tracked so the pruner's "re-add unchanged" pass below can't
            // resurrect them.
            Set<String> deletedSourcePaths = new LinkedHashSet<>();
            // Topic constant simple-names the deleted bootstrap created — threaded
            // to the scaffolding generator so it emits one NewTopic bean each.
            Set<String> topicConstantNames = new LinkedHashSet<>();
            // Held outside the hybrid block so the re-stamp pass below
            // (after ContractRepairer) can enforce it regardless of what the
            // repairer did to the file.
            MigratedFile anchoredConfig = null;
            if (jakartaMessagingTarget == JakartaMessagingTarget.SPRING_KAFKA_HYBRID) {
                var conv = hybridConsumerTransformer.transform(allFiles);
                for (MigratedFile cf : conv.convertedFiles()) {
                    if (toMigrate.remove(cf.originalPath()) != null) {
                        deterministicConsumers.add(cf);
                    }
                }
                if (!deterministicConsumers.isEmpty() || !conv.bails().isEmpty()) {
                    log.info("[{}] hybrid consumer transform: {} converted deterministically, {} bailed to LLM",
                            getName(), deterministicConsumers.size(), conv.bails().size());
                }

                // PubSubConfig → constants-only (rewrite, re-emit, keep out of the LLM set).
                anchoredConfig = pubSubConfigAnchor.anchor(allFiles);
                if (anchoredConfig != null) {
                    boolean removedFromLlmSet = toMigrate.remove(anchoredConfig.originalPath()) != null;
                    deterministicConsumers.add(anchoredConfig);
                    log.info("[{}] hybrid: PubSubConfig anchored to constants-only '{}' (removed from LLM set={})",
                            getName(), anchoredConfig.originalPath(), removedFromLlmSet);
                } else {
                    log.info("[{}] hybrid: PubSubConfigAnchor found no constants-holder to anchor", getName());
                }

                // TopicBootstrap → DELETE; harvest its topic constants for the generator.
                var bootstrap = topicBootstrapAnchor.analyze(allFiles);
                if (bootstrap != null) {
                    toMigrate.remove(bootstrap.sourceFilePath());
                    deletedSourcePaths.add(bootstrap.sourceFilePath());
                    topicConstantNames.addAll(bootstrap.topicConstantNames());
                    log.info("[{}] hybrid: deleting Pub/Sub bootstrap '{}' (replaced by generated KafkaAdmin/NewTopic "
                            + "beans for {} topic(s))", getName(), bootstrap.sourceFilePath(), topicConstantNames.size());
                }

                // Pub/Sub wrapper service + raw-client CDI producer → DELETE.
                // Dead code by construction once consumers are @KafkaListener
                // components and publishers use the bridged KafkaTemplate —
                // keeping them sends the hand-rolled wrapper to the LLM, which
                // reliably invents a raw kafka-clients re-implementation with
                // type errors (job d3fa6347: Iterable vs List).  Deleting also
                // arms revertFilesWithUnresolvedImports: any LLM output still
                // importing the wrapper reverts to original and flows into the
                // hybridPublishRewriter's deterministic KafkaTemplate rewrite.
                // Gated on full deterministic consumer coverage — if any
                // @Schedule poller bailed to the LLM, its fallback migration
                // may still lean on the wrapper, so we keep it (current
                // behaviour) rather than chase a type we removed.
                if (conv.bails().isEmpty()) {
                    for (String glue : pubSubWrapperRemover.detect(allFiles)) {
                        toMigrate.remove(glue);
                        deletedSourcePaths.add(glue);
                        log.info("[{}] hybrid: deleting Pub/Sub wrapper glue '{}' (consumers are @KafkaListener, "
                                + "publishers use the bridged KafkaTemplate)", getName(), glue);
                    }
                }
            }

            // ── Retry-scope narrowing: on a checkpointed retry, only the files
            // implicated in the previous validation failure go back to the LLM.
            // Everything else already survived attempt N's full guard chain —
            // re-migrating healthy files hands the model a fresh chance to
            // corrupt them (proven regression: job d3fa6347, where the
            // deterministically-converted @KafkaListener pollers were
            // re-rewritten by the LLM on retry and lost their CdiLookup
            // import).  Kept files are re-added verbatim from the overlaid
            // checkpoint content in the result assembly below.  No failing
            // paths (e.g. boot timeout produced no per-file findings) → no
            // narrowing → previous behaviour.
            Map<String, String> retryKept = new LinkedHashMap<>();
            if (input.previousArtifact() != null && !input.failingPaths().isEmpty()) {
                Set<String> failing = new LinkedHashSet<>(input.failingPaths());
                for (var it = toMigrate.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<String, String> e = it.next();
                    if (!matchesFailingPath(e.getKey(), failing)) {
                        retryKept.put(e.getKey(), e.getValue());
                        it.remove();
                    }
                }
                if (!retryKept.isEmpty()) {
                    log.info("[{}] retry scope narrowed: {} implicated file(s) to the LLM, "
                            + "{} kept verbatim from checkpoint", getName(), toMigrate.size(), retryKept.size());
                }
            }

            List<MigratedFile> migrated = migrateFiles(toMigrate, effectiveSystemPrompt,
                    perFileProvenance, isJakarta, blueprint);

            // Include unchanged versions of files excluded by the pruner
            List<MigratedFile> result = new ArrayList<>(migrated);
            // Deterministically-converted consumers (removed from the LLM set above).
            result.addAll(deterministicConsumers);
            // Retry-narrowing: healthy checkpoint files, kept verbatim (content is the
            // overlaid previous-attempt output, not the original source).
            for (Map.Entry<String, String> entry : retryKept.entrySet()) {
                result.add(unchanged(entry.getKey(), entry.getValue(),
                        "Retry checkpoint: kept from previous attempt (not implicated in validation failure)"));
            }
            for (Map.Entry<String, String> entry : allFiles.entrySet()) {
                if (deletedSourcePaths.contains(entry.getKey())) {
                    continue; // deliberately deleted (e.g. Pub/Sub TopicBootstrap) — never re-add
                }
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
            // Deterministically-converted consumer files are exempt: they
            // import CdiLookup which is generated by HybridScaffoldingGenerator
            // AFTER this pass; reverting them would restore the original EJB
            // source which can no longer compile once PubSubConfig.subscription()
            // is stripped by the anchor.
            Set<String> deterministicPaths = new java.util.HashSet<>();
            for (MigratedFile f : deterministicConsumers) {
                String p = f.newPath() != null ? f.newPath() : f.originalPath();
                if (p != null) deterministicPaths.add(p);
            }
            result = revertFilesWithUnresolvedImports(result, allFiles, deterministicPaths);

            // Post-revert hybrid fix: non-consumer files (e.g. JAX-RS resources)
            // that call pubsub.publish(PubSubConfig.topic(CONST), msg) still carry
            // the GCP pattern after revert — topic() was stripped by the anchor so
            // the original source no longer compiles.  Rewrites them deterministically
            // to kafkaTemplate.send(PubSubConfig.CONST, msg) via CDI injection of
            // the KafkaTemplate produced by SpringBeanBridge.
            if (jakartaMessagingTarget == JakartaMessagingTarget.SPRING_KAFKA_HYBRID) {
                result = hybridPublishRewriter.rewrite(result);
            }

            // Hybrid-only, deterministic, ADD-files (the one stage permitted to):
            // generate the 5 mandatory Spring Kafka bridge classes a genuine
            // Pub/Sub project has no source file to map from.  MUST run BEFORE
            // applyContractRepairs: the deterministic pollers reference the
            // generated CdiLookup, and validating an artifact that doesn't
            // contain it yet produces MISSING_IMPORT violations that send the
            // (correct) pollers to the repair LLM — which "fixed" them by
            // nulling the lookup (job d7b6d473: both CdiLookup-calling pollers
            // came back as `Store store = null;` with the save() deleted —
            // compiles, boots, and silently drops the business logic).
            // Generated paths are tracked and, together with the deterministic
            // transforms, are off-limits to the repairer below.
            Set<String> generatedPaths = new LinkedHashSet<>();
            if (jakartaMessagingTarget == JakartaMessagingTarget.SPRING_KAFKA_HYBRID) {
                Set<String> beforeGenerate = new LinkedHashSet<>();
                for (MigratedFile f : result) {
                    String p = f.newPath() != null ? f.newPath() : f.originalPath();
                    if (p != null) beforeGenerate.add(p);
                }
                result = hybridScaffoldingGenerator.generate(result, topicConstantNames);
                for (MigratedFile f : result) {
                    String p = f.newPath() != null ? f.newPath() : f.originalPath();
                    if (p != null && !beforeGenerate.contains(p)) generatedPaths.add(p);
                }
            }

            // Project Semantic Index — contract validation + minimal-patch
            // LLM repair loop.  The earlier guards revert broken files to
            // original; this one PATCHES them so the artifact can actually
            // reach DONE.  Catches interface drift, unknown method calls,
            // constructor arity mismatches, file/class rename, missing
            // overrides — everything the file-by-file LLM pass can't see
            // because it never holds two files at once.  Deterministic and
            // generated files are correct by construction — the repairer may
            // read them for context but must never rewrite them.
            Set<String> repairProtectedPaths = new LinkedHashSet<>(deterministicPaths);
            repairProtectedPaths.addAll(generatedPaths);
            result = applyContractRepairs(result, repairProtectedPaths);

            // Output gate — every Pub/Sub artifact that survived the
            // migration is a failure (the target is Kafka).  The repairer
            // uses the validator's pre-computed Kafka replacement strategy
            // per leak so the model has zero design freedom.  Runs AFTER
            // contract repair because we need stable signatures before we
            // can swap Google method chains for Kafka calls.
            result = applyPubSubLeakRepairs(result);

            // Re-stamp the anchored constants class after the ContractRepairer and
            // PubSubLeakRepairer have run.  Both repairers invoke the LLM to fix
            // cross-file inconsistencies; when the unconverted Pub/Sub pollers still
            // call PubSubConfig.topic()/subscription() the ContractRepairer sees
            // UNKNOWN_METHOD_CALL violations and asks the LLM to "fix" PubSubConfig —
            // which hallucinates @Configuration/@Bean additions and undoes the
            // constants-only rewrite.  Re-stamping here makes the anchor final
            // regardless of what the repairer did.
            if (anchoredConfig != null) {
                final String anchoredPath = anchoredConfig.originalPath();
                final MigratedFile finalAnchor = anchoredConfig;
                result = result.stream()
                        .map(f -> anchoredPath.equals(f.originalPath()) ? finalAnchor : f)
                        .collect(java.util.stream.Collectors.toList());
                log.info("[{}] hybrid: re-stamped anchored constants class '{}' after repair passes",
                        getName(), anchoredPath);
            }

            // Final guarantee, deterministic — reconcile pom.xml against
            // whatever Kafka classes the files above actually import, no
            // matter how pom.xml got here (LLM rewrite, structural-check
            // revert, or already-correct).  Runs last so it sees the truly
            // final Java content.
            result = applyPomDependencyReconciliation(result);

            // Hybrid-only, deterministic, auto-fix: CDI persistence classes
            // reached from a Spring @KafkaListener via CdiLookup carry no JTA
            // transaction unless converted to @Stateless EJBs (see
            // JAKARTA_EE_SPRING_KAFKA_PREFIX item 7). Runs last so it sees the
            // final file set, same as the pom reconciliation above it.
            // (The bridge scaffolding itself is generated earlier, BEFORE the
            // contract-repair pass — see the comment there.)
            if (jakartaMessagingTarget == JakartaMessagingTarget.SPRING_KAFKA_HYBRID) {
                result = cdiStatelessConverter.convert(result);
            }

            persistProvenance(perFileProvenance);

            long modifiedCount = result.stream()
                    .filter(f -> f.changeType() == FileChangeType.MODIFIED).count();
            String summary = "Migrated %d/%d file(s) for project '%s' (pruned %d file(s))"
                    .formatted(modifiedCount, result.size(), projectId, pruned.prunedFiles());
            return new MigrationArtifact(projectId, result, summary, jakartaMessagingTarget);
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
                                            boolean isJakarta, ProjectBlueprint blueprint) {
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

        // ── Cluster-coherent migration ───────────────────────────────────
        // Coupled files (an interface + its implementors) are rewritten in a
        // single LLM call so the shared contract stays consistent, instead of
        // diverging across independent per-file rewrites.  Best-effort: a
        // cluster that fails or returns malformed output falls back to the
        // per-file path below.  Cluster results join the result set; the
        // artifact-level contract/leak passes still run on the merged output.
        List<MigratedFile> clusterResults = new ArrayList<>();
        if (blueprint != null && !toMigrate.isEmpty()) {
            java.util.Set<String> migratable = new java.util.HashSet<>();
            for (Map.Entry<String, String> e : toMigrate) migratable.add(e.getKey());
            java.util.Set<String> handled = new java.util.HashSet<>();
            for (List<String> cluster : clusterPlanner.plan(blueprint)) {
                if (cluster.size() < 2 || !migratable.containsAll(cluster)
                        || cluster.stream().anyMatch(handled::contains)) {
                    continue;
                }
                List<MigratedFile> res = migrateCluster(cluster, sourceFiles, systemPrompt, isJakarta, blueprint);
                if (res != null) {
                    clusterResults.addAll(res);
                    handled.addAll(cluster);
                    log.info("[{}] migrated cluster of {} file(s) coherently: {}",
                            getName(), cluster.size(), cluster);
                }
            }
            toMigrate.removeIf(e -> handled.contains(e.getKey()));
        }

        if (toMigrate.isEmpty()) {
            List<MigratedFile> only = new ArrayList<>(straightThrough);
            only.addAll(clusterResults);
            return only;
        }

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
                        migrateOneFile(entry.getKey(), entry.getValue(), systemPrompt, perFileProvenance, isJakarta, blueprint)));
            }

            List<MigratedFile> result = new ArrayList<>(straightThrough);
            result.addAll(clusterResults);
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

    /** Appended to the system prompt for a cluster call — enforces one consistent contract. */
    private static final String CLUSTER_RULES = """

            MULTI-FILE CONTRACT RULES — these files share ONE contract and are migrated together:
            * The `=== FILE: <path> ===` lines are FILE PATHS, not packages.  Preserve each
              file's ORIGINAL `package` declaration EXACTLY (dotted, e.g. `package com.example.altrix.pubsub.tasks;`).
              NEVER derive the package from the path or use '/' in a package statement.
            * Treat the interface (or abstract base) as the single source of truth.  Every
              implementation MUST match it EXACTLY: identical method names, parameter types,
              parameter order, and return types.  If you change a signature, change it in the
              interface AND every implementation in the same way.
            * Keep shared type names and shared constant names identical across all files
              (if one file calls a constant ORDERS_CREATED, every file uses ORDERS_CREATED).
            * Every constructor call must match the migrated constructor's parameter list.
            * Output format: return EVERY input file, each one preceded by a line of the EXACT
              form `=== FILE: <path> ===` (same path you were given), followed by the complete
              file content.  No commentary before, between, or after the files.
            """;

    /**
     * Migrate a cluster of coupled files (interface + implementors) in a SINGLE
     * LLM call so their shared contract is consistent by construction.  Returns
     * one {@link MigratedFile} per input path, or {@code null} to signal the
     * caller should fall back to per-file migration (AI failure / malformed or
     * incomplete response).  Runs on the calling thread (clusters are few).
     */
    private List<MigratedFile> migrateCluster(List<String> paths, Map<String, String> sourceFiles,
                                              String systemPrompt, boolean isJakarta,
                                              ProjectBlueprint blueprint) {
        StringBuilder user = new StringBuilder();
        user.append("Migrate the following ").append(paths.size())
            .append(" RELATED files TOGETHER as one coherent unit. They share a contract")
            .append(" (an interface / abstract base and its implementors). Keep that contract")
            .append(" identical across every file.\n\n");
        for (String p : paths) {
            user.append("=== FILE: ").append(p).append(" ===\n")
                .append(sourceFiles.get(p)).append("\n\n");
        }
        try {
            String raw = aiPort.chat(systemPrompt + CLUSTER_RULES, user.toString());
            Map<String, String> migrated =
                    com.altrix.orchestrator.infrastructure.migration.MigrationClusterPlanner.parseResponse(raw);
            if (migrated.isEmpty()) {
                log.warn("[{}] cluster response had no parseable files — falling back to per-file", getName());
                return null;
            }
            List<MigratedFile> out = new ArrayList<>(paths.size());
            for (String p : paths) {
                String content = sourceFiles.get(p);
                String m = migrated.get(p);
                if (m == null || m.isBlank()) {
                    out.add(unchanged(p, content, "Cluster migration: file missing from response"));
                    continue;
                }
                m = stripLeadingProse(stripMarkdownFences(m), p);
                if (isPom(p) && pomSanitizer != null) {
                    m = pomSanitizer.stripHallucinatedDependencies(content, m);
                }
                if (looksTruncated(m, content)) {
                    out.add(unchanged(p, content, "Cluster migration: output truncated"));
                    continue;
                }
                FileChangeType changeType = m.equals(content) ? FileChangeType.UNCHANGED : FileChangeType.MODIFIED;
                out.add(MigratedFile.builder()
                        .originalPath(p).newPath(p).content(m)
                        .changeType(changeType)
                        .diffSummary("Migrated Pub/Sub → Kafka (cluster-coherent)")
                        .build());
            }
            return out;
        } catch (Exception e) {
            log.warn("[{}] cluster migration failed for {} ({}) — falling back to per-file",
                    getName(), paths, e.getMessage());
            return null;
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
                                        boolean isJakarta, ProjectBlueprint blueprint) {
        // #1 — pull the most-relevant doc chunks for this file BEFORE the
        // cache check so the prompt is RAG-augmented on every call.
        // Including chunk-content hashes in the cache key means a doc
        // update invalidates the cache automatically.
        List<DocumentChunk> ragChunks = retrieveDocs(content, isJakarta);
        String ragSection = buildRagSection(ragChunks);
        // Project-wide understanding for THIS file (role, neighbours,
        // detected features → Kafka targets).  Empty string when no
        // blueprint slice exists — the migrator then behaves exactly as
        // before.
        String blueprintSection = buildBlueprintSection(path, blueprint);
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
        String cacheKey = computeCacheKey(systemPrompt + ragSection + blueprintSection, path, content);
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
            String userMessage = "File: " + path + "\n\n" + content + ragSection + blueprintSection;
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

            // External-import hallucination guard: when the model emits a
            // .java that imports a class we know doesn't exist on the
            // target classpath (e.g. org.apache.kafka.clients.consumer.OffsetCommitResult,
            // which the model invents because Pub/Sub ack returns a result
            // and it assumes Kafka has the same), revert to the original.
            // The deny-list lives in MigrationConfig.source.deniedImports
            // and is user-extensible without code changes.
            if (path != null && path.toLowerCase().endsWith(".java")) {
                java.util.List<String> denied = migrationConfig != null && migrationConfig.source() != null
                        ? migrationConfig.source().deniedImports() : null;
                String hit = firstDeniedImport(migrated, denied);
                if (hit != null) {
                    log.warn("[{}] '{}' imports hallucinated class '{}' — keeping original",
                            getName(), path, hit);
                    return unchanged(path, content,
                            "Migration skipped — output imported non-existent class: " + hit);
                }
                // File/class name mismatch guard.  Java enforces that a
                // file with a public type matches the basename; the model
                // sometimes renames the type inside a file ("IGoogleErrorConverter"
                // → "IKafkaErrorConverter") without realising the file
                // can't be renamed in this isolated call.  The compiler
                // reports "class X is public, should be declared in a
                // file named X.java" and gives up on every dependent
                // file.  Reverting keeps the original name in the file.
                String wrongPublicType = publicTypeMismatchingFilename(path, migrated);
                if (wrongPublicType != null) {
                    log.warn("[{}] '{}' declares public type '{}' but file basename differs — keeping original",
                            getName(), path, wrongPublicType);
                    return unchanged(path, content,
                            "Migration skipped — public type '" + wrongPublicType
                            + "' does not match filename");
                }
                // Used-but-not-imported guard.  When the model swaps a
                // Pub/Sub type for KafkaProducer / KafkaConsumer as a
                // field / parameter / return type but forgets the
                // corresponding import, the compile dies with "cannot
                // find symbol class KafkaProducer".  We check only types
                // we know the migrator introduces (kafka-clients API
                // surface, common java.time types it routinely needs)
                // — false positives revert a file unnecessarily, which
                // is worse than the symptom.
                String missing = firstUsedButNotImported(migrated);
                if (missing != null) {
                    log.warn("[{}] '{}' uses type '{}' without importing it — keeping original",
                            getName(), path, missing);
                    return unchanged(path, content,
                            "Migration skipped — used type without import: " + missing);
                }
                // Lombok experimental-feature guard.  `onConstructor_`
                // requires a delombok-aware compiler config (an extra
                // `-Xplugin:Lombok` arg or `lombok.addLombokGeneratedAnnotation`
                // in lombok.config) that almost no project enables.
                // The model adds it when it sees a CDI / Spring
                // constructor — the result is "cannot find symbol
                // method onConstructor_()" on every annotation use.
                // Stripping it preserves the rest of the migration.
                if (migrated.contains("onConstructor_")) {
                    String stripped = stripLombokOnConstructor(migrated);
                    if (!stripped.equals(migrated)) {
                        log.info("[{}] stripped Lombok @AllArgsConstructor(onConstructor_=…) from '{}'",
                                getName(), path);
                        migrated = stripped;
                    }
                }
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
    /**
     * Project-wide contract validation + minimal-patch repair loop.
     *
     * <p>Runs after every other per-file guard so the input to the
     * validator is as clean as the rest of the pipeline can make it.
     * The repairer mutates a working copy and returns it; we re-emit
     * {@link MigratedFile} entries preserving the original ordering and
     * marking only those whose content actually changed as MODIFIED.
     *
     * <p>No-op when the validator is disabled (null) so the agent is
     * usable in tests without wiring the new components.
     */
    /**
     * @param protectedPaths deterministic/generated files (hybrid consumer
     *        transforms, anchored config, bridge scaffolding) that are correct
     *        by construction.  The repairer sees them as read-only context;
     *        any rewrite it proposes for one of them is discarded — the repair
     *        LLM has "fixed" them before by nulling the {@code CdiLookup}
     *        bridge call (job d7b6d473), which compiles but silently drops the
     *        business logic.
     */
    private List<MigratedFile> applyContractRepairs(List<MigratedFile> files, Set<String> protectedPaths) {
        if (contractValidator == null || contractRepairer == null || files.isEmpty()) return files;

        Map<String, String> working = new LinkedHashMap<>();
        for (MigratedFile f : files) {
            String path = f.newPath() != null ? f.newPath() : f.originalPath();
            if (path != null && f.content() != null) working.put(path, f.content());
        }

        List<com.altrix.orchestrator.domain.model.contract.ContractViolation> before =
                contractValidator.validate(working);
        if (before.isEmpty()) {
            log.info("[{}] contract check: clean — {} file(s)", getName(), working.size());
            return files;
        }
        log.info("[{}] contract check: {} violation(s) across {} file(s); attempting repair",
                getName(), before.size(),
                contractValidator.groupByFile(before).size());

        Map<String, String> repaired = contractRepairer.repair(working);

        List<com.altrix.orchestrator.domain.model.contract.ContractViolation> after =
                contractValidator.validate(repaired);
        if (after.isEmpty()) {
            log.info("[{}] contract repair: artifact is now clean ({} → 0 violations)",
                    getName(), before.size());
        } else {
            log.warn("[{}] contract repair: {} violation(s) remain after repair pass — sandbox will surface them",
                    getName(), after.size());
        }

        List<MigratedFile> rewired = new ArrayList<>(files.size());
        for (MigratedFile f : files) {
            String path = f.newPath() != null ? f.newPath() : f.originalPath();
            String newContent = path != null ? repaired.get(path) : null;
            if (newContent == null || newContent.equals(f.content())) {
                rewired.add(f);
                continue;
            }
            if (path != null && protectedPaths.contains(path)) {
                log.warn("[{}] contract repair: blocked rewrite of deterministic/generated file '{}' — "
                        + "kept the deterministic content", getName(), path);
                rewired.add(f);
                continue;
            }
            rewired.add(MigratedFile.builder()
                    .originalPath(f.originalPath())
                    .newPath(f.newPath())
                    .content(newContent)
                    .changeType(FileChangeType.MODIFIED)
                    .diffSummary("Contract-repair patch applied")
                    .build());
        }
        return rewired;
    }

    /**
     * Output-gate scan for surviving GCP Pub/Sub artifacts + minimal-patch
     * repair pass.  Runs after {@link #applyContractRepairs(List)} so
     * cross-file structure is stable.  Same accounting pattern: only
     * touched files are re-emitted as MODIFIED; everything else passes
     * through.  No-op when the validator / repairer aren't wired (tests).
     */
    private List<MigratedFile> applyPubSubLeakRepairs(List<MigratedFile> files) {
        if (pubSubLeakValidator == null || pubSubLeakRepairer == null || files.isEmpty()) return files;

        Map<String, String> working = new LinkedHashMap<>();
        for (MigratedFile f : files) {
            String path = f.newPath() != null ? f.newPath() : f.originalPath();
            if (path != null && f.content() != null) working.put(path, f.content());
        }

        List<com.altrix.orchestrator.domain.model.leak.PubSubLeakViolation> before =
                pubSubLeakValidator.validate(working);
        if (before.isEmpty()) {
            log.info("[{}] leak check: clean — no Pub/Sub residue", getName());
            return files;
        }
        log.info("[{}] leak check: {} Pub/Sub leak(s) across {} file(s); attempting repair",
                getName(), before.size(),
                pubSubLeakValidator.groupByFile(before).size());

        Map<String, String> repaired = pubSubLeakRepairer.repair(working);

        List<com.altrix.orchestrator.domain.model.leak.PubSubLeakViolation> after =
                pubSubLeakValidator.validate(repaired);
        if (after.isEmpty()) {
            log.info("[{}] leak repair: artifact is now Pub/Sub-free ({} → 0 leaks)",
                    getName(), before.size());
        } else {
            log.warn("[{}] leak repair: {} leak(s) remain after repair pass — sandbox will surface them",
                    getName(), after.size());
        }

        List<MigratedFile> rewired = new ArrayList<>(files.size());
        for (MigratedFile f : files) {
            String path = f.newPath() != null ? f.newPath() : f.originalPath();
            String newContent = path != null ? repaired.get(path) : null;
            if (newContent == null || newContent.equals(f.content())) {
                rewired.add(f);
                continue;
            }
            rewired.add(MigratedFile.builder()
                    .originalPath(f.originalPath())
                    .newPath(f.newPath())
                    .content(newContent)
                    .changeType(FileChangeType.MODIFIED)
                    .diffSummary("Pub/Sub leak repaired (Kafka replacement applied)")
                    .build());
        }
        return rewired;
    }

    /**
     * Deterministic final guarantee for pom.xml — see
     * {@link com.altrix.orchestrator.infrastructure.migration.PomDependencyReconciler}
     * for the full rationale.  No-op when there's no pom.xml in the
     * artifact, or the reconciler isn't wired (tests).
     */
    private List<MigratedFile> applyPomDependencyReconciliation(List<MigratedFile> files) {
        if (pomDependencyReconciler == null || files.isEmpty()) return files;

        MigratedFile pom = null;
        Map<String, String> javaFiles = new LinkedHashMap<>();
        for (MigratedFile f : files) {
            String path = f.newPath() != null ? f.newPath() : f.originalPath();
            if (isPom(path)) {
                pom = f;
            } else if (path != null && path.toLowerCase().endsWith(".java") && f.content() != null) {
                javaFiles.put(path, f.content());
            }
        }
        if (pom == null || pom.content() == null) return files;

        String reconciled = pomDependencyReconciler.reconcile(pom.content(), javaFiles);
        if (reconciled.equals(pom.content())) return files;

        List<MigratedFile> result = new ArrayList<>(files.size());
        for (MigratedFile f : files) {
            if (f == pom) {
                result.add(MigratedFile.builder()
                        .originalPath(f.originalPath())
                        .newPath(f.newPath())
                        .content(reconciled)
                        .changeType(FileChangeType.MODIFIED)
                        .diffSummary("Dependencies reconciled deterministically (Pub/Sub → Kafka)")
                        .build());
            } else {
                result.add(f);
            }
        }
        return result;
    }

    private List<MigratedFile> revertFilesWithUnresolvedImports(List<MigratedFile> migratedFiles,
                                                                Map<String, String> allFiles,
                                                                Set<String> exemptPaths) {
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
            // Deterministically-converted files (consumers, anchored constants) are exempt:
            // they may import CdiLookup or other scaffold classes not yet generated.
            if (exemptPaths.contains(path)) {
                log.debug("[{}] revert skipped for deterministic file '{}' (unresolved: {})",
                        getName(), path, bad);
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

    /**
     * Resolves the {@link ProjectBlueprint} for the current workflow
     * session, or null when none is available.  MUST be called on the
     * thread that holds the {@link SandboxContext} sessionId (the migrator's
     * calling thread) — the per-file workers run on a pool that can't see
     * the ThreadLocal, so {@link #execute} resolves once here and passes the
     * result down.  Null-safe + never throws: a missing context / row /
     * port just means file-by-file fallback.
     */
    private ProjectBlueprint resolveBlueprint() {
        if (projectBlueprintPort == null) return null;
        String sessionId = SandboxContext.currentSessionId();
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            WorkflowSessionId id = new WorkflowSessionId(UUID.fromString(sessionId));
            ProjectBlueprint bp = projectBlueprintPort.findForSession(id).orElse(null);
            if (bp != null) {
                log.info("[{}] project blueprint loaded for session '{}' — {} file slice(s)",
                        getName(), sessionId, bp.files().size());
            }
            return bp;
        } catch (Exception e) {
            log.debug("[{}] no project blueprint for session '{}' ({}): {}",
                    getName(), sessionId, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * Builds the "PROJECT MAP (this file)" prompt section from the
     * {@code blueprint} slice for {@code path}, or "" when no blueprint /
     * slice is available.  Gives the per-file LLM call the project-wide
     * understanding the ProjectMapper phase computed — the file's role, its
     * type-graph neighbours, and each detected Pub/Sub feature mapped to its
     * concrete Kafka target.
     *
     * <p>Pure + null-safe: takes the already-resolved blueprint (so it's
     * safe to call from a worker thread) and returns "" on any miss, so the
     * migrator behaves exactly as it did before the blueprint existed.
     * Never throws.
     */
    private String buildBlueprintSection(String path, ProjectBlueprint blueprint) {
        if (blueprint == null || path == null) return "";
        try {
            Optional<BlueprintFile> sliceOpt = blueprint.fileSlice(path);
            if (sliceOpt.isEmpty()) return "";
            var slice = sliceOpt.get();

            StringBuilder sb = new StringBuilder(
                    "\n\n--- PROJECT MAP (this file — derived from a project-wide semantic analysis) ---\n");
            if (slice.role() != null && !slice.role().isBlank()) {
                sb.append("Role: ").append(slice.role()).append('\n');
            }
            var rel = slice.relationships();
            if (rel != null) {
                if (rel.extendsType() != null && !rel.extendsType().isBlank()) {
                    sb.append("Extends: ").append(rel.extendsType()).append('\n');
                }
                if (!rel.implementsTypes().isEmpty()) {
                    sb.append("Implements: ").append(String.join(", ", rel.implementsTypes())).append('\n');
                }
                if (!rel.dependsOn().isEmpty()) {
                    sb.append("Depends on (project types — keep these names stable): ")
                      .append(String.join(", ", rel.dependsOn())).append('\n');
                }
                if (!rel.calledBy().isEmpty()) {
                    sb.append("Called by (renaming this class breaks them): ")
                      .append(String.join(", ", rel.calledBy())).append('\n');
                }
            }
            if (!slice.features().isEmpty()) {
                sb.append("Detected Pub/Sub features → Kafka target:\n");
                for (var f : slice.features()) {
                    sb.append("  - ").append(f.id());
                    if (f.description() != null && !f.description().isBlank()) {
                        sb.append(" (").append(f.description()).append(')');
                    }
                    if (f.kafkaTarget() != null && !f.kafkaTarget().isBlank()) {
                        sb.append(" → ").append(f.kafkaTarget());
                    } else {
                        sb.append(" → no clean Kafka equivalent; leave in place with a // TODO altrix: note");
                    }
                    sb.append('\n');
                }
            }
            if (slice.migrationNotes() != null && !slice.migrationNotes().isBlank()) {
                sb.append("Migration notes: ").append(slice.migrationNotes()).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("[{}] no blueprint slice for '{}' ({}): {}",
                    getName(), path, e.getClass().getSimpleName(), e.getMessage());
            return "";
        }
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
     * Retry-scope matching: does {@code artifactPath} correspond to one of the
     * failing paths reported by the previous validation?  Paths on both sides
     * are artifact-relative ({@code src/main/java/...} — the sandbox runners
     * strip their {@code /workspace/} prefix), but suffix-tolerant matching
     * guards against a runner that reports a differently-rooted variant.  The
     * {@code /}-boundary check prevents {@code Poller.java} from matching
     * {@code OtherPoller.java}.
     */
    private static boolean matchesFailingPath(String artifactPath, Set<String> failingPaths) {
        if (failingPaths.contains(artifactPath)) return true;
        for (String f : failingPaths) {
            if (artifactPath.endsWith("/" + f) || f.endsWith("/" + artifactPath)) return true;
        }
        return false;
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
            // A real parse catches mid-method truncation AND mid-stream token
            // corruption that the length-ratio looksTruncated() guard misses
            // (cutting the last method or two off a large file is still >30%
            // of the original size, and a garbled field declaration doesn't
            // change the file's length at all).
            return hasMarkdownContamination(migrated)
                    || com.altrix.orchestrator.infrastructure.migration.JavaOutputGuard
                            .isMalformed(migrated);
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

    /**
     * Scans the migrated Java source for {@code import X;} lines where
     * {@code X} matches the configured deny-list and returns the first
     * matching FQN, or {@code null} if none.  Static imports are
     * checked too — the model occasionally hallucinates a constant on
     * a non-existent type.
     *
     * <p>A deny-list entry ending in {@code ".*"} matches any class
     * under that package.  E.g.
     * {@code org.apache.kafka.common.security.auth.permission.*} blocks
     * both {@code AdminPermission} and {@code Permission} (the model
     * invents a whole bogus package mapping Pub/Sub IAM to Kafka).
     */
    static String firstDeniedImport(String javaSource, java.util.List<String> deniedImports) {
        if (javaSource == null || deniedImports == null || deniedImports.isEmpty()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;",
                java.util.regex.Pattern.MULTILINE).matcher(javaSource);
        while (m.find()) {
            String fqn = m.group(1);
            for (String denied : deniedImports) {
                if (denied == null || denied.isBlank()) continue;
                if (denied.endsWith(".*")) {
                    String prefix = denied.substring(0, denied.length() - 2);
                    if (fqn.startsWith(prefix + ".")) return fqn;
                } else if (fqn.equals(denied)) {
                    return fqn;
                }
            }
        }
        return null;
    }

    /** Matches the first public {@code class|interface|enum|record} declaration. */
    private static final java.util.regex.Pattern PUBLIC_TYPE_DECL = java.util.regex.Pattern.compile(
            "(?:^|\\s)public\\s+(?:final\\s+|abstract\\s+|static\\s+|sealed\\s+|non-sealed\\s+)*"
            + "(?:class|interface|enum|record|@interface)\\s+(\\w+)",
            java.util.regex.Pattern.MULTILINE);

    /**
     * If the file declares a {@code public} type whose name does NOT match
     * the file's basename, returns that mismatched type name; otherwise
     * {@code null}.  The Java compiler requires the names to match — when
     * they don't every dependent file also fails ("bad source file: …").
     *
     * <p>Non-public top-level types are ignored (they're legal under any
     * filename).  Files without any public type are ignored too — they
     * compile fine.
     */
    static String publicTypeMismatchingFilename(String path, String javaSource) {
        if (path == null || javaSource == null) return null;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String fileName = path.substring(slash + 1);
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) return null;
        String expected = fileName.substring(0, dot);

        java.util.regex.Matcher m = PUBLIC_TYPE_DECL.matcher(javaSource);
        if (!m.find()) return null;
        String declared = m.group(1);
        return declared.equals(expected) ? null : declared;
    }

    /**
     * Kafka client / java.time types the migrator commonly introduces.
     * When one of these appears as a type token in the source (declaration,
     * generic argument, {@code new X(…)}, etc.) but no matching {@code import}
     * brings it in, the file won't compile.  Kept short and conservative —
     * each entry is a type the migrator EXPLICITLY writes, not something a
     * hand-authored Java file might use through some other API.
     */
    private static final java.util.List<String[]> REQUIRED_IMPORTS_FOR_TYPES = java.util.List.of(
            // {simple name, expected import FQN}
            new String[]{"KafkaProducer",      "org.apache.kafka.clients.producer.KafkaProducer"},
            new String[]{"KafkaConsumer",      "org.apache.kafka.clients.consumer.KafkaConsumer"},
            new String[]{"ProducerRecord",     "org.apache.kafka.clients.producer.ProducerRecord"},
            new String[]{"ConsumerRecord",     "org.apache.kafka.clients.consumer.ConsumerRecord"},
            new String[]{"ConsumerRecords",    "org.apache.kafka.clients.consumer.ConsumerRecords"},
            new String[]{"OffsetAndMetadata",  "org.apache.kafka.clients.consumer.OffsetAndMetadata"},
            new String[]{"OffsetCommitCallback","org.apache.kafka.clients.consumer.OffsetCommitCallback"},
            new String[]{"TopicPartition",     "org.apache.kafka.common.TopicPartition"},
            new String[]{"Duration",           "java.time.Duration"});

    /**
     * If the source references one of {@link #REQUIRED_IMPORTS_FOR_TYPES}
     * as a type token but doesn't import it (and the import wasn't covered
     * by a {@code .*} wildcard import or a same-package declaration),
     * returns the missing simple name.
     *
     * <p>"Reference as a type token" is approximated with word-boundary
     * matching — good enough for the migrator's output, which writes
     * Java in conventional style.  False positives here only revert a
     * file to its original, never make a broken file worse.
     */
    static String firstUsedButNotImported(String javaSource) {
        if (javaSource == null || javaSource.isEmpty()) return null;
        for (String[] pair : REQUIRED_IMPORTS_FOR_TYPES) {
            String simple = pair[0];
            String fqn    = pair[1];
            // Word-boundary match: type name surrounded by non-identifier
            // characters at least once.  Skip if no usage.
            if (!java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(simple) + "\\b")
                    .matcher(javaSource).find()) {
                continue;
            }
            // Imported explicitly?
            if (javaSource.contains("import " + fqn + ";")
                    || javaSource.contains("import static " + fqn + ".")) {
                continue;
            }
            // Imported via wildcard from the same package?
            String pkg = fqn.substring(0, fqn.lastIndexOf('.'));
            if (javaSource.contains("import " + pkg + ".*;")) continue;
            // Declared in the same compilation unit (a same-named inner
            // type or sibling top-level type)?
            if (java.util.regex.Pattern.compile(
                    "\\b(?:class|interface|enum|record)\\s+" + java.util.regex.Pattern.quote(simple) + "\\b")
                    .matcher(javaSource).find()) {
                continue;
            }
            return simple;
        }
        return null;
    }

    /**
     * Strips Lombok's experimental {@code onConstructor_ = …} parameter
     * from {@code @AllArgsConstructor} / {@code @RequiredArgsConstructor}
     * / {@code @NoArgsConstructor} annotations.  The feature needs an
     * extra compile-time annotation processor most projects don't enable,
     * so leaving it in produces {@code cannot find symbol method
     * onConstructor_()} on every annotation use.
     *
     * <p>Preserves any other annotation parameters around it.  When the
     * stripped parameter was the only one, the empty {@code ()} is
     * removed too.
     */
    static String stripLombokOnConstructor(String javaSource) {
        if (javaSource == null || !javaSource.contains("onConstructor_")) return javaSource;
        // Match `onConstructor_` followed by `=`, optional whitespace, and
        // either an `@…(…)` annotation reference or `@…`.  The body of the
        // annotation reference may contain balanced parens — kept simple
        // (one nesting level) which covers @Inject / @Autowired / @Inject(…).
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\\s*,?\\s*onConstructor_\\s*=\\s*@\\w+(?:\\([^()]*\\))?\\s*,?",
                java.util.regex.Pattern.MULTILINE);
        String stripped = p.matcher(javaSource).replaceAll("");
        // Clean any now-empty annotation argument lists like @AllArgsConstructor().
        stripped = stripped.replaceAll(
                "(@(?:AllArgsConstructor|RequiredArgsConstructor|NoArgsConstructor))\\(\\s*\\)",
                "$1");
        return stripped;
    }
}
