# ADR-007: Community social interaction — likes, ratings, comments (#64)

- Status: PROPOSED (P3-C0 architecture gate for Epic #63; no production code before acceptance) — **Rev 4** (owner-approved comment display-name policy change, #72, supersedes the §4 alias consent lifecycle for comments — see §13; R1 review findings closed in Rev 2, 3× GATE-PASS in Rev 3, cosmetic fixes in Rev 3; see §12)
- Date: 2026-11-09
- Deciders: autonomous engineering team; human approval required before merge
- Builds on ADR-005 (user-centric), ADR-006 (community publication/discovery/reuse). Extends the `community` bounded context; changes no P3-A/P3-B behavior (one narrow, security-justified addition: publication alias validation gains a cross-namespace check, §4).

## 1. Context (baseline audit, 2026-11-09)

- Backend main `187dc60`: P3-A/P3-B merged (V26–V29; `CommunityVisibilityStore` §4 predicate, `CommunityRateLimiter` with 8 per-action `Policy` buckets wired in a four-place lockstep (enum + exhaustive `maxFor()` + `CommunityRateLimitProperties` + main/test properties), `CommunityAuthorizationService`, moderator grant via dormant `COMMUNITY_MODERATOR` role, append-only `community_moderation_actions`). Latest Flyway = **V30** (CLASS-M3 `class_membership_periods`); new migrations allocate from **V31**. Head-pins at `"30"` exist in exactly 11 suites (V8, V9, V9-staged, V17-staged, V24, V26, V27, V28, V29, V30 matrices, `UserEncryptedPersonalData`).
- Security chain: `/api/community/**` authenticated; four exact-path GET `permitAll` under `/api/public/community/**`; non-GET → deny-by-default.
- Frontend main `92ac79c`: P3 FE + PR #22 merged. Detail page has two large Save/Fork panels (to be replaced), `ForkPublicationDialog`, `use-community-bookmarks`/`use-community-fork`, `classifyBookmarkError`/`classifyForkError`, `CommunityDialog`; pages run under PR #22's `CommunityAdaptiveShell`. No star-rating/popover/toolbar primitives exist in `components/ui/`.
- `community_moderation_actions` (V27): action CHECK = 6 publication-scope values; subject links `publication_id` + `report_id` only; state CHECKs admit VISIBLE/HIDDEN.
- `users` has no public-alias column (`display_name` is non-public self-service text — ADR-006 §1). Comment authors therefore need a community-owned pseudonym (§4).

## 2. Decision

Extend the community module with three per-user interactions on **publicly visible publications only** — like (toggle), rating 1–5 (upsert/delete), comments (CRUD + reports + moderation) — plus aggregate exposure through a **public** social-summary (counts only; anonymous-readable) and an **authenticated** twin that adds the caller's own interaction state. All publication-scoped endpoints enforce the ADR-006 §4 visibility predicate with 404 anti-enumeration; comment-scoped owner routes check existence + ownership only (§5). Authorization, error envelope, admission-first rate limiting (complete policy table §5), pagination and audit conventions are reused. Comment authors are identified publicly **only** by an explicit, consent-based, collision-checked community alias captured at first comment. Publication authors cannot rate their own publications.

## 3. Data model (forward-only after V30 → V31)

All ids BIGINT; timestamps TIMESTAMPTZ; users FKs RESTRICT (ADR-006 §3 policy). **Publication-side child FKs CASCADE** — likes, ratings, comments → `community_publications`; comment_reports → `community_comments` — matching the ADR-006 §3 precedent ("their meaning dies with the publication"; no parent hard-delete path exists, so the choice is latent and taken for consistency). Moderation-audit FKs stay RESTRICT (audit outlives content).

**V31 `community_social`**

- `community_member_profiles`: `user_id BIGINT PK` FK→users RESTRICT, `alias VARCHAR(80) NOT NULL`, `created_at`, `updated_at`; `CREATE UNIQUE INDEX uk_community_member_profiles_alias_ci ON community_member_profiles (LOWER(alias))`. One per user; created at first comment. Deliberate deviation from nothing — new surface; NOT PII: a user-chosen pseudonym validated against impersonation and self-PII rules (§4).
- `community_likes`: **PK `(user_id, publication_id)`** (deliberate deviation from #64's literal `UNIQUE(publication_id, user_id)` — same column set, matches the `community_publication_tags` composite-PK precedent, saves a surrogate id; arbiter inference for `ON CONFLICT (user_id, publication_id)` is identical), FKs user RESTRICT / publication CASCADE, `created_at`; index `(publication_id)` for aggregate COUNT. Mapped `@IdClass` (publication-tags precedent); writes via native upsert.
- `community_ratings`: same shape + `rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5)`, `created_at`/`updated_at`; **covering index `(publication_id) INCLUDE (rating)`** (index-only COUNT and AVG).
- `community_comments`: `id` PK IDENTITY, `publication_id` FK CASCADE, `author_user_id` FK users RESTRICT, `author_alias VARCHAR(80) NOT NULL` (snapshot of the **post-upsert stored** profile alias — never the raw request value), `body VARCHAR(2000) NOT NULL` (trimmed, non-blank, printable text — control characters other than `\n`/`\t` rejected at the API layer; stored verbatim, rendered escaped), `state CHECK ('VISIBLE','HIDDEN','DELETED')` DEFAULT VISIBLE, `edited_at TIMESTAMPTZ` NULL (iff edited), `created_at`/`updated_at`; indexes `(publication_id, state, created_at DESC, id DESC)` (public page scan + index-only VISIBLE COUNT) and `(author_user_id, created_at DESC, id DESC)` (abuse review, platform tiebreak convention).
- `community_comment_reports`: `id`, `comment_id` FK CASCADE, `reporter_user_id` FK users RESTRICT, `reason_code` CHECK (same 5 values as publication reports), `details VARCHAR(500)`, `status CHECK ('OPEN','DISMISSED','RESOLVED')` DEFAULT OPEN, `handled_by` NULL FK users RESTRICT, `handled_at`, `resolution_note VARCHAR(500)`, `created_at`; partial unique `UNIQUE(reporter_user_id, comment_id) WHERE status='OPEN'`; indexes `(status, created_at)`, `(comment_id)`.
- `community_moderation_actions` (V27 table, additive ALTER): add `comment_id BIGINT NULL` FK→comments RESTRICT + index `(comment_id, created_at DESC)`, and `comment_report_id BIGINT NULL` FK→comment_reports RESTRICT + index `(comment_report_id)`; widen the **action** CHECK (drop + re-add, identity-audit precedent) to add `COMMENT_HIDE`, `COMMENT_RESTORE`, `COMMENT_REPORT_DISMISS`, `COMMENT_REPORT_RESOLVE`. The state CHECKs are **not** widened: comment audit rows record comment state in `from/to_moderation_state` strictly for VISIBLE↔HIDDEN transitions; DELETED never appears (owner deletes are not moderation actions; refused hide/restore attempts never audit — publication no-op precedent). `from/to_status` stay NULL on comment rows.
- RBAC seeds: `COMMUNITY_LIKE`, `COMMUNITY_RATE`, `COMMUNITY_COMMENT` → STUDENT and TEACHER (idempotent `WHERE NOT EXISTS`); comment moderation uses the existing `COMMUNITY_MODERATION` permission.

Deletion semantics (tombstones): owner delete = soft delete — `UPDATE … SET state='DELETED', body='', edited_at=<preserved>` (single state-guarded statement, §6); DELETED comments never appear in any public list and cannot be restored. Moderator hide = `state='HIDDEN'`, body retained server-side for moderation review, absent publicly, present in the moderator queue. Restore = HIDDEN→VISIBLE only. **Deliberate deviation from #66's literal "public list with tombstones for hidden/deleted"**: the public list contains VISIBLE items only — no placeholder rows, `totalElements` counts VISIBLE only (rationale: moderation opacity — placeholder rows leak that content was hidden and invite probing; ADR-006 §5 deviation precedent).

Aggregates are computed on read (deterministic): `likeCount = COUNT(*)` on `community_likes` (index-only), `ratingCount = COUNT(rating)` / `average = ROUND(AVG(rating), 2)` (covering index), `commentCount = COUNT(*) WHERE state='VISIBLE'` (same predicate as the public list — summary and list totals can never diverge). `average` is JSON `null` when `ratingCount = 0` (pinned — never `0`). No denormalized counters; no N+1 anywhere (alias denormalized on the comment row).

## 4. Alias consent lifecycle & impersonation defenses

- **Validation** (create and every alias change): 1–80 chars after trim; printable visible charset only (reject control characters — except `\n`/`\t` never allowed in an alias at all — zero-width and bidi characters, mirroring the tag-charset discipline); must not contain `@`; rejected case-insensitively when equal to: the requester's own email **or** username, **any** `users.username` (EXISTS probe), or **any** `community_publications.author_alias` (EXISTS probe — closes publication-author impersonation). Symmetrically, publication publish/PATCH `authorAlias` validation additionally rejects ci-equality with any `community_member_profiles.alias` (the single P3-A touch, security-justified).
- **First comment**: request carries `alias`; the server validates, then upserts the profile (`INSERT … ON CONFLICT (user_id) DO NOTHING` + re-select; an alias **update** runs `UPDATE … WHERE user_id=?` after the same validation). A different-user collision on the ci-unique alias violates the index; the loser's comment POST fails **atomically** (single transaction, no partial rows) with **409 `COMMUNITY_ALIAS_TAKEN`** — the `DataIntegrityViolationException` is mapped by constraint name (`uk_community_member_profiles_alias_ci`) in the exception handler (fork-precedent recovery; never a 500). Comment snapshots the **post-upsert stored** alias, so concurrent first-comments from one user display one identity.
- Subsequent comments default to the stored alias; a changed alias applies to future comments only (snapshots are stable). FE re-shows the "publicly visible" acknowledgment whenever the submitted alias differs from the stored one.
- Likes and ratings carry no alias (no public identity surface). The profile is never exposed as a directory. Alias recycling after a user rotates off a name is an accepted LOW risk (§9).

## 5. API contract & auth matrix

Conventions per ADR-006 §7 (envelope, `no-store`, 401-AuthN for non-ACTIVE, matrix shape). Publication-scoped social endpoints require the §4 predicate → ineligible = **404 identical to absent**. Comment-scoped routes: owner PATCH/DELETE check **existence + ownership only** (users keep control of their own UGC even on hidden/unpublished publications — a hidden publication never *lists* the comment publicly, but its author can still delete it) and additionally require ACTIVE + `COMMUNITY_COMMENT` (module convention; defense-in-depth — a role-revoked owner gets 403, not 200, on their own comment); report routes require comment VISIBLE **and** parent publication §4-visible → else 404; moderation routes require the grant + comment existence (moderators act across publication visibility). All 404s for a given route are byte-identical (anti-enumeration; comment ids are global IDENTITY and public in VISIBLE lists — cross-publication enumeration of visible ids is inherent, publication-id parity). All mutations are admission-first.

| Route | Auth | Body → Result |
|---|---|---|
| `GET /api/public/community/publications/{id}/social-summary` | **anonymous** (sixth public GET) | 200 `{likeCount, average, ratingCount, commentCount}` — aggregates only, never caller states |
| `GET /api/community/publications/{id}/social-summary` | authenticated | 200 `{likeCount, average, ratingCount, commentCount, liked, myRating}` — aggregates + caller's own states ONLY; no other user ids anywhere |
| `PUT /api/community/publications/{id}/like` | ACTIVE + `COMMUNITY_LIKE` | 201 first / 200 idempotent re-PUT; `{likeCount, liked:true}` |
| `DELETE /api/community/publications/{id}/like` | same | 200 always; `{likeCount, liked:false}` |
| `PUT /api/community/publications/{id}/rating` | ACTIVE + `COMMUNITY_RATE` | `{rating: 1..5}`; publisher of the publication → **409 `COMMUNITY_SELF_RATING_NOT_ALLOWED`** (check before upsert; `publisher_user_id` immutable → no TOCTOU); otherwise 201 first / 200 update; `{average, ratingCount, myRating}` |
| `DELETE /api/community/publications/{id}/rating` | same | 200 always; `{average, ratingCount, myRating:null}` |
| `GET /api/public/community/publications/{id}/comments?page&size` | **anonymous** (fifth public GET) | `PageResponse` shape verbatim (`items,page,size,totalElements,totalPages,sort`); items `{commentId, authorAlias, body, createdAt, editedAt}` (all VISIBLE, `state` implicit); `created_at DESC, id DESC`; size 1..50 default 20 |
| `POST /api/community/publications/{id}/comments` | ACTIVE + `COMMUNITY_COMMENT` | `{body, alias?}` → 201 comment DTO (alias §4; 409 `COMMUNITY_ALIAS_TAKEN` on collision) |
| `PATCH /api/community/comments/{commentId}` | owner only | `{body}` → 200 (sets `edited_at`); foreign/absent → 404; non-VISIBLE → 409 `COMMUNITY_COMMENT_STATE_INVALID` |
| `DELETE /api/community/comments/{commentId}` | owner only | 200 always (idempotent; non-VISIBLE own → 200 no-op); foreign/absent → 404 |
| `POST /api/community/comments/{commentId}/reports` | ACTIVE + `COMMUNITY_REPORT` | fixed order: admission → comment VISIBLE + parent §4 predicate (else 404) → `ON CONFLICT … WHERE status='OPEN' DO NOTHING` dedupe (200) → 201 |
| `GET /api/community/moderation/comment-reports?status&page&size` | moderator | queue items `{id, commentId, comment:{body, authorAlias, createdAt}, publication:{card summary}}` paged newest-first |
| `POST /api/community/moderation/comments/{commentId}/hide` `{reason}` | moderator | VISIBLE→HIDDEN, audit `COMMENT_HIDE` (states in from/to_moderation_state); repeat hide → 200 no-op without duplicate audit; hide of DELETED → 409 `COMMUNITY_COMMENT_STATE_INVALID` |
| `POST /api/community/moderation/comments/{commentId}/restore` `{reason}` | moderator | HIDDEN→VISIBLE, audit `COMMENT_RESTORE`; repeat restore → 200 no-op; restore of DELETED → 409 |
| `POST /api/community/moderation/comment-reports/{id}/dismiss|resolve` `{note?}` | moderator | sets handled fields; audit `COMMENT_REPORT_DISMISS`/`COMMENT_REPORT_RESOLVE` with `comment_report_id` link; 409 `COMMUNITY_REPORT_NOT_OPEN` when not OPEN |
| `GET /api/community/moderation/comments/{commentId}/history` | moderator | audit rows newest-first (unpaged array, publication-history precedent) |

Error codes added: `COMMUNITY_ALIAS_TAKEN` (409), `COMMUNITY_COMMENT_STATE_INVALID` (409), `COMMUNITY_SELF_RATING_NOT_ALLOWED` (409); reuse `COMMUNITY_ALIAS_INVALID` (400 charset/self-identity), `COMMUNITY_VALIDATION_ERROR` (400 bounds), `COMMUNITY_NOT_FOUND` (404 publications), `COMMUNITY_COMMENT_NOT_FOUND` (404 comments — identical for absent/foreign/non-VISIBLE), `COMMUNITY_RATE_LIMITED` (429).

**Rate policies** (`quizopia.community.rate-limit.*`; four-place lockstep: `Policy` enum + `maxFor()` + properties + main/test properties): `LIKE` 60/h/user, `RATING` 30/h/user, `COMMENT_CREATE` 10/h/user, `COMMENT_EDIT` 20/h/user, `COMMENT_DELETE` 30/h/user, `COMMENT_REPORT_USER` 5/h/user + `COMMENT_REPORT_IP` 20/h/IP — **separate buckets** from publication `REPORT_*` (independent budgets; the policy segment in the HMAC key exists for exactly this). Comment moderation writes reuse `MODERATION` (60/h/user). All six public GETs (the four existing plus the two new — comments list, public social-summary) use the `PUBLIC_READ_IP` posture: 6000/h/IP anonymous, user-keyed when authenticated; the authenticated social-summary twin is admitted under the same `PUBLIC_READ_IP` policy on the user dimension.

## 6. Concurrency & integrity

- Like PUT: `INSERT … ON CONFLICT (user_id, publication_id) DO NOTHING` + re-select → `created` from rowcount (bookmark precedent); races collapse to one row, all 2xx.
- Rating PUT: single native statement `INSERT … ON CONFLICT (user_id, publication_id) DO UPDATE SET rating=:r, updated_at=now() RETURNING (xmax = 0) AS created` (JdbcTemplate; `xmax=0` distinguishes insert vs update race-correctly) → 201/200; aggregates recomputed on read after upsert.
- Comment create: admission → §4 visibility → alias validate/upsert (§4) → insert; alias collision aborts atomically → 409.
- Comment PATCH: state-guarded CAS `UPDATE community_comments SET body=:b, edited_at=now(), updated_at=now() WHERE id=:id AND author_user_id=:u AND state='VISIBLE'` → rowcount 0 with ownership = state conflict → 409 (prevents delete-then-edit resurrecting a cleared tombstone; no separate row lock needed).
- Comment DELETE: `UPDATE … SET state='DELETED', body='', updated_at=now() WHERE id=:id AND author_user_id=:u` (any state; rowcount 0 without ownership → 404, else idempotent 200).
- Self-rating check precedes the upsert (publisher id immutable; sound under race).

## 7. Frontend contract (P3-C3/C4)

- Detail page replaces the two large Save/Fork panels with one compact, left-aligned, wrap-friendly toolbar: **Like → Comment → Rate → Fork → Save** (icon + label, ≥44px targets, active states for liked/rated/saved, per-action loading disable, like count beside Like). Aggregate `average (2dp, null-safe) + ratingCount` moves into the quiz metadata area — rendered for guests too, fed by the **public** social-summary. Fork button keeps `allowFork` gating + `ForkPublicationDialog`; Save keeps existing bookmark semantics.
- Guests: toolbar renders with counts; Like/Rate/Save CTAs redirect to `/login` with `returnTo` (sanitized helper); comments remain readable. **Authored clarification (implementation review, #67)**: the Comment CTA anchor-scrolls to the discussion section instead of redirecting — guests can read visible comments (§7 intent) and the editor itself is the login-gated surface; posting stays server-gated (§5 ACTIVE + `COMMUNITY_COMMENT`).
- Community Discussion section below the toolbar: paginated newest-first list (alias, timestamp, plain-text body, edited marker), own-comment Edit/Delete, Report for others, empty/loading/error states, editor with submit/cancel + live char count (≤2000), first-comment alias capture with consent copy (re-shown on alias change). No `dangerouslySetInnerHTML`; React text escaping only.
- Rating UX: accessible 1–5 star selector (radiogroup, keyboard operable) as popover/dialog; average, count, own rating; update + clear; publisher sees the self-rating-blocked state (server 409 copy).
- FE API module + hooks extend the `["community"]` family; moderation console gains a comment-reports tab behind the same grant gate. Star radiogroup + toolbar built from Button/Badge/Card/Input + DESIGN.md tokens (no such primitives exist); `CommunityAdaptiveShell` untouched.

## 8. Testing strategy

Backend (Testcontainers PG17): V31 matrix (fresh + V30-staged; CHECKs incl. widened action CHECK + new FKs; alias ci-unique; partial unique; head-pin bumps 30→31 across the 11 pinning suites); like/rating duplicate + 8-thread concurrent races; rating upsert/update/delete + aggregate determinism **incl. zero-ratings → `average:null`**; self-rating 409; auth matrices (guest/student/teacher/publisher/foreign/disabled across every route incl. owner-routes-on-hidden-publication); comment ownership (edit/delete own incl. idempotent re-delete; foreign 404 byte-identical); **alias contract**: first-comment capture, change + re-consent data path, collision race (two users same alias → exactly one 201, one 409 `COMMUNITY_ALIAS_TAKEN`, zero comment rows), impersonation rejections (any-username, publication author_alias, `@`, control/bidi chars), publication alias reverse-check; tombstone non-leakage (DELETED body cleared in DB + absent from list; HIDDEN absent publicly, present in queue; delete-then-edit race cannot resurrect); comment report dedupe race + hidden-parent 404 oracle; moderation hide/restore idempotency + audit rows (incl. `comment_report_id` linkage); rate limits (LIKE/RATING/COMMENT_* saturations, separate report budgets); public routes method-matrix + anti-enumeration; recursive JSON redaction (no PII/other-user ids); full community + fork/bookmark regression green.

Frontend: Vitest component tests (toolbar states × auth states incl. guest counts from public summary, rating popover a11y + keyboard, comment flows incl. alias consent + change re-consent, report, foreign-action absence, leak checks with poisoned fixtures), tsc/eslint/build. E2E at P3-C5: guest browse + comments + aggregates, login continuation, like/rate/comment, own edit/delete, foreign denied, moderator hide + no leak, ineligible publications expose nothing, fork + bookmark regression, responsive 375/768/1024/1440/1920.

## 9. Consequences & risks

- Comment alias is a new public identity surface — deliberately minimal (pseudonym, no profile pages, no directory, collision-checked, cross-namespace impersonation blocked). LOW (accepted): aliases can be recycled after their owner rotates off them (two historical authors may render identically in old threads); HIDDEN/DELETED-adjacent content may survive inside moderator-only `community_comment_reports.details`/`resolution_note` free text — a documented moderator privilege (ADR-006 §5 parity), never public.
- On-read aggregates: two indexed COUNTs + a covering-index AVG per summary fetch — fine at MVP scale.
- Alias snapshots make changes non-retroactive — display stability over global rename.
- Author self-rating blocked (frozen policy: manipulation reduction; publishers may like their own quiz — rating integrity unaffected).
- LOW (accepted): last-writer-wins own-comment edits (owner-only surface, state-guarded); publication-author alias namespace vs member aliases is cross-checked at write time on both sides but historical rows are not retro-validated.

## 10. Alternatives considered

- **Denormalized like/rating counters** — rejected: drift risk; on-read aggregates are deterministic and cheap with the frozen (covering) indexes.
- **Reuse `community_reports` for comments** — rejected: publication-scoped shape; comment reports need their own table, queue and audit linkage.
- **Alias on `users` (identity module)** — rejected: identity stays PII-minimal; community owns its pseudonym surface.
- **Public list tombstone placeholders** — rejected (documented deviation §3): moderation opacity.
- **Authenticated-only aggregates** — rejected (review finding): guest toolbar unimplementable; public counts-only twin chosen.
- **Sharing publication report buckets with comment reports** — rejected: independent budgets via policy-segmented keys.
- **Websocket live comments / threads / rich text** — out of MVP scope.

## 11. Implementation allocation

| Issue | Deliverable | Migration |
|---|---|---|
| #65 (P3-C1) | V31 (full), likes, ratings, both social-summary routes **incl. the public twin's SecurityFilterChainConfig GET permitAll matcher**, RBAC seeds, LIKE/RATING policies | V31 |
| #66 (P3-C2) | comments CRUD + alias profile, comment reports, comment moderation (CHECK widen + comment_id/comment_report_id), COMMENT_* policies, **SecurityFilterChainConfig GET permitAll matcher for the public comments route** | V31 (landed by #65) |
| #67 (P3-C3) | FE toolbar + public aggregates | — |
| #68 (P3-C4) | FE discussion + rating UX | — |
| #69 (P3-C5) | gate: full suites, E2E, responsive, report | — |

No renumbering; V1–V30 untouched.

## 12. Review record

- **R1 (2026-11-09, this revision)**: three independent reviewers (architecture, security, DB). Verdicts 3× GATE-FAIL, 1 BLOCKER + 8 HIGH + 12 MEDIUM — all closed in Rev 2: public social-summary twin (BLOCKER/ARCH-1/SEC-3/DB-2); alias collision contract + `COMMUNITY_ALIAS_TAKEN` + atomic failure + race recovery (SEC-1/DB-1/ARCH-3d); alias impersonation namespace checks both directions + charset policy (SEC-2/SEC-10); comment-report eligibility oracle + §3 contradiction (SEC-4); owner-route vs visibility semantics pinned (SEC-5); complete policy table incl. named constants + separate comment-report buckets + summary/list read policy (SEC-6/ARCH-6/DB-5); state-guarded comment CAS + rating `RETURNING (xmax=0)` mechanism (SEC-7/DB-4/DB-10); `comment_report_id` audit linkage + scoped retention claim (SEC-8/DB-7); tombstone deviation documented (ARCH-2/SEC-9); aggregate edges pinned (`average:null`, VISIBLE-only `commentCount`) + covering index (DB-8/DB-12); composite-PK deviation note + `@IdClass` pin (DB-3/DB-13); FK policy aligned to CASCADE precedent with rationale (ARCH-8/DB-6); state CHECKs not widened — VISIBLE↔HIDDEN only (ARCH-5); PageResponse `sort` field (ARCH-9); security-chain matchers in the deliverable list (ARCH-7); DELETE idempotency pinned (DB-11); abuse-index tiebreak (DB-9); alias-change re-consent (SEC-13/ARCH-10); alias recycling recorded (DB-14). R1 evidence on issue #64.

## 13. Rev 4 (2026-11-10, issue #72): comment author display-name policy — alias consent retired

**Owner-approved product decision (Epic #70 F6).** After Phase 3-C shipped, the owner explicitly decided that public comments display the author's **account profile `displayName` (`users.display_name`) by default**, with **no Community Alias entry and no first-comment public-name confirmation**. This Rev supersedes the pseudonym guarantees this ADR originally established for the COMMENT surface. It does NOT change likes, ratings, publication `authorAlias` attribution, moderation rights, visibility predicates, rate limits, tombstone semantics or anti-enumeration behavior.

### 13.1 What changed

- **Create**: `POST /api/community/publications/{id}/comments` accepts `{body}` only. Author identity is the authenticated JWT subject; the author name is resolved server-side. A legacy client still sending `alias` is ignored at deserialization (Spring Boot's default `FAIL_ON_UNKNOWN_PROPERTIES=false`) — it can never override the displayed name or impersonate anyone. `CreateCommentRequest` lost its `alias` component.
- **Public page**: `PublicCommentEntry` is now `{commentId, authorName, body, createdAt, editedAt, isMine}`. `authorName` = the author's CURRENT `users.display_name` read via JOIN at query time (`CommunityCommentAuthorStore`, two statements — count + page, no N+1). Profile renames are reflected on existing comments immediately; duplicate display names are allowed (the column was never unique). A missing/blank name falls back to the generic `Community member` (defense-in-depth: V2's `LENGTH(TRIM(display_name)) > 0` CHECK already makes blank names unreachable).
- **Ownership (`isMine`)**: computed server-side by comparing the OPTIONAL authenticated caller id (a valid JWT on the otherwise anonymous public GET — the existing `PublicReadCaller` pattern) against `author_user_id`. Anonymous callers get `false` on every item: no viewer state, no internal author ids. The FE's former alias-set equality heuristic is deleted.
- **Owner routes**: unchanged §6 CAS shapes — server-authorized by `author_user_id` alone; identical display names never grant cross-user control.
- **Moderation queue / hide-restore DTOs**: `authorAlias` → `authorName` (current display name; moderator privilege). The moderation queue batches display-name lookups (one statement per page).
- **Error codes**: `COMMUNITY_ALIAS_TAKEN` removed (no alias writes remain to collide). `COMMUNITY_ALIAS_INVALID` and `COMMUNITY_ALIAS_REQUIRED` survive — they serve the PUBLICATION `authorAlias` surface, which is unchanged.

### 13.2 Data model (forward-only V32)

`V32__community_comment_display_name.sql`: `community_comments.author_alias DROP NOT NULL` — new rows store NULL; the column and all legacy snapshots are RETAINED (historical data; never deleted). `community_member_profiles` is RETAINED unchanged as a deprecated read-only legacy surface: no API path writes it, and the publication publish/PATCH reverse impersonation probe (`assertAliasNotTakenByMember`) keeps reading it, closing the publisher credit namespace against every alias a member ever took. Likes, ratings, reports and audit tables are untouched. No backfill: display names are read-time JOINed.

### 13.3 Privacy statement (accurate as of Rev 4)

`users.display_name` — historically non-public — is now **intentionally public on exactly one surface: the community comment author name** (public page, owner create/PATCH response `authorName`, moderator queue). This is a deliberate narrowing of the original pseudonym policy, approved by the owner. No other public DTO exposes it; username, email, phone, internal user ids and token data remain non-public everywhere, including comments. The FE renders a small informative note (posted name is public) without any consent gate.

### 13.4 Superseded provisions

The following original provisions are OBSOLETE for comments (kept above for history): §2's "comment authors are identified publicly only by an explicit, consent-based, collision-checked community alias captured at first comment"; §9's "alias snapshots make changes non-retroactive — display stability over global rename" and alias-recycling notes (comment names now track the account's CURRENT display name — retroactive by design); the §4 alias consent lifecycle (first-comment capture, defaults, change + re-consent, `COMMUNITY_ALIAS_TAKEN` collision mapping, member-direction impersonation probes); §3's `author_alias NOT NULL` snapshot semantics for new rows; §5's `CreateCommentRequest {body, alias}` and `PublicCommentEntry {… authorAlias …}` shapes; §8's alias contract test list. The §4 reverse check on publication `authorAlias` (a publication author may not take a member alias) REMAINS in force against the legacy table.

### 13.5 Accepted consequences of the public display name

`users.display_name` is self-service and is NOT cross-checked against usernames or publication `authorAlias` values (the old §4 namespace probes existed because aliases were collision-checked; display names deliberately are not). Two users may render identical names next to comments, and a commenter may choose a name equal to a well-known publisher's credit — impersonation mitigations are now social/report-based (comment reporting + moderation) rather than name-uniqueness-based. This is inherent to the owner-approved duplicates-allowed policy; duplicates never grant cross-user ownership (owner routes are `author_user_id`-keyed) and the FE shows no profile-link affordance from a comment name.
