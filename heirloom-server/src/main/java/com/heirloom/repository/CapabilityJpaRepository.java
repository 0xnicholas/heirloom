package com.heirloom.repository;

import com.heirloom.security.domain.Capability;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CapabilityJpaRepository extends JpaRepository<Capability, Long> {}
