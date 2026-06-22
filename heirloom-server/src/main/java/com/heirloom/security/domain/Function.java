package com.heirloom.security.domain;

import com.heirloom.entity.HeirloomEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "security_functions")
public class Function implements HeirloomEntity {
    @Id @GeneratedValue private Long id;
    @Column(name = "fully_qualified_name") private String fullyQualifiedName;
    private String name;
    private String inputType;      // ResourceType name for input (or null if scalar)
    private String outputType;     // "STRING", "NUMBER", "BOOLEAN", "OBJECT"
    @Column(columnDefinition = "text") private String code;  // Function body — SpEL expression
    private String description;

    /** Execution timeout in milliseconds. 0 = use executor default. */
    @Column(name = "timeout_ms") private Long timeoutMs = 0L;

    /** Whether to emit an audit event on each invocation. Default off (high-frequency
     *  Functions like UI-derived calculations would otherwise flood the Event Log). */
    @Column(name = "audit_enabled") private Boolean auditEnabled = false;

    @Version private Long version = 1L; private String changeHash;
    private Boolean deleted = false;
    private Instant createdAt = Instant.now(); private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public String getEntityType() { return "function"; }
    public String getFullyQualifiedName() { return fullyQualifiedName; }
    public void setFullyQualifiedName(String f) { this.fullyQualifiedName = f; }
    public String getName() { return name; } public void setName(String n) { this.name = n; }
    public String getDescription() { return description; } public void setDescription(String d) { this.description = d; }
    public Long getVersion() { return version; }
    public String getChangeHash() { return changeHash; }
    public void setChangeHash(String h) { this.changeHash = h; }
    public Boolean getDeleted() { return deleted; } public void setDeleted(Boolean d) { this.deleted = d; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getInputType() { return inputType; } public void setInputType(String t) { this.inputType = t; }
    public String getOutputType() { return outputType; } public void setOutputType(String t) { this.outputType = t; }
    public String getCode() { return code; } public void setCode(String c) { this.code = c; }
    public Long getTimeoutMs() { return timeoutMs; } public void setTimeoutMs(Long t) { this.timeoutMs = t; }
    public Boolean getAuditEnabled() { return auditEnabled; } public void setAuditEnabled(Boolean a) { this.auditEnabled = a; }
}