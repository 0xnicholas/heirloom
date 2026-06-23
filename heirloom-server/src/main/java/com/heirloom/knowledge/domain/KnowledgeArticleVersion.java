package com.heirloom.knowledge.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Phase 4.1: snapshot of a {@link KnowledgeArticle} at a point in time.
 * Created on every update / delete so the article's history is recoverable.
 *
 * <p>{@link #articleId} is nullable on purpose — when the parent article
 * is soft- or hard-deleted, the version rows survive (the denormalised
 * {@link #articleFqn} is the durable link).
 */
@Entity
@Table(name = "knowledge_article_versions")
public class KnowledgeArticleVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "article_id")
    private Long articleId;

    @Column(name = "article_fqn", nullable = false, length = 512)
    private String articleFqn;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "snapshot_at", nullable = false)
    private Instant snapshotAt;

    @Column(name = "snapshot_reason", nullable = false, length = 64)
    private String snapshotReason = "update";

    // --- snapshot fields (denormalised from KnowledgeArticle at snapshot time) ---
    @Column(length = 512) private String title;
    @Column(length = 1024) private String description;
    @Column(columnDefinition = "text") private String body;
    @Column(length = 32) private String status;
    @Column(length = 128) private String type;
    @Column(length = 128) private String domain;
    @Column(length = 256) private String author;
    @Column(length = 256) private String owner;
    @Column(length = 2048) private String resource;
    @Column(name = "file_path", length = 1024) private String filePath;
    @Column(name = "file_hash", length = 64) private String fileHash;

    /** Article's @Version value at snapshot time (optimistic-locking coord). */
    private Long version;

    // === Reason constants ===

    public static final String REASON_UPDATE = "update";
    public static final String REASON_DELETE = "delete";
    public static final String REASON_RESTORE = "restore";

    // === Getters / setters ===

    public Long getId() { return id; }
    public Long getArticleId() { return articleId; }
    public void setArticleId(Long articleId) { this.articleId = articleId; }
    public String getArticleFqn() { return articleFqn; }
    public void setArticleFqn(String articleFqn) { this.articleFqn = articleFqn; }
    public Integer getVersionNumber() { return versionNumber; }
    public void setVersionNumber(Integer versionNumber) { this.versionNumber = versionNumber; }
    public Instant getSnapshotAt() { return snapshotAt; }
    public void setSnapshotAt(Instant snapshotAt) { this.snapshotAt = snapshotAt; }
    public String getSnapshotReason() { return snapshotReason; }
    public void setSnapshotReason(String snapshotReason) { this.snapshotReason = snapshotReason; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getResource() { return resource; }
    public void setResource(String resource) { this.resource = resource; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}