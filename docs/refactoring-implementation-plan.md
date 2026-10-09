# Make event changes atomic and calendar delivery recoverable

## In short

Keep Kotlin, Ktor, PostgreSQL and the existing deployment shape. Prioritize
correct event mutations and recoverable calendar work over reorganizing files.
The target is a modular monolith whose HTTP handlers do not coordinate commits,
background delivery or recovery.

This is a proposed implementation plan, researched on 2026-10-09, not an
approved behavior change or an ADR. Application code has not been changed.
The owning team decides the rollout and the explicit contract gates below.

**Priority update:** Subsequent discussion established developer comprehension
and onboarding as the primary problem. The readability-first issue drafts in
[`issues/README.md`](issues/README.md) supersede the ranking and delivery order
below. Keep this document as supporting research and a proposed recovery
roadmap, not as a prerequisite to the readability work.

## Evidence and priorities

Ranking considers demonstrated integrity failures, recovery risk, breadth of
affected changes and migration effort. File size alone is not a criterion.

| Priority | Refactoring | Evidence and consequence | Relative effort |
| --- | --- | --- | --- |
| 1 | Give event mutations one transaction and concurrency owner | Actual legacy methods accepted two signups with capacity one and allowed both hosts to be demoted. Legacy creation can leave a committed event without a host. | Medium |
| 2 | Persist legacy calendar effects and deletion snapshots | Threads and acknowledged webhook coroutines hold work only in memory. Shortening a legacy series deletes calendar references without returning cancellation data. | Large |
| 3 | Own background execution, cancellation and retry eligibility | An injected cancellation was persisted as permanent failure. Scheduler jobs have no retained lifecycle handle; new intent can reset a throttled job's eligibility. | Medium |
| 4 | Introduce caller-shaped Graph capabilities and explicit adapter contracts | Room and directory consumers need small subsets of a broad interface. Availability mapping relies on position, and paging completion can be ambiguous. | Small to medium |

Two small correctness fixes should precede the larger refactorings:
invite-only shared edits must enqueue outgoing work, and cancellation must not
be classified as a permanent calendar failure.

### Demonstrated failures

An isolated probe invoked the repository's compiled production methods against
disposable PostgreSQL 15.19, using no production connection or real Graph calls.
The capacity and demotion probes used barriers after the real check queries,
not timing-dependent sleeps. The other probes applied the repository's actual
Flyway migrations.

| Probe | Observed result | Repository evidence |
| --- | --- | --- |
| Two legacy signups with limit one | Both succeeded; two participant rows committed | `event/Database.kt:544-581,953-977` |
| Concurrent demotion of the two legacy hosts | Both succeeded; zero hosts remained | `event/Database.kt:583-607,1001-1023` |
| Legacy create database sequence with expired signup deadline | Event committed; host registration failed; event remained hostless | `event/Database.kt:36-86`; route composition and conditional compensation at `event/Routes.kt:251-300` |
| Shorten legacy recurrence with an existing tail calendar ID | Tail and participant calendar reference deleted; returned mutation result contained only retained occurrences | `event/RecurringDatabase.kt:238-248,284-288`; notification consumer at `event/Routes.kt:419-429,923-948` |
| Invite-only update to an already synchronized shared event | INVITED row persisted without pending outbox work | `calendar/SharedCalendarRepository.kt:126-157` |
| Inject cancellation during shared calendar creation | `runOnce()` returned and outbox state became FAILED | `calendar/CalendarSyncWorker.kt:48-71` |

Source paths above are relative to `src/main/kotlin/no/nav/delta`.
The partial-create probe exercises the database sequence, not an HTTP request;
the route inspection establishes why the no-master case skips cleanup.
These are reproducible application failure modes, not evidence of their
frequency in production. Production is configured for PostgreSQL 14; the
relevant isolation guarantees were also checked against its documentation.

Existing regression baselines passed: 67 mutation/application/serialization
tests and 135 calendar/Graph/webhook/room/directory tests. Passing tests do not
cover the six demonstrated schedules above or prove Exchange delivery behavior.

### Preserve the stronger shared design

Do not replace the shared-calendar implementation with a generic queue or
remove its concurrency mechanisms during extraction:

- `SharedCalendarRepository.mutate()` locks and actually updates the parent
  event before participant checks, then retries the whole transaction on
  `40001`/`40P01` (`SharedCalendarRepository.kt:609-620,745-761`).
- That write is intentional. A row lock alone does not refresh a stale
  repeatable-read snapshot; the write conflict and full retry are essential.
- Claims, replacement-token fencing and a live session advisory lock serve
  different purposes (`SharedCalendarRepository.kt:194-254,596-606`).
- V28's deletion trigger preserves shared cancellation intent independently
  of returned mutation results, including recurring-tail deletion
  (`src/main/resources/db/migration/V28__shared_calendar_invitations.sql:61-78`;
  `SharedCalendarRepositoryTest.kt:272-294`).
- Creation snapshots, transaction IDs, reinvitation phases, independent
  reconciliation/detail revisions and delivered-detail checkpoints preserve
  recovery behavior. Keep them until tests prove an equivalent replacement.

## Constraints

- Preserve endpoint paths, JSON shapes, status mappings, local date/time
  representation, M2M restrictions and visibility rules.
- Keep existing events' persisted invite mode. A feature flag selects the
  mode of new events; it does not migrate old ones.
- Preserve the difference between legacy synchronous room/Teams saves and
  shared PENDING/SYNCED/FAILED saves. Do not make legacy saves asynchronous as
  an incidental refactoring.
- Preserve room/Teams omission semantics and attendee-only versus detail
  updates. Shared synchronization cannot be suppressed with
  `sendNotificationEmail`; legacy suppression must retain its existing scope.
- Make database changes and delivery intent atomic, not database and Graph.
  Never put Graph or mail operations inside a retried database transaction.
- No microservice split, Kafka, ORM migration, generic command bus or new
  dependency-injection framework is required.

## Proposed structure and alternatives

### Event mutations

**A: A cohesive event-command module with mode-specific delivery policies.**
Named operations own fresh authorization, validation, concurrency and commit.
HTTP handlers parse requests, derive a trusted actor, call the operation and
map the result. Read queries can remain separate. SQL helpers receive the
operation's connection and never acquire another connection or commit.

**B: Expand `SharedCalendarRepository` to own legacy mutations too.**
This reuses the current entry points sooner, but combines already substantial
shared reconciliation/outbox logic with different legacy semantics.

Recommend A incrementally. Extract only responsibilities that disappear from
callers; do not add forwarding-only interfaces or a new giant coordinator.
Retain the current shared repository as an implementation while migrating.

For concurrency, prefer the existing repeatable-read parent-write/full-retry
protocol over a global isolation change. Serializable transactions are a real
alternative, but require consistent participation and retry handling across
all relevant writers. Every invariant-affecting operation must participate:
signup, withdrawal, invitation, role changes, RSVP reconciliation, capacity
edits, deletion and recurring edits. Use deterministic lock ordering for
series-wide operations and re-read authorization in the transaction.

Move the existing transaction mechanism into a neutral persistence location
when needed; recurrence currently imports it from `calendar` and also commits
inside the transaction block (`RecurringDatabase.kt:14,83,284,336`).
Give commit/rollback exactly one owner. Do not replace intentional parent
writes with lock-only checks.

### Durable legacy delivery

**A: A legacy-specific durable effect store using existing transaction/runtime
primitives.** Store per-recipient outcomes and deletion snapshots explicitly.
Keep the shared outbox and its specialized state machine intact.

**B: Extend the shared per-event outbox to represent both calendar modes.**
This reduces infrastructure duplication, but requires mode-specific recipient
checkpoints, snapshots and recovery rules in an already complex state machine.

Prefer A unless a schema/design spike proves B is simpler without losing
per-recipient recovery. Avoid a generic serialized-task framework. Pure
business rules and a small transaction helper can be shared without forcing
both modes into the same state machine.

### Graph capabilities

**A: Required, consumer-shaped interfaces backed initially by the same Azure
client and transport.** Room catalog loading, live availability and people
search have distinct callers and policies. Calendar/mail/subscription seams
can migrate as their consumers need them.

**B: Keep the broad interface and split only private SDK implementation files.**
This improves navigation but does not reduce fake implementation burden or
make missing capabilities compile-time visible.

Recommend A later, one consumer at a time. Keep a temporary compatibility
facade; do not create one interface or HTTP client per Graph endpoint.

## Implementation sequence

### Slice 0a: Correct shared invite-only scheduling

Independent; deliver before extracting modules.

Track invitation changes separately from detail changes in `updateInternal()`.
Enqueue outgoing work if either changed; request a detail write only for actual
detail changes. Periodic reconciliation is not a substitute: reconciliation-only
work exits before the outgoing attendee update.

**Acceptance:** An invite-only edit becomes pending and produces one
attendee-only update and zero detail updates. An unchanged invite list adds no
intent. Capacity rejection rolls back event, category, invitation and outbox
changes together.

### Slice 0b: Correct worker cancellation classification

Independent of 0a. Propagate `CancellationException` before
`IllegalStateException`; cancellation is a subtype on the JVM. Add cooperative
checkpoints between effects without claiming that they interrupt an already
issued blocking request.

**Acceptance:** Cancellation is propagated, never records permanent failure,
releases the event lock and leaves incomplete work reclaimable. Existing
permanent-error classification remains intact.

### Slice 1: Establish the atomic event-mutation seam

First add deterministic PostgreSQL tests for capacity, concurrent demotion,
duplicate signup and failed plain-event creation. Reuse existing test seams.

Extract the transaction/parent-write protocol without changing the shared
behavior. Migrate legacy create and signup first, then update, role changes,
withdrawal/category mutations and series operations. Keep event, initial host,
categories and intent in one transaction. Remove route-level database cleanup
as each operation becomes atomic, not before.

Retain synchronous master creation for compatibility, outside the retry block.
Database atomicity does not solve its crash window; slice 3 addresses that.
Remove nested commits from recurring mutations. Serialize series changes with
a consistent series/event acquisition order and preserve split/extend/shorten
semantics.

**Acceptance:** Exactly one contender succeeds for the last available place;
rejection maps to the existing conflict response. Concurrent demotion cannot
leave zero hosts. Failed creation commits no event, host, category link or
outbox row. A forced serialization failure re-executes database logic but
does not repeat Graph calls. Authorization uses the state protected by the
mutation, not a route's earlier snapshot.

### Slice 2: Persist deletion and cancellation intent

Depends on slice 1's transaction seam. Capture legacy calendar IDs, recipients
and required event details before participant/event cascade deletion. Cover
explicit event deletion, withdrawal, UPCOMING deletion and recurrence
shortening. The worker must not query deleted rows to recover this information.

Shortening a series must create cancellation intent for removed occurrences,
not merely return updates for retained ones. Characterize the legacy
notification flag before choosing suppression behavior for removed occurrences;
do not silently change the documented explicit-delete behavior.

Start with a gated legacy worker and one active execution path per effect.
Keep shared cancellation owned by the existing deletion trigger.

**Acceptance:** After database commit and process restart, all removed
occurrences still have recoverable cancellation intent. A failed master
deletion is visible and retryable rather than only logged. Successful
recipients are checkpointed independently of failed recipients. No switch
causes both the old thread and new worker to deliver the same effect.

### Slice 3: Persist remaining legacy effects and webhook receipt

Depends on slices 1 and 2. Replace legacy create/update notification threads
with transactionally stored work, including recurring edits. Persist validated
legacy webhook intent before 202 acknowledgement, as the shared path already
does. Return an error if valid work could not be queued.

Give each create effect a stable operation identity and creation payload.
Support ambiguous create responses without blindly creating another event.
Process batch subresponses independently; an outer HTTP 200 is not evidence
that all writes succeeded.

Preserve synchronous room/Teams contracts using a bounded operation journal
if needed: record intent before Graph, finalize the event and journal outcome
atomically, and recover/compensate unfinished operations. Never retry an entire
network-containing transaction. An asynchronous legacy redesign is a separate
frontend/product decision, not the default implementation.

**Acceptance:** Restart before delivery, lost create response, mixed batch
success, delete during pending create, and remove/re-add each preserve the
intended calendar state. Graph `Retry-After` is honored. A replayed webhook
does not repeat an already completed state transition. Where mail cannot be
deduplicated, delivery is explicitly at least once, with checkpoints and safe
operator recovery rather than an exactly-once claim.

**Gate before implementing the operation journal:** Agree compensation after
an ambiguous master response, client retry expectations, operator recovery and
retention of recipient/event snapshots. Preserve the 502/no-save contract.

### Slice 4a: Make worker and resource lifecycle observable

Independent of the legacy delivery schema, after 0b. Retain job handles and
observe completion. Separate per-item failures, transient polling failures and
terminal scheduler failures. Propagate cancellation and expose safe terminal
errors instead of swallowing unexpected exceptions or endlessly retrying bugs.

Give subscription renewal equivalent lifecycle ownership, while preserving
leader-only subscription management. Calendar execution remains active on
each replica. Own cache executors, the connection pool and any owned transport
resources; release them through application lifecycle hooks.

Offload JDBC and synchronous SDK operations at cohesive execution seams.
Bound admission with the actual connection/Graph budget, not an assumed thread
count. An active calendar worker holds one lock-session connection and borrows
another for short transactions; each replica has its own pool of five.
Do not increase worker concurrency during this extraction.

**Acceptance:** An injected unexpected item/polling failure cannot silently
leave all subsequent work stopped. Terminal worker state is observable;
subscription validity and renewal-loop health are separate. Stopping the app
stops owned jobs and executors and closes owned resources. An independent
health request completes while a test Graph provider is blocked, before the
provider is released. Cancellation alone is not evidence of transport abort.

Keep external Graph availability decoupled from general readiness. Whether a
dead required worker should affect readiness/liveness needs an explicit
operational decision; add worker/progress observability first.

### Slice 4b: Separate new intent from retry eligibility

Can follow 0a independently of legacy migration. New revisions and
reconciliation requests must not accidentally advance the externally imposed
retry deadline (`SharedCalendarRepository.kt:285-294,564-573,774-783`).
Keep durable desired state distinct from the next permitted external attempt.
Define host-triggered retry behavior separately.

Use calendar-specific claim/acknowledgement outcomes so metrics advance only
for accepted durable transitions. Distinguish no work, unavailable execution
ownership, completed transition and changed ownership. Keep token fencing and
the live session lock; lease expiry alone does not authorize overlapping work.

**Acceptance:** Notifications before, during and after a throttled attempt
preserve its delay while retaining new intent. Old tokens cannot acknowledge
new claims; unchanged ownership can finish after nominal lease expiry while
holding its live lock. Teams retries do not resend checkpointed detail writes.
Claim attempt counts are not presented as HTTP request counts.

### Slice 5: Narrow Graph contracts as consumers migrate

Lower priority; do not delay slices 0-4 for a wholesale client rewrite.
Introduce required room/directory capabilities and focused fakes. Keep SDK
types inside adapters as touched, preserving unknown/absent RSVP semantics.
Move directory outcomes away from shared-calendar-specific error vocabulary.

Add SDK-backed tests for room identity mapping, reordered/missing/duplicate
schedule results, per-room errors and incomplete paging. Map availability by
`scheduleId`, not position. Distinguish valid empty data from incomplete or
malformed retrieval; preserve stale-cache policy without caching an incomplete
first load as a successful complete catalog.

**Acceptance:** Consumers compile against only their required capability;
unexpected fake calls fail explicitly. JSON/status contracts remain stable.
Room and directory route tests do not need PostgreSQL merely to boot unrelated
fixtures. Production middleware/timeout behavior has its own proof; interceptor
tests remain payload tests, not transport or tenant validation.

## Proof and rollout

Promote the isolated reproductions into focused repository regression tests
as their slices start. Test through the public command/HTTP seam and real
PostgreSQL where transactions matter. Use SDK wire tests for request shape
and controlled mailbox checks for actual Exchange behavior.

Keep these gates:

| Gate | Required evidence |
| --- | --- |
| Mutation extraction | Existing route/serialization/M2M contracts pass; deterministic concurrent and rollback outcomes pass for both invite modes |
| Durable legacy worker | Crash-window, deletion snapshot, ambiguous creation, batch partial-failure and duplicate receipt cases pass |
| Worker/runtime change | Cancellation, ownership, failure containment, blocked-provider responsiveness and resource shutdown cases pass |
| Mailbox rollout | Invitation counts, attendee-only updates, RSVP preservation, Teams body/link behavior, room acceptance and cancellation checked with controlled recipients |

Reference baseline commands:

```bash
./gradlew test --tests 'no.nav.delta.event.DatabasesTest' \
  --tests 'no.nav.delta.event.EventRoutesTest' \
  --tests 'no.nav.delta.application.SerializationContractTest' \
  --tests 'no.nav.delta.application.ApplicationE2ETest' installDist

./gradlew test --tests 'no.nav.delta.calendar.*' \
  --tests 'no.nav.delta.event.SharedCalendarRepositoryTest' \
  --tests 'no.nav.delta.webhook.WebhookRoutesTest' \
  --tests 'no.nav.delta.email.*' --tests 'no.nav.delta.room.*' \
  --tests 'no.nav.delta.directory.DirectoryRoutesTest'
```

Use the smallest selectors for each slice; the commands above record this
research baseline, not a requirement to rerun everything after every edit.

Add new migrations rather than rewriting applied V28. Deploy schema and
compatible readers before enabling writers/workers. Never dual-execute mail
or invitation effects. Preserve queued work when disabling a flag.
Rollback must leave a compatible processor for already committed new work;
returning to an old binary that ignores it is not a complete rollback.

Observe backlog age, permanent failures, accepted transitions, retry delays,
worker progress, pool pressure and mailbox recipient volume. Agree production
alert/rollback thresholds with the operators before enablement. Do not
fabricate throughput improvements or infer actual usage from resource requests.

## Remaining decisions

- The maintainers/product owner should settle legacy master compensation and
  client retry behavior before slice 3's journal is implemented.
- Operators should settle dead-worker readiness policy, snapshot retention and
  backlog/recipient thresholds before durable delivery rollout.
- Controlled tenant checks must resolve RSVP preservation on organizer PATCH,
  event conditional-write enforcement and cancellation recovery behavior.
  An ETag or a passing fake is not proof.
- Mutation and effect ownership should be documented durably if the team
  adopts them; this plan does not declare ADR eligibility or rewrite ADR-0001.

## Primary sources checked on 2026-10-09

- [PostgreSQL 14 isolation](https://www.postgresql.org/docs/14/transaction-iso.html):
  repeatable read permits serialization anomalies and requires transaction retry.
- [PostgreSQL 14 application consistency](https://www.postgresql.org/docs/14/applevel-consistency.html)
  and [explicit locks](https://www.postgresql.org/docs/14/explicit-locking.html):
  stale snapshots, actual parent writes and deterministic lock ordering matter.
- [PostgreSQL retry handling](https://www.postgresql.org/docs/current/mvcc-serialization-failure-handling.html):
  retry the complete transaction on serialization failures; do not retry arbitrary
  integrity errors indiscriminately.
- [Graph webhook delivery](https://learn.microsoft.com/en-us/graph/change-notifications-delivery-webhooks):
  queue before 202; valid unprocessed/unqueued work needs an error response.
- [Graph throttling](https://learn.microsoft.com/en-us/graph/throttling):
  honor Retry-After; SDK handling of ordinary calls does not retry failed batch
  subrequests automatically.
- [Graph event resource](https://learn.microsoft.com/en-us/graph/api/resources/event?view=graph-rest-1.0):
  transactionId addresses redundant create POSTs, not arbitrary exactly-once
  effects; attendee limits include resource attendees.
- [Graph event updates](https://learn.microsoft.com/en-us/graph/api/event-update?view=graph-rest-1.0):
  attendees-only updates have selective notification semantics; preserve the
  Teams meeting body.
- [Graph schedule identity](https://learn.microsoft.com/en-us/graph/api/resources/scheduleinformation?view=graph-rest-1.0)
  and [getSchedule](https://learn.microsoft.com/en-us/graph/api/calendar-getschedule?view=graph-rest-1.0):
  scheduleId identifies the result and each result can carry its own error.
- [Coroutine blocking I/O](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/-i-o.html):
  explicit blocking offload; elastic dispatcher views are not connection budgets.
- [Ktor lifecycle events](https://ktor.io/docs/server-events.html)
  and [engine configuration](https://ktor.io/docs/server-engines.html):
  resource-release hooks, execution groups and shutdown settings exist.

Public documentation does not prove the pinned SDK's complete middleware
behavior, production resource pressure or Exchange tenant outcomes. Those are
implementation/rollout evidence requirements, not established gains.
