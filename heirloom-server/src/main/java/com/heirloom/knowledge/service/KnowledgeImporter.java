package com.heirloom.knowledge.service;
import java.util.List;

public interface KnowledgeImporter {
    String sourceType();
    List<ImportEntry> listEntries(ImportConfig config);
    String convertToMarkdown(ImportEntry entry);

    record ImportEntry(String id, String title, String content, String contentType,
                        String url, List<String> tags, String lastModified) {}
    record ImportConfig(String sourceUrl, java.util.Map<String,String> auth,
                        String spaceFilter, boolean includeAttachments) {}
}
