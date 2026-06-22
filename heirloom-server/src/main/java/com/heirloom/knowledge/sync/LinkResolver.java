package com.heirloom.knowledge.sync;
import com.heirloom.knowledge.domain.EntityReference;
import com.heirloom.knowledge.domain.ExternalCitation;
import java.util.*;
import java.util.regex.*;

public class LinkResolver {
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\(([^\\)]*)\\)");

    public record ResolvedLinks(List<EntityReference> references, List<ExternalCitation> citations) {}

    public ResolvedLinks resolve(String body) {
        if (body == null || body.isBlank()) return new ResolvedLinks(List.of(), List.of());
        List<EntityReference> refs = new ArrayList<>();
        List<ExternalCitation> cites = new ArrayList<>();
        Matcher m = LINK.matcher(body);
        while (m.find()) {
            String text = m.group(1), url = m.group(2);
            if (url.startsWith("http://") || url.startsWith("https://")) {
                cites.add(new ExternalCitation(url, text, null));
            } else if (url.endsWith(".md")) {
                String fqn = pathToFqn(url);
                refs.add(new EntityReference(fqn, "knowledgeArticle", text, m.group(0)));
            }
        }
        return new ResolvedLinks(refs, cites);
    }

    static String pathToFqn(String mdPath) {
        String n = mdPath.replaceAll("\\.md$", "").replace('/', '.');
        if (n.startsWith(".")) n = n.substring(1);
        return "knowledge." + n;
    }
}
