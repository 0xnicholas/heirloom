package com.heirloom.schema.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A Resource Type definition — the central entity in Heirloom's schema registry.
 * <p>
 * Each Resource Type declares what fields an instance has, which abilities
 * are permitted on it, how its state machine works, and how it relates to
 * other types. These declarations are type-level contracts enforced by the
 * system, not optional RBAC configuration.
 */
@Entity
@Table(name = "resource_types")
public class ResourceType {

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
     * Monotonically increasing version number. Incremented on
     * every schema change. Kept for historical query compatibility.
     */
    @Version
    @Column(nullable = false)
    private int version;

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

    public int getVersion() { return version; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
