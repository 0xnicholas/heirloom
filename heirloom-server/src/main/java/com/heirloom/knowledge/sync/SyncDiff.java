package com.heirloom.knowledge.sync;
import java.util.List;
public record SyncDiff(List<String> newFiles, List<String> changedFiles, List<String> removedFiles, List<String> unchangedFiles, List<String> recreatedFiles, int total) {
    public boolean hasChanges() { return !newFiles.isEmpty()||!changedFiles.isEmpty()||!removedFiles.isEmpty()||!recreatedFiles.isEmpty(); }
}
