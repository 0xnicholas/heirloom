package com.heirloom.knowledge.domain;

import com.heirloom.entity.HeirloomEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;
import java.time.Instant;
import java.util.*;

@Entity @Table(name = "knowledge_articles")
public class KnowledgeArticle implements HeirloomEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 256) private String name;
    @Column(name = "fully_qualified_name", length = 512) private String fullyQualifiedName;
    @Column(name = "file_path", nullable = false, length = 1024) private String filePath;
    @Column(name = "file_hash", nullable = false, length = 64) private String fileHash;
    @Column(name = "source_fqn", nullable = false, length = 512) private String sourceFqn;
    @Column(nullable = false, length = 128) private String type;
    @Column(length = 128) private String domain = "default";
    @Column(length = 512) private String title;
    @Column(length = 1024) private String description;
    @Column(length = 2048) private String resource;
    @Column(columnDefinition = "text") private String body;
    @Column(name = "frontmatter_raw", columnDefinition = "text") private String frontmatterRaw;
    @Type(JsonType.class) @Column(columnDefinition = "jsonb") private Map<String, Object> frontmatter = new HashMap<>();
    @Type(JsonType.class) @Column(columnDefinition = "jsonb") private List<String> tags = new ArrayList<>();
    @Type(JsonType.class) @Column(name = "references_jsonb", columnDefinition = "jsonb") private List<EntityReference> references = new ArrayList<>();
    @Type(JsonType.class) @Column(name = "citations_jsonb", columnDefinition = "jsonb") private List<ExternalCitation> citations = new ArrayList<>();
    @Column(length = 256) private String author;
    @Column(length = 256) private String owner;
    @Column(name = "okf_version", length = 16) private String okfVersion = "0.1";
    @Column(columnDefinition = "vector(1536)") private float[] embedding;
    @Column(length = 32) private String status = "published";
    @Column(name = "sync_status", length = 32) private String syncStatus = "OK";
    @Column(name = "sync_error", columnDefinition = "text") private String syncError;
    @Column(name = "last_synced_at") private Instant lastSyncedAt;
    @Version private Long version = 1L;
    @Column(name = "change_hash", length = 64) private String changeHash;
    @Column(nullable = false) private Boolean deleted = false;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;

    @PrePersist void prePersist() { createdAt = Instant.now(); updatedAt = Instant.now(); }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }

    @Override public Long getId() { return id; }
    @Override public String getEntityType() { return "knowledgeArticle"; }
    @Override public String getFullyQualifiedName() { return fullyQualifiedName; }
    @Override public void setFullyQualifiedName(String f) { fullyQualifiedName = f; }
    @Override public String getName() { return name; }
    @Override public String getDescription() { return description; }
    @Override public Long getVersion() { return version; }
    @Override public Instant getCreatedAt() { return createdAt; }
    @Override public Instant getUpdatedAt() { return updatedAt; }
    @Override public String getOwner() { return owner; }
    @Override public String getChangeHash() { return changeHash; }
    @Override public Boolean getDeleted() { return deleted; }

    public void setName(String n) { name = n; }
    public void setFilePath(String p) { filePath = p; }
    public void setFileHash(String h) { fileHash = h; }
    public void setSourceFqn(String s) { sourceFqn = s; }
    public void setType(String t) { type = t; }
    public void setTitle(String t) { title = t; }
    public void setDescription(String d) { description = d; }
    public void setResource(String r) { resource = r; }
    public void setBody(String b) { body = b; }
    public void setFrontmatterRaw(String r) { frontmatterRaw = r; }
    public void setFrontmatter(Map<String, Object> f) { frontmatter = f; }
    public void setTags(List<String> t) { tags = t; }
    public void setReferences(List<EntityReference> r) { references = r; }
    public void setCitations(List<ExternalCitation> c) { citations = c; }
    public void setAuthor(String a) { author = a; }
    public void setOkfVersion(String v) { okfVersion = v; }
    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] e) { embedding = e; }
    public void setOwner(String o) { owner = o; }
    public void setStatus(String s) { status = s; }
    public void setSyncStatus(String s) { syncStatus = s; }
    public void setSyncError(String e) { syncError = e; }
    public void setLastSyncedAt(Instant t) { lastSyncedAt = t; }
    public void setCreatedAt(Instant t) { createdAt = t; }
    public void setUpdatedAt(Instant t) { updatedAt = t; }
    public void setVersion(Long v) { version = v; }
    public void setChangeHash(String h) { changeHash = h; }
    public void setDeleted(Boolean d) { deleted = d; }
    public void setDomain(String d) { domain = d; }
    public String getFilePath() { return filePath; }
    public String getFileHash() { return fileHash; }
    public String getSourceFqn() { return sourceFqn; }
    public String getType() { return type; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getFrontmatterRaw() { return frontmatterRaw; }
    public Map<String,Object> getFrontmatter() { return frontmatter; }
    public List<String> getTags() { return tags; }
    public List<EntityReference> getReferences() { return references; }
    public List<ExternalCitation> getCitations() { return citations; }
    public String getDomain() { return domain; }
    public String getResource() { return resource; }
    public String getAuthor() { return author; }
    public String getStatus() { return status; }
    public String getSyncStatus() { return syncStatus; }
    public String getSyncError() { return syncError; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
}
