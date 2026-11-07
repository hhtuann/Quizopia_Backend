# ADR-006: Community publication, discovery & reuse (P3 MVP)

- Status: PROPOSED (architecture gate for Epic #38 P3-A/P3-B; no production code before acceptance)
- Date: 2026-11-08
- Deciders: autonomous engineering team; human approval required before merge
- Supersedes: the deleted local Community draft that targeted Workspace/Org (never merged). Builds on ADR-005 (user-centric model) and ADR-004 (identity M5 conventions).

## 1. Context (source audit, 2026-11-08)

Audited at Backend `d671a60`, Frontend `10c90be` (System `c4738c2` gitlink baseline), branch `feature/p3-community`.

- Ownership is user-centric since V24/V25: `exams.created_by_user_id`, `question_banks.created_by_user_id`, owner-check authorization (load → 404 anti-enumeration / 403), no workspace/org columns, roles are exactly STUDENT and TEACHER (dual-role supported). SYSTEM_ADMIN/ACADEMIC_ADMIN are gone and MUST NOT return.
- RBAC is DB-backed (`roles`, `permissions`, `user_roles` with `expires_at`, `role_permissions`); checks are service-layer `requirePermission` style via `RolePermissionRepository.findEffectivePermissionCodesByUserId`; no method-security annotations. JWT subject = user id, live-DB revalidation each request (status ACTIVE + token_version).
- Exam lifecycle is DRAFT → PUBLISHED → ARCHIVED; **no exam unpublish exists** (archive only, blocked by live sessions). `Exam.version` is the JPA optimistic token; `ExamRepository.findByIdForUpdate` is the pessimistic-write lock pattern.
- `exam_sections` / `exam_questions` / `exam_question_options` ARE the immutable published composition snapshot (question content, `answer_key`, option `is_correct`, `source_question_id` + `source_question_version_id` provenance). Question banks/questions/versions/options are owned by users; `ExamService.publishExam` already enforces bank-owner == exam-owner.
- `ExamService.duplicateExam` is same-owner and copies provenance ids verbatim — not sufficient for cross-user fork.
- Cross-module reuse today is direct repository injection; there are no port interfaces and no ArchUnit enforcement. This ADR introduces narrow ports for Community only; it does not refactor existing modules.
- Abuse control precedent: V23 `password_reset_abuse_buckets` — PostgreSQL HMAC-keyed fixed-window buckets, servlet remote address only (never forwarded headers). Redis is deployed in compose but used by **no application code**; it stays unused by Community.
- Audit precedent: `identity_audit_events` — append-only by repository surface (no update/delete API), widened by forward-only CHECK extension.
- Error envelope: per-module `*ErrorCode` enum + module exception → `GlobalExceptionHandler` → shared `ApiError(timestamp,status,code,message,path,traceId)`. Pagination: `PageResponse` records, 0-based page/size, `MAX_PAGE_SIZE=100`, sort allowlist, `id DESC` tiebreaker.

## 2. Decision

A `community` bounded context (`com.quizopia.backend.community`, same api/application/domain/repository/dto/exception layering) owning publication, taxonomy, reports, moderation audit, bookmarks and fork records. Community never writes Exam/Question tables: it orchestrates through three narrow ports implemented in the exam/question application layers. Public discovery is a read-only SQL projection with eligibility filtering before count/pagination. Moderation power is an explicit, operator-provisioned, revocable per-user grant — no global admin role. Reuse defaults to NO_FORK; fork clones the **published exam composition snapshot** into a fully independent bank/question/exam set owned by the requesting teacher, atomically and idempotently.

## 3. Data model (forward-only after V25)

All ids BIGINT IDENTITY, FKs to `users`/`exams` only — **no workspace/org FK anywhere**. Timestamps TIMESTAMPTZ. All state CHECK-constrained.

**V26 `community_foundation`**

- `community_categories`: `id`, `code VARCHAR(40)` unique ci, `title VARCHAR(100)` NOT NULL, `position INT NOT NULL DEFAULT 0`, `created_at`. Seeded once at migration time from the V24 global `subjects` catalog (`INSERT ... SELECT`), so the starter taxonomy matches the platform (TOAN…MT) on both fresh and upgrade legs. Categories are migration-managed (like `exam_purposes`); no CRUD API.
- `community_publications`:
  - `id` PK; `exam_id BIGINT NOT NULL UNIQUE` FK→`exams` (one publication per exam, immutable); `publisher_user_id BIGINT NOT NULL` FK→`users` (immutable, equals `exams.created_by_user_id` at every state change — invariant rechecked, never updated).
  - `status` CHECK `('UNPUBLISHED','PUBLISHED')`; `moderation_state` CHECK `('VISIBLE','HIDDEN')`; `reuse_policy` CHECK `('NO_FORK','ALLOW_FORK')` DEFAULT `NO_FORK`.
  - `title VARCHAR(255) NOT NULL`; `summary VARCHAR(500)`; `author_alias VARCHAR(80)` NULL (opt-in; see §7); `category_id` FK→`community_categories` NULL; `question_count INT NOT NULL DEFAULT 0`; `section_count INT NOT NULL DEFAULT 0`; `duration_minutes INT NOT NULL DEFAULT 0`; `published_at TIMESTAMPTZ` NULL.
  - `version INT NOT NULL DEFAULT 0` (JPA `@Version`, the owner optimistic token); `created_at`/`updated_at`.
  - Indexes: `(status, moderation_state, published_at DESC, id DESC)` for the catalog; `(publisher_user_id, updated_at DESC)` for owner lists.
- `community_tags`: `id`, `label VARCHAR(40)` unique ci; created on demand from publish payloads (lower-cased, trimmed, `[a-z0-9][a-z0-9 -]{0,39}`, max 5 distinct per publication).
- `community_publication_tags`: PK `(publication_id, tag_id)`, FKs CASCADE.
- Permission seeds: `COMMUNITY_PUBLISH`, `COMMUNITY_FORK` → TEACHER; `COMMUNITY_REPORT`, `COMMUNITY_BOOKMARK` → STUDENT and TEACHER. Dormant role `COMMUNITY_MODERATOR` (granted to nobody by the migration) with permission `COMMUNITY_MODERATION`. Idempotent `INSERT ... WHERE NOT EXISTS`; roles/permissions remain the only RBAC path (no new grant table).

**V27 `community_moderation`**

- `community_reports`: `id`, `publication_id` FK CASCADE, `reporter_user_id` FK→users, `reason_code` CHECK `('INAPPROPRIATE_CONTENT','COPYRIGHT','MISLEADING','SPAM','OTHER')`, `details VARCHAR(500)`, `status` CHECK `('OPEN','DISMISSED','RESOLVED')` DEFAULT OPEN, `handled_by` NULL FK→users, `handled_at`, `resolution_note VARCHAR(500)`, `created_at`; partial unique index `UNIQUE(reporter_user_id, publication_id) WHERE status='OPEN'` (one OPEN report per reporter per publication); indexes `(status, created_at)`, `(publication_id)`.
- `community_moderation_actions` (append-only audit): `id`, `actor_user_id` FK→users, `publication_id` NULL FK, `report_id` NULL FK, `action` CHECK `('HIDE','RESTORE','REPORT_DISMISS','REPORT_RESOLVE','MODERATOR_GRANTED','MODERATOR_REVOKED')`, `from_status`/`to_status`, `from_moderation_state`/`to_moderation_state`, `reason VARCHAR(500) NOT NULL`, `correlation_id VARCHAR(64)`, `created_at`. Repository exposes `save` + reads only (identity_audit_events convention). Operator grant/revoke also writes rows here (§5).
- `community_rate_buckets`: generalized V23 shape — `bucket_key VARCHAR(64)` PK CHECK 64-hex (domain-separated HMAC-SHA-256 of `community:<dimension>:<value>`), `window_start TIMESTAMPTZ`, `attempts INT CHECK 0..2147483647`, `last_admitted_at` NULL, `expires_at`; `CHECK expires_at > window_start`; expiry index; retention sweep 2h.

**V28 `community_bookmarks`**: `id`, `user_id` FK CASCADE, `publication_id` FK CASCADE, `created_at`, `UNIQUE(user_id, publication_id)`, index `(user_id, created_at DESC)`.

**V29 `community_fork_records`**: `id`, `actor_user_id` FK→users, `idempotency_key_hash VARCHAR(64)` CHECK 64-hex (SHA-256 of raw Idempotency-Key), `source_publication_id` FK, `source_exam_id` FK, `target_exam_id BIGINT NOT NULL UNIQUE` FK→exams, `source_title_snapshot VARCHAR(255)`, `source_alias_snapshot VARCHAR(80)`, `created_at`; `UNIQUE(actor_user_id, idempotency_key_hash)`.

Migration-matrix tests (fresh V1→head and staged V25→head with legacy data) accompany every migration, following `V24UserCentricMigrationMatrixTest`.

## 4. Publication lifecycle & invariants

- **Create**: `POST /api/community/publications {examId, title, summary, categoryId, tags[], authorAlias?, reusePolicy}`. Eligibility: caller ACTIVE + `email_verified_at IS NOT NULL` + active TEACHER role + effective `COMMUNITY_PUBLISH`; exam exists; `exam.created_by_user_id == caller`; `exam.status == PUBLISHED`. Created row: UNPUBLISHED / VISIBLE / NO_FORK default (`reusePolicy` may set ALLOW_FORK at create; FE shows the consent warning). Never mutates the exam. `question_count`/`section_count`/`duration_minutes` denormalized from the exam composition at create and re-derived on publish.
- **Update**: `PATCH /api/community/publications/{id} {…fields, expectedVersion}` — owner only; any status; `version` mismatch → `COMMUNITY_CONCURRENT_MODIFICATION` (409). `examId`/`publisher` immutable.
- **Publish**: UNPUBLISHED→PUBLISHED under publication row lock with eligibility recheck (exam still PUBLISHED, publisher still ACTIVE+verified+TEACHER+`COMMUNITY_PUBLISH`). Already PUBLISHED → 200 idempotent no-op. **HIDDEN → 409 `COMMUNITY_MODERATION_HIDDEN`** (author cannot bypass moderator hide; hide survives unpublish/republish).
- **Unpublish**: PUBLISHED→UNPUBLISHED, publication row lock only (never locks the exam). Already UNPUBLISHED → 200 idempotent no-op. Does not touch exam/session state.
- **Moderator hide**: VISIBLE→HIDDEN (publication row lock only, mandatory reason, audit row). **Restore**: HIDDEN→VISIBLE; does NOT change `status` — if the owner unpublished meanwhile, it stays UNPUBLISHED and owner may republish (unless hidden again).
- Public visibility of a publication requires ALL of: `status='PUBLISHED'` AND `moderation_state='VISIBLE'` AND `exams.status='PUBLISHED'` AND publisher user ACTIVE AND `publisher_user_id == exams.created_by_user_id`. Any failure → 404 anti-enumeration on detail; filtered out before count/pagination in lists. This predicate is shared by catalog, detail, bookmark listing eligibility and fork eligibility.

## 5. Moderator model & operator procedure

- Moderator = any ACTIVE user holding the dormant `COMMUNITY_MODERATOR` role (checked through the existing effective-permission path: `COMMUNITY_MODERATION`). TEACHER/STUDENT get nothing automatically; dual-role moderators are fine. Revocation = role row delete or `expires_at` (existing `user_roles` semantics).
- **No self-service grant endpoint exists or may be added.** Provisioning is an operator runbook (`docs/runbooks/community-moderator-provisioning.md`, delivered with #42): a SQL script that inserts/deactivates the `user_roles` row and inserts the `MODERATOR_GRANTED`/`MODERATOR_REVOKED` audit row with the operator as actor, executed by a credentialed DB operator. The runbook documents verification queries and is the launch-blocking operator step recorded in #49.
- Moderator API (`/api/community/moderation/**`, authenticated, effective `COMMUNITY_MODERATION`, moderator must be ACTIVE): report queue (paged, status filter), hide/restore with mandatory reason, report dismiss/resolve, per-publication history (audit rows, actor ids and timestamps only — no PII).

## 6. Module boundaries & ports

Community may not inject Exam/Question repositories or mutate their aggregates. It consumes:

- `ExamCommunityPort` (exam module, implemented by exam application):
  - `PublicationEligibility checkPublicationEligibility(Long examId, Long userId)` — read-only owner/status projection;
  - `PublishedCompositionSnapshot loadPublishedSnapshot(Long examId)` — sections/questions/options with `answer_key`, `is_correct`, `metadata`, `source_question_id`, `source_question_version_id` (server-only record; never serialized to any API, never logged);
  - `Long createForkDraft(ForkDraftSpec spec)` — fresh DRAFT exam (new `EX` code, `source_exam_id` = source exam) with sections/questions/options whose `source_question_id`/`source_question_version_id` are rewritten to the cloned question/version ids.
- `QuestionCommunityPort` (question module): `QuestionCloneResult cloneForFork(PublishedCompositionSnapshot snapshot, Long ownerUserId)` — one fresh bank (new `QB` code) + fresh questions (`QU` codes) + version 1 + options copied from the snapshot, `created_by_user_id = ownerUserId`, question status ACTIVE, type/content/difficulty/points/answer data preserved. Returns `sourceQuestionId → (newQuestionId, newVersionId)` mapping.
- Identity access follows the existing pattern: a community `CommunityAuthorizationService` reading `UserRepository`/`UserRoleRepository`/`RolePermissionRepository` (the same dependency direction every module already uses for auth).
- The public catalog is a read-only `JdbcTemplate` SQL projection joining `community_publications` × `community_categories` × `exams` × `users` with the §4 visibility predicate in the WHERE clause before COUNT and LIMIT/OFFSET. Deterministic order `published_at DESC, id DESC`.

## 7. API contract & DTO allowlists

All responses use the existing `ApiError` envelope, 404 for absent/invisible/foreign resources, 403 only for "authenticated but not allowed on a visible resource" (owner surfaces), `Cache-Control: no-store` on every community endpoint (public and authenticated) until a proven invalidation story exists — stale-cache acceptance in #41/#45 is satisfied by no-store.

**Anonymous (exact-path permitAll additions, GET only):**

| Route | Response |
|---|---|
| `GET /api/public/community/publications?q&categoryId&tags&page&size` | `PublicPublicationPage` |
| `GET /api/public/community/publications/{id}` | `PublicPublicationDetail` or 404 |
| `GET /api/public/community/categories` | `[{code,title}]` |
| `GET /api/public/community/tags` | top 100 used `[{label,usageCount}]` |

`PublicPublicationCard` (exact allowlist): `publicationId, title, summary, authorName, categoryCode, categoryTitle, tags[], questionCount, sectionCount, durationMinutes, publishedAt, allowFork`. Detail = card + `updatedAt`. **Never** present in any public DTO: answers, `is_correct`, `answer_key`, question/option content or ids, `exam_id`/`sourceExamId`, internal section ids, username, email, phone, roles, publisher user id, session/attempt/result/roster data. `authorName` = `author_alias` when provided, else the publisher's `display_name` (the platform's designed-public nickname; username/email never). Search `q` is parametrized bounded ILIKE on title/summary (escaped `%_`, length ≤ 100); `tags` uses AND semantics (all listed tags required), `size` capped at 50, `page` ≥ 0. JSON redaction tests serialize real DTOs recursively and assert none of the forbidden keys/answers appear.

**Authenticated owner (TEACHER + COMMUNITY_PUBLISH, ACTIVE, verified):** `GET /api/community/me/publications`; create/PATCH/publish/unpublish per §4. Owner DTO adds `examId, status, moderationState, reusePolicy, version, reportCount, createdAt, updatedAt, publishedAt`.

**Reports (any ACTIVE user + COMMUNITY_REPORT):** `POST /api/community/publications/{id}/reports {reasonCode, details?}` on a publicly visible publication → 201; duplicate OPEN report by same reporter → idempotent 200 returning the existing report; rate-limited §9.

**Bookmarks (any ACTIVE user + COMMUNITY_BOOKMARK):** `PUT`/`DELETE /api/community/publications/{id}/bookmark` (idempotent under concurrency via `UNIQUE(user_id, publication_id)` upsert/delete; bookmarking requires only the §4 visibility predicate — bookmarks never grant read rights beyond public metadata); `GET /api/community/me/bookmarks` paged — entries failing the predicate render as `UNAVAILABLE`: `{publicationId, savedAt, unavailable:true}` only (no title/alias/category/tags or any last-known content).

**Fork (TEACHER + COMMUNITY_FORK + EXAM_CREATE, ACTIVE):** `POST /api/community/publications/{id}/fork` with required `Idempotency-Key` header (8–128 chars, hashed). 201 first creation `{targetExamId, sourcePublicationId, forkedAt}`; same key + same publication re-request → 200 identical target; same key + different publication → 409 `COMMUNITY_IDEMPOTENCY_CONFLICT`.

**Moderation:** `GET /api/community/moderation/grant` (200 `{granted:true}` / 403 — FE console gate); `GET /api/community/moderation/reports?status&page&size`; `POST /api/community/moderation/publications/{id}/hide|restore {reason}`; `POST /api/community/moderation/reports/{id}/dismiss|resolve {note?}`; `GET /api/community/moderation/publications/{id}/history`.

## 8. Fork algorithm (lock order, atomicity, idempotency)

1. AuthN → ACTIVE + TEACHER + `COMMUNITY_FORK` + `EXAM_CREATE`. Require `Idempotency-Key`; compute `key_hash`.
2. Pre-check `community_fork_records` for `(actor, key_hash)`: hit + same publication → return original (200, read-only path, no locks); hit + different publication → 409.
3. Open one transaction. **Lock the source `exams` row FIRST** (`findByIdForUpdate`), **then the `community_publications` row** (`SELECT … FOR UPDATE`). Recheck under lock: §4 predicate + `reuse_policy='ALLOW_FORK'`. Because community publish/unpublish and moderator hide/restore take only the publication lock, and exam archive takes only the exam lock, no path acquires exam-after-publication → no deadlock; archive-vs-fork and hide-vs-fork serialize correctly.
4. Load `PublishedCompositionSnapshot` (from the locked exam composition rows — the immutable published state, never the mutable `question_versions`).
5. `QuestionCommunityPort.cloneForFork` → new bank/questions/versions/options owned by requester (four types: SINGLE_CHOICE, MULTIPLE_CHOICE, TRUE_FALSE_MATRIX, NUMERIC_FILL; options with `is_correct`, `answer_key` for NUMERIC_FILL).
6. `ExamCommunityPort.createForkDraft` → new DRAFT exam + composition with remapped `source_question_id`/`source_question_version_id`, `source_exam_id` = source exam, owner = requester. New owner's existing edit/publish APIs work unchanged (bank owner == exam owner holds by construction).
7. Insert `community_fork_records`. Commit. 201.
8. Concurrent same-key losers hit `UNIQUE(actor_user_id, idempotency_key_hash)`; the constraint violation is caught **outside** the poisoned transaction and resolved by a fresh read-only lookup of the winner's record (same publication → 200 winner target; else 409). No sessions, attempts, results, grades or rosters are touched; source rows are never written; injected mid-failure leaves no orphan rows (single transaction).
9. Target may be published through the standard exam publish flow (its snapshot answers come from the cloned bank versions).

## 9. Rate limiting & abuse

`CommunityRateLimiter` — a reusable, PostgreSQL-backed fixed-window admission component generalizing V23 (`community_rate_buckets`), keyed by domain-separated HMAC of `user:<id>` or `ip:<servlet-remote-addr>`; forwarded headers are never trusted (consistent spoofed-IP posture with V23). Admission runs inside the request transaction; **fail closed**: if the bucket store is unavailable the request errors rather than bypassing. Redis is explicitly not adopted (unused dependency; no new failure modes; documented as the rejected alternative). Frozen thresholds (tunable via `quizopia.community.rate-limit.*`): publish/unpublish 10/h/user; create/update 30/h/user (taxonomy abuse); report 5/h/user + 20/h/IP; bookmark mutate 60/h/user; fork 5/h/user; anonymous catalog/detail/categories/tags 600/h/IP. 429 body carries code `COMMUNITY_RATE_LIMITED` and `Retry-After`; no PII in bucket keys or logs (HMAC only).

## 10. Frontend contract

- Public (outside `RequireAuth`, anonymous deep links work): `/community` (cards, q/category/tag filters, URL-bound state, pagination, loading/empty/error/404) and `/community/quizzes/[publicationId]`. Safe render: React text escaping only, no `dangerouslySetInnerHTML`, no markdown rendering of community content, no answer/option/PII in DOM or metadata/SEO; `no-store` respected.
- Protected: `/community/mine` (TEACHER; publish/manage, version-conflict and hidden-state actionable errors, ALLOW_FORK consent warning), `/community/bookmarks` (STUDENT/TEACHER; unavailable placeholders + safe remove), `/community/moderation` (gated by `GET …/grant`; queue, hide/restore with mandatory reason, history).
- "Publish to Community" entry lives in the existing teacher exam detail overview actions (`?tab=overview`), PUBLISHED exams only. Bookmark CTA on public detail asks guests to log in preserving `returnTo` (existing `buildLoginRedirect`). Fork CTA only when `allowFork` is true; FE generates a stable per-action `Idempotency-Key` (crypto UUID kept across retries of the same user action, never regenerated on retry), disables double submits, handles 200/201/409/429/404 distinctly, and navigates to existing `/exams/[targetExamId]` on success. Nav gains a Community entry for all views (anonymous header included) via `lib/navigation.tsx`.

## 11. Testing strategy

Per issue, Testcontainers (`PostgresTestContainerConfiguration`, postgres:17) integration suites: migration matrices (fresh + V25→head), owner/auth/disabled/unverified/foreign/dual-role matrices, version/concurrent publish/unpublish collisions, idempotent transitions, HTTP method/anonymous matrices, recursive JSON secret redaction, interleaved hidden/archived/inactive pagination counts, injection/wildcard search, anti-enumeration direct-ID checks, report dedupe race, moderation grant/deny/audit append-only, hide vs fork vs archive races (multi-threaded), fork content roundtrip of all four question types with exact answer comparison, post-fork source immutability, injected-failure rollback, idempotency conflicts, bucket/rate behavior. FE: Vitest component/state tests + `tsc`/`eslint`/`build`; E2E per #49 against the real local stack.

## 12. Security & privacy invariants

1. No answer material (`answer_key`, `is_correct`, option content) or internal exam/question ids ever leave through community APIs; the fork snapshot stays server-side.
2. No username/email/phone/roles/publisher-id in public DTOs; `authorName` only as §7.
3. 404 anti-enumeration for absent/inactive/hidden/unpublished/foreign resources on every public/owner route.
4. Owner checks are server-side `publisher_user_id == caller`; JWT claims are never trusted for roles/permissions (live effective-permission queries).
5. Fail-closed authorization and rate limiting; moderator power only via operator-provisioned grant; no admin roles reintroduced.
6. Logs follow ADR-004 redaction: exception class + trace id, no request bodies, no snapshot/PII.

## 13. Consequences & risks

- Two new cross-module ports establish the first explicit contract seams; if later modules want them, promote to a shared port package — do not widen Community's.
- `community_categories` duplicates subject titles; acceptable for MVP (taxonomy drift is cosmetic; publications reference categories by id).
- no-store on public endpoints trades CDN efficiency for zero stale-risk; revisiting requires an invalidation design (out of MVP).
- HIDDEN-forever rows accumulate if moderators hide and owners never act; acceptable (audit + queue give operators visibility).
- LOW (documented, unfixed): `display_name` fallback may differ from alias expectations for privacy-sensitive teachers (they can always set an alias); `community_tags` growth is unbounded but rate-limited.

## 14. Alternatives considered

- **Redis limiter** — rejected: zero current app usage, new secret/availability/failure-mode surface; PostgreSQL buckets are proven (V23) and fail closed naturally.
- **Moderator as dedicated grant table** — rejected: `user_roles` already models revocable per-user grants with expiry + effective-permission integration; a parallel table would duplicate RBAC semantics.
- **Community writing Exam/Question repositories directly** — rejected: issues mandate narrow ports; direct writes would couple lifecycle rules (exam publish checks, code uniqueness) into Community.
- **Fork from mutable `question_versions`** — rejected: violates immutability guarantees; the published composition snapshot is the authoritative reused artifact.
- **Reusing `duplicateExam`** — rejected: same-owner, keeps provenance ids, no idempotency, no bank clone.

## 15. Implementation allocation

| Issue | Deliverable | Migration |
|---|---|---|
| #40 | publications aggregate, taxonomy, owner lifecycle APIs, RBAC seeds | V26 |
| #42 | reports, moderator grant path, moderation APIs, audit, rate limiter | V27 |
| #45 | bookmarks + unavailable placeholders | V28 |
| #47 | fork ports, fork service, idempotency | V29 |

#43/#44/#46/#48 consume the frozen §7/§10 contracts (mock-first allowed). No migration renumbering after creation; V1–V25 untouched.
