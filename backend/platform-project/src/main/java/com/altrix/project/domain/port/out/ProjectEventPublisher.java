package com.altrix.project.domain.port.out;

import com.altrix.project.domain.model.Project;

/**
 * Secondary port — driven side.
 *
 * <p>Defines what the domain needs to publish domain events.
 * The domain service calls this after a project is registered.
 * The Kafka adapter implements this interface.
 */
public interface ProjectEventPublisher {

    /**
     * Publishes a {@code project.registered} event so the job service
     * can create a migration job automatically.
     *
     * @param project the fully registered project
     */
    void publishProjectRegistered(Project project);
}
