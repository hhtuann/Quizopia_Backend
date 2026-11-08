# Runbook: Community Moderator Provisioning (ADR-006 §5, issue #42)

Moderation power in Quizopia is an **explicit, operator-provisioned, revocable
per-user grant**. There is no self-service API, no admin role, and no code path
that grants or revokes the moderator capability: `POST/GET/…` on
`/api/community/moderation/**` can only *use* a grant. Provisioning happens
exclusively through this document, executed by credentialed DB operators.

The grant **is** the `user_roles` row for the dormant `COMMUNITY_MODERATOR` role
(seeded by V26, granted to nobody). Authorization checks resolve the effective
`COMMUNITY_MODERATION` permission live from the database on every request, so:

- **grant lag is zero** — the grant applies to the moderator's next request;
- **revocation lag is zero** — deleting the row (or its `expires_at` passing)
  denies the very next request;
- expiry follows the normal `user_roles` semantics (`expires_at IS NULL` or
  strictly greater than `now`).

## 0. Conventions

- Every SQL block below is executable **verbatim** after substituting the
  `:placeholder` variables (psql: `psql -v moderator_user_id=42 …` or literal
  replacement). This runbook is an acceptance-tested artifact:
  `CommunityModeratorRunbookIntegrationTests` executes these exact blocks.
- All timestamp arithmetic uses the database clock (`now()`).
- Never grant `COMMUNITY_MODERATOR` through any application API — there is none.

### Variables

| Placeholder | Meaning |
|---|---|
| `:operator_username` | login name for the NEW dedicated operator account (one-time setup) |
| `:operator_email` | operator account email (internal, never public) |
| `:operator_password_hash` | strong bcrypt/argon2 hash for the operator account |
| `:operator_user_id` | id of the dedicated operator account (after step 1) |
| `:moderator_user_id` | id of the user receiving/losing the moderator grant |
| `:grant_reason` | human-readable audit reason (≤500 chars, no trailing `;`) |
| `:revoke_reason` | human-readable audit reason (≤500 chars, no trailing `;`) |
| `:expires_at` | bounded-expiry timestamp (optional variant only) |

## 1. One-time: the dedicated operator account

The operator acts through a **dedicated ACTIVE platform account that holds no
roles at all** — not `COMMUNITY_MODERATOR`, not `TEACHER`, not `STUDENT`. Its
only function is to be the `actor_user_id` on `MODERATOR_GRANTED` /
`MODERATOR_REVOKED` audit rows, so the audit trail attributes operator actions
to the operator, never to a moderator or content account.

```sql
-- runbook-step: operator-account
-- One-time: create the dedicated operator account (ACTIVE, role-less).
-- String variables are quoted IN the SQL (psql -v substitutes bare text).
INSERT INTO users (username, email, password_hash, display_name, status,
                   email_verified_at, token_version, failed_login_attempts,
                   password_changed_at)
VALUES (':operator_username', ':operator_email', ':operator_password_hash',
        'Community Operator', 'ACTIVE', now(), 0, 0, now());
```

Resolve and record the operator id (used as `:operator_user_id` below):

```sql
-- runbook-step: resolve-operator
SELECT id FROM users WHERE username = ':operator_username';
```

## 2. Preconditions (run before every grant/revoke)

```sql
-- runbook-step: preconditions
-- The moderator candidate exists and is ACTIVE (inactive accounts cannot
-- pass the moderator gate even with the grant).
SELECT u.id, u.username, u.status
FROM users u
WHERE u.id = :moderator_user_id AND u.status = 'ACTIVE';

-- The operator account holds NO roles (least privilege — especially no
-- COMMUNITY_MODERATOR, TEACHER or STUDENT). MUST return zero rows.
SELECT r.code
FROM user_roles ur
JOIN roles r ON r.id = ur.role_id
WHERE ur.user_id = :operator_user_id;

-- The dormant role exists and carries exactly the COMMUNITY_MODERATION
-- permission (V26 seed invariant).
SELECT p.code
FROM role_permissions rp
JOIN roles r ON r.id = rp.role_id
JOIN permissions p ON p.id = rp.permission_id
WHERE r.code = 'COMMUNITY_MODERATOR' AND p.code = 'COMMUNITY_MODERATION';
```

## 3. Grant procedure

Default policy: **no expiry** (`expires_at` omitted → NULL → valid until
revoked). A bounded expiry is optional (§3b).

```sql
-- runbook-step: grant
-- 1. Grant the dormant role (idempotent; assigned_by pins the operator).
INSERT INTO user_roles (user_id, role_id, assigned_by)
SELECT :moderator_user_id, r.id, :operator_user_id
FROM roles r
WHERE r.code = 'COMMUNITY_MODERATOR'
  AND NOT EXISTS (
      SELECT 1 FROM user_roles ur
      WHERE ur.user_id = :moderator_user_id AND ur.role_id = r.id
  );

-- 2. Append the MODERATOR_GRANTED audit row (actor = the operator account).
INSERT INTO community_moderation_actions
    (actor_user_id, publication_id, report_id, action,
     from_status, to_status, from_moderation_state, to_moderation_state,
     reason, correlation_id)
VALUES (:operator_user_id, NULL, NULL, 'MODERATOR_GRANTED',
        NULL, NULL, NULL, NULL, ':grant_reason', NULL);
```

## 4. Grant verification

```sql
-- runbook-step: verify-grant
-- The effective permission now resolves for the moderator (live check —
-- identical semantics to the per-request authorization query).
SELECT DISTINCT p.code
FROM role_permissions rp
JOIN roles r ON r.id = rp.role_id
JOIN permissions p ON p.id = rp.permission_id
JOIN user_roles ur ON ur.role_id = r.id
WHERE ur.user_id = :moderator_user_id
  AND (ur.expires_at IS NULL OR ur.expires_at > now())
  AND p.code = 'COMMUNITY_MODERATION';

-- The audit trail recorded the grant with the operator as actor.
SELECT a.action, a.actor_user_id, a.reason
FROM community_moderation_actions a
WHERE a.action = 'MODERATOR_GRANTED' AND a.actor_user_id = :operator_user_id
ORDER BY a.id DESC;
```

Functional check (operator or the moderator): `GET /api/community/moderation/grant`
with the moderator's token must return `200 {"granted":true}` — effective on the
next request, no re-login needed.

## 5. Revoke procedure (and rollback of a mistaken grant)

Revocation is the rollback. It is immediate: the moderator's next request fails
`403 COMMUNITY_ACCESS_DENIED`.

```sql
-- runbook-step: revoke
-- 1. Remove the grant (takes effect on the moderator's NEXT request).
DELETE FROM user_roles ur
WHERE ur.user_id = :moderator_user_id
  AND ur.role_id = (SELECT r.id FROM roles r WHERE r.code = 'COMMUNITY_MODERATOR');

-- 2. Append the MODERATOR_REVOKED audit row (actor = the operator account).
INSERT INTO community_moderation_actions
    (actor_user_id, publication_id, report_id, action,
     from_status, to_status, from_moderation_state, to_moderation_state,
     reason, correlation_id)
VALUES (:operator_user_id, NULL, NULL, 'MODERATOR_REVOKED',
        NULL, NULL, NULL, NULL, ':revoke_reason', NULL);
```

## 6. Revoke verification

```sql
-- runbook-step: verify-revoke
-- MUST return remaining_grants = 0.
SELECT count(*) AS remaining_grants
FROM user_roles ur
JOIN roles r ON r.id = ur.role_id
WHERE ur.user_id = :moderator_user_id AND r.code = 'COMMUNITY_MODERATOR';

-- The audit trail recorded the revocation with the operator as actor.
SELECT a.action, a.actor_user_id
FROM community_moderation_actions a
WHERE a.action = 'MODERATOR_REVOKED' AND a.actor_user_id = :operator_user_id
ORDER BY a.id DESC;
```

Functional check: `GET /api/community/moderation/grant` with the moderator's
token must now return `403`.

## 3b. Optional: bounded-expiry grant

Time-boxed moderation (e.g. a review sprint). Same semantics as §3 plus
`expires_at`; the permission expires automatically — no revoke needed (but an
explicit revoke + audit row is still recommended for the record).

```sql
-- runbook-step: grant-with-expiry
-- Bounded-expiry variant: the grant dies at :expires_at (timestamptz).
INSERT INTO user_roles (user_id, role_id, assigned_by, expires_at)
SELECT :moderator_user_id, r.id, :operator_user_id, :expires_at
FROM roles r
WHERE r.code = 'COMMUNITY_MODERATOR'
  AND NOT EXISTS (
      SELECT 1 FROM user_roles ur
      WHERE ur.user_id = :moderator_user_id AND ur.role_id = r.id
  );
```

## 7. Invariants (why this procedure is shaped this way)

- **Audit actor integrity** — `community_moderation_actions.actor_user_id` on
  GRANT/REVOKE rows is always the dedicated operator account; application
  moderation rows (HIDE/RESTORE/…) carry the acting moderator's id.
- **Append-only** — the audit table has no update/delete surface anywhere in
  the application; GRANT/REVOKE rows are inserted only here.
- **No dormant-role leak** — V26 ships `COMMUNITY_MODERATOR` granted to
  nobody; this runbook is the only door.
- **Dual-role moderators are fine** — the gate checks the effective
  `COMMUNITY_MODERATION` permission, not role exclusivity.
- **Session invalidation is unnecessary** — authorization re-resolves from the
  DB per request; tokens never carry moderation authority.
