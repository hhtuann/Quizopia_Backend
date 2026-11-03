# Phase 1 B migration verification (V12 → V17)

Use a restored production backup in staging first. The scripts are verification artifacts,
not Flyway migrations. V1–V17 remain immutable. V14's same-table attempt count comparison
is not preservation evidence; compare independently captured before/after rows instead.

## Preconditions and backup

1. Stop **all** writers (HTTP, workers, timeout sweeper, realtime commands and administrative
   SQL). Keep them stopped until postflight comparison passes. Take a maintenance window.
2. Confirm the starting database is V12; retain `flyway_schema_history` and the application
   revision. For an already upgraded database, restore the pre-upgrade backup into an isolated
   V12 scratch database to recover the before evidence. Do not manufacture a baseline at V17.
3. `pg_dump --format=custom --file=pre-phase1.dump "$DATABASE_URL"`; restore into a disposable
   database with `pg_restore --exit-on-error --dbname="$SCRATCH_URL" pre-phase1.dump` and
   verify the restore. Record backup SHA-256 with `Get-FileHash` (PowerShell) or `sha256sum`.
4. Protect evidence exports like backups: they contain answer keys and learner results.

## Preflight and durable baseline

From the backend root, with PostgreSQL client tools installed:

```sh
PGOPTIONS='-c default_transaction_read_only=on' psql -X -v ON_ERROR_STOP=1 "$DATABASE_URL" -f docs/runbooks/sql/p1-b-preflight.sql
PGOPTIONS='-c default_transaction_read_only=on' psql -X -q -A -t -v ON_ERROR_STOP=1 "$DATABASE_URL" -f docs/runbooks/sql/p1-b-before.sql -o p1-b-before.txt
```

Capture counts by the first column as well as the complete export and its SHA-256. Each row
contains kind, original ID, MD5 of normalized JSONB, and full JSONB payload. Full payload equality
is the gate (MD5 is a convenient diagnostic, not a cryptographic integrity claim).
The preflight rejects orphan content/versions/attempts, sessions on drafts, and mismatched
Attempt→Session or AttemptQuestion→version lineage. Flyway's V14 preflight additionally checks
published invariants; split-code collisions and schema constraints fail the migration transaction.
On **any** anomaly, stop and investigate; never silently repoint a legacy attempt.

## Apply and postflight

Deploy the approved release using the normal Flyway startup path against the staging restore
first, then the production database during the maintenance window. Validate Flyway checksums;
confirm successful history through V17. Do not run `repair` to bypass errors.

```sh
PGOPTIONS='-c default_transaction_read_only=on' psql -X -v ON_ERROR_STOP=1 "$DATABASE_URL" -f docs/runbooks/sql/p1-b-postflight.sql
PGOPTIONS='-c default_transaction_read_only=on' psql -X -q -A -t -v ON_ERROR_STOP=1 "$DATABASE_URL" -f docs/runbooks/sql/p1-b-after.sql -o p1-b-after.txt
diff -u p1-b-before.txt p1-b-after.txt
```

Require exact equality, not just counts or sums. The normalized contract checks original Exam
metadata, every version's content on its mapped Exam, retained audit versions, sections,
questions/options, materialized Session duration and exact original version, Attempt identity
and lineage, AttemptQuestions, Answers, Grades and GradeItems (including scores, timestamps,
JSON and IDs). New split exams are checked via their original version IDs. Also inspect mapping
completeness/canonical IDs with the staged tests and retain all command logs. Only restart writers
after verification and operator sign-off. Exports require a quiescent DB and the exact V12/V17
schemas; they are not a live-data comparison after application traffic resumes.

`exam_version_metadata` checks the full target Exam row per legacy version, including split
title/code, description, workspace/creator, school/subject/teacher/purpose provenance and
creation time. Expected values follow V14's canonical-vs-split transformations. Generated
target IDs are checked via mapping/postflight rather than predicted; only the nondeterministic
split `updated_at` is normalized to null. Canonical `updated_at` remains compared exactly.
The staged fixture contains four real snapshot options across canonical/noncanonical questions;
full option JSONB includes ID, question link, key, content, correctness, position and creation time.

## Recovery

On failed checks, keep writers stopped. Preserve failed database/logs for diagnosis. Restore the
verified backup into a clean database and redeploy the matching previous application release;
validate counts and backup evidence before reopening. There are no reverse migrations. Do not
drop retained audit data, edit applied migrations or retry by disabling constraints.

## Reproducible automated gate

```powershell
.\mvnw.cmd test "-Dtest=P1CheckpointBMetadataIntegrationTests,V14ExamSplitMigrationStagedFlywayTest,V17UserBasedParticipantsStagedFlywayTest"
```

Docker/PostgreSQL 17 are required. The V14 staged test runs the same SQL exports and compares
every ID/checksum/payload for two published versions plus one draft. It also probes draft-session
and orphan-section failure/rollback before the valid V12→latest cutover. Spring integration tests
exercise clean-slate V1→latest. This is fixture proof; production proof requires the backup and
maintenance-window evidence above. Broader backend regression/CI and frontend interaction evidence
remain the subsequent checkpoint C gate.
