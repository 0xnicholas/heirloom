package com.heirloom.repository;

import com.heirloom.discovery.domain.DiscoveryReport;
import com.heirloom.entity.EntityRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class DiscoveryReportRepository extends EntityRepository<DiscoveryReport> {
    private final DiscoveryReportJpaRepository jpa;
    public DiscoveryReportRepository(DiscoveryReportJpaRepository jpa) { super(EntityRegistry.DISCOVERY_REPORT, DiscoveryReport.class, jpa); this.jpa = jpa; }
    @PostConstruct void init() { EntityRegistry.register(EntityRegistry.DISCOVERY_REPORT, DiscoveryReport.class, this, null, "{sourceFQN}.{timestamp}", "/v1/discovery/reports"); }
    @Override protected void setFullyQualifiedName(DiscoveryReport r) { r.setFullyQualifiedName(r.getSourceFQN() + "." + System.currentTimeMillis()); }
    @Override protected void prepareInternal(DiscoveryReport r, boolean isUpdate) {}
    public List<DiscoveryReport> findBySourceFQN(String fqn) { return jpa.findBySourceFQNOrderByCreatedAtDesc(fqn); }
}
