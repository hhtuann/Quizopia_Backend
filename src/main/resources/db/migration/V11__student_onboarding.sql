-- ============================================================
-- Quizopia Flyway Migration V11
-- Purpose: Student onboarding — school counters for auto-generated
--   student/teacher codes + ACADEMIC_ADMIN user-management grants.
--
-- Changes:
--   - schools.student_counter (BIGINT DEFAULT 0) — per-school sequence for student_code.
--   - schools.teacher_counter (BIGINT DEFAULT 0) — per-school sequence for teacher_code.
--   - GRANT USER_CREATE, USER_UPDATE, USER_DISABLE to ACADEMIC_ADMIN
--     (STUDENT_PROFILE_CREATE / TEACHER_PROFILE_CREATE already in V3).
--
-- Design: registration no longer auto-creates academic profiles (V11 RegistrationService fix).
--   Students self-register → PENDING (user + STUDENT role, no profile).
--   ACADEMIC_ADMIN assigns students to their school → creates student_profiles with
--   auto-generated student_code (atomic counter increment).
--
-- Do not modify V1–V10. Do not modify this file after it has been applied.
-- ============================================================

-- 1. SCHOOL COUNTERS (for auto-generated student/teacher codes)
ALTER TABLE schools ADD COLUMN student_counter BIGINT NOT NULL DEFAULT 0;
ALTER TABLE schools ADD COLUMN teacher_counter BIGINT NOT NULL DEFAULT 0;

-- 2. PERMISSION GRANTS — ACADEMIC_ADMIN gains user-management permissions
--    (already has STUDENT_PROFILE_CREATE / TEACHER_PROFILE_CREATE from V3).
INSERT INTO role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, NULL
FROM roles r
JOIN permissions p ON p.code IN ('USER_CREATE', 'USER_UPDATE', 'USER_DISABLE')
WHERE r.code = 'ACADEMIC_ADMIN'
ON CONFLICT DO NOTHING;
