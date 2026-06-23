package com.heirloom.knowledge.service;

import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.domain.KnowledgeStatus;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import com.heirloom.knowledge.repository.KnowledgeArticleRepository;
import com.heirloom.repository.ProposalRepository;
import com.heirloom.schema.domain.Proposal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Phase 4.1 Knowledge approval workflow.
 *
 * <p>Drives status transitions on {@link KnowledgeArticle} through the
 * {@link KnowledgeStatus} state machine. Some transitions are auto-approved
 * (the author moving their own draft forward) and others require a
 * {@link Proposal} that a human reviewer must approve.
 *
 * <p>Rules:
 * <ul>
 *   <li>DRAFT → REVIEW: auto-approved (author moves own work forward).
 *       Also auto-approved for REVIEW → DRAFT (back to drafting).</li>
 *   <li>REVIEW → PUBLISHED: requires Proposal of type
 *       {@code REVIEW_KNOWLEDGE_ARTICLE} — a reviewer must approve before
 *       the article becomes visible to non-author readers.</li>
 *   <li>PUBLISHED → ARCHIVED: requires Proposal (deletion of knowledge
 *       is never silent).</li>
 *   <li>ARCHIVED → PUBLISHED: requires Proposal (un-archiving is
 *       equivalent to republishing).</li>
 *   <li>Any other transition: rejected with IllegalStateTransition.</li>
 * </ul>
 *
 * <p>On every successful transition (auto or proposal-driven) a
 * {@link com.heirloom.knowledge.domain.KnowledgeArticleVersion} snapshot
 * is captured by the underlying {@link KnowledgeArticleRepository}, so
 * the history stays queryable.
 */
@Service
public class KnowledgeWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeWorkflowService.class);

    public static final String CHANGE_TYPE_REVIEW = "REVIEW_KNOWLEDGE_ARTICLE";

    private final KnowledgeArticleRepository articleRepo;
    private final KnowledgeArticleJpaRepository articleJpa;
    private final ProposalRepository proposalRepo;
    private final com.heirloom.repository.ProposalJpaRepository proposalJpa;

    public KnowledgeWorkflowService(KnowledgeArticleRepository articleRepo,
                                    KnowledgeArticleJpaRepository articleJpa,
                                    ProposalRepository proposalRepo,
                                    com.heirloom.repository.ProposalJpaRepository proposalJpa) {
        this.articleRepo = articleRepo;
        this.articleJpa = articleJpa;
        this.proposalRepo = proposalRepo;
        this.proposalJpa = proposalJpa;
    }

    /**
     * Request a status transition. Auto-approved transitions return
     * {@code TransitionResult.autoApproved=true}; sensitive transitions
     * create a Proposal and return its id for the caller to track.
     */
    @Transactional
    public TransitionResult requestTransition(long articleId, String targetStatusName,
                                              String proposedBy, String comment) {
        KnowledgeArticle article = articleJpa.findById(articleId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Article not found: " + articleId));

        KnowledgeStatus current = KnowledgeStatus.fromString(article.getStatus());
        KnowledgeStatus target = parseTarget(targetStatusName);

        if (current == target) {
            return new TransitionResult(false, "ALREADY_IN_TARGET_STATUS", current, target, null);
        }

        if (!current.canTransitionTo(target)) {
            throw new IllegalStateTransition(current, target);
        }

        if (isAutoApproved(current, target)) {
            applyTransition(article, target, "auto:" + proposedBy, comment);
            return new TransitionResult(true, "AUTO_APPROVED", current, target, null);
        }

        // Sensitive transition — create a Proposal for human review.
        Proposal proposal = new Proposal();
        proposal.setName("review-" + article.getName() + "-" + target.name().toLowerCase());
        proposal.setFullyQualifiedName(article.getFullyQualifiedName() + ".review-" + target.name().toLowerCase());
        proposal.setTargetEntityType("knowledgeArticle");
        proposal.setTargetEntityFQN(article.getFullyQualifiedName());
        proposal.setChangeType(CHANGE_TYPE_REVIEW);
        proposal.setStatus("PENDING");
        proposal.setSource("knowledge-workflow");
        proposal.setProposedBy(proposedBy != null ? proposedBy : "anonymous");
        proposal.setDescription(buildDescription(article, current, target, comment));
        Proposal saved = proposalRepo.create(proposal);

        log.info("Knowledge transition {} → {} requires review; proposal {} created",
                current, target, saved.getId());
        return new TransitionResult(false, "PROPOSAL_PENDING", current, target, saved.getId());
    }

    /**
     * Apply an approved proposal — actually perform the status transition
     * and capture a version snapshot. Idempotent: re-applying an already-
     * applied proposal is a no-op (returns the current state).
     */
    @Transactional
    public TransitionResult applyApprovedProposal(long proposalId, String reviewer) {
        Proposal proposal = proposalJpa().findById(proposalId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Proposal not found: " + proposalId));
        if (!"APPROVED".equalsIgnoreCase(proposal.getStatus())) {
            throw new IllegalStateException(
                    "Proposal " + proposalId + " is not APPROVED (current: "
                            + proposal.getStatus() + ")");
        }
        if (!CHANGE_TYPE_REVIEW.equals(proposal.getChangeType())) {
            throw new IllegalStateException(
                    "Proposal " + proposalId + " is not a knowledge review proposal");
        }

        // Re-derive target from the proposal name suffix (review-published / review-archived / review-draft).
        KnowledgeArticle article = articleJpa.findByFullyQualifiedName(proposal.getTargetEntityFQN())
                .orElseThrow(() -> new IllegalStateException(
                        "Article gone: " + proposal.getTargetEntityFQN()));

        String nameSuffix = proposal.getName();
        KnowledgeStatus target = inferTarget(nameSuffix, proposal.getDescription());

        KnowledgeStatus current = KnowledgeStatus.fromString(article.getStatus());
        if (current == target) {
            return new TransitionResult(true, "ALREADY_APPLIED", current, target, proposalId);
        }
        applyTransition(article, target, "approved-by:" + reviewer, proposal.getDescription());
        return new TransitionResult(true, "APPLIED", current, target, proposalId);
    }

    // === Helpers ===

    private void applyTransition(KnowledgeArticle article, KnowledgeStatus target,
                                 String changeHashSuffix, String comment) {
        article.setStatus(target.name());
        article.setUpdatedAt(Instant.now());
        // articleRepo.update captures the snapshot before mutating.
        articleRepo.update(article);
        log.info("Knowledge transition applied: {} → {} ({}); comment={}",
                article.getFullyQualifiedName(),
                target, changeHashSuffix, comment);
    }

    private static boolean isAutoApproved(KnowledgeStatus current, KnowledgeStatus target) {
        // DRAFT ↔ REVIEW is author-driven; the rest needs review.
        if (current == KnowledgeStatus.DRAFT && target == KnowledgeStatus.REVIEW) return true;
        if (current == KnowledgeStatus.REVIEW && target == KnowledgeStatus.DRAFT) return true;
        return false;
    }

    private static KnowledgeStatus parseTarget(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("targetStatus must not be blank");
        }
        try {
            return KnowledgeStatus.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown target status: " + name);
        }
    }

    private static KnowledgeStatus inferTarget(String proposalName, String description) {
        // Proposal name is e.g. "review-ArticleName-published" — split on '-'.
        if (proposalName != null) {
            for (KnowledgeStatus s : KnowledgeStatus.values()) {
                if (proposalName.toUpperCase().endsWith("-" + s.name())) return s;
            }
        }
        // Fallback: scan description for status keyword.
        if (description != null) {
            for (KnowledgeStatus s : KnowledgeStatus.values()) {
                if (description.contains(s.name())) return s;
            }
        }
        throw new IllegalStateException(
                "Cannot infer target status from proposal name=" + proposalName);
    }

    private static String buildDescription(KnowledgeArticle article,
                                           KnowledgeStatus from, KnowledgeStatus to,
                                           String comment) {
        return String.format(
                "Promote %s from %s to %s. Reason: %s",
                article.getFullyQualifiedName(), from, to,
                comment != null ? comment : "(none)");
    }

    /** Unwrap the underlying JPA repository for the apply path. */
    private com.heirloom.repository.ProposalJpaRepository proposalJpa() {
        return proposalJpa;
    }

    // === Result + exceptions ===

    public record TransitionResult(boolean applied, String outcome,
                                   KnowledgeStatus fromStatus, KnowledgeStatus toStatus,
                                   Long proposalId) {}

    public static class IllegalStateTransition extends RuntimeException {
        private final KnowledgeStatus from;
        private final KnowledgeStatus to;
        public IllegalStateTransition(KnowledgeStatus from, KnowledgeStatus to) {
            super("Invalid transition: " + from + " → " + to);
            this.from = from;
            this.to = to;
        }
        public KnowledgeStatus getFrom() { return from; }
        public KnowledgeStatus getTo() { return to; }
    }
}