-- =====================================================================
-- V43 (gaze proctoring): admit the gaze observation / alert vocabulary.
--
--   * monitoring_ai_detections.event_type  += LOOKING_AWAY_OBSERVED
--   * monitoring_ai_alerts.alert_type      += LOOKING_AWAY
--
-- The browser now runs a face detector (YOLO11n-face) + a gaze model
-- (MobileGaze / MobileOne-S0) next to yolo26s. A sustained or repeated
-- deviation of the estimated gaze from the test taker's own baseline is
-- ingested through the SAME contract as the other four observations
-- (same tables, same quarantine / idempotency / aggregation rules).
--
-- Invariants unchanged (ADR-008 D8, CONTRACT §8): a gaze estimate is an
-- OBSERVATION for teacher review — inconclusive context, never a verdict,
-- no score impact; there is still no FK or write path from these tables
-- into grades/grade_items. The payload stays the normalized detections
-- array (face box + label) — no pixels, no frames.
--
-- Forward-only: two CHECK constraint swaps (supersets of the V38 lists,
-- so every existing row keeps validating).
-- =====================================================================

ALTER TABLE monitoring_ai_detections DROP CONSTRAINT chk_ai_detections_event_type;
ALTER TABLE monitoring_ai_detections ADD CONSTRAINT chk_ai_detections_event_type CHECK (event_type IN
    ('PHONE_OBSERVED', 'SCREEN_OBSERVED', 'MULTIPLE_PERSONS_OBSERVED', 'PERSON_NOT_OBSERVED',
     'LOOKING_AWAY_OBSERVED'));

ALTER TABLE monitoring_ai_alerts DROP CONSTRAINT chk_ai_alerts_type;
ALTER TABLE monitoring_ai_alerts ADD CONSTRAINT chk_ai_alerts_type CHECK (alert_type IN
    ('PHONE_DETECTED', 'SCREEN_DETECTED', 'MULTIPLE_PERSONS_DETECTED', 'PERSON_NOT_VISIBLE',
     'LOOKING_AWAY'));

-- Verification — the grading-domain isolation of V38 still holds.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint c
               JOIN pg_class t ON t.oid = c.conrelid
               WHERE t.relname IN ('monitoring_ai_detections', 'monitoring_ai_alerts')
                 AND c.contype = 'f'
                 AND c.confrelid IN ('grades'::regclass, 'grade_items'::regclass)) THEN
        RAISE EXCEPTION 'V43 verification failed: AI tables must have no FK into grades/grade_items';
    END IF;
END $$;
