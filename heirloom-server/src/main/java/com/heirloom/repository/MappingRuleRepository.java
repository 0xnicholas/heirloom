package com.heirloom.repository;

import com.heirloom.domain.MappingRule;
import com.heirloom.core.entity.EntityRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class MappingRuleRepository extends EntityRepository<MappingRule> {
    private final MappingRuleJpaRepository jpa;
    public MappingRuleRepository(MappingRuleJpaRepository jpa) { super(EntityRegistry.MAPPING_RULE, MappingRule.class, jpa); this.jpa = jpa; }

    @PostConstruct void init() { EntityRegistry.register(EntityRegistry.MAPPING_RULE, MappingRule.class, this, null, "{typeFQN}.{field}", "/v1/mappings"); }

    @Override protected void setFullyQualifiedName(MappingRule r) { r.setFullyQualifiedName(r.getTypeFQN() + "." + r.getFieldName()); }
    @Override protected void prepareInternal(MappingRule r, boolean isUpdate) {}

    public Optional<MappingRule> findByTypeAndField(String typeFQN, String fieldName) { return jpa.findByTypeFQNAndFieldName(typeFQN, fieldName); }
}
