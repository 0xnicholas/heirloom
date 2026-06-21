package com.heirloom.schema.repository;

import com.heirloom.schema.domain.ResourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ResourceTypeRepository extends JpaRepository<ResourceType, Long> {

    Optional<ResourceType> findByName(String name);

    boolean existsByName(String name);

    void deleteByName(String name);
}
