-- =====================================================================
-- V16 (issue #4 P0-1): User-based attempts — the user becomes the primary
-- attempt identity; StudentProfile/School remain OPTIONAL legacy provenance.
--
-- A newly registered public user (no school, no StudentProfile) can create
-- personal-workspace exams AND participate in PUBLIC sessions with the same
-- account. Attempt/Grade history is preserved verbatim: no row ids change,
-- no data is rewritten — only constraints move from student_profile_id to
-- the user_id that V12 already backfilled (NOT NULL).
--
-- Also grants the learner attempt permissions to the TEACHER role so one
-- account can act as creator and learner (dual capability, server-side).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. student_profile_id becomes nullable legacy provenance.
--    (school_id was already made nullable by V15.)
-- ---------------------------------------------------------------------
ALTER TABLE attempts ALTER COLUMN student_profile_id DROP NOT NULL;

-- ---------------------------------------------------------------------
-- 2. Uniqueness moves from (session, student_profile) to (session, user).
--    Old rows satisfy the new constraints because user_id was backfilled
--    1:1 from student_profile_id (V12) and one profile belongs to one user.
-- ---------------------------------------------------------------------
ALTER TABLE attempts DROP CONSTRAINT uk_attempts_session_student_number;
ALTER TABLE attempts ADD CONSTRAINT uk_attempts_session_user_number
    UNIQUE (exam_session_id, user_id, attempt_number);

DROP INDEX uk_attempts_one_active_per_session_student;
CREATE UNIQUE INDEX uk_attempts_one_active_per_session_user
    ON attempts (exam_session_id, user_id) WHERE status = 'IN_PROGRESS';

DROP INDEX uk_attempts_student_submit_key;
CREATE UNIQUE INDEX uk_attempts_user_submit_key
    ON attempts (user_id, submission_idempotency_key) WHERE submission_idempotency_key IS NOT NULL;

-- ---------------------------------------------------------------------
-- 3. Dual creator+learner capability: the TEACHER role gains the three
--    learner-only attempt permissions (idempotent via the composite PK).
--    No admin powers are granted — these are learner-scoped permissions.
-- ---------------------------------------------------------------------
INSERT INTO role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, NULL
FROM roles r
JOIN permissions p ON p.code IN ('ATTEMPT_START', 'ATTEMPT_ANSWER_SAVE', 'ATTEMPT_SUBMIT')
WHERE r.code = 'TEACHER'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- 4. Verification — user identity complete, new uniqueness holds.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM attempts WHERE user_id IS NULL) THEN
        RAISE EXCEPTION 'V16 verification: attempts without user_id found';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM attempts a
        JOIN attempts b
          ON b.exam_session_id = a.exam_session_id
         AND b.user_id = a.user_id
         AND b.attempt_number = a.attempt_number
         AND b.id <> a.id
    ) THEN
        RAISE EXCEPTION 'V16 verification: duplicate (session, user, number) — backfill inconsistency';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM attempts a
        JOIN attempts b
          ON b.exam_session_id = a.exam_session_id
         AND b.user_id = a.user_id
         AND b.status = 'IN_PROGRESS'
         AND b.id <> a.id
        WHERE a.status = 'IN_PROGRESS'
    ) THEN
        RAISE EXCEPTION 'V16 verification: multiple IN_PROGRESS attempts per (session, user)';
    END IF;

    RAISE NOTICE 'V16: attempts are user-based; profile/school provenance is optional';
END $$;
