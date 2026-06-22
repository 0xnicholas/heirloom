package com.heirloom.knowledge.sync;

import java.time.Instant;
import java.util.*;

public class SyncReport {
    private String sourceFqn, status = "IN_PROGRESS";
    private Instant startedAt = Instant.now(), completedAt;
    private long durationMs;
    private int totalFiles, created, updated, removed, skipped, errors;
    private List<SyncError> errorDetails = new ArrayList<>();

    public record SyncError(String filePath, String error, String errorType) {}

    public static SyncReport start(String s) {
        SyncReport r = new SyncReport();
        r.sourceFqn = s;
        return r;
    }

    public void complete() {
        status = errors > 0 ? "COMPLETED_WITH_ERRORS" : "COMPLETED";
        completedAt = Instant.now();
        durationMs = java.time.Duration.between(startedAt, completedAt).toMillis();
    }

    public void addError(String fp, String e, String t) {
        errors++;
        errorDetails.add(new SyncError(fp, e, t));
    }

    public boolean hasErrors() { return errors > 0; }
    public boolean hasChanges() { return created + updated + removed > 0; }

    public String getSourceFqn() { return sourceFqn; }
    public String getStatus() { return status; }
    public int getTotalFiles() { return totalFiles; }
    public void setTotalFiles(int t) { totalFiles = t; }
    public int getCreated() { return created; }
    public void incrementCreated() { created++; }
    public int getUpdated() { return updated; }
    public void incrementUpdated() { updated++; }
    public int getRemoved() { return removed; }
    public void incrementRemoved() { removed++; }
    public int getSkipped() { return skipped; }
    public void setSkipped(int s) { skipped = s; }
    public int getErrors() { return errors; }
    public List<SyncError> getErrorDetails() { return errorDetails; }
}
