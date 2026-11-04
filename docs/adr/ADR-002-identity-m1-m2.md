# ADR-002 — Identity M1/M2: Student-by-default registration, email OTP verification, Google OIDC, teacher self-upgrade

- **Status:** Proposed (implemented on `feature/identity-m1-auth`; M2 on `feature/identity-m2-teacher-mode`)
- **Scope:** System issues #13 (Identity-M1) and #14 (Identity-M2)
- **Supersedes:** none. Extends ADR-001 (personal-workspace provisioning) and the Phase-1 auth batches.

## 1. Identity lifecycle

```
register (public)           verify-email (public)          login (public)
──────────────────          ─────────────────────          ─────────────────
user row (ACTIVE,           OTP challenge PENDING          identifier+password
  email_verified_at=NULL)     → CONSUMED                   → gate: ACTIVE +
+ STUDENT role                → user.email_verified_at=now     email_verified_at≠NULL
+ personal workspace          → login allowed                → Quizopia JWT + refresh cookie
+ OTP challenge (hashed)

Google OIDC login (public)                          teacher self-upgrade (M2, authenticated)
───────────────────────────                         ────────────────────────────────────────
authorization code + PKCE + state + nonce           requires ACTIVE + verified email
→ ID token validated (sig/iss/aud/exp/email_verified)  → insert user_roles (STUDENT kept)
→ external_identities keyed (provider, issuer, sub)   → immediate effect (authorities loaded
→ new: STUDENT + workspace + verified email             from DB on every request — no re-login)
→ collision with existing email: REJECT (no auto-link) → audit row in identity_audit_events
→ one-time opaque code → POST /api/auth/oauth/exchange
```

Rules:

1. **Every new account is STUDENT.** `RegisterRequest` drops `accountType`/`teacherInviteCode`; a
   request that self-selects TEACHER is rejected before any write (`AUTH_TEACHER_REGISTRATION_DISABLED`, 403).
2. **One User ID per human.** Google users and password users are `users` rows; Google-only users get
   a per-account random 256-bit password hash (never reusable, never disclosed) to satisfy the
   NOT NULL constraint — not a "fake password" that can authenticate.
3. **Email verification is orthogonal to academic pending-student.** Verification state lives in
   `users.email_verified_at`; the academic "awaiting school assignment" state remains "no
   student_profiles row" (V11 behavior, unchanged).
4. **Teacher upgrade adds, never replaces:** `roles = [STUDENT] → [STUDENT, TEACHER]`. Legacy
   Teacher-only accounts are not granted STUDENT (issue #14).

## 2. Email verification lifecycle (OTP)

- OTP = 6 decimal digits from `SecureRandom`, sent as plain text mail (no HTML), never logged.
- Stored as **HMAC-SHA256(otp, K_otp)** where `K_otp = HMAC-SHA256(K_enc, "quizopia.otp.hmac.v1")`
  — domain-separated derivation from the existing 32-byte encryption key. No new secret to operate.
- Comparison constant-time (`MessageDigest.isEqual`). Expiry 10 min (default). Max 5 failed
  attempts per challenge → challenge FAILED (must resend). One PENDING challenge per user per
  purpose (partial unique index) — **resend supersedes** the previous challenge, so at most one
  OTP is valid at any time.
- Rate limits (DB-backed, transactional): max 5 challenges per email / hour, max 20 per IP / hour
  (register + resend), configurable. Unverified-verify attempts are bounded by per-challenge cap.
- **Idempotent verification:** if `email_verified_at` is already set, verify returns success
  without touching the challenge. The verify path locks the challenge row (`SELECT ... FOR UPDATE`)
  so concurrent submissions of the same OTP yield exactly one CONSUMED transition.
- Login/refresh gate: `AUTH_EMAIL_NOT_VERIFIED` is returned **only after** the password verifies,
  to avoid leaking account existence through a distinguishable pre-password error.
- Delivery: transactional write of user+challenge, mail sent **after commit** (best effort).
  SMTP failure does not activate/skip anything; the resend endpoint is the recovery flow.
  The register response reports `emailSent` so the UI can prompt resend immediately.

## 3. OAuth identity mapping & linking rules

- `external_identities` unique key = `(provider, issuer, subject)`. **Email is never the key.**
- Same issuer+sub must resolve to the same user id on every login — enforced by lookup-then-insert
  under the unique index (race loser re-reads).
- **No auto-linking on email collision.** If the Google email matches an existing password account,
  the OIDC login is rejected (`AUTH_OAUTH_EMAIL_CONFLICT`, redirect error state). Linking an
  existing account requires an authenticated (re-)verified flow that ships with the Profile
  feature ("Hồ sơ") — explicitly out of M1 scope.
- New Google users: verified email only when the ID token carries `email_verified=true`;
  otherwise the login is rejected (`AUTH_OAUTH_EMAIL_NOT_VERIFIED`).

## 4. OIDC security contract

- Standard Spring Security OAuth2 Client authorization-code flow; client registration exists only
  when `QUIZOPIA_GOOGLE_CLIENT_ID/SECRET` are configured (otherwise the endpoint and the UI button
  are absent/disabled).
- **PKCE (S256) and nonce on every authorization request** (customizer), **state** validated by
  Spring Security.
- ID token validation in a dedicated Nimbus-based validator (JWKS from the issuer, cached):
  signature, `iss`, `aud` (client id), `exp`, `iat` skew, `nonce`, `azp` when multiple audiences,
  and `email_verified` policy above. Errors fail closed.
- **No tokens in URLs.** The success handler mints a single-use, 60-second, opaque 256-bit
  authorization code (stored hashed), and redirects to the frontend as
  `/auth/callback?code=<opaque>`. The frontend exchanges it at `POST /api/auth/oauth/exchange`
  for the Quizopia `TokenResponse` + HttpOnly refresh cookie (same shape as password login).
- Client secret only in backend env. No `redirect_uri` whitelisting surprises: redirect URI is
  `{backend}/api/auth/oauth/google/callback`.

## 5. Database migration strategy

Forward-only, V18+ (V1–V17 untouched). Testcontainers recreates from scratch each run.

- **V18 (M1, amended after the first Tech Lead review — review M1-03):**
  - `users.email_verified_at TIMESTAMPTZ NULL` — a REAL ownership proof only (OTP success,
    trusted OIDC provider, or a completed account link). Legacy accounts are NOT grandfathered.
  - `users.login_requires_email_verification BOOLEAN NOT NULL DEFAULT TRUE`, set to FALSE for
    all rows existing at migration time: login compatibility for legacy accounts, kept strictly
    separate from the ownership proof (see §6).
  - `email_verification_challenges` (id, user_id, purpose, otp_hash, status, attempts,
    expires_at, superseded_at/consumed_at/failed_at, request_ip, created_at; partial unique
    `(user_id, purpose) WHERE status='PENDING'`; terminal rows retained so verification is
    proof-bound and idempotent retries of the consumed CORRECT code still succeed).
  - `external_identities` (id, user_id, provider, issuer, subject, email_at_link, linked_at,
    created_at; unique `(provider, issuer, subject)`).
  - `oauth_login_codes` (id, code_hash, user_id, expires_at, consumed_at, created_at; unique
    `code_hash`).
  - `oauth_link_requests` (id, user_id, state_hash, code_verifier, nonce, expires_at,
    consumed_at; unique `state_hash`) — server-side state binding the authenticated Google
    LINKING flow (review M1-06) to one re-authenticated account.
  - The amendment was made in-place on the feature branch: V18/V19 had only ever been applied
    to ephemeral Testcontainers databases (the long-lived compose database runs main/V17), so
    no preserved environment can hit a checksum mismatch.
- **V19 (M2):** `identity_audit_events` (id, user_id, event_type, created_at, ip, user_agent,
  details JSONB) — records `TEACHER_SELF_UPGRADE` (and idempotent repeats) with no secrets.
- Expiration/cleanup: lazily on access + scheduled sweeper (`quizopia.email-verification.cleanup.*`,
  disabled under test profile, mirrors the attempt-timeout sweeper pattern).

## 6. Legacy compatibility

- **Login compatibility ≠ ownership proof (review M1-03):** pre-M1 accounts keep signing in
  with their old credentials (`login_requires_email_verification = FALSE`), but their email
  stays UNPROVEN (`email_verified_at = NULL`) until they complete a real OTP verification via
  the resend flow. Ownership-requiring actions — teacher self-upgrade (M2), Google account
  linking, M3 roster auto-linking — read ONLY `email_verified_at`, never the compatibility
  flag. A completed Google link also sets `email_verified_at` (the provider asserted the same
  address under re-authentication).
- No token_version bump, no refresh-session changes: existing JWTs/cookies keep working.
- Demo seeder accounts are written with `email_verified_at` set AND the login gate flag FALSE
  (they are operator-seeded and password-authenticated).
- `AUTH_ACCOUNT_PENDING` (academic) semantics unchanged.
- Teacher invite code: `QUIZOPIA_TEACHER_INVITE_CODE` and `SecurityProperties.TeacherInvite`
  become obsolete with student-only registration and are removed; the env var is ignored if
  still supplied (documented in `.env.example`).

## 7. Threat model (M1/M2 additions)

| Threat | Mitigation |
|---|---|
| OTP brute force | 6-digit HMAC + 5 attempts/challenge + 5 challenges/email/h + 20/IP/h |
| OTP replay | one PENDING challenge; CONSUMED transition under row lock |
| OTP leak via logs | OTP only in mail body; hash at rest; logger denylist |
| Account enumeration (login) | verify gate after password check |
| Account enumeration (OTP) | success is proof-bound: unknown email, verified+random OTP and unverified+wrong OTP all answer AUTH_OTP_INVALID after a constant-time dummy compare; only knowledge of the code yields 200 |
| Failed-attempt rollback | wrong-OTP counters and FAILED transitions commit in their own short transaction before the error is thrown; login lockout uses an atomic committed UPDATE |
| SMTP failure abuse | user never activated by delivery; resend is the only recovery |
| Registration race (dup username/email) | unique lower() indexes + `DataIntegrityViolationException` → 409 mapping |
| OAuth CSRF/mix-up | state (framework), nonce (validated), single registered provider, exact redirect URI |
| OAuth token leakage | tokens only in POST body + HttpOnly cookie; opaque 60s one-time exchange code |
| Google email collision takeover | login flow rejects; linking requires JWT + password re-auth + state/PKCE/nonce + email equality |
| Linking CSRF / session substitution | single-use 5-minute state bound to the authenticated user, consumed under a row lock |
| Partial OAuth configuration | provider disabled unless BOTH client id and secret are set; redirect origin must be CORS-allowed or startup fails |
| Unverified email privilege | login and refresh gated; teacher upgrade requires verified email |
| Role escalation via client | register ignores roles; upgrade endpoint takes no role parameter, grants only TEACHER |
| Frontend role spoofing | activeView is UI preference only; every API re-checks DB authorities |

## 8. M2 authorization boundary

The backend remains the boundary: `QuizopiaJwtAuthenticationConverter` reloads effective
roles/permissions from the database on **every** request, so a fresh TEACHER grant applies
immediately to existing access tokens — no logout/login, no token reissue required. The
`/api/auth/me` response reflects the new role set; the frontend re-fetches it after upgrade.

Known M2 limitation (documented, deferred to M3 per issue #14): `ClassroomService` resolves a
`TeacherProfile` and an organization workspace; self-upgraded teachers without a school
assignment will see a limited Classes surface instead of raw 500s. No schema or permission
changes are made to Classroom in M2.

## 9. Test strategy

- OTP capture: production `SmtpOtpMailSender` (`quizopia.mail.mode=smtp`); tests run with
  `quizopia.mail.mode=capture` where the sender records OTPs in an in-memory mailbox (no SMTP
  dependency in CI). Mailpit smoke (real SMTP) runs in the local E2E phase via the Mailpit
  HTTP API.
- OIDC: unit tests mint RSA-signed ID tokens against an injected immutable JWK set (signature /
  iss / aud / exp / nonce / email_verified failure cases) + MockMvc tests of the authorization
  endpoint (state + PKCE S256 present) + service tests for identity mapping/collision. Live
  Google E2E stays **BLOCKED** until real credentials exist.
- Concurrency: duplicate registration race, concurrent OTP verification, concurrent teacher
  upgrade — via executor + MockMvc against the Testcontainers Postgres.
