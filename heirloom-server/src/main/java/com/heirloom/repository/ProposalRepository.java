package com.heirloom.repository;

import com.heirloom.schema.domain.Proposal;
import com.heirloom.entity.EntityRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;

@Repository
public class ProposalRepository extends EntityRepository<Proposal> {
    private final ProposalJpaRepository jpa;

    public ProposalRepository(ProposalJpaRepository jpa) {
        super(EntityRegistry.PROPOSAL, Proposal.class, jpa);
        this.jpa = jpa;
    }

    @PostConstruct void init() { EntityRegistry.register(EntityRegistry.PROPOSAL, Proposal.class, this, null, "{typeFQN}.proposal-{uuid}", "/v1/proposals"); }

    @Override protected void setFullyQualifiedName(Proposal p) {
        p.setFullyQualifiedName((p.getTargetEntityFQN() != null ? p.getTargetEntityFQN() : "new") + ".proposal-" + System.currentTimeMillis());
    }
    @Override protected void prepareInternal(Proposal p, boolean isUpdate) {}
}
