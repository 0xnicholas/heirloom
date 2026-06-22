package com.heirloom.knowledge.sync;
import java.util.List;
import java.util.Map;
public record ParseResult(Map<String, Object> frontmatter, String body, String frontmatterRaw, List<ParseError> errors) {
    public boolean hasErrors() { return !errors.isEmpty(); }
    public boolean hasType() { return frontmatter.containsKey("type"); }
}
