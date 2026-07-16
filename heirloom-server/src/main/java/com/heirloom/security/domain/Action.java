package com.heirloom.security.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.core.entity.HeirloomEntity;
import com.heirloom.schema.domain.Ability;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "security_actions")
public class Action implements HeirloomEntity {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ActionInput>> INPUT_LIST = new TypeReference<>() {};

    @Id
    @GeneratedValue
    private Long id;

    @Column(name = "fully_qualified_name")
    private String fullyQualifiedName;

    private String name;

    /** ResourceType name this action operates on (deprecated, use targetTypeFqn). */
    private String targetType;

    /** I-1: Fully-qualified target type name. */
    @Column(name = "target_type_fqn", length = 256)
    private String targetTypeFqn;

    /** KEY, QUERY, MUTATE, TRANSFER, COPY, DROP, FREEZE (deprecated, use requiredAbilityEnum). */
    private String requiredAbility;

    /** I-1: Structured ability enum name. */
    @Column(name = "req_ability", length = 32)
    private String requiredAbilityEnum;

    /** Optional: "state = Active" or null (deprecated, use stateGateJson). */
    private String stateGate;

    /** I-1: Structured StateGate as JSONB. */
    @Type(JsonType.class)
    @Column(name = "state_gate_json", columnDefinition = "jsonb")
    private StateGate stateGateJson;

    @Column(columnDefinition = "jsonb")
    private String validationRules = "{}";

    @Column(columnDefinition = "jsonb")
    private String executeParams = "{}";

    /** I-1: Action input schema as JSONB. */
    @Type(JsonType.class)
    @Column(name = "input_schema_json", columnDefinition = "jsonb")
    private List<ActionInput> inputSchemaJson;

    private String description;

    @Version
    private Long version = 1L;

    private String changeHash;
    private Boolean deleted = false;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    // === I-1 bridge methods ===

    /**
     * Resolve the target type FQN. Prefers new column, falls back to old name.
     */
    public String resolveTargetType() {
        if (targetTypeFqn != null && !targetTypeFqn.isBlank()) return targetTypeFqn;
        return "default." + targetType; // old format assumed default domain
    }

    /**
     * Resolve the required Ability. Prefers new enum column, falls back to parsing old String.
     */
    public Ability resolveRequiredAbility() {
        if (requiredAbilityEnum != null && !requiredAbilityEnum.isBlank()) {
            return Ability.valueOf(requiredAbilityEnum);
        }
        if (requiredAbility != null && !requiredAbility.isBlank()) {
            return Ability.valueOf(requiredAbility);
        }
        return null;
    }

    /**
     * Resolve the StateGate. Prefers new JSONB column, falls back to null (old format not parseable).
     */
    public StateGate resolveStateGate() {
        return stateGateJson;
    }

    /**
     * Resolve the input schema. Prefers new JSONB column, falls back to empty list.
     */
    public List<ActionInput> resolveInputSchema() {
        return inputSchemaJson != null ? inputSchemaJson : Collections.emptyList();
    }

    // === HeirloomEntity ===

    @Override
    public Long getId() { return id; }

    @Override
    public String getEntityType() { return "action"; }

    @Override
    public String getFullyQualifiedName() { return fullyQualifiedName; }

    @Override
    public void setFullyQualifiedName(String f) { this.fullyQualifiedName = f; }

    @Override
    public String getName() { return name; }

    public void setName(String n) { this.name = n; }

    @Override
    public String getDescription() { return description; }

    public void setDescription(String d) { this.description = d; }

    @Override
    public Long getVersion() { return version; }

    @Override
    public String getChangeHash() { return changeHash; }

    public void setChangeHash(String h) { this.changeHash = h; }

    @Override
    public Boolean getDeleted() { return deleted; }

    public void setDeleted(Boolean d) { this.deleted = d; }

    @Override
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public Instant getUpdatedAt() { return updatedAt; }

    // === Old getters (keep for backward compat) ===

    /** @deprecated use {@link #resolveTargetType()} */
    @Deprecated
    public String getTargetType() { return targetType; }

    public void setTargetType(String t) { this.targetType = t; }

    /** @deprecated use {@link #resolveRequiredAbility()} */
    @Deprecated
    public String getRequiredAbility() { return requiredAbility; }

    /** @deprecated use {@link #resolveRequiredAbility()} */
    @Deprecated
    public void setRequiredAbility(String a) { this.requiredAbility = a; }

    /** @deprecated use {@link #resolveStateGate()} */
    @Deprecated
    public String getStateGate() { return stateGate; }

    /** @deprecated use {@link #resolveStateGate()} */
    @Deprecated
    public void setStateGate(String g) { this.stateGate = g; }

    public String getValidationRules() { return validationRules; }
    public void setValidationRules(String r) { this.validationRules = r; }

    public String getExecuteParams() { return executeParams; }
    public void setExecuteParams(String p) { this.executeParams = p; }

    // === I-1 new getters/setters ===

    public String getTargetTypeFqn() { return targetTypeFqn; }
    public void setTargetTypeFqn(String fqn) { this.targetTypeFqn = fqn; }

    public String getRequiredAbilityEnum() { return requiredAbilityEnum; }
    public void setRequiredAbilityEnum(String e) { this.requiredAbilityEnum = e; }

    public StateGate getStateGateJson() { return stateGateJson; }
    public void setStateGateJson(StateGate g) { this.stateGateJson = g; }

    public List<ActionInput> getInputSchemaJson() { return inputSchemaJson; }
    public void setInputSchemaJson(List<ActionInput> s) { this.inputSchemaJson = s; }
}
