package com.hhtuann.backend.identity.repository;

import com.hhtuann.backend.identity.domain.model.Role;
import com.hhtuann.backend.identity.domain.model.UserRole;
import com.hhtuann.backend.identity.domain.model.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Repository for the {@link UserRole} entity, keyed by the composite
 * {@link UserRoleId} of {@code (user_id, role_id)}.
 *
 * <p>Queries return only the codes of roles that are still effective for a
 * given user, rather than the {@link UserRole} or {@link Role} entities.
 */
public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {

    /**
     * Returns the codes of all roles currently effective for a user.
     *
     * <p>An assignment is effective when {@code expiresAt} is {@code null}
     * (valid indefinitely) or strictly greater than {@code now}. Assignments
     * that have expired are excluded. The instant {@code now} is supplied by
     * the application instead of relying on the database clock.
     *
     * @param userId the user identifier
     * @param now    the instant to evaluate expiry against
     * @return the role codes that are still effective for the user
     */
    @Query("""
            select r.code
            from UserRole ur
            join ur.role r
            where ur.user.id = :userId
              and (ur.expiresAt is null or ur.expiresAt > :now)
            """)
    List<String> findActiveRoleCodesByUserId(
            @Param("userId") Long userId,
            @Param("now") Instant now
    );

    /** Returns the user IDs that currently hold the given role code. */
    @Query("""
            select ur.user.id
            from UserRole ur
            join ur.role r
            where r.code = :roleCode
              and (ur.expiresAt is null or ur.expiresAt > :now)
            """)
    List<Long> findUserIdsByRoleCode(
            @Param("roleCode") String roleCode,
            @Param("now") Instant now
    );
}
