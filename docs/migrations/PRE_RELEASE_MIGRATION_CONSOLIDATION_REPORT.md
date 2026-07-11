# Pre-Release Flyway Migration Consolidation (V1–V15 → V1–V9)

The project had not been deployed to staging or production; all databases were local/dev and
disposable. Before the public release, the 15-migration history (which had accumulated
ALTER/patch/seed follow-ups) was rewritten into a clean, business-grouped set of 9 migrations
that builds the final schema directly from an empty database, with no patch migrations and no
`flyway repair`.

## Final migration set

| Version | File | Domain |
|---|---|---|
| V1 | `V1__initialize_database.sql` | extensions / base |
| V2 | `V2__create_identity_schema.sql` | users, roles, profiles (+ username + encrypted PII) |
| V3 | `V3__seed_roles_and_permissions.sql` | role/permission seed (+ classroom/admin grants) |
| V4 | `V4__create_academic_schema.sql` | schools, subjects, grade levels (+ school counters) |
| V5 | `V5__create_question_bank_schema.sql` | banks, questions, versions (+ numeric CHECK) |
| V6 | `V6__create_exam_schema.sql` | exams, sessions, questions (+ duration, visibility, `max_attempts>=0`) |
| V7 | `V7__create_attempt_and_grading_schema.sql` | attempts, answers, grades, grade_items |
| V8 | `V8__add_classrooms.sql` | classrooms, members, session-classes |
| V9 | `V9__create_notifications.sql` | notifications |

## Absorption map (legacy → final)

| Legacy migration | Absorbed into |
|---|---|
| V4 (username CHECK), V5 (encrypted PII) | V2 |
| V10 (classroom grants), V11 (admin grants) | V3 |
| V11 (school counters) | V4 |
| V12 (numeric CHECK) | V5 |
| V12 (exam_q CHECK), V14 (`max_attempts>=0`), V15 (duration), V10 (visibility) | V6 |
| V6→V4, V7→V5, V8→V6, V9→V7, V10→V8, V13→V9 | renamed |
| V4-old, V5-old, V11, V12, V14, V15 | deleted (content absorbed) |

## Permission counts (unchanged)

SYSTEM_ADMIN 13 · ACADEMIC_ADMIN 54 · TEACHER 50 · STUDENT 9 · **catalog total 84**

## Verification

- Fresh-DB migration: Flyway applied 9/9, now at version v9.
- Flyway validate (every test context): successfully validated 9 migrations.
- Hibernate: `ddl-auto=validate`, 0 schema-validation errors.
- Schema equivalence: PASS — see `SCHEMA_EQUIVALENCE_REPORT.md`.
- Full backend suite: green — see `FULL_REGRESSION_SUMMARY.md`.

Legacy migration files are preserved locally (outside Git) for audit.
