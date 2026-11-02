-- =====================================================================
-- V15 (Task B / Issue #2): Exam redesign — CONTRACT phase.
--
-- Removes every schema dependency on exam_versions after the verified
-- V14 cutover: exam_version_id columns and their FKs/uniques,
-- exams.current_version_number, and the transitional READY status.
-- The exam_versions TABLE itself is retained (archived, no dependents)
-- for audit; dropping it is a later, separately verified release.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Drop every constraint referencing exam_version_id (any table).
-- ---------------------------------------------------------------------
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT conrelid::regclass::text AS tbl, conname
        FROM pg_constraint
        WHERE contype IN ('f','u','p')
          AND pg_get_constraintdef(oid) ILIKE '%exam_version_id%'
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT IF EXISTS %I', r.tbl, r.conname);
    END LOOP;
END $$;

-- ---------------------------------------------------------------------
-- 2. Drop the exam_version_id columns themselves.
-- ---------------------------------------------------------------------
ALTER TABLE attempts        DROP COLUMN IF EXISTS exam_version_id;
ALTER TABLE exam_sessions   DROP COLUMN IF EXISTS exam_version_id;
ALTER TABLE exam_questions  DROP COLUMN IF EXISTS exam_version_id;
ALTER TABLE exam_sections   DROP COLUMN IF EXISTS exam_version_id;

-- ---------------------------------------------------------------------
-- 2b. Attempt school provenance: PUBLIC sessions are platform-wide (issue #1
--     §2.3 Phase 1 rule), so an attempt's school may legitimately differ
--     from the student's profile school (or be null for personal-workspace
--     exams). Relax the school pairing; student_profile_id remains the
--     legacy provenance FK.
-- ---------------------------------------------------------------------
ALTER TABLE attempts DROP CONSTRAINT IF EXISTS fk_attempts_student_school;
ALTER TABLE attempts ALTER COLUMN school_id DROP NOT NULL;

-- ---------------------------------------------------------------------
-- 3. exams: no content versioning.
-- ---------------------------------------------------------------------
ALTER TABLE exams DROP CONSTRAINT IF EXISTS chk_exams_current_version;
ALTER TABLE exams DROP COLUMN IF EXISTS current_version_number;

-- Final lifecycle statuses (READY was mapped to PUBLISHED in V14).
ALTER TABLE exams DROP CONSTRAINT IF EXISTS chk_exams_status;
ALTER TABLE exams ADD CONSTRAINT chk_exams_status
    CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED'));

-- ---------------------------------------------------------------------
-- 4. exam_versions: detached (no dependents), retained for audit.
-- ---------------------------------------------------------------------
COMMENT ON TABLE exam_versions IS
    'ARCHIVED legacy table (Task B redesign): superseded by exam-level content. Retained for audit; no runtime or schema dependency. Drop in a later, separately verified release.';

-- ---------------------------------------------------------------------
-- 5. Verification — no remaining dependency on exam_versions.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint tc
        JOIN pg_class c ON c.oid = tc.confrelid
        WHERE c.relname = 'exam_versions'
          AND tc.connamespace = 'public'::regnamespace
    ) THEN
        RAISE EXCEPTION 'V15 verification: foreign keys still reference exam_versions';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND column_name = 'exam_version_id'
    ) THEN
        RAISE EXCEPTION 'V15 verification: exam_version_id columns still exist';
    END IF;

    IF EXISTS (SELECT 1 FROM exams WHERE status = 'READY') THEN
        RAISE EXCEPTION 'V15 verification: legacy READY exams were not mapped';
    END IF;

    RAISE NOTICE 'V15: exam versioning dependencies removed; exam_versions retained as audit table';
END $$;
