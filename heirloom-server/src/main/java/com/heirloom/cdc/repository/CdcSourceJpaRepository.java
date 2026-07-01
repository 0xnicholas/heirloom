package com.heirloom.cdc.repository;

import com.heirloom.cdc.domain.CdcSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CdcSourceJpaRepository extends JpaRepository<CdcSource, Long> {
    Optional<CdcSource> findByName(String name);
}
