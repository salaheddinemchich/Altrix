package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.rag.FileProvenance.DocReference;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;

import java.util.List;
import java.util.Map;

/**
 * JSONB ⇄ {@code Map<String, List<DocReference>>} converter for the
 * {@code file_provenance.per_file} column.
 *
 * <p>Returns an empty (not null) map on a null/blank DB value so callers
 * never have to null-check.
 */
public class FileProvenanceJsonConverter
        implements AttributeConverter<Map<String, List<DocReference>>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, List<DocReference>>> TYPE_REF =
            new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, List<DocReference>> attribute) {
        if (attribute == null || attribute.isEmpty()) return "{}";
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialise file provenance to JSON", e);
        }
    }

    @Override
    public Map<String, List<DocReference>> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(dbData, TYPE_REF);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not deserialise file provenance from JSON", e);
        }
    }
}
