package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.blueprint.BlueprintFile;
import com.altrix.orchestrator.domain.model.blueprint.DocReference;

/**
 * Driven port for streaming {@code ProjectMapperAgent} progress to the
 * UI in real time.  The agent calls these methods at each significant
 * step; the adapter pushes them onto a WebSocket topic
 * {@code /topic/sessions/{id}/mapper}.
 *
 * <p>Lives in the domain layer so the agent can stay framework-free.
 * The Spring / STOMP transport is an adapter concern.
 *
 * <p>All methods are fire-and-forget — implementations must never throw,
 * never block long enough to slow down the mapper, and must tolerate
 * the case where no one is subscribed.
 */
public interface BlueprintStreamPort {

    /** Emitted once when the mapper starts work for a session. */
    void mapperStarted(String sessionId, int totalFiles);

    /** Emitted as each file is identified — before features are detected. */
    void fileDetected(String sessionId, BlueprintFile file);

    /**
     * Emitted once per feature attached to a file.  Carries the same
     * data already on the {@link BlueprintFile} so the UI can render
     * incremental changes without re-fetching the whole blueprint.
     */
    void featureDetected(String sessionId, String filePath, String featureId, String description);

    /**
     * Emitted when the doc-requirement planner decides which doc paths
     * it needs to fetch for a particular file.  The UI uses this to
     * show "looking up docs…" chips before the actual fetch finishes.
     */
    void docsRequested(String sessionId, String filePath, java.util.List<String> logicalPaths);

    /** Emitted once per documentation page that landed in the embedding store. */
    void docFetched(String sessionId, DocReference doc);

    /**
     * Emitted when a file's blueprint slice is finalised — the file row
     * in the UI can flip from a spinner to a green check.
     */
    void fileBlueprintReady(String sessionId, BlueprintFile file);

    /**
     * Emitted once when the mapper finishes the run.  Carries the totals
     * for the UI summary card.
     */
    void mapperCompleted(String sessionId, int totalFiles, int totalFeatures, int totalDocs, long durationMs);

    /** Emitted when the mapper aborts mid-run with a partial result. */
    void mapperFailed(String sessionId, String reason, boolean partial);
}
