-- =====================================================================
-- V38 (P4-C AI proctoring, issues #88/#90, ADR-008 amendment):
--
--   1. AI_ALERT_RAISED / AI_ALERT_UPDATED activity types (SERVER source
--      only; the client allowlist in ActivityEventType.parse() keeps
--      rejecting them).
--   2. AI_DETECTION_INGEST + AI_ALERTS_READ permissions (V36/V37 dual
--      pattern): ingest is learner-owned (TEACHER granted too so a
--      teacher can run the monitored flows on their own sessions in
--      demos); alerts read is TEACHER-ONLY.
--   3. monitoring_ai_detections — the durable client-model/worker
--      detection ledger (CONTRACT §3/§4). event_id is the idempotency
--      identity (ON CONFLICT DO NOTHING); quarantined rows are stored
--      with a reason but NEVER aggregate into alerts.
--   4. monitoring_ai_alerts — server-side temporal aggregation windows
--      (issue #90). One OPEN alert per (attempt_id, alert_type) via a
--      partial unique index; a gap > cooldown closes the old window
--      (status CLOSED) and starts a NEW one, so every incident window
--      keeps its own first/last/count evidence.
--
-- Invariants (ADR-008 D8 + P4-C CONTRACT §8):
--   * Detections are OBSERVATIONS, never verdicts: no FK, no trigger and
--     no write path from these tables to grades/grade_items (verified
--     below); no score impact, no auto cheating conclusion.
--   * provenance distinguishes CLIENT_MODEL (browser ORT-Web) from
--     WORKER_VERIFIED (internal worker path) — a client can never store
--     the worker value (ingest pins provenance server-side).
--   * payload is the raw detections ARRAY (bbox-normalised, no pixels,
--     no frames — evidence media is P4-D).
--
-- Forward-only: new tables + constraint swap + idempotent inserts.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Activity event types: server-confirmed AI alert lifecycle.
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
    'MONITORING_GAP',
    -- server-confirmed AI alert lifecycle (P4-C, issue #90); written by
    -- AiAlertAggregationService only — never client-ingestable.
    'AI_ALERT_RAISED', 'AI_ALERT_UPDATED'
));

-- ---------------------------------------------------------------------
-- 2. Permissions (V36/V37 pattern): AI_DETECTION_INGEST is learner-owned
--    (TEACHER granted too so a teacher can run the monitored flows on
--    their own sessions in demos); AI_ALERTS_READ is TEACHER-ONLY —
--    students never read alerts (CONTRACT §5).
-- ---------------------------------------------------------------------
INSERT INTO permissions (code, name, description) VALUES
    ('AI_DETECTION_INGEST',
     'Ingest AI Detections',
     'Post browser-model AI detection batches for one''s own monitored attempts (P4-C, issues #88/#90).'),
    ('AI_ALERTS_READ',
     'Read AI Alerts',
     'Teacher-only AI alert feed for OWNED sessions — aggregated observations, never verdicts (P4-C, issue #90).')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, NULL
FROM roles r
JOIN permissions p ON p.code = 'AI_DETECTION_INGEST'
WHERE r.code IN ('STUDENT', 'TEACHER')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, NULL
FROM roles r
JOIN permissions p ON p.code = 'AI_ALERTS_READ'
WHERE r.code = 'TEACHER'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- 3. Detection ledger (both ingest paths, CONTRACT §3).
-- ---------------------------------------------------------------------
CREATE TABLE monitoring_ai_detections (
    id                 BIGSERIAL     PRIMARY KEY,
    attempt_id         BIGINT        NOT NULL,
    exam_session_id    BIGINT        NOT NULL,
    user_id            BIGINT        NOT NULL,
    media_binding_id   UUID,
    event_id           UUID          NOT NULL,
    provenance         VARCHAR(20)   NOT NULL,
    event_type         VARCHAR(40)   NOT NULL,
    model_id           VARCHAR(40)   NOT NULL,
    model_version      VARCHAR(40)   NOT NULL,
    runtime            VARCHAR(20)   NOT NULL,
    observed_at        TIMESTAMPTZ   NOT NULL,
    server_received_at TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    quarantined        BOOLEAN       NOT NULL DEFAULT FALSE,
    quarantine_reason  VARCHAR(40),
    frame_width        INT           NOT NULL,
    frame_height       INT           NOT NULL,
    processing_ms      INT           NOT NULL,
    payload            JSONB         NOT NULL,
    late_ms            BIGINT,
    CONSTRAINT fk_ai_detections_attempt
        FOREIGN KEY (attempt_id) REFERENCES attempts(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_detections_session
        FOREIGN KEY (exam_session_id) REFERENCES exam_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_detections_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_detections_media_binding
        FOREIGN KEY (media_binding_id) REFERENCES monitoring_media_bindings(id) ON DELETE SET NULL,
    CONSTRAINT uk_ai_detections_event_id UNIQUE (event_id),
    CONSTRAINT chk_ai_detections_provenance CHECK (provenance IN
        ('CLIENT_MODEL', 'WORKER_VERIFIED')),
    CONSTRAINT chk_ai_detections_event_type CHECK (event_type IN
        ('PHONE_OBSERVED', 'SCREEN_OBSERVED', 'MULTIPLE_PERSONS_OBSERVED', 'PERSON_NOT_OBSERVED')),
    CONSTRAINT chk_ai_detections_runtime CHECK (runtime IN
        ('ORT_WEB_WASM', 'ORT_WEB_WEBGPU', 'WORKER_ORT')),
    CONSTRAINT chk_ai_detections_quarantine_pair CHECK (
        (quarantined AND quarantine_reason IS NOT NULL)
        OR (NOT quarantined AND quarantine_reason IS NULL)
    ),
    CONSTRAINT chk_ai_detections_frame CHECK (frame_width >= 1 AND frame_height >= 1),
    CONSTRAINT chk_ai_detections_processing CHECK (processing_ms >= 0),
    CONSTRAINT chk_ai_detections_payload CHECK (jsonb_typeof(payload) = 'array')
);

CREATE INDEX ix_ai_detections_attempt_observed
    ON monitoring_ai_detections (attempt_id, observed_at);
CREATE INDEX ix_ai_detections_session_received
    ON monitoring_ai_detections (exam_session_id, server_received_at);
CREATE INDEX ix_ai_detections_attempt_quarantined
    ON monitoring_ai_detections (attempt_id) WHERE quarantined;

COMMENT ON TABLE monitoring_ai_detections IS
    'P4-C (#88/#90): durable AI detection ledger (client model + worker paths); event_id is the idempotency identity; quarantined rows are stored but never aggregate; observations only — no verdict semantics, no grade linkage.';

-- ---------------------------------------------------------------------
-- 4. Aggregated alert windows (issue #90, CONTRACT §5).
--
--    status: only OPEN windows are live (partial unique index); a
--    detection gap > cooldown CLOSES the window and opens a new one —
--    closed windows keep their own incident evidence (first/last/count).
-- ---------------------------------------------------------------------
CREATE TABLE monitoring_ai_alerts (
    id                BIGSERIAL        PRIMARY KEY,
    attempt_id        BIGINT           NOT NULL,
    exam_session_id   BIGINT           NOT NULL,
    user_id           BIGINT           NOT NULL,
    alert_type        VARCHAR(40)      NOT NULL,
    status            VARCHAR(12)      NOT NULL DEFAULT 'OPEN',
    first_detected_at TIMESTAMPTZ      NOT NULL,
    last_detected_at  TIMESTAMPTZ      NOT NULL,
    detection_count   INT              NOT NULL DEFAULT 1,
    max_confidence    DOUBLE PRECISION,
    latest_payload    JSONB,
    model_id          VARCHAR(40)      NOT NULL,
    model_version     VARCHAR(40)      NOT NULL,
    provenance        VARCHAR(20)      NOT NULL,
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_alerts_attempt
        FOREIGN KEY (attempt_id) REFERENCES attempts(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_alerts_session
        FOREIGN KEY (exam_session_id) REFERENCES exam_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_alerts_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_ai_alerts_type CHECK (alert_type IN
        ('PHONE_DETECTED', 'SCREEN_DETECTED', 'MULTIPLE_PERSONS_DETECTED', 'PERSON_NOT_VISIBLE')),
    CONSTRAINT chk_ai_alerts_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT chk_ai_alerts_count CHECK (detection_count >= 1),
    CONSTRAINT chk_ai_alerts_period CHECK (last_detected_at >= first_detected_at),
    CONSTRAINT chk_ai_alerts_provenance CHECK (provenance IN
        ('CLIENT_MODEL', 'WORKER_VERIFIED')),
    CONSTRAINT chk_ai_alerts_payload CHECK (
        latest_payload IS NULL OR jsonb_typeof(latest_payload) = 'array'
    )
);

-- One OPEN window per (attempt_id, alert_type) — the aggregation anchor.
CREATE UNIQUE INDEX uk_ai_alerts_open
    ON monitoring_ai_alerts (attempt_id, alert_type) WHERE status = 'OPEN';
CREATE INDEX ix_ai_alerts_session_id
    ON monitoring_ai_alerts (exam_session_id, id DESC);

COMMENT ON TABLE monitoring_ai_alerts IS
    'P4-C (#90): server-side temporal aggregation windows (one OPEN per attempt+type); cooldown gaps close a window and open a new one; observations for the teacher feed — never verdicts, never score input.';

-- ---------------------------------------------------------------------
-- Verification — grants exact, alert-type activity types admitted, and
-- NO FK from the P4-C tables into the grading domain (grades,
-- grade_items): AI observations can never touch scores by design.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM permissions
               WHERE code = 'AI_DETECTION_INGEST'
                 AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                                 JOIN roles r ON r.id = rp.role_id
                                 WHERE rp.permission_id = permissions.id
                                   AND r.code IN ('STUDENT','TEACHER'))) THEN
        RAISE EXCEPTION 'V38 verification failed: AI_DETECTION_INGEST must be granted to STUDENT and TEACHER';
    END IF;
    IF EXISTS (SELECT 1 FROM role_permissions rp
               JOIN roles r ON r.id = rp.role_id
               JOIN permissions p ON p.id = rp.permission_id
               WHERE p.code = 'AI_DETECTION_INGEST'
                 AND r.code NOT IN ('STUDENT','TEACHER')) THEN
        RAISE EXCEPTION 'V38 verification failed: AI_DETECTION_INGEST must be granted to STUDENT and TEACHER only';
    END IF;
    IF EXISTS (SELECT 1 FROM permissions
               WHERE code = 'AI_ALERTS_READ'
                 AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                                 JOIN roles r ON r.id = rp.role_id
                                 WHERE rp.permission_id = permissions.id
                                   AND r.code = 'TEACHER')) THEN
        RAISE EXCEPTION 'V38 verification failed: AI_ALERTS_READ must be granted to TEACHER';
    END IF;
    IF EXISTS (SELECT 1 FROM role_permissions rp
               JOIN roles r ON r.id = rp.role_id
               JOIN permissions p ON p.id = rp.permission_id
               WHERE p.code = 'AI_ALERTS_READ'
                 AND r.code <> 'TEACHER') THEN
        RAISE EXCEPTION 'V38 verification failed: AI_ALERTS_READ must be granted to TEACHER only';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint c
               JOIN pg_class t ON t.oid = c.conrelid
               WHERE t.relname IN ('monitoring_ai_detections', 'monitoring_ai_alerts')
                 AND c.contype = 'f'
                 AND c.confrelid IN ('grades'::regclass, 'grade_items'::regclass)) THEN
        RAISE EXCEPTION 'V38 verification failed: P4-C tables must have no FK into grades/grade_items';
    END IF;
END $$;
