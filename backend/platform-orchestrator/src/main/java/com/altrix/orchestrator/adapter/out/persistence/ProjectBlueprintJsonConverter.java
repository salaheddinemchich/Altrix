package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.blueprint.ProjectBlueprint;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.AttributeConverter;

/**
 * JSONB ⇄ {@link ProjectBlueprint} converter for the
 * {@code project_blueprints.blueprint} column.
 *
 * <p>Uses Jackson with the JavaTime module so {@link java.time.Instant}
 * inside the aggregate round-trips as an ISO-8601 string instead of a
 * numeric epoch.  Records are auto-supported by recent Jackson.
 *
 * <p>Throws {@link IllegalArgumentException} on either side of the
 * conversion when serialisation fails — the caller (Hibernate) treats
 * that as a database error which surfaces clearly in logs.
 */
public class ProjectBlueprintJsonConverter
        implements AttributeConverter<ProjectBlueprint, String> {

    // Mix-ins keep the domain records Jackson-free: they carry the
    // @JsonIgnore for the records' computed convenience accessors (e.g.
    // BlueprintFile.isPassThrough()), which would otherwise serialise as
    // phantom properties and fail to round-trip on read.
    private static final ObjectMapper MAPPER = newMapper();

    private static ObjectMapper newMapper() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        BlueprintJacksonMixins.register(mapper);
        return mapper;
    }

    @Override
    public String convertToDatabaseColumn(ProjectBlueprint attribute) {
        if (attribute == null) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialise ProjectBlueprint to JSON", e);
        }
    }

    @Override
    public ProjectBlueprint convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return MAPPER.readValue(dbData, ProjectBlueprint.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not deserialise ProjectBlueprint from JSON", e);
        }
    }
}
