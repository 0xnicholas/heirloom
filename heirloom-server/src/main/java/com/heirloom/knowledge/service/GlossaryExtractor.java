package com.heirloom.knowledge.service;
import java.util.*;
import java.util.regex.*;

public class GlossaryExtractor {
    // Pattern: **Bold Term** or **Bold Term (English)**: definition
    private static final Pattern GLOSSARY = Pattern.compile(
        "\\*\\*(.+?)\\*\\*\\s*[：:]\\s*(.+?)(?=\\n\\n|\\n\\*\\*|$)",
        Pattern.DOTALL);

    public record ExtractedTerm(String term, String englishName, String definition,
                                 String sourceArticle, int confidence) {}

    public List<ExtractedTerm> extract(String body, String articleFqn) {
        if (body == null || body.isBlank()) return List.of();
        List<ExtractedTerm> terms = new ArrayList<>();
        Matcher m = GLOSSARY.matcher(body);
        while (m.find()) {
            String rawTerm = m.group(1).strip();
            String definition = m.group(2).strip();
            // Parse "Term (English)" pattern within the bold text
            String term, english = null;
            Matcher termEnglish = Pattern.compile("^(.+?)\\s*\\((.+?)\\)$").matcher(rawTerm);
            if (termEnglish.matches()) { term = termEnglish.group(1).strip(); english = termEnglish.group(2).strip(); }
            else { term = rawTerm; }
            if (definition.length() < 5) continue; // too short to be meaningful
            int confidence = calculateConfidence(term, definition);
            terms.add(new ExtractedTerm(term, english, definition, articleFqn, confidence));
        }
        return terms;
    }

    private int calculateConfidence(String term, String definition) {
        int score = 50;
        if (term.length() >= 2) score += 10;
        if (definition.length() >= 20) score += 20;
        if (definition.contains(" ") || definition.contains("，")) score += 10;
        return Math.min(score, 95);
    }
}
