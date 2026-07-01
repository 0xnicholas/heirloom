package com.heirloom.repository;

import com.heirloom.domain.Resource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResourceJpaRepository extends JpaRepository<Resource, Long> {
    Optional<Resource> findByRid(String rid);
}
