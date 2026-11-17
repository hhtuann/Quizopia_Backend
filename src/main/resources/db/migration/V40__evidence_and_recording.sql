-- =====================================================================
-- V40 (P4-D Evidence & Recording, issues #91-#94, ADR-009):
--
--   1. Server-source activity types for recording + evidence lifecycle.
--   2. MONITORING_EVIDENCE_READ permission (TEACHER only).
--   3. monitoring_recording_jobs — one row per egress run
--      (FULL continuous segment or ROLLING pulse).
--   4. monitoring_evidence — snapshots + clips with provenance,
--      honest lifecycle and per-session retention expiry.
--   5. monitoring_evidence_access — playback/URL audit ledger.
--
-- Invariants (ADR-009):
--   * Provenance is fixed at insert (CLIENT_CAPTURE / FULL_RECORDING /
--     ROLLING_BUFFER) and never rewritten.
--   * AVAILABLE/PARTIAL requires an object; EXPIRED rows keep metadata
--     only (object_deleted semantics for pulse rows live in
--     monitoring_recording_jobs).
--   * Retention: evidence expires per session media_retention_days
--     (default 30, set by the service from the policy; DB default is the
--     safety net); access audit is activity metadata (90 d, job-swept).
--   * No FK from evidence to grades/attempts answers — evidence never
--     participates in grading.
--
-- Forward-only: new tables/types/permission only.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Activity event types (server-confirmed only; client allowlist in
--    ActivityEventType.parse() keeps rejecting them).
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
    -- server-confirmed camera lifecycle (P4-B)
    'CAMERA_CONNECTED', 'CAMERA_DISCONNECTED', 'CAMERA_RECONNECTED', 'CAMERA_REVOKED',
    'MONITORING_GAP',
    -- server-confirmed AI alert lifecycle (P4-C)
    'AI_ALERT_RAISED', 'AI_ALERT_UPDATED',
    -- server-confirmed recording + evidence lifecycle (P4-D)
    'RECORDING_STARTED', 'RECORDING_STOPPED', 'RECORDING_FAILED',
    'EVIDENCE_SNAPSHOT_UPLOADED', 'EVIDENCE_SNAPSHOT_REJECTED',
    'EVIDENCE_CLIP_REQUESTED', 'EVIDENCE_CLIP_READY', 'EVIDENCE_CLIP_FAILED'
));

-- ---------------------------------------------------------------------
-- 2. Permission (V36/V37 pattern): teacher-only evidence read. The
--    student snapshot upload path authorizes by attempt ownership + the
--    preflight-style learner grant, never by this permission.
-- ---------------------------------------------------------------------
INSERT INTO permissions (code, name, description) VALUES
    ('MONITORING_EVIDENCE_READ',
     'Read Monitoring Evidence',
     'Teacher-only snapshot/clip/recording viewing, signed playback URLs and clip requests for OWNED sessions (P4-D, issues #91-#94).')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, NULL
FROM roles r
JOIN permissions p ON p.code = 'MONITORING_EVIDENCE_READ'
WHERE r.code = 'TEACHER'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- 3. Egress run ledger.
-- ---------------------------------------------------------------------
CREATE TABLE monitoring_recording_jobs (
    id              UUID        NOT NULL,
    exam_session_id BIGINT      NOT NULL,
    attempt_id      BIGINT      NOT NULL,
    binding_id      UUID        NOT NULL,
    kind            VARCHAR(8)  NOT NULL,
    egress_id       VARCHAR(64) NOT NULL,
    room_name       VARCHAR(64) NOT NULL,
    track_sid       VARCHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    pulse_index     INTEGER     NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    object_key      VARCHAR(256),
    bytes           BIGINT,
    duration_ms     BIGINT,
    error           TEXT,
    object_deleted  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_monitoring_recording_jobs PRIMARY KEY (id),
    CONSTRAINT fk_recording_jobs_session
        FOREIGN KEY (exam_session_id) REFERENCES exam_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_recording_jobs_attempt
        FOREIGN KEY (attempt_id) REFERENCES attempts(id) ON DELETE CASCADE,
    CONSTRAINT fk_recording_jobs_binding
        FOREIGN KEY (binding_id) REFERENCES monitoring_media_bindings(id) ON DELETE CASCADE,
    CONSTRAINT uk_recording_jobs_egress UNIQUE (egress_id),
    CONSTRAINT chk_recording_jobs_kind CHECK (kind IN ('FULL', 'ROLLING')),
    CONSTRAINT chk_recording_jobs_status CHECK (status IN
        ('STARTING', 'ACTIVE', 'ENDING', 'COMPLETE', 'FAILED', 'ABORTED')),
    CONSTRAINT chk_recording_jobs_pulse CHECK (pulse_index >= 0)
);

CREATE INDEX ix_recording_jobs_attempt_kind_time
    ON monitoring_recording_jobs (attempt_id, kind, started_at);
CREATE INDEX ix_recording_jobs_session ON monitoring_recording_jobs (exam_session_id);

COMMENT ON TABLE monitoring_recording_jobs IS
    'P4-D (#92/#93): one row per LiveKit egress run — FULL continuous segments or ROLLING pulses; authoritative file metadata from EgressInfo; coverage/gap source for clip extraction.';

-- ---------------------------------------------------------------------
-- 4. Evidence (snapshots + clips).
-- ---------------------------------------------------------------------
CREATE TABLE monitoring_evidence (
    id              UUID        NOT NULL,
    exam_session_id BIGINT      NOT NULL,
    attempt_id      BIGINT      NOT NULL,
    alert_id        BIGINT,
    binding_id      UUID,
    kind            VARCHAR(8)  NOT NULL,
    status          VARCHAR(12) NOT NULL,
    provenance      VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(128),
    object_key      VARCHAR(256),
    content_type    VARCHAR(64),
    sha256          CHAR(64),
    bytes           BIGINT,
    width           INTEGER,
    height          INTEGER,
    captured_at     TIMESTAMPTZ,
    window_start    TIMESTAMPTZ,
    window_end      TIMESTAMPTZ,
    coverage_json   JSONB,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP + INTERVAL '30 days',
    CONSTRAINT pk_monitoring_evidence PRIMARY KEY (id),
    CONSTRAINT fk_evidence_session
        FOREIGN KEY (exam_session_id) REFERENCES exam_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_evidence_attempt
        FOREIGN KEY (attempt_id) REFERENCES attempts(id) ON DELETE CASCADE,
    CONSTRAINT fk_evidence_alert
        FOREIGN KEY (alert_id) REFERENCES monitoring_ai_alerts(id) ON DELETE SET NULL,
    CONSTRAINT fk_evidence_binding
        FOREIGN KEY (binding_id) REFERENCES monitoring_media_bindings(id) ON DELETE SET NULL,
    CONSTRAINT uk_evidence_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_evidence_kind CHECK (kind IN ('SNAPSHOT', 'CLIP')),
    CONSTRAINT chk_evidence_status CHECK (status IN
        ('PENDING', 'AVAILABLE', 'PARTIAL', 'FAILED', 'EXPIRED')),
    CONSTRAINT chk_evidence_provenance CHECK (provenance IN
        ('CLIENT_CAPTURE', 'FULL_RECORDING', 'ROLLING_BUFFER')),
    CONSTRAINT chk_evidence_clip_window CHECK (
        kind <> 'CLIP' OR (window_start IS NOT NULL AND window_end IS NOT NULL
                           AND window_end > window_start)
    ),
    CONSTRAINT chk_evidence_snapshot_capture CHECK (
        kind <> 'SNAPSHOT' OR captured_at IS NOT NULL
    )
);

CREATE INDEX ix_evidence_session_kind ON monitoring_evidence (exam_session_id, kind, created_at);
CREATE INDEX ix_evidence_attempt ON monitoring_evidence (attempt_id);
CREATE INDEX ix_evidence_alert ON monitoring_evidence (alert_id) WHERE alert_id IS NOT NULL;
CREATE INDEX ix_evidence_expiry ON monitoring_evidence (expires_at) WHERE status <> 'EXPIRED';

COMMENT ON TABLE monitoring_evidence IS
    'P4-D (#91/#93): evidence objects with fixed provenance; AVAILABLE/PARTIAL require a real stored object; EXPIRED keeps metadata only.';

-- ---------------------------------------------------------------------
-- 5. Access audit (URL mint + playback authorization outcomes).
-- ---------------------------------------------------------------------
CREATE TABLE monitoring_evidence_access (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    evidence_id UUID        NOT NULL,
    user_id     BIGINT      NOT NULL,
    action      VARCHAR(16) NOT NULL,
    accessed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evidence_access_evidence
        FOREIGN KEY (evidence_id) REFERENCES monitoring_evidence(id) ON DELETE CASCADE,
    CONSTRAINT fk_evidence_access_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_evidence_access_action CHECK (action IN ('URL_MINTED', 'PLAYBACK'))
);

CREATE INDEX ix_evidence_access_time ON monitoring_evidence_access (accessed_at);

COMMENT ON TABLE monitoring_evidence_access IS
    'P4-D (#94): audit of every evidence URL mint / playback authorization; activity-metadata retention class (90 d).';

-- ---------------------------------------------------------------------
-- Verification — permission granted exactly to TEACHER; no backfill.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM role_permissions rp
               JOIN roles r ON r.id = rp.role_id
               JOIN permissions p ON p.id = rp.permission_id
               WHERE p.code = 'MONITORING_EVIDENCE_READ'
                 AND r.code <> 'TEACHER') THEN
        RAISE EXCEPTION 'V40 verification failed: MONITORING_EVIDENCE_READ must be TEACHER only';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM role_permissions rp
                   JOIN roles r ON r.id = rp.role_id
                   JOIN permissions p ON p.id = rp.permission_id
                   WHERE p.code = 'MONITORING_EVIDENCE_READ'
                     AND r.code = 'TEACHER') THEN
        RAISE EXCEPTION 'V40 verification failed: MONITORING_EVIDENCE_READ must be granted to TEACHER';
    END IF;
    IF (SELECT COUNT(*) FROM monitoring_evidence) <> 0 THEN
        RAISE EXCEPTION 'V40 verification failed: evidence rows must not be backfilled';
    END IF;
    IF (SELECT COUNT(*) FROM monitoring_recording_jobs) <> 0 THEN
        RAISE EXCEPTION 'V40 verification failed: recording job rows must not be backfilled';
    END IF;
END $$;
