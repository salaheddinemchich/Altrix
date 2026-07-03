package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.common.domain.model.MigrationPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationPlanConverterTest {

    private final MigrationPlanConverter converter = new MigrationPlanConverter();

    // ── null / blank guards ───────────────────────────────────────────────────

    @Test
    void convertToDatabaseColumn_null_returnsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void convertToEntityAttribute_null_returnsNull() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void convertToEntityAttribute_blank_returnsNull() {
        assertThat(converter.convertToEntityAttribute("   ")).isNull();
    }

    // ── round-trip ────────────────────────────────────────────────────────────

    @Test
    void roundTrip_preservesAllFields() {
        MigrationPlan plan = new MigrationPlan(
                "proj-1", "uploads/proj-1.zip", "Spring Boot 3 + Kafka",
                List.of("step-a", "step-b"), "MEDIUM", "2 days", "two steps", List.of(), null);

        String json = converter.convertToDatabaseColumn(plan);
        MigrationPlan result = converter.convertToEntityAttribute(json);

        assertThat(result).isEqualTo(plan);
    }

    @Test
    void roundTrip_emptyPlan_preserved() {
        MigrationPlan empty = MigrationPlan.empty("proj-x");
        assertThat(converter.convertToEntityAttribute(
                converter.convertToDatabaseColumn(empty))).isEqualTo(empty);
    }

    // ── schema evolution ──────────────────────────────────────────────────────

    @Test
    void convertToEntityAttribute_unknownFields_ignored() {
        String json = "{\"projectId\":\"p\",\"steps\":[],\"summary\":\"\",\"futureField\":\"value\"}";
        MigrationPlan result = converter.convertToEntityAttribute(json);
        assertThat(result.projectId()).isEqualTo("p");
    }

    @Test
    void convertToEntityAttribute_missingNewFields_defaultsToEmpty() {
        // Old 3-field rows from before issue #5
        String oldJson = "{\"projectId\":\"p\",\"steps\":[\"s1\"],\"summary\":\"old row\"}";
        MigrationPlan result = converter.convertToEntityAttribute(oldJson);
        assertThat(result.projectId()).isEqualTo("p");
        assertThat(result.storageKey()).isEmpty();
        assertThat(result.targetStack()).isEmpty();
        assertThat(result.riskLevel()).isEmpty();
    }

    // ── serialised JSON is valid ──────────────────────────────────────────────

    @Test
    void convertToDatabaseColumn_producesValidJson() {
        MigrationPlan plan = new MigrationPlan(
                "proj-1", "", "", List.of("s1"), "", "", "one step", List.of(), null);
        String json = converter.convertToDatabaseColumn(plan);
        assertThat(json).contains("proj-1").contains("s1").contains("one step");
    }

    // ── malformed JSON throws ─────────────────────────────────────────────────

    @Test
    void convertToEntityAttribute_malformedJson_throws() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("{broken"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deserialise");
    }
}
