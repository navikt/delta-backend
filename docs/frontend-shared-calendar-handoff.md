# Frontend handoff: shared calendar and individual invitations

The contract is `src/main/resources/openapi/documentation.yaml`. ADR-0001 explains
the calendar model. Group invitations are deferred: build a people picker, not a
group picker.

## Modes and rollout

Fetch `GET /features`. `sharedCalendar` controls creating new shared-mode events;
`peopleSearch` controls directory search. Do not inspect Entra groups in the client.
`event.inviteMode` is authoritative for an existing event, even after rollout is
turned off. Existing `PER_PARTICIPANT` events retain their previous behavior.

Keep room/Teams controls behind their existing independent features.
Invitations are not supported on recurring series or individual series occurrences.
Shared recurring occurrences still synchronize their calendars normally.

## People picker

Use `GET /directory/search?q=...` for basic person records (`id`, `name`, `email`).
Debounce input, require at least two characters, and display unavailable/search
errors rather than treating them as empty results. Search requires the backend's
`User.ReadBasic.All` permission; no group members or group metadata are returned.

Creation can include `invitees: [{ "email": "colleague@nav.no" }]`.
Only individually addressed Nav users are supported. Additional invitations use
`POST /admin/event/{id}/invitations` with the same list shape. Revoke with
`DELETE /admin/event/{id}/invitations` and `{ "email": "colleague@nav.no" }`.
Only hosts can add/revoke invitations. Do not send Graph response status.

Directory group classification runs during asynchronous delivery. A manually
entered distribution-list address can therefore save as PENDING and then fail
synchronization; it will not be sent as a supported individual invitation.
Prefer selecting people from search and expose the host's FAILED state.

An invitation batch is atomic: on a validation/capacity error, none of its new
invitations are saved. Deduplicate selected people. Registered people are not
invited twice. Include hosts and the room when considering the calendar attendee
ceiling; the backend performs the authoritative checks.

## Invitations and capacity

`FullEvent.participants` contains registered participants, not every calendar
attendee. `hosts` retains management roles regardless of their Outlook response.
`invited` includes invitation state; declined invitations are visible only to hosts.

| State | Presentation | Capacity |
|---|---|---|
| `INVITED` | Waiting for an answer, including tentative invitees | Reserved until signup deadline, or indefinitely without a deadline |
| `REGISTERED` | In the ordinary participant list | Counted |
| `DECLINED` | Host-only declined state | Not counted |
| `FORWARDED` | Added through Outlook forwarding | Not reserved |

Hosts always count. A registered participant answering tentative stays registered.
Acceptance after the reservation expires follows ordinary signup/capacity checks.
Outlook reply comments are not read; say so in invitation copy.

Do not present pending invitees as registered. A signup deadline passing releases
their reservations without changing their INVITED label or cancelling their calendar
entry.

## Saved is not delivered

Shared-mode creation and edits save immediately, then synchronize in the background.
`calendarSyncStatus` distinguishes `PENDING`, `SYNCED` and `FAILED`.

- `PENDING`: show that Outlook is still being updated; do not tell the user to
  recreate the event or resend an invitation.
- `SYNCED`: the desired calendar change has synchronized. Room acceptance is
  still a separate `roomStatus`; synchronized does not mean booked.
- `FAILED`: show the host-visible sanitized failure and a retry action. Retrying
  reuses the same event and durable intent rather than creating a duplicate.

The retry action is `POST /admin/event/{id}/calendar/retry`, authorized for hosts.
`calendarSyncError` must not be shown to non-hosts.

A missing Teams URL has more than one cause: it can still be provisioning, or
be hidden because the caller is not registered/a host. Do not always label it
"Sign up to see the Teams link." For an authorized participant with pending sync,
show provisioning state; for a failed sync, show an unavailable state.

For shared events, `sendNotificationEmail` does not suppress synchronization.
Adding/removing attendees sends notifications only to changed individuals;
ordinary RSVP and forward adoption do not send meeting updates to everyone.
Changing meeting details intentionally updates everyone.

## Local verification and rollout

Use `FEATURE_SHARED_CALENDAR=maintainers` or `all` locally. The dummy Graph
adapter supports shared events and people search; no real invitations are sent.
Validate pending and failed states as well as successful saves.

Production starts with shared calendar off. Backend permission consent and the
frontend contract must be ready before enabling the people picker. Mailbox tests
remain necessary for actual delivery/forwarding behavior; local tests cannot
prove what arrives in Outlook inboxes.
