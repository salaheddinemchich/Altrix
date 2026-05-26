package com.altrix.orchestrator.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for the Docker-based sandbox runner (#16/#17/#99-#103).
 *
 * <pre>
 * sandbox:
 *   docker:
 *     enabled:           false           # opt-in — daemon may not be available
 *     maven-image:       maven:3.9-eclipse-temurin-21-alpine
 *     timeout:           5m              # ISO-8601 duration; hard cap per container
 *     memory-mb:         1024            # --memory; OOM-kills runaway builds
 *     cpus:              "2.0"           # --cpus; throttles a busy build
 *     network:           bridge          # "none" once a local .m2 cache is in place
 *     workspace-prefix:  altrix-sandbox- # name prefix for created containers
 *     reaper:
 *       enabled:         true
 *       sweep-interval:  10m             # periodic orphan cleanup
 *       max-age:         30m             # containers older than this are reaped
 * </pre>
 *
 * <p>Everything is opt-in.  When {@code enabled=false} (the default), the
 * runner registers a Spring bean but {@code isAvailable()} returns false,
 * so the validator skips it without ever touching the Docker SDK at runtime.
 */
@ConfigurationProperties(prefix = "sandbox.docker")
public record SandboxDockerConfig(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("maven:3.9-eclipse-temurin-21-alpine") String mavenImage,
        @DefaultValue("5m") Duration timeout,
        @DefaultValue("1024") int memoryMb,
        @DefaultValue("2.0") String cpus,
        @DefaultValue("bridge") String network,
        @DefaultValue("altrix-sandbox-") String workspacePrefix,
        Reaper reaper
) {

    public SandboxDockerConfig {
        if (reaper == null) reaper = new Reaper(true, Duration.ofMinutes(10), Duration.ofMinutes(30));
        if (timeout == null) timeout = Duration.ofMinutes(5);
    }

    /**
     * Periodic cleanup of orphaned containers (#103).  Any container
     * whose name starts with {@link #workspacePrefix} and is older than
     * {@link Reaper#maxAge} gets removed.
     */
    public record Reaper(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT10M") Duration sweepInterval,
            @DefaultValue("PT30M") Duration maxAge
    ) {
        public Reaper {
            if (sweepInterval == null) sweepInterval = Duration.ofMinutes(10);
            if (maxAge == null)        maxAge        = Duration.ofMinutes(30);
        }
    }
}
