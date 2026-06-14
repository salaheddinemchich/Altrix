package com.example.altrix;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Liveness / readiness probe.  Returns a simple {@code {"status":"UP"}}
 * JSON document on GET — fast, dependency-free, no I/O.  Used by the
 * Altrix sandbox boot-health runner to verify the deployed app actually
 * started accepting requests after a migration.
 *
 * <p>Exposed at {@code /api/health} (the {@code /api} prefix comes from
 * {@link JaxRsApplication}).  Intentionally kept inside the same
 * application path as the business resources so it shares the same
 * JAX-RS lifecycle and CDI scope — a probe that doesn't survive the
 * same way the rest of the API does isn't a useful probe.
 */
@Path("/health")
@ApplicationScoped
public class HealthResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response health() {
        return Response.ok(Map.of("status", "UP")).build();
    }
}
