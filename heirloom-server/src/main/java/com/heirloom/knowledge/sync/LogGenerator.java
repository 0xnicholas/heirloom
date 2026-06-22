package com.heirloom.knowledge.sync;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;

public class LogGenerator {
    public void appendLog(Path knowledgeRoot, SyncDiff diff, SyncReport report) throws IOException {
        if (!diff.hasChanges() && report.getErrors() == 0) return;

        Path logPath = knowledgeRoot.resolve("log.md");
        String dateHeader = "## " + LocalDate.now();
        StringBuilder entries = new StringBuilder();

        for (String f : diff.newFiles()) entries.append("* **Create**: ").append(f).append("\n");
        for (String f : diff.changedFiles()) entries.append("* **Update**: ").append(f).append("\n");
        for (String f : diff.removedFiles()) entries.append("* **Deprecation**: ").append(f).append("\n");
        for (String f : diff.recreatedFiles()) entries.append("* **Restore**: ").append(f).append("\n");
        for (SyncReport.SyncError e : report.getErrorDetails()) entries.append("* **Error**: ").append(e.filePath()).append(" - ").append(e.error()).append("\n");

        if (entries.isEmpty()) return;

        String existing = Files.exists(logPath) ? Files.readString(logPath) : "# Update Log\n\n";
        String updated;
        if (existing.contains(dateHeader)) {
            int ip = existing.indexOf(dateHeader) + dateHeader.length() + 1;
            int nh = existing.indexOf("\n## ", ip);
            updated = nh > 0 ? existing.substring(0, ip) + entries + existing.substring(nh) : existing.substring(0, ip) + entries + "\n" + existing.substring(ip);
        } else {
            int ip = existing.indexOf("\n") + 1;
            updated = existing.substring(0, ip) + dateHeader + "\n" + entries + "\n" + existing.substring(ip);
        }
        Files.writeString(logPath, updated);
    }
}
