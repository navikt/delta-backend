# Synchronize invitations added through shared event edits

## In short

An organizer can add invitations while editing a shared event without changing
its title or time. Those invitations must reach Outlook instead of remaining
only in Delta.

## What to build

Make shared event editing track attendee and detail changes independently.
Persist outgoing synchronization intent in the same transaction as newly added
invitations, even when no meeting details change.

## Acceptance criteria

- [ ] Adding an invitee to an already synchronized event through the edit endpoint creates pending outgoing work.
- [ ] Processing that work sends an attendees-only update and no detail update when meeting details are unchanged.
- [ ] Repeating the same invitation list creates no unnecessary outgoing intent.
- [ ] An edit changing both attendees and details preserves both kinds of intent.
- [ ] Capacity rejection rolls back the entire edit, including invitations, categories and outbox changes.
- [ ] Existing response shapes, authorization and notification behavior remain unchanged apart from the missing synchronization.

## Implementation context and proof

`src/main/kotlin/no/nav/delta/calendar/SharedCalendarRepository.kt`,
`updateInternal()`, discards the change result from `invite()` and currently
enqueues only when details change. A production-method probe reproduced a
persisted INVITED attendee with no pending outbox work.

Periodic reconciliation is not an outgoing-update fallback: reconciliation-only
work exits before the attendee write in `CalendarSyncWorker`.

Use `SharedCalendarRepositoryTest`, `SharedCalendarRoutesTest` and
`CalendarSyncWorkerTest` to prove the HTTP-to-persisted-intent-to-adapter
outcome. Preserve the existing atomic mutation and retry mechanism.

Microsoft Graph documents that an update containing only `attendees` notifies
only changed attendees, with a distribution-list exception:
https://learn.microsoft.com/en-us/graph/api/event-update?view=graph-rest-1.0

## Out of scope

Route extraction, group invitations, new invitation semantics, legacy calendar
migration and a shared-worker rewrite.
