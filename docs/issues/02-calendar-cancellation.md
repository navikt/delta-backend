# Keep cancelled calendar work recoverable

## In short

Stopping calendar processing must not turn unfinished work into a permanent
business failure. Preserve recoverability when execution is cancelled.

## What to build

Distinguish execution cancellation from reconciliation failures in
`CalendarSyncWorker`. Propagate cancellation before broader exception handlers
and preserve cleanup and claim recovery.

## Acceptance criteria

- [ ] A cancellation raised during processing propagates to the caller or owning coroutine.
- [ ] Cancellation does not mark the event or outbox row FAILED or increment permanent-failure metrics.
- [ ] The event execution lock is released and unfinished work can be reclaimed under the existing lease protocol.
- [ ] Existing permanent and retryable Graph failure classifications remain unchanged.
- [ ] Deterministic tests cover cancellation during an adapter operation and subsequent recovery.

## Implementation context and proof

`src/main/kotlin/no/nav/delta/calendar/CalendarSyncWorker.kt`, `runOnce()`,
catches `IllegalStateException`. On the JVM, `CancellationException` is a
subtype and can enter that handler before the outer cancellation handler.
An injected adapter cancellation reproduced outbox state FAILED.

Use `CalendarSyncWorkerTest` with real repository/claim behavior where recovery
is asserted. Preserve ownership tokens and advisory-lock cleanup.
Do not infer that coroutine cancellation interrupts an already issued blocking
Graph or JDBC call; any added checkpoints operate between effects.

## Out of scope

Scheduler supervision, probe policy, transport cancellation redesign,
subscription management and changes to retry budgets.
