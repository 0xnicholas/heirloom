package com.heirloom.repository;

import com.heirloom.security.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RoleJpaRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByName(String name);
}
