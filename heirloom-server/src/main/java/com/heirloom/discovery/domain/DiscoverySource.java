package com.heirloom.discovery.domain;

import com.heirloom.entity.HeirloomEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "discovery_sources")
public class DiscoverySource implements HeirloomEntity {
    @Id @GeneratedValue private Long id;
    private String name, fullyQualifiedName, sourceType, environment = "prod";
    @Column(columnDefinition = "jsonb") private String connectionConfig = "{}";
    private String schedule = "manual", status = "ACTIVE", description, owner;
    @Version private Long version = 1L; private String changeHash;
    private Boolean deleted = false;
    private Instant createdAt = Instant.now(), updatedAt = Instant.now();

    public Long getId() { return id; }
    public String getEntityType() { return "discoverySource"; }
    public String getFullyQualifiedName() { return fullyQualifiedName; }
    public void setFullyQualifiedName(String f) { this.fullyQualifiedName = f; }
    public String getName() { return name; } public void setName(String n) { this.name = n; }
    public String getDescription() { return description; } public void setDescription(String d) { this.description = d; }
    public String getOwner() { return owner; } public void setOwner(String o) { this.owner = o; }
    public Long getVersion() { return version; }
    public String getChangeHash() { return changeHash; } public void setChangeHash(String h) { this.changeHash = h; }
    public Boolean getDeleted() { return deleted; }
    public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; }
    public String getSourceType() { return sourceType; } public void setSourceType(String s) { this.sourceType = s; }
    public String getConnectionConfig() { return connectionConfig; } public void setConnectionConfig(String c) { this.connectionConfig = c; }
    public String getSchedule() { return schedule; } public void setSchedule(String s) { this.schedule = s; }
    public String getStatus() { return status; } public void setStatus(String s) { this.status = s; }
    public String getEnvironment() { return environment; } public void setEnvironment(String e) { this.environment = e; }
}
