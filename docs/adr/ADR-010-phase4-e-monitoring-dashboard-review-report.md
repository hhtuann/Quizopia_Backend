# ADR-010 — Phase 4-E: Unified Monitoring Dashboard, Alert Review & Attempt Reports

Status: ACCEPTED (engineering) — 2026-11-17. Product Owner merge approval still required per Phase-4 gate policy.

## 1. Context

P4-A..D persisted every monitoring signal as durable rows: browser activity + server-accepted
answer history (`attempt_activity_events`, `attempt_answer_history`), camera presence
(`monitoring_camera_connections` + CAMERA_* events), AI detections/alerts
(`monitoring_ai_detections` / `monitoring_ai_alerts`), evidence + recordings
(`monitoring_evidence` / `monitoring_recording_jobs`) with audited short-lived playback.
Teachers see these as independent cards; there is no unified roster view, no review
workflow (alerts are display-only OPEN/CLOSED windows) and no post-exam report.

Issues #95 (P4-E1) and #96 (P4-E2) ask for: a unified teacher dashboard with signal
separation, an append-only human review layer (viewed / false positive / notes), and a
per-attempt monitoring report usable after session close. Explicitly out of scope:
automated cheating verdicts, score impact, rebuilding any P4-A..D pipeline.

## 2. Decisions

### 2.1 The unified timeline already exists — P4-E joins it, never forks it

Every P4-A..D subsystem already writes server-confirmed lifecycle rows into
`attempt_activity_events` via `ActivityEventRecorder` (mandatory-tx). P4-E adds exactly one
new event type, `ALERT_REVIEW_RECORDED` (V42), so teacher reviews appear in the same
per-attempt stream with the same keyset cursor `(server_received_at, id)`, the same
`event_id` idempotency and the same STOMP `ATTEMPT_ACTIVITY` invalidation ping. No second
timeline table, no session-wide event merge at write time; cross-signal views are read-time
aggregations.

### 2.2 Review is append-only ledger + denormalized current state (V42)

`monitoring_alert_reviews` is the sole write target: one row per review action with
`reviewer_user_id`, `action ∈ {MARK_VIEWED, FALSE_POSITIVE, NEEDS_FOLLOW_UP, NOTED,
REVIEWED_NO_CONCLUSION}`, bounded note (≤1000 chars), 1-based `review_version`,
`previous_action`/`previous_review_version`, unique `idempotency_key` and unique
`(alert_id, review_version)`. The AI alert row keeps its detector fields untouched and gains
only denormalized `review_status`/`review_version`/`reviewed_by`/`reviewed_at`, updated in
the same transaction as the append (optimistic concurrency: caller sends
`expectedVersion`; mismatch ⇒ 409, no partial state). The original detection/alert is never
rewritten — provenance cannot be forged into "detector verified".

No CHEATED action exists; no grade, attempt or session status transitions from a review.

### 2.3 Dashboard = three read-only projections behind the existing teacher gate

- `GET /api/exam-sessions/{id}/monitoring/overview` — KPI counters (attempts by status,
  camera states, alert counts incl. unreviewed, recording/evidence health), each tied to the
  signal that produced it, plus `asOf`. No per-student inference.
- `GET /api/exam-sessions/{id}/monitoring/participants` — one row per attempt with the
  signals kept SEPARATE (`attemptStatus`, `camera.state`, `ai.*`, `recording.*`,
  `evidence.*`, raw `lastActivityAt`) precisely so the UI never derives misconduct from a
  missing signal (issue #95 guardrail). Cursor = attemptId DESC (numeric precedent).
- Per-attempt timeline — existing endpoint, unchanged contract + review events.

Authorization reuses the deny-by-default service-layer pattern: effective permission
`AI_ALERTS_READ` (read surfaces) / new `MONITORING_ALERT_REVIEW` (review writes) +
`exam_sessions.created_by == userId`; students and foreign teachers get the same generic
`MONITORING_ACCESS_DENIED`. Teacher-subscribed / publishing camera truth stays
media-side — the teacher's own browser knows its LiveKit subscription state; the backend
never claims who watched.

### 2.4 Report is a read-time aggregation with honest holes

`GET /api/attempts/{id}/monitoring/report` aggregates persisted rows only: monitoring
window, activity counts by group, camera connected/disconnect periods from CAMERA_* events,
recording segments + derived gaps (>2s holes between COMPLETE non-deleted segments, same
rule as the P4-D UI), and counts that keep the three layers distinct — raw detections
(non-quarantined), grouped alerts, reviewed alerts (distinct alerts with ≥1 review) — so
nothing double-counts. Evidence rows list state honestly (PENDING/AVAILABLE/PARTIAL/
FAILED/EXPIRED with `expiresAt`); playable items carry the P4-D mint endpoint reference,
NOT a presigned URL — every mint stays individually audited via
`monitoring_evidence_access`. The report joins nothing from `grades`/`grade_items`; a fixed
disclaimer states observations ≠ verdict. Works for any attempt status after session close.

### 2.5 STOMP stays an invalidation signal

No new topic. Review/recording/evidence activity already pings
`/topic/exam-sessions/{sessionId}` via `ATTEMPT_ACTIVITY` envelopes; the dashboard adds its
query keys to the client-side invalidation map. REST remains the durable truth — reload and
reconnect backfill by refetch.

## 3. Consequences

- One migration (V42), one new event type, three read services + two controllers; no change
  to P4-A..D write paths or the frozen legacy envelope.
- Alert feed DTO gains `reviewStatus`/`reviewVersion`/`reviewedAt` + `review` filter —
  additive, existing consumers unaffected.
- Retention unchanged: media 30d / activity+review metadata 90d per session policy; reports
  show EXPIRED metadata truthfully and never mint dead URLs.
- Frontend composes a `MonitoringDashboard` section (overview KPIs, participants table,
  per-attempt drilldown feeding the existing timeline/evidence components), review controls
  inside the existing alert card, and a printable report route.

## 4. Alternatives rejected

- **Session-wide event stream table** — duplicates §2.1's stream, doubles write amplification,
  breaks the existing cursor contract.
- **Review state as alert status extension (ACKNOWLEDGED on `monitoring_ai_alerts.status`)** —
  conflates detector lifecycle (OPEN/CLOSED by cooldown) with human judgement; loses history.
- **Presigned URLs inside the report** — bypasses per-mint audit + TTL budgeting of P4-D.
- **New STOMP monitoring topic** — interceptor surface + payload pressure for zero gain over
  the existing invalidation ping.
