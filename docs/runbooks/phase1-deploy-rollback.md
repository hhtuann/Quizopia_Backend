# Quizopia deployment & rollback checklist (Phase 1 release)

Applies to the Phase 1 release heads. Merge order and SHAs are approved by the
Tech Lead's final merge approval BEFORE any step below runs.

## Pre-deploy (every release)

1. Confirm approved HEADs: Backend Task A `1517166` merged first, then Backend
   Task B (`38fa85e` or later approved), then Frontend (`738a81f` or later
   approved), then System PR #3 submodule pointer update (staged merge order —
   no direct main pushes).
2. Backend CI green on the exact final HEAD
   (`gh run list --repo hhtuann/Quizopia_Backend`); suite must show
   `Failures: 0, Errors: 0` — skipped count must be explained, not assumed.
3. Frontend: `tsc --noEmit`, `eslint`, `next build` green; Vercel preview PASS
   on the PR.
4. Secrets present in the target environment (fail-fast if missing):
   `QUIZOPIA_JWT_SECRET_BASE64`, `QUIZOPIA_DATA_ENCRYPTION_KEY_BASE64`,
   `QUIZOPIA_TEACHER_INVITE_CODE`, plus `DB_URL/DB_USERNAME/DB_PASSWORD`,
   `REDIS_*`, `QUIZOPIA_ALLOWED_ORIGINS` (include the real frontend origin),
   `QUIZOPIA_COOKIE_SECURE=true` behind HTTPS.
5. Database backup taken and verified:
   `pg_dump -Fc` → SHA-256 recorded → trial `pg_restore` into a scratch
   database → row-group equality spot check (see phase1-migration.md).

## Backend deploy

1. Stop writers (HTTP pod / service) — maintenance window.
2. Apply the release JAR (`java -jar backend-0.0.1-SNAPSHOT.jar` with the env
   above). Flyway runs V1–V17 on boot; a non-empty `flyway_schema_history`
   must already end at the version this release expects, else STOP.
3. Health gate: `GET /actuator/health` → `{"status":"UP"}`.
4. Auth gate: real login → JWT → `GET /api/auth/me` 200; anonymous
   `GET /api/exams/my` → 401.
5. Restart writers; watch error rate and `flyway_schema_history` tail.

## Frontend deploy (Next.js standalone)

1. `npm run build` WITH `NEXT_PUBLIC_API_URL` set to the browser-reachable
   backend origin (baked at build time — a rebuild is required to change it).
2. Standalone packaging completeness — REQUIRED after every build:
   `copy .next/static → .next/standalone/.next/static` and
   `copy public → .next/standalone/public` (58-file parity check used in P1).
3. Start: `node .next/standalone/server.js` (NOT `next start` — incompatible
   with `output: standalone`).
4. Smoke: document HTTP 200; one `/_next/static/chunks/*.js` asset 200
   (a 200 document with 404 chunks means step 2 was skipped — the app will
   show a perpetual "Loading" screen).

## Audit logging

- `logback-spring.xml` routes `quizopia.security.audit` (SYSTEM_ADMIN grant
  events) to `logs/security-audit.log`: daily+size rollover, 90-day / 512 MB
  cap, gzipped history. `QUIZOPIA_LOG_DIR` overrides the directory.
- Production must ship this directory to the platform log collector and set
  retention ≥ 90 days; console output duplicates audit events for container
  collectors. Retention config alone is not delivery guarantee — verify the
  collector actually persists the file.

## Rollback

1. Stop writers on the failing tier.
2. Application rollback only (no DB rollback): redeploy the previous JAR /
   previous frontend build. Flyway is forward-only — V1–V17 have no reverse;
   the previous JAR must tolerate the current schema version (verify the
   previous release's expected schema ≤ current `flyway_schema_history` tail).
3. Data rollback (last resort, data-affecting failure): restore the verified
   pre-deploy dump into a clean database (`pg_restore --exit-on-error`),
   point the app at it, redeploy the matching previous release, re-run the
   health + auth gates. Never `flyway repair`, never edit applied migrations,
   never drop audit tables.
4. Post-rollback: confirm counts vs the backup evidence, record the incident
   and the failed run's logs before cleanup.
