package com.heirloom.cdc.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "cdc_offsets")
public class CdcOffset {

    @Id
    @Column(name = "source_name", length = 128)
    private String sourceName;

    @Column(nullable = false, length = 64)
    private String lsn;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public CdcOffset() {}

    public CdcOffset(String sourceName, String lsn) {
        this.sourceName = sourceName;
        this.lsn = lsn;
    }

    public String getSourceName() { return sourceName; }
    public void setSourceName(String n) { this.sourceName = n; }

    public String getLsn() { return lsn; }
    public void setLsn(String l) { this.lsn = l; }

    public Instant getUpdatedAt() { return updatedAt; }
}
