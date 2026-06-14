package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.blueprint.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip tests for the Jackson binding behind the JSONB column.
 *
 * <p>The aggregate uses records throughout, including nested generic
 * collections + an enum + an {@link Instant}.  These are exactly the
 * things that go wrong in Jackson defaults — covering them here keeps
 * a JPA save/load from blowing up at runtime.
 */
class ProjectBlueprintJsonConverterTest {

    private final ProjectBlueprintJsonConverter converter = new ProjectBlueprintJsonConverter();

    @Test
    void nullRoundTrip() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("")).isNull();
        assertThat(converter.convertToEntityAttribute("   ")).isNull();
    }

    @Test
    void minimalBlueprintRoundTrip() {
        ProjectBlueprint original = new ProjectBlueprint(
                "proj-1", "11111111-1111-1111-1111-111111111111", Instant.parse("2026-01-15T10:00:00Z"),
                1, DetectedStack.unknown(), List.of(), List.of(),
                SemanticGraph.empty(), List.of(), List.of(), List.of());

        String json = converter.convertToDatabaseColumn(original);
        assertThat(json).contains("\"projectId\":\"proj-1\"");
        // Instant must be ISO-8601 (Jackson + JavaTimeModule), not epoch millis.
        assertThat(json).contains("2026-01-15T10:00:00Z");

        ProjectBlueprint roundTripped = converter.convertToEntityAttribute(json);
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void richBlueprintRoundTrip() {
        // Build a blueprint exercising every record + the graph helpers.
        ClassNode pubsubServiceImpl = new ClassNode(
                "com.x.PubsubServiceImpl", "PubsubServiceImpl", "com.x",
                BlueprintFile.Kind.CLASS,
                "src/main/java/com/x/PubsubServiceImpl.java",
                true,
                List.of("public"),
                List.of("Vetoed", "AllArgsConstructor"));
        MethodNode publish = new MethodNode(
                "com.x.PubsubServiceImpl", "publish",
                List.of("com.x.PubsubTopic", "com.x.AltrixPubsubMessage"),
                "void", List.of("public", "Override"));
        InheritanceEdge impls = new InheritanceEdge(
                "com.x.PubsubServiceImpl", "com.x.PubsubService", InheritanceEdge.Kind.IMPLEMENTS);
        CallEdge call = new CallEdge(
                "com.x.OrderService", 42, "com.x.PubsubServiceImpl#publish/2", true);
        ImportEdge imp = new ImportEdge(
                "com.x.PubsubServiceImpl", "com.google.api.services.pubsub.Pubsub", false);

        BlueprintFeature feature = new BlueprintFeature(
                "pubsub.publish-batch",
                "Batches PubSub publish requests",
                new BlueprintFeature.Evidence("publishPubsubMessages", 80, "publishPubsubMessages(topic, msgs)"),
                "KafkaProducer.send + RecordHeaders",
                List.of("kafka/producers", "kafka/topic-config"));

        BlueprintFile fileSlice = new BlueprintFile(
                "src/main/java/com/x/PubsubServiceImpl.java",
                "PubsubServiceImpl",
                BlueprintFile.Kind.CLASS,
                "com.x",
                "MessagingAdapter",
                new BlueprintRelationships(
                        null,
                        List.of("com.x.PubsubService"),
                        List.of("com.x.RetryHandler"),
                        List.of("OrderService", "PaymentService")),
                List.of(feature),
                "Replace Pubsub client with KafkaProducer; preserve @Vetoed");

        DocReference doc = new DocReference(
                "kafka/producers", "https://kafka.apache.org/documentation/#producerapi",
                1700000000000L, DocReference.Source.MCP, 6);

        ProjectBlueprint original = new ProjectBlueprint(
                "proj-1", "22222222-2222-2222-2222-222222222222",
                Instant.parse("2026-02-20T08:30:00Z"), 1,
                new DetectedStack("Java", "17", "Jakarta EE 10", "Maven", "Payara Micro"),
                List.of(new DetectedIntegration(
                        "GCP Pub/Sub REST v1", "messaging",
                        List.of("src/main/java/com/x/PubsubServiceImpl.java"))),
                List.of(fileSlice),
                new SemanticGraph(
                        List.of(pubsubServiceImpl),
                        List.of(publish),
                        List.of(impls),
                        List.of(call),
                        List.of(imp)),
                List.of(doc),
                List.of("com.x.AltrixPubsubMessage", "com.x.PubsubServiceImpl"),
                List.of("PubsubTopicTestIAMPermissionsTask has no clean Kafka equivalent"));

        String json = converter.convertToDatabaseColumn(original);
        assertThat(json).contains("\"id\":\"pubsub.publish-batch\"");
        assertThat(json).contains("\"kind\":\"IMPLEMENTS\"");
        assertThat(json).contains("\"fetchedVia\":\"MCP\"");

        ProjectBlueprint roundTripped = converter.convertToEntityAttribute(json);
        assertThat(roundTripped).isEqualTo(original);

        // Spot-check that helpers still work after deserialisation.
        assertThat(roundTripped.semanticGraph().callersOf("com.x.PubsubServiceImpl"))
                .containsExactly("OrderService");
        assertThat(roundTripped.semanticGraph().importersOf("com.google.api.services.pubsub.Pubsub"))
                .containsExactly("com.x.PubsubServiceImpl");
        assertThat(roundTripped.fileSlice("src/main/java/com/x/PubsubServiceImpl.java"))
                .isPresent();
        assertThat(roundTripped.migrationRelevantFileCount()).isEqualTo(1L);
    }
}
