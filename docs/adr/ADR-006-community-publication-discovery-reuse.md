# ADR-006: Community publication, discovery & reuse (P3 MVP)

- Status: PROPOSED (architecture gate for Epic #38 P3-A/P3-B; no production code before acceptance)
- Date: 2026-11-08 — Rev 2 (independent architecture/security/DB review findings folded in; see §16)
- Deciders: autonomous engineering team; human approval required before merge
- Supersedes: the deleted local Community draft that targeted Workspace/Org (never merged). Builds on ADR-005 (user-centric model) and ADR-004 (identity M5 conventions).

## 1. Context (source audit, 2026-11-08)

Audited at Backend `d671a60`, Frontend `10c90be` (System `c4738c2` gitlink baseline), branch `feature/p3-community`.

- Ownership is user-centric since V24/V25: `exams.created_by_user_id`, `question_banks.created_by_user_id`, owner-check authorization (load → 404 anti-enumeration / 403), no workspace/org columns, roles are exactly STUDENT and TEACHER (dual-role supported). SYSTEM_ADMIN/ACADEMIC_ADMIN are gone and MUST NOT return.
- RBAC is DB-backed (`roles`, `permissions`, `user_roles` with `expires_at`, `role_permissions`); checks are service-layer `requirePermission` style via `RolePermissionRepository.findEffectivePermissionCodesByUserId`; no method-security annotations. JWT subject = user id, live-DB revalidation each request (status ACTIVE + token_version); unverified-email users cannot log in at all (AuthN-layer denial).
- Exam lifecycle is DRAFT → PUBLISHED → ARCHIVED; **no exam unpublish exists** (archive only, blocked by live sessions). `Exam.version` is the JPA optimistic token; `ExamRepository.findByIdForUpdate` is the pessimistic-write lock pattern; every exam lock taker locks only the exam row.
- `exam_sections` / `exam_questions` / `exam_question_options` ARE the immutable published composition snapshot (question content, `answer_key`, option `is_correct`, `default_points`, `difficulty`, `explanation`, `source_question_id` + `source_question_version_id` provenance); the TRUE_FALSE_MATRIX grading ladder lives on `exams.tf_matrix_scoring`. Composition is DRAFT-only editable, so a PUBLISHED exam's composition never changes — the basis for denormalized counts (§4). Question banks/questions/versions/options are owned by users; `ExamService.publishExam` already enforces bank-owner == exam-owner.
- `ExamService.duplicateExam` is same-owner and copies provenance ids verbatim — not sufficient for cross-user fork.
- Cross-module reuse today is direct repository injection; there are no port interfaces and no ArchUnit enforcement. This ADR introduces narrow ports for Community only; it does not refactor existing modules.
- Abuse control precedent: V23 `password_reset_abuse_buckets` — PostgreSQL HMAC-keyed fixed-window buckets, servlet remote address only (never forwarded headers), `ON CONFLICT DO NOTHING` → `SELECT … FOR UPDATE` upsert. In-transaction dedupe precedent: `INSERT … ON CONFLICT … DO NOTHING` + re-read (roster membership), because a caught constraint violation aborts the enclosing PostgreSQL transaction. Redis is deployed in compose but used by **no application code**; it stays unused by Community.
- Audit precedent: `identity_audit_events` — append-only by repository surface (no update/delete API). Error envelope: per-module `*ErrorCode` enum + module exception → `GlobalExceptionHandler` → `ApiError(timestamp,status,code,message,path,traceId)`. Pagination: `PageResponse`, 0-based page/size, sort allowlist, `id DESC` tiebreaker; list convention is `created_at DESC, id DESC`.
- `users.display_name` is a free-form self-service field currently exposed only to the user themself and privileged instructional surfaces (rosters, classroom members, teacher-gated search) — **not** public anywhere. This drives §7's alias-consent decision.

## 2. Decision

A `community` bounded context (`com.quizopia.backend.community`, same api/application/domain/repository/dto/exception layering) owning publication, taxonomy, reports, moderation audit, bookmarks and fork records. Community never writes Exam/Question tables: it orchestrates through two narrow ports implemented in the exam/question application layers (identity access stays the existing repository-based authorization pattern every module uses). Public discovery is a read-only SQL projection with eligibility filtering before count/pagination. Moderation power is an explicit, operator-provisioned, revocable per-user grant — no global admin role. Reuse defaults to NO_FORK; fork clones the **published exam composition snapshot** into a fully independent bank/question/exam set owned by the requesting teacher, atomically and idempotently.

## 3. Data model (forward-only after V25)

All ids BIGINT IDENTITY; timestamps TIMESTAMPTZ; enum state CHECK-constrained. **FK policy**: every FK to `users` or `exams` is `ON DELETE RESTRICT` (no user or exam hard-delete path exists; RESTRICT makes that permanent); `community_publication_tags` cascades from both parents; `community_reports.publication_id` and `community_bookmarks.publication_id` cascade (their meaning dies with the publication). No workspace/org FK anywhere.

**V26 `community_foundation`**

- `community_categories`: `id`, `code VARCHAR(40)` unique ci, `title VARCHAR(100)` NOT NULL, `position INT NOT NULL DEFAULT 0`, `created_at`. Seeded once, migration-managed (like `exam_purposes`), from the V24 subject catalog deduplicated by code — `subjects` rows are per-grade (`(grade_level_id, LOWER(code))` unique; `TOAN` exists per grade), so the seed is:
  ```sql
  INSERT INTO community_categories (code, title, position)
  SELECT code, name, rn FROM (
    SELECT DISTINCT ON (LOWER(s.code)) LOWER(s.code) AS code, s.name, s.id,
           dense_rank() OVER (ORDER BY LOWER(s.code)) AS rn
    FROM subjects s WHERE s.status = 'ACTIVE'
    ORDER BY LOWER(s.code), s.id
  ) t;
  ```
  (`name`→`title` mapping explicit; deterministic tie-break; works identically on fresh and V25→V26 legs. Matrix assertion: `count(*) = count(DISTINCT LOWER(code))` with contiguous unique positions — not a literal row count, since the upgrade leg preserves legacy subject codes). No CRUD API.
- `community_publications`:
  - `id` PK; `exam_id BIGINT NOT NULL UNIQUE` FK→`exams` ON DELETE RESTRICT (one publication per exam, immutable); `publisher_user_id BIGINT NOT NULL` FK→`users` ON DELETE RESTRICT (immutable, equals `exams.created_by_user_id` at every state change — invariant rechecked, never updated; app-layer enforced — the entity exposes no setter and no UPDATE path writes these columns, asserted by test).
  - `status` CHECK `('UNPUBLISHED','PUBLISHED')`; `moderation_state` CHECK `('VISIBLE','HIDDEN')`; `reuse_policy` CHECK `('NO_FORK','ALLOW_FORK')` DEFAULT `NO_FORK`.
  - `title VARCHAR(255) NOT NULL`; `summary VARCHAR(500)`; `author_alias VARCHAR(80)` NULL (required at publish, §4/§7); `category_id` FK→`community_categories` ON DELETE RESTRICT NULL (categories are migration-managed and never deleted); `question_count INT NOT NULL DEFAULT 0`; `section_count INT NOT NULL DEFAULT 0`; `duration_minutes INT NOT NULL DEFAULT 0` (`COALESCE(exams.duration_minutes, 0)` — the source column is nullable); `published_at TIMESTAMPTZ` NULL.
  - `version INT NOT NULL DEFAULT 0` (JPA `@Version`, the owner optimistic token); `created_at`/`updated_at`.
  - Indexes: catalog `(status, moderation_state, published_at DESC, id DESC)`; owner list `(publisher_user_id, created_at DESC, id DESC)`.
- `community_tags`: `id`, `label VARCHAR(40)` unique ci; created on demand from create/PATCH payloads (lower-cased, trimmed, `[a-z0-9][a-z0-9 -]{0,39}`, max 5 distinct per publication) via `INSERT … ON CONFLICT DO NOTHING`.
- `community_publication_tags`: PK `(publication_id, tag_id)`, FKs CASCADE; plus `INDEX (tag_id)` for tag-driven semi-joins and usage counts.
- `community_rate_buckets`: generalized V23 shape — `bucket_key VARCHAR(64)` PK CHECK 64-hex (domain-separated HMAC-SHA-256, §9), `window_start TIMESTAMPTZ`, `attempts INT CHECK 0..2147483647`, `last_admitted_at` NULL, `expires_at`; `CHECK expires_at > window_start`; expiry index; 2h retention sweep. Ships in V26 with the limiter component because it gates the #40 endpoints from day one (#42 "common gate" depends on #40).
- Permission seeds: `COMMUNITY_PUBLISH`, `COMMUNITY_FORK` → TEACHER; `COMMUNITY_REPORT`, `COMMUNITY_BOOKMARK` → STUDENT and TEACHER. Dormant role `COMMUNITY_MODERATOR` (granted to nobody by the migration) with permission `COMMUNITY_MODERATION`. Idempotent `INSERT … WHERE NOT EXISTS`; `user_roles` remains the only grant mechanism.

**V27 `community_moderation`**

- `community_reports`: `id`, `publication_id` FK CASCADE, `reporter_user_id` FK→users RESTRICT, `reason_code` CHECK `('INAPPROPRIATE_CONTENT','COPYRIGHT','MISLEADING','SPAM','OTHER')`, `details VARCHAR(500)`, `status` CHECK `('OPEN','DISMISSED','RESOLVED')` DEFAULT OPEN, `handled_by` NULL FK→users RESTRICT, `handled_at`, `resolution_note VARCHAR(500)`, `created_at`; partial unique index `UNIQUE(reporter_user_id, publication_id) WHERE status='OPEN'`; indexes `(status, created_at)`, `(publication_id)`.
- `community_moderation_actions` (append-only audit): `id`, `actor_user_id` FK→users RESTRICT (the operator's dedicated platform account for GRANT/REVOKE rows, §5), `publication_id` NULL FK RESTRICT, `report_id` NULL FK RESTRICT, `action` CHECK `('HIDE','RESTORE','REPORT_DISMISS','REPORT_RESOLVE','MODERATOR_GRANTED','MODERATOR_REVOKED')`, `from_status`/`to_status`, `from_moderation_state`/`to_moderation_state`, `reason VARCHAR(500) NOT NULL`, `correlation_id VARCHAR(64)`, `created_at`; indexes `(publication_id, created_at DESC)`, `(report_id)`. Repository exposes `save` + reads only.

**V28 `community_bookmarks`**: `id`, `user_id` FK→users RESTRICT, `publication_id` FK CASCADE, `created_at`, `UNIQUE(user_id, publication_id)`, index `(user_id, created_at DESC, id DESC)`.

**V29 `community_fork_records`**: `id`, `actor_user_id` FK RESTRICT, `idempotency_key_hash VARCHAR(64)` CHECK 64-hex (server-keyed HMAC of `community-fork:<actor_user_id>:<key>`, §9 key posture), `source_publication_id` FK RESTRICT, `source_exam_id` FK RESTRICT, `target_exam_id BIGINT NOT NULL UNIQUE` FK→exams RESTRICT, `source_title_snapshot VARCHAR(255)`, `source_alias_snapshot VARCHAR(80)`, `created_at`; `UNIQUE(actor_user_id, idempotency_key_hash)`.

Migration-matrix tests (fresh V1→head and staged V25→head with legacy data, following `V24UserCentricMigrationMatrixTest`) accompany every migration.

## 4. Publication lifecycle & invariants

- **Public visibility predicate** (shared verbatim by catalog, detail, `/tags` usage counts, bookmark save/list eligibility and fork eligibility): `community_publications.status='PUBLISHED' AND moderation_state='VISIBLE' AND exams.status='PUBLISHED' AND publisher users.status='ACTIVE' AND publisher_user_id = exams.created_by_user_id`. Any failure → 404 anti-enumeration on detail; filtered before count/pagination in lists. Hidden-source tags therefore vanish from `/tags` counts (dormant labels never render without a visible publication).
- **Create**: `POST /api/community/publications {examId, title, summary, categoryId, tags[], authorAlias?, reusePolicy}`. Eligibility: caller ACTIVE + `email_verified_at IS NOT NULL` + active TEACHER role + effective `COMMUNITY_PUBLISH`; exam exists; `exam.created_by_user_id == caller`; `exam.status == PUBLISHED`. Created row: UNPUBLISHED / VISIBLE / NO_FORK default (`reusePolicy` may set ALLOW_FORK at create; FE shows the consent warning). Never mutates the exam. Duplicate publication for the same exam → 409 `COMMUNITY_PUBLICATION_EXISTS`. `question_count`/`section_count`/`duration_minutes` denormalized from the exam composition at create and re-derived on publish; because a PUBLISHED exam's composition is immutable (DRAFT-only edits, no unpublish), recompute at create+publish is drift-free — any future exam feature that changes composition of published exams MUST extend this rule.
- **Update**: `PATCH /api/community/publications/{id}` — owner only; any status; PATCHable set exactly `{title, summary, categoryId, tags (replace-set, ≤5), authorAlias, reusePolicy}`; `expectedVersion` required, mismatch → 409 `COMMUNITY_CONCURRENT_MODIFICATION`. `examId`/`publisher` immutable.
- **Publish**: UNPUBLISHED→PUBLISHED under publication row lock (never the exam lock — preserving §8's lock order) with eligibility recheck (exam still PUBLISHED, publisher still ACTIVE+verified+TEACHER+`COMMUNITY_PUBLISH`; failure → 409 `COMMUNITY_PUBLISH_INELIGIBLE`) and `author_alias` present (else 409 `COMMUNITY_ALIAS_REQUIRED`). Already PUBLISHED **and VISIBLE** → 200 idempotent no-op. Any HIDDEN state → 409 `COMMUNITY_MODERATION_HIDDEN` (author cannot bypass moderator hide; hide survives unpublish/republish). A concurrent archive may commit between the exam recheck and commit, briefly leaving a PUBLISHED publication over an ARCHIVED exam; the visibility predicate re-checks `exams.status` on every read, so the window is self-healing and accepted.
- **Unpublish**: PUBLISHED→UNPUBLISHED, publication row lock only. Already UNPUBLISHED → 200 idempotent no-op. Does not touch exam/session state.
- **Moderator hide**: VISIBLE→HIDDEN (publication row lock only, mandatory reason, audit row). Already HIDDEN → 200 no-op without a duplicate audit row. **Restore**: HIDDEN→VISIBLE (already VISIBLE → 200 no-op, no duplicate audit row); does NOT change `status` — if the owner unpublished meanwhile, it stays UNPUBLISHED and the owner may republish (unless hidden again).

## 5. Moderator model & operator procedure

- Moderator = any ACTIVE user holding the dormant `COMMUNITY_MODERATOR` role (checked through the existing effective-permission path: `COMMUNITY_MODERATION`). TEACHER/STUDENT get nothing automatically; dual-role moderators are fine. Revocation = role row deletion or `expires_at` (existing `user_roles` semantics); effective permissions re-resolve per request, so revocation lag is zero.
- **Deliberate deviation from #39's literal "moderator grant table" wording**: the grant IS the `user_roles` row for the dormant role — reviewed and accepted (dedicated table would duplicate RBAC semantics: revocability, expiry, per-request resolution, audit).
- **No self-service grant endpoint exists or may be added.** Provisioning is an operator runbook (`docs/runbooks/community-moderator-provisioning.md`, delivered with #42): operators act through a dedicated ACTIVE platform operator account holding no moderator/content roles; the runbook SQL inserts/deactivates the `user_roles` row and inserts the `MODERATOR_GRANTED`/`MODERATOR_REVOKED` audit row with that account as `actor_user_id`, pins the exact INSERT statements (including `expires_at` policy: grants default to no expiry, optional bounded expiry documented) and verification queries, and is executed only by credentialed DB operators. The runbook is an acceptance-tested artifact (#42 "operator setup tested") and the launch-blocking operator step recorded in #49.
- Moderator API (`/api/community/moderation/**`, authenticated, effective `COMMUNITY_MODERATION`, moderator ACTIVE): report queue (paged, status filter), hide/restore with mandatory reason, report dismiss/resolve, per-publication history. Moderator surfaces expose internal user ids (reporter, actors) — an intentional, audited privilege documented in the report-submission UX ("reports are visible to community moderators"); no username/email/phone.

## 6. Module boundaries & ports

Community may not inject Exam/Question repositories or mutate their aggregates. It consumes two ports plus the shared authorization pattern:

- `ExamCommunityPort` (exam module, implemented in exam application):
  - `PublicationEligibility checkPublicationEligibility(Long examId, Long userId)` — read-only projection record `{examOwnerId, examStatus, durationMinutes (COALESCE'd), sectionCount, questionCount}` (counts from the current composition; the create/publish gates and denormalization both consume it);
  - `PublishedCompositionSnapshot loadPublishedSnapshot(Long examId)` — server-only record, never serialized to any API, never logged. Exact fields — exam level: `tfMatrixScoring`, `instructions`, `durationMinutes`, `subjectId`, `purposeId`, `title`, `description`; section: `position, title, instructions, code`; question: `position, questionType, content, points, difficulty, explanation, answerKey, metadata, sourceQuestionId, sourceQuestionVersionId`; option: `optionKey, content, isCorrect, position`;
  - `Long createForkDraft(ForkDraftSpec spec)` — fresh DRAFT exam (new `EX` code; `source_exam_id` = source exam; `title` = source title; `subjectId`/`purposeId`/`description`/`instructions`/`durationMinutes` copied; `tf_matrix_scoring` deep-copied — `copyGradingPolicyFrom` semantics; total points recomputed by the normal publish path) with sections/questions/options whose `source_question_id`/`source_question_version_id` are rewritten to the cloned ids.
- `QuestionCommunityPort` (question module): `QuestionCloneResult cloneForFork(PublishedCompositionSnapshot snapshot, Long ownerUserId)` — one fresh bank (new `QB` code) + fresh questions (`QU` codes) + version 1 + options copied from the snapshot for all four types (SINGLE_CHOICE, MULTIPLE_CHOICE, TRUE_FALSE_MATRIX, NUMERIC_FILL), `created_by_user_id = ownerUserId`, question status ACTIVE, type/content/points/difficulty/explanation/answer data preserved. Returns `sourceQuestionId → (newQuestionId, newVersionId)` mapping + new bank id.
- Identity access follows the existing pattern: a community `CommunityAuthorizationService` reading `UserRepository`/`UserRoleRepository`/`RolePermissionRepository` (the dependency direction every module already uses for auth).
- The §4 visibility predicate is implemented once in a read-only `CommunityVisibilityStore` (JdbcTemplate): the public catalog projection joins `community_publications` × `community_categories` × `exams` × `users` with the predicate in the WHERE clause **before COUNT and LIMIT/OFFSET** (deterministic order `published_at DESC, id DESC`; tag filters compile to one `EXISTS (… publication_tags …)` probe per requested tag — AND semantics); detail, bookmark save, `/tags` counts and fork recheck reuse the same predicate. Community performs no writes outside its own tables.

## 7. API contract, DTO allowlists & auth matrix

All responses use the existing `ApiError` envelope. 404 for absent/invisible/foreign resources on public and owner routes; 403 for "authenticated, resource visible or owner-scoped, but caller not allowed" (foreign owner edits, NO_FORK fork, non-moderator moderation routes); disabled/LOCKED/PENDING callers are rejected **401 at AuthN** by the JWT converter (service-layer ACTIVE checks remain defense-in-depth); unverified users cannot log in, so every authenticated route denies them at AuthN. `Cache-Control: no-store` on every community endpoint (public and authenticated) until a proven invalidation story exists.

**Anonymous (exact-path, GET-only permitAll additions; any other method on these paths → 401 by the deny-by-default chain):**

| Route | Response |
|---|---|
| `GET /api/public/community/publications?q&categoryId&tags&page&size` | `PublicPublicationPage` |
| `GET /api/public/community/publications/{id}` | `PublicPublicationDetail` or 404 |
| `GET /api/public/community/categories` | `[{id, code, title}]` (Rev 4: `id` added — the `categoryId` filter key; taxonomy ids are public catalog identity per the `/api/exam-purposes` platform precedent, not internal content ids) |
| `GET /api/public/community/tags` | top 100 `[{label,usageCount}]`, counts over §4-visible publications only |

`PublicPublicationCard` (exact allowlist): `publicationId, title, summary, authorName, categoryCode, categoryTitle, tags[], questionCount, sectionCount, durationMinutes, publishedAt, allowFork`. Detail = card + `updatedAt`. **Never** in any public DTO: answers, `is_correct`, `answer_key`, question/option content or ids, `exam_id`/`sourceExamId`, internal section ids, username, email, phone, roles, publisher user id, `display_name`, session/attempt/result/roster data. **`authorName` = `author_alias` only** — publish requires the author to set an explicit public credit alias (FE pre-fills from display name behind an explicit "publicly visible" acknowledgment); the server rejects aliases equal (case-insensitive) to the publisher's email or username. `display_name` is never exposed publicly (§1: it is self-service free-text with no public surface today). Search `q`: parametrized bounded ILIKE on title/summary (escaped `%_`, length ≤ 100); `tags` AND semantics; `size` ≤ 50, `page` ≥ 0. Recursive JSON redaction tests serialize real DTOs and assert no forbidden keys/answers appear.

**Auth matrix (route × caller → outcome):**

| Route | Guest | STUDENT | TEACHER (owner) | TEACHER (foreign) | Disabled/LOCKED/PENDING | Unverified |
|---|---|---|---|---|---|---|
| public GET (4 routes) | 200 | 200 | 200 | 200 | 200 (public) | 200 (public) |
| any method on `/api/public/community/**` beyond GET | 401 | 405/401 | 405/401 | 405/401 | 401 | 401 |
| `GET /api/community/me/publications` | 401 | 403 `COMMUNITY_ACCESS_DENIED` | 200 | 200 (own list only) | 401 AuthN | 401 AuthN |
| create / PATCH / publish / unpublish | 401 | 403 | §4 outcomes | 404 (foreign id) | 401 AuthN | 401 AuthN |
| `POST …/{id}/reports` | 401 | 201 / 200 dedupe | 201 / 200 dedupe | 201 / 200 dedupe | 401 AuthN | 401 AuthN |
| bookmark PUT/DELETE + `GET /me/bookmarks` | 401 | §7 bookmark rules | same | same | 401 AuthN | 401 AuthN |
| `POST …/{id}/fork` | 401 | 403 `COMMUNITY_ACCESS_DENIED` | 201/200/409 | 201/200/409 (on visible ALLOW_FORK) | 401 AuthN | 401 AuthN |
| `/api/community/moderation/**` | 401 | 403 | 403 (no grant) | 403 (no grant) | 401 AuthN | 401 AuthN |
| moderator routes with grant | — | — | §5 outcomes | §5 outcomes | 401 AuthN | 401 AuthN |

(405 where the method is genuinely unmapped on a matched path; 401 where the deny-by-default chain catches it. All cells additionally inherit §4 visibility 404s and §9 429s.)

**Owner DTO** adds `examId, status, moderationState, reusePolicy, version, openReportCount (OPEN reports only — no reporter data), createdAt, updatedAt, publishedAt, authorAlias`.

**Reports (any ACTIVE user + `COMMUNITY_REPORT`)**: `POST /api/community/publications/{id}/reports {reasonCode, details?}`. Fixed order: (1) rate admission for every syntactically valid request; (2) §4 visibility → 404; (3) OPEN-dedupe → 200 returning the existing report (re-reporting a since-hidden publication therefore 404s — no state-transition oracle); (4) insert → 201. Dedupe uses `INSERT … ON CONFLICT (reporter_user_id, publication_id) WHERE status='OPEN' DO NOTHING` + re-read (partial-index inference), never a caught constraint violation inside the live transaction.

**Bookmarks (any ACTIVE user + `COMMUNITY_BOOKMARK`)**: `PUT`/`DELETE /api/community/publications/{id}/bookmark` — PUT requires the §4 predicate (404 otherwise), idempotent under concurrency via `INSERT … ON CONFLICT (user_id, publication_id) DO NOTHING` + re-select; DELETE idempotent (absent → 200). Bookmarks never grant read rights beyond public metadata. `GET /api/community/me/bookmarks` paged (`created_at DESC, id DESC`): available entries = `PublicPublicationCard + {savedAt, unavailable:false}`; entries failing the predicate = `{publicationId, savedAt, unavailable:true}` ONLY (no title/alias/category/tags or any last-known content).

**Fork (TEACHER + `COMMUNITY_FORK` + `EXAM_CREATE`, ACTIVE)**: `POST /api/community/publications/{id}/fork` with required `Idempotency-Key` header (8–128 printable ASCII, hashed per §3). `NO_FORK` visible publication → **403 `COMMUNITY_FORK_NOT_ALLOWED`**. 201 first creation `{targetExamId, sourcePublicationId, sourceTitle, authorAlias, forkedAt}` (attribution from the already-public snapshot); same key + same publication → 200 identical target; same key + different publication → 409 `COMMUNITY_IDEMPOTENCY_CONFLICT`.

**Moderation**: `GET /api/community/moderation/grant` (200 `{granted:true}` with grant / 403 without — FE console gate); `GET /api/community/moderation/reports?status&page&size`; `POST /api/community/moderation/publications/{id}/hide|restore {reason}`; `POST /api/community/moderation/reports/{id}/dismiss|resolve {note?}`; `GET /api/community/moderation/publications/{id}/history`.

## 8. Fork algorithm (lock order, atomicity, idempotency)

1. AuthN → ACTIVE + TEACHER + `COMMUNITY_FORK` + `EXAM_CREATE` (unverified callers already cannot authenticate — §7). Require `Idempotency-Key`; compute the §3 HMAC hash.
2. Pre-check `community_fork_records` for `(actor, key_hash)` read-only: hit + same publication → return the original 200 (no locks); hit + different publication → 409.
3. **Transaction begins.** Rate admission runs first (§9: bucket lock is always the leaf-most row lock). **Lock the source `exams` row** (`findByIdForUpdate`), **then the `community_publications` row** (`SELECT … FOR UPDATE`). Global lock order: `rate bucket → exam → publication`; community publish/unpublish/hide/restore take only publication locks and exam archive takes only the exam lock, so no cycle exists (the discipline is enforced by #47's race tests).
4. Recheck under lock: §4 predicate + `reuse_policy='ALLOW_FORK'` (else 403 `COMMUNITY_FORK_NOT_ALLOWED` / 404 per matrix).
5. Load `PublishedCompositionSnapshot` from the locked exam's composition rows (the immutable published state, never the mutable `question_versions`).
6. `QuestionCommunityPort.cloneForFork` → new bank/questions/versions/options owned by requester (four types; options with `is_correct`, `answer_key` for NUMERIC_FILL).
7. `ExamCommunityPort.createForkDraft` → new DRAFT exam + composition with remapped `source_question_id`/`source_question_version_id`, `source_exam_id` = source exam, owner = requester. Bank owner == exam owner holds by construction, so the new owner's existing edit/publish APIs work unchanged.
8. Insert `community_fork_records` with an explicit `saveAndFlush` (Hibernate flush order is unspecified; the unique violation must surface inside the transaction, before the commit-time dance). Commit → 201.
9. Concurrent same-key losers: the `UNIQUE(actor_user_id, idempotency_key_hash)` violation is caught **outside the `@Transactional` boundary** (non-transactional orchestrator method around the transactional fork method) and resolved by a fresh `REQUIRES_NEW` read-only lookup (an aborted PostgreSQL transaction cannot serve the lookup): same publication → 200 winner target; different → 409. No sessions, attempts, results, grades or rosters are touched; source rows are never written; any injected mid-failure leaves no orphan rows (single transaction, rollback).

## 9. Rate limiting & abuse

`CommunityRateLimiter` — reusable, PostgreSQL-backed fixed-window admission generalizing V23 (`community_rate_buckets`), keyed by domain-separated HMAC (`community:<dimension>:<value>`) of `user:<id>` or `ip:<servlet-remote-addr>`; forwarded headers are never trusted (V23 posture). Upsert = `ON CONFLICT (bucket_key) DO NOTHING` → `SELECT … FOR UPDATE` → `UPDATE` (V23 mechanics). Admission is the **first** statement of every community write transaction (before exam/publication locks — bucket locks stay leaf-most in the §8 order); multi-bucket requests evaluate user-keyed then IP-keyed buckets (V23 fixed-order precedent). **Fail closed**: bucket-store unavailability fails the request (#42's "Redis outage" acceptance item is satisfied by this test — Redis is not adopted, §14). Thresholds (tunable `quizopia.community.rate-limit.*`): publish/unpublish 10/h/user; create/update 30/h/user (taxonomy abuse); report 5/h/user + 20/h/IP; bookmark mutate 60/h/user; fork 5/h/user; anonymous public reads 6000/h/IP (generous: a catalog page costs 3–4 calls and remote-addr-only keying pools NAT cohorts). Authenticated callers on public endpoints are never IP-bucketed (user bucket only). Deployment invariant: the backend must not sit behind an unconfigured reverse proxy — with a proxy, remote-addr collapses to one bucket key; trusted-proxy support is a separate future decision. 429 body carries `COMMUNITY_RATE_LIMITED` + `Retry-After`; no PII in keys or logs (HMAC only).

## 10. Frontend contract

- Public (outside `RequireAuth`, anonymous deep links work): `/community` and `/community/quizzes/[publicationId]` at top level of `app/` (like `/login` — the authenticated route groups hard-gate roles and cannot host them). **An anonymous-capable header is a new deliverable** (no anonymous header exists today; `AppShell`/`Header` render only for authenticated groups): a minimal public community header with brand + login/register CTAs, following DESIGN.md tokens. Safe render: React text escaping only, no `dangerouslySetInnerHTML`, no markdown rendering of community content, no answer/option/PII in DOM or metadata/SEO; `no-store` respected.
- Protected pages use page-level `RequireAuth`: `/community/mine` (`requireRole="TEACHER"`; publish/manage, version-conflict and hidden-state actionable errors, ALLOW_FORK consent warning, **public-credit-alias acknowledgment at publish**), `/community/bookmarks` (role-free — STUDENT and TEACHER share one collection), `/community/moderation` (gated by `GET …/grant`; queue, hide/restore with mandatory reason, history).
- "Publish to Community" entry lives in the existing teacher exam detail overview actions (`?tab=overview`), PUBLISHED exams only. Bookmark CTA on public detail asks guests to log in preserving `returnTo` (existing `buildLoginRedirect`). Fork CTA only when `allowFork` is true; FE generates a stable per-action `Idempotency-Key` (crypto UUID kept across retries of the same user action, never regenerated), disables double submits, handles **200/201/403/409/429/404** distinctly, and navigates to existing `/exams/[targetExamId]` on success (attribution credit shown from the fork response).

## 11. Testing strategy

Per issue, Testcontainers (`PostgresTestContainerConfiguration`, postgres:17) integration suites: migration matrices (fresh + V25→head; V26 asserts 15 unique categories on both legs), owner/auth/disabled/unverified/foreign/dual-role matrices per §7, version/concurrent publish/unpublish collisions, idempotent transitions (incl. publish-while-HIDDEN and hide/restore repeats), HTTP method/anonymous matrices, recursive JSON secret redaction, interleaved hidden/archived/inactive pagination counts, tag-count visibility (`/tags` excludes hidden/unpublished sources), injection/wildcard search, anti-enumeration direct-ID checks, report dedupe race + dedupe-after-hidden, moderation grant/deny/audit append-only + operator runbook execution, hide vs fork vs archive races (multi-threaded), fork content roundtrip of all four question types with exact answer comparison + `tf_matrix_scoring` copy, post-fork source immutability, injected-failure rollback, idempotency conflicts, bucket/rate behavior incl. fail-closed and forwarded-header rejection. FE: Vitest component/state tests + `tsc`/`eslint`/`build`; E2E per #49 against the real local stack.

## 12. Security & privacy invariants

1. No answer material (`answer_key`, `is_correct`, option content) or internal exam/question ids ever leaves through community APIs; the fork snapshot stays server-side.
2. No username/email/phone/roles/publisher-id/`display_name` in public DTOs; `authorName` is the explicitly acknowledged public alias only (§7).
3. 404 anti-enumeration for absent/inactive/hidden/unpublished/foreign resources on every public/owner route; tag usage counts respect the visibility predicate.
4. Owner checks are server-side `publisher_user_id == caller`; JWT claims are never trusted for roles/permissions (live effective-permission queries).
5. Fail-closed authorization and rate limiting; moderator power only via operator-provisioned grant through a dedicated operator account; no admin roles reintroduced.
6. Logs follow ADR-004 redaction: exception class + trace id, no request bodies, no snapshot/PII.

## 13. Consequences & risks

- Two new cross-module ports establish the first explicit contract seams; if later modules want them, promote to a shared port package — do not widen Community's.
- `community_categories` duplicate subject titles; acceptable for MVP (taxonomy drift is cosmetic; publications reference categories by id).
- no-store on public endpoints trades CDN efficiency for zero stale-risk; revisiting requires an invalidation design (out of MVP).
- HIDDEN-forever rows accumulate if moderators hide and owners never act; acceptable (audit + queue give operators visibility).
- LOW (documented, unfixed): `community_tags` growth is unbounded but rate-limited and never rendered without a visible publication; moderator surfaces expose internal user ids (audited privilege, §5); remote-addr-only rate keying pools NAT users behind shared thresholds (§9 invariant).

## 14. Alternatives considered

- **Redis limiter** — rejected: zero current app usage, new secret/availability/failure-mode surface; PostgreSQL buckets are proven (V23) and fail closed naturally.
- **Moderator as dedicated grant table** — rejected: `user_roles` already models revocable per-user grants with expiry + effective-permission integration; a parallel table would duplicate RBAC semantics (documented deviation from #39's literal wording, §5).
- **Community writing Exam/Question repositories directly** — rejected: issues mandate narrow ports; direct writes would couple lifecycle rules (exam publish checks, code uniqueness) into Community.
- **Fork from mutable `question_versions`** — rejected: violates immutability guarantees; the published composition snapshot is the authoritative reused artifact.
- **Reusing `duplicateExam`** — rejected: same-owner, keeps provenance ids, no idempotency, no bank clone.
- **`display_name` as default public author credit** — rejected on review (§1): not public anywhere today; replaced by the explicit publish-time alias acknowledgment.

## 15. Implementation allocation

| Issue | Deliverable | Migration |
|---|---|---|
| #40 | publications aggregate, taxonomy, owner lifecycle APIs, RBAC seeds, **rate buckets + limiter** | V26 |
| #42 | reports, moderator grant path, moderation APIs, audit, limiter wiring for report | V27 |
| #45 | bookmarks + unavailable placeholders | V28 |
| #47 | fork ports, fork service, idempotency | V29 |

#43/#44/#46/#48 consume the frozen §7/§10 contracts (mock-first allowed). No migration renumbering after creation; V1–V25 untouched. #40 endpoints are limiter-gated from their first commit (buckets ship in V26).

## 16. Review record

- **R1 (2026-11-08, this revision)**: three independent reviewers (architecture, security, DB) against the audited source and issues #39–#48. Verdicts: 3× GATE-FAIL, 0 BLOCKERs, 6 HIGHs (category seed unimplementable vs V24 reality; FK ON DELETE undecided; limiter allocation vs dependency order; `/tags` visibility leak; `display_name` fallback; §15 rate-gate gap) — all closed in Rev 2, plus 13 MEDIUMs (port field freezes incl. `tf_matrix_scoring`, error-code pins, idempotent-transition semantics, auth matrix, FE placement, operator account model, report ordering, NO_FORK 403, HMAC idempotency key, tag index, lock-order completeness, bookmark shapes, PATCHable set) — all closed; LOWs (ARCH-8…14, DB-8…11, SEC-8…10) closed or documented in §5/§9/§13. R1 evidence archived on issue #39.
