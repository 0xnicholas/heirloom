package com.heirloom.pipeline.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;

public interface PipelineOutboxJpaRepository extends JpaRepository<PipelineOutboxEntity, Long> {

    @Modifying
    @Query(value = """
        UPDATE pipeline_outbox
        SET status='CLAIMED', claimed_by=:claimant,
            claimed_until=:leaseUntil,
            claimed_at=COALESCE(claimed_at, now())
        WHERE id IN (
          SELECT id FROM pipeline_outbox
          WHERE status IN ('PENDING','CLAIMED')
            AND (claimed_until IS NULL OR claimed_until < now())
            AND (not_before IS NULL OR not_before <= now())
          ORDER BY created_at
          LIMIT :batchSize
          FOR UPDATE SKIP LOCKED
        )
        RETURNING id, event_id, event_type, payload
        """, nativeQuery = true)
    List<Object[]> claimBatch(@Param("claimant") String claimant,
                              @Param("leaseUntil") Instant leaseUntil,
                              @Param("batchSize") int batchSize);

    @Modifying
    @Query(value = """
        UPDATE pipeline_outbox
        SET status='PENDING', claimed_by=NULL, claimed_until=NULL
        WHERE status='CLAIMED' AND claimed_until < now()
        """, nativeQuery = true)
    int releaseExpiredClaims();
}
