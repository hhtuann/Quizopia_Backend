-- =====================================================================
-- V25 (user-centric refactor, EPIC: Remove Organization & Workspace):
-- CONTRACT.
--
-- Drops everything the user-centric model no longer needs, AFTER V24
-- verified that all live ownership is user-based:
--   * transitional triggers + resolver functions (V10–V12/V17 era)
--   * the detached exam-versioning leftovers (V13–V15 redesign debris)
--   * workspace/org columns on content tables
--   * legacy school/profile provenance columns + composite FK targets
--   * tables: workspace_memberships, workspaces, organizations,
--     schools, teacher_profiles, student_profiles
--
-- Forward-only. No historical user content (banks, questions, exams,
-- sessions, attempts, results, classrooms, rosters) is touched.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Transitional triggers + resolver functions
-- ---------------------------------------------------------------------
DROP TRIGGER IF EXISTS tg_question_banks_ownership ON question_banks;
DROP FUNCTION IF EXISTS quizopia_question_banks_ownership_tg();
DROP TRIGGER IF EXISTS tg_exams_ownership ON exams;
DROP FUNCTION IF EXISTS quizopia_exams_ownership_tg();
DROP TRIGGER IF EXISTS tg_classrooms_workspace ON classrooms;
DROP FUNCTION IF EXISTS quizopia_classrooms_workspace_tg();
DROP TRIGGER IF EXISTS tg_classroom_members_user ON classroom_members;
DROP FUNCTION IF EXISTS quizopia_classroom_members_user_tg();
DROP TRIGGER IF EXISTS tg_attempts_user ON attempts;
DROP FUNCTION IF EXISTS quizopia_attempts_user_tg();
DROP TRIGGER IF EXISTS tg_participants_user ON exam_session_participants;
DROP FUNCTION IF EXISTS quizopia_participants_user_tg();
DROP FUNCTION IF EXISTS quizopia_org_workspace_for_school(BIGINT);
DROP FUNCTION IF EXISTS quizopia_user_for_teacher(BIGINT);
DROP FUNCTION IF EXISTS quizopia_user_for_student(BIGINT);

-- ---------------------------------------------------------------------
-- 2. Detached exam-versioning leftovers (V13–V15 redesign debris).
--    Must go FIRST: their school-composite FKs pin exams.school_id and
--    the content was already backfilled into exams/exam_sections/
--    exam_questions by V14.
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS legacy_exam_content_map;
DROP TABLE IF EXISTS exam_versions;

-- ---------------------------------------------------------------------
-- 3. Legacy provenance columns on content tables
--     (dropping a column drops its FKs, composite uniques and indexes)
-- ---------------------------------------------------------------------

-- exam_session_classes: composite school FKs first, then school_id.
ALTER TABLE exam_session_classes DROP CONSTRAINT fk_esc_session_school;
ALTER TABLE exam_session_classes DROP CONSTRAINT fk_esc_classroom_school;
ALTER TABLE exam_session_classes DROP COLUMN school_id;

-- question_banks: workspace + school-era provenance (subject stays as
-- optional global metadata).
ALTER TABLE question_banks DROP COLUMN workspace_id;
ALTER TABLE question_banks DROP COLUMN school_id;
ALTER TABLE question_banks DROP COLUMN owner_teacher_id;

-- exams: same treatment.
ALTER TABLE exams DROP COLUMN workspace_id;
ALTER TABLE exams DROP COLUMN school_id;
ALTER TABLE exams DROP COLUMN owner_teacher_id;

-- exam_sessions: school/teacher provenance (owner = created_by user).
ALTER TABLE exam_sessions DROP COLUMN school_id;
ALTER TABLE exam_sessions DROP COLUMN owner_teacher_id;

-- attempts: profile/school provenance (owner = user_id).
ALTER TABLE attempts DROP COLUMN school_id;
ALTER TABLE attempts DROP COLUMN student_profile_id;

-- exam_session_participants: user_id is the sole identity since V17.
ALTER TABLE exam_session_participants DROP COLUMN school_id;
ALTER TABLE exam_session_participants DROP COLUMN student_profile_id;

-- classroom_members: user-based membership since V12.
ALTER TABLE classroom_members DROP COLUMN school_id;
ALTER TABLE classroom_members DROP COLUMN student_profile_id;

-- classrooms: single ownership mode (owner_user_id).
ALTER TABLE classrooms DROP CONSTRAINT IF EXISTS chk_classrooms_ownership_mode;
ALTER TABLE classrooms DROP COLUMN workspace_id;
ALTER TABLE classrooms DROP COLUMN school_id;
ALTER TABLE classrooms DROP COLUMN owner_teacher_id;

-- ---------------------------------------------------------------------
-- 4. Workspace / organization / school-era tables
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS workspace_memberships;
DROP TABLE IF EXISTS workspaces;
DROP TABLE IF EXISTS organizations;
DROP TABLE IF EXISTS student_profiles;
DROP TABLE IF EXISTS teacher_profiles;
DROP TABLE IF EXISTS schools;

-- ---------------------------------------------------------------------
-- 5. Verification — target schema reached.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name IN ('organizations', 'workspaces', 'workspace_memberships',
                             'schools', 'teacher_profiles', 'student_profiles',
                             'exam_versions', 'legacy_exam_content_map')
    ) THEN
        RAISE EXCEPTION 'V25 verification: legacy tables still present';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND column_name IN ('workspace_id', 'owner_teacher_id', 'student_profile_id')
    ) THEN
        RAISE EXCEPTION 'V25 verification: legacy columns still present';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns c
        WHERE c.table_schema = 'public' AND c.column_name = 'school_id'
          AND c.table_name IN ('question_banks', 'exams', 'exam_sessions', 'attempts',
                               'exam_session_participants', 'classrooms', 'classroom_members',
                               'exam_session_classes', 'subjects', 'grade_levels', 'exam_purposes')
    ) THEN
        RAISE EXCEPTION 'V25 verification: school_id still present on content/catalog tables';
    END IF;

    RAISE NOTICE 'V25: user-centric contract complete (roles: %)', (SELECT string_agg(code, ', ') FROM roles);
END $$;
