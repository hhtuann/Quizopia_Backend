package com.quizopia.backend.community.application;

import com.quizopia.backend.community.dto.PublicBookmarkEntry;
import com.quizopia.backend.community.dto.PublicPublicationCard;
import com.quizopia.backend.community.dto.PublicPublicationDetail;
import com.quizopia.backend.community.dto.PublicTagUsage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
/**
 * Single read-only SQL surface for the §4 public visibility predicate
 * (ADR-006 §6, issue #41). The predicate is written ONCE in
 * {@link #VISIBILITY_PREDICATE} and shared verbatim by the paged catalog,
 * the detail check, the {@code /tags} usage counts and the reusable
 * {@link #isVisible(long)} probe — bookmark save/list eligibility (#45)
 * and fork eligibility (#47) MUST reuse this store instead of
 * re-implementing the predicate.
 *
 * <p>Every query joins {@code community_publications} × {@code exams} ×
 * {@code users} and applies the predicate in the WHERE clause <b>before
 * COUNT and LIMIT/OFFSET</b>, so counts and pages only ever describe the
 * visible set. Catalog ordering is the deterministic
 * {@code published_at DESC, id DESC}; tag filters compile to one
 * {@code EXISTS} probe per requested tag (AND semantics); {@code q} is a
 * parametrized bounded ILIKE with {@code %}/{@code _} escaping.
 *
 * <p>Read-only: this component never writes any table.
 */
@Component
public class CommunityVisibilityStore {

    /**
     * §4 predicate verbatim — expected aliases in every using query:
     * {@code p} = community_publications, {@code e} = exams,
     * {@code u} = users (the publisher).
     */
    static final String VISIBILITY_PREDICATE = """
            p.status = 'PUBLISHED'
              AND p.moderation_state = 'VISIBLE'
              AND e.status = 'PUBLISHED'
              AND u.status = 'ACTIVE'
              AND p.publisher_user_id = e.created_by_user_id
            """;

    /** The exams/users joins behind the §4 predicate ({@code p} is the alias in scope). */
    private static final String PREDICATE_JOINS = """
            JOIN exams e ON e.id = p.exam_id
            JOIN users u ON u.id = p.publisher_user_id
            """;

    /** FROM + predicate join skeleton for publications-rooted queries. */
    private static final String VISIBLE_JOIN = "FROM community_publications p\n" + PREDICATE_JOINS;

    /** Card projection shared by catalog and detail (detail adds updated_at use). */
    private static final String CARD_SELECT = """
            SELECT p.id AS publication_id,
                   p.title,
                   p.summary,
                   p.author_alias,
                   c.code AS category_code,
                   c.title AS category_title,
                   p.question_count,
                   p.section_count,
                   p.duration_minutes,
                   p.published_at,
                   p.updated_at,
                   (p.reuse_policy = 'ALLOW_FORK') AS allow_fork,
                   ARRAY_REMOVE(ARRAY_AGG(t.label ORDER BY t.label), NULL) AS tag_labels
            """;

    private static final String CARD_FROM = VISIBLE_JOIN + """
            LEFT JOIN community_categories c ON c.id = p.category_id
            LEFT JOIN community_publication_tags pt ON pt.publication_id = p.id
            LEFT JOIN community_tags t ON t.id = pt.tag_id
            """;

    /** The visible card projection as a derived table (#45 bookmark page). */
    private static final String VISIBLE_CARD_SUBQUERY =
            CARD_SELECT + CARD_FROM + " WHERE " + VISIBILITY_PREDICATE + "\nGROUP BY p.id, c.id";

    /**
     * ONE statement for the paged bookmark shelf (#45): the user's
     * bookmarks LEFT JOINed to the §4-projected cards — a NULL
     * projection is exactly "the predicate now fails", never a missing
     * publication row (the FK guarantees the row exists).
     */
    private static final String BOOKMARKED_PAGE_SELECT = """
            SELECT b.publication_id,
                   b.created_at AS saved_at,
                   vp.publication_id AS visible_id,
                   vp.title,
                   vp.summary,
                   vp.author_alias,
                   vp.category_code,
                   vp.category_title,
                   vp.question_count,
                   vp.section_count,
                   vp.duration_minutes,
                   vp.published_at,
                   vp.allow_fork,
                   vp.tag_labels
            FROM community_bookmarks b
            LEFT JOIN (
                %s
            ) vp ON vp.publication_id = b.publication_id
            WHERE b.user_id = ?
            ORDER BY b.created_at DESC, b.id DESC
            LIMIT ? OFFSET ?
            """.formatted(VISIBLE_CARD_SUBQUERY);

    /** Catalog filter inputs (already normalized/clamped by the caller). */
    public record CatalogFilter(String q, Long categoryId, List<String> tags) {
    }

    /** Page slice of the visible set: items plus the pre-LIMIT total. */
    public record CatalogPage(List<PublicPublicationCard> items, long totalElements) {
    }

    /**
     * Page slice of a user's bookmark shelf (#45): entries (available
     * cards or unavailable placeholders) plus the pre-LIMIT bookmark
     * total — availability never affects counts or ordering.
     */
    public record BookmarkedPage(List<PublicBookmarkEntry> entries, long totalElements) {
    }

    /** Card columns + updated_at (detail = card + updatedAt). */
    private record Projection(PublicPublicationCard card, Instant updatedAt) {
    }

    private final JdbcTemplate jdbc;

    public CommunityVisibilityStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The §4 predicate as a reusable boolean — the eligibility oracle for
     * bookmark save/list (#45) and fork recheck (#47). Absent publications
     * are simply invisible.
     */
    public boolean isVisible(long publicationId) {
        Boolean visible = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    %s
                    WHERE %s AND p.id = ?
                )
                """.formatted(VISIBLE_JOIN, VISIBILITY_PREDICATE), Boolean.class, publicationId);
        return Boolean.TRUE.equals(visible);
    }

    /**
     * Paged visible catalog. The WHERE (predicate + filters) is built once
     * and used identically by the COUNT (before LIMIT/OFFSET) and the page
     * query, so {@code totalElements}/{@code totalPages} describe exactly
     * the filtered visible set.
     *
     * @param page 0-based, already clamped {@code >= 0} by the caller
     * @param size already clamped to 1..50 by the caller
     */
    public CatalogPage findVisibleCatalog(CatalogFilter filter, int page, int size) {
        FilterSql where = buildFilter(filter);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) " + VISIBLE_JOIN + " WHERE " + where.sql(),
                Long.class, where.args().toArray());

        // Long arithmetic: page * size can exceed Integer.MAX_VALUE long
        // before the page clamp (e.g. page=2147483647, size=50); computing
        // it as int would overflow to a negative OFFSET and 500.
        long offset = (long) page * size;
        List<PublicPublicationCard> items = jdbc.query(
                CARD_SELECT + CARD_FROM + " WHERE " + where.sql() + """
                        GROUP BY p.id, c.id
                        ORDER BY p.published_at DESC, p.id DESC
                        LIMIT ? OFFSET ?
                        """,
                (rs, rowNum) -> mapProjection(rs).card(),
                withAppended(where.args(), size, offset));

        return new CatalogPage(items, total == null ? 0L : total);
    }

    /** Single visible publication (detail = card + updatedAt); empty → 404. */
    public Optional<PublicPublicationDetail> findVisibleDetail(long publicationId) {
        List<PublicPublicationDetail> rows = jdbc.query(
                CARD_SELECT + CARD_FROM + " WHERE " + VISIBILITY_PREDICATE + """
                          AND p.id = ?
                        GROUP BY p.id, c.id
                        """,
                (rs, rowNum) -> {
                    Projection projection = mapProjection(rs);
                    return new PublicPublicationDetail(projection.card(), projection.updatedAt());
                },
                publicationId);
        return rows.stream().findFirst();
    }

    /**
     * Tag usage counts over §4-visible publications only (§4/§7: tags of
     * hidden/unpublished sources vanish). {@code usageCount} DESC,
     * {@code label} ASC, capped by {@code limit} (100 for the public
     * route).
     */
    public List<PublicTagUsage> findVisibleTagUsage(int limit) {
        return jdbc.query("""
                        SELECT t.label, COUNT(*) AS usage_count
                        FROM community_tags t
                        JOIN community_publication_tags pt ON pt.tag_id = t.id
                        JOIN community_publications p ON p.id = pt.publication_id
                        %s
                        WHERE %s
                        GROUP BY t.label
                        ORDER BY usage_count DESC, t.label ASC
                        LIMIT ?
                        """.formatted(PREDICATE_JOINS, VISIBILITY_PREDICATE),
                (rs, rowNum) -> new PublicTagUsage(rs.getString("label"), rs.getLong("usage_count")),
                limit);
    }

    /**
     * Paged bookmark shelf for {@code GET /api/community/me/bookmarks}
     * (#45): exactly two statements — the page query (bookmarks LEFT
     * JOINed to the §4-projected cards, deterministic
     * {@code created_at DESC, id DESC}) plus the bookmark total. A NULL
     * projection yields the three-field
     * {@link PublicBookmarkEntry.Unavailable} placeholder; availability
     * never changes counts, ordering or pagination.
     *
     * @param page 0-based, already clamped {@code >= 0} by the caller
     * @param size already clamped to 1..50 by the caller
     */
    public BookmarkedPage findBookmarkedPage(long userId, int page, int size) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_bookmarks WHERE user_id = ?", Long.class, userId);

        List<PublicBookmarkEntry> entries = jdbc.query(BOOKMARKED_PAGE_SELECT, (rs, rowNum) -> {
            long publicationId = rs.getLong("publication_id");
            Instant savedAt = rs.getTimestamp("saved_at").toInstant();
            rs.getLong("visible_id");
            if (rs.wasNull()) {
                return (PublicBookmarkEntry) new PublicBookmarkEntry.Unavailable(
                        publicationId, savedAt, true);
            }
            return new PublicBookmarkEntry.Available(
                    publicationId,
                    rs.getString("title"),
                    rs.getString("summary"),
                    rs.getString("author_alias"),
                    rs.getString("category_code"),
                    rs.getString("category_title"),
                    mapLabels(rs.getArray("tag_labels")),
                    rs.getInt("question_count"),
                    rs.getInt("section_count"),
                    rs.getInt("duration_minutes"),
                    rs.getTimestamp("published_at") == null
                            ? null : rs.getTimestamp("published_at").toInstant(),
                    rs.getBoolean("allow_fork"),
                    savedAt,
                    false);
        }, userId, size, (long) page * size);

        return new BookmarkedPage(entries, total == null ? 0L : total);
    }

    // ============================================================
    // internals
    // ============================================================

    /**
     * Builds the shared WHERE fragment: the §4 predicate plus, when
     * requested, a parametrized bounded ILIKE on title/summary with
     * {@code %}/{@code _}/{@code \} wildcard escaping, a categoryId
     * equality and one EXISTS probe per tag (AND semantics).
     */
    private FilterSql buildFilter(CatalogFilter filter) {
        StringBuilder sql = new StringBuilder(VISIBILITY_PREDICATE);
        List<Object> args = new ArrayList<>();

        if (filter.q() != null && !filter.q().isBlank()) {
            sql.append("""
                     AND (p.title ILIKE ? ESCAPE '\\' OR p.summary ILIKE ? ESCAPE '\\')
                    """);
            String pattern = "%" + escapeLike(filter.q()) + "%";
            args.add(pattern);
            args.add(pattern);
        }
        if (filter.categoryId() != null) {
            sql.append(" AND p.category_id = ?\n");
            args.add(filter.categoryId());
        }
        if (filter.tags() != null) {
            for (String tag : filter.tags()) {
                sql.append("""
                          AND EXISTS (
                              SELECT 1
                              FROM community_publication_tags ptx
                              JOIN community_tags tx ON tx.id = ptx.tag_id
                              WHERE ptx.publication_id = p.id AND tx.label = ?
                          )
                        """);
                args.add(tag);
            }
        }
        return new FilterSql(sql.toString(), args);
    }

    private record FilterSql(String sql, List<Object> args) {
    }

    private static Object[] withAppended(List<Object> args, Object... tail) {
        Object[] out = new Object[args.size() + tail.length];
        for (int i = 0; i < args.size(); i++) {
            out[i] = args.get(i);
        }
        System.arraycopy(tail, 0, out, args.size(), tail.length);
        return out;
    }


    /**
     * Escapes LIKE/ILIKE wildcards ({@code %}, {@code _}) and the escape
     * character itself so user input matches literally; combined with the
     * parametrized bind this makes wildcard and SQL-injection payloads
     * inert. The ESCAPE clause in the SQL is a backslash.
     */
    private static String escapeLike(String literal) {
        StringBuilder out = new StringBuilder(literal.length() + 8);
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c == '%' || c == '_' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    private Projection mapProjection(ResultSet rs) throws SQLException {
        PublicPublicationCard card = new PublicPublicationCard(
                rs.getLong("publication_id"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("author_alias"),
                rs.getString("category_code"),
                rs.getString("category_title"),
                mapLabels(rs.getArray("tag_labels")),
                rs.getInt("question_count"),
                rs.getInt("section_count"),
                rs.getInt("duration_minutes"),
                rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant(),
                rs.getBoolean("allow_fork"));
        return new Projection(card,
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant());
    }

    private static List<String> mapLabels(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (!(raw instanceof String[] labels) || labels.length == 0) {
            return List.of();
        }
        return List.of(labels);
    }
}
