# Make event creation understandable and atomic

## In short

Developers should be able to understand how an event is created without
untangling HTTP handling, database commits, recurrence and Outlook cleanup.
Failed creation must not leave a partially created event.

## What to build

Create a cohesive event-creation operation that owns business validation,
feature/mode decisions, recurrence selection, persistence and external-effect
coordination. The HTTP route should parse the request, obtain the caller,
invoke that operation and map its result.

Make event, initial host and categories atomic. Keep legacy synchronous
room/Teams orchestration outside retried database transactions, and keep
shared saves asynchronous. Introduce only the transaction/operation seams
needed for this workflow; other workflows can migrate later.

## Acceptance criteria

- [ ] The create route has no recurrence policy, Graph compensation, commit coordination or direct notification-thread creation.
- [ ] Creation rules and outcomes can be exercised without an HTTP request or Ktor `ApplicationCall`.
- [ ] A developer can locate mode selection, initial-host registration and failure cleanup from the creation operation without searching unrelated routes.
- [ ] A failed creation commits no partial event, host or category links; shared outbox intent remains atomic with creation.
- [ ] A deterministic regression covers plain legacy creation with a deadline that rejects host registration.
- [ ] Retried database work does not repeat Graph calls.
- [ ] Endpoint paths, JSON/time formats, response status mappings, feature access and server-managed fields remain compatible.
- [ ] Legacy room/Teams failure retains its synchronous no-save behavior; shared creation retains PENDING synchronization.
- [ ] Existing recurring creation behavior and invitation/room/Teams restrictions remain intact.

## Implementation context and proof

Start with `eventApi` in
`src/main/kotlin/no/nav/delta/event/Routes.kt`, `addEvent()` in
`event/Database.kt`, and `createRecurringEventSeries()` in
`event/RecurringDatabase.kt`.

The legacy route commits event creation, host registration and categories
separately. Its cleanup returns early when no master event exists. A probe of
the actual database sequence reproduced a committed hostless event after
registration failed. This is an explicit correctness fix alongside extraction.

The shared path already offers a useful transaction protocol. Preserve its
parent-row write and full retries; a lock alone does not refresh a stale
repeatable-read snapshot. Put reusable transaction ownership in a neutral
location only when needed, with exactly one commit/rollback owner.
Do not expand the shared repository into a catch-all service.

Use `EventRoutesTest`, `DatabasesTest`, `SharedCalendarRoutesTest` and
`SerializationContractTest`; supplement with operation-level failure tests.
Keep existing mode-specific delivery mechanics behind the operation initially.

## Out of scope

Extraction of all event routes, new creation UX, changing legacy saves to
asynchronous behavior, a generic command bus, an ORM migration and durable
legacy delivery. Preserve and explicitly document the remaining network crash
window rather than claiming that one database transaction solves it.
