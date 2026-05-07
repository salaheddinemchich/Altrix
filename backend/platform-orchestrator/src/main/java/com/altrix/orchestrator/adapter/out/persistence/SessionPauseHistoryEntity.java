package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.session.SessionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "session_pause_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionPauseHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "paused_from", nullable = false, length = 30)
    private SessionStatus pausedFrom;

    @Column(name = "paused_at", nullable = false)
    private Instant pausedAt;

    @Column(name = "resumed_at")
    private Instant resumedAt;
}
