package com.altrix.orchestrator.adapter.out.sandbox;

import com.altrix.orchestrator.infrastructure.config.SandboxDockerConfig;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Periodic cleanup of orphaned sandbox containers (#103).
 *
 * <p>If the orchestrator crashes mid-compile or a stop signal misses
 * its window, a container with the {@code altrix-sandbox-} name prefix
 * + {@code altrix.sandbox=true} label can be left running.  This bean
 * sweeps every {@code sandbox.docker.reaper.sweep-interval} and removes
 * any container older than {@code sandbox.docker.reaper.max-age}.
 *
 * <p>Disabled when {@code sandbox.docker.enabled=false} or
 * {@code sandbox.docker.reaper.enabled=false}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "sandbox.docker", name = "enabled", havingValue = "true")
public class DockerSandboxReaper {

    private final SandboxDockerConfig config;
    private volatile DockerClient client;

    public DockerSandboxReaper(SandboxDockerConfig config) {
        this.config = config;
    }

    @PostConstruct
    void start() {
        if (!config.reaper().enabled()) {
            log.info("[DockerSandboxReaper] disabled — leaving orphans to manual cleanup");
            return;
        }
        try {
            client = buildClient();
            client.pingCmd().exec();
            log.info("[DockerSandboxReaper] armed — sweep interval {}, max age {}",
                    config.reaper().sweepInterval(), config.reaper().maxAge());
        } catch (Exception e) {
            log.warn("[DockerSandboxReaper] Docker daemon unreachable ({}) — reaper disabled", e.getMessage());
            client = null;
        }
    }

    @PreDestroy
    void close() {
        if (client != null) {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Sweep runs at a fixed delay tied to {@code sandbox.docker.reaper.sweep-interval}.
     * The Spring {@code @Scheduled} fixed-rate expression in Spel reads from
     * the property file directly so reconfig requires a restart — sweep
     * intervals shouldn't change at runtime anyway.
     */
    @Scheduled(fixedRateString = "#{@sandboxDockerConfig.reaper().sweepInterval().toMillis()}")
    void sweep() {
        if (client == null || !config.reaper().enabled()) return;
        try {
            List<Container> containers = client.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of(DockerSandboxRunner.LABEL_KEY, DockerSandboxRunner.LABEL_VALUE))
                    .exec();
            long now = Instant.now().getEpochSecond();
            long maxAgeSeconds = config.reaper().maxAge().getSeconds();
            int reaped = 0;
            for (Container c : containers) {
                long ageSec = now - c.getCreated();
                if (ageSec >= maxAgeSeconds) {
                    try {
                        client.removeContainerCmd(c.getId()).withForce(true).exec();
                        reaped++;
                    } catch (Exception e) {
                        log.warn("[DockerSandboxReaper] could not reap {}: {}",
                                c.getId().substring(0, 12), e.getMessage());
                    }
                }
            }
            if (reaped > 0) {
                log.info("[DockerSandboxReaper] reaped {} orphan container(s)", reaped);
            }
        } catch (Exception e) {
            // Sweep errors must never propagate — the scheduler keeps firing.
            log.warn("[DockerSandboxReaper] sweep failed: {}", e.getMessage());
        }
    }

    DockerClient buildClient() {
        DefaultDockerClientConfig cfg = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient http = new ZerodepDockerHttpClient.Builder()
                .dockerHost(cfg.getDockerHost())
                .sslConfig(cfg.getSSLConfig())
                .connectionTimeout(Duration.ofSeconds(10))
                .responseTimeout(Duration.ofSeconds(60))
                .build();
        return DockerClientImpl.getInstance(cfg, http);
    }
}
