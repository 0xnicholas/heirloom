package com.heirloom.knowledge.service;

import org.slf4j.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.regex.*;

/**
 * Updates only HEIRLOOM_AUTO_START/END blocks in existing knowledge files,
 * preserving human-written content outside those blocks.
 * 
 * Used by KnowledgeBootstrapper when re-discovering tables whose
 * knowledge drafts already exist.
 */
public class MetadataBlockUpdater {
    private static final Logger log = LoggerFactory.getLogger(MetadataBlockUpdater.class);
    private static final Pattern AUTO_BLOCK = Pattern.compile(
        "<!-- HEIRLOOM_AUTO_START: (\\w+) -->(.*?)<!-- HEIRLOOM_AUTO_END: \\1 -->",
        Pattern.DOTALL);

    /**
     * Update a specific AUTO block in an existing markdown file.
     * @param filePath path to the .md file
     * @param blockName the block name (e.g., "schema")
     * @param newContent new content for the block
     * @return true if the file was modified
     */
    public boolean updateBlock(Path filePath, String blockName, String newContent) throws IOException {
        if (!Files.exists(filePath)) return false;

        String existing = Files.readString(filePath);
        String startTag = "<!-- HEIRLOOM_AUTO_START: " + blockName + " -->";
        String endTag = "<!-- HEIRLOOM_AUTO_END: " + blockName + " -->";

        int start = existing.indexOf(startTag);
        int end = existing.indexOf(endTag);

        if (start < 0 || end < start) {
            // Block doesn't exist — append at end
            String updated = existing.stripTrailing() + "\n\n" + startTag + "\n" + newContent + "\n" + endTag + "\n";
            Files.writeString(filePath, updated);
            return true;
        }

        String replacement = startTag + "\n" + newContent + "\n" + endTag;
        String updated = existing.substring(0, start) + replacement + existing.substring(end + endTag.length());

        if (!updated.equals(existing)) {
            Files.writeString(filePath, updated);
            return true;
        }
        return false;
    }

    /**
     * Extract the content of a specific AUTO block.
     * @return block content, or null if block doesn't exist
     */
    public String getBlockContent(Path filePath, String blockName) throws IOException {
        if (!Files.exists(filePath)) return null;
        String content = Files.readString(filePath);
        String startTag = "<!-- HEIRLOOM_AUTO_START: " + blockName + " -->";
        String endTag = "<!-- HEIRLOOM_AUTO_END: " + blockName + " -->";
        int start = content.indexOf(startTag);
        int end = content.indexOf(endTag);
        if (start < 0 || end < start) return null;
        return content.substring(start + startTag.length(), end).strip();
    }
}
