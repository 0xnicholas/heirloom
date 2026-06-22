package com.heirloom.security.domain;

import com.heirloom.entity.HeirloomEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "security_capabilities")
public class Capability implements HeirloomEntity {
    @Id @GeneratedValue private Long id;
    @Column(name = "fully_qualified_name") private String fullyQualifiedName;
    private String actorName;
    private String targetEntityType; // e.g., "resourceType"
    private String operation;
    private Instant expiry;        // When this capability expires
    private String grantedBy;      // Which role granted this
    @Version private Long version = 1L; private String changeHash;
    private Boolean deleted = false;
    private Instant createdAt = Instant.now(); private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public String getEntityType() { return "capability"; }
    public String getFullyQualifiedName() { return fullyQualifiedName; }
    public void setFullyQualifiedName(String f) { this.fullyQualifiedName = f; }
    public String getName() { return actorName + "." + targetEntityType + "." + operation; }
    public String getDescription() { return null; }
    public Long getVersion() { return version; }
    public String getChangeHash() { return changeHash; } public void setChangeHash(String h) { this.changeHash = h; }
    public Boolean getDeleted() { return deleted; } public void setDeleted(Boolean d) { this.deleted = d; }
    public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; }
    public String getActorName() { return actorName; } public void setActorName(String a) { this.actorName = a; }
    public String getTargetEntityType() { return targetEntityType; } public void setTargetEntityType(String t) { this.targetEntityType = t; }
    public String getOperation() { return operation; } public void setOperation(String o) { this.operation = o; }
    public Instant getExpiry() { return expiry; } public void setExpiry(Instant e) { this.expiry = e; }
    public String getGrantedBy() { return grantedBy; } public void setGrantedBy(String g) { this.grantedBy = g; }
}
