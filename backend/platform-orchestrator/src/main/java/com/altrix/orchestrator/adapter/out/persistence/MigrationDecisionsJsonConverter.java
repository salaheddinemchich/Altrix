package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.migration.MigrationDecision;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.AttributeConverter;

import java.util.List;

/**
 * JSONB ⇄ {@code List<MigrationDecision>} converter for the
 * {@code migration_decisions.decisions} column.  Only the decision list
 * is stored — the {@code sessionId} lives in the row's primary key.
 *
 * <p>JavaTimeModule so the {@link java.time.Instant decidedAt} field
 * round-trips as ISO-8601 instead of a numeric epoch.
 */
public class MigrationDecisionsJsonConverter
        implements AttributeConverter<List<MigrationDecision>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final TypeReference<List<MigrationDecision>> TYPE_REF = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<MigrationDecision> attribute) {
        if (attribute == null || attribute.isEmpty()) return "[]";
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialise migration decisions to JSON", e);
        }
    }

    @Override
    public List<MigrationDecision> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return List.of();
        try {
            return MAPPER.readValue(dbData, TYPE_REF);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not deserialise migration decisions from JSON", e);
        }
    }
}
