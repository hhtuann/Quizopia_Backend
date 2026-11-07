-- =====================================================================
-- V24 (user-centric refactor, EPIC: Remove Organization & Workspace):
-- EXPAND + BACKFILL + VERIFY.
--
-- Target model (see ADR-005):
--   * Users hold STUDENT / TEACHER roles only. ACADEMIC_ADMIN and
--     SYSTEM_ADMIN leave the role model; dependent admin APIs are
--     decommissioned in the same release.
--   * Subjects / grade levels / exam purposes become a GLOBAL academic
--     catalog (school-scope removed), still referenced (optionally) by
--     question banks and exams.
--   * Resource ownership moves fully to users: question_banks /
--     exams (created_by_user_id), classrooms (owner_user_id),
--     exam_sessions (created_by). The workspace columns become
--     redundant and are dropped in V25.
--
-- This migration only RESHAPES DATA; every table/column that V25 drops
-- still exists afterwards, so the migration is a single safe stopping
-- point. Verification DO-blocks FAIL the migration on any inconsistency
-- (never silently drop or reassign).
--
-- Ambiguity rule (per refactor brief): if the same user owns two
-- resources with the same business code in different workspaces (legal
-- under the old (workspace, creator, code) uniqueness), the target
-- (creator, code) uniqueness is ambiguous — the migration STOPS with an
-- explicit error instead of silently renaming business identifiers.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. RBAC consolidation: STUDENT / TEACHER only
-- ---------------------------------------------------------------------

-- 1.1 ACADEMIC_ADMIN holders keep content management ability: grant
--     TEACHER where not already held (academic affairs ≈ teacher in the
--     user-centric model). Deterministic, idempotent.
INSERT INTO user_roles (user_id, role_id, assigned_at)
SELECT DISTINCT ur.user_id, r_teacher.id, now()
FROM user_roles ur
JOIN roles r_admin ON r_admin.id = ur.role_id AND r_admin.code = 'ACADEMIC_ADMIN'
JOIN roles r_teacher ON r_teacher.code = 'TEACHER'
WHERE NOT EXISTS (
    SELECT 1 FROM user_roles ur2
    JOIN roles r2 ON r2.id = ur2.role_id
    WHERE ur2.user_id = ur.user_id AND r2.code = 'TEACHER'
)
ON CONFLICT DO NOTHING;

-- 1.2 Any user left without STUDENT or TEACHER (e.g. SYSTEM_ADMIN-only
--     operators) gets the STUDENT base role so login keeps working.
--     Nobody gains content permissions from this grant (STUDENT is the
--     least-privileged default every public registration receives).
INSERT INTO user_roles (user_id, role_id, assigned_at)
SELECT u.id, r_student.id, now()
FROM users u
JOIN roles r_student ON r_student.code = 'STUDENT'
WHERE NOT EXISTS (
    SELECT 1 FROM user_roles ur
    JOIN roles r ON r.id = ur.role_id
    WHERE ur.user_id = u.id AND r.code IN ('STUDENT', 'TEACHER')
)
ON CONFLICT DO NOTHING;

-- 1.3 Remove the two admin roles: assignments, then grants, then rows.
DELETE FROM user_roles
WHERE role_id IN (SELECT id FROM roles WHERE code IN ('SYSTEM_ADMIN', 'ACADEMIC_ADMIN'));

DELETE FROM role_permissions
WHERE role_id IN (SELECT id FROM roles WHERE code IN ('SYSTEM_ADMIN', 'ACADEMIC_ADMIN'));

DELETE FROM roles WHERE code IN ('SYSTEM_ADMIN', 'ACADEMIC_ADMIN');

-- 1.4 Decommission permissions no role grants anymore (user management,
--     school/profile administration, admin-only result access...).
--     TEACHER/STUDENT grants are untouched.
DELETE FROM permissions p
WHERE NOT EXISTS (
    SELECT 1 FROM role_permissions rp WHERE rp.permission_id = p.id
);

-- ---------------------------------------------------------------------
-- 2. Global academic catalog (subjects / grade levels / purposes)
-- ---------------------------------------------------------------------

-- 2.1 Replace school-composite FKs with simple FKs BEFORE any reshaping
--     (the composites pin rows to same-school pairs, which the dedupe
--     below must be free to break).
ALTER TABLE subjects DROP CONSTRAINT fk_subjects_grade_level_school;
ALTER TABLE subjects
    ADD CONSTRAINT fk_subjects_grade_level
    FOREIGN KEY (grade_level_id) REFERENCES grade_levels (id) ON DELETE RESTRICT;

ALTER TABLE question_banks DROP CONSTRAINT fk_question_banks_subject_school;
ALTER TABLE question_banks
    ADD CONSTRAINT fk_question_banks_subject
    FOREIGN KEY (subject_id) REFERENCES subjects (id) ON DELETE RESTRICT;

ALTER TABLE exams DROP CONSTRAINT fk_exams_subject_school;
ALTER TABLE exams
    ADD CONSTRAINT fk_exams_subject
    FOREIGN KEY (subject_id) REFERENCES subjects (id) ON DELETE RESTRICT;

ALTER TABLE exams DROP CONSTRAINT fk_exams_purpose_school;
ALTER TABLE exams
    ADD CONSTRAINT fk_exams_purpose
    FOREIGN KEY (purpose_id) REFERENCES exam_purposes (id) ON DELETE RESTRICT;

-- attempts: the school-composite session FK becomes a simple session FK.
ALTER TABLE attempts DROP CONSTRAINT fk_attempts_session_school;
ALTER TABLE attempts
    ADD CONSTRAINT fk_attempts_session
    FOREIGN KEY (exam_session_id) REFERENCES exam_sessions (id) ON DELETE RESTRICT;

-- exam_session_classes: plain session/classroom FKs replace the
-- school-composite ones (composite + school_id dropped in V25).
ALTER TABLE exam_session_classes
    ADD CONSTRAINT fk_esc_session
    FOREIGN KEY (exam_session_id) REFERENCES exam_sessions (id) ON DELETE CASCADE;
ALTER TABLE exam_session_classes
    ADD CONSTRAINT fk_esc_classroom
    FOREIGN KEY (classroom_id) REFERENCES classrooms (id) ON DELETE CASCADE;

-- 2.2 Grade levels: dedupe by code (case-insensitive) across schools.
--     Canonical = MIN(id). Subjects are remapped to the canonical rows
--     BEFORE the non-canonical rows are deleted.
UPDATE subjects s
SET grade_level_id = canon.canonical_id
FROM (
    SELECT g.id AS old_id,
           MIN(g.id) OVER (PARTITION BY LOWER(g.code)) AS canonical_id
    FROM grade_levels g
) canon
WHERE s.grade_level_id = canon.old_id
  AND canon.canonical_id <> canon.old_id;

DELETE FROM grade_levels g
USING grade_levels gmin
WHERE LOWER(gmin.code) = LOWER(g.code)
  AND gmin.id < g.id;

DROP INDEX IF EXISTS uk_grade_levels_school_code_ci;
ALTER TABLE grade_levels DROP CONSTRAINT fk_grade_levels_school;
ALTER TABLE grade_levels DROP CONSTRAINT uk_grade_levels_school_id;
ALTER TABLE grade_levels DROP COLUMN school_id;

CREATE UNIQUE INDEX uk_grade_levels_code_ci ON grade_levels (LOWER(code));

-- 2.3 Subjects: dedupe by (canonical grade level, code). Remap
--     question bank / exam references, then delete duplicates.
WITH canon AS (
    SELECT MIN(s.id) AS keep_id, s.grade_level_id, LOWER(s.code) AS code_key
    FROM subjects s
    GROUP BY s.grade_level_id, LOWER(s.code)
    HAVING COUNT(*) > 1
)
UPDATE question_banks qb
SET subject_id = canon.keep_id
FROM subjects old_s
JOIN canon ON canon.grade_level_id = old_s.grade_level_id
         AND canon.code_key = LOWER(old_s.code)
WHERE qb.subject_id = old_s.id
  AND old_s.id <> canon.keep_id;

WITH canon AS (
    SELECT MIN(s.id) AS keep_id, s.grade_level_id, LOWER(s.code) AS code_key
    FROM subjects s
    GROUP BY s.grade_level_id, LOWER(s.code)
    HAVING COUNT(*) > 1
)
UPDATE exams e
SET subject_id = canon.keep_id
FROM subjects old_s
JOIN canon ON canon.grade_level_id = old_s.grade_level_id
         AND canon.code_key = LOWER(old_s.code)
WHERE e.subject_id = old_s.id
  AND old_s.id <> canon.keep_id;

DELETE FROM subjects s
USING subjects smin
WHERE smin.grade_level_id = s.grade_level_id
  AND LOWER(smin.code) = LOWER(s.code)
  AND smin.id < s.id;

ALTER TABLE subjects DROP CONSTRAINT uk_subjects_school_id;
ALTER TABLE subjects DROP COLUMN school_id;

CREATE UNIQUE INDEX uk_subjects_grade_code_ci
    ON subjects (grade_level_id, LOWER(code));

-- 2.4 Exam purposes: dedupe by code (case-insensitive).
UPDATE exams e
SET purpose_id = canon.keep_id
FROM exam_purposes old_p
JOIN (
    SELECT MIN(p.id) AS keep_id, LOWER(p.code) AS code_key
    FROM exam_purposes p
    GROUP BY LOWER(p.code)
    HAVING COUNT(*) > 1
) canon ON canon.code_key = LOWER(old_p.code)
WHERE e.purpose_id = old_p.id
  AND old_p.id <> canon.keep_id;

DELETE FROM exam_purposes p
USING exam_purposes pmin
WHERE LOWER(pmin.code) = LOWER(p.code)
  AND pmin.id < p.id;

ALTER TABLE exam_purposes DROP CONSTRAINT fk_exam_purposes_school;
ALTER TABLE exam_purposes DROP CONSTRAINT uk_exam_purposes_school_id;
ALTER TABLE exam_purposes DROP COLUMN school_id;

CREATE UNIQUE INDEX uk_exam_purposes_code_ci ON exam_purposes (LOWER(code));

-- 2.5 Seed the global catalog on fresh databases (no schools ever
--     existed → empty tables). Idempotent; never re-seeds an upgraded
--     database, whose catalog came from the dedupe above.
INSERT INTO grade_levels (code, name, sort_order)
SELECT * FROM (VALUES
    ('G10', 'Grade 10', 10),
    ('G11', 'Grade 11', 11),
    ('G12', 'Grade 12', 12)
) AS seed(code, name, sort_order)
WHERE NOT EXISTS (SELECT 1 FROM grade_levels);

INSERT INTO subjects (grade_level_id, code, name)
SELECT gl.id, v.code, v.name
FROM (VALUES
    ('TOAN',    'Mathematics'),
    ('VAN',     'Literature'),
    ('ANH',     'English'),
    ('LY',      'Physics'),
    ('HOA',     'Chemistry'),
    ('SINH',    'Biology'),
    ('SU',      'History'),
    ('DIA',     'Geography'),
    ('GDCD',    'Civic Education'),
    ('TIN',     'Informatics'),
    ('CN',      'Technology'),
    ('TD',      'Physical Education'),
    ('GDQP',    'National Defence Education'),
    ('NHAC',    'Music'),
    ('MT',      'Fine Arts')
) AS v(code, name)
CROSS JOIN grade_levels gl
WHERE NOT EXISTS (SELECT 1 FROM subjects);

INSERT INTO exam_purposes (code, title, position)
SELECT * FROM (VALUES
    ('MIDTERM',  'Midterm Test', 1),
    ('FINAL',    'Final Exam', 2),
    ('QUIZ',     'Quiz', 3),
    ('PRACTICE', 'Practice', 4)
) AS seed(code, title, position)
WHERE NOT EXISTS (SELECT 1 FROM exam_purposes);

-- ---------------------------------------------------------------------
-- 3. Ownership expand: user-scoped uniqueness + indexes
-- ---------------------------------------------------------------------

-- 3.1 Ambiguity pre-check: the same user must not own two resources
--     with the same code (legal across workspaces before, ambiguous
--     now). STOP before any destructive step in V25.
DO $$
BEGIN
    IF EXISTS (
        SELECT qb.created_by_user_id, LOWER(qb.code)
        FROM question_banks qb
        GROUP BY qb.created_by_user_id, LOWER(qb.code)
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'V24 verification: user owns duplicate question bank codes (ambiguous ownership) — resolve before migrating';
    END IF;
    IF EXISTS (
        SELECT e.created_by_user_id, LOWER(e.code)
        FROM exams e
        GROUP BY e.created_by_user_id, LOWER(e.code)
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'V24 verification: user owns duplicate exam codes (ambiguous ownership) — resolve before migrating';
    END IF;
    IF EXISTS (
        SELECT s.created_by, LOWER(s.code)
        FROM exam_sessions s
        GROUP BY s.created_by, LOWER(s.code)
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'V24 verification: user owns duplicate session codes (ambiguous ownership) — resolve before migrating';
    END IF;
END $$;

-- 3.2 Relax workspace columns (dropped in V25; nothing writes them
--     between this migration and the new code).
ALTER TABLE question_banks ALTER COLUMN workspace_id DROP NOT NULL;
ALTER TABLE exams ALTER COLUMN workspace_id DROP NOT NULL;
ALTER TABLE classrooms ALTER COLUMN workspace_id DROP NOT NULL;

CREATE UNIQUE INDEX uk_question_banks_creator_code_ci
    ON question_banks (created_by_user_id, LOWER(code));
CREATE UNIQUE INDEX uk_exams_creator_code_ci
    ON exams (created_by_user_id, LOWER(code));
CREATE UNIQUE INDEX uk_exam_sessions_creator_code_ci
    ON exam_sessions (created_by, LOWER(code));

CREATE INDEX idx_question_banks_creator ON question_banks (created_by_user_id);
CREATE INDEX idx_exams_creator_status ON exams (created_by_user_id, status);
CREATE INDEX idx_exam_sessions_creator ON exam_sessions (created_by, created_at);

-- ---------------------------------------------------------------------
-- 4. Verification — fail the migration on any inconsistency.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    -- 4.1 Role model: only STUDENT/TEACHER remain; every user has one.
    IF EXISTS (SELECT 1 FROM roles WHERE code IN ('SYSTEM_ADMIN', 'ACADEMIC_ADMIN')) THEN
        RAISE EXCEPTION 'V24 verification: admin roles still present';
    END IF;
    IF EXISTS (
        SELECT 1 FROM users u
        WHERE NOT EXISTS (
            SELECT 1 FROM user_roles ur
            JOIN roles r ON r.id = ur.role_id
            WHERE ur.user_id = u.id AND r.code IN ('STUDENT', 'TEACHER')
        )
    ) THEN
        RAISE EXCEPTION 'V24 verification: users without STUDENT/TEACHER role found';
    END IF;
    IF EXISTS (
        SELECT 1 FROM permissions p
        WHERE NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.permission_id = p.id)
    ) THEN
        RAISE EXCEPTION 'V24 verification: orphan permissions remain';
    END IF;

    -- 4.2 Global catalog: uniques hold, no dangling references.
    IF (SELECT COUNT(*) FROM grade_levels) <> (SELECT COUNT(DISTINCT LOWER(code)) FROM grade_levels) THEN
        RAISE EXCEPTION 'V24 verification: grade level code collision';
    END IF;
    IF EXISTS (
        SELECT 1 FROM subjects s
        WHERE NOT EXISTS (SELECT 1 FROM grade_levels g WHERE g.id = s.grade_level_id)
    ) THEN
        RAISE EXCEPTION 'V24 verification: subjects with dangling grade level';
    END IF;
    IF EXISTS (
        SELECT 1 FROM question_banks qb
        WHERE qb.subject_id IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM subjects s WHERE s.id = qb.subject_id)
    ) THEN
        RAISE EXCEPTION 'V24 verification: question banks with dangling subject';
    END IF;
    IF EXISTS (
        SELECT 1 FROM exams e
        WHERE (e.subject_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM subjects s WHERE s.id = e.subject_id))
           OR (e.purpose_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM exam_purposes p WHERE p.id = e.purpose_id))
    ) THEN
        RAISE EXCEPTION 'V24 verification: exams with dangling subject/purpose';
    END IF;

    -- 4.3 Ownership counts for the migration log (NOT NULL columns
    --     guarantee user ownership on every row).
    RAISE NOTICE 'V24: % question banks, % exams, % classrooms, % exam sessions on user ownership',
        (SELECT COUNT(*) FROM question_banks),
        (SELECT COUNT(*) FROM exams),
        (SELECT COUNT(*) FROM classrooms),
        (SELECT COUNT(*) FROM exam_sessions);
END $$;
