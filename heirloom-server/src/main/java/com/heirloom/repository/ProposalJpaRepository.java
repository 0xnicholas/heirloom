package com.heirloom.repository;

import com.heirloom.schema.domain.Proposal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProposalJpaRepository extends JpaRepository<Proposal, Long> {
    List<Proposal> findByStatus(String status);
    List<Proposal> findByTargetEntityType(String type);
}
