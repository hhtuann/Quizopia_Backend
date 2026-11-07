# ADR-004 — Identity M5 account and credential management

Status: approved for implementation after two independent architecture/DB and security review rounds. Initial proof-binding/schema/redaction findings resolved in the specification; implementation acceptance remains mandatory. Product decisions D1–D6 authorized explicitly by the M5 execution request; this supersedes older approval wording in System #33/#34. No merge/deploy authorization.

## Scope and baseline

Backend main 2f0aeb3bbff9a121758eef906ff7a84def79048e, frontend main 463c164ec697088cf777ff8fea1c18c29d737d05, fetched before audit. Modular monolith, existing identity domain and authentication application services retained; self-service account application/API separated from SYSTEM_ADMIN user management. Actual frontend is Next 16.2.9/React 19, not the brief's Next 15. Preserve current topbar and DESIGN.md.

Only displayName and phone editable. Username/email read-only. Initials avatar. No uploads, Community, self email change, new service, role/membership/ownership changes, password-history database or implicit set-password.

## Credential and account states

Add localCredentialState ENABLED/DISABLED/UNKNOWN, NOT NULL default UNKNOWN with DB CHECK. All existing users remain UNKNOWN: hashes, Google links, passwordChangedAt and email proof cannot independently establish provenance. New password registration/admin creation/operator seed explicitly ENABLED; new Google provisioning explicitly DISABLED. Linking never changes provenance by itself.

UNKNOWN retains password and Google login. Successful fresh current-password proof (login, reauth/change) promotes UNKNOWN to ENABLED atomically. DISABLED rejects password authentication/change/reset and retains Google login. ENABLED, including Google-linked local accounts, supports change and recovery. UNKNOWN forgot remains generic with no mail; current-password login plus verified email enables recovery. A legacy UNKNOWN user who has forgotten their password requires operator-assisted provenance investigation outside M5; email ownership alone must never create a Google-only local password. No automatic legacy lockout or unsafe guessed backfill.

ACTIVE required for mutations/issuance; PENDING/DISABLED denied by existing contracts. Active temporary lock denies profile and credential writes; safe owner reads remain available. Reset does not bypass lockout: request/consume require lock expired, user can retry after the configured window. Email verification is ownership proof only; legacy login compatibility flag never grants reset eligibility. Current verified email and ENABLED required for reset.

## HTTP contracts

Reuse existing ApiError envelope, deny-by-default filter and authenticated principal. Never accept owner IDs or bind domain entities from JSON. Explicit DTOs reject unknown fields on new mutation routes.

| Route | Request / response |
|---|---|
| GET /api/auth/me | Existing owner-safe CurrentUserResponse unchanged |
| PUT /api/account/profile | Full replacement of editable projection: required displayName, nullable phone (absent/null clears); 200 CurrentUserResponse. Client always sends both. |
| GET /api/account/security | 200 {localCredentialState, canChangePassword, googleLinked, passwordChangedAt}; UNKNOWN can attempt current-password proof, DISABLED cannot |
| POST /api/account/security/change-password | {currentPassword,newPassword,confirmPassword}; 204, expire refresh cookie, no tokens |
| POST /api/auth/password/forgot | {email}; 202 identical generic confirmation including ineligible/throttled/saturated cases; syntax errors 400 independent of existence |
| POST /api/auth/password/reset | {token,newPassword,confirmPassword}; 204, expire refresh cookie, no automatic login |

Trim displayName, require 1–150 characters after trim, reject control characters. Phone null/blank clears; otherwise trim, allow optional leading + and 7–15 digits (no internal punctuation). AES-GCM existing encryptor retained; plaintext only in owner DTO, never audit. Passwords untrimmed 8–128 chars, required confirmation equality; reject current password reuse, no additional history requirement. Existing Argon2id parameters/lazy upgrade retained. Invalid/expired/replaced/used reset token shares one 400 code; unsupported credential 403; wrong current password existing invalid-credentials error; lock 423. Disabled JWT may fail earlier at converter with 401.

## Transaction and revocation invariant

Common order: fresh user row lock, then credential proof/reset/refresh/OAuth artifact row locks. Nonlocking ID projections may identify owner first but no authorization decision uses those snapshots. Avoid stale persistence-context instances: first user read inside the transaction must lock, or explicitly refresh under lock. Capture time after blocking locks.

Shared password reauthentication uses one user-locked transaction for fresh status/lock/hash, expired-lock reset, failure increment and success. Return an outcome then throw outside the committing transaction so failures survive. Never start REQUIRES_NEW while holding the same row. Login, link reauth and password change share threshold 5/duration 15m configuration. Validate new-password syntax before expensive reauth; verify current proof before reuse check.

Credential mutation atomically writes Argon2 hash, passwordChangedAt, ENABLED, tokenVersion+1, revokes every refresh family including current, invalidates pending reset/OAuth login/link proofs, and records redacted audit. Flush before existing clearing bulk updates. No credential changes or version writes outside this transaction. Old JWT rejected on next protected request by existing DB converter. Refresh and initial issuance acquire same user lock, preventing a pre-change login/rotation from issuing a usable post-change session. Login completion revalidates the proof under lock, never rehashes a stale password.

Existing writers must cooperate: admin mutations lock user (reads remain unlocked), OTP consumption locks fresh user before challenge and revalidates address, OAuth bookkeeping locks fresh user. Google provider email can verify current address only when normalized addresses match; stable issuer/subject login remains valid without granting unrelated address proof. Link completion rechecks email under lock. OAuth login/link artifacts carry credential epoch, checked at final exchange/link; do not hold DB locks across provider HTTP calls. Exchange consumption and issuance are atomic. Reset invalidates preexisting authorization proofs, not a genuinely new Google authentication.

Established WebSockets use a trusted server-owned simpSessionId → JwtAuthenticationToken registry populated on authenticated CONNECT and removed on disconnect. Inbound protected operations and per-recipient clientOutboundChannel delivery validate ACTIVE status, credential epoch and expiry against DB; absent principal resolves only by that exact session ID, otherwise fails closed. Retain SEND/MESSAGE denial and subscription permissions. Check at delivery-handler time to cover queued broadcasts; ERROR/DISCONNECT remain possible. Checks after credential commit reject old sessions; already authorized/in-flight bytes cannot be recalled.

## Reset persistence, intake and delivery

Separate reset challenge table, not OTP purpose reuse. 32 SecureRandom bytes, Base64URL without padding, SHA-256 digest only at rest. Unique digest; one pending challenge/user partial unique index; user/history and expiration indexes. Bind challenge to normalized email, database-precision emailVerifiedAt and tokenVersion at issuance. 15-minute TTL; replacement supersedes prior pending challenge under user lock. Consume user-first/challenge-second, recheck eligibility, all bindings, pending state and expiry; atomically consume and mutate credential. Admin email changes invalidate pending challenges; binding also protects changed-away-and-back addresses. Reuse rejection does not mutate credentials.

Reset schema: UUID id primary key; user_id BIGINT NOT NULL FK users ON DELETE CASCADE; token_hash VARCHAR(64) NOT NULL UNIQUE CHECK lowercase 64-hex; email_binding VARCHAR(254), email_verified_at TIMESTAMPTZ, credential_epoch INTEGER all NOT NULL with epoch >=0; status VARCHAR(16) NOT NULL CHECK PENDING/CONSUMED/SUPERSEDED; created_at/expires_at TIMESTAMPTZ NOT NULL with expires_at>created_at; consumed_at/superseded_at nullable, present iff respective terminal status and >=created_at. Unique(user_id) WHERE status='PENDING'; indexes (user_id,created_at), expires_at. Expiry is a predicate, not a fourth state. Terminalize and flush predecessor before inserting replacement. Application-supplied timestamps truncated to microseconds; proof timestamps read from persisted user, not independently recomputed. Cleanup rows expired over seven days ago.

V22 adds credential_epoch INTEGER NOT NULL CHECK >=0 to OAuth login/link artifacts. Preexisting short-lived proofs are deleted before adding the required column: require fresh OAuth attempt, never assign old proof today's epoch. Carry original resolved/authenticated user epoch into minting/start; lock and compare rather than relabeling it. Final exchange consumes and issues in one transaction; final link rechecks stored epoch after provider HTTP. Logout joins user-first/artifact-second ordering to avoid stale rotation metadata writes.

OTP ownership protection: admin email reassignment, under user lock, deletes ALL verification challenges (including consumed proofs) for that user before writing the new email, invalidating A→B and A→B→A reuse. Issuance/resend and consumption lock user first, require normalized request/issuance address still matches current email, then lock challenge. Delivery uses the captured issuance address, never a later mutable user address. In-flight deleted challenge fails consumption. Existing challenge rows need no guessed backfill because every future email reassignment performs atomic invalidation. Correct consumed-code retry only applies while the same proof row exists and current verified email matches.

Generic response after account-independent synchronous abuse admission, before account lookup or SMTP. Fixed-order IP then email bucket admission in one short DB transaction for EVERY syntactically valid request, including unknown/ineligible accounts. Bucket table: bucket_key VARCHAR(64) primary key CHECK lowercase 64-hex (domain-separated HMAC email/IP); window_start TIMESTAMPTZ, attempts INTEGER CHECK 0..2147483647, last_admitted_at nullable TIMESTAMPTZ, expires_at TIMESTAMPTZ all except last_admitted_at NOT NULL; expires_at>window_start; expiry index. Atomic upsert/row lock resets elapsed one-hour window and saturating-increments attempts, even when denied. Email max5/hour plus 60s since last admitted request, IP max20/hour; denied attempts do not extend cooldown. Fixed windows permit adjacent-boundary bursts, bounded by both buckets and cooldown. Expired buckets cleaned after two hours; last-admitted time preserved across window reset. Queue (2 workers, 200 capacity) receives only admitted work; saturation consumes allowance and returns identical 202. No user lookup before admission; per-user lock serializes issuance. Never trust forwarded IP without configured trusted proxy.

Mail only after challenge commit, purpose-specific reset sender using existing JavaMail transport/capture testing. Failure logging records exception class only, no recipient/message/body/token. Failed delivery or process restart may drop a notification: best effort, user retries after cooldown; no promise of durable email. No raw token in DB/outbox/audit/metrics/logs. SMTP failure must not change HTTP eligibility response.

Configured trusted absolute reset URL ending /reset-password, HTTPS required except explicitly non-production localhost/loopback HTTP. Reject userinfo/query/fragment and non-allowlisted origin at startup. Never derive URL from Host or forwarded headers. Append #token=opaque. Responses no-store; reset page no-referrer. No analytics/third-party script capture on recovery route.

## Frontend behavior

One authenticated /profile independent of Student/Teacher view. Existing AppShell/Header entry, tokens/forms/styles; initials, readonly username/email and verification/link indicators. Editable displayName/phone, dirty/cancel/save/loading/success/error, labelled inputs and accessible errors, responsive fixed-topbar layout. Successful save calls refreshUser and synchronizes header.

Security section reads capability, offers current/new/confirm and visibility toggles, explains Google-only and UNKNOWN proof path. Success synchronously invalidates local auth and query/workspace context, replaces route with login and non-sensitive success marker. No logout network call after server revocation. Shared monotonic auth generation guards boot/login/OAuth completion, interceptor refresh/token writes and retries: capture before request, reject stale completions, generation-scoped single-flight. In-flight older responses cannot resurrect JavaScript auth. Backend invalidation protects any stale Set-Cookie response.

Forgot page neutral confirmation and resend. Reset captures fragment into component memory and removes it with history.replaceState immediately before network, no session/local storage, logging, query strings or auto-login. StrictMode capture must retain token for the mounted lifecycle without restoring history. Invalid/expired/replayed links offer fresh request; password errors remain actionable. Public password routes never trigger refresh interceptor. Successful reset clears any current local auth state.

Public forgot/reset HTTP requests explicitly omit Authorization, including any stale token, and never trigger refresh. Login/OAuth success acquisition also participates in shared generation captured before token request. Global error logs use exception class, trace ID and matched route template/fixed fallback only, not raw URI, exception message/throwable/cause. Reused OTP worker/SMTP failure logging is redacted similarly; SQL bind and SMTP debug logging must stay disabled. Inject sentinel secrets into nested failures and assert no logs/responses expose them.

## Audit

Expand existing identity_audit_events CHECK forward-only, retaining all teacher events. Add profile update, credential proof promotion, password change/reset events. Store actor/user ID and event type; profile metadata field names only. Never passwords, hashes, tokens, email, phone, display name or untrusted exception/request bodies. Failure attempts use existing safe counters; no account-existence signal through public audit responses.

## Migration and concurrent integration

Fetched main and all local worktree/remote branch migration history end V20; no Organization allocation exists in inspected worktrees or published PRs. Reserve V21 profile audit, V22 credential foundation/OAuth epochs, V23 password reset and throttle persistence; announce in System ORG #24 and M5 #34 before creation. ORG must allocate after V23 or coordinate a different union before either track deploys. Recheck integration head before merge; verify fresh and V20→combined union plus any permitted staged deployment. Never renumber applied migrations or silently enable out-of-order. V1–V20 untouched. Preserve all user/domain data. Mixed-version deployment unsafe for provenance/writer locking: coordinated backend cutover then frontend. No down migration; back up and forward-fix.

## Verification and review gate

Independent architecture/security/DB review before production implementation, then milestone implementation/test/review/fix cycles. Profile owner/mass-assignment/encryption/admin boundary tests. Foundation creation matrix, legacy login, shared lockout, old JWT/all refresh, concurrent issuance/rotation and stale writers. Change correct/wrong/weak/reused/Google-only/UNKNOWN, preserved identity/roles. Reset capture + real Mailpit, expiry/replay/replacement/concurrent winners, all eligibility classes, timing/unknown throttling, queue saturation/mail failure, address/proof epoch changes, audit redaction. Fresh and V20 upgrade tests. Regression registration/OTP/OIDC/link/logout/teacher/Workspace/Organization and Phase 1/2.

Frontend component tests for consumer-visible states and auth races; typecheck/lint/tests/build; real browser local-stack profile/change/forgot/reset/relogin, multiple sessions, mobile/desktop/keyboard and topbar. Existing Google signed-token/provider tests plus live Google only if credentials available; report unavailable live provider verification explicitly. Full Maven suite and exact-final-SHA CI evidence, no invented passes. Record findings severity/root cause/repro/fix/regression/disposition in progress/review artifact. No merge until human approval.

## M5-A profile implementation

The profile slice uses `AccountController` → `ProfileService`, with an explicit authenticated PUT allowlist entry. The owner ID and credential epoch come only from the authenticated JWT. The service locks and refreshes the user before checking epoch, ACTIVE status and temporary lock; it never accepts an owner selector in JSON. The existing `/api/auth/me` remains the safe owner read, including while temporarily locked.

`UpdateProfileRequest` accepts only `displayName` and `phone`; unknown fields fail with the existing validation envelope without echoing input. Display names are trimmed, required, capped at 150 characters and reject control characters. Phone is trimmed, optional leading `+` plus 7–15 ASCII digits; absent, null or blank clears it. The existing AES-GCM encryptor protects the stored phone, and the returned `CurrentUserResponse` exposes plaintext only to its owner. Username, email, password, roles, verification and session state are not updated. Profile edits do not revoke sessions.

V21 only extends the audit event CHECK, preserving all three teacher events and existing rows. A successful mutation writes `PROFILE_UPDATED` in the same transaction, with metadata exactly `{"fields":["displayName","phone"]}` and no request IP/user-agent or profile values. Credential foundation, security capabilities and password flows are separate slices, not enabled by this endpoint.

Verification commands from `backend` (Java 21 and Docker required; not executed by the profile implementation worker):

```powershell
.\mvnw.cmd -Dtest=ProfileIntegrationTests,V21ProfileAuditMigrationMatrixTest test
.\mvnw.cmd -Dtest=AccessTokenAndMeIntegrationTests,UserManagementIntegrationTests,TeacherSelfUpgradeIntegrationTests,UserEncryptedPersonalDataIntegrationTests test
```

The profile tests cover owner isolation, unchanged protected fields, ciphertext/decryption, redacted audit, unknown-field rejection, normalization and length boundaries, nullable replacement, admin-route denial, locked writes versus safe reads, expired locks, inactive/revoked JWTs and a service-level stale persistence-context epoch. The migration matrix exercises fresh V21 and V20→V21 with legacy user/audit preservation and strict event constraints. Passing evidence remains the integrating agent's verification responsibility.

## M5 realtime revocation foundation

`RealtimeSessionRegistry` binds only authenticated CONNECT tokens to the server-owned exact `simpSessionId`, removes bindings on disconnect, and reads ACTIVE status and credential epoch directly from the database without relying on a persistence-context `User`. Expiry is checked with the application clock. Missing bindings and database failures fail closed; principal names, native headers and destination strings never substitute for session identity.

`StompSessionGuardInterceptor` rejects stale SUBSCRIBE before subscription events and rechecks protected messages/subscriptions at executor handler time on both client channels. Recipient-specific outbound delivery is therefore denied even when queued before a committed epoch change. Existing resource authorization and SEND denial remain in place; disconnect/unsubscribe, ERROR and connection-control frames remain available. CONNECT failure logs contain only the exception class.

`WebSocketRevocationIntegrationTests` uses real STOMP clients and the simple broker for personal and teacher subscriptions. It installs and restores the outbound executor gate using `setInterceptors` (not indexed add/remove, which do not maintain executor callback ordering/removal), checks a current binding at gate entry and revoked binding before release, and awaits completion of the queued delivery. Epoch commit, ACTIVE→DISABLED and queued expiry each deny queued/new delivery. Concurrent fresh and revoked connections for the same user exercise per-recipient isolation on the same personal/topic broadcast; stale subscriptions fail and fresh reconnect succeeds. A second-invocation regression ensures a removed gate receives no executor callback. `RealtimeSessionGuardTests` covers exact binding, expiry, database denial/exception, disconnect cleanup with a still-valid token, both CONNECT and STOMP connection commands, and sentinel-safe failure logging. The implementation worker did not run checks; integration should run these plus the existing realtime suite.

## M5 OAuth and OTP foundation

OAuth login/link artifacts store the original credential epoch. Minting and consumption use `CredentialSecurityService.lockUser` before artifact locks; exchange consumption and session issuance share the exchange transaction. Authenticated link start carries the JWT epoch, commits shared password-reauthentication failures, and records the epoch only after successful proof. Provider exchange runs outside database locks; final linking freshly locks the account and rechecks the stored epoch and exact normalized address, including already-linked identities. Stable Google subject login does not verify a different current account email. Newly provisioned Google users explicitly have DISABLED local credentials.

OTP verification and issuance lock and refresh the user before challenge access, recheck the requested/captured address, and never merge a stale user snapshot. Consumed-code retries require an existing proof row and a currently verified matching address. Admin reassignment deletes all challenge history through `deleteAllByUserId`; delivery carries immutable issuance address/display-name values. Worker and OAuth failure logs expose exception class only.

Targeted integration commands from `backend` (not run by this implementation worker):

```powershell
.\mvnw.cmd -Dtest=OauthIdentityIntegrationTests,OauthIdentityConcurrencyIntegrationTests,GoogleLinkIntegrationTests,OauthExchangeAtomicityIntegrationTests test
.\mvnw.cmd -Dtest=EmailVerificationAddressBindingIntegrationTests,EmailVerificationOtpIntegrationTests,EmailVerificationTransactionIntegrationTests,EmailVerificationResendIntegrationTests test
```

The added deterministic regressions cover stale proof minting, previous-epoch exchange, provider-call epoch changes, mismatched-address linking/Google proof, issuance rollback, stale registration issuance, and pending/consumed OTP invalidation across address reassignment. Live Google verification remains a separate integration prerequisite.

## M5 credential foundation implementation

V22 preserves existing users as UNKNOWN, adds explicit local-credential provenance and OAuth proof epochs, discards pre-epoch OAuth artifacts, and extends the redacted audit event allowlist. Password registration, admin creation and demo provisioning explicitly enable local credentials; Google provisioning explicitly disables them.

`CredentialSecurityService` owns fresh user locking, shared password reauthentication outcomes and atomic credential mutation. Callers commit rejected proof outcomes before throwing, so lockout counters survive. Login verifies and issues in one user-locked transaction; refresh, logout and initial issuance use the same user-first ordering. Admin mutations refresh under lock and email reassignment deletes all OTP history and invalidates authorization proofs in the same transaction. Synchronous `AuthorizationProofsInvalidated` listeners provide the V23 reset invalidation extension without creating reset tables in V22.

`GET /api/account/security` exposes only credential state, password-change capability, Google link presence and password-change time. Temporary lockout does not deny this owner-safe read.

Foundation verification targets (integrator execution required): `CredentialFoundationIntegrationTests`, `ProfileWriterConcurrencyIntegrationTests`, `V22CredentialFoundationMigrationMatrixTest`, plus existing login/refresh/logout/user-management/OAuth/OTP suites. New tests cover provenance, legacy promotion, shared lockout, all-family/JWT revocation, stale issuance, credential rotation races, email-proof deletion and both stale admin/profile writer commit orders. No checks were run by the implementation worker.


## M5 password reset implementation

V23 adds `password_reset_challenges` (UUID PK, users FK cascade, unique lowercase-64-hex token digest, email/verified-at/epoch bindings, PENDING/CONSUMED/SUPERSEDED with terminal-timestamp iff-and-after-created checks, `expires_at > created_at`, partial unique pending-per-user, `(user_id, created_at)` and `expires_at` indexes, seven-day cleanup) and `password_reset_abuse_buckets` (64-hex domain-separated HMAC key, one-hour fixed window, saturating attempts 0..2147483647, nullable `last_admitted_at`, `expires_at > window_start`, expiry index, two-hour cleanup). V1–V22 are untouched.

`PasswordResetService` keeps the `issueForEmail` contract and adds `issue` returning the captured delivery address/display name; the digest is HMAC-SHA-256 domain-separated from the data-encryption key, all application timestamps are microsecond-truncated, replacement terminalizes and flushes the predecessor under the user lock before insert, and consumption is user-first/challenge-second with a non-locking digest→owner projection (missing digest resolves via `queryForList` to a plain rejection, never an exception). Consumption rechecks PENDING, expiry predicate, email/verified-at/epoch bindings and ENABLED, rejects current-password reuse without mutation, then consumes and mutates in one transaction; a locked account throws 423 with the challenge left PENDING for retry. The synchronous `AuthorizationProofsInvalidated` listener supersedes pending challenges on password change/reset and admin email reassignment. The trusted origin from `quizopia.password-reset.frontend-base-uri` is validated at startup (absolute http(s), no userinfo/query/fragment, origin-only, HTTPS except non-production loopback HTTP) and the link is `<base>/reset-password#token=<opaque>`.

`POST /api/auth/password/forgot` validates syntax only; every syntactically valid request then runs fixed-order IP-then-email bucket admission in one short transaction (both buckets always evaluated, attempts increment even when denied, `last_admitted_at` advances only on overall admission, elapsed windows reset preserving the last admission), enqueues admitted work on a bounded 2-worker/200-capacity executor, and answers an identical empty 202 with `no-store` for unknown, ineligible, throttled and dropped (saturated) requests. The worker issues via the user-locked transaction and delivers the purpose-specific reset mail after commit through `PasswordResetMailSender` (SMTP transport for production/Mailpit, in-memory capture for tests, failure logs exception-class only). `POST /api/auth/password/reset` returns 204 with a cleared refresh cookie, or the single shared `AUTH_PASSWORD_RESET_INVALID` 400 for invalid/expired/replaced/replayed tokens; both public routes are exact-path permitAll entries.

Verification targets (integrator execution required): `PasswordResetIntegrationTests`, `PasswordResetIntakeIntegrationTests`, `V23PasswordResetMigrationMatrixTest`, plus existing login/refresh/change-password suites. Tests cover the Mailpit-capture happy path with session/JWT invalidation and new-password login, expiry via the mutable clock, replay, replacement, concurrent one-winner reset, the eligibility matrix (unknown, Google-only/DISABLED, unverified, locked, ENABLED), uniform responses including throttle and executor saturation, cooldown/window bucket state machine with cap denial, binding drift (email change, epoch bump, verified-at cleared and re-stamped), lockout retry semantics, no raw token in database or logs, and fresh plus V20→V23 migrations with full schema negatives. No checks were run by the implementation worker.
