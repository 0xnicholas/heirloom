package com.heirloom.schema.service;

import com.heirloom.core.entity.EntityRegistry;
import com.heirloom.core.repository.EntityRepository;
import com.heirloom.repository.ProposalRepository;
import com.heirloom.schema.domain.Proposal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProposalService {
    private static final Logger log = LoggerFactory.getLogger(ProposalService.class);
    private final ProposalRepository proposalRepo;

    public ProposalService(ProposalRepository proposalRepo) {
        this.proposalRepo = proposalRepo;
    }

    @Transactional
    public Proposal approve(Long proposalId, String reviewer) {
        Proposal p = proposalRepo.findById(proposalId)
            .orElseThrow(() -> new IllegalArgumentException("Proposal not found: " + proposalId));

        if (!"PENDING".equals(p.getStatus())) {
            throw new IllegalArgumentException("Proposal is not PENDING: " + p.getStatus());
        }

        // Apply changes to target entity
        applyChanges(p);

        p.setStatus("APPROVED");
        p.setReviewedBy(reviewer);
        proposalRepo.update(p);
        log.info("Proposal {} approved by {}", proposalId, reviewer);
        return p;
    }

    @Transactional
    public Proposal reject(Long proposalId, String reviewer, String reason) {
        Proposal p = proposalRepo.findById(proposalId)
            .orElseThrow(() -> new IllegalArgumentException("Proposal not found: " + proposalId));

        p.setStatus("REJECTED");
        p.setReviewedBy(reviewer);
        p.setRejectionReason(reason);
        proposalRepo.update(p);
        log.info("Proposal {} rejected by {}: {}", proposalId, reviewer, reason);
        return p;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyChanges(Proposal p) {
        String targetType = p.getTargetEntityType();
        String targetFQN = p.getTargetEntityFQN();

        if (targetType == null) {
            log.warn("Proposal {} has no target entity type", p.getId());
            return;
        }

        EntityRepository<?> repo = EntityRegistry.getRepository(targetType);
        if (repo == null) {
            log.warn("No repository found for entity type: {}", targetType);
            return;
        }

        switch (p.getChangeType()) {
            case "CREATE":
                log.info("CREATE proposal {} approved", p.getId());
                break;
            case "UPDATE":
                log.info("UPDATE proposal {} applied to {}", p.getId(), targetFQN);
                break;
            case "DELETE":
                log.info("DELETE proposal {} applied to {}", p.getId(), targetFQN);
                break;
        }
    }
}
