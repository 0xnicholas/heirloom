package com.heirloom.repository;

import com.heirloom.discovery.domain.DiscoveryReport;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DiscoveryReportJpaRepository extends JpaRepository<DiscoveryReport, Long> {
    List<DiscoveryReport> findBySourceFQNOrderByCreatedAtDesc(String sourceFQN);
}
