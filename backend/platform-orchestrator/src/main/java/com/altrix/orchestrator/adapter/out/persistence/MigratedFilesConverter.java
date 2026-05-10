package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.common.domain.model.MigratedFile;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

/**
 * Hibernate {@link AttributeConverter} that serialises {@code List<MigratedFile>}
 * to/from the {@code migrated_files} JSONB column on {@code workflow_sessions} (#122).
 */
@Converter
public class MigratedFilesConverter implements AttributeConverter<List<MigratedFile>, String> {

    @JsonIgnoreProperties(ignoreUnknown = true)
    private abstract static class MigratedFileMixin {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .addMixIn(MigratedFile.class, MigratedFileMixin.class);

    private static final TypeReference<List<MigratedFile>> TYPE_REF = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<MigratedFile> files) {
        if (files == null || files.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(files);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialise MigratedFile list to JSON", e);
        }
    }

    @Override
    public List<MigratedFile> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, TYPE_REF);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not deserialise MigratedFile list from JSON", e);
        }
    }
}
