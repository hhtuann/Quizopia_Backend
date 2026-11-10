package com.quizopia.backend.community.repository;

import com.quizopia.backend.community.domain.model.CommunityMemberProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Repository for {@link CommunityMemberProfile} (ADR-007 §4, V31).
 *
 * <p>The #65 surface is deliberately minimal: reads by owner key. The
 * §4 first-comment upsert ({@code INSERT … ON CONFLICT (user_id) DO
 * NOTHING} + re-select, then alias-change UPDATE) lands with #66's
 * comment create path — as does the ci-unique collision recovery that
 * maps {@code uk_community_member_profiles_alias_ci} violations to 409
 * COMMUNITY_ALIAS_TAKEN. No delete path exists: the profile outlives
 * the comments that created it (users RESTRICT).
 *
 * <p>#66 write surface (ADR-007 §4): the idempotent first-comment
 * profile upsert {@link #insertProfileIfAbsent}
 * ({@code ON CONFLICT (user_id) DO NOTHING} — concurrent first
 * comments from one user collapse onto one row) and the alias-change
 * {@link #updateAliasByUserId}, both followed by a re-select of the
 * STORED alias. A different-user collision on the ci-unique alias
 * aborts the enclosing comment transaction on
 * {@code uk_community_member_profiles_alias_ci} → 409
 * COMMUNITY_ALIAS_TAKEN. The three ci probes back the §4
 * impersonation defenses (any username, any publication author_alias)
 * and the reverse publication-side check (any member alias).
 */
public interface CommunityMemberProfileRepository
        extends JpaRepository<CommunityMemberProfile, Long> {

    /** The caller's stored pseudonym, if they have commented yet. */
    Optional<CommunityMemberProfile> findByUserId(Long userId);

    /**
     * The §4 first-comment upsert: creates the profile unless one
     * already exists for the user. Returns 1 when this call created
     * the row, 0 when a row already existed (caller re-selects). A
     * ci-unique alias collision with ANOTHER user raises
     * DataIntegrityViolationException on
     * uk_community_member_profiles_alias_ci — never caught here.
     */
    @Modifying
    @Query(value = "INSERT INTO community_member_profiles (user_id, alias, created_at, updated_at)"
            + " VALUES (:userId, :alias, now(), now())"
            + " ON CONFLICT (user_id) DO NOTHING", nativeQuery = true)
    int insertProfileIfAbsent(@Param("userId") long userId, @Param("alias") String alias);

    /**
     * The §4 alias change: applies the validated alias to the caller's
     * existing profile. Future comment snapshots pick it up; past ones
     * are stable. clearAutomatically keeps a re-select from serving a
     * stale managed instance over the native update.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE community_member_profiles"
            + " SET alias = :alias, updated_at = now() WHERE user_id = :userId", nativeQuery = true)
    int updateAliasByUserId(@Param("userId") long userId, @Param("alias") String alias);

    // The §4 ci probes (member alias, any username, any publication
    // author_alias) live in CommunityAliasService on JdbcTemplate: a
    // repository native query would auto-flush a DIRTY persistence
    // context (double version bump on a PATCH mid-mutation); JDBC reads
    // bypass the persistence context entirely (visibility-store pattern).
}
