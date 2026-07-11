-- ============================================================
-- Quizopia Flyway Migration V3
-- Purpose: Seed the foundational RBAC catalog.
--
--   - 4 roles:  SYSTEM_ADMIN, ACADEMIC_ADMIN, TEACHER, STUDENT
--   - 84 permissions (catalog approved in docs/security.md)
--   - role-permission matrix approved in docs/security.md:
--         SYSTEM_ADMIN   -> 13
--         ACADEMIC_ADMIN -> 54
--         TEACHER        -> 50
--         STUDENT        ->  9
--
-- Rules followed:
--   - No hard-coded numeric IDs; everything is resolved by `code`.
--   - `granted_by` is left NULL because this is system seed data.
--   - Timestamps rely on the schema defaults (CURRENT_TIMESTAMP).
--   - No role PROCTOR and no permission ATTEMPT_CANCEL.
--   - No implicit role hierarchy.
--
-- Source of truth: docs/security.md (sections 12.2 and 12.6).
-- Do not modify this file after it has been applied.
-- ============================================================

-- ============================================================
-- 1. ROLES
-- The 4 foundational roles. No hierarchy is implied.
-- ============================================================

INSERT INTO roles (code, name, description) VALUES
    ('SYSTEM_ADMIN',
     'System Administrator',
     'Manages accounts, roles, refresh sessions and platform operations. Creates schools during bootstrap. Does not implicitly hold academic, exam or grading permissions.'),
    ('ACADEMIC_ADMIN',
     'Academic Administrator',
     'Manages academic affairs within an assigned school scope: grade levels, subjects, profiles, classrooms, assignments, exam coordination, results and reporting. Does not create accounts, edit questions, publish exams or change grades.'),
    ('TEACHER',
     'Teacher',
     'Manages question banks, exams and exam sessions under ownership/assignment. Monitors exam sessions via EXAM_SESSION_MONITOR. Views attempts, grades, releases results and reads reports.'),
    ('STUDENT',
     'Student',
     'Views exams and sessions they may join. Starts attempts, reads/autosaves answers and submits. Views their own released grades per exam policy.');

-- ============================================================
-- 2. PERMISSIONS
-- Catalog of 84 permissions, grouped per docs/security.md 12.2.
-- Permission names only describe the action; ownership, assignment,
-- school scope, participation, resource state and time window are
-- enforced by service policy, not by the permission name.
-- ============================================================

-- ------------------------------------------------------------
-- 2.1. Identity & System (12)
-- ------------------------------------------------------------
INSERT INTO permissions (code, name, description) VALUES
    ('USER_CREATE',          'Create User',          'Create a user account.'),
    ('USER_READ',            'Read User',            'Read user account information.'),
    ('USER_UPDATE',          'Update User',          'Update non-status user account fields.'),
    ('USER_ACTIVATE',        'Activate User',        'Activate a user account.'),
    ('USER_DISABLE',         'Disable User',         'Disable a user account.'),
    ('USER_ENABLE',          'Enable User',          'Re-enable a user account.'),
    ('USER_LOCK',            'Lock User',            'Lock a user account.'),
    ('USER_UNLOCK',          'Unlock User',          'Unlock a user account.'),
    ('USER_ROLE_ASSIGN',     'Assign User Role',     'Assign or revoke roles on a user account.'),
    ('USER_SESSION_REVOKE',  'Revoke User Session',  'Revoke refresh sessions of a user.'),
    ('ROLE_READ',            'Read Role',            'Read role catalog.'),
    ('PERMISSION_READ',      'Read Permission',      'Read permission catalog.');

-- ------------------------------------------------------------
-- 2.2. Academic — School, Grade Level, Subject (11)
-- ------------------------------------------------------------
INSERT INTO permissions (code, name, description) VALUES
    ('SCHOOL_CREATE',         'Create School',          'Create a school during bootstrap.'),
    ('SCHOOL_READ',           'Read School',            'Read school information.'),
    ('SCHOOL_UPDATE',         'Update School',          'Update non-status school fields.'),
    ('SCHOOL_STATUS_UPDATE',  'Update School Status',   'Change school status.'),
    ('GRADE_LEVEL_CREATE',    'Create Grade Level',     'Create a grade level within a school.'),
    ('GRADE_LEVEL_READ',      'Read Grade Level',       'Read grade level information.'),
    ('GRADE_LEVEL_UPDATE',    'Update Grade Level',     'Update a grade level.'),
    ('SUBJECT_CREATE',        'Create Subject',         'Create a subject within a school and grade level.'),
    ('SUBJECT_READ',          'Read Subject',           'Read subject information.'),
    ('SUBJECT_UPDATE',        'Update Subject',         'Update non-status subject fields.'),
    ('SUBJECT_STATUS_UPDATE', 'Update Subject Status',  'Change subject status.');

-- ------------------------------------------------------------
-- 2.3. Academic — Profiles, Classrooms, Assignments (17)
-- ------------------------------------------------------------
INSERT INTO permissions (code, name, description) VALUES
    ('TEACHER_PROFILE_CREATE',         'Create Teacher Profile',        'Create a teacher profile.'),
    ('TEACHER_PROFILE_READ',           'Read Teacher Profile',          'Read teacher profile information.'),
    ('TEACHER_PROFILE_UPDATE',         'Update Teacher Profile',        'Update non-status teacher profile fields.'),
    ('TEACHER_PROFILE_STATUS_UPDATE',  'Update Teacher Profile Status', 'Change teacher profile status.'),
    ('STUDENT_PROFILE_CREATE',         'Create Student Profile',        'Create a student profile.'),
    ('STUDENT_PROFILE_READ',           'Read Student Profile',          'Read student profile information.'),
    ('STUDENT_PROFILE_UPDATE',         'Update Student Profile',        'Update non-status student profile fields.'),
    ('STUDENT_PROFILE_STATUS_UPDATE',  'Update Student Profile Status', 'Change student profile status.'),
    ('CLASSROOM_CREATE',               'Create Classroom',              'Create a classroom.'),
    ('CLASSROOM_READ',                 'Read Classroom',                'Read classroom information.'),
    ('CLASSROOM_UPDATE',               'Update Classroom',              'Update non-status classroom fields.'),
    ('CLASSROOM_STATUS_UPDATE',        'Update Classroom Status',       'Change classroom status.'),
    ('CLASSROOM_MEMBER_READ',          'Read Classroom Member',         'Read classroom membership.'),
    ('CLASSROOM_MEMBER_ADD',           'Add Classroom Member',          'Add a member to a classroom.'),
    ('CLASSROOM_MEMBER_REMOVE',        'Remove Classroom Member',       'Remove a member from a classroom.'),
    ('CLASSROOM_TEACHER_ASSIGN',       'Assign Classroom Teacher',      'Assign a homeroom teacher to a classroom.'),
    ('TEACHER_SUBJECT_ASSIGN',         'Assign Teacher Subject',        'Assign subjects to a teacher profile.');

-- ------------------------------------------------------------
-- 2.4. Question Bank (8)
-- ------------------------------------------------------------
INSERT INTO permissions (code, name, description) VALUES
    ('QUESTION_BANK_CREATE',         'Create Question Bank',        'Create a question bank.'),
    ('QUESTION_BANK_READ',           'Read Question Bank',          'Read question bank information.'),
    ('QUESTION_BANK_UPDATE',         'Update Question Bank',        'Update question bank name, description and visibility. Status changes use QUESTION_BANK_STATUS_UPDATE.'),
    ('QUESTION_BANK_STATUS_UPDATE',  'Update Question Bank Status', 'Change question bank status.'),
    ('QUESTION_CREATE',              'Create Question',             'Create a question.'),
    ('QUESTION_READ',                'Read Question',               'Read question information.'),
    ('QUESTION_UPDATE',              'Update Question',             'Update a question.'),
    ('QUESTION_ARCHIVE',             'Archive Question',            'Archive a question.');

-- ------------------------------------------------------------
-- 2.5. Exam — Purpose and Content (9)
-- ------------------------------------------------------------
INSERT INTO permissions (code, name, description) VALUES
    ('EXAM_PURPOSE_CREATE',   'Create Exam Purpose',    'Create an exam purpose.'),
    ('EXAM_PURPOSE_READ',     'Read Exam Purpose',      'Read exam purpose information.'),
    ('EXAM_PURPOSE_UPDATE',   'Update Exam Purpose',    'Update an exam purpose.'),
    ('EXAM_CREATE',           'Create Exam',            'Create an exam.'),
    ('EXAM_READ',             'Read Exam',              'Read exam information.'),
    ('EXAM_UPDATE',           'Update Exam',            'Update an exam.'),
    ('EXAM_VERSION_CREATE',   'Create Exam Version',    'Create a new version of an exam.'),
    ('EXAM_PUBLISH',          'Publish Exam',           'Publish an exam version (immutable snapshot).'),
    ('EXAM_ARCHIVE',          'Archive Exam',           'Archive an exam.');

-- ------------------------------------------------------------
-- 2.6. Exam — Sessions and Participants (14)
-- ------------------------------------------------------------
INSERT INTO permissions (code, name, description) VALUES
    ('EXAM_SESSION_CREATE',              'Create Exam Session',              'Create an exam session.'),
    ('EXAM_SESSION_READ',                'Read Exam Session',                'Read exam session information.'),
    ('EXAM_SESSION_UPDATE',              'Update Exam Session',              'Update an exam session.'),
    ('EXAM_SESSION_SCHEDULE',            'Schedule Exam Session',            'Schedule an exam session.'),
    ('EXAM_SESSION_OPEN',                'Open Exam Session',                'Open an exam session.'),
    ('EXAM_SESSION_CLOSE',               'Close Exam Session',               'Close an exam session.'),
    ('EXAM_SESSION_CANCEL',              'Cancel Exam Session',              'Cancel an exam session.'),
    ('EXAM_SESSION_ARCHIVE',             'Archive Exam Session',             'Archive an exam session.'),
    ('EXAM_SESSION_PARTICIPANT_READ',    'Read Exam Session Participant',    'Read exam session participants.'),
    ('EXAM_SESSION_PARTICIPANT_ADD',     'Add Exam Session Participant',     'Add a participant to an exam session.'),
    ('EXAM_SESSION_PARTICIPANT_BLOCK',   'Block Exam Session Participant',   'Block an exam session participant.'),
    ('EXAM_SESSION_PARTICIPANT_UNBLOCK', 'Unblock Exam Session Participant', 'Unblock an exam session participant.'),
    ('EXAM_SESSION_PARTICIPANT_REMOVE',  'Remove Exam Session Participant',  'Remove an exam session participant.'),
    ('EXAM_SESSION_MONITOR',             'Monitor Exam Session',             'Monitor an exam session (teacher self-proctoring).');

-- ------------------------------------------------------------
-- 2.7. Attempt (5)
-- ------------------------------------------------------------
INSERT INTO permissions (code, name, description) VALUES
    ('ATTEMPT_START',       'Start Attempt',        'Start an attempt for an eligible participant.'),
    ('ATTEMPT_READ',        'Read Attempt',         'Read attempt information.'),
    ('ATTEMPT_ANSWER_READ', 'Read Attempt Answer',  'Read attempt answers.'),
    ('ATTEMPT_ANSWER_SAVE', 'Save Attempt Answer',  'Autosave an attempt answer.'),
    ('ATTEMPT_SUBMIT',      'Submit Attempt',       'Submit an attempt (idempotent).');

-- ------------------------------------------------------------
-- 2.8. Grading (6)
-- ------------------------------------------------------------
INSERT INTO permissions (code, name, description) VALUES
    ('GRADE_READ',         'Read Grade',         'Read grade information.'),
    ('GRADE_ITEM_READ',    'Read Grade Item',    'Read per-question grade items.'),
    ('GRADE_MANUAL_SCORE', 'Manual Score Grade', 'Enter a manual score for a grade item.'),
    ('GRADE_OVERRIDE',     'Override Grade',     'Override a grade. Sensitive: must be audited.'),
    ('GRADE_FINALIZE',     'Finalize Grade',     'Finalize a grade.'),
    ('GRADE_RELEASE',      'Release Grade',      'Release a grade. Sensitive: must be audited.');

-- ------------------------------------------------------------
-- 2.9. Reporting (2)
-- ------------------------------------------------------------
INSERT INTO permissions (code, name, description) VALUES
    ('REPORT_READ',   'Read Report',   'Read reports.'),
    ('REPORT_EXPORT', 'Export Report', 'Export reports.');

-- ============================================================
-- 3. ROLE-PERMISSION MAPPING
-- Resolved by code only; granted_by is NULL (system seed).
-- granted_at relies on the schema default CURRENT_TIMESTAMP.
-- ============================================================

-- ------------------------------------------------------------
-- 3.1. SYSTEM_ADMIN — 13
-- 12 Identity & System permissions + SCHOOL_CREATE (bootstrap).
-- No other academic permissions are implied.
-- ------------------------------------------------------------
INSERT INTO role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, NULL
FROM roles r
JOIN permissions p
  ON p.code IN (
        'USER_CREATE',
        'USER_READ',
        'USER_UPDATE',
        'USER_ACTIVATE',
        'USER_DISABLE',
        'USER_ENABLE',
        'USER_LOCK',
        'USER_UNLOCK',
        'USER_ROLE_ASSIGN',
        'USER_SESSION_REVOKE',
        'ROLE_READ',
        'PERMISSION_READ',
        'SCHOOL_CREATE'
  )
WHERE r.code = 'SYSTEM_ADMIN';

-- ------------------------------------------------------------
-- 3.2. ACADEMIC_ADMIN — 54
-- ------------------------------------------------------------
INSERT INTO role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, NULL
FROM roles r
JOIN permissions p
  ON p.code IN (
        'USER_READ',
        'USER_CREATE',
        'USER_UPDATE',
        'USER_DISABLE',
        'SCHOOL_READ',
        'SCHOOL_UPDATE',
        'SCHOOL_STATUS_UPDATE',
        'GRADE_LEVEL_CREATE',
        'GRADE_LEVEL_READ',
        'GRADE_LEVEL_UPDATE',
        'SUBJECT_CREATE',
        'SUBJECT_READ',
        'SUBJECT_UPDATE',
        'SUBJECT_STATUS_UPDATE',
        'TEACHER_PROFILE_CREATE',
        'TEACHER_PROFILE_READ',
        'TEACHER_PROFILE_UPDATE',
        'TEACHER_PROFILE_STATUS_UPDATE',
        'STUDENT_PROFILE_CREATE',
        'STUDENT_PROFILE_READ',
        'STUDENT_PROFILE_UPDATE',
        'STUDENT_PROFILE_STATUS_UPDATE',
        'CLASSROOM_CREATE',
        'CLASSROOM_READ',
        'CLASSROOM_UPDATE',
        'CLASSROOM_STATUS_UPDATE',
        'CLASSROOM_MEMBER_READ',
        'CLASSROOM_MEMBER_ADD',
        'CLASSROOM_MEMBER_REMOVE',
        'CLASSROOM_TEACHER_ASSIGN',
        'TEACHER_SUBJECT_ASSIGN',
        'EXAM_PURPOSE_CREATE',
        'EXAM_PURPOSE_READ',
        'EXAM_PURPOSE_UPDATE',
        'EXAM_READ',
        'EXAM_SESSION_CREATE',
        'EXAM_SESSION_READ',
        'EXAM_SESSION_UPDATE',
        'EXAM_SESSION_SCHEDULE',
        'EXAM_SESSION_OPEN',
        'EXAM_SESSION_CLOSE',
        'EXAM_SESSION_CANCEL',
        'EXAM_SESSION_ARCHIVE',
        'EXAM_SESSION_PARTICIPANT_READ',
        'EXAM_SESSION_PARTICIPANT_ADD',
        'EXAM_SESSION_PARTICIPANT_BLOCK',
        'EXAM_SESSION_PARTICIPANT_UNBLOCK',
        'EXAM_SESSION_PARTICIPANT_REMOVE',
        'EXAM_SESSION_MONITOR',
        'ATTEMPT_READ',
        'GRADE_READ',
        'GRADE_ITEM_READ',
        'REPORT_READ',
        'REPORT_EXPORT'
  )
WHERE r.code = 'ACADEMIC_ADMIN';

-- ------------------------------------------------------------
-- 3.3. TEACHER — 50
-- ------------------------------------------------------------
INSERT INTO role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, NULL
FROM roles r
JOIN permissions p
  ON p.code IN (
        'SCHOOL_READ',
        'GRADE_LEVEL_READ',
        'SUBJECT_READ',
        'TEACHER_PROFILE_READ',
        'STUDENT_PROFILE_READ',
        'CLASSROOM_READ',
        'CLASSROOM_CREATE',
        'CLASSROOM_UPDATE',
        'CLASSROOM_MEMBER_READ',
        'CLASSROOM_MEMBER_ADD',
        'CLASSROOM_MEMBER_REMOVE',
        'QUESTION_BANK_CREATE',
        'QUESTION_BANK_READ',
        'QUESTION_BANK_UPDATE',
        'QUESTION_BANK_STATUS_UPDATE',
        'QUESTION_CREATE',
        'QUESTION_READ',
        'QUESTION_UPDATE',
        'QUESTION_ARCHIVE',
        'EXAM_PURPOSE_READ',
        'EXAM_CREATE',
        'EXAM_READ',
        'EXAM_UPDATE',
        'EXAM_VERSION_CREATE',
        'EXAM_PUBLISH',
        'EXAM_ARCHIVE',
        'EXAM_SESSION_CREATE',
        'EXAM_SESSION_READ',
        'EXAM_SESSION_UPDATE',
        'EXAM_SESSION_SCHEDULE',
        'EXAM_SESSION_OPEN',
        'EXAM_SESSION_CLOSE',
        'EXAM_SESSION_CANCEL',
        'EXAM_SESSION_ARCHIVE',
        'EXAM_SESSION_PARTICIPANT_READ',
        'EXAM_SESSION_PARTICIPANT_ADD',
        'EXAM_SESSION_PARTICIPANT_BLOCK',
        'EXAM_SESSION_PARTICIPANT_UNBLOCK',
        'EXAM_SESSION_PARTICIPANT_REMOVE',
        'EXAM_SESSION_MONITOR',
        'ATTEMPT_READ',
        'ATTEMPT_ANSWER_READ',
        'GRADE_READ',
        'GRADE_ITEM_READ',
        'GRADE_MANUAL_SCORE',
        'GRADE_OVERRIDE',
        'GRADE_FINALIZE',
        'GRADE_RELEASE',
        'REPORT_READ',
        'REPORT_EXPORT'
  )
WHERE r.code = 'TEACHER';

-- ------------------------------------------------------------
-- 3.4. STUDENT — 9
-- ------------------------------------------------------------
INSERT INTO role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, NULL
FROM roles r
JOIN permissions p
  ON p.code IN (
        'EXAM_READ',
        'EXAM_SESSION_READ',
        'ATTEMPT_START',
        'ATTEMPT_READ',
        'ATTEMPT_ANSWER_READ',
        'ATTEMPT_ANSWER_SAVE',
        'ATTEMPT_SUBMIT',
        'GRADE_READ',
        'GRADE_ITEM_READ'
  )
WHERE r.code = 'STUDENT';

-- ============================================================
-- 4. VALIDATION
-- Fail the whole migration if the seed does not match the
-- approved design in docs/security.md. All checks are by code,
-- never by numeric ID.
--
-- The permission catalog is validated two ways at once:
--   * v_perm_count     = total rows in permissions             (must be 84)
--   * v_approved_count = rows whose code is one of the 84
--                        approved catalog codes                (must be 84)
-- Together these detect every catalog drift:
--   - a missing permission            -> approved_count < 84 (and total < 84)
--   - a wrong permission code         -> approved_count < 84, total still 84
--   - a permission outside the catalog-> total > 84
--   - a code swapped for a different  -> approved_count < 84, total still 84
-- Duplicates are already rejected by uk_permissions_code (V2), so a swap
-- cannot hide behind a duplicate row.
-- ============================================================
DO $$
DECLARE
    v_role_count         INTEGER;
    v_perm_count         INTEGER;
    v_approved_count     INTEGER;
    v_system_admin       INTEGER;
    v_academic_admin     INTEGER;
    v_teacher            INTEGER;
    v_student            INTEGER;
    v_proctor            INTEGER;
    v_attempt_cancel     INTEGER;
    v_missing_roles      INTEGER;
    v_errors             TEXT := '';
BEGIN
    SELECT COUNT(*) INTO v_role_count     FROM roles;
    SELECT COUNT(*) INTO v_perm_count     FROM permissions;

    -- Count rows whose code belongs to the exact 84-code approved
    -- catalog. This list MUST stay in sync with the seed in section 2.
    SELECT COUNT(*) INTO v_approved_count
        FROM permissions
        WHERE code IN (
            -- Identity & System (12)
            'USER_CREATE', 'USER_READ', 'USER_UPDATE', 'USER_ACTIVATE',
            'USER_DISABLE', 'USER_ENABLE', 'USER_LOCK', 'USER_UNLOCK',
            'USER_ROLE_ASSIGN', 'USER_SESSION_REVOKE', 'ROLE_READ', 'PERMISSION_READ',
            -- Academic — School, Grade Level, Subject (11)
            'SCHOOL_CREATE', 'SCHOOL_READ', 'SCHOOL_UPDATE', 'SCHOOL_STATUS_UPDATE',
            'GRADE_LEVEL_CREATE', 'GRADE_LEVEL_READ', 'GRADE_LEVEL_UPDATE',
            'SUBJECT_CREATE', 'SUBJECT_READ', 'SUBJECT_UPDATE', 'SUBJECT_STATUS_UPDATE',
            -- Academic — Profiles, Classrooms, Assignments (17)
            'TEACHER_PROFILE_CREATE', 'TEACHER_PROFILE_READ', 'TEACHER_PROFILE_UPDATE', 'TEACHER_PROFILE_STATUS_UPDATE',
            'STUDENT_PROFILE_CREATE', 'STUDENT_PROFILE_READ', 'STUDENT_PROFILE_UPDATE', 'STUDENT_PROFILE_STATUS_UPDATE',
            'CLASSROOM_CREATE', 'CLASSROOM_READ', 'CLASSROOM_UPDATE', 'CLASSROOM_STATUS_UPDATE',
            'CLASSROOM_MEMBER_READ', 'CLASSROOM_MEMBER_ADD', 'CLASSROOM_MEMBER_REMOVE',
            'CLASSROOM_TEACHER_ASSIGN', 'TEACHER_SUBJECT_ASSIGN',
            -- Question Bank (8)
            'QUESTION_BANK_CREATE', 'QUESTION_BANK_READ', 'QUESTION_BANK_UPDATE', 'QUESTION_BANK_STATUS_UPDATE',
            'QUESTION_CREATE', 'QUESTION_READ', 'QUESTION_UPDATE', 'QUESTION_ARCHIVE',
            -- Exam — Purpose and Content (9)
            'EXAM_PURPOSE_CREATE', 'EXAM_PURPOSE_READ', 'EXAM_PURPOSE_UPDATE',
            'EXAM_CREATE', 'EXAM_READ', 'EXAM_UPDATE', 'EXAM_VERSION_CREATE',
            'EXAM_PUBLISH', 'EXAM_ARCHIVE',
            -- Exam — Sessions and Participants (14)
            'EXAM_SESSION_CREATE', 'EXAM_SESSION_READ', 'EXAM_SESSION_UPDATE', 'EXAM_SESSION_SCHEDULE',
            'EXAM_SESSION_OPEN', 'EXAM_SESSION_CLOSE', 'EXAM_SESSION_CANCEL', 'EXAM_SESSION_ARCHIVE',
            'EXAM_SESSION_PARTICIPANT_READ', 'EXAM_SESSION_PARTICIPANT_ADD', 'EXAM_SESSION_PARTICIPANT_BLOCK',
            'EXAM_SESSION_PARTICIPANT_UNBLOCK', 'EXAM_SESSION_PARTICIPANT_REMOVE', 'EXAM_SESSION_MONITOR',
            -- Attempt (5)
            'ATTEMPT_START', 'ATTEMPT_READ', 'ATTEMPT_ANSWER_READ', 'ATTEMPT_ANSWER_SAVE', 'ATTEMPT_SUBMIT',
            -- Grading (6)
            'GRADE_READ', 'GRADE_ITEM_READ', 'GRADE_MANUAL_SCORE', 'GRADE_OVERRIDE', 'GRADE_FINALIZE', 'GRADE_RELEASE',
            -- Reporting (2)
            'REPORT_READ', 'REPORT_EXPORT'
        );

    SELECT COUNT(*) INTO v_system_admin
        FROM role_permissions rp
        JOIN roles r ON r.id = rp.role_id
        WHERE r.code = 'SYSTEM_ADMIN';

    SELECT COUNT(*) INTO v_academic_admin
        FROM role_permissions rp
        JOIN roles r ON r.id = rp.role_id
        WHERE r.code = 'ACADEMIC_ADMIN';

    SELECT COUNT(*) INTO v_teacher
        FROM role_permissions rp
        JOIN roles r ON r.id = rp.role_id
        WHERE r.code = 'TEACHER';

    SELECT COUNT(*) INTO v_student
        FROM role_permissions rp
        JOIN roles r ON r.id = rp.role_id
        WHERE r.code = 'STUDENT';

    SELECT COUNT(*) INTO v_proctor
        FROM roles WHERE code = 'PROCTOR';

    SELECT COUNT(*) INTO v_attempt_cancel
        FROM permissions WHERE code = 'ATTEMPT_CANCEL';

    -- All four foundational role codes must exist.
    SELECT COUNT(*) INTO v_missing_roles
        FROM roles
        WHERE code IN ('SYSTEM_ADMIN', 'ACADEMIC_ADMIN', 'TEACHER', 'STUDENT');

    IF v_role_count <> 4 THEN
        v_errors := v_errors || 'Expected 4 roles, found ' || v_role_count || '. ';
    END IF;

    IF v_missing_roles <> 4 THEN
        v_errors := v_errors || 'Missing one or more foundational role codes (SYSTEM_ADMIN, ACADEMIC_ADMIN, TEACHER, STUDENT). ';
    END IF;

    IF v_perm_count <> 84 THEN
        v_errors := v_errors || 'Expected 84 permissions, found ' || v_perm_count || '. ';
    END IF;

    IF v_approved_count <> 84 THEN
        v_errors := v_errors || 'Expected 84 approved catalog codes, found ' || v_approved_count || '. ';
    END IF;

    IF v_system_admin <> 13 THEN
        v_errors := v_errors || 'SYSTEM_ADMIN expected 13 permissions, found ' || v_system_admin || '. ';
    END IF;

    IF v_academic_admin <> 54 THEN
        v_errors := v_errors || 'ACADEMIC_ADMIN expected 54 permissions, found ' || v_academic_admin || '. ';
    END IF;

    IF v_teacher <> 50 THEN
        v_errors := v_errors || 'TEACHER expected 50 permissions, found ' || v_teacher || '. ';
    END IF;

    IF v_student <> 9 THEN
        v_errors := v_errors || 'STUDENT expected 9 permissions, found ' || v_student || '. ';
    END IF;

    IF v_proctor <> 0 THEN
        v_errors := v_errors || 'Role PROCTOR must not exist. ';
    END IF;

    IF v_attempt_cancel <> 0 THEN
        v_errors := v_errors || 'Permission ATTEMPT_CANCEL must not exist. ';
    END IF;

    IF v_errors <> '' THEN
        RAISE EXCEPTION 'V3 seed validation failed: %', v_errors;
    END IF;
END $$;
