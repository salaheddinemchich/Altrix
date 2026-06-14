package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.blueprint.BlueprintFile;
import com.altrix.orchestrator.domain.model.blueprint.ProjectBlueprint;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.util.Optional;

/**
 * Read-side port the migrator uses to look up project-level context
 * during a rewrite.  Deliberately narrower than
 * {@link ProjectBlueprintRepository} so the migrator can't accidentally
 * persist a partial blueprint.
 *
 * <p>The two methods cover the migrator's actual read patterns:
 * <ul>
 *   <li>{@link #findForSession(WorkflowSessionId)} — once per migration
 *       run, to read the stack / migration order / risk notes the
 *       prompt builder pins at the top of every call.</li>
 *   <li>{@link #findFileSlice(WorkflowSessionId, String)} — once per
 *       file, to read that file's {@link BlueprintFile} slice (role,
 *       features, neighbour signatures).</li>
 * </ul>
 *
 * <p>Implementations are expected to be cheap on repeated calls (the
 * JPA adapter caches the deserialised blueprint per session for the
 * length of a single migration run).
 */
public interface ProjectBlueprintPort {

    Optional<ProjectBlueprint> findForSession(WorkflowSessionId sessionId);

    /**
     * Convenience — returns just the slice for a single file, or empty
     * when neither the blueprint nor a matching file path exists.
     *
     * <p>Equivalent to
     * {@code findForSession(sessionId).flatMap(b -> b.fileSlice(filePath))}
     * but adapters may short-circuit the deserialisation when only one
     * file is needed.
     */
    default Optional<BlueprintFile> findFileSlice(WorkflowSessionId sessionId, String filePath) {
        return findForSession(sessionId).flatMap(bp -> bp.fileSlice(filePath));
    }
}
