package com.altrix.orchestrator.domain.port.out;

import com.altrix.common.domain.model.ProjectContext;

/** Driven port — indexes project source files into the vector store for RAG retrieval. */
public interface CodeIndexingPort {
    void index(ProjectContext context);
}
