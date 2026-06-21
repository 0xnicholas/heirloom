package com.heirloom.repository;

import com.heirloom.domain.ChangeEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class EventLogRepository {
    private final EventLogJpaRepository jpa;

    public EventLogRepository(EventLogJpaRepository jpa) { this.jpa = jpa; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(ChangeEvent event) { jpa.save(event); }
}
