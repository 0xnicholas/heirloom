package com.heirloom.repository;

import com.heirloom.security.domain.Function;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface FunctionJpaRepository extends JpaRepository<Function, Long> {
    Optional<Function> findByName(String name);
}
