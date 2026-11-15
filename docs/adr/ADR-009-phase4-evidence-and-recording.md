# ADR-009 — Phase 4-D: Evidence & Recording (Snapshots, Full Recording, Alert Clips, Secure Playback)

- **Status:** Proposed (P4-D implementation, issues #91–#94)
- **Date:** 2026-11-15
- **Supersedes:** none. Extends ADR-008 (Amendment 1) — browser-first AI detection stays unchanged; this ADR adds the *media evidence* layer on top.
- **Context docs:** `docs/phase4/P4D_EVIDENCE_RECORDING_DESIGN.md` (Quizopia_System), `docs/phase4/AI_PROCTORING_CONTRACT.md` (P4-C).

## 1. Context & Requirements

P4-C lands server-side alert aggregation of browser-side AI detections. P4-D
adds the media evidence chain those alerts point at (issues #91–#94):

- **D1 (#91) Evidence snapshots** — real frames captured at alert time, stored privately, honestly attributed.
- **D2 (#92) Full video recording** — teacher-configured, video-only, per-attempt segments with honest AVAILABLE/FAILED/PARTIAL states.
- **D3 (#93) Alert clips** — clips around alerts from recordings **and** from a real rolling buffer when recording is OFF. *Full Recording OFF + Clips ON must work; a clip may never be fabricated from footage that was never recorded.*
- **D4 (#94) Secure playback & retention** — teacher-only scoped playback, short-lived signed URLs, full access audit, 30d media / 90d metadata retention.

## 2. Decision

### 2.1 Provenance is a first-class column, never a label applied after the fact

Every evidence row records `provenance` exactly as produced:

| Provenance | Meaning (and nothing more) |
|---|---|
| `CLIENT_CAPTURE` | A JPEG the **student's browser** captured from the same `MediaStream` the detector ran on, uploaded via an authenticated student endpoint. The server verifies ownership, policy, bounds, JPEG shape and SHA-256 — **not** that the frame shows what the detection claims. |
| `FULL_RECORDING` | An MP4 written by **LiveKit Egress** (`StartTrackCompositeEgress`, video-only) while the session's full-recording policy was ON. Timing/size come from Egress `FileInfo`. |
| `ROLLING_BUFFER` | An MP4 written by the same egress mechanism but in **bounded pulses** that exist only to serve clip extraction; kept only while newer pulses exist. |

Bounding boxes / JSON payloads are **never** evidence; a snapshot row without a
real stored JPEG object is `FAILED`, not `AVAILABLE`.

### 2.2 Media compatibility: pinned LiveKit v1.9 egress surface (verified)

`livekit-server v1.9.12` pins `livekit/protocol@3688e156` (verified from the
running container's go.mod and the protocol commit). Its `livekit.Egress`
Twirp service serves: `StartRoomCompositeEgress`, `StartWebEgress`,
`StartParticipantEgress`, **`StartTrackCompositeEgress`**, `StartTrackEgress`,
`UpdateLayout`, `UpdateStream`, `ListEgress`, `StopEgress`. The **unified
`StartEgress` RPC does NOT exist** at this pin (it requires a newer server);
we therefore do not use it. Video-only recording = `StartTrackCompositeEgress`
with `video_track_id` set and **`audio_track_id` omitted** (no audio path
exists server-side, satisfying the no-audio rule by construction).

Segmented output at this pin is **HLS-only** (`SegmentedFileProtocol` =
DEFAULT/HLS; MP4 segmentation arrived later). HLS would force per-segment
signed-URL playback complexity — **rejected**. Instead:

- **Full recording (D2):** one continuous MP4 egress run per camera-connection period (restart on reconnect ⇒ segments + explicit gaps from `monitoring_camera_connections`).
- **Rolling buffer (D3):** duty-cycled MP4 egress **pulses** (default 120 s) started when the camera track publishes under a clips-ON/recording-OFF policy, stopped, restarted. The buffer **exists before any alert** (pulses run from camera connect), is bounded (default keep 3 pulses), and old pulse objects are deleted. Pulse boundaries are recorded; clip coverage is computed against them and clips touching pulse restart gaps are honestly `PARTIAL`.

### 2.3 Storage: single private MinIO bucket, backend is the only credential holder

One private bucket `quizopia-evidence` with key prefixes `snapshots/`,
`recordings/`, `rolling/`, `clips/`. The backend holds the MinIO credentials;
egress receives **per-request inline S3 credentials** in the start request
(never persisted in `egress.yaml`). Frontend never sees storage credentials —
it receives short-lived (300 s) presigned GET URLs minted per authorized
teacher access, each mint recorded in the access audit. Range requests work on
presigned GETs (S3 semantics) so browser seeking is served by the same URL.

### 2.4 Coordination model: policy decides, webhooks drive, never inside transactions

- Policy flags (`session_monitoring_policies`, V33) remain **frozen at first
  open** — recording/clips are configured before the session starts, exactly
  like P4-A flags. `evidence_clips` no longer requires `full_recording`
  (P4-D change): clips-ON/recording-OFF enables the rolling buffer instead.
- The recording coordinator reacts to camera-connection webhook routing
  **after commit** (`@TransactionalEventListener(AFTER_COMMIT)`), then calls
  the egress Twirp gateway **outside any DB transaction** (ADR-008 invariant
  6, enforced identically to `TwirpMediaRoomGateway`).
- Egress lifecycle webhooks (`egress_started`/`egress_ended`/`egress_updated`)
  flow through the existing verified webhook pipeline + dedupe ledger; they
  update `monitoring_recording_jobs` from the authoritative `EgressInfo`
  (status, file results: name/size/duration/start/end).
- Media/egress/storage failures NEVER touch attempt, autosave, submit or grade
  paths: the coordinator is strictly after-commit and failure-isolated (its
  errors mark recording rows FAILED + `RECORDING_FAILED` activity only).

### 2.5 Clip extraction: ffmpeg stream-copy, coverage-honest

Clips are produced by an extraction job: presigned GET URLs as ffmpeg inputs,
`-ss/-to` per covering segment, `-c copy` (no re-encode), faststart MP4 to a
temp file, then PUT + SHA-256. Coverage = window `[firstDetectedAt − 30 s,
lastDetectedAt + 60 s]` (bounded ≤ 5 min) intersected with pulse/segment
intervals; uncovered intersections are stored in `coverage_json` and surface
as `PARTIAL` (or `FAILED`/`NO_COVERAGE` when nothing covers the alert). The
ffmpeg binary path is configuration (`quizopia.media.egress.ffmpeg-command`,
default `ffmpeg`); environments without it get honest `FAILED`
(`FFMPEG_UNAVAILABLE`) — never a silent skip.

### 2.6 Retention & audit

- Evidence/recordings metadata expires at per-session `media_retention_days`
  (default 30); objects are deleted by the retention job which then flips
  status to `EXPIRED` (API returns metadata + `EXPIRED`, never a dead URL).
- Activity metadata retention stays 90 d (P4-A).
- Every teacher URL mint/open and playback authorization failure is audited
  (`monitoring_evidence_access`).

## 3. Consequences

- **+** No new services: reuses LiveKit v1.9 stack, MinIO (already in the dev
  compose), Postgres, the verified webhook pipeline and P4-C alert tables.
- **+** Recording-ON and Clips-ON-with-recording-OFF share one mechanism
  (pulsed vs continuous egress), so coverage/gap math is uniform.
- **−** Pulse restart gaps (~1 s/120 s) are inherent to the pulsed design;
  they are recorded and surface as `PARTIAL` clips — an honest trade for not
  upgrading the LiveKit stack mid-phase.
- **−** Clip extraction depends on an ffmpeg binary in the backend host
  environment (configurable; CI images include it; local dev documents it).
- **0** No change to ADR-008 Amendment 1: no Python AI worker, no AI repo, no
  grade coupling, no automatic verdicts. Evidence is teacher-facing material.

## 4. Alternatives considered

- **Upgrade LiveKit/egress for unified `StartEgress`/MP4 segments** — rejected
  for P4-D: requires full P4-B media regression (ICE range, webhooks, canvas
  fallback) with no functional gain the issues demand; the pinned surface
  already supports everything needed.
- **HLS segments for the rolling buffer** — rejected: signed-URL playback of
  HLS playlists requires a playlist rewriter/proxy; MP4 pulses keep playback
  on plain presigned GETs.
- **Server-side snapshot capture via egress `ImageOutput`** — rejected for D1
  alerts: `ImageOutput` shoots at a fixed interval, not at alert times, and
  cannot be triggered per-alert; `CLIENT_CAPTURE` + honest labeling is
  truthful and timely. (`ImageOutput` remains a possible future worker-path
  cross-check.)
- **Java-native MP4 sub-range cutting** — rejected: fMP4 box surgery is high
  risk for no benefit over stream-copy ffmpeg.

### 2.7 Amendment (final gate): snapshot transport decision

The kickoff rule "no raw media frames through Spring Boot/STOMP" targets
STREAMED frame paths (WebSocket/STOMP), not bounded one-shot uploads. The
alternatives for D1 were:

1. **Scoped presigned PUT + server finalization** — the browser uploads
   directly to MinIO. Rejected for P4-D: S3 presigned PUT cannot cap
   Content-Length, so a hostile client could push unbounded bytes straight
   to storage; enforcing the 2 MiB bound would need post-hoc HEAD+delete
   (the abuse already happened), and the two-phase slot flow adds orphan
   states for marginal gain in an authenticated single-tenant deployment.
2. **Multipart JPEG via Spring (CHOSEN for the demo, Product Owner
   sign-off PENDING)** — precise layering: the Spring multipart parser
   accepts the request under the pre-existing TRANSPORT envelope (5 MiB
   file / 6 MiB request — the question-import workbook size, unchanged by
   P4-D); authentication (JWT attempt owner), policy, and the ENDPOINT
   business cap of 2 MiB are enforced in the controller/service AFTER the
   transport has buffered the part — the size guard runs on
   `file.getSize()` before the bytes are processed further. Total exposure
   per attempt is bounded (30 × 2 MiB service cap); the byte stream always
   traverses the backend process (that is the documented trade-off of this
   option).

Safeguards in force: 2 MiB endpoint business cap (distinct from the 5/6
MiB multipart transport cap), per-alert (3) / per-attempt (30) budgets
enforced under a per-alert advisory lock (race-safe), insert-first
reservation so a duplicate never overwrites the winner's metadata or
object, idempotency key, JPEG SOI validation, SHA-256 persistence,
provenance fixed at CLIENT_CAPTURE, orphan reconciliation for objects
whose finalize transaction was lost. **Product Owner sign-off for this
transport decision is still PENDING — it is not approved.** Switching to
presigned PUT later only touches `EvidenceSnapshotService`/
`EvidenceControllers` (ADR-009 §1 semantics unchanged).

## 5. Compliance gates (from the issues, restated as invariants)

1. No clip without prior real footage (rolling pulses start at camera
   connect, before any alert exists) — tested.
2. No audio anywhere in the egress request — tested at the gateway request
   body level.
3. No student/foreign-teacher access to any evidence object or URL — tested
   (IDOR matrix).
4. No `AVAILABLE` without a verified object (HEAD + SHA-256 where produced by
   us; size+duration from Egress `FileInfo` for egress-written files) —
   tested.
5. Exam correctness isolation — camera/egress/storage outages cannot fail
   attempt flows — tested by failure injection.
