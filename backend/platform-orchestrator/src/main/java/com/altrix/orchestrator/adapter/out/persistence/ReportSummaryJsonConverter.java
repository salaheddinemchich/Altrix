package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.common.domain.model.ReportSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.AttributeConverter;

/**
 * JSONB ⇄ {@link ReportSummary} converter for the
 * {@code migration_reports.structured_summary} column.
 *
 * <p>{@link ReportSummary} and its nested records have no computed
 * accessors, so unlike {@link ProjectBlueprintJsonConverter} no Jackson
 * mix-ins are needed — plain record (de)serialisation round-trips cleanly.
 */
public class ReportSummaryJsonConverter implements AttributeConverter<ReportSummary, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override
    public String convertToDatabaseColumn(ReportSummary attribute) {
        if (attribute == null) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialise ReportSummary to JSON", e);
        }
    }

    @Override
    public ReportSummary convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return MAPPER.readValue(dbData, ReportSummary.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not deserialise ReportSummary from JSON", e);
        }
    }
}
