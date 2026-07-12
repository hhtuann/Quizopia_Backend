package com.quizopia.backend.identity.repository;

import com.quizopia.backend.identity.domain.model.RolePermission;
import com.quizopia.backend.identity.domain.model.RolePermissionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Repository for the {@link RolePermission} entity, keyed by the composite
 * {@link RolePermissionId} of {@code (role_id, permission_id)}.
 */
public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermissionId> {

    /**
     * Returns the distinct codes of all permissions currently effective for a
     * user, derived from their still-valid role assignments.
     *
     * <p>Since a user may hold several roles that grant the same permission,
     * {@code distinct} is used to collapse duplicates. A role assignment
     * counts as valid when {@code expiresAt} is {@code null} (valid
     * indefinitely) or strictly greater than {@code now}; expired assignments
     * are ignored. The instant {@code now} is supplied by the application
     * rather than the database clock.
     *
     * @param userId the user identifier
     * @param now    the instant to evaluate role assignment expiry against
     * @return the distinct permission codes effective for the user
     */
    @Query("""
            select distinct p.code
            from RolePermission rp
            join rp.permission p
            where exists (
                select 1
                from UserRole ur
                where ur.role = rp.role
                  and ur.user.id = :userId
                  and (ur.expiresAt is null or ur.expiresAt > :now)
            )
            """)
    List<String> findEffectivePermissionCodesByUserId(
            @Param("userId") Long userId,
            @Param("now") Instant now
    );
}
