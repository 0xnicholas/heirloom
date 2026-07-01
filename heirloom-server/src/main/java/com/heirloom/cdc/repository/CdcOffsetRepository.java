package com.heirloom.cdc.repository;

import com.heirloom.cdc.domain.CdcOffset;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public class CdcOffsetRepository {

    private final CdcOffsetJpaRepository jpa;

    public CdcOffsetRepository(CdcOffsetJpaRepository jpa) {
        this.jpa = jpa;
    }

    public Optional<CdcOffset> findBySourceName(String sourceName) {
        return jpa.findById(sourceName);
    }

    @Transactional
    public void save(CdcOffset offset) {
        jpa.save(offset);
    }
}
