package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.common.domain.model.MigrationPlan;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Hibernate {@link AttributeConverter} that serialises {@link MigrationPlan} to/from
 * a JSONB column on {@code workflow_sessions} (#57).
 *
 * <p>{@code @Converter(autoApply = true)} means no per-field annotation is needed;
 * any {@code MigrationPlan}-typed JPA column is handled automatically.
 *
 * <p>{@link JsonIgnoreProperties} on the mixin ensures schema evolution
 * (adding new fields) never breaks existing rows.
 */
@Converter(autoApply = true)
public class MigrationPlanConverter implements AttributeConverter<MigrationPlan, String> {

    @JsonIgnoreProperties(ignoreUnknown = true)
    private abstract static class MigrationPlanMixin {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .addMixIn(MigrationPlan.class, MigrationPlanMixin.class);

    @Override
    public String convertToDatabaseColumn(MigrationPlan plan) {
        if (plan == null) return null;
        try {
            return MAPPER.writeValueAsString(plan);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialise MigrationPlan to JSON", e);
        }
    }

    @Override
    public MigrationPlan convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, MigrationPlan.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not deserialise MigrationPlan from JSON", e);
        }
    }
}
