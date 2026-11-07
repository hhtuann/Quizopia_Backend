-- Identity M5-A / issue 12. V21 allocated in ORG #24; V1-V20 remain unchanged.
-- Preserve every existing teacher event while allowing owner profile updates.
ALTER TABLE identity_audit_events DROP CONSTRAINT chk_identity_audit_events_type;
ALTER TABLE identity_audit_events ADD CONSTRAINT chk_identity_audit_events_type
    CHECK (event_type IN (
        'TEACHER_SELF_UPGRADE',
        'TEACHER_SELF_UPGRADE_IDEMPOTENT',
        'TEACHER_SELF_UPGRADE_REACTIVATED',
        'PROFILE_UPDATED'
    ));
