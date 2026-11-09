package com.quizopia.backend.community.repository;

import com.quizopia.backend.community.domain.model.CommunityMemberProfile;
import org.springframework.data.jpa.repository.JpaRepository;

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
 */
public interface CommunityMemberProfileRepository
        extends JpaRepository<CommunityMemberProfile, Long> {

    /** The caller's stored pseudonym, if they have commented yet. */
    Optional<CommunityMemberProfile> findByUserId(Long userId);
}
