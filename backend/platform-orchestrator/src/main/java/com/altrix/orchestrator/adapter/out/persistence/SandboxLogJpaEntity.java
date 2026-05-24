package com.altrix.orchestrator.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite-key JPA entity for {@code sandbox_logs} (#105).
 */
@Entity
@Table(name = "sandbox_logs")
@IdClass(SandboxLogJpaEntity.Pk.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SandboxLogJpaEntity {

    @Id
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Id
    @Column(name = "runner_id", nullable = false, updatable = false, length = 64)
    private String runnerId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    /** Composite primary key holder.  JPA needs a static class implementing Serializable. */
    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @Setter
    public static class Pk implements Serializable {
        private UUID sessionId;
        private String runnerId;

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk other)) return false;
            return Objects.equals(sessionId, other.sessionId)
                && Objects.equals(runnerId, other.runnerId);
        }
        @Override public int hashCode() {
            return Objects.hash(sessionId, runnerId);
        }
    }
}
