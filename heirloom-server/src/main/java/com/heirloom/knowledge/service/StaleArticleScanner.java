package com.heirloom.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.domain.KnowledgeStatus;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import com.heirloom.schema.domain.Proposal;
import com.heirloom.repository.ProposalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Phase 2.6: surface {@code published} KnowledgeArticles that look stale so a
 * human can archive them.
 *
 * <p>Stale criteria (all must hold):
 * <ul>
 *   <li>Status is {@code PUBLISHED}</li>
 *   <li>{@code updatedAt} is older than {@link #DEFAULT_STALE_AFTER} (configurable per-call)</li>
 *   <li>Has fewer than {@link #DEFAULT_MAX_REFERENCES} inbound references
 *       (links pointing TO this article from other articles — i.e. nobody cites it)</li>
 * </ul>
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>dry-run</b> (default) — returns candidates without mutating anything;
 *       safe to call from a dashboard preview button.</li>
 *   <li><b>commit</b> — creates a {@link Proposal} of change-type
 *       {@code ARCHIVE_KNOWLEDGE_ARTICLE} for each candidate. A human reviews
 *       the proposal in the existing governance workflow (Phase 2.6 already
 *       wires proposal → review → published/archived transition).</li>
 * </ul>
 *
 * <p>This scanner never archives articles directly — archiving without human
 * approval would lose searchable knowledge. The proposal is the audit trail.
 */
@Service
public class StaleArticleScanner {

    private static final Logger log = LoggerFactory.getLogger(StaleArticleScanner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Default staleness threshold: 180 days without updates. */
    public static final Duration DEFAULT_STALE_AFTER = Duration.ofDays(180);
    /** Default: articles with < N inbound refs are considered "uncited". */
    public static final int DEFAULT_MAX_REFERENCES = 1;
    /** Safety cap to avoid scanning the whole table in pathological cases. */
    public static final int SCAN_LIMIT = 5_000;

    public static final String CHANGE_TYPE_ARCHIVE = "ARCHIVE_KNOWLEDGE_ARTICLE";

    private final KnowledgeArticleJpaRepository articleRepo;
    private final ProposalRepository proposalRepo;

    public StaleArticleScanner(KnowledgeArticleJpaRepository articleRepo,
                               ProposalRepository proposalRepo) {
        this.articleRepo = articleRepo;
        this.proposalRepo = proposalRepo;
    }

    /**
     * Scan for stale articles.
     *
     * @param staleAfter how old {@code updatedAt} must be to qualify
     * @param maxReferences inbound references must be strictly below this
     * @param dryRun if true, don't create Proposals
     * @param caller agent identifier for the audit trail
     * @return list of candidates (with the resulting Proposal id when not dry-run)
     */
    public List<StaleCandidate> scan(Duration staleAfter, int maxReferences,
                                     boolean dryRun, String caller) {
        Instant cutoff = Instant.now().minus(staleAfter);

        // Take the recent-N published slice; we still filter inside the loop.
        // (A dedicated repo query is the next-step optimisation once volume matters.)
        List<KnowledgeArticle> published = articleRepo.findAll().stream()
                .filter(a -> !Boolean.TRUE.equals(a.getDeleted()))
                .filter(a -> KnowledgeStatus.PUBLISHED.name().equalsIgnoreCase(a.getStatus()))
                .filter(a -> a.getUpdatedAt() != null && a.getUpdatedAt().isBefore(cutoff))
                .limit(SCAN_LIMIT)
                .toList();

        List<StaleCandidate> candidates = new ArrayList<>();
        for (KnowledgeArticle article : published) {
            int refs = countInboundReferences(article);
            if (refs >= maxReferences) continue;

            StaleCandidate candidate = new StaleCandidate(
                    article.getFullyQualifiedName(),
                    article.getTitle(),
                    article.getUpdatedAt(),
                    refs);

            if (!dryRun) {
                Optional<Proposal> created = createArchiveProposal(article, caller);
                created.ifPresent(p -> candidate.setProposalId(p.getId()));
            }
            candidates.add(candidate);
        }

        log.info("Stale scan (dryRun={}): found {} candidates out of {} published articles older than {}",
                dryRun, candidates.size(), published.size(), cutoff);
        return candidates;
    }

    /** Count how many other articles cite this one via references_jsonb. */
    private int countInboundReferences(KnowledgeArticle article) {
        String targetFqn = article.getFullyQualifiedName();
        if (targetFqn == null) return 0;
        String filter = "[{\"fqn\":\"" + targetFqn.replace("\"", "\\\"") + "\"}]";
        return articleRepo.findByEntityRef(filter).size();
    }

    private Optional<Proposal> createArchiveProposal(KnowledgeArticle article, String caller) {
        Proposal p = new Proposal();
        p.setName("archive-" + article.getName());
        p.setFullyQualifiedName(article.getFullyQualifiedName() + ".archive-proposal");
        p.setTargetEntityType("knowledgeArticle");
        p.setTargetEntityFQN(article.getFullyQualifiedName());
        p.setChangeType(CHANGE_TYPE_ARCHIVE);
        p.setStatus("PENDING");
        p.setSource("stale-scanner");
        p.setProposedBy(caller != null ? caller : "stale-scanner");
        p.setDescription(String.format(
                "Auto-suggested archive: published %s, last updated %s, %d inbound refs.",
                article.getFullyQualifiedName(),
                article.getUpdatedAt(),
                countInboundReferences(article)));
        try {
            // proposedChanges is JSONB — encode the intended transition so the
            // reviewer can see what would happen.
            p.setProposedChanges(MAPPER.writeValueAsString(java.util.Map.of(
                    "fromStatus", article.getStatus(),
                    "toStatus", KnowledgeStatus.ARCHIVED.name(),
                    "reason", "stale-scanner")));
        } catch (Exception e) {
            log.warn("Could not encode proposedChanges for {}: {}",
                    article.getFullyQualifiedName(), e.getMessage());
        }
        return Optional.ofNullable(proposalRepo.create(p));
    }

    /** A candidate article that may be archived, plus the resulting Proposal
     *  id when the scanner ran in commit mode. Plain class so the id is settable. */
    public static final class StaleCandidate {
        private final String fullyQualifiedName;
        private final String title;
        private final Instant lastUpdatedAt;
        private final int inboundReferences;
        private Long proposalId;

        public StaleCandidate(String fullyQualifiedName, String title,
                              Instant lastUpdatedAt, int inboundReferences) {
            this.fullyQualifiedName = fullyQualifiedName;
            this.title = title;
            this.lastUpdatedAt = lastUpdatedAt;
            this.inboundReferences = inboundReferences;
        }

        public String getFullyQualifiedName() { return fullyQualifiedName; }
        public String getTitle() { return title; }
        public Instant getLastUpdatedAt() { return lastUpdatedAt; }
        public int getInboundReferences() { return inboundReferences; }
        public Long getProposalId() { return proposalId; }
        public void setProposalId(Long proposalId) { this.proposalId = proposalId; }
    }
}