# ADR-007: Community social interaction — likes, ratings, comments (#64)

- Status: PROPOSED (P3-C0 architecture gate for Epic #63; no production code before acceptance)
- Date: 2026-11-09
- Deciders: autonomous engineering team; human approval required before merge
- Builds on ADR-005 (user-centric), ADR-006 (community publication/discovery/reuse). Extends the `community` bounded context; changes no P3-A/P3-B semantics.

## 1. Context (baseline audit, 2026-11-09)

- Backend main `187dc60`: P3-A/P3-B merged (V26–V29: publications/taxonomy, moderation+audit+rate buckets, bookmarks, fork ledger; `CommunityVisibilityStore` §4 predicate, `CommunityRateLimiter` with per-action `Policy` buckets, `CommunityAuthorizationService`, moderator grant via dormant `COMMUNITY_MODERATOR` role). Latest Flyway = **V30** (`class_membership_periods`, CLASS-M3); new migrations allocate from **V31**.
- Frontend main `92ac79c`: P3 FE (PR #18) + community shell/nav fixes (PR #22) merged. `/community/quizzes/[publicationId]` currently renders two large informational panels (Save, Fork) to be replaced by the toolbar; `ForkPublicationDialog`, `use-community-bookmarks`, `use-community-fork`, `classifyBookmarkError`/`classifyForkError`, `CommunityDialog` (labelled modal with focus trap) all reusable.
- Moderation audit table `community_moderation_actions` has an action CHECK (V27) listing publication-scope actions only — comment moderation requires widening it (forward-only, §3).
- `users` exposes `display_name` (self-service free text, no public surface — ADR-006 §1); there is no per-user public alias. Comment authors therefore need a community-owned alias (§4).

## 2. Decision

Extend the community module with three per-user interactions on **publicly visible publications only** — like (toggle), rating 1–5 (upsert/delete), comments (CRUD + reports + moderation) — plus aggregate exposure through a `social-summary` endpoint that returns counts and **only the caller's own** interaction state. All social endpoints enforce the ADR-006 §4 visibility predicate with the established 404 anti-enumeration; authorization, error envelope, admission-first rate limiting, pagination and audit conventions are reused unchanged. Comment authors are identified publicly **only** by an explicit, consent-based per-user community alias captured at first comment. Publication authors cannot rate their own publications.

## 3. Data model (forward-only after V30 → V31)

**V31 `community_social`** — all ids BIGINT IDENTITY; timestamps TIMESTAMPTZ; users/exams FKs RESTRICT per ADR-006 §3 policy.

- `community_member_profiles`: `user_id BIGINT PK` FK→users RESTRICT, `alias VARCHAR(80) NOT NULL` unique ci (`CREATE UNIQUE INDEX (LOWER(alias))`), `created_at`, `updated_at`. One per user; created on first comment (§4). Not PII — user-chosen public pseudonym, validated ≠ own email/username (ci, ADR-006 alias rule).
- `community_likes`: `user_id` + `publication_id` PK `(user_id, publication_id)` (one like per user per publication — the existence of the row IS the like), FKs RESTRICT, `created_at`; index `(publication_id)` for aggregate COUNT.
- `community_ratings`: `user_id` + `publication_id` PK, `rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5)`, `created_at`/`updated_at`, FKs RESTRICT; index `(publication_id)` for COUNT/AVG.
- `community_comments`: `id` PK, `publication_id` FK RESTRICT, `author_user_id` FK RESTRICT, `author_alias VARCHAR(80) NOT NULL` (snapshot at post time — audit-stable display), `body VARCHAR(2000) NOT NULL` (trimmed, non-blank, plain text; stored verbatim, rendered escaped), `state CHECK ('VISIBLE','HIDDEN','DELETED')` DEFAULT VISIBLE, `edited_at TIMESTAMPTZ` NULL (iff edited), `created_at`/`updated_at`; indexes `(publication_id, state, created_at DESC, id DESC)` for newest-first page + COUNT, `(author_user_id, created_at DESC)` for abuse review.
- `community_comment_reports`: `id`, `comment_id` FK RESTRICT, `reporter_user_id` FK RESTRICT, `reason_code` CHECK (same 5 values as publication reports), `details VARCHAR(500)`, `status CHECK ('OPEN','DISMISSED','RESOLVED')` DEFAULT OPEN, `handled_by` NULL FK users RESTRICT, `handled_at`, `resolution_note VARCHAR(500)`, `created_at`; partial unique `UNIQUE(reporter_user_id, comment_id) WHERE status='OPEN'`; indexes `(status, created_at)`, `(comment_id)`.
- `community_moderation_actions` gains `comment_id BIGINT NULL` FK→`community_comments` RESTRICT + index `(comment_id, created_at DESC)` (comment-scoped audit link; comment actions also carry `publication_id` contextually), and its action CHECK is widened (drop + re-add, identity-audit precedent) to add `COMMENT_HIDE`, `COMMENT_RESTORE`, `COMMENT_REPORT_DISMISS`, `COMMENT_REPORT_RESOLVE` (enum `CommunityModerationActionType` extended in lockstep).
- RBAC seeds: `COMMUNITY_LIKE`, `COMMUNITY_RATE`, `COMMUNITY_COMMENT` → STUDENT and TEACHER (idempotent `WHERE NOT EXISTS`); moderation of comments uses the existing `COMMUNITY_MODERATION` permission.

Deletion semantics (tombstones): owner delete = soft delete — row kept with `state='DELETED'`, `body` **cleared** (no content remains to leak), `edited_at` preserved; DELETED comments never appear in any public list. Moderator hide = `state='HIDDEN'`, body retained server-side for moderation review, absent from the public list, present in the moderator queue. Restore returns VISIBLE. A DELETED comment cannot be restored (no resurrecting removed content); reports against DELETED/HIDDEN comments resolve no-op.

Aggregates are computed on read (deterministic): `COUNT(*)` likes, `COUNT(rating)+ROUND(AVG(rating),2)` ratings — single indexed queries, no denormalized counters to drift, no N+1 (comments page is a single-table scan on `(publication_id, state, created_at DESC, id DESC)`; alias is denormalized on the row).

## 4. Alias consent lifecycle

- First comment: request must carry `alias` (1–80 chars, trimmed; rejected when equal ci to the author's email or username, mirroring ADR-006 §7 alias validation). The server creates `community_member_profiles` atomically (`ON CONFLICT (user_id) DO NOTHING` + re-select) and snapshots the alias onto the comment.
- Subsequent comments default to the stored alias (request may omit it); a different alias in a later request **updates** the profile for future comments (old comments keep their snapshot — display stability + audit).
- Likes and ratings store/need no alias (no public identity surface). The profile is never exposed as a directory; aliases appear only on comment rows.
- FE surfaces the consent copy once ("This name is publicly visible next to your comments") at first comment.

## 5. API contract & auth matrix

Envelope/`no-store`/404-anti-enumeration/401-AuthN conventions per ADR-006 §7 (identical matrix shape; unverified users cannot authenticate; disabled/LOCKED/PENDING → 401 AuthN). All `{id}` = publicationId unless noted. Social endpoints require the publication to pass the §4 predicate — ineligible → **404 identical to absent**. All mutations are admission-first.

| Route | Auth | Body → Result |
|---|---|---|
| `PUT /api/community/publications/{id}/like` | ACTIVE + `COMMUNITY_LIKE` | 201 created / 200 idempotent re-PUT; returns `{likeCount, liked:true}` |
| `DELETE /api/community/publications/{id}/like` | same | 200 always (absent → not-liked); `{likeCount, liked:false}` |
| `PUT /api/community/publications/{id}/rating` | ACTIVE + `COMMUNITY_RATE` | `{rating: 1..5}` → 201 first / 200 update; publisher of the publication → **409 `COMMUNITY_SELF_RATING_NOT_ALLOWED`**; returns `{average, ratingCount, myRating}` |
| `DELETE /api/community/publications/{id}/rating` | same | 200 always; `{average, ratingCount, myRating:null}` |
| `GET /api/community/publications/{id}/social-summary` | authenticated only | 200 `{likeCount, liked, average, ratingCount, myRating, commentCount}` — aggregates + caller's own states ONLY; no other user ids anywhere |
| `GET /api/public/community/publications/{id}/comments?page&size` | **anonymous allowed** (fifth public GET route — the `/api/community/**` prefix is authenticated, so guest-readable comments live under `/api/public/community/**`, exact-path GET permitAll) | paged `{items:[{commentId, authorAlias, body, createdAt, editedAt, state:'VISIBLE'}], page,size,totalElements,totalPages}` — VISIBLE only, `created_at DESC, id DESC`, size ≤ 50 default 20 |
| `POST /api/community/publications/{id}/comments` | ACTIVE + `COMMUNITY_COMMENT` | `{body, alias?}` → 201 comment DTO (alias consent §4) |
| `PATCH /api/community/comments/{commentId}` | owner only | `{body}` → 200 updated (sets `edited_at`); foreign → 404; non-VISIBLE → 409 |
| `DELETE /api/community/comments/{commentId}` | owner only | 200; tombstone §3; foreign → 404 |
| `POST /api/community/comments/{commentId}/reports` | ACTIVE + `COMMUNITY_REPORT` | `{reasonCode, details?}` → 201 / 200 dedupe (same fixed order as publication reports: admission → VISIBLE check 404 → ON CONFLICT dedupe) |
| `GET /api/community/moderation/comment-reports?status&page&size` | moderator | queue mirroring publication reports (comment snippet + publication card summary) |
| `POST /api/community/moderation/comments/{commentId}/hide|restore` | moderator | `{reason}` mandatory; audit rows `COMMENT_HIDE`/`COMMENT_RESTORE`; idempotent no-ops without duplicate audit; restore only HIDDEN→VISIBLE |
| `POST /api/community/moderation/comment-reports/{id}/dismiss|resolve` | moderator | `{note?}`; audit `COMMENT_REPORT_DISMISS`/`COMMENT_REPORT_RESOLVE`; 409 when not OPEN |
| `GET /api/community/moderation/comments/{commentId}/history` | moderator | audit rows newest-first |

Error codes added: `COMMUNITY_SELF_RATING_NOT_ALLOWED` (409), `COMMUNITY_COMMENT_INVALID` (400 bounds/state), `COMMUNITY_ALIAS_INVALID` (400, reuse), `COMMUNITY_COMMENT_NOT_FOUND` (404), `COMMUNITY_NOT_FOUND` reuse. Rate policies (`quizopia.community.rate-limit.*`): like mutate 60/h/user, rating mutate 30/h/user, comment create 10/h/user, comment edit 20/h/user, comment report 5/h/user + 20/h/IP (reuse report posture). Comment list reads join the anonymous public-read policy (6000/h/IP unauthenticated; user bucket authenticated). Limiter wiring is the existing four-place lockstep: `Policy` enum + exhaustive `maxFor()` switch + `CommunityRateLimitProperties` fields + main/test properties.

Aggregate/response invalidation: `no-store` everywhere (consistent with ADR-006 §7 — no cache to invalidate); FE invalidates its `["community"]` query family after mutations.

## 6. Concurrency & integrity

- Like PUT: `INSERT … ON CONFLICT (user_id, publication_id) DO NOTHING` + re-select → idempotent under races (roster/bookmark precedent), `created` flag from rowcount.
- Rating PUT: `INSERT … ON CONFLICT DO UPDATE SET rating=?, updated_at=now()` — atomic upsert; aggregate computed after upsert in the same response (eventual consistency window between concurrent upserts is acceptable and self-correcting: aggregate is computed on read).
- Self-rating check: compare `publication.publisher_user_id == caller` before upsert (publisher id is immutable; no TOCTOU).
- Comment create: visibility check → alias profile upsert → insert; concurrency safe by construction.
- Comment PATCH/DELETE: load by id + `author_user_id == caller` else 404; optimistic `@Version`-free (comment rows have no version; edits are last-writer-wins by owner only — acceptable MVP, no cross-user conflict surface).

## 7. Frontend contract (P3-C3/C4)

- Detail page replaces the two large Save/Fork panels with one compact, left-aligned, wrap-friendly toolbar: **Like → Comment → Rate → Fork → Save** (icon + label, ≥44px targets, active states for liked/rated/saved, per-action loading disable, like count beside Like). Aggregate `average (2dp) + ratingCount` moves into the quiz metadata area. Fork button keeps `allowFork` gating + `ForkPublicationDialog`; Save keeps existing bookmark semantics.
- Guests: toolbar actions render but redirect to `/login` with `returnTo` (existing sanitized helper); comments remain readable; Like/Rate/Comment/Save CTAs all go through login continuation.
- Community Discussion section below the toolbar: paginated newest-first list (alias, timestamp, plain-text body, edited marker), own-comment Edit/Delete, Report for others, empty/loading/error states, editor with submit/cancel + live char count (≤2000), first-comment alias capture with consent copy. No `dangerouslySetInnerHTML`; React text escaping only.
- Rating UX: accessible 1–5 star selector (radiogroup pattern, keyboard operable) as a popover/dialog; shows average, count, own rating; update supported; removes via clear action; self-rating blocked state for the publisher (server 409 surfaced with copy).
- New FE API module + hooks extend the `["community"]` query family; moderation console gains a comment-reports tab behind the same grant gate. No star-rating/popover/toolbar primitives exist in `components/ui/` — the star radiogroup and toolbar are built from Button/Badge/Card/Input + DESIGN.md tokens (audit 2026-11-09); the detail page runs under the PR #22 `CommunityAdaptiveShell` (authenticated → AppShell, guest → PublicHeader), which stays untouched.

## 8. Testing strategy

Backend (Testcontainers PG17, per P3-A conventions): V31 matrix (fresh + V30-staged; CHECKs incl. widened moderation action CHECK + comment_id FK; alias unique-ci; partial unique comment report); head-pin bumps 30→31 across all pinning suites (V8, V9, V9-staged, V17-staged, V24, V26, V27, V28, V29, V30 matrices, UserEncryptedPersonalData `endsWith`); like/rating duplicate + concurrent (8-thread) races; rating upsert/update/delete + aggregate determinism; self-rating 409; auth matrices (guest/student/teacher/publisher/foreign/disabled); comment ownership edit/delete vs foreign 404; tombstone non-leakage (DELETED body cleared + absent from list; HIDDEN absent publicly, present in queue); comment report dedupe race; moderation hide/restore idempotency + audit rows; rate limits incl. comment 10/h; anonymous comments route method-matrix + anti-enumeration; recursive JSON redaction (no PII/other-user ids in summary/comments); regression: full community + fork/bookmark suites green.

Frontend: Vitest component tests (toolbar states × auth states, rating popover a11y, comment flows incl. alias consent, report, foreign-action absence, leak checks with poisoned fixtures), tsc/eslint/build; E2E at P3-C5 per the gate list (guest browse, login continuation, like/rate/comment, own edit/delete, foreign denied, moderator hide, ineligible publications, fork+bookmark regression, responsive 375/768/1024/1440/1920).

## 9. Consequences & risks

- Comment alias is a new public identity surface — deliberately minimal (pseudonym only, no profile pages, no directory enumeration endpoint).
- On-read aggregates cost two indexed COUNT/AVG per summary fetch — fine at MVP scale; denormalized counters can be added later behind the same contract if needed.
- `author_alias` snapshots make alias changes non-retroactive — display stability chosen over global rename.
- Author self-rating blocked (frozen policy: manipulation reduction; publisher can still like own quiz — harmless to rating integrity; recorded here per §4 of the execution request).
- LOW (accepted): last-writer-wins own-comment edits (no version token); HIDDEN comment bodies retained server-side for moderation (privileged-only access, audit-logged).

## 10. Alternatives considered

- **Denormalized like/rating counters** — rejected: drift risk; on-read aggregates are deterministic and cheap with the frozen indexes.
- **Reuse `community_reports` for comments** — rejected: publication_id NOT NULL shape; a comment scope needs its own table + queue (parallel to publication reports).
- **Alias on `users` (identity module)** — rejected: identity schema must stay PII-minimal; community owns its public-pseudonym surface.
- **Websocket live comments** — rejected for MVP (explicitly out of scope).
- **Hard comment delete** — rejected: audit/abuse review needs tombstones; body cleared so no content leaks.

## 11. Implementation allocation

| Issue | Deliverable | Migration |
|---|---|---|
| #65 (P3-C1) | likes, ratings, social-summary, RBAC seeds, policies | V31 |
| #66 (P3-C2) | comments CRUD, alias profile, comment reports, comment moderation + CHECK widen + audit | V31 (single migration, both issues build on it; C1 lands it) |
| #67 (P3-C3) | FE toolbar | — |
| #68 (P3-C4) | FE discussion + rating UX | — |
| #69 (P3-C5) | gate: full suites, E2E, responsive, report | — |

No renumbering after creation; V1–V30 untouched.
