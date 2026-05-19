package com.altrix.project.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface WebhookDeliveryJpaRepository extends JpaRepository<WebhookDeliveryEntity, String> {

    /** Used by the replay endpoint and to deduplicate concurrent retries. */
    Optional<WebhookDeliveryEntity> findByDeliveryId(String deliveryId);

    /** #91 — nightly purge of audit rows beyond the retention window. */
    @Modifying
    @Transactional
    @Query("DELETE FROM WebhookDeliveryEntity w WHERE w.receivedAt < :cutoff")
    int deleteAllReceivedBefore(@Param("cutoff") Instant cutoff);
}
