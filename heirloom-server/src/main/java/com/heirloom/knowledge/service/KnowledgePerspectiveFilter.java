package com.heirloom.knowledge.service;

import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.security.KnowledgeCapability;
import com.heirloom.security.KnowledgeCapabilityResolver;
import com.heirloom.security.KnowledgeRestrictions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 2.3: SQL-level / result-level permission filter for the Knowledge Base.
 *
 * <p>Combines the actor's {@link KnowledgeCapability} with their
 * {@link KnowledgeRestrictions} to produce an {@link AccessPolicy}, then
 * filters article lists / checks individual articles against it.
 *
 * <p>Convention: actor identifier is the role name (matching how
 * {@link com.heirloom.auth.RoleBasedAuthorizer} resolves actor → role). The
 * SDK / REST headers already feed {@code X-Agent-Role} which the controller
 * layer extracts into the call.
 *
 * <p>Admin roles short-circuit every check. Otherwise: {@code allowedDomains}
 * / {@code allowedTypes} / {@code deniedTypes} / {@code allowDrafts} all apply.
 */
@Component
public class KnowledgePerspectiveFilter {

    private static final Logger log = LoggerFactory.getLogger(KnowledgePerspectiveFilter.class);

    private final KnowledgeCapabilityResolver resolver;

    public KnowledgePerspectiveFilter(KnowledgeCapabilityResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Result of evaluating an actor against the policy. {@code capability} is null
     * when the actor has no knowledge-scoped capability at all.
     */
    public record AccessPolicy(KnowledgeCapability capability,
                               KnowledgeRestrictions restrictions,
                               boolean isAdmin) {

        public boolean canRead() {
            return isAdmin || (capability != null
                    && KnowledgeCapability.covers(capability, KnowledgeCapability.KNOWLEDGE_QUERY));
        }

        public boolean canWrite() {
            return isAdmin || (capability != null
                    && KnowledgeCapability.covers(capability, KnowledgeCapability.KNOWLEDGE_CREATE));
        }
    }

    /**
     * Tri-state visibility verdict for a single article vs. an {@link AccessPolicy}.
     * Lets callers (e.g. {@code KnowledgeArticleResource}) distinguish a policy-blocked
     * read ({@code DENIED}) from a genuine miss ({@code NOT_FOUND}) when emitting
     * audit events.
     */
    public enum Visibility { VISIBLE, DENIED, NOT_FOUND }

    /** Resolve the effective policy for an actor. */
    public AccessPolicy resolvePolicy(String actorId) {
        var resolution = resolver.resolve(actorId);
        return new AccessPolicy(resolution.capability(), resolution.restrictions(),
                resolution.isAdmin());
    }

    /**
     * Filter a list of articles down to those the caller can see.
     * Used for list / search endpoints where a permission trim is cheaper than
     * re-querying.
     */
    public List<KnowledgeArticle> filterByPolicy(List<KnowledgeArticle> articles, AccessPolicy policy) {
        if (articles == null || articles.isEmpty()) return articles;
        if (policy.isAdmin()) return articles;
        if (!policy.canRead()) return List.of();

        KnowledgeRestrictions r = policy.restrictions() != null
                ? policy.restrictions() : KnowledgeRestrictions.NONE;

        return articles.stream()
                .filter(a -> domainAllowed(a, r))
                .filter(a -> typeAllowed(a, r))
                .filter(a -> !deniedType(a, r))
                .filter(a -> statusVisible(a, r))
                .toList();
    }

    /**
     * Tri-state visibility check — for {@code getById} / {@code getByFQN}.
     * Returns {@link Visibility#NOT_FOUND} for a null article (genuine miss),
     * {@link Visibility#DENIED} when the article exists but the policy blocks
     * the read, and {@link Visibility#VISIBLE} when the article should be shown.
     */
    public Visibility checkVisibility(KnowledgeArticle article, AccessPolicy policy) {
        if (article == null) return Visibility.NOT_FOUND;
        if (policy.isAdmin()) return Visibility.VISIBLE;
        if (!policy.canRead()) return Visibility.DENIED;
        KnowledgeRestrictions r = policy.restrictions() != null
                ? policy.restrictions() : KnowledgeRestrictions.NONE;
        boolean ok = domainAllowed(article, r)
                && typeAllowed(article, r)
                && !deniedType(article, r)
                && statusVisible(article, r);
        return ok ? Visibility.VISIBLE : Visibility.DENIED;
    }

    /** Single-article visibility check — for {@code getById} / {@code getByFQN}. */
    public boolean canSee(KnowledgeArticle article, AccessPolicy policy) {
        return checkVisibility(article, policy) == Visibility.VISIBLE;
    }

    /** Maximum graph traversal depth allowed; {@code -1} means no cap. */
    public int maxDepth(AccessPolicy policy) {
        if (policy.isAdmin()) return -1;
        KnowledgeRestrictions r = policy.restrictions();
        return r != null ? r.maxDepth() : -1;
    }

    private static boolean domainAllowed(KnowledgeArticle a, KnowledgeRestrictions r) {
        if (r.allowedDomains() == null || r.allowedDomains().isEmpty()) return true;
        if (r.allowedDomains().contains("*")) return true;
        return r.allowedDomains().contains(a.getDomain());
    }

    private static boolean typeAllowed(KnowledgeArticle a, KnowledgeRestrictions r) {
        if (r.allowedTypes() == null || r.allowedTypes().isEmpty()) return true;
        return r.allowedTypes().contains(a.getType());
    }

    private static boolean deniedType(KnowledgeArticle a, KnowledgeRestrictions r) {
        if (r.deniedTypes() == null || r.deniedTypes().isEmpty()) return false;
        return r.deniedTypes().contains(a.getType());
    }

    private static boolean statusVisible(KnowledgeArticle a, KnowledgeRestrictions r) {
        if (r.allowDrafts()) return true;
        String status = a.getStatus();
        return "PUBLISHED".equalsIgnoreCase(status) || "ARCHIVED".equalsIgnoreCase(status);
    }
}