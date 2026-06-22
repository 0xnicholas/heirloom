package com.heirloom.security;

import java.util.List;

/**
 * Phase 2.3: optional per-Role restrictions on knowledge operations.
 *
 * <p>Stored as JSONB on the {@code Role} entity. {@code null} or all-default
 * fields means "no restrictions beyond the capability itself".
 *
 * <ul>
 *   <li>{@code allowedDomains}: if non-empty, the actor may only see articles
 *       whose {@code domain} is in this list. Empty = no restriction.</li>
 *   <li>{@code allowedTypes}: same shape as {@code allowedDomains} but
 *       against the article {@code type} field (e.g. "BigQuery Table",
 *       "Agent Experience Note").</li>
 *   <li>{@code deniedTypes}: explicit denylist — takes precedence over
 *       {@code allowedTypes}. Empty = nothing denied.</li>
 *   <li>{@code maxDepth}: cap on graph traversal depth. 0 means no traversal
 *       beyond the root node. Negative = unlimited (default).</li>
 *   <li>{@code allowDrafts}: when false (default), the actor never sees
 *       {@code DRAFT} or {@code REVIEW} articles — only {@code PUBLISHED}
 *       and {@code ARCHIVED}. Set true to let editors see drafts.</li>
 * </ul>
 *
 * <p>All fields are optional; the resolver merges defaults with what's stored.
 */
public record KnowledgeRestrictions(
        List<String> allowedDomains,
        List<String> allowedTypes,
        List<String> deniedTypes,
        int maxDepth,
        boolean allowDrafts) {

    public static final KnowledgeRestrictions NONE =
            new KnowledgeRestrictions(List.of(), List.of(), List.of(), -1, false);

    public boolean isEmpty() {
        return (allowedDomains == null || allowedDomains.isEmpty())
                && (allowedTypes == null || allowedTypes.isEmpty())
                && (deniedTypes == null || deniedTypes.isEmpty())
                && maxDepth <= 0
                && !allowDrafts;
    }
}