# backend/docs/migrations

Condensed, version-controlled documentation for the database migration set and the strict
deadline/sweeper contract.

## Contents

| File | Purpose |
|---|---|
| `PRE_RELEASE_MIGRATION_CONSOLIDATION_REPORT.md` | How the legacy V1–V15 history was consolidated into the clean V1–V9 set |
| `SCHEMA_EQUIVALENCE_REPORT.md` | Proof that the consolidated V1–V9 schema equals the pre-consolidation schema |
| `POST_CONSOLIDATION_TEST_FIX_REPORT.md` | Root-cause + fix for the post-consolidation test failures |
| `STRICT_DEADLINE_AND_SWEEPER_CONTRACT.md` | The server-side deadline boundary and timeout-sweeper contract |
| `STRICT_DEADLINE_TEST_SUMMARY.md` | Targeted deadline/sweeper/concurrency test results |
| `FULL_REGRESSION_SUMMARY.md` | Two full backend regression runs (same source hash) |

## Consolidation note

Before the public GitHub release, the legacy migration history (V1–V15, which had accumulated
ALTER/patch/seed follow-ups) was rewritten into a single clean, business-grouped set of nine
migrations (V1–V9) that builds the final schema directly from an empty database. This was
permissible because the project had not been deployed to staging or production and all databases
were local/dev and disposable.

Legacy migration files are preserved locally (outside Git) under `evidence/` for audit only.

## Post-release rule

**After the repository is public, released migrations must never be rewritten.** Once a migration
has shipped to a shared environment, editing it breaks every database that already applied it
(Flyway checksum validation fails). Every future schema change must be a new, forward-only
migration with the next version number (V10, V11, …) — additive where possible, with explicit
down/up thought through but never destructive on shared data.

Do not run `flyway repair` to mask a checksum mismatch; fix the migration history properly.
