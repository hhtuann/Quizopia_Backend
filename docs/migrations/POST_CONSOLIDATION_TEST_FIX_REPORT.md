# Post-Consolidation Test Fix Report

After consolidation (V1–V15 → V1–V9) the suite had 29 broken tests (19 failures, 10 errors).
Every one was mapped to a root cause and fixed. No test was weakened, no production FK was
downgraded to CASCADE to please cleanup, and no failure was dismissed as "pre-existing".

## Initial state

1414 tests · 19 failures · 10 errors · 4 skipped (pre-existing `@Disabled`).

## Root-cause inventory

| Category | Count | Root cause |
|---|---|---|
| A — Schema/migration expectation | 5 | tests asserted legacy migration layout (forbidden-table list, `max_attempts>0`, staged-Flyway targets, version refs) |
| B — Notification FK cleanup | 10 | `notifications.user_id` ON DELETE RESTRICT blocked `DELETE FROM users` in shared `@AfterEach` |
| C — Deadline/sweeper contract | 14 | submit/autosave did not enforce the deadline; the sweeper was the only path that finalized on timeout |

## Category A — schema/migration expectations (5)

- `V8ExamSchemaIntegrationTests`: removed `notifications` from the forbidden-table list (now a V9
  object); split `max_attempts` test into `sessionMaxAttemptsNegativeRejected` + new
  `sessionMaxAttemptsZeroAccepted` (`0` = unlimited).
- `V8PurposeSeedStagedFlywayTest`: stage target V7 → V5 (purpose seed runs in V6).
- `V9AttemptSchemaStagedFlywayTest`: stage target V8 → V6 (attempt tables are created in V7).
- `V9AttemptSchemaIntegrationTests`: version refs aligned.
- `UserEncryptedPersonalDataIntegrationTests`: version assertion V11 → V9.

No migration SQL changed — only test expectations realigned to the verified final schema.

## Category B — notification FK cleanup (10)

`QuestionImportServiceIntegrationTests` and `QuestionImportApiIntegrationTests`: added
`DELETE FROM notifications WHERE user_id IN (...)` before the `DELETE FROM user_roles` in
`@AfterEach`. The FK is intentionally RESTRICT (a notification is an audit record); the fix is
correct child-before-parent delete order, not a CASCADE downgrade.

## Category C — strict deadline contract (14)

`AttemptSubmitService.submitAttempt` and `AttemptAutosaveService.saveAnswer` now enforce the
deadline unconditionally (`if (now.isAfter(deadlineAt)) throw ATTEMPT_DEADLINE_EXCEEDED`). The
sweeper uses an internal-only `finalizeAttempt` path (bypasses `submitAttempt`, re-checks status
under lock, `submittedAt = deadlineAt`). See `STRICT_DEADLINE_AND_SWEEPER_CONTRACT.md`.

## Additional fix — flaky student-code fixture

`Day8R4EvidenceTests` and `SessionStatisticsIntegrationTests` generated per-student codes as
`tag + UUID.substring(0,4)` (65536 space). With 50 students sharing a tag the birthday paradox
produced intermittent duplicate-key failures. Widened the random suffix to 8 hex chars
(matching the username entropy) — `VARCHAR(50)` easily accommodates it.

## Final state

1425 tests · 0 failures · 0 errors · 4 skipped — see `FULL_REGRESSION_SUMMARY.md`.
(The count rose from 1414 to 1425: +1 `sessionMaxAttemptsZeroAccepted`, +10 dedicated sweeper
tests in `AttemptTimeoutSweeperIntegrationTests`.)
