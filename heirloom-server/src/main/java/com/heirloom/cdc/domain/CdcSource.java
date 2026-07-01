package com.heirloom.cdc.domain;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "cdc_sources")
public class CdcSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 128)
    private String name;

    @Column(name = "pg_host", nullable = false, length = 256)
    private String pgHost;

    @Column(name = "pg_port", nullable = false)
    private int pgPort = 5432;

    @Column(name = "pg_database", nullable = false, length = 128)
    private String pgDatabase;

    @Column(name = "pg_schema", nullable = false, length = 128)
    private String pgSchema = "public";

    @Column(name = "pg_username", nullable = false, length = 128)
    private String pgUsername;

    @Column(name = "pg_password", nullable = false, length = 256)
    private String pgPassword;

    @Column(name = "publication_name", nullable = false, length = 128)
    private String publicationName;

    @Column(name = "slot_name", nullable = false, length = 128)
    private String slotName;

    @Type(JsonType.class)
    @Column(name = "watched_tables", columnDefinition = "jsonb", nullable = false)
    private Map<String, TableConfig> watchedTables = new LinkedHashMap<>();

    @Column(nullable = false, length = 32)
    private String status = "STOPPED";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
    public void setName(String n) { this.name = n; }

    public String getPgHost() { return pgHost; }
    public void setPgHost(String h) { this.pgHost = h; }

    public int getPgPort() { return pgPort; }
    public void setPgPort(int p) { this.pgPort = p; }

    public String getPgDatabase() { return pgDatabase; }
    public void setPgDatabase(String d) { this.pgDatabase = d; }

    public String getPgSchema() { return pgSchema; }
    public void setPgSchema(String s) { this.pgSchema = s; }

    public String getPgUsername() { return pgUsername; }
    public void setPgUsername(String u) { this.pgUsername = u; }

    public String getPgPassword() { return pgPassword; }
    public void setPgPassword(String p) { this.pgPassword = p; }

    public String getPublicationName() { return publicationName; }
    public void setPublicationName(String n) { this.publicationName = n; }

    public String getSlotName() { return slotName; }
    public void setSlotName(String n) { this.slotName = n; }

    public Map<String, TableConfig> getWatchedTables() { return watchedTables; }
    public void setWatchedTables(Map<String, TableConfig> t) { this.watchedTables = t != null ? t : new LinkedHashMap<>(); }

    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /**
     * Per-table CDC configuration.
     */
    public record TableConfig(String resourceType, String stateColumn, List<String> pkColumns) {
        public TableConfig {
            if (resourceType == null || resourceType.isBlank()) {
                throw new IllegalArgumentException("resourceType is required");
            }
            pkColumns = pkColumns != null ? pkColumns : List.of();
        }

        // Backward-compatible constructors
        public TableConfig(String resourceType, String stateColumn) {
            this(resourceType, stateColumn, List.of());
        }
        public TableConfig(String resourceType) {
            this(resourceType, null, List.of());
        }
    }
}
