# Make event deletion and calendar cleanup easy to follow

## In short

Developers should be able to explain what deletion removes and what calendar
cleanup it triggers. HTTP handlers should not assemble cancellation closures
or decide best-effort cleanup behavior.

## What to build

Introduce explicit single-event and UPCOMING deletion operations. They own
current-state authorization, deletion scope, necessary pre-delete snapshots,
transactional removal and mode-specific cleanup decisions.

Keep external effects outside retried database transactions. Make cleanup
results and existing best-effort semantics explicit rather than discarding
errors into empty success-shaped callbacks.

## Acceptance criteria

- [ ] Delete routes only parse scope/identity, call the operation and map its outcome.
- [ ] Authorization, scope and cleanup decisions can be tested without Ktor.
- [ ] Legacy participant calendar IDs and required event data are collected before cascade deletion.
- [ ] Single and UPCOMING deletion affect only the intended events; series metadata remains consistent.
- [ ] Shared cancellation intent survives event deletion in the same transaction.
- [ ] Legacy master deletion retains its documented best-effort success semantics while failures remain explicitly reported through standard safe logging/outcomes.
- [ ] No error is silently converted into an empty cleanup callback; expected absence and failed lookup are distinguishable.
- [ ] Retried database work does not resend cancellation or email effects.
- [ ] A developer can identify remaining legacy cleanup failure/restart limits from the operation's documented contract.

## Implementation context and proof

Start with `DELETE /admin/event/{id}`, its UPCOMING branch,
`deleteMasterBestEffort()` and cancellation callbacks in `event/Routes.kt`,
plus `deleteRecurringSeriesFromOccurrence()` in `event/RecurringDatabase.kt`.

Use `EventRoutesTest`'s master-deletion and best-effort-failure cases,
`DatabasesTest` and shared deletion/worker tests. Add operation-level tests for
lookup failure, recipient snapshots and scope.

Preserve the applied V28 shared-calendar deletion trigger. The new operation
must not replace it with iterating over a returned list of events.
Do not imply that a successful legacy HTTP deletion confirms Outlook cleanup.

## Out of scope

Recurrence shortening through editing, converting best-effort legacy deletion
to a new HTTP contract, durable legacy cancellation and exactly-once mail.
