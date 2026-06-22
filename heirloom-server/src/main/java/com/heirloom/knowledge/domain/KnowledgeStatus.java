package com.heirloom.knowledge.domain;
import java.util.Set;
public enum KnowledgeStatus {
    DRAFT, REVIEW, PUBLISHED, ARCHIVED;
    public Set<KnowledgeStatus> validTransitions() { return switch(this) {
        case DRAFT -> Set.of(REVIEW, PUBLISHED);
        case REVIEW -> Set.of(PUBLISHED, DRAFT);
        case PUBLISHED -> Set.of(ARCHIVED, DRAFT);
        case ARCHIVED -> Set.of(PUBLISHED);
    };}
    public static KnowledgeStatus fromString(String s) {
        if (s==null||s.isBlank()) return PUBLISHED;
        try { return valueOf(s.toUpperCase()); } catch(IllegalArgumentException e) { return PUBLISHED; }
    }
    public boolean canTransitionTo(KnowledgeStatus target) { return validTransitions().contains(target); }
}
