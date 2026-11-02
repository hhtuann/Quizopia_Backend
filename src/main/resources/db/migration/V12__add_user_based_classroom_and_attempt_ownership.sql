-- =====================================================================
-- V12 (Task A / Issue #1): Classroom workspace scope + user-based membership
-- and attempt user ownership.
--
-- Per ADR-001 §2.2/§2.3:
--   * classrooms.workspace_id — org workspace of the classroom's school
--   * classroom_members.user_id — user-based membership (NOT NULL after
--     backfill); student_profile_id/school_id stay as nullable legacy
--     provenance for historical rows
--   * attempts.user_id — user-based attempt ownership backfilled from
--     student_profiles (student_profile_id untouched, per issue #1 A4)
--
-- Verification DO-blocks fail on anomaly; no historical ids change.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. classrooms.workspace_id
-- ---------------------------------------------------------------------
ALTER TABLE classrooms ADD COLUMN workspace_id BIGINT;

UPDATE classrooms c
SET workspace_id = w.id
FROM workspaces w, organizations o
WHERE w.workspace_type = 'ORGANIZATION'
  AND w.organization_id = o.id
  AND o.legacy_school_id = c.school_id
  AND c.workspace_id IS NULL;

ALTER TABLE classrooms
    ALTER COLUMN workspace_id SET NOT NULL,
    ADD CONSTRAINT fk_classrooms_workspace FOREIGN KEY (workspace_id)
        REFERENCES workspaces (id) ON DELETE RESTRICT;

CREATE INDEX idx_classrooms_workspace ON classrooms (workspace_id);

-- Transitional workspace fill (ADR-001 §2.2): legacy-style INSERTs resolve
-- the org workspace from school_id. Dropped in the contract phase.
CREATE OR REPLACE FUNCTION quizopia_classrooms_workspace_tg() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.workspace_id IS NULL THEN
        NEW.workspace_id := quizopia_org_workspace_for_school(NEW.school_id);
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER tg_classrooms_workspace
    BEFORE INSERT OR UPDATE ON classrooms
    FOR EACH ROW EXECUTE FUNCTION quizopia_classrooms_workspace_tg();

-- ---------------------------------------------------------------------
-- 2. classroom_members.user_id
-- ---------------------------------------------------------------------
ALTER TABLE classroom_members ADD COLUMN user_id BIGINT;

ALTER TABLE classroom_members ALTER COLUMN school_id         DROP NOT NULL;
ALTER TABLE classroom_members ALTER COLUMN student_profile_id DROP NOT NULL;

UPDATE classroom_members cm
SET user_id = sp.user_id
FROM student_profiles sp
WHERE sp.id = cm.student_profile_id
  AND sp.school_id = cm.school_id
  AND cm.user_id IS NULL;

ALTER TABLE classroom_members
    ALTER COLUMN user_id SET NOT NULL,
    ADD CONSTRAINT fk_classroom_members_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uk_classroom_members_classroom_user
    ON classroom_members (classroom_id, user_id);

CREATE INDEX idx_classroom_members_user ON classroom_members (user_id);

-- Transitional user fill (ADR-001 §2.2): legacy-style INSERTs resolve the
-- member user from student_profile_id. Dropped in the contract phase.
CREATE OR REPLACE FUNCTION quizopia_classroom_members_user_tg() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.user_id IS NULL THEN
        NEW.user_id := quizopia_user_for_student(NEW.student_profile_id);
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER tg_classroom_members_user
    BEFORE INSERT OR UPDATE ON classroom_members
    FOR EACH ROW EXECUTE FUNCTION quizopia_classroom_members_user_tg();

-- ---------------------------------------------------------------------
-- 3. attempts.user_id
-- ---------------------------------------------------------------------
ALTER TABLE attempts ADD COLUMN user_id BIGINT;

UPDATE attempts a
SET user_id = sp.user_id
FROM student_profiles sp
WHERE sp.id = a.student_profile_id
  AND sp.school_id = a.school_id
  AND a.user_id IS NULL;

ALTER TABLE attempts
    ALTER COLUMN user_id SET NOT NULL,
    ADD CONSTRAINT fk_attempts_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE RESTRICT;

CREATE INDEX idx_attempts_user ON attempts (user_id);

-- Transitional user fill (ADR-001 §2.2): legacy-style INSERTs resolve the
-- attempt owner from student_profile_id. Dropped in the contract phase.
CREATE OR REPLACE FUNCTION quizopia_attempts_user_tg() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.user_id IS NULL THEN
        NEW.user_id := quizopia_user_for_student(NEW.student_profile_id);
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER tg_attempts_user
    BEFORE INSERT OR UPDATE ON attempts
    FOR EACH ROW EXECUTE FUNCTION quizopia_attempts_user_tg();

-- ---------------------------------------------------------------------
-- 4. Verification
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF (SELECT COUNT(*) FROM classrooms WHERE workspace_id IS NULL) > 0 THEN
        RAISE EXCEPTION 'V12 verification: classrooms without workspace mapping found';
    END IF;
    IF (SELECT COUNT(*) FROM classroom_members WHERE user_id IS NULL) > 0 THEN
        RAISE EXCEPTION 'V12 verification: classroom members without user mapping found';
    END IF;
    IF (SELECT COUNT(*) FROM attempts WHERE user_id IS NULL) > 0 THEN
        RAISE EXCEPTION 'V12 verification: attempts without user mapping found';
    END IF;

    RAISE NOTICE 'V12: classrooms, classroom members and attempts mapped to user/workspace ownership';
END $$;
