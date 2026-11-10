-- =====================================================================
-- V32 (community comment display-name policy, ADR-007 Rev 4, #72):
--
-- The owner-approved policy change (Epic #70 F6): public comments show
-- the author's CURRENT account profile display_name (users.display_name,
-- read via JOIN at query time — never snapshotted, never
-- client-supplied). The V31 Community Alias consent lifecycle is
-- retired: no alias capture on create, no alias validation, duplicate
-- display names are allowed (display_name was never unique).
--
-- This migration is intentionally MINIMAL and forward-only:
--   * community_comments.author_alias loses its NOT NULL — the column
--     is RETAINED (deprecated) so historical rows keep their legacy
--     alias snapshots for audit; NEW comment rows store NULL.
--   * community_member_profiles is RETAINED unchanged (deprecated,
--     read-only legacy): existing rows are historical data; the table
--     simply stops receiving writes. The publication authorAlias
--     reverse impersonation probe (ADR-007 §4 assertAliasNotTakenByMember)
--     keeps reading it, so the publisher credit namespace stays closed
--     against every alias ever taken by a member.
--
-- No data is deleted, no applied migration is edited. Display of
-- author names needs no backfill: the public comment projection JOINs
-- users at read time, so existing AND new comments immediately render
-- the author's current display_name, including after profile renames.
--
-- FK/CHECK posture unchanged from V31.
-- =====================================================================

-- New-era comment rows carry no alias snapshot (legacy rows keep theirs).
ALTER TABLE community_comments ALTER COLUMN author_alias DROP NOT NULL;

-- ---------------------------------------------------------------------
-- Verification — the display-name contract holds on both fresh and
-- upgraded databases.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    -- author_alias is now nullable so comment inserts can omit it.
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'community_comments'
          AND column_name = 'author_alias'
          AND is_nullable = 'NO'
    ) THEN
        RAISE EXCEPTION 'V32 verification: community_comments.author_alias must be nullable';
    END IF;

    -- users.display_name stays non-unique (duplicate display names are
    -- explicitly allowed for comment authors).
    IF EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = 'users'
          AND indexdef LIKE '%display_name%'
          AND indexdef LIKE '%UNIQUE%'
    ) THEN
        RAISE EXCEPTION 'V32 verification: users.display_name must remain non-unique';
    END IF;

    -- The legacy member-profile table is retained read-only (deprecated).
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = 'community_member_profiles'
    ) THEN
        RAISE EXCEPTION 'V32 verification: legacy community_member_profiles must be retained';
    END IF;
END $$;
