# Strict Deadline & Timeout Sweeper Contract

The server-side exam timer has two cooperating parts: the **synchronous deadline check**
(enforced on every manual submit and autosave) and the **timeout sweeper** (a background
backstop that auto-submits attempts left running after the deadline). They are deliberately
decoupled: toggling the sweeper never changes what the submit/autosave API accepts.

## Boundary (server clock is the source of truth)

```text
now < deadlineAt:
    client submit/autosave allowed

now == deadlineAt:
    client submit/autosave allowed   (the deadline is the last permitted instant)

now > deadlineAt:
    client submit/autosave rejected  (409 ATTEMPT_DEADLINE_EXCEEDED)
```

The sweeper selects attempts with `deadlineAt < now` (strictly after), so at `now == deadlineAt`
the attempt still belongs to the student; the sweeper defers until the deadline has actually
passed.

## Rules

1. **Submit always checks the deadline** — `AttemptSubmitService.submitAttempt`, after acquiring
   the attempt lock: `if (now.isAfter(deadlineAt)) throw ATTEMPT_DEADLINE_EXCEEDED`.
2. **Autosave always checks the deadline** — `AttemptAutosaveService.saveAnswer`, same guard.
3. **The sweeper flag toggles only the scheduler bean**, never the API semantics. The flag is
   `quizopia.attempt.timeout-sweep.enabled` (a `@ConditionalOnProperty` on
   `AttemptTimeoutScheduler`). It is not read inside `submitAttempt` / `saveAnswer`.
4. **Late client write is rejected** when server time is past the deadline — regardless of the
   sweeper flag.
5. **The sweeper finalizes via an internal-only path** — `AttemptSubmitService.finalizeAttempt`
   does not call `submitAttempt`, so the synchronous deadline check does not gate it.
6. **The sweeper does not fake a manual submit** to bypass validation — it has its own locked,
   status-rechecked path.
7. **Timeout `submittedAt = deadlineAt`** — the attempt is recorded as submitted when the allotted
   time expired, not when the sweep runs.
8. **Manual submit racing the sweeper yields exactly one submission, one Grade, one GradeItems
   set.** Both lock the attempt row (`findByIdForUpdate`); the loser sees the new state and
   no-ops (sweeper) or gets `ATTEMPT_ALREADY_SUBMITTED` (manual). The
   `uk_attempts_student_submit_key` unique constraint is the final guard.
9. **No hidden grace period.** The check is `isAfter` (strict) with no slack.
10. **Client write rights are not gated on the scheduler's 30-second cycle.** The synchronous
    check is independent of the `@Scheduled(fixedDelay = 30_000)` poll.

## Evidence (test class → contract case)

All cases are covered by `AttemptTimeoutSweeperIntegrationTests` (PostgreSQL + Testcontainers,
deterministic via `MutableClock` and `CountDownLatch`; the scheduler bean is disabled in the test
profile and the sweeper is driven directly).

| # | Contract case | Test method |
|---|---|---|
| 1 | Expired attempt auto-submitted | `expiredAttemptIsAutoSubmittedWithDeadlineAsSubmittedAt` |
| 2 | Active attempt not selected | `activeAttemptNotSelectedBySweeper` |
| 3 | Exact deadline handled per contract | `exactDeadlineNotSelectedBySweeper` (+ `AttemptSubmitServiceIntegrationTests.exactDeadlineAccepted`) |
| 4 | Repeated sweep → no second submission | `repeatedSweepDoesNotCreateSecondSubmission` |
| 5 | Repeated sweep → no second Grade | `repeatedSweepDoesNotCreateSecondGrade` |
| 6 | Repeated sweep → no duplicate GradeItems | `repeatedSweepDoesNotCreateDuplicateGradeItems` |
| 7 | Manual-vs-sweeper race → one winner | `manualSubmitRacingSweeperProducesSingleSubmissionAndGrade` |
| 8 | No duplicate notification in timeout flow | `sweeperEmitsExactlyOneGradedNotification` |
| 9 | Grading failure rolls back atomically | `gradingFailureRollsBackSweeperLeavingNoPartialState` |
| 10 | Late manual submit rejected (flag-independent) | `lateManualSubmitRejectedIndependentlyOfSweeperFlag` |

Additional boundary coverage: `AttemptSubmitServiceIntegrationTests.exactDeadlineAccepted` /
`afterDeadlineRejected409`, and `AttemptAutosaveServiceIntegrationTests.exactDeadlineAccepted` /
`afterDeadlineRejected409`.
