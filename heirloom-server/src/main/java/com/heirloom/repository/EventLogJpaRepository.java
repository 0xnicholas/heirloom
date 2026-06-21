package com.heirloom.repository;

import com.heirloom.domain.ChangeEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventLogJpaRepository extends JpaRepository<ChangeEvent, Long> {}
