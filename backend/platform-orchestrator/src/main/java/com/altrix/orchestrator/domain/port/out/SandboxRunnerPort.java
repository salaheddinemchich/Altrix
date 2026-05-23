package com.altrix.orchestrator.domain.port.out;

import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding;

import java.util.List;

/**
 * Driven port — one strategy that examines a {@link MigrationArtifact} and
 * reports {@link SandboxFinding}s.
 *
 * <p>Multiple implementations coexist and the {@code SandboxValidatorAgent}
 * runs them all (Strategy + Composite).  Today the only one is the static
 * checker ({@code StaticSandboxRunner}); #16/#17 add a Docker runner that
 * actually compiles the migrated tree, #97 adds Checkstyle + SpotBugs, etc.
 *
 * <p>New runners just declare themselves {@code @Component} — the agent
 * picks them up via Spring auto-wiring.  Open/Closed: zero modification
 * to the agent when a runner is added.
 *
 * <p>Best-effort contract: a runner must <b>never</b> throw — it returns
 * findings (with severity ERROR if the runner itself broke).  A throwing
 * runner would block the whole pipeline; the validator wraps every call
 * defensively but runners should not rely on that.
 */
public interface SandboxRunnerPort {

    /**
     * Stable identifier carried in every {@link SandboxFinding}.  Used in
     * logs, in the future structured ValidationReport, and in tests.
     */
    String id();

    /**
     * Lower numbers run first.  Static checks should run early (cheap,
     * catches obvious mistakes); heavy runners like Docker compile last.
     * The agent processes them in ascending order so a fast ERROR can
     * short-circuit on the future {@code failFast} switch (TBD).
     */
    int order();

    /**
     * Cheap availability probe.  Return false to skip this runner when its
     * preconditions aren't met (e.g. Docker daemon unreachable, no Maven
     * wrapper in the project).  The agent still runs everything else.
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Examines the artifact and returns any findings.  Empty list = clean.
     * Implementations must be deterministic given the same input — caching
     * relies on it (#28).
     */
    List<SandboxFinding> run(MigrationArtifact artifact);
}
