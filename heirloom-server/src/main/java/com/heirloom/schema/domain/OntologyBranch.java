package com.heirloom.schema.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;

/**
 * Phase 4.1 Ontology Branching.
 *
 * <p>A branch is a named workspace for editing {@link ResourceType} schemas
 * in isolation from {@code main}. When created, it captures a snapshot of
 * every main type's {@code changeHash} into {@link #baseHashes}. As
 * branch-local types are edited, their hashes move relative to base.
 *
 * <p>On merge, each type is classified:
 * <ul>
 *   <li><b>Unchanged on both</b> (branch tip = base, main = base) — skip.</li>
 *   <li><b>Branch only</b> (main = base, branch ≠ base) — take branch version.</li>
 *   <li><b>Main only</b> (main ≠ base, branch = base) — keep main.</li>
 *   <li><b>Both diverged</b> (main ≠ base, branch ≠ base, and they differ)
 *       — conflict. Caller resolves via {@code take-mine} / {@code take-theirs}
 *       / {@code take-both}.</li>
 * </ul>
 */
@Entity
@Table(name = "ontology_branches")
public class OntologyBranch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    /** typeName → changeHash snapshot at branch creation. */
    @jakarta.persistence.Convert(attributeName = "json")  // hibernate-types or hibernate6 default
    // For simplicity, store as String JSON and parse in service.
    @Column(name = "base_hashes", columnDefinition = "jsonb", nullable = false)
    private String baseHashes;

    /** typeName → current changeHash on the branch overlay (if any). */
    @Column(name = "tip_hashes", columnDefinition = "jsonb")
    private String tipHashes;

    @Column(nullable = false, length = 16)
    private String status = "OPEN";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "merged_at")
    private Instant mergedAt;

    @Column(name = "merged_by", length = 128)
    private String mergedBy;

    @Column(length = 512)
    private String description;

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_MERGED = "MERGED";
    public static final String STATUS_CLOSED = "CLOSED";

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseHashes() { return baseHashes; }
    public void setBaseHashes(String baseHashes) { this.baseHashes = baseHashes; }
    public String getTipHashes() { return tipHashes; }
    public void setTipHashes(String tipHashes) { this.tipHashes = tipHashes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getMergedAt() { return mergedAt; }
    public void setMergedAt(Instant mergedAt) { this.mergedAt = mergedAt; }
    public String getMergedBy() { return mergedBy; }
    public void setMergedBy(String mergedBy) { this.mergedBy = mergedBy; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}