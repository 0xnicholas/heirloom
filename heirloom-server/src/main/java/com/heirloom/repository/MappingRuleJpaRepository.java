package com.heirloom.repository;

import com.heirloom.domain.MappingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MappingRuleJpaRepository extends JpaRepository<MappingRule, Long> {
    Optional<MappingRule> findByTypeFQNAndFieldName(String typeFQN, String fieldName);
}
