package com.heirloom.schema.repository;

import com.heirloom.schema.domain.OntologyBranch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OntologyBranchJpaRepository extends JpaRepository<OntologyBranch, Long> {
    Optional<OntologyBranch> findByName(String name);
}