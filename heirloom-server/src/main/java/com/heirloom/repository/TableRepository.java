package com.heirloom.repository;

import com.heirloom.core.entity.EntityRegistry;
import com.heirloom.metadata.domain.TableEntity;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class TableRepository extends EntityRepository<TableEntity> {
    private final TableJpaRepository jpa;
    public TableRepository(TableJpaRepository jpa) { super(EntityRegistry.TABLE, TableEntity.class, jpa); this.jpa = jpa; }

    @PostConstruct void init() { EntityRegistry.register(EntityRegistry.TABLE, TableEntity.class, this, null, "{service}.{db}.{schema}.{name}", "/v1/tables"); }

    @Override protected void setFullyQualifiedName(TableEntity t) {
        String db = t.getDatabaseFQN() != null ? t.getDatabaseFQN().replaceFirst(".*\\.", "") : "default";
        String schema = t.getDatabaseSchemaFQN() != null ? t.getDatabaseSchemaFQN().replaceFirst(".*\\.", "") : "public";
        t.setFullyQualifiedName((t.getDatabaseServiceFQN() != null ? t.getDatabaseServiceFQN() : "unknown") + "." + db + "." + schema + "." + t.getName());
    }
    @Override protected void prepareInternal(TableEntity t, boolean isUpdate) {}

    public List<TableEntity> findAll() { return jpa.findAll(); }
    public Optional<TableEntity> findByFQN(String fqn) { return jpa.findByFullyQualifiedName(fqn); }
}
