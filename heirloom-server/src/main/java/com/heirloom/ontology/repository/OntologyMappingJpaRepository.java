package com.heirloom.ontology.repository;

import com.heirloom.ontology.domain.OntologyMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OntologyMappingJpaRepository extends JpaRepository<OntologyMapping, Long> {

    /** Outgoing edges from this (ontology, rid). */
    List<OntologyMapping> findBySourceOntologyAndSourceRid(
            String sourceOntology, String sourceRid);

    /** Outgoing edges from one ontology (any RID). */
    List<OntologyMapping> findBySourceOntology(String sourceOntology);

    /** Incoming edges to one ontology (any RID). */
    List<OntologyMapping> findByTargetOntology(String targetOntology);

    /** Specific edge lookup for a typed lookup. */
    Optional<OntologyMapping> findBySourceOntologyAndSourceRidAndTargetOntologyAndMappingType(
            String sourceOntology, String sourceRid,
            String targetOntology, String mappingType);

    /** Incoming to a (target ontology, target rid). */
    List<OntologyMapping> findByTargetOntologyAndTargetRid(
            String targetOntology, String targetRid);
}