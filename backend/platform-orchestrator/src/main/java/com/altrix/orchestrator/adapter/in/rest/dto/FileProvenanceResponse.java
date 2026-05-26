package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.orchestrator.domain.model.rag.FileProvenance;
import com.altrix.orchestrator.domain.model.rag.FileProvenance.DocReference;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST shape for the per-file RAG provenance (#1) — for each migrated
 * file, the doc chunks that were retrieved as context.  Drives the
 * file-to-docs panel on the Index step of the JobDetail timeline.
 */
public record FileProvenanceResponse(
        String sessionId,
        Map<String, List<DocReference>> perFile,
        Instant generatedAt
) {
    public static FileProvenanceResponse from(FileProvenance p) {
        return new FileProvenanceResponse(p.sessionId(), p.perFile(), p.generatedAt());
    }
}
