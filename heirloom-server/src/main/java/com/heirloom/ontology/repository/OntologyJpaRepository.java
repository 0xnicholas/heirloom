package com.heirloom.ontology.repository;

import com.heirloom.ontology.domain.Ontology;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OntologyJpaRepository extends JpaRepository<Ontology, Long> {
    Optional<Ontology> findByName(String name);
}