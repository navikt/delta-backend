# ADR-0001: One shared calendar event per Delta event

**Date:** 2026-10-02
**Status:** Proposed
**Decision makers:** Team Delta

## Context

### Problem

Users report that some participants don't get calendar updates when a host edits an event, while
others do. The only workaround is to sign off and sign up again, which creates a fresh invite.
This has been going on for months.

### How invites work today

- Delta sends **one Graph calendar event per participant** from its own mailbox
  (`prepareCalendarEvent` in `email/CloudClient.kt`, with `attendees = listOf(participant)`). The id
  is stored in `participant.calendar_event_id`.
- A host edit with `sendNotificationEmail = true` PATCHes all N invites in one Graph JSON batch
  (`batchUpdateOrCreateEvents`), in a fire-and-forget `Thread` (`event/Routes.kt`, `POST /admin/event/{id}`).
- Events with a room or Teams also get a separate **master event** that has only the room as an
  attendee (`docs/teams-meeting-room-booking-plan.md`). The master is separate only because a room
  or Teams meeting on N per-participant events would mean N bookings or N meetings.
- Declines in Outlook come in through the Graph webhook and unregister the participant
  (`webhook/Routes.kt`).

### Why participants miss updates

Each edit makes N separate Graph writes, and the system has no retry, no persisted sync state and
no reconciliation:

1. **Lost writes.** A failed step (429, 5xx, timeout) is only logged
   (`batchSendUpdateOrCreationNotification` in `email/Email.kt`) and never retried. If the
   background thread fails or the pod restarts, the update is gone. Before 541036f (March 2026)
   every participant got its own thread at once. That breaks Outlook's limit of
   [4 concurrent requests per mailbox](https://learn.microsoft.com/en-us/graph/throttling-limits#outlook-service-limits).
   Since then Graph runs at most 4 batch steps at a time, but those requests still share the
   mailbox with concurrent sign-ups, the second replica and the webhook. The webhook calls `GET` on
   every event we just PATCHed, so each edit generates load on the same mailbox. *Confirm against
   production logs (`Failed to update/create calendar event`) before relying on the exact mix.*
2. **Signup-and-edit race.** A sign-up creates its invite in a background thread. An edit that runs
   before the id is saved creates a second invite, and only one of the two is updated afterwards.
3. **Invites deleted in Outlook** return 404 on PATCH and are never recreated.
4. **`sendNotificationEmail = false`** on an edit skips the calendar update completely.

Points 1–3 come from keeping N copies of the same data in sync, which the per-participant model
requires.

### Constraints

- Participants are already visible to each other in Delta, so a shared attendee list exposes
  nothing new. If we ever need to hide it, Graph has a native `hideAttendees` property. We do not
  plan for it now.
- 2 replicas (`nais.yaml`). Leader election already exists (`webhook/LeaderElection.kt`).
- Dev and local use `DummyCloudClient`, so Exchange behaviour can't be verified in dev.

## Decision

Each Delta event (each occurrence, for recurring series) gets **exactly one Graph calendar event**
in Delta's mailbox. **All participants and hosts are attendees on it.** It is the existing master
event made general. The room, if any, is a resource attendee on the same event, and the Teams
meeting lives on it natively.

Calendar state is changed **only by a persisted, per-event sync job** that builds the desired state
from the database:

1. A `calendar_sync` table (one row per event, so repeated changes are coalesced) holds a flag for
   whether attendees or details changed, plus `next_attempt_at`, `attempts` and `last_error`.
   Routes write this row **in the same transaction** as the database change and stop starting
   threads.
2. A worker picks due rows with `SELECT … FOR UPDATE SKIP LOCKED`, so each event is synced one job
   at a time across replicas. It then:
   - **Attendees changed** (sign-up, sign-off, decline): PATCH **only** `attendees`, with the full
     list from the database plus the room resource. Graph sends a meeting update only to attendees
     who were added or removed
     ([Update event](https://learn.microsoft.com/en-us/graph/api/event-update?view=graph-rest-1.0)).
     New participants get an invite and those removed get a cancellation, with no noise for others.
   - **Details changed** (host edit): PATCH title, time, location and body. Exchange sends the
     update to everyone. The body is read first, the Delta-managed section is replaced and the
     Teams meeting blob is kept, as the Graph docs require for online meetings.
   - On 429 or 5xx it backs off (honouring `Retry-After`) and reschedules. Permanent errors are
     stored and logged.
3. **Delete** calls `POST /events/{id}/cancel` with a comment. Exchange cancels for every attendee
   and frees the room, which replaces the per-participant cancellation emails.
4. **Declines**: the webhook reads all attendee responses on the shared event. Each registered
   participant whose response is `declined` is unregistered and gets an attendee sync, which removes
   them. This keeps a later sign-up working: it re-adds them, which sends a new invite.

## Alternatives considered

### A: One shared event with a persisted sync job (chosen)
- **Pros:** an edit is 1 Graph write instead of N, so a partial update can't happen. Exchange does
  delivery, update, cancellation and RSVP natively. Room and Teams become native, and the master/
  participant split and the Delta-rendered Teams block go away. Because the sync is persisted and
  built from the database, it has no race and recovers after restarts or errors. It also removes a
  lot of code: batching, per-participant ids and recreating invites.
- **Cons:** a migration period with two invite modes. Attendee PATCH replaces the whole list, which
  is why per-event serialization is required. Changing details always notifies everyone. Exchange
  has a recipient limit (see Risks).
- **Nav assessment:** less accidental complexity, and it uses the platform (Exchange) for what it is
  built for. No new infrastructure.

### B: Keep one invite per participant and add a persisted sync job with retry
- **Pros:** no migration and no change in behaviour for users.
- **Cons:** still N writes per edit and N webhook notifications. Each participant can still end up
  in a different state, so we'd need reconciliation. Keeps the master/participant split and our own
  Teams block. The fix covers symptoms and adds code.
- **Nav assessment:** workable, but it keeps complexity we don't need.

### C: Keep two Graph events (master plus one shared participant event)
- **Pros:** the master body is never touched.
- **Cons:** two invites for the same occurrence and two lifecycles to keep in sync. The original
  reason for the split (N bookings) no longer applies.
- **Nav assessment:** rejected.

### D: Do nothing
- **Pros:** no effort.
- **Cons:** participants keep missing updates (time and place changes), and trust in Delta goes down.
- **Nav assessment:** not acceptable.

## Nav-specific considerations

### Security
- **Data:** Nav employees' names and work emails, already visible to other participants in Delta.
  Classification is unchanged (internal).
- **Auth:** the same app-only Graph access through the Delta mailbox (`ClientSecretCredential`). No
  new Graph permissions.
- **PII:** `calendar_sync` holds only `event_id`, timestamps and error text. New logging must not
  include email addresses. Existing logs do, which is a separate cleanup.

### Platform
- **Nais:** no manifest changes. One new Flyway migration (`calendar_sync`, plus `event.invite_mode`).
- **Resources:** much fewer Graph calls per edit. The worker polls every few seconds with an
  indexed query.
- **Observability:** gauges for pending and failed `calendar_sync` rows and the age of the oldest
  due row. A counter of sync outcomes by result. Alert when rows have failed for more than 1 hour.

### Team impact
- **Affected teams:** Delta only. Frontend: the meaning of `sendNotificationEmail` changes (see open
  questions). Users: an edit shows as one meeting update instead of a new per-person invite.

### Migration
- **Backward compatibility:** add `event.invite_mode` (`PER_PARTICIPANT` | `SHARED`). Existing rows
  stay `PER_PARTICIPANT`, so all current code paths keep working for them. Only new events get
  `SHARED`.
- **Rollout:** run both modes side by side, behind the existing feature-toggle mechanism (the one
  used for room booking). Start with the team, then everyone.
- **Rollback:** turn the toggle off so new events use `PER_PARTICIPANT` again. Events already
  `SHARED` keep working, because the `SHARED` path stays until exit.
- **Rollback trigger:** sync failures above the alert threshold, or reports of missing or duplicate
  invites on `SHARED` events.
- **Exit criteria:** no upcoming events left in `PER_PARTICIPANT` mode. We don't actively migrate
  them: converting would send each participant a cancellation followed by a new invite. They run
  out naturally.
- **Decommissioning:** remove `batchUpdateOrCreateEvents`, the per-participant
  create/update/delete, `participant.calendar_event_id`, `buildInviteBodyHtml`'s Teams block and
  `invite_mode`. Update `docs/teams-meeting-room-booking-plan.md`.

## Consequences

### Positive
- All participants see the same up-to-date invite, which fixes the reported bug at its root.
- Native Outlook features: a shared attendee list, the Teams join button, the room and cancellation.
- Less code and fewer Graph calls.

### Negative
- About 2 releases of extra complexity while both modes exist.
- Details edits always notify all attendees. Updating the calendar without notifying everyone is no
  longer possible.

### Risks
- **Recipient limit:** Exchange Online accepts at most 500 recipients per message. A details update
  to an event with more attendees may fail. *Action: check the largest participant count in prod.*
  If it's close to the limit, cap `participantLimit` or keep large events on `PER_PARTICIPANT`.
- **Attendee PATCH preserving RSVPs:** we expect existing attendees' responses to survive a
  full-list PATCH, but this must be verified (spike).
- **Body edits with Teams:** keeping the meeting blob when editing the body must be verified (spike).
  The fallback is to never PATCH the body and put the description link in the location or subject.
- **Decline races:** someone signs up again right after declining in Outlook. Handled by the
  per-event sync running one job at a time and the list being built from the database, but it must
  be covered by tests.

## Open questions

1. `sendNotificationEmail = false` on edits: drop the flag, so the calendar is always correct, or
   keep it and leave the calendar stale? Recommendation: drop it.
2. Should the organizer (the Delta mailbox) send a cancellation with a comment, or do we also keep
   our own cancellation email?

## Action items

- [ ] Check production logs and data: how often `Failed to update/create calendar event` happens,
      broken down by status code, and the largest number of participants per event.
- [ ] Spike against a test mailbox (dev uses `DummyCloudClient`): adding and removing attendees
      only notifies the changed attendees, RSVPs are preserved, editing the body keeps Teams, and
      `/cancel` frees the room.
- [ ] Settle the open questions.
- [ ] Flyway migration: `calendar_sync` and `event.invite_mode`.
- [ ] Sync worker with backoff, `FOR UPDATE SKIP LOCKED` and metrics/alerts.
- [ ] Route `SHARED` events in create, edit, sign-up, sign-off, delete and the webhook through the
      sync job.
- [ ] Integration tests for serialized sync per event, retry, decline, and sign-up after a decline.
- [ ] Feature toggle and gradual rollout. Tell the frontend about `sendNotificationEmail`.
- [ ] Decommission the `PER_PARTICIPANT` path when the exit criteria are met. Update the
      room/Teams plan doc.

## Review

| Axis | Finding |
|------|---------|
| Architecture | Removes the root cause (N copies of the same state) rather than patching it. The persisted sync job is the only new mechanism and it's justified: fire-and-forget threads are part of why updates get lost today. |
| Security | No new permissions or data flows. Attendee visibility is unchanged from Delta's own UI. Concern: existing logs contain emails. Not introduced by this ADR, but don't add more. |
| Platform | Works with 2 replicas through row locks. No new infrastructure. Needs alerting on stuck sync rows, or failures will stay as invisible as today. |
| Migration | Running both modes side by side with `invite_mode` is additive and reversible. Exit criteria and decommissioning are defined. Open risk: events over 500 recipients. |

```
Inspected:     email/CloudClient.kt, email/Email.kt, event/Routes.kt (create/edit/delete/signup routes),
               event/Database.kt (participant + master id queries), webhook/Routes.kt, nais.yaml,
               docs/teams-meeting-room-booking-plan.md, git history of Email.kt, Graph docs
               (event-update, Outlook throttling/JSON batching)
Not inspected: production logs/data (no access), RecurringDatabase.kt in full, frontend repo,
               live Exchange behaviour (dev uses DummyCloudClient)
Findings:      0 blocking, 3 concerns (recipient limit, RSVP preservation, body/Teams blob — all spike items)
Verdict:       CONCERNS
```
