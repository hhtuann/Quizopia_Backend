# ADR-005: Remove Organization & Workspace Architecture (User-Centric Model)

- Status: ACCEPTED (supersedes ADR-001's workspace/organization foundation)
- Date: 2026-11-07
- Deciders: Project owner directive; implemented by the autonomous engineering team

## Context

ADR-001 introduced a three-layer tenancy (Organization → Workspace → Membership)
as a transitional bridge from the legacy school era toward a public platform.
The planned Organization Epic (#24, issues #25–#32) was cancelled before
implementation. In practice every real flow already ran on user-owned
resources: V12/V16/V17/V20 moved attempts, participants and classrooms to
user-based identity, and Identity M1–M5 made the platform user the only
account authority. The workspace layer added authorization complexity
(WorkspaceAccessService, OrganizationWorkspaceResolver, revoked-membership
semantics) without serving any live feature.

## Decision

Remove the Organization/Workspace architecture entirely:

1. **Roles**: Users hold STUDENT, TEACHER, or both. ACADEMIC_ADMIN and
   SYSTEM_ADMIN leave the role model; every dependent admin API (user
   management, role catalog, school/subject CRUD, student onboarding,
   SYSTEM_ADMIN result break-glass) is decommissioned. No STUDENT/TEACHER
   gains administrative power; former ACADEMIC_ADMIN holders keep content
   ability via TEACHER (V24 backfill), SYSTEM_ADMIN-only holders fall back
   to the STUDENT base role so nobody loses login.
2. **Ownership**: Resources belong directly to users —
   `question_banks.created_by_user_id`, `exams.created_by_user_id`,
   `classrooms.owner_user_id`, `exam_sessions.created_by`,
   `attempts.user_id`, `exam_session_participants.user_id`. Authorization
   is a server-side owner check: load by id → 404 (anti-enumeration) when
   absent, 403 when the caller is not the owner. List queries are
   creator-scoped. No membership, no sharing, no admin override.
3. **Session access**: PUBLIC = any ACTIVE user with ATTEMPT_START;
   CLASS_RESTRICTED = classroom membership (`exam_session_classes` ×
   `classroom_members.user_id`); BLOCKED participant rows deny new starts.
   Organization membership is no longer consulted anywhere.
4. **Academic metadata**: Subjects, grade levels and exam purposes become
   a GLOBAL seed-managed catalog (read-only API), optionally referenced by
   banks/exams. The academic feature (subject tagging/filtering) survives;
   school scoping does not.
5. **Classrooms**: single ownership mode (owner user). Members are platform
   users; teachers add them via user search (TEACHER-gated, min 2 chars,
   capped) or the existing verified-email roster invitations.

## Migration strategy

Forward-only Flyway chain, EXPAND → BACKFILL → VERIFY → CONTRACT:

- **V24** (expand/backfill/verify): consolidates roles; dedupes the
  school-scoped catalogs into global ones (grade levels by code, subjects
  by (grade, code), purposes by code) with reference remapping; creates
  user-scoped uniqueness (`(created_by_user_id, LOWER(code))` etc.) and
  creator indexes. Ambiguity rule: if the same user owns two resources
  with the same code in different workspaces, V24 FAILS with an explicit
  error — no silent renaming of business identifiers.
- **V25** (contract): drops transitional triggers/functions, workspace
  columns, legacy school/profile provenance columns, the detached
  exam-versioning debris, and the organization/workspace/school/profile
  tables. All user content (banks, questions, exams, sessions, attempts,
  results, classrooms, rosters, history) is preserved.

## Consequences

- One authorization mental model (owner checks) replaces layered
  membership policy; revoked-membership test matrices disappear.
- Cross-user collaboration inside one container is no longer possible by
  design; sharing happens through sessions (PUBLIC/CLASS_RESTRICTED),
  session share links and classroom rosters.
- The catalog (subjects/grades/purposes) is seed-managed; product changes
  to it ship as migrations.
- Rollback path: restore from pre-V24 backup (forward-only chain), or
  revert the release before migration.
