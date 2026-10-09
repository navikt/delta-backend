# Make participant changes understandable and concurrency-safe

## In short

Signup, withdrawal and host changes should have one identifiable owner for
their rules. Developers should not need to reconstruct eligibility and calendar
effects from several routes and SQL helpers.

## What to build

Move self-signup, self-withdrawal, organizer removal and role changes behind
cohesive event operations. Own current-state authorization, capacity, deadline
and host rules together with each mutation. Delegate calendar effects through
explicit mode-specific behavior rather than constructing work in HTTP routes.

Reuse the established transaction seam and preserve separate participant
status and role concepts. Cover all writers that affect the relevant invariant,
including webhook reconciliation where it uses the same participant mutation.

## Acceptance criteria

- [ ] Migrated participant routes only parse input, derive the caller, invoke an operation and map its result.
- [ ] Eligibility, role transitions and calendar decisions can be exercised without Ktor.
- [ ] One identifiable owner decides capacity/deadline checks for each invite mode; callers do not duplicate those checks.
- [ ] For two concurrent signups competing for one remaining place, exactly one succeeds and the other receives the existing conflict outcome.
- [ ] Concurrent demotion of the two hosts cannot leave an event with no host.
- [ ] Duplicate concurrent signup produces a deliberate domain/HTTP outcome rather than an unhandled constraint error.
- [ ] Existing withdrawal and last-host behavior is characterized; any policy change beyond the demonstrated demotion race is explicitly approved, not smuggled into extraction.
- [ ] Authorization is checked against state protected by the mutation, not only an earlier route query.
- [ ] Shared invitation consumption, RSVP ordering, capacity reservations, reinvitation phases and notification behavior remain intact.

## Implementation context and proof

Relevant anchors are `/user/event/{id}`, organizer participant routes,
`registerForEvent()`, `changeParticipant()` and
`checkIfEventWillHaveNoHosts()` in the event package.
`SharedCalendarRepository` already contains mode-specific participant rules.

Deterministic probes against actual legacy methods reproduced both capacity
overflow and zero-host concurrent demotion under repeatable read. Use real
PostgreSQL and barriers, not sleeps, for the regression tests.

All same-event invariant writers must participate in the concurrency protocol.
Preserve the shared parent-row write/full-transaction retry mechanism; merely
adding `FOR UPDATE` under repeatable read is insufficient.

Prove operations and their HTTP mappings with `DatabasesTest`,
`EventRoutesTest`, `SharedCalendarRepositoryTest` and relevant webhook tests.
Legacy effects may remain best effort behind an explicit delivery seam.

## Out of scope

New participant policies, changes to invite mode, exactly-once delivery,
durable legacy jobs and broad authentication redesign.
