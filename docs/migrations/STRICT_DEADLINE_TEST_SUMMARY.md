# Strict Deadline & Sweeper — Targeted Test Summary

Targeted verification of the strict-deadline / timeout-sweeper contract and the surrounding
submit/autosave/grading/concurrency surface. PostgreSQL + Testcontainers, deterministic
(`MutableClock`, `CountDownLatch`); the scheduler bean is disabled and the sweeper is driven
directly. No `Thread.sleep`.

## Command (broad targeted set)

```
docker compose --profile test run --rm backend-test \
  mvn -Dtest='AttemptTimeoutSweeperIntegrationTests,\
AttemptSubmitServiceIntegrationTests,AttemptSubmitConcurrencyIntegrationTests,\
AttemptSubmitRollbackIntegrationTests,AttemptAutosaveServiceIntegrationTests,\
AttemptAutosaveConcurrencyIntegrationTests,V9AttemptConcurrencyIntegrationTests,\
RealtimeAttemptEventConcurrencyIntegrationTests' test
```

## Result

| Class | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| AttemptTimeoutSweeperIntegrationTests | 10 | 0 | 0 | 0 |
| AttemptSubmitServiceIntegrationTests | 49 | 0 | 0 | 0 |
| AttemptSubmitConcurrencyIntegrationTests | 6 | 0 | 0 | 0 |
| AttemptSubmitRollbackIntegrationTests | 1 | 0 | 0 | 0 |
| AttemptAutosaveServiceIntegrationTests | 50 | 0 | 0 | 0 |
| AttemptAutosaveConcurrencyIntegrationTests | 6 | 0 | 0 | 0 |
| V9AttemptConcurrencyIntegrationTests | 2 | 0 | 0 | 0 |
| RealtimeAttemptEventConcurrencyIntegrationTests | 5 | 0 | 0 | 0 |
| **Total** | **129** | **0** | **0** | **0** |

**BUILD SUCCESS.**

## Contract-case evidence

- **Concurrency (manual-vs-sweeper one winner):** `manualSubmitRacingSweeperProducesSingleSubmissionAndGrade` — exactly one submission, one Grade, one GradeItems set; winning key is the manual key or the auto-timeout key (never both). Plus `V9AttemptConcurrencyIntegrationTests` (2), `AttemptSubmitConcurrencyIntegrationTests` (6), `AttemptAutosaveConcurrencyIntegrationTests` (6).
- **Duplicate Grade verification:** `repeatedSweepDoesNotCreateSecondGrade` — `count(grades) == 1` after a repeated sweep.
- **Duplicate GradeItems verification:** `repeatedSweepDoesNotCreateDuplicateGradeItems` — GradeItems count unchanged after a repeated sweep.
- **Duplicate event/notification verification:** `sweeperEmitsExactlyOneGradedNotification` — exactly one `RESULT_GRADED` notification, unchanged after a repeated sweep; plus `RealtimeAttemptEventConcurrencyIntegrationTests` (5) for the realtime event path.
- **Grading-failure rollback (no partial state):** `gradingFailureRollsBackSweeperLeavingNoPartialState` — status stays `IN_PROGRESS`, no grade, on a forced `grades` INSERT failure.
- **Late manual submit rejected (flag-independent):** `lateManualSubmitRejectedIndependentlyOfSweeperFlag` — `ATTEMPT_DEADLINE_EXCEEDED` with the scheduler disabled.
