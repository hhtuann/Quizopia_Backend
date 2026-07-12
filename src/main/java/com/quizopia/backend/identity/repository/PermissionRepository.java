package com.quizopia.backend.identity.repository;

import com.quizopia.backend.identity.domain.model.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for the {@link Permission} entity.
 *
 * <p>Permissions are looked up by their {@code code}, which is seeded by
 * Flyway migrations and stored normalized as uppercase, so case-insensitive
 * matching is intentionally not used.
 */
public interface PermissionRepository extends JpaRepository<Permission, Long> {

    Optional<Permission> findByCode(String code);
}
