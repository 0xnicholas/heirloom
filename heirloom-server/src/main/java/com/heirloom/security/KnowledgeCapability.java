package com.heirloom.security;

import java.util.Set;

/**
 * Phase 2.3: Knowledge Base capabilities that can be granted to a Role.
 *
 * <p>Hierarchical — higher levels imply all lower levels. So a Role with
 * {@link #KNOWLEDGE_ADMIN} is allowed every knowledge operation; a Role with
 * only {@link #KNOWLEDGE_QUERY} can read but not write.
 *
 * <p>These are checked by {@link KnowledgeCapabilityResolver} when an actor
 * (human or agent) attempts a knowledge operation. The companion
 * {@code knowledgeRestrictions} on the Role narrows the scope further
 * (allowedTypes, deniedTypes, maxDepth).
 */
public enum KnowledgeCapability {
    KNOWLEDGE_QUERY,
    KNOWLEDGE_CREATE,
    KNOWLEDGE_MANAGE,
    KNOWLEDGE_ADMIN;

    /** Capabilities implied by holding {@code cap}. */
    public static Set<KnowledgeCapability> implied(KnowledgeCapability cap) {
        if (cap == null) return Set.of();
        return switch (cap) {
            case KNOWLEDGE_QUERY -> Set.of(KNOWLEDGE_QUERY);
            case KNOWLEDGE_CREATE -> Set.of(KNOWLEDGE_QUERY, KNOWLEDGE_CREATE);
            case KNOWLEDGE_MANAGE -> Set.of(KNOWLEDGE_QUERY, KNOWLEDGE_CREATE, KNOWLEDGE_MANAGE);
            case KNOWLEDGE_ADMIN  -> Set.of(KNOWLEDGE_QUERY, KNOWLEDGE_CREATE, KNOWLEDGE_MANAGE, KNOWLEDGE_ADMIN);
        };
    }

    /** True if {@code holder} implies {@code required}. */
    public static boolean covers(KnowledgeCapability holder, KnowledgeCapability required) {
        if (holder == null || required == null) return false;
        return implied(holder).contains(required);
    }
}