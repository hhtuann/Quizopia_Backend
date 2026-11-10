package com.quizopia.backend.community.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Server-side resolution of the PUBLIC comment author name (ADR-007
 * Rev 4, #72): the author's CURRENT account {@code users.display_name}
 * — the single owner-approved public profile field, exposed ONLY on
 * the comment surface. Names are resolved at read time (renames are
 * reflected immediately, duplicates are allowed because
 * {@code display_name} is not unique) and can never be supplied or
 * overridden by client payloads.
 *
 * <p>Privacy-safe fallback: a missing/blank display name renders as
 * the generic "{@code Community member}" — never a username, email,
 * phone or any id. The V2 CHECK {@code LENGTH(TRIM(display_name)) > 0}
 * makes blank names unreachable through normal writes; the fallback is
 * deliberate defense-in-depth.
 *
 * <p>Projection queries are JdbcTemplate native SQL (the
 * {@link CommunityVisibilityStore} precedent — cross-module reads
 * without importing the identity module's entities). The public page
 * is two statements — a count on the frozen
 * {@code (publication_id, state, created_at DESC, id DESC)} index and
 * one JOIN page fetch — no per-comment user lookup (no N+1); the
 * {@code users} FK is RESTRICT, so the inner JOIN can never drop a
 * comment row.
 */
@Component
public class CommunityCommentAuthorStore {

    /** Privacy-safe public fallback when the profile name is unusable. */
    static final String FALLBACK_AUTHOR_NAME = "Community member";

    private static final String NAME_EXPRESSION =
            "COALESCE(NULLIF(BTRIM(u.display_name), ''), '" + FALLBACK_AUTHOR_NAME + "')";

    private final JdbcTemplate jdbc;

    public CommunityCommentAuthorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One row of the public VISIBLE page with the resolved author name. */
    public record VisibleCommentRow(
            Long commentId,
            Long authorUserId,
            String authorName,
            String body,
            Instant createdAt,
            Instant editedAt) {
    }

    /** Page slice + total count (same predicate for both — list and
     * summary totals can never diverge). */
    public record VisibleCommentPage(List<VisibleCommentRow> rows, long totalElements) {
    }

    /**
     * The public comments projection of one publication: VISIBLE rows
     * newest-first ({@code created_at DESC, id DESC}), the author name
     * resolved from {@code users.display_name} with the safe fallback.
     * {@code authorUserId} is internal — callers use it ONLY for the
     * server-side {@code isMine} computation and never serialize it.
     */
    public VisibleCommentPage visiblePage(long publicationId, int page, int size) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_comments"
                        + " WHERE publication_id = ? AND state = 'VISIBLE'",
                Long.class, publicationId);
        List<VisibleCommentRow> rows = jdbc.query(
                "SELECT c.id, c.author_user_id, " + NAME_EXPRESSION + " AS author_name,"
                        + " c.body, c.created_at, c.edited_at"
                        + " FROM community_comments c"
                        + " JOIN users u ON u.id = c.author_user_id"
                        + " WHERE c.publication_id = ? AND c.state = 'VISIBLE'"
                        + " ORDER BY c.created_at DESC, c.id DESC"
                        + " LIMIT ? OFFSET ?",
                (rs, i) -> new VisibleCommentRow(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getTimestamp(5).toInstant(),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()),
                publicationId, size, (long) page * size);
        return new VisibleCommentPage(rows, total == null ? 0L : total);
    }

    /**
     * The current public author name of one user (create/PATCH owner
     * responses) — same fallback discipline, never identity data.
     */
    public String displayNameOf(long userId) {
        List<String> names = jdbc.query(
                "SELECT COALESCE(NULLIF(BTRIM(display_name), ''), ?) FROM users WHERE id = ?",
                (rs, i) -> rs.getString(1), FALLBACK_AUTHOR_NAME, userId);
        return names.isEmpty() ? FALLBACK_AUTHOR_NAME : names.get(0);
    }

    /**
     * Batched current display names (moderation queue page — one
     * statement per page, never per comment).
     */
    public Map<Long, String> displayNamesOf(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = userIds.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Long> ids = List.copyOf(userIds);
        return jdbc.query(
                "SELECT id, COALESCE(NULLIF(BTRIM(display_name), ''), ?) FROM users"
                        + " WHERE id IN (" + placeholders + ")",
                (rs, i) -> Map.entry(rs.getLong(1), rs.getString(2)),
                prepend(FALLBACK_AUTHOR_NAME, ids)).stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Object[] prepend(Object first, List<Long> rest) {
        Object[] args = new Object[rest.size() + 1];
        args[0] = first;
        for (int i = 0; i < rest.size(); i++) {
            args[i + 1] = rest.get(i);
        }
        return args;
    }
}
