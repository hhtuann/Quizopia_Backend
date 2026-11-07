-- Identity M5: no inferred provenance for existing password hashes or Google links.
ALTER TABLE users ADD COLUMN local_credential_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE users ADD CONSTRAINT chk_users_local_credential_state
    CHECK (local_credential_state IN ('UNKNOWN', 'ENABLED', 'DISABLED'));

-- Existing short-lived proofs did not capture an epoch: require a fresh OAuth attempt.
DELETE FROM oauth_login_codes;
DELETE FROM oauth_link_requests;
ALTER TABLE oauth_login_codes ADD COLUMN credential_epoch INTEGER NOT NULL;
ALTER TABLE oauth_login_codes ADD CONSTRAINT chk_oauth_login_codes_credential_epoch
    CHECK (credential_epoch >= 0);
ALTER TABLE oauth_link_requests ADD COLUMN credential_epoch INTEGER NOT NULL;
ALTER TABLE oauth_link_requests ADD CONSTRAINT chk_oauth_link_requests_credential_epoch
    CHECK (credential_epoch >= 0);

ALTER TABLE identity_audit_events DROP CONSTRAINT chk_identity_audit_events_type;
ALTER TABLE identity_audit_events ADD CONSTRAINT chk_identity_audit_events_type
    CHECK (event_type IN (
        'TEACHER_SELF_UPGRADE', 'TEACHER_SELF_UPGRADE_IDEMPOTENT',
        'TEACHER_SELF_UPGRADE_REACTIVATED', 'PROFILE_UPDATED',
        'CREDENTIAL_PROOF_PROMOTED', 'PASSWORD_CHANGED', 'PASSWORD_RESET'
    ));
