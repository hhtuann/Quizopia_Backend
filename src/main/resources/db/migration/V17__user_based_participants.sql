-- =====================================================================
-- V17 (issue #4 P0-R8): User-based participant blocking.
--
-- The User becomes the primary participant identity — exactly like Attempt
-- since V16 — so an organizer can BLOCK any known participant of a session,
-- including PUBLIC profile-less learners (no School/StudentProfile).
--
-- Changes (forward-only, single transaction, fail-fast verification):
--   1. exam_session_participants.user_id NOT NULL — backfilled 1:1 from the
--      legacy student_profiles mapping (one profile belongs to one user).
--   2. Uniqueness per (exam_session_id, user_id) — the authoritative block
--      key. The legacy (session, profile) unique remains for old clients.
--   3. Legacy columns become nullable provenance:
--      school_id (also because personal-workspace sessions have none) and
--      student_profile_id. The same-school composite FKs are replaced by
--      simple FKs so profile-less rows can exist; provenance integrity for
--      legacy rows is preserved by the backfill verification.
--   4. No row ids, statuses, blocked_at or history change.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Preflight — mapping must be complete and collision-free.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    unmapped   BIGINT;
    collisions BIGINT;
BEGIN
    SELECT COUNT(*) INTO unmapped
    FROM exam_session_participants p
    WHERE NOT EXISTS (SELECT 1 FROM student_profiles sp WHERE sp.id = p.student_profile_id);
    IF unmapped > 0 THEN
        RAISE EXCEPTION 'V17 preflight: % participant rows have no matching student_profile', unmapped;
    END IF;

    -- Two profiles of the same user in one session would collide on the new
    -- unique key. The legacy unique (session, profile) plus user_id mapping
    -- make this impossible unless data is already inconsistent.
    SELECT COUNT(*) INTO collisions
    FROM (
        SELECT exam_session_id, sp.user_id
        FROM exam_session_participants p
        JOIN student_profiles sp ON sp.id = p.student_profile_id
        GROUP BY exam_session_id, sp.user_id
        HAVING COUNT(*) > 1
    ) dup;
    IF collisions > 0 THEN
        RAISE EXCEPTION 'V17 preflight: % (session, user) collisions in legacy participants', collisions;
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 2. Additive column + backfill.
-- ---------------------------------------------------------------------
ALTER TABLE exam_session_participants ADD COLUMN user_id BIGINT;

UPDATE exam_session_participants p
SET user_id = sp.user_id
FROM student_profiles sp
WHERE sp.id = p.student_profile_id
  AND p.user_id IS NULL;

ALTER TABLE exam_session_participants
    ALTER COLUMN user_id SET NOT NULL,
    ADD CONSTRAINT fk_exam_session_participants_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE RESTRICT;

-- ---------------------------------------------------------------------
-- 3. Uniqueness by (session, user) — the authoritative block key.
-- ---------------------------------------------------------------------
CREATE UNIQUE INDEX uk_exam_session_participants_session_user
    ON exam_session_participants (exam_session_id, user_id);

-- ---------------------------------------------------------------------
-- 4. Legacy columns become optional provenance; same-school composite FKs
--    are replaced by simple FKs (profile may be absent for public users;
--    school may be absent for personal-workspace sessions).
-- ---------------------------------------------------------------------
ALTER TABLE exam_session_participants DROP CONSTRAINT fk_exam_session_participants_session_school;
ALTER TABLE exam_session_participants DROP CONSTRAINT fk_exam_session_participants_student_school;

ALTER TABLE exam_session_participants
    ALTER COLUMN school_id DROP NOT NULL,
    ALTER COLUMN student_profile_id DROP NOT NULL,
    ADD CONSTRAINT fk_exam_session_participants_session FOREIGN KEY (exam_session_id)
        REFERENCES exam_sessions (id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_exam_session_participants_student_profile FOREIGN KEY (student_profile_id)
        REFERENCES student_profiles (id) ON DELETE RESTRICT;

-- The (session, profile) unique must become partial (NULLs allowed for new
-- user-based rows) — rebuild as a partial unique index under a new name.
ALTER TABLE exam_session_participants DROP CONSTRAINT uk_exam_session_participants_session_student;
CREATE UNIQUE INDEX uk_exam_session_participants_session_student
    ON exam_session_participants (exam_session_id, student_profile_id)
    WHERE student_profile_id IS NOT NULL;

CREATE INDEX idx_exam_session_participants_user ON exam_session_participants (user_id);

COMMENT ON TABLE exam_session_participants IS
    'Session participants with user-based identity (V17, issue #4 P0-R8): user_id is the primary identity and the authoritative BLOCKED key; school_id/student_profile_id are optional legacy provenance.';

-- ---------------------------------------------------------------------
-- 3b. Transitional fill (ADR-001 §2.2 pattern): legacy-style INSERTs that
--     omit user_id get it resolved from student_profile_id. The application
--     always writes user_id explicitly; dropped in a later contract phase.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION quizopia_participants_user_tg() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.user_id IS NULL THEN
        NEW.user_id := quizopia_user_for_student(NEW.student_profile_id);
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER tg_participants_user
    BEFORE INSERT OR UPDATE ON exam_session_participants
    FOR EACH ROW EXECUTE FUNCTION quizopia_participants_user_tg();

-- ---------------------------------------------------------------------
-- 5. Verification — ids/status/history preserved; mapping complete.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM exam_session_participants WHERE user_id IS NULL) THEN
        RAISE EXCEPTION 'V17 verification: participant rows without user_id';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM exam_session_participants a
        JOIN exam_session_participants b
          ON b.exam_session_id = a.exam_session_id
         AND b.user_id = a.user_id
         AND b.id <> a.id
    ) THEN
        RAISE EXCEPTION 'V17 verification: duplicate (session, user) participants';
    END IF;

    IF EXISTS (
        SELECT 1 FROM exam_session_participants
        WHERE (status = 'BLOCKED') <> (blocked_at IS NOT NULL)
    ) THEN
        RAISE EXCEPTION 'V17 verification: status/blocked_at invariant violated';
    END IF;

    RAISE NOTICE 'V17: participants are user-based; legacy provenance preserved';
END $$;
