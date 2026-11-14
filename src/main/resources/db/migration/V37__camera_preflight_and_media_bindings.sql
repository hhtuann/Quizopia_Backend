-- =====================================================================
-- V37 (P4-B camera infrastructure, issues #84–#87, ADR-008 D2/D3):
--
--   1. CAMERA_*/MONITORING_GAP activity types (server-confirmed only).
--   2. MONITORING_PREFLIGHT + MONITORING_CAMERA_MANAGE permissions
--      (V36 dual learner/teacher pattern).
--   3. One opaque LiveKit room per session (monitoring_media_rooms).
--   4. Camera preflights with one-time hashed readiness leases
--      (monitoring_preflights).
--   5. Media-identity ↔ attempt bindings with epochs
--      (monitoring_media_bindings). media_identity is a fresh opaque
--      UUID per preflight, so a late webhook can never be reattributed
--      to a different attempt of the same user.
--   6. Camera connection periods per binding (monitoring_camera_
--      connections) — one OPEN row per binding enforced by a partial
--      unique index; idempotent against duplicate webhooks via the
--      open-row unique constraint.
--   7. Webhook dedupe/replay ledger (monitoring_webhook_events).
--
-- Invariants (ADR-008):
--   * Raw lease tokens are NEVER stored — only sha256 hex hashes.
--   * Legacy sessions (no room/preflight rows) are unaffected.
--   * No frames/metadata beyond connectivity bookkeeping here.
--
-- Forward-only: new tables + constraint swap + idempotent inserts.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Activity event types: camera lifecycle (SERVER source only; the
--    client allowlist in ActivityEventType parse() rejects them).
-- ---------------------------------------------------------------------
ALTER TABLE attempt_activity_events DROP CONSTRAINT chk_aae_event_type;
ALTER TABLE attempt_activity_events ADD CONSTRAINT chk_aae_event_type CHECK (event_type IN (
    -- server-confirmed lifecycle
    'ATTEMPT_STARTED', 'ATTEMPT_RESUMED', 'ATTEMPT_SUBMITTED', 'ATTEMPT_TIMED_OUT',
    -- server-confirmed autosave acceptance
    'ANSWER_ACCEPTED',
    -- client-observed answer interactions
    'ANSWER_SELECTED', 'ANSWER_CHANGED', 'ANSWER_CLEARED',
    -- client-observed navigation/flags/autosave telemetry
    'QUESTION_NAVIGATED', 'QUESTION_FLAGGED', 'QUESTION_UNFLAGGED', 'AUTOSAVE_FAILED',
    -- client-observed browser telemetry
    'TAB_HIDDEN', 'TAB_VISIBLE', 'WINDOW_FOCUS_LOST', 'WINDOW_FOCUS_GAINED',
    'PAGE_RELOADED', 'CONNECTION_LOST', 'CONNECTION_RESTORED',
    -- server-confirmed camera lifecycle (P4-B, issues #85/#87)
    'CAMERA_CONNECTED', 'CAMERA_DISCONNECTED', 'CAMERA_RECONNECTED', 'CAMERA_REVOKED',
    'MONITORING_GAP'
));

-- ---------------------------------------------------------------------
-- 2. Permissions (V36 pattern: learner-owned; TEACHER granted too so a
--    teacher can run the monitored flows on their own sessions in demos).
-- ---------------------------------------------------------------------
INSERT INTO permissions (code, name, description) VALUES
    ('MONITORING_PREFLIGHT',
     'Camera Preflight',
     'Start/verify camera preflights and obtain media tokens for one''s own monitored attempts (P4-B2, issue #85).'),
    ('MONITORING_CAMERA_MANAGE',
     'Monitor Session Cameras',
     'Teacher-only camera status aggregation, viewer media token and participant revocation for OWNED sessions (P4-B3/B4, issues #86/#87).')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, NULL
FROM roles r
JOIN permissions p ON p.code IN ('MONITORING_PREFLIGHT', 'MONITORING_CAMERA_MANAGE')
WHERE r.code IN ('STUDENT', 'TEACHER')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- 3. One opaque room per session.
-- ---------------------------------------------------------------------
CREATE TABLE monitoring_media_rooms (
    exam_session_id BIGINT       NOT NULL,
    room_name       VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_monitoring_media_rooms PRIMARY KEY (exam_session_id),
    CONSTRAINT fk_media_rooms_session
        FOREIGN KEY (exam_session_id) REFERENCES exam_sessions(id) ON DELETE CASCADE,
    CONSTRAINT uk_media_rooms_name UNIQUE (room_name),
    CONSTRAINT chk_media_rooms_name CHECK (room_name ~ '^[a-z0-9-]{8,64}$')
);

COMMENT ON TABLE monitoring_media_rooms IS
    'P4-B1 (#84): opaque LiveKit room per session; room_name is an unguessable slug, never derived from user data.';

-- ---------------------------------------------------------------------
-- 4. Preflights + one-time hashed readiness leases.
-- ---------------------------------------------------------------------
CREATE TABLE monitoring_preflights (
    id                 UUID        NOT NULL,
    exam_session_id    BIGINT      NOT NULL,
    user_id            BIGINT      NOT NULL,
    media_identity     VARCHAR(64) NOT NULL,
    status             VARCHAR(16) NOT NULL,
    lease_hash         CHAR(64),
    lease_expires_at   TIMESTAMPTZ,
    verified_track_sid VARCHAR(64),
    expires_at         TIMESTAMPTZ NOT NULL,
    consumed_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_monitoring_preflights PRIMARY KEY (id),
    CONSTRAINT fk_preflights_session
        FOREIGN KEY (exam_session_id) REFERENCES exam_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_preflights_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_preflights_media_identity UNIQUE (media_identity),
    CONSTRAINT uk_preflights_lease_hash UNIQUE (lease_hash),
    CONSTRAINT chk_preflights_status CHECK (status IN
        ('ACTIVE', 'VERIFIED', 'CONSUMED', 'EXPIRED', 'REVOKED')),
    CONSTRAINT chk_preflights_lease_pair CHECK (
        (lease_hash IS NULL AND lease_expires_at IS NULL)
        OR (lease_hash IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);

CREATE INDEX ix_preflights_session_user_expiry
    ON monitoring_preflights (exam_session_id, user_id, expires_at);

COMMENT ON TABLE monitoring_preflights IS
    'P4-B2 (#85): camera preflight per (session,user); lease_hash is sha256 of the raw one-time readiness lease — the raw lease is only ever held by the client.';

-- ---------------------------------------------------------------------
-- 5. Media identity ↔ attempt bindings (identity created BEFORE the
--    attempt exists; attempt_id bound atomically at start).
-- ---------------------------------------------------------------------
CREATE TABLE monitoring_media_bindings (
    id              UUID        NOT NULL,
    exam_session_id BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    preflight_id    UUID        NOT NULL,
    attempt_id      BIGINT,
    media_identity  VARCHAR(64) NOT NULL,
    epoch           INTEGER     NOT NULL,
    active_from     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active_to       TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    CONSTRAINT pk_monitoring_media_bindings PRIMARY KEY (id),
    CONSTRAINT fk_bindings_session
        FOREIGN KEY (exam_session_id) REFERENCES exam_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_bindings_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_bindings_preflight
        FOREIGN KEY (preflight_id) REFERENCES monitoring_preflights(id) ON DELETE CASCADE,
    CONSTRAINT fk_bindings_attempt
        FOREIGN KEY (attempt_id) REFERENCES attempts(id) ON DELETE SET NULL,
    CONSTRAINT uk_bindings_identity_epoch UNIQUE (media_identity, epoch),
    CONSTRAINT chk_bindings_epoch CHECK (epoch >= 1),
    CONSTRAINT chk_bindings_unbound_still_active CHECK (attempt_id IS NOT NULL OR active_to IS NULL)
);

CREATE INDEX ix_bindings_session_active ON monitoring_media_bindings (exam_session_id, active_to);
CREATE INDEX ix_bindings_attempt ON monitoring_media_bindings (attempt_id) WHERE attempt_id IS NOT NULL;

COMMENT ON TABLE monitoring_media_bindings IS
    'P4-B2/B4 (#85/#87): media identity is per-preflight (fresh UUID), bound to the exact attempt at start; late webhooks resolve by identity+epoch and can never reattribute across attempts.';

-- ---------------------------------------------------------------------
-- 6. Camera connection periods (gap evidence).
-- ---------------------------------------------------------------------
CREATE TABLE monitoring_camera_connections (
    id                UUID        NOT NULL,
    binding_id        UUID        NOT NULL,
    track_sid         VARCHAR(64),
    connected_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    disconnected_at   TIMESTAMPTZ,
    last_verified_at  TIMESTAMPTZ,
    close_reason      VARCHAR(32),
    CONSTRAINT pk_monitoring_camera_connections PRIMARY KEY (id),
    CONSTRAINT fk_camera_connections_binding
        FOREIGN KEY (binding_id) REFERENCES monitoring_media_bindings(id) ON DELETE CASCADE,
    CONSTRAINT chk_camera_connections_period CHECK (
        disconnected_at IS NULL OR disconnected_at >= connected_at
    )
);

-- One OPEN (unterminated) camera connection per binding — the
-- idempotency anchor for duplicate/replayed track_published webhooks.
CREATE UNIQUE INDEX uk_camera_connections_open
    ON monitoring_camera_connections (binding_id) WHERE disconnected_at IS NULL;
CREATE INDEX ix_camera_connections_binding_time
    ON monitoring_camera_connections (binding_id, connected_at);

COMMENT ON TABLE monitoring_camera_connections IS
    'P4-B4 (#87): server-confirmed camera presence periods per binding; gaps = missing open row between periods; webhook idempotency via partial unique open-row index.';

-- ---------------------------------------------------------------------
-- 7. Webhook dedupe/replay ledger (bounded by housekeeping sweep).
-- ---------------------------------------------------------------------
CREATE TABLE monitoring_webhook_events (
    dedupe_key CHAR(64)    NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_monitoring_webhook_events PRIMARY KEY (dedupe_key)
);

CREATE INDEX ix_webhook_events_received ON monitoring_webhook_events (received_at);

COMMENT ON TABLE monitoring_webhook_events IS
    'P4-B4 (#87): sha256 ledger of processed LiveKit webhook events; duplicates/replays are skipped; rows expire (housekeeping sweep).';

-- ---------------------------------------------------------------------
-- Verification — camera types live in the CHECK; no backfill rows.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF (SELECT COUNT(*) FROM monitoring_preflights) <> 0 THEN
        RAISE EXCEPTION 'V37 verification failed: preflight rows must not be backfilled';
    END IF;
    IF (SELECT COUNT(*) FROM monitoring_media_bindings) <> 0 THEN
        RAISE EXCEPTION 'V37 verification failed: binding rows must not be backfilled';
    END IF;
    IF (SELECT COUNT(*) FROM monitoring_media_rooms) <> 0 THEN
        RAISE EXCEPTION 'V37 verification failed: room rows must not be backfilled';
    END IF;
    IF EXISTS (SELECT 1 FROM permissions
               WHERE code = 'MONITORING_PREFLIGHT'
                 AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                                 JOIN roles r ON r.id = rp.role_id
                                 WHERE rp.permission_id = permissions.id
                                   AND r.code IN ('STUDENT','TEACHER'))) THEN
        RAISE EXCEPTION 'V37 verification failed: MONITORING_PREFLIGHT must be granted to STUDENT and TEACHER';
    END IF;
END $$;
