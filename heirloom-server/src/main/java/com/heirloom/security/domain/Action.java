package com.heirloom.security.domain;

import com.heirloom.entity.HeirloomEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "security_actions")
public class Action implements HeirloomEntity {
    @Id @GeneratedValue private Long id;
    @Column(name = "fully_qualified_name") private String fullyQualifiedName;
    private String name;
    private String targetType;        // ResourceType name this action operates on
    private String requiredAbility;   // KEY, QUERY, MUTATE, TRANSFER, COPY, DROP, FREEZE
    private String stateGate;         // optional: "state = Active" or null
    @Column(columnDefinition = "jsonb") private String validationRules = "{}";
    @Column(columnDefinition = "jsonb") private String executeParams = "{}";
    private String description;
    @Version private Long version = 1L; private String changeHash;
    private Boolean deleted = false;
    private Instant createdAt = Instant.now(); private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public String getEntityType() { return "action"; }
    public String getFullyQualifiedName() { return fullyQualifiedName; }
    public void setFullyQualifiedName(String f) { this.fullyQualifiedName = f; }
    public String getName() { return name; } public void setName(String n) { this.name = n; }
    public String getDescription() { return description; } public void setDescription(String d) { this.description = d; }
    public Long getVersion() { return version; }
    public String getChangeHash() { return changeHash; } public void setChangeHash(String h) { this.changeHash = h; }
    public Boolean getDeleted() { return deleted; } public void setDeleted(Boolean d) { this.deleted = d; }
    public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; }
    public String getTargetType() { return targetType; } public void setTargetType(String t) { this.targetType = t; }
    public String getRequiredAbility() { return requiredAbility; } public void setRequiredAbility(String a) { this.requiredAbility = a; }
    public String getStateGate() { return stateGate; } public void setStateGate(String g) { this.stateGate = g; }
    public String getValidationRules() { return validationRules; } public void setValidationRules(String r) { this.validationRules = r; }
    public String getExecuteParams() { return executeParams; } public void setExecuteParams(String p) { this.executeParams = p; }
}
