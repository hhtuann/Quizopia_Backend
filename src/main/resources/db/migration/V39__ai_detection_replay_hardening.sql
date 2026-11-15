-- P4-C pre-merge security hardening (worker-contract review, PR #26).
--
-- 1. Persist the client/worker's monotonic per-attempt sequence counter
--    (CONTRACT §3.1) — previously validated then discarded.
ALTER TABLE monitoring_ai_detections
    ADD COLUMN client_sequence BIGINT;

-- 2. Replay/aggregation-poisoning guard: a partial UNIQUE on
--    (attempt_id, provenance, client_sequence) for non-quarantined rows.
--    Replaying one physical detection with FRESH eventIds but a repeated
--    sequence now conflicts — the item reports DUPLICATE instead of
--    inflating alert counts/confidence. Legacy rows (NULL sequence) are
--    unconstrained; quarantined rows never block legitimate re-sends.
CREATE UNIQUE INDEX ux_ai_detections_attempt_prov_seq
    ON monitoring_ai_detections (attempt_id, provenance, client_sequence)
    WHERE NOT quarantined AND client_sequence IS NOT NULL;
