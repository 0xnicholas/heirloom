package com.heirloom.schema.domain;

import com.heirloom.entity.HeirloomEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Resource Type definition — the central entity in Heirloom's schema registry.
 * <p>
 * Each Resource Type declares what fields an instance has, which abilities
 * are permitted on it, how its state machine works, and how it relates to
 * other types. These declarations are type-level contracts enforced by the
 * system, not optional RBAC configuration.
 * <p>
 * Implements {@link HeirloomEntity} — the common interface for all platform entities.
 */
@Entity
@Table(name = "resource_types")
public class ResourceType implements HeirloomEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Unique type name. Must be PascalCase by convention.
     * E.g., "Customer", "PurchaseOrder", "InventoryItem".
     */
    @NotBlank
    @Pattern(regexp = "^[A-Z][a-zA-Z0-9]*$",
             message = "Type name must be PascalCase")
    @Column(unique = true, nullable = false, length = 128)
    private String name;

    @Column(length = 1024)
    private String description;

    /**
     * Field definitions stored as JSONB.
     */
    @Type(value = io.hypersistence.utils.hibernate.type.json.JsonType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Field> fields = new ArrayList<>();

    /**
     * Declared abilities — which operations are structurally possible
     * on instances of this type. Stored as JSONB array of enum names.
     */
    @Type(value = io.hypersistence.utils.hibernate.type.json.JsonType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Ability> abilities = new ArrayList<>();

    /**
     * State machine transitions. States are defined implicitly
     * by the union of from/to across all transitions.
     */
    @Type(value = io.hypersistence.utils.hibernate.type.json.JsonType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<StateTransition> stateMachine = new ArrayList<>();

    /**
     * Typed relationships to other Resource Types.
     */
    @Type(value = io.hypersistence.utils.hibernate.type.json.JsonType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Relationship> relationships = new ArrayList<>();

    /**
     * Phase 1.3: per-field visibility configuration. JSONB map of
     * {@code fieldName → [roleName, ...]}. A field is visible to an actor
     * when the actor's role (or {@code "*"} for wildcard) is in the list.
     * Missing field or empty list = visible to everyone with read capability.
     */
    @Type(value = io.hypersistence.utils.hibernate.type.json.JsonType.class)
    @Column(name = "field_visibility", columnDefinition = "jsonb")
    private Map<String, List<String>> fieldVisibility = new HashMap<>();

    /**
     * Phase 4.1: branch identifier. {@code NULL} means the type lives on
     * {@code main}. A non-null value means this row is a clone of the main
     * type for that branch — branch edits are isolated; merging compares
     * the branch clone against the main row.
     */
    @Column(name = "branch_name", length = 64)
    private String branchName;

    /**
     * Phase 2.6 / I-0: initial state for new Resource instances of this type.
     * Required when the state machine is non-empty. TypeValidator enforces
     * that this value is a valid state in the state machine.
     */
    @Column(name = "initial_state", length = 64)
    private String initialState;

    /**
     * Monotonically increasing version number. Incremented on
     * every schema change. Kept for historical query compatibility.
     */
    @Version
    @Column(nullable = false)
    private int version;

    /** Fully Qualified Name — globally unique identifier. Pattern: {domain}.{name} */
    @Column(length = 512)
    private String fullyQualifiedName;

    /** Domain namespace for organizational separation. Default: "default" */
    @Column(length = 128)
    private String domain = "default";

    /** Content hash for incremental ingestion detection (sourceHash equivalent) */
    @Column(length = 64)
    private String changeHash;

    /** Soft-delete flag */
    @Column(nullable = false)
    private Boolean deleted = false;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ResourceType() {
        // JPA
    }

    public ResourceType(String name) {
        this.name = name;
        this.fields = new ArrayList<>();
        this.abilities = new ArrayList<>();
        this.stateMachine = new ArrayList<>();
        this.relationships = new ArrayList<>();
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

    // --- Getters / Setters ---

    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<Field> getFields() { return fields; }
    public void setFields(List<Field> fields) { this.fields = fields; }

    public List<Ability> getAbilities() { return abilities; }
    public void setAbilities(List<Ability> abilities) { this.abilities = abilities; }

    public List<StateTransition> getStateMachine() { return stateMachine; }
    public void setStateMachine(List<StateTransition> stateMachine) { this.stateMachine = stateMachine; }

    public List<Relationship> getRelationships() { return relationships; }
    public void setRelationships(List<Relationship> relationships) { this.relationships = relationships; }

    public Map<String, List<String>> getFieldVisibility() { return fieldVisibility; }
    public void setFieldVisibility(Map<String, List<String>> fieldVisibility) { this.fieldVisibility = fieldVisibility; }

    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }

    public String getInitialState() { return initialState; }
    public void setInitialState(String initialState) { this.initialState = initialState; }

    @Override
    public Long getVersion() { return (long) version; }

    @Override
    public String getFullyQualifiedName() { return fullyQualifiedName; }

    @Override
    public void setFullyQualifiedName(String fqn) { this.fullyQualifiedName = fqn; }

    @Override
    public String getEntityType() { return "resourceType"; }

    @Override
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    @Override
    public String getChangeHash() { return changeHash; }
    public void setChangeHash(String changeHash) { this.changeHash = changeHash; }

    @Override
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = deleted; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
