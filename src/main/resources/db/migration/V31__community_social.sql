-- =====================================================================
-- V31 (community social interaction, ADR-007 §3, issues #65/#66):
--
--   * community_member_profiles — the community-owned pseudonym (one
--     per user, ci-unique alias; created at first comment, #66)
--   * community_likes           — per-user like rows (#65)
--   * community_ratings         — per-user 1..5 ratings (#65)
--   * community_comments        — publication comments (#66 services;
--                                   the table lands here so the audit
--                                   links below can reference it)
--   * community_comment_reports — the comment report queue (#66)
--   * community_moderation_actions (V27, additive ALTER) — nullable
--                                   comment_id/comment_report_id audit
--                                   links + widened action CHECK
--   * RBAC seeds                 — COMMUNITY_LIKE / COMMUNITY_RATE /
--                                   COMMUNITY_COMMENT → STUDENT + TEACHER
--
-- V1–V30 are untouched; this migration only ADDS tables, two nullable
-- audit columns, a CHECK drop/re-add (identity-audit precedent) and
-- seed rows.
-- FK policy (ADR-007 §3): publication-side child FKs CASCADE — likes,
-- ratings, comments → community_publications, comment_reports →
-- community_comments (their meaning dies with the parent); every FK to
-- users and every moderation-audit FK is ON DELETE RESTRICT (audit
-- outlives content).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. COMMUNITY_MEMBER_PROFILES
--    The community pseudonym surface (ADR-007 §4): one row per user,
--    created at first comment, alias collision-checked ci-unique by
--    uk_community_member_profiles_alias_ci (the constraint name #66's
--    DataIntegrityViolationException recovery maps on). NOT PII: a
--    user-chosen pseudonym validated against impersonation and
--    self-PII rules at the API layer.
-- ---------------------------------------------------------------------
CREATE TABLE community_member_profiles (
    user_id BIGINT PRIMARY KEY,

    alias VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_community_member_profiles_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uk_community_member_profiles_alias_ci
    ON community_member_profiles (LOWER(alias));

-- ---------------------------------------------------------------------
-- 2. COMMUNITY_LIKES (#65)
--    The fact "user X liked publication Y at T" — PK (user_id,
--    publication_id) (publication-tags composite-PK precedent; the ON
--    CONFLICT arbiter is identical to #64's UNIQUE pair), immutable
--    after insert, removed by DELETE. Writes go through the native
--    INSERT … ON CONFLICT DO NOTHING upsert (bookmark precedent).
-- ---------------------------------------------------------------------
CREATE TABLE community_likes (
    user_id BIGINT NOT NULL,
    publication_id BIGINT NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (user_id, publication_id),

    CONSTRAINT fk_community_likes_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_community_likes_publication
        FOREIGN KEY (publication_id) REFERENCES community_publications (id) ON DELETE CASCADE
);

-- Aggregate COUNT (likeCount) per publication — index-only.
CREATE INDEX idx_community_likes_publication
    ON community_likes (publication_id);

-- ---------------------------------------------------------------------
-- 3. COMMUNITY_RATINGS (#65)
--    Same shape as likes + the 1..5 value. One rating per (user,
--    publication), upserted by a single native INSERT … ON CONFLICT …
--    DO UPDATE … RETURNING (xmax = 0) statement (§6). The covering
--    index (publication_id) INCLUDE (rating) serves index-only COUNT
--    and ROUND(AVG(rating), 2) for the social summary.
-- ---------------------------------------------------------------------
CREATE TABLE community_ratings (
    user_id BIGINT NOT NULL,
    publication_id BIGINT NOT NULL,

    rating SMALLINT NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (user_id, publication_id),

    CONSTRAINT fk_community_ratings_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_community_ratings_publication
        FOREIGN KEY (publication_id) REFERENCES community_publications (id) ON DELETE CASCADE,

    CONSTRAINT chk_community_ratings_rating
        CHECK (rating BETWEEN 1 AND 5)
);

-- Index-only COUNT + AVG per publication (ADR-007 §3).
CREATE INDEX idx_community_ratings_publication
    ON community_ratings (publication_id) INCLUDE (rating);

-- ---------------------------------------------------------------------
-- 4. COMMUNITY_COMMENTS (#66 services; table lands in V31)
--    Publication comments. author_alias is the SNAPSHOT of the
--    post-upsert stored member-profile alias (§4) — never the raw
--    request value; snapshots are stable across later alias changes.
--    body is stored verbatim after API-layer charset/length checks.
--    state: VISIBLE (public) / HIDDEN (moderation, retained
--    server-side) / DELETED (owner tombstone: body cleared, terminal).
-- ---------------------------------------------------------------------
CREATE TABLE community_comments (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,

    publication_id BIGINT NOT NULL,
    author_user_id BIGINT NOT NULL,

    author_alias VARCHAR(80) NOT NULL,
    body VARCHAR(2000) NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'VISIBLE',
    edited_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_community_comments_publication
        FOREIGN KEY (publication_id) REFERENCES community_publications (id) ON DELETE CASCADE,
    CONSTRAINT fk_community_comments_author
        FOREIGN KEY (author_user_id) REFERENCES users (id) ON DELETE RESTRICT,

    CONSTRAINT chk_community_comments_state
        CHECK (state IN ('VISIBLE', 'HIDDEN', 'DELETED'))
);

-- Public page scan + index-only VISIBLE COUNT (commentCount shares the
-- list predicate — summary and list totals can never diverge).
CREATE INDEX idx_community_comments_publication
    ON community_comments (publication_id, state, created_at DESC, id DESC);

-- Abuse review by author (platform tiebreak convention).
CREATE INDEX idx_community_comments_author
    ON community_comments (author_user_id, created_at DESC, id DESC);

-- ---------------------------------------------------------------------
-- 5. COMMUNITY_COMMENT_REPORTS (#66)
--    Same shape discipline as V27 community_reports, scoped to
--    comments: status OPEN → DISMISSED | RESOLVED (moderator
--    transitions only), one OPEN report per (reporter, comment) via
--    the partial unique index — the dedupe oracle for #66's
--    ON CONFLICT … DO NOTHING submission.
-- ---------------------------------------------------------------------
CREATE TABLE community_comment_reports (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,

    comment_id BIGINT NOT NULL,
    reporter_user_id BIGINT NOT NULL,

    reason_code VARCHAR(40) NOT NULL,
    details VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',

    handled_by BIGINT,
    handled_at TIMESTAMPTZ,
    resolution_note VARCHAR(500),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_community_comment_reports_comment
        FOREIGN KEY (comment_id) REFERENCES community_comments (id) ON DELETE CASCADE,
    CONSTRAINT fk_community_comment_reports_reporter
        FOREIGN KEY (reporter_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_community_comment_reports_handled_by
        FOREIGN KEY (handled_by) REFERENCES users (id) ON DELETE RESTRICT,

    CONSTRAINT chk_community_comment_reports_reason_code
        CHECK (reason_code IN ('INAPPROPRIATE_CONTENT', 'COPYRIGHT', 'MISLEADING', 'SPAM', 'OTHER')),
    CONSTRAINT chk_community_comment_reports_status
        CHECK (status IN ('OPEN', 'DISMISSED', 'RESOLVED'))
);

-- One OPEN report per (reporter, comment) — the dedupe oracle.
CREATE UNIQUE INDEX uk_community_comment_reports_open_per_reporter
    ON community_comment_reports (reporter_user_id, comment_id)
    WHERE status = 'OPEN';

-- Moderator queue scan (status filter + newest-first).
CREATE INDEX idx_community_comment_reports_status_created
    ON community_comment_reports (status, created_at);

-- Per-comment drill-down.
CREATE INDEX idx_community_comment_reports_comment
    ON community_comment_reports (comment_id);

-- ---------------------------------------------------------------------
-- 6. COMMUNITY_MODERATION_ACTIONS (V27) — ADDITIVE ALTER (ADR-007 §3)
--    Two nullable audit links for comment moderation (#66): comment
--    rows record the acted-on comment; report dismiss/resolve rows
--    record the handled comment report. Audit FKs stay RESTRICT (audit
--    outlives content). The action CHECK is dropped and re-added with
--    the four COMMENT_* values (identity-audit precedent); the state
--    CHECKs are deliberately NOT widened — comment audit rows carry
--    VISIBLE↔HIDDEN only, from/to_status stay NULL on comment rows.
-- ---------------------------------------------------------------------
ALTER TABLE community_moderation_actions
    ADD COLUMN comment_id BIGINT,
    ADD COLUMN comment_report_id BIGINT;

ALTER TABLE community_moderation_actions
    ADD CONSTRAINT fk_community_moderation_actions_comment
        FOREIGN KEY (comment_id) REFERENCES community_comments (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_community_moderation_actions_comment_report
        FOREIGN KEY (comment_report_id) REFERENCES community_comment_reports (id) ON DELETE RESTRICT;

ALTER TABLE community_moderation_actions DROP CONSTRAINT chk_community_moderation_actions_action;
ALTER TABLE community_moderation_actions
    ADD CONSTRAINT chk_community_moderation_actions_action
        CHECK (action IN ('HIDE', 'RESTORE', 'REPORT_DISMISS', 'REPORT_RESOLVE',
                          'MODERATOR_GRANTED', 'MODERATOR_REVOKED',
                          'COMMENT_HIDE', 'COMMENT_RESTORE',
                          'COMMENT_REPORT_DISMISS', 'COMMENT_REPORT_RESOLVE'));

-- Per-comment audit history (newest-first).
CREATE INDEX idx_community_moderation_actions_comment
    ON community_moderation_actions (comment_id, created_at DESC);

-- Comment-report-driven audit lookup.
CREATE INDEX idx_community_moderation_actions_comment_report
    ON community_moderation_actions (comment_report_id);

-- ---------------------------------------------------------------------
-- 7. RBAC SEEDS (idempotent INSERT … WHERE NOT EXISTS)
--    COMMUNITY_LIKE / COMMUNITY_RATE / COMMUNITY_COMMENT → STUDENT and
--    TEACHER (reader-facing interactions; the publisher may like — but
--    never rate — their own publication, enforced in #65's service).
--    Comment moderation reuses the existing COMMUNITY_MODERATION
--    permission (V27 dormant-role posture unchanged).
-- ---------------------------------------------------------------------
INSERT INTO permissions (code, name, description)
SELECT v.code, v.name, v.description
FROM (VALUES
    ('COMMUNITY_LIKE',
     'Like Community Publication',
     'Like and unlike publicly visible community publications.'),
    ('COMMUNITY_RATE',
     'Rate Community Publication',
     'Rate publicly visible community publications 1-5 (never as the publisher of the publication).'),
    ('COMMUNITY_COMMENT',
     'Comment on Community Publication',
     'Create and manage own comments on publicly visible community publications.')
) AS v(code, name, description)
WHERE NOT EXISTS (SELECT 1 FROM permissions p WHERE p.code = v.code);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('COMMUNITY_LIKE', 'COMMUNITY_RATE', 'COMMUNITY_COMMENT')
WHERE r.code IN ('STUDENT', 'TEACHER')
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- ---------------------------------------------------------------------
-- 8. Verification — the community social contract holds.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE indexname = 'uk_community_member_profiles_alias_ci'
    ) THEN
        RAISE EXCEPTION 'V31 verification: ci-unique member alias index missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE indexname = 'idx_community_ratings_publication'
          AND indexdef LIKE '%INCLUDE (rating)%'
    ) THEN
        RAISE EXCEPTION 'V31 verification: ratings covering index missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE indexname = 'uk_community_comment_reports_open_per_reporter'
    ) THEN
        RAISE EXCEPTION 'V31 verification: partial unique OPEN comment-report index missing';
    END IF;

    -- The widened action CHECK must admit the four COMMENT_* values.
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_community_moderation_actions_action'
          AND pg_get_constraintdef(oid) NOT LIKE '%COMMENT_HIDE%'
    ) THEN
        RAISE EXCEPTION 'V31 verification: moderation action CHECK not widened';
    END IF;

    -- RBAC grants: the three new permissions on exactly STUDENT + TEACHER
    -- (6 rows), for a total of 13 COMMUNITY_* grants (V26 seeded 7).
    IF (SELECT count(*) FROM role_permissions rp
        JOIN permissions p ON p.id = rp.permission_id
        WHERE p.code IN ('COMMUNITY_LIKE', 'COMMUNITY_RATE', 'COMMUNITY_COMMENT')) <> 6 THEN
        RAISE EXCEPTION 'V31 verification: expected exactly 6 like/rate/comment role grants';
    END IF;
    IF (SELECT count(*) FROM role_permissions rp
        JOIN permissions p ON p.id = rp.permission_id
        WHERE p.code LIKE 'COMMUNITY_%') <> 13 THEN
        RAISE EXCEPTION 'V31 verification: expected exactly 13 COMMUNITY_* role grants';
    END IF;

    RAISE NOTICE 'V31: community social interaction tables complete';
END $$;
