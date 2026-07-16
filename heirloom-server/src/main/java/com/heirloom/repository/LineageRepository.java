package com.heirloom.repository;

import com.heirloom.core.entity.EntityRegistry;
import com.heirloom.metadata.domain.LineageEntity;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class LineageRepository extends EntityRepository<LineageEntity> {
    private final LineageJpaRepository jpa;
    public LineageRepository(LineageJpaRepository jpa) { super(EntityRegistry.LINEAGE, LineageEntity.class, jpa); this.jpa = jpa; }
    @PostConstruct void init() { EntityRegistry.register(EntityRegistry.LINEAGE, LineageEntity.class, this, null, "{fromFQN}.{toFQN}.{type}", "/v1/lineage"); }
    @Override protected void setFullyQualifiedName(LineageEntity l) { l.setFullyQualifiedName(l.getFromEntityFQN() + "." + l.getToEntityFQN() + "." + l.getLineageType()); }
    @Override protected void prepareInternal(LineageEntity l, boolean isUpdate) {}

    public List<LineageEntity> findAll() { return jpa.findAll(); }
}
