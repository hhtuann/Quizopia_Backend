package com.quizopia.backend.community.application;

import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.community.exception.CommunityException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The publication author-alias impersonation probe (ADR-007 §4; Rev 4
 * / #72). After the Rev 4 comment display-name policy retired the
 * member alias consent lifecycle (no alias capture on comments, no
 * profile writes), this service keeps the single cross-namespace check
 * that still has a subject: a publication author may not take the
 * alias of a {@code community_member_profiles} row. That V31 table is
 * retained read-only as legacy data (V32) and never receives new
 * rows, so the probe closes the publisher credit namespace against
 * every alias a member ever took historically.
 *
 * <p>Comment-side alias validation/upserting (former §4 lifecycle) was
 * removed with the policy change — comment authors are now identified
 * by their current account {@code display_name}, resolved by
 * {@link CommunityCommentAuthorStore}; duplicate names are allowed and
 * no client payload can influence them.
 */
@Service
public class CommunityAliasService {

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public CommunityAliasService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The §4 reverse ci-check for publication publish/PATCH
     * {@code authorAlias} (ADR-007's single P3-A touch): a publication
     * author may not take a (legacy) member pseudonym. Null (alias
     * cleared) is not this check's concern.
     *
     * <p>Deliberately a JdbcTemplate probe, NOT a repository native
     * query: a native query auto-flushes a dirty persistence context
     * (a publication PATCH validates the alias AFTER renaming the
     * entity; the spurious flush would double-bump @Version); JDBC
     * reads bypass the persistence context entirely.
     */
    public void assertAliasNotTakenByMember(String alias) {
        if (alias != null
                && exists("SELECT 1 FROM community_member_profiles WHERE LOWER(alias) = LOWER(?)",
                        alias)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_ALIAS_INVALID,
                    "Author alias must not match a community member's public alias");
        }
    }

    private boolean exists(String sql, String alias) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (" + sql + ")", Boolean.class, alias));
    }
}
