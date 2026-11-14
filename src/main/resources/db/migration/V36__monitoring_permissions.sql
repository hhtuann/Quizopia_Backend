-- =====================================================================
-- V36 (P4-A2/P4-A4 permissions, issues #81/#83):
--
-- Two learner-scoped permission codes for the monitoring-foundation
-- endpoints, following the V16 dual creator+learner pattern: TEACHER
-- gains them too so a teacher can run the monitored-exam flows on
-- their own sessions during demos. No admin powers are granted.
--
--   * ATTEMPT_ACTIVITY_INGEST — POST /api/attempts/{id}/activity-events
--     (append client-observed telemetry for an OWNED attempt).
--   * ATTEMPT_ACTIVITY_READ  — GET  /api/attempts/{id}/timeline and
--     /answer-history for an OWNED attempt. Teachers additionally
--     need EXAM_SESSION_MONITOR + session ownership for other users'
--     attempts (service-layer check, mirroring
--     RealtimeAuthorizationService.authorizeTeacherTopic).
--
-- Forward-only: idempotent inserts (ON CONFLICT DO NOTHING).
-- =====================================================================

INSERT INTO permissions (code, name, description) VALUES
    ('ATTEMPT_ACTIVITY_INGEST',
     'Ingest Attempt Activity Events',
     'Append client-observed activity events to an attempt the caller owns (P4-A2).'),
    ('ATTEMPT_ACTIVITY_READ',
     'Read Attempt Activity',
     'Read the activity timeline and accepted-answer history of an attempt the caller owns (P4-A4).')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, NULL
FROM roles r
JOIN permissions p ON p.code IN ('ATTEMPT_ACTIVITY_INGEST', 'ATTEMPT_ACTIVITY_READ')
WHERE r.code IN ('STUDENT', 'TEACHER')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- Verification — both codes exist and are granted to exactly STUDENT
-- and TEACHER.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF (SELECT COUNT(*) FROM permissions
        WHERE code IN ('ATTEMPT_ACTIVITY_INGEST', 'ATTEMPT_ACTIVITY_READ')) <> 2 THEN
        RAISE EXCEPTION 'V36 verification failed: permission codes missing';
    END IF;
    IF (SELECT COUNT(*) FROM role_permissions rp
        JOIN permissions p ON p.id = rp.permission_id
        JOIN roles r ON r.id = rp.role_id
        WHERE p.code IN ('ATTEMPT_ACTIVITY_INGEST', 'ATTEMPT_ACTIVITY_READ')) <> 4 THEN
        RAISE EXCEPTION 'V36 verification failed: expected exactly 4 role grants (2 codes x STUDENT,TEACHER)';
    END IF;
END $$;
