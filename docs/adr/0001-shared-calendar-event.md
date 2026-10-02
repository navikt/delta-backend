# ADR-0001: Shared calendar event and invitations

**Date:** 2026-10-02
**Status:** Proposed
**Decision makers:** Team Delta

## Context

Delta puts events in people's Outlook calendars, but each calendar entry is private to one person:

- Every participant gets **their own Graph calendar event**, sent from Delta's mailbox
  (`ikkesvar.delta@nav.no`). The participant is the only attendee, and the event id is stored in
  `participant.calendar_event_id` (`prepareCalendarEvent` in `email/CloudClient.kt`).
- An event with a room or Teams meeting also gets a **master event**, where the room is the only
  attendee (`docs/teams-meeting-room-booking-plan.md`). It's kept separate only because putting the
  room or Teams meeting on N personal events would create N bookings or N meetings.
- When a host edits an event, all N copies are updated in a background thread nobody checks on.
  Some of those updates get lost, which is why some participants end up with outdated invites.
- The only way to become a participant is to sign up in Delta. Hosts can't invite colleagues or a
  team from Delta. Today they send a separate Outlook invite with a link to Delta.

Hosts want to **invite people when they create an event**, and later, and have the invitation show
up as an ordinary Outlook meeting.

### Constraints

- Participants can already see each other in Delta, so sharing the attendee list exposes nothing
  new. If we ever need to hide attendees, Graph's `hideAttendees` does that. We aren't planning for
  it.
- Production runs 2 replicas (`nais.yaml`). Leader election already exists
  (`webhook/LeaderElection.kt`).
- Graph access is app-only, through the Delta mailbox. Granted application permissions:
  `Calendars.ReadWrite`, `Mail.Send`, `Place.Read.All`, `GroupMember.Read.All`. For users, only
  `User.Read` is granted, which reads the signed-in user's profile and gives nothing to an app
  running without a signed-in user. `User.Read.All` and `User.ReadBasic.All` are **not** granted.
- Dev and local use `DummyCloudClient`, so we can't check how Exchange behaves in dev.
- `participantLimit = 0` means unlimited. Hosts count towards the limit (`checkIfEventIsFull`).

## Decision

### 1. One shared calendar event per Delta event

Each Delta event (each occurrence, for recurring series) gets **exactly one Graph event**, based on
today's master event. All hosts, participants and invitees are attendees on it. The room, if any,
is a resource attendee, and the Teams meeting is attached to the event itself.
`responseRequested = true`.

### 2. Calendar changes go through a stored sync job, one event at a time

Routes add a `calendar_sync` row **in the same transaction** as the database change, instead of
starting threads. There is one row per event, so several changes in a row are handled together. A
worker picks rows that are due using `SELECT … FOR UPDATE SKIP LOCKED`, so the two replicas never
sync the same event at once:

- **Attendees changed:** first GET the event's current attendees and **reconcile** any people
  Delta doesn't know about (see *Forwarding*), so a forward Exchange added moments ago is never
  overwritten. Then PATCH **only** `attendees`, with the full list built from the database
  plus the room. Graph then sends a meeting update only to the people added or removed
  ([Update event](https://learn.microsoft.com/en-us/graph/api/event-update?view=graph-rest-1.0)).
- **Details changed:** PATCH title, time, location and body, which notifies everyone. The worker
  reads the body first and keeps the Teams join section, as the Graph docs require.
- **Delete:** `POST /events/{id}/cancel`. Exchange sends the cancellation to every attendee and
  frees the room.
- On 429 or 5xx errors, the worker waits (using the `Retry-After` header when Graph sends it) and
  tries again later. Permanent errors are saved in `last_error`.

**Shared-mode changes are saved before Graph responds.** The API exposes calendar sync as
`PENDING`, `SYNCED` or `FAILED`; hosts can see a sanitized failure and retry it. Calendar sync is
separate from the room's acceptance and Teams provisioning. A pending save must not be presented
as a confirmed booking, and missing Teams details can mean provisioning is still pending.
Legacy events keep their existing synchronous room/Teams behavior.

Cancellation intent survives deleting the Delta event. Pending removals are remembered so a
revoked attendee still visible in Graph is not adopted as a forwarded invite. Remove/re-add
reinvitation has durable phases; coalescing jobs must not discard the removal.

#### Notification rules

Adding participants, processing their RSVP and adopting a forwarded invite must not send a
meeting update to everyone.

Microsoft explicitly documents that an event update containing **only `attendees` in the request
body** sends a meeting update **only to attendees that have changed**
([Update event: notes for updating specific properties](https://learn.microsoft.com/en-us/graph/api/event-update?view=graph-rest-1.0#notes-for-updating-specific-properties)).
We use the same `PATCH /users/{mailbox}/events/{id}` endpoint for attendee changes, but send only
the `attendees` property. We must not reuse a details-update payload containing `subject`, `body`,
`start`, `end`, location or other event properties for this operation.

The documented exception is removing an attendee specified as a member of a distribution list:
that sends an update to all attendees. V1 adds only individual attendees; no distribution list
is added to the Graph event. If group invitations are introduced later, groups must be expanded
before sending invitations. Recording a person's source group in Delta would not make them a
distribution-list attendee in Graph.

- **RSVP:** the webhook updates Delta's database only. It does not PATCH the Graph event or
  enqueue an attendee sync merely because an existing attendee accepted, declined or answered
  tentatively.
- **Forwarding:** adopting someone Exchange has already added is a database insert only. It does
  not require an attendee PATCH. Removing an unwanted individual forwardee uses the same
  attendees-only PATCH as other removals. A forwarded distribution list needs separate attention:
  do not assume the selective-notification rule covers removing its members.
- **Details edits:** changing the meeting's details still intentionally notifies everyone.

Selective attendee notifications are documented API behaviour, not an undocumented assumption.
The mailbox spike must still verify the actual request payload, preservation of existing replies
and forwarding behaviour before rollout.

### 3. Invitations

Hosts can invite **individual people** (by email), both
when they create an event and later. An invitation is **pending**: the person appears as an
attendee in Outlook and as *invited* in Delta. They become a participant when they accept in
Outlook or sign up in Delta. **An invitation reserves a spot** under `participantLimit`.

V1 includes a **searchable people picker** backed by Graph and `User.ReadBasic.All`.
**Group invitations are deferred**, and there is no per-host batch rate limit. The attendee
ceiling, capacity checks, audit trail and mailbox recipient monitoring still apply. Groups are
not accepted in invitation requests or returned in directory search.

#### Deferred: group invitations

**Groups are expanded into their members when the invite is sent.** If we added a distribution
list as an attendee, Exchange would keep it as one attendee and wouldn't tell us how each member
answered. We then couldn't reserve a spot per person or match Outlook answers to people. So Delta
fetches the group's members at that moment
(`GET /groups/{id}/transitiveMembers/microsoft.graph.user`) and invites each of them. It records
which group each person came from, so the UI can show "invited via *Team X*". People who join the
group later are not invited.

**This needs `User.ReadBasic.All`.** With only `GroupMember.Read.All`, Graph returns each member's
`id` and type, and every other property, including `mail`, is `null`
([limited information for member objects](https://learn.microsoft.com/en-us/graph/permissions-overview#limited-information-returned-for-inaccessible-member-objects)).
Attendees need an email address, so we can't expand a group without it. `GroupMember.Read.All` is
enough to search groups and read group names.

#### Participant status

`participant` gets a `status` column, separate from `type` (HOST/PARTICIPANT):

| Status | Meaning | Takes a spot | Outlook attendee |
|---|---|---|---|
| `INVITED` | Invited, no answer yet or answered *tentative* | yes, until `signupDeadline` | yes |
| `REGISTERED` | Signed up in Delta or accepted in Outlook | yes | yes |
| `DECLINED` | Declined in Outlook | no | yes (kept so they don't get a cancellation) |
| `FORWARDED` | Added by someone forwarding the invite in Outlook, no answer yet | no | yes |

The table also gets `invited_by` and `invited_at` for audit and display. Group provenance is
deferred with group invitations. Existing rows become `REGISTERED`.

#### Transitions

| What happens | Result |
|---|---|
| Host invites people | Add `INVITED` rows. If there aren't enough spots, the whole batch is rejected. People already registered are skipped. Attendees are synced. |
| Accepts in Outlook (webhook) | `INVITED` or `DECLINED` becomes `REGISTERED`. The spot is already reserved, or is taken again if one is free. |
| Declines in Outlook (webhook) | Becomes `DECLINED` and frees the spot, but the person stays an attendee. Replaces today's behaviour, which unregisters the person and deletes their invite. |
| Signs up in Delta | `INVITED` becomes `REGISTERED`, with no new invite. `DECLINED` becomes `REGISTERED`, and the sync removes and re-adds them so they get a new invite. Anyone else becomes `REGISTERED` through the normal signup checks. |
| Signs off in Delta | Row is deleted and attendees are synced, so the person gets a cancellation. |
| Host revokes an invitation | Row is deleted and attendees are synced, so the person gets a cancellation. |
| Someone Delta doesn't know about turns up on the event (forwarded invite) | See *Forwarding* below. |
| `signupDeadline` passes | `INVITED` stops holding a spot. The person stays `INVITED` and stays an attendee. Nothing is sent to Graph. |

**Hosts keep their role and counted spot regardless of RSVP.** A host declining in Outlook never
loses management authority or frees a spot. A `REGISTERED` non-host answering tentative stays
registered; only an explicit decline releases their registration.

**Unanswered invitations release their spot at the signup deadline.** This is computed when the
spot count is checked (`INVITED` counts only while `signupDeadline` is null or in the future), so
no job is needed. Events without a `signupDeadline` keep the reservation until the event.

Before the deadline, an invitee can accept even if the event has filled up, because their spot is
reserved. After the deadline, accepting follows the normal signup rules. If the event is full, the
person becomes `DECLINED` and is told why by email. *Check in the spike whether answers like this
should also be visible in Outlook.*

#### Forwarding

When an attendee with a Microsoft 365 mailbox forwards the invite, Exchange sends it to the new
person, **adds them to the attendee list on Delta's copy** and notifies the organizer
([Forward event](https://learn.microsoft.com/en-us/graph/api/event-forward?view=graph-rest-1.0)).
Graph has no setting to block forwarding, and Delta is meant to be open, so we **adopt**
forwarded people instead of fighting it.

The webhook (on any attendee change) and every attendee sync compare the Graph attendees with
the database. Each attendee Delta doesn't know about is handled like this:

| Attendee | Result |
|---|---|
| `@nav.no` person, room left under the 490 limit | Insert as `FORWARDED`. Holds **no** spot, so participants can't reserve spots for others by forwarding. They stay an attendee and get updates. |
| `@nav.no` person, 490 limit reached | Removed by the attendee sync, so they get a cancellation. |
| External address or group/distribution list | Removed by the attendee sync. Delta can't register them (Entra login, per-person status). |

| What happens next | Result |
|---|---|
| `FORWARDED` accepts in Outlook | Registers under the normal signup rules (capacity, deadline). If refused, they become `DECLINED` and get an email saying why. |
| `FORWARDED` signs up in Delta | Becomes `REGISTERED` under the normal rules. No new invite. |
| `FORWARDED` declines | Becomes `DECLINED`. |
| Host removes a `FORWARDED` person | Same as revoking an invitation: row deleted, attendee sync, cancellation. |

- `invited_by` stays `null` for forwarded people. Only the organizer's notification email says who
  forwarded, and reading it would need `Mail.Read` on the mailbox, which we don't want.
- Hosts see `FORWARDED` people in the "Invited" list, marked "via forwarding".
- Forwarding defeats the rule that only participants see Teams details. Removing someone doesn't
  take back a link they've already received. We accept this, because the Teams lobby is the
  control for meeting access.

#### API (overview; details go in OpenAPI)

- `CreateEvent.invitees: List<InviteeRequest>?`, where `InviteeRequest` is `{ email }`.
- `POST /admin/event/{id}/invitations` (same body) and `DELETE /admin/event/{id}/invitations`
  (`{ email }`). Hosts only.
- `FullEvent.invited: List<Invitation>` (email, name, status), shown to the same people
  who can see `participants`. Only hosts see `DECLINED` invitees.
- `GET /directory/search?q=` finds people for the frontend's invite picker. People search needs
  `User.ReadBasic.All`; groups are deferred.

#### Limits (against misuse, and Exchange limits)

- Only Nav addresses (`@nav.no`). Anything else gets a 400.
- At most **490 attendees** per event, including hosts and the room, since Exchange caps a
  message at 500 recipients.
- Exchange also caps a mailbox at 10,000 recipients a day, and every edit to a large event counts
  each attendee again. Track recipients per day and alert at 70%.
- Keep an audit log of who invited whom, without email addresses in ordinary log lines.

### 4. Out of scope for v1

- **No invitations on recurring series** in v1, the same rule as for room and Teams today. Each
  occurrence is its own Graph event, so a weekly series would send each person one invite per
  occurrence. Fixing that means using Graph's built-in recurring events, which needs its own ADR.
- Only new events use this. Existing events keep their personal invites until the event is over.

## Alternatives considered

### A: Shared event, individual invitations and a stored sync job (chosen for v1)
- **Pros:** invitations are ordinary Outlook meetings, and Exchange handles delivery, updates,
  cancellations and replies. Replies map to a Delta status per person. Edits always reach everyone,
  with 1 Graph write instead of N. Room and Teams work natively. Removes a lot of code.
- **Cons:** people search needs one new Graph permission (`User.ReadBasic.All`). Two invite models run in parallel for a
  while. Every details edit notifies everyone.

### B: Invite distribution lists as a single attendee
- **Pros:** no new permission, and people who join the group later get the invite.
- **Cons:** Graph doesn't report each member's answer, so there's no status, no reserved spot and
  no "accept = registered". It can't be combined with "an invitation reserves a spot".
- **Rejected** because of the spot reservation requirement.

### C: Keep personal invites, and send each invitee their own invite too
- **Pros:** no change to the invite model.
- **Cons:** each edit is still N Graph writes, which is why updates get lost today. No shared
  attendee list, and the master/participant split remains. Invitations would be built on a weak
  base.

### D: Do nothing
- Hosts keep sending Outlook invites next to Delta, and the participant list and the calendars
  drift apart.

## Nav-specific considerations

### Security
- **Data:** names and email addresses of Nav employees. Group names, members and invitation
  provenance are deferred with group invitations.
- **Auth:** nothing changes for users (Entra ID). `GroupMember.Read.All` (application) is already
  granted but group search/expansion is deferred. **One new application permission:**
  `User.ReadBasic.All`, to search
  people. It is the narrowest user-read permission (name, email, photo; no profile data) and needs
  admin consent and a security review.
- **If `User.ReadBasic.All` is refused:** the planned searchable picker cannot work. Individual
  invitation endpoints need no directory lookup, but silently replacing the agreed picker with
  an email-only UI is not part of this decision.
- **Misuse:** every Nav employee can create events (`allowAllUsers`). Domain validation,
  490 attendees and mailbox monitoring are required in v1. No per-host batch rate limit is
  planned; group invitations are deferred.
- **PII in logs:** don't log email addresses or group members. `calendar_sync.last_error` must not
  contain recipient lists.

### Platform
- **Nais:** no manifest changes. Flyway migrations: `calendar_sync`, `event.invite_mode` and the
  new `participant` columns.
- **Monitoring:** gauges for pending and failed `calendar_sync` rows and the age of the oldest due
  row. Counters for invitation recipients and rejections for capacity or attendee limit,
  recipients per day, and status changes from the webhook. Alert when a row has been failing for
  over 1 hour, and when recipients pass the threshold.
- **Testing:** dev never calls Graph. We need a **way to test against real Graph**, such as a test
  mailbox in the dev tenant behind a toggle, to check how Exchange behaves before prod.

### Team impact
- **Delta frontend:** a person picker when creating and editing an event, an "Invited"
  list with status, a way to revoke invitations, capacity that counts reserved spots, and a note
  that recurring series can't have invitations.
- **Users:** invitations come from `ikkesvar.delta@nav.no`. Answering in Outlook updates Delta, but
  **nobody reads comments written in a reply**. The invite text must say so.

### Migration
- **Backward compatibility:** `event.invite_mode` (`PER_PARTICIPANT` | `SHARED`). Existing events
  stay `PER_PARTICIPANT`. New events get `SHARED` behind a feature toggle (the same mechanism as
  room booking). Invitations require `SHARED`.
- **Rollback:** turn the toggle off, and new events go back to `PER_PARTICIPANT` and invitations
  are hidden. Events already `SHARED` keep working, because that code stays until the migration is
  done.
- **When to roll back:** sync rows stuck or failing above the threshold, duplicate or missing
  invites, or a recipient alert.
- **Done when:** no upcoming events are `PER_PARTICIPANT`. We don't convert old events, because
  that would send every participant a cancellation followed by a new invite.
- **Cleanup:** remove the per-participant create/update/delete, `batchUpdateOrCreateEvents`,
  `participant.calendar_event_id`, the Teams block Delta adds in `buildInviteBodyHtml`, and
  `invite_mode`. Update the room/Teams plan doc.

## Consequences

### Positive
- Hosts can invite people from Delta, and the invitation is an ordinary Outlook meeting.
- Everyone shares one calendar entry. Durable retries replace the unchecked background updates.
- Room, Teams, replies and cancellations work natively. Less code and fewer Graph calls.

### Negative
- One new Graph permission, and we depend on the identity admins to grant it.
- Group invitations are deferred.
- Every details edit notifies all attendees.
- Two invite models run side by side for about 2 releases.

### Risks
- **Unanswered invitations hold spots** until the signup deadline. Events without a deadline can
  look full of people who never answer. The frontend should suggest a deadline when inviting.
- **Attendee updates and replies:** existing answers should survive a PATCH of the full attendee
  list. Must be checked in the spike.
- **Editing the text of a Teams meeting:** keeping the Teams join section must be checked in the
  spike. If it doesn't work, we never PATCH the body.
- **Signing up again after declining:** the person gets a cancellation and then a new invite. Check
  that this looks acceptable in Outlook.

## Decided questions (2026-10-02)

1. **Unanswered invitations** release their spot at `signupDeadline`. They stay `INVITED`.
2. **Forwarded invites:** Nav users added by forwarding are adopted as `FORWARDED` (no spot held).
   External addresses and groups are removed. See *Forwarding*.
3. **`sendNotificationEmail`** is dropped for `SHARED` events. The calendar always matches Delta.
4. **Group invitations are deferred.** V1 supports individual invitations and people search.
5. **No per-host batch rate limit.** Keep the attendee ceiling and mailbox monitoring.
6. **Asynchronous shared-mode saves** expose pending/failed calendar sync to the frontend.
7. **Host RSVP** changes neither authority nor counted capacity. A registered participant's
   tentative response does not downgrade their registration.

## Action items

- [ ] Request `User.ReadBasic.All` (Application) with admin consent. `GroupMember.Read.All` is
      already granted. Note: `getUserDisplayName` (faggruppe owners) needs it too, and returns
      `null` today.
- [ ] Security review of the permission and misuse limits (security champion).
- [ ] Spike against a test mailbox: a PATCH of only attendees notifies only the changed people,
      replies survive, editing the text keeps Teams, `/cancel` frees the room, and removing and
      re-adding someone looks acceptable. Forward from an attendee's mailbox: check that the
      forwardee appears on Delta's copy, that a webhook notification fires, and what their reply
      looks like.
- [ ] Flyway: `calendar_sync`, `event.invite_mode`, and `participant.status`/`invited_by`/
      `invited_at`.
- [ ] Sync worker (retry with backoff, `SKIP LOCKED`, metrics and alerts).
- [ ] `SHARED` handling in create, edit, sign-up, sign-off, delete and the webhook (status changes).
- [ ] Individual invitation API, people search, limits and audit log. Group expansion is deferred.
- [ ] OpenAPI and frontend handoff (picker, status, capacity, no invitations on recurring series).
- [ ] Integration tests: one sync per event at a time, retries, every status change, capacity when
      inviting, the 490 limit, and forwarding (adopt, accept when full, external removed, and a
      forward arriving between the GET and PATCH of an attendee sync).
- [ ] Feature toggle and gradual rollout.
- [ ] Remove `PER_PARTICIPANT` once the migration is done.

## Review

| Axis | Finding |
|------|---------|
| Architecture | One shared event replaces N copies of the same data. V1 supports individual invitations and reserved spots; group expansion is deferred. People search still requires a new permission. |
| Security | **Concern:** the new `User.ReadBasic.All` permission gives read access to basic profile data for every user in the directory, and needs a security review. **Concern:** mass invites from the shared mailbox. The limits must ship in v1, not later. **Accepted:** forwarding spreads the Teams link beyond Delta's participant list, which the Teams lobby mitigates. What participants can see is otherwise unchanged. |
| Platform | No new infrastructure. Row locks handle the 2 replicas. Exchange's limit of 10,000 recipients per mailbox per day is a real ceiling for large events and must be monitored. Dev can't test Graph, so we need a test mailbox. |
| Migration | Running both models side by side through `invite_mode` only adds code and can be rolled back. When the migration is done and what to clean up are both defined. |

```
Inspected:     email/CloudClient.kt, email/Email.kt, event/Routes.kt, event/Models.kt,
               event/Database.kt (registerForEvent, checkIfEventIsFull, participant queries),
               webhook/Routes.kt, nais.yaml, docs/teams-meeting-room-booking-plan.md (permissions),
               Graph docs (event-update, event-forward, event resource, Outlook throttling)
Not inspected: frontend repo, production data (largest event, recipient volume), Entra app
               registration (actual granted permissions), live Exchange behaviour, RecurringDatabase.kt
Findings:      0 blocking, 3 concerns (new User.ReadBasic.All permission, misuse limits,
               Exchange behaviour not yet checked)
Verdict:       CONCERNS
```
