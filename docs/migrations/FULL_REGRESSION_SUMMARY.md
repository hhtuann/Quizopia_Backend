# Full Backend Regression Summary

Two full backend suite runs against the identical backend source hash, after the strict-deadline
contract, the dedicated timeout-sweeper coverage, the pre-release migration consolidation
(V1–V15 → V1–V9), and the post-consolidation test remediation.

## Command

```
docker compose --profile test run --rm backend-test
```

## Backend source hash (java + sql under `src`)

```
a88dbcc93713894138039dde5372dbe76f9257f0db8edb8ae5031a3ba4958c58   (454 files)
```

No source file changed between the two runs.

## Runs

| Run | Total | Failures | Errors | Skipped | Build | Source hash | Finished (UTC) |
|---|---|---|---|---|---|---|---|
| 1 | 1425 | 0 | 0 | 4 | SUCCESS | `a88dbcc9…` | 2026-07-11T14:14:45Z |
| 2 | 1425 | 0 | 0 | 4 | SUCCESS | `a88dbcc9…` | 2026-07-11T14:25:35Z |

Both runs green and stable — no flakiness. (Test count rose from the pre-checkpoint 1414 to 1425:
+1 `sessionMaxAttemptsZeroAccepted`, +10 `AttemptTimeoutSweeperIntegrationTests`.)

## Skipped (4)

Pre-existing `@Disabled` in `QuestionImportServiceIntegrationTests` (3) and
`QuestionImportApiIntegrationTests` (1) — "question codes are auto-generated; import code-collision
detection removed". Unchanged, justified, not inflated.

## Migration / ORM

- **Flyway:** Successfully applied 9 migrations to schema `public`, now at version v9 (re-validated
  in every test context).
- **Hibernate:** `spring.jpa.hibernate.ddl-auto=validate` (main + test); 0 schema-validation errors.

## Not committed

Full raw logs, schema dumps, and legacy-migration backups are kept locally outside Git (under the
repo-root `docs/migrations/evidence/`, gitignored). Only this condensed summary is version-controlled.
