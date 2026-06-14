package com.altrix.orchestrator.domain.model.migration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationDecisionRegistryTest {

    @Test
    void emptyRegistryHasNoDecisions() {
        var r = MigrationDecisionRegistry.empty("s1");
        assertThat(r.isEmpty()).isTrue();
        assertThat(r.decisions()).isEmpty();
    }

    @Test
    void rejectsBlankSessionId() {
        assertThatThrownBy(() -> new MigrationDecisionRegistry(" ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withDecisionReturnsNewInstanceLeavingOriginalUntouched() {
        var original = MigrationDecisionRegistry.empty("s1");
        var next = original.withDecision(
                MigrationDecision.replaceType("Pubsub", "KafkaProducer", "migrate client"));

        assertThat(original.isEmpty()).isTrue();          // immutable
        assertThat(next.decisions()).hasSize(1);
        assertThat(next.sessionId()).isEqualTo("s1");
    }

    @Test
    void typeReplacementLookupFindsLatest() {
        var r = MigrationDecisionRegistry.empty("s1")
                .withDecision(MigrationDecision.replaceType(
                        "com.google.api.services.pubsub.Pubsub",
                        "KafkaProducer", "first"))
                // A later, overriding decision for the SAME type.
                .withDecision(MigrationDecision.replaceType(
                        "com.google.api.services.pubsub.Pubsub",
                        "KafkaProducer+KafkaConsumer", "refined"));

        assertThat(r.typeReplacement("com.google.api.services.pubsub.Pubsub"))
                .contains("KafkaProducer+KafkaConsumer");   // last write wins
        assertThat(r.typeReplacement("not.recorded.Type")).isEmpty();
    }

    @Test
    void classRenameAndDependencyReplacementLookups() {
        var r = MigrationDecisionRegistry.empty("s1")
                .withDecision(MigrationDecision.renameClass(
                        "com.example.PubsubServiceImpl", "com.example.PubsubServiceImpl", "kept"))
                .withDecision(MigrationDecision.replaceDependency(
                        "com.google.apis:google-api-services-pubsub",
                        "org.apache.kafka:kafka-clients", "swap"));

        assertThat(r.classRename("com.example.PubsubServiceImpl"))
                .contains("com.example.PubsubServiceImpl");
        assertThat(r.dependencyReplacement("com.google.apis:google-api-services-pubsub"))
                .contains("org.apache.kafka:kafka-clients");
    }

    @Test
    void hasDecisionReportsPresence() {
        var r = MigrationDecisionRegistry.empty("s1")
                .withDecision(MigrationDecision.replaceApi(
                        "acknowledge", "commitSync", "PubsubServiceImpl", "ack→commit"));

        assertThat(r.hasDecision(MigrationDecision.Kind.REPLACE_API, "acknowledge")).isTrue();
        assertThat(r.hasDecision(MigrationDecision.Kind.REPLACE_API, "pull")).isFalse();
        assertThat(r.hasDecision(MigrationDecision.Kind.RENAME_CLASS, "acknowledge")).isFalse();
    }

    @Test
    void withDecisionsAppendsBatch() {
        var r = MigrationDecisionRegistry.empty("s1").withDecisions(java.util.List.of(
                MigrationDecision.replaceType("A", "B", "x"),
                MigrationDecision.replaceType("C", "D", "y")));
        assertThat(r.decisions()).hasSize(2);
        // null/empty batch is a no-op returning the same instance.
        assertThat(r.withDecisions(java.util.List.of())).isSameAs(r);
        assertThat(r.withDecisions(null)).isSameAs(r);
    }

    @Test
    void decisionFactoriesSetKindCorrectly() {
        assertThat(MigrationDecision.renameClass("a", "b", null).kind())
                .isEqualTo(MigrationDecision.Kind.RENAME_CLASS);
        assertThat(MigrationDecision.replaceType("a", "b", null).kind())
                .isEqualTo(MigrationDecision.Kind.REPLACE_TYPE);
        assertThat(MigrationDecision.replaceDependency("a", "b", null).kind())
                .isEqualTo(MigrationDecision.Kind.REPLACE_DEPENDENCY);
        assertThat(MigrationDecision.replaceApi("a", "b", "s", null).kind())
                .isEqualTo(MigrationDecision.Kind.REPLACE_API);
    }

    @Test
    void decisionNormalisesNullToAndDecidedAt() {
        var d = new MigrationDecision(
                MigrationDecision.Kind.REPLACE_TYPE, "from", null, null, null, null);
        assertThat(d.to()).isEmpty();
        assertThat(d.decidedAt()).isNotNull();
    }

    @Test
    void decisionRejectsBlankFrom() {
        assertThatThrownBy(() -> new MigrationDecision(
                MigrationDecision.Kind.REPLACE_TYPE, " ", "to", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
