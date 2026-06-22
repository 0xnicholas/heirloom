package com.heirloom.repository;

import com.heirloom.security.domain.Action;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ActionJpaRepository extends JpaRepository<Action, Long> {
    Optional<Action> findByName(String name);
}
