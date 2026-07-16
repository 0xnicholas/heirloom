package com.heirloom.metadata.repository;

import com.heirloom.core.entity.EntityRegistry;
import com.heirloom.metadata.domain.ColumnProfileEntity;
import com.heirloom.repository.EntityRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ColumnProfileRepository extends EntityRepository<ColumnProfileEntity> {

    private final ColumnProfileJpaRepository jpa;

    public ColumnProfileRepository(ColumnProfileJpaRepository jpa) {
        super("columnProfile", ColumnProfileEntity.class, jpa);
        this.jpa = jpa;
    }

    @PostConstruct
    void init() {
        EntityRegistry.register("columnProfile", ColumnProfileEntity.class, this, null,
                "{tableFQN}/{columnName}", "/v1/column-profiles");
    }

    @Override
    protected void setFullyQualifiedName(ColumnProfileEntity entity) {
        entity.setFullyQualifiedName(entity.getTableFQN() + "." + entity.getColumnName());
    }

    @Override
    protected void prepareInternal(ColumnProfileEntity entity, boolean isUpdate) {
    }

    public List<ColumnProfileEntity> findByTableFQNAndColumnName(String tableFQN, String columnName) {
        return jpa.findByTableFQNAndColumnNameOrderByProfiledAtDesc(tableFQN, columnName);
    }

    public List<ColumnProfileEntity> findByTableFQN(String tableFQN) {
        return jpa.findByTableFQNOrderByProfiledAtDesc(tableFQN);
    }
}
