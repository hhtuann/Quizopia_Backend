package com.hhtuann.backend.identity.repository;

import com.hhtuann.backend.identity.domain.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@link Role} entity.
 *
 * <p>Roles are looked up by their fixed {@code code}, which is seeded by
 * Flyway migrations and stored normalized as uppercase, so case-insensitive
 * matching is intentionally not used.
 */
public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByCode(String code);

    /** All roles in seed order (used by {@code GET /api/roles}). */
    List<Role> findAllByOrderByIdAsc();
}
