# Make recurring-event changes explicit and preserve cancellation data

## In short

Developers should be able to understand how an upcoming-series edit splits,
extends or shortens a series. Removed occurrences must not lose the information
needed to cancel their legacy calendar entries.

## What to build

Give UPCOMING editing one cohesive workflow, separating occurrence calculation
from transactional application and mode-specific effects. Make its result
distinguish retained/changed, newly created and removed occurrences as needed
by delivery, instead of treating all effects as updates to remaining events.

Capture required legacy calendar IDs and recipient/event information before
cascade deletion. Preserve the shared deletion trigger's durable cancellation.

## Acceptance criteria

- [ ] The UPCOMING edit route does not calculate recurrence, coordinate SQL, decide notifications or start threads directly.
- [ ] A developer can locate split, extend and shorten rules and their effect decisions from one workflow.
- [ ] Occurrence generation and edit decisions can be tested without Ktor.
- [ ] Series metadata, affected occurrences, categories and shared intent commit atomically with one commit/rollback owner.
- [ ] Concurrent series edits use consistent locking/retry participation and do not leave overlapping or inconsistent series mappings.
- [ ] Shortening a legacy series with existing calendar IDs produces cancellation work for removed occurrences before losing their references.
- [ ] Tests exercise shortening with real participant/calendar references and prove that only removed occurrences are cancelled.
- [ ] Existing single-occurrence identity, past-occurrence preservation, cadence, maximum occurrence count and unsupported-feature rules remain intact.
- [ ] The notification flag's effect on removed legacy occurrences is explicitly characterized and settled before changing it.
- [ ] Shared cancellation snapshots and independent outgoing/detail revisions survive the extraction.

## Implementation context and proof

Relevant anchors are `updateRecurringSeriesFromOccurrence()` in
`event/RecurringDatabase.kt`, `generateOccurrences()` in `event/Recurrence.kt`
and the UPCOMING branch in `event/Routes.kt`.

The current shortening path deletes tail occurrences but returns only updated
and inserted events. A production-method probe reproduced deletion of a tail's
calendar reference with no deleted-event cancellation data in the result.
The current notification consumer cannot cancel that tail.

Recurring operations also explicitly commit inside the shared transaction
helper. Remove that split ownership as part of this workflow, not as a
repository-wide SQL rewrite.

Use `DatabasesTest`, route tests and
`SharedCalendarRepositoryTest`'s recurring-shrink cancellation case.
Preserve V28's shared deletion-trigger behavior.
Legacy cancellation may initially use the existing delivery mechanism through
an explicit seam; this fixes missing intent/data, not restart durability.

## Out of scope

New recurrence frequencies, Outlook-native recurring series, room/Teams support
for series, new notification policy without agreement and durable legacy jobs.
