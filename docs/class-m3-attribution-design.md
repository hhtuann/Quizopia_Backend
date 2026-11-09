# CLASS-M3 — Historical Attribution & Classroom Reporting Design

Issue: hhtuann/Quizopia_System#54. Status: implemented as described (design gate
satisfied before user-visible gradebook work; see git history for the order).

## 1. Historical cohort attribution (Stage A — M3-HIST)

### Schema (V30, forward-only)

`classroom_membership_periods` — temporal membership history:

| column | type | notes |
|---|---|---|
| `id` | BIGINT identity PK | |
| `classroom_id` | BIGINT NOT NULL → classrooms(id) ON DELETE CASCADE | classroom delete removes its history with it (no API deletes classrooms today) |
| `user_id` | BIGINT NOT NULL → users(id) ON DELETE RESTRICT | matches `classroom_members` FK policy |
| `joined_at` | TIMESTAMPTZ NOT NULL | = `classroom_members.added_at` of the originating INSERT |
| `left_at` | TIMESTAMPTZ NULL | set on the membership DELETE; NULL = current member |

- `chk_cmp_interval`: `left_at IS NULL OR left_at > joined_at`.
- Partial unique `uk_cmp_one_open (classroom_id, user_id) WHERE left_at IS NULL` —
  at most one open period per member, DB-enforced. A new period can only be
  created after the previous one closed, so closed-period overlap is impossible
  by construction (single writer discipline below).
- `idx_cmp_cohort (classroom_id, user_id, joined_at)` — cohort lookups.

### Capture mechanism — DB triggers, not service hooks

`tg_classroom_members_ins/del` fire on every INSERT/DELETE of
`classroom_members`:

- **INSERT** → insert an open period `joined_at = COALESCE(NEW.added_at, now())`,
  unless an open period already exists (defensive; unreachable while
  `uk_classroom_members_classroom_user` holds).
- **DELETE** → close the open period (`left_at = now()`). If none exists
  (legacy row predating the migration, or defensive), self-heal: insert a
  closed period `joined_at = OLD.added_at, left_at = now()`.

**Why triggers over application hooks**: every membership write path
audited (7 paths — direct add, invitation accept, verified-email
reconciliation [OTP/OAuth/roster-add], teacher remove, learner leave,
learner decline, teacher revoke, plus the dev-only demo seeder) funnels
through exactly two SQL shapes against `classroom_members` (INSERT — JPA or
native `ON CONFLICT DO NOTHING`; `DELETE WHERE classroom_id, user_id`).
Triggers make capture *unmissable* by any current or future path, atomic
with the membership mutation, and race-safe under the existing
user-row → entry → member lock ordering (the period row is only ever
touched by the same (classroom, user) statement that holds the members-row
lock — no new lock cycles). `ON CONFLICT DO NOTHING` inserts that skip the
row entirely fire no trigger and create no duplicate period.

### Backfill (honest evidence only)

`INSERT ... SELECT classroom_id, user_id, added_at, NULL FROM classroom_members`
— **current members only**, `joined_at = added_at`. Departed pre-migration
memberships are NOT reconstructible (membership was a hard DELETE; roster
lifecycle rows are path-dependent and retention-purged) and are NOT
fabricated. Documented limitation surfaced by the API
(`historyCoverage = "SINCE"` marker on class reporting).

### Assignment timing (`exam_session_classes`)

No association-history table and no `assigned_at` column — **necessity not
demonstrated**: since M2 (`f0d6195`) both additive assign/unassign freeze at
window start (`SCHEDULED && now >= startsAt` → 409), and this milestone
closes the last gap by adding the same guard to the legacy replace-set
endpoint (`ExamSessionService.assignClasses`), after which **no mutation
path can alter a session's class set once its window begins**. The current
junction therefore IS the assignment record for every session window that
begins after this fix; pre-fix mid-window replace-set mutations are
unrecoverable and documented as a legacy limitation (such a session simply
appears/disappears per the current junction; attempts are never deleted).

### Cohort definition (documented contract)

For class C and session S:

- **taking window** `W(S) = [max(startsAt, openedAt), min(endsAt, closedAt)]`
  where missing bounds resolve to the schedule bound; **empty** when the
  session never actually opened (expired-never-opened SCHEDULED sessions are
  CLOSED with fabricated `opened_at = closed_at = expiry-time > endsAt` →
  `W = ∅` → "not held"), for DRAFT, upcoming SCHEDULED, and CANCELLED.
- **cohort(S, C)** = DISTINCT users with a membership period `p` in C where
  `[p.joined_at, p.left_at)` overlaps `W(S)` (C currently assigned to S).
  Early close shrinks `W` (closedAt); joining after the window ended → not
  in cohort (never retroactively "not attempted"); leaving mid-window keeps
  the overlap already earned; BLOCKED participants remain members — in
  cohort, attempts (if any) counted (BLOCK only gates new starts).
- **upcoming sessions** (window not begun): prospective view = current
  roster, all cells "upcoming", excluded from completed-analytics
  denominators.

## 2. Metric denominators (Stage B contract)

Per (session, class): `cohort`, `started` (≥1 attempt any status),
`submitted` (≥1 SUBMITTED/GRADED attempt), `graded` (≥1 persisted Grade),
`notAttempted = cohort − started`, `completion% = submitted/cohort`
(NULL when cohort = 0), `average%` = arithmetic mean of **BEST** grade
percentages among graded cohort users (BigDecimal, scale 2, HALF_UP).
Missing results are NULL — never zero. SUBMITTED without grade = "pending
grading" (distinct cell state). Per-student mean% and completion use the
same primitives over the student's cohort-visible sessions.

**Nullability (Final Gate P2-01).** `notAttempted` and per-session
`completion%` are COMPLETED-session metrics: non-null only when the
session's window has begun (`WINDOW`); NULL for UPCOMING (a prospective
roster is not a denominator and nobody has "not attempted" an unopened
assignment), DRAFT, CANCELLED and NOT_HELD — on every surface (gradebook
columns, matrix footer, trend, single-session detail). Raw
started/submitted/graded counters remain exact everywhere.

**Class overall completion (Final Gate P2-02).**
`overallCompletionPercentage = completedPairs / eligiblePairs` where the
pairs are DISTINCT (session, learner) cohort memberships over **ALL**
eligible WINDOW sessions of the class — aggregated in SQL, never sampled
by the ≤20-session trend bound; a learner counts once per session
(multi-epoch membership deduped) and correctly across sessions. Both pair
counts are returned (`eligiblePairs`, `completedPairs`; NULL when no
eligible pairs). Known cosmetic divergence (accepted): a session whose
`startsAt` falls between the lifecycle refresh instant and the analytics
read may appear on the trend while contributing nothing to the overall
pairs for that request (sub-second window; nobody can have completed a
session that started milliseconds ago).

**BEST selection** reuses the existing SQL ordering verbatim
(`percentage DESC NULLS LAST, final_score DESC, submitted_at ASC, attempt_id
ASC` — identical to `BestResultComparator`); the CTE is extracted into a
shared query component used by session and classroom reporting alike. No
re-grading, no recomputation of persisted scores.

## 3. Deliberate exclusions

- No `exam_session_classes` history table / `assigned_at` (see §1).
- No new grading engine, no attempt/grade cloning, no roster PII in
  reporting outputs (display names only).
- Learner class reporting is strictly self-scoped; leaving a class retains
  `/api/attempts/my` + own results under existing authorization but revokes
  the class detail (M1/M2 behavior unchanged).

## 4. Known limitations (surfaced, not hidden)

1. Pre-V30 departed memberships are unrecoverable → class reports are
   cohort-accurate for windows overlapping periods recorded since V30 plus
   the backfilled still-current memberships; the API marks coverage
   `SINCE_MIGRATION` where relevant (docs + PR).
2. Pre-guard mid-window replace-set reassignments are unrecoverable (§1).
3. Teacher removal writes no consent/opt-out row (M1 asymmetry, unchanged).
