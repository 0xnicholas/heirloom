package com.heirloom.domain;

import com.heirloom.core.entity.HeirloomEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;
import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "event_log")
public class ChangeEvent implements HeirloomEntity {
    @Id @GeneratedValue private Long id;
    @Column(name = "entity_fqn") private String entityFQN;
    private String entityType;
    private Long entityId;
    @Enumerated(EnumType.STRING) private EventType eventType;
    private String actor;
    private Long entityVersion;
    private String changeHash;
    private String deniedReason;
    private String deniedOperation;
    @Type(JsonType.class)
    @Column(name = "details", columnDefinition = "jsonb")
    private Map<String, Object> details;
    private Instant timestamp = Instant.now();

    @Transient public String getFullyQualifiedName() { return "event." + id; }
    public void setFullyQualifiedName(String fqn) {}

    public Long getId() { return id; }
    public String getEntityType() { return "event"; }
    public String getName() { return "event." + id; }
    public String getDescription() { return null; }
    public Long getVersion() { return 1L; }
    public Instant getCreatedAt() { return timestamp; }
    public Instant getUpdatedAt() { return timestamp; }

    public EventType getEventType() { return eventType; }
    public void setEventType(EventType t) { this.eventType = t; }
    public String getActor() { return actor; }
    public void setActor(String a) { this.actor = a; }
    public Long getEntityId() { return entityId; }
    public void setEntityId(Long id) { this.entityId = id; }
    public String getEntityFQN() { return entityFQN; }
    public void setEntityFQN(String fqn) { this.entityFQN = fqn; }
    public void setEntityType(String t) { this.entityType = t; }
    public Long getEntityVersion() { return entityVersion; }
    public void setEntityVersion(Long v) { this.entityVersion = v; }
    public String getChangeHash() { return changeHash; }
    public void setChangeHash(String h) { this.changeHash = h; }
    public String getDeniedReason() { return deniedReason; }
    public void setDeniedReason(String r) { this.deniedReason = r; }
    public String getDeniedOperation() { return deniedOperation; }
    public void setDeniedOperation(String o) { this.deniedOperation = o; }
    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> d) { this.details = d; }
    public Instant getTimestamp() { return timestamp; }

    public enum EventType {
        ENTITY_CREATED, ENTITY_UPDATED, ENTITY_DELETED,
        ENTITY_DENIED, FUNCTION_INVOKED,
        KNOWLEDGE_SEARCH,
        KNOWLEDGE_CONTEXT_FETCH,
        KNOWLEDGE_ACCESS_DENIED
    }

    public static ChangeEvent created(HeirloomEntity entity, String actor) {
        ChangeEvent e = new ChangeEvent();
        e.entityType = entity.getEntityType();
        e.entityId = entity.getId();
        e.entityFQN = entity.getFullyQualifiedName();
        e.eventType = EventType.ENTITY_CREATED;
        e.actor = actor;
        e.entityVersion = entity.getVersion();
        e.changeHash = entity.getChangeHash();
        return e;
    }
}
