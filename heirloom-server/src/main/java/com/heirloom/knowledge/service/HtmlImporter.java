package com.heirloom.knowledge.service;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class HtmlImporter implements KnowledgeImporter {
    @Override public String sourceType() { return "html"; }

    @Override public List<ImportEntry> listEntries(ImportConfig config) {
        return List.of(); // HTML importer works on explicitly provided content
    }

    @Override public String convertToMarkdown(ImportEntry entry) {
        String html = entry.content();
        if (html == null) return "";
        // Simple HTML → Markdown conversion
        return html
            .replaceAll("(?is)<h1[^>]*>(.*?)</h1>", "# $1\n")
            .replaceAll("(?is)<h2[^>]*>(.*?)</h2>", "## $1\n")
            .replaceAll("(?is)<h3[^>]*>(.*?)</h3>", "### $1\n")
            .replaceAll("(?is)<b>(.*?)</b>", "**$1**")
            .replaceAll("(?is)<strong>(.*?)</strong>", "**$1**")
            .replaceAll("(?is)<i>(.*?)</i>", "*$1*")
            .replaceAll("(?is)<em>(.*?)</em>", "*$1*")
            .replaceAll("(?is)<code>(.*?)</code>", "`$1`")
            .replaceAll("(?is)<pre[^>]*>(.*?)</pre>", "```\n$1\n```")
            .replaceAll("(?is)<a\\s+href=\"([^\"]*)\"[^>]*>(.*?)</a>", "[$2]($1)")
            .replaceAll("(?is)<img\\s+src=\"([^\"]*)\"[^>]*/?>", "![]($1)")
            .replaceAll("(?is)<li>(.*?)</li>", "- $1\n")
            .replaceAll("(?is)<br\\s*/?>", "\n")
            .replaceAll("<[^>]+>", "")
            .replaceAll("&amp;", "&")
            .replaceAll("&lt;", "<")
            .replaceAll("&gt;", ">")
            .replaceAll("&quot;", "\"");
    }
}
