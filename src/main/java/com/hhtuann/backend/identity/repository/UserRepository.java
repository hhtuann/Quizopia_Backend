package com.hhtuann.backend.identity.repository;

import com.hhtuann.backend.identity.domain.model.User;
import com.hhtuann.backend.identity.domain.model.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * Repository for the {@link User} entity.
 *
 * <p>Supports case-insensitive lookups and existence checks for username
 * and email, so account creation and authentication can detect duplicates
 * regardless of letter casing.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsernameIgnoreCase(String username);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    /**
     * Admin user search with optional filters (NULL-safe). Used by
     * {@code GET /api/users}. {@code search} matches username/email/display name
     * case-insensitively; {@code status} filters by {@link UserStatus};
     * {@code roleCode} keeps only users with an effective role assignment.
     * Ordered by creation (newest first) then id for stable pagination.
     */
    @Query("""
            select u from User u
            where (:status is null or u.status = :status)
              and ( lower(coalesce(:search, '')) = ''
                    or lower(u.username) like lower(concat('%', :search, '%'))
                    or lower(u.email) like lower(concat('%', :search, '%'))
                    or lower(u.displayName) like lower(concat('%', :search, '%')) )
              and ( :roleCode is null
                    or exists (select 1 from UserRole ur
                               join ur.role r
                               where ur.user.id = u.id
                                 and r.code = :roleCode
                                 and (ur.expiresAt is null or ur.expiresAt > :now)) )
            order by u.createdAt desc, u.id desc
            """)
    Page<User> searchForAdmin(
            @Param("status") UserStatus status,
            @Param("search") String search,
            @Param("roleCode") String roleCode,
            @Param("now") Instant now,
            Pageable pageable);
}
