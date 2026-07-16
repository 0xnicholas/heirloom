package com.heirloom.pipeline.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DeadLetterJpaRepository extends JpaRepository<DeadLetterEntity, Long> {
    List<DeadLetterEntity> findBySourceFqnOrderByFailedAtDesc(String sourceFqn);
    List<DeadLetterEntity> findByReplayedAtIsNullOrderByFailedAtDesc();
    List<DeadLetterEntity> findByReplayedAtIsNotNullOrderByFailedAtDesc();
    List<DeadLetterEntity> findAllByOrderByFailedAtDesc();
}
