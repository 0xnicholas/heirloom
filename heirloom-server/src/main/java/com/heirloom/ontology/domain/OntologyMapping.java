package com.heirloom.ontology.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Phase 4.1: directed mapping from one (ontology, RID) to another.
 *
 * <p>{@link #mappingType} semantics:
 * <ul>
 *   <li>{@code ALIAS} — same physical resource, different identifier (the
 *       common case for cross-team agreements: "prod.public.customer" in
 *       our team equals "crm.customer" in theirs).</li>
 *   <li>{@code EQUIVALENT} — semantically the same, may be transformed
 *       (e.g., normalised names, units converted).</li>
 *   <li>{@code RELATED} — linked but distinct (e.g., a customer + their
 *       mirrored record in another system).</li>
 *   <li>{@code DERIVED_FROM} — one was generated from the other (a
 *       derived view, a snapshot).</li>
 * </ul>
 *
 * <p>Confidence is a [0..1] float that the registry / caller can use to
 * rank candidates; default 1.0.
 */
@Entity
@Table(name = "ontology_mappings")
public class OntologyMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_ontology", nullable = false, length = 64)
    private String sourceOntology;

    @Column(name = "source_rid", nullable = false, length = 512)
    private String sourceRid;

    @Column(name = "target_ontology", nullable = false, length = 64)
    private String targetOntology;

    @Column(name = "target_rid", nullable = false, length = 512)
    private String targetRid;

    @Column(name = "mapping_type", nullable = false, length = 32)
    private String mappingType;

    @Column(nullable = false)
    private Double confidence = 1.0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(length = 1024)
    private String notes;

    // Mapping-type constants — kept as String to align with the DB CHECK
    // constraint and make JSON-friendly API responses.
    public static final String TYPE_ALIAS = "ALIAS";
    public static final String TYPE_EQUIVALENT = "EQUIVALENT";
    public static final String TYPE_RELATED = "RELATED";
    public static final String TYPE_DERIVED_FROM = "DERIVED_FROM";

    public Long getId() { return id; }
    public String getSourceOntology() { return sourceOntology; }
    public void setSourceOntology(String sourceOntology) { this.sourceOntology = sourceOntology; }
    public String getSourceRid() { return sourceRid; }
    public void setSourceRid(String sourceRid) { this.sourceRid = sourceRid; }
    public String getTargetOntology() { return targetOntology; }
    public void setTargetOntology(String targetOntology) { this.targetOntology = targetOntology; }
    public String getTargetRid() { return targetRid; }
    public void setTargetRid(String targetRid) { this.targetRid = targetRid; }
    public String getMappingType() { return mappingType; }
    public void setMappingType(String mappingType) { this.mappingType = mappingType; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}