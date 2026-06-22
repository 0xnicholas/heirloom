package com.heirloom.knowledge.service;

import com.heirloom.domain.ChangeEvent;
import com.heirloom.entity.HeirloomEntity;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.domain.KnowledgeStatus;
import com.heirloom.knowledge.repository.KnowledgeArticleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Phase 3.3: Auto-generate draft {@link KnowledgeArticle}s from agent activity.
 *
 * <p>Whenever an agent (caller with {@code X-Agent-Id} header) successfully
 * creates / updates / deletes an entity, capture the experience as a draft
 * knowledge article. Human reviewers promote them via the existing workflow.
 *
 * <p>Idempotent: a second capture for the same entity + actor + event-type
 * does not create a duplicate. The natural key is
 * {@code knowledge.agent.{caller}.{eventType}.{entityFQN}}.
 *
 * <p>The draft body is a small markdown summary: who did what to which entity,
 * when. It deliberately does NOT speculate about meaning — a human reviews
 * and enriches before promotion.
 */
@Service
public class AgentExperienceCapture {

    private static final Logger log = LoggerFactory.getLogger(AgentExperienceCapture.class);
    /** Synthetic source tag so all agent-captured articles cluster in queries. */
    private static final String SOURCE_FQN = "agent-capture";
    /** Only act on these event types — denials are noise, GETs aren't actions. */
    private static final List<ChangeEvent.EventType> CAPTURED_EVENTS =
            List.of(ChangeEvent.EventType.ENTITY_CREATED,
                    ChangeEvent.EventType.ENTITY_UPDATED,
                    ChangeEvent.EventType.ENTITY_DELETED);

    private final KnowledgeArticleRepository articleRepo;

    public AgentExperienceCapture(KnowledgeArticleRepository articleRepo) {
        this.articleRepo = articleRepo;
    }

    /**
     * Returns true when a draft was created, false when skipped
     * (non-agent caller, non-action event, duplicate, or missing FQN).
     */
    @Transactional
    public boolean captureIfAgent(HeirloomEntity entity, ChangeEvent.EventType eventType, String caller) {
        if (caller == null || !caller.startsWith("agent:")) return false;
        if (entity == null || entity.getFullyQualifiedName() == null) return false;
        if (!CAPTURED_EVENTS.contains(eventType)) return false;

        String filePath = syntheticFilePath(caller, eventType, entity);
        String sourceFqn = SOURCE_FQN;

        Optional<KnowledgeArticle> existing =
                articleRepo.findByFilePath(sourceFqn, filePath);
        if (existing.isPresent()) {
            log.debug("Skipping duplicate agent-capture for {}", filePath);
            return false;
        }

        KnowledgeArticle draft = buildDraft(entity, eventType, caller, sourceFqn, filePath);
        articleRepo.create(draft);
        log.info("Captured agent experience: {} on {} (article FQN: {})",
                eventType, entity.getFullyQualifiedName(), draft.getFullyQualifiedName());
        return true;
    }

    private static String syntheticFilePath(String caller, ChangeEvent.EventType eventType,
                                            HeirloomEntity entity) {
        // Lower-case + escape to a filesystem-safe path component.
        String actor = caller.replace("agent:", "").toLowerCase().replaceAll("[^a-z0-9_-]", "_");
        String fqn = entity.getFullyQualifiedName().toLowerCase().replaceAll("[^a-z0-9._-]", "_");
        return String.format("agents/%s/%s/%s.md", actor, eventType.name().toLowerCase(), fqn);
    }

    private static KnowledgeArticle buildDraft(HeirloomEntity entity,
                                               ChangeEvent.EventType eventType,
                                               String caller,
                                               String sourceFqn,
                                               String filePath) {
        KnowledgeArticle draft = new KnowledgeArticle();
        draft.setSourceFqn(sourceFqn);
        draft.setFilePath(filePath);
        draft.setType("Agent Experience Note");
        draft.setTitle(humanise(eventType) + ": " + entity.getFullyQualifiedName());
        draft.setDescription("Auto-captured from agent activity. Review and enrich before promotion.");
        draft.setAuthor(caller);
        draft.setResource("@" + entity.getFullyQualifiedName());
        draft.setStatus(KnowledgeStatus.DRAFT.name());
        draft.setSyncStatus("OK");
        draft.setCreatedAt(Instant.now());
        draft.setUpdatedAt(Instant.now());
        draft.setLastSyncedAt(Instant.now());
        draft.setBody(renderBody(entity, eventType, caller));
        draft.setFrontmatter(java.util.Map.of(
                "type", "Agent Experience Note",
                "agent", caller,
                "event", eventType.name(),
                "resource", "@" + entity.getFullyQualifiedName(),
                "auto_captured_at", Instant.now().toString()));
        return draft;
    }

    private static String renderBody(HeirloomEntity entity, ChangeEvent.EventType eventType, String caller) {
        return String.format("""
                # %s

                **Agent:** `%s`
                **Action:** %s
                **Resource:** `@%s`
                **Captured at:** %s

                <!-- HEIRLOOM_AUTO_START: meta -->
                <!-- HEIRLOOM_AUTO_END: meta -->

                ## Context

                This note was auto-generated when `%s` performed a `%s` against `@%s`.
                Review the change in the Event Log and enrich this draft before
                promoting it to REVIEW then PUBLISHED.
                """,
                humanise(eventType),
                caller,
                eventType.name().toLowerCase().replace('_', ' '),
                entity.getFullyQualifiedName(),
                Instant.now(),
                caller,
                eventType.name().toLowerCase().replace('_', ' '),
                entity.getFullyQualifiedName());
    }

    private static String humanise(ChangeEvent.EventType t) {
        return switch (t) {
            case ENTITY_CREATED -> "Created";
            case ENTITY_UPDATED -> "Updated";
            case ENTITY_DELETED -> "Deleted";
            default -> t.name();
        };
    }
}