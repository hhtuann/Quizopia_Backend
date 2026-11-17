-- =====================================================================
-- P4-E (issues #95/#96, ADR-010): teacher alert review ledger + unified
-- dashboard/report read surface.
--
--  1. Append-only review history (monitoring_alert_reviews) + denormalized
--     current review state on monitoring_ai_alerts. The detector's own
--     fields are NEVER rewritten — provenance cannot be forged.
--  2. New server-confirmed activity event ALERT_REVIEW_RECORDED so reviews
--     ride the existing unified per-attempt timeline + STOMP ping.
--  3. Teacher-only MONITORING_ALERT_REVIEW permission.
--
-- No grade/attempt/session status transitions from a review. No verdicts.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Activity event type (server-confirmed only; client ingest allowlist
--    in ActivityEventType.CLIENT_TYPES keeps rejecting it).
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
    'EVIDENCE_CLIP_REQUESTED', 'EVIDENCE_CLIP_READY', 'EVIDENCE_CLIP_FAILED',
    -- server-confirmed teacher review lifecycle (P4-E)
    'ALERT_REVIEW_RECORDED'
));

-- ---------------------------------------------------------------------
-- 2. Permission (V36/V40 pattern): teacher-only review writes. Reading
--    reviews reuses AI_ALERTS_READ.
-- ---------------------------------------------------------------------
INSERT INTO permissions (code, name, description) VALUES
    ('MONITORING_ALERT_REVIEW',
     'Review Monitoring Alerts',
     'Teacher-only append-only alert review actions (viewed / false positive / notes) for OWNED sessions (P4-E, issues #95/#96). Never a verdict, never score input.')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, NULL
FROM roles r
JOIN permissions p ON p.code = 'MONITORING_ALERT_REVIEW'
WHERE r.code = 'TEACHER'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- 3. Append-only review ledger.
-- ---------------------------------------------------------------------
CREATE TABLE monitoring_alert_reviews (
    id                       BIGSERIAL       PRIMARY KEY,
    alert_id                 BIGINT          NOT NULL,
    reviewer_user_id         BIGINT          NOT NULL,
    action                   VARCHAR(24)     NOT NULL,
    note                     VARCHAR(1000),
    review_version           INT             NOT NULL,
    previous_action          VARCHAR(24),
    previous_review_version  INT,
    idempotency_key          UUID            NOT NULL,
    created_at               TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_alert_reviews_alert
        FOREIGN KEY (alert_id) REFERENCES monitoring_ai_alerts(id) ON DELETE CASCADE,
    CONSTRAINT fk_alert_reviews_reviewer
        FOREIGN KEY (reviewer_user_id) REFERENCES users(id),
    CONSTRAINT uk_alert_reviews_idem UNIQUE (idempotency_key),
    CONSTRAINT uk_alert_reviews_version UNIQUE (alert_id, review_version),
    CONSTRAINT chk_alert_reviews_action CHECK (action IN
        ('MARK_VIEWED', 'FALSE_POSITIVE', 'NEEDS_FOLLOW_UP', 'NOTED', 'REVIEWED_NO_CONCLUSION')),
    CONSTRAINT chk_alert_reviews_note CHECK (
        note IS NULL OR char_length(note) <= 1000
    ),
    CONSTRAINT chk_alert_reviews_version CHECK (review_version >= 1)
);

CREATE INDEX ix_alert_reviews_alert
    ON monitoring_alert_reviews (alert_id, id);

COMMENT ON TABLE monitoring_alert_reviews IS
    'P4-E (#96): append-only teacher review ledger. One row per action; review_version 1-based per alert (unique). The AI alert row keeps detector fields untouched; only the denormalized current review state moves. Never a verdict, never score input.';

-- ---------------------------------------------------------------------
-- 4. Denormalized current review state on the alert (updated in the SAME
--    transaction as the ledger append; optimistic concurrency via
--    review_version).
-- ---------------------------------------------------------------------
ALTER TABLE monitoring_ai_alerts
    ADD COLUMN review_status VARCHAR(24) NOT NULL DEFAULT 'UNREVIEWED',
    ADD COLUMN review_version INT NOT NULL DEFAULT 0,
    ADD COLUMN reviewed_by BIGINT,
    ADD COLUMN reviewed_at TIMESTAMPTZ,
    ADD CONSTRAINT fk_ai_alerts_reviewer
        FOREIGN KEY (reviewed_by) REFERENCES users(id),
    ADD CONSTRAINT chk_ai_alerts_review_status CHECK (review_status IN
        ('UNREVIEWED', 'VIEWED', 'FALSE_POSITIVE', 'NEEDS_FOLLOW_UP', 'NOTED',
         'REVIEWED_NO_CONCLUSION'));

-- ---------------------------------------------------------------------
-- 5. Dashboard/report read indexes.
-- ---------------------------------------------------------------------
CREATE INDEX ix_attempts_session
    ON attempts (exam_session_id, id DESC);
CREATE INDEX ix_ai_alerts_attempt
    ON monitoring_ai_alerts (attempt_id, id);
CREATE INDEX ix_ai_detections_attempt
    ON monitoring_ai_detections (attempt_id, quarantined);

-- ---------------------------------------------------------------------
-- 6. Verification block.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    -- Review status check admits exactly the six states.
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_ai_alerts_review_status'
          AND conrelid = 'monitoring_ai_alerts'::regclass
    ) THEN
        RAISE EXCEPTION 'V42 verification: review status CHECK missing';
    END IF;
    -- Teacher role holds the review permission.
    IF NOT EXISTS (
        SELECT 1 FROM role_permissions rp
        JOIN roles r ON r.id = rp.role_id
        JOIN permissions p ON p.id = rp.permission_id
        WHERE r.code = 'TEACHER' AND p.code = 'MONITORING_ALERT_REVIEW'
    ) THEN
        RAISE EXCEPTION 'V42 verification: TEACHER missing MONITORING_ALERT_REVIEW';
    END IF;
    -- No review linkage into grading (observations-never-verdicts invariant).
    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name LIKE '%grades%'
          AND table_name = 'monitoring_alert_reviews'
    ) THEN
        RAISE EXCEPTION 'V42 verification: reviews must not FK into grades';
    END IF;
END $$;
