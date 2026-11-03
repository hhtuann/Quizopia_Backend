-- =====================================================================
-- V11 (Task A / Issue #1): Workspace ownership for question banks & exams.
--
-- Per ADR-001 §2.2:
--   * question_banks / exams gain workspace_id + created_by_user_id
--   * legacy school_id / subject_id / owner_teacher_id become nullable
--     provenance (composite FKs retained; they stop applying when NULL)
--   * code uniqueness becomes (workspace_id, created_by_user_id, code)
--   * exam_versions.school_id nullable so personal-workspace draft exams
--     can exist until Task B removes the version table from the runtime path
--
-- Backfill maps every legacy row to its school's organization workspace and
-- resolves owner_teacher_id -> users.id via teacher_profiles.
-- No historical row ids change; verification DO-blocks fail on anomaly.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. question_banks ownership
-- ---------------------------------------------------------------------
ALTER TABLE question_banks
    ADD COLUMN workspace_id       BIGINT,
    ADD COLUMN created_by_user_id BIGINT;

ALTER TABLE question_banks ALTER COLUMN school_id        DROP NOT NULL;
ALTER TABLE question_banks ALTER COLUMN subject_id       DROP NOT NULL;
ALTER TABLE question_banks ALTER COLUMN owner_teacher_id DROP NOT NULL;

UPDATE question_banks qb
SET workspace_id       = w.id,
    created_by_user_id = tp.user_id
FROM workspaces w, organizations o, teacher_profiles tp
WHERE w.workspace_type = 'ORGANIZATION'
  AND w.organization_id = o.id
  AND o.legacy_school_id = qb.school_id
  AND tp.id = qb.owner_teacher_id
  AND tp.school_id = qb.school_id
  AND qb.workspace_id IS NULL;

ALTER TABLE question_banks
    ALTER COLUMN workspace_id       SET NOT NULL,
    ALTER COLUMN created_by_user_id SET NOT NULL,
    ADD CONSTRAINT fk_question_banks_workspace FOREIGN KEY (workspace_id)
        REFERENCES workspaces (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_question_banks_created_by FOREIGN KEY (created_by_user_id)
        REFERENCES users (id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uk_question_banks_workspace_creator_code_ci
    ON question_banks (workspace_id, created_by_user_id, LOWER(code));

CREATE INDEX idx_question_banks_workspace ON question_banks (workspace_id);

-- Transitional ownership fill (ADR-001 §2.2): legacy-style INSERTs that omit
-- the new columns still resolve ownership deterministically via BEFORE INSERT
-- triggers. JPA always writes the columns explicitly, so the trigger is a
-- no-op for the application. Dropped in the Task B contract phase.
CREATE OR REPLACE FUNCTION quizopia_question_banks_ownership_tg() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.workspace_id IS NULL THEN
        NEW.workspace_id := quizopia_org_workspace_for_school(NEW.school_id);
    END IF;
    IF NEW.created_by_user_id IS NULL THEN
        NEW.created_by_user_id := quizopia_user_for_teacher(NEW.owner_teacher_id);
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER tg_question_banks_ownership
    BEFORE INSERT OR UPDATE ON question_banks
    FOR EACH ROW EXECUTE FUNCTION quizopia_question_banks_ownership_tg();

-- ---------------------------------------------------------------------
-- 2. exams ownership
-- ---------------------------------------------------------------------
ALTER TABLE exams
    ADD COLUMN workspace_id       BIGINT,
    ADD COLUMN created_by_user_id BIGINT;

ALTER TABLE exams ALTER COLUMN school_id        DROP NOT NULL;
ALTER TABLE exams ALTER COLUMN subject_id       DROP NOT NULL;
ALTER TABLE exams ALTER COLUMN owner_teacher_id DROP NOT NULL;

UPDATE exams e
SET workspace_id       = w.id,
    created_by_user_id = tp.user_id
FROM workspaces w, organizations o, teacher_profiles tp
WHERE w.workspace_type = 'ORGANIZATION'
  AND w.organization_id = o.id
  AND o.legacy_school_id = e.school_id
  AND tp.id = e.owner_teacher_id
  AND tp.school_id = e.school_id
  AND e.workspace_id IS NULL;

ALTER TABLE exams
    ALTER COLUMN workspace_id       SET NOT NULL,
    ALTER COLUMN created_by_user_id SET NOT NULL,
    ADD CONSTRAINT fk_exams_workspace FOREIGN KEY (workspace_id)
        REFERENCES workspaces (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_exams_created_by FOREIGN KEY (created_by_user_id)
        REFERENCES users (id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uk_exams_workspace_creator_code_ci
    ON exams (workspace_id, created_by_user_id, LOWER(code));

CREATE INDEX idx_exams_workspace ON exams (workspace_id);

-- Transitional ownership fill (see question_banks above).
CREATE OR REPLACE FUNCTION quizopia_exams_ownership_tg() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.workspace_id IS NULL THEN
        NEW.workspace_id := quizopia_org_workspace_for_school(NEW.school_id);
    END IF;
    IF NEW.created_by_user_id IS NULL THEN
        NEW.created_by_user_id := quizopia_user_for_teacher(NEW.owner_teacher_id);
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER tg_exams_ownership
    BEFORE INSERT OR UPDATE ON exams
    FOR EACH ROW EXECUTE FUNCTION quizopia_exams_ownership_tg();

-- ---------------------------------------------------------------------
-- 3. Personal draft exams: exam_versions.school_id nullable (interim,
--    until Task B V13-V15 removes exam_versions from the runtime path).
-- ---------------------------------------------------------------------
ALTER TABLE exam_versions ALTER COLUMN school_id DROP NOT NULL;

-- ---------------------------------------------------------------------
-- 4. Verification
-- ---------------------------------------------------------------------
DO $$
DECLARE
    banks_total   BIGINT;
    banks_mapped  BIGINT;
    exams_total   BIGINT;
    exams_mapped  BIGINT;
BEGIN
    SELECT COUNT(*) INTO banks_total FROM question_banks;
    SELECT COUNT(*) INTO banks_mapped FROM question_banks WHERE school_id IS NOT NULL;
    IF (SELECT COUNT(*) FROM question_banks WHERE school_id IS NOT NULL AND workspace_id IS NULL) > 0 THEN
        RAISE EXCEPTION 'V11 verification: school-era question banks without workspace mapping found';
    END IF;
    IF (SELECT COUNT(*) FROM question_banks WHERE owner_teacher_id IS NOT NULL AND created_by_user_id IS NULL) > 0 THEN
        RAISE EXCEPTION 'V11 verification: teacher-owned question banks without created_by_user_id found';
    END IF;

    SELECT COUNT(*) INTO exams_total FROM exams;
    SELECT COUNT(*) INTO exams_mapped FROM exams WHERE school_id IS NOT NULL;
    IF (SELECT COUNT(*) FROM exams WHERE school_id IS NOT NULL AND workspace_id IS NULL) > 0 THEN
        RAISE EXCEPTION 'V11 verification: school-era exams without workspace mapping found';
    END IF;
    IF (SELECT COUNT(*) FROM exams WHERE owner_teacher_id IS NOT NULL AND created_by_user_id IS NULL) > 0 THEN
        RAISE EXCEPTION 'V11 verification: teacher-owned exams without created_by_user_id found';
    END IF;

    RAISE NOTICE 'V11: % question banks (% school-era), % exams (% school-era) mapped to workspaces',
        banks_total, banks_mapped, exams_total, exams_mapped;
END $$;
