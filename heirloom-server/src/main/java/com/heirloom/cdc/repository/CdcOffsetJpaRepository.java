package com.heirloom.cdc.repository;

import com.heirloom.cdc.domain.CdcOffset;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CdcOffsetJpaRepository extends JpaRepository<CdcOffset, String> {
}
