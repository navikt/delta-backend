# Teams Meetings & Room Booking in Delta

## Status (read this first)

Implemented in this branch: **Phase 0, Phase 1, and Phase 2**, for **single (non-recurring)
events only**, plus the step-11 test gap-check and a hardening pass (see "Update rules" and
"Hardening" below). Full test suite passes (`./gradlew test`).

**Deliberately not supported in v1:**
- **Recurring events.** Room/Teams fields are rejected with 400 on every recurring path: create
  with `recurrence`, update with `editScope=UPCOMING`, **and** single-occurrence edits of an event
  that belongs to a series. So series occurrences never have a master, and the `UPCOMING` delete
  needs no master cleanup.
- **Removing a room or turning Teams off** on an existing event. Delete the event instead, which
  deletes the master, cancels the Graph event and frees the room.

**What's NOT done:**
- Step 17 (prod test + Teams lobby verification): blocked on the external prerequisites below.
  An agent can't do it.
- No frontend work (separate repo). **The frontend must know the update rules below.**

**External prerequisites still outstanding** (see the table below). Nothing can be *verified*
end-to-end until these are granted:
- ~~`Place.Read.All` on the prod-gcp `delta-backend` app~~ **granted** (Sep 2026). Verify it is
  an *Application* permission (not Delegated) with admin consent; app-only auth ignores delegated ones.
- `User.Read.All` (Application) is **not** in the granted list (`User.Read` is). Existing
  `getUserDisplayName` (faggruppe owners) needs it; unrelated to room/Teams but worth checking.
- Teams-enabled license on `ikkesvar.delta@nav.no`
- Confirmation that room `BookInPolicy` allows `ikkesvar.delta@nav.no` to book

**To resume:** chase the external prerequisites and do the prod test (step 17). Recurring support
would be a separate v2 decision.

### Update rules (`POST /admin/event/{id}`)

| Request | Result |
|---|---|
| `roomEmail`/`roomName`/`isOnlineMeeting` omitted (null) | **Kept as is.** A client without the feature (toggle off, older frontend) can never remove a booking by leaving the fields out. |
| Event has a master, any edit (title, time, …) | `updateMasterEvent` with the merged event, so the booking follows the event. |
| New `roomEmail` (+ `roomName`) | Master PATCHed with the new room (attendee list replaced); `roomStatus` taken fresh from Graph. |
| Same room, unrelated edit | `roomStatus` kept unless Graph reports a definite ACCEPTED/DECLINED (a PATCH re-fetch usually says PENDING until the room re-answers). |
| `isOnlineMeeting=false` on an event with Teams | **400**, delete the event instead. |
| Adding Teams/room to an event without a master | `createMasterEvent`. |
| `roomEmail` without `roomName` or vice versa | **400** (also on create). |
| Room/Teams fields on an event in a recurring series | **400**. |
| `roomStatus`/`teams*` sent by the client | Ignored (`@JsonIgnoreProperties` on `CreateEvent`). |

Teams fields are merged too: if the master re-fetch has no `onlineMeeting`, the stored join
URL/conference ID/dial-in are kept.

### Hardening (done alongside step 11)

- Room status is read from the attendee with `type == Resource`, not `attendees.firstOrNull()`.
- The webhook uses a targeted `setRoomStatus` UPDATE instead of rewriting the whole event row, so
  it can't overwrite a concurrent admin edit.
- The `/rooms` cache no longer caches Graph failures (they return 502 and are retried), and the
  per-list cache is a `ConcurrentHashMap`.
- Create: if anything after master creation fails (`setMasterCalendarEventId`, `updateEvent`,
  host registration, categories), both the master and the event row are deleted. Update: if the DB
  write fails after a *new* master was created, that master is deleted and the id cleared.
- `prepareMasterCalendarEvent` is now an `internal` top-level function with unit tests (never
  sets `body`, room is a Resource attendee).

## Problem Statement

Delta creates calendar events with a plain text `location`. Goal:
1. Optionally create a **Microsoft Teams meeting** for a Delta event
2. Optionally **book a meeting room** (like "Add a location" in Outlook)

Both must be **feature toggled** so they can be tested in prod before general release.

## Current State

- Events are created in Delta's mailbox (`ikkesvar.delta@nav.no`) with app-only auth (`ClientSecretCredential`).
- **One Graph event per participant** (`CloudClient.kt` `prepareCalendarEvent`, `attendees = listOf(participant)`), id stored in `participant.calendar_event_id`.
- Decline detection: Graph subscription on `users/{deltaEmail}/events` → `webhook/Routes.kt` `processNotification` → looks up participant by calendar event id.
- `CloudClient.fromEnvironment` returns `DummyCloudClient` in dev-gcp and local — **dev never calls Graph**.
- No feature flag mechanism; config is env vars in `Environment.kt`.
- Latest migration **before this work**: `V26`. This work adds `V27`.

### Why the room/Teams cannot go on the participant events

- Room as resource on N participant events → N overlapping bookings; the room accepts the first and declines the rest.
- `isOnlineMeeting` on N participant events → N *different* Teams meetings.

## Chosen Design

### Master event

For each Delta event (each occurrence, for recurring series) with a room and/or Teams enabled, Delta creates **one extra "master" Graph event** in its own mailbox:

- Attendees: **the room only** (`type = resource`). No people → no duplicate invites.
- `isOnlineMeeting = true`, `onlineMeetingProvider = teamsForBusiness` if Teams is enabled.
- `location` = room display name.
- Stored as `event.master_calendar_event_id`.
- **Never set or PATCH `body` on the master.** Exchange injects the Teams join block into the master body; overwriting it removes the join info from the calendar entry. Only time, title, location and attendees are patched.

Per-participant invites stay unchanged (decline tracking keeps working), but their body includes the room name and a Delta-rendered Teams block (join link, plus dial-in if available).

### Teams meeting details

Meeting details are read from the structured `onlineMeeting` (`OnlineMeetingInfo`) in the Graph response — **not** parsed from the Teams HTML in `body.content`:

| Graph field | Stored as |
|---|---|
| `joinUrl` | `teams_join_url` |
| `conferenceId` | `teams_conference_id` |
| first of `phones`/`tollNumber` + `quickDial` | `teams_dial_in` (only if NAV has Audio Conferencing; otherwise null) |

The Delta `description` is never modified. The frontend renders the details.

**Visibility:** Teams fields are returned only to registered participants and hosts; for others they are `null` in `GET /event` and `GET /event/{id}`.

### Lifecycle (as implemented, single events only)

| Trigger | Action |
|---|---|
| Event created with room/Teams requested | `cloudClient.createMasterEvent` runs **before** the DB insert. Success → insert event, `setMasterCalendarEventId`, update event with the returned room status/join URL. Graph failure → **502**, nothing persisted. A later failure (DB, host registration, categories) → master and event row are both deleted. |
| Event updated (any edit) on an event with room/Teams | Merged per "Update rules" above. `updateMasterEvent` (existing master) or `createMasterEvent` (none yet) runs **before** `database.updateEvent`. Failure → **502**, the DB update never happens. |
| Event updated: room removed / Teams turned off | **Not possible.** Omitted fields are kept; `isOnlineMeeting=false` on a Teams event → 400. |
| Event deleted | Best-effort `deleteMasterEvent` before `deleteEvent`. |
| Recurring series (create with `recurrence`, `editScope=UPCOMING`, or single-occurrence edit of a series event) | **Rejected with 400** if room/Teams fields are set. Not supported in v1. |
| Participant invites | Not touched directly by the master lifecycle. They pick up the current room/Teams fields the next time they're resent, which only happens when the caller passes `sendNotificationEmail=true` on a create/update — same as every other field change (title, time, etc.). There is no separate "batch update invites when room/Teams changes" trigger. |

Master creation/update is **synchronous** in the request (so the response already has
`teamsJoinUrl`), unlike participant invites which go out in a background thread as before.

### Room decline detection

The existing webhook already receives updates for master events (same mailbox). `webhook/Routes.kt`'s `processNotification` checks `getEventIdByMasterCalendarEventId` first; if the notification is for a master event, `processMasterEventNotification` reads the room attendee's response and stores it in `room_status` — a Teams-only master (no room) is a no-op. No email to host in v1 — frontend shows the status.

### Feature toggles

Env vars in `Environment`, default off:

| Env var | Meaning |
|---|---|
| `FEATURE_ROOM_BOOKING` | `off` / `maintainers` / `all` |
| `FEATURE_TEAMS_MEETING` | `off` / `maintainers` / `all` |
| `DELTA_MAINTAINERS_GROUP_ID` | The Delta maintainers Entra ID group (also the faggruppe admin group; one variable for both) |

`maintainers` enables the feature only for callers whose token `groups` claim contains
`DELTA_MAINTAINERS_GROUP_ID` (the group is listed under `azure.application.claims.groups` in
`nais.yaml`, so Entra emits it). `true`/`false` are accepted as aliases for `all`/`off`; any
other value fails startup. Invalid or unset group id + `maintainers` = nobody.

Behaviour when a feature is disabled for the user:
- `/rooms*` routes return **400** ("Room booking is not enabled").
- Writes containing room/Teams fields → **400** (`validateRoomAndTeamsToggles` in `event/Routes.kt`).
- Reads still return stored data (fields always present, `null` when unused — stable contract).
  Teams fields are further redacted to `null` for callers who are not a registered participant or
  host, regardless of the toggle (see "Teams meeting details" above).
- Turning a flag off does not clean up existing masters/bookings.

`GET /features` returns `{ "roomBooking": bool, "teamsMeeting": bool }` for the current user so the frontend can show/hide UI.

Testing strategy: set the flag to `maintainers` in **prod**; `all` for general release.

### Data model (V27)

On `event`:

| Column | Type |
|---|---|
| `room_email` | text null |
| `room_name` | text null |
| `room_status` | text null (`PENDING` / `ACCEPTED` / `DECLINED`; the unused `FAILED` enum value was removed) |
| `is_online_meeting` | boolean not null default false |
| `teams_join_url` | text null |
| `teams_conference_id` | text null |
| `teams_dial_in` | text null |
| `master_calendar_event_id` | text null (indexed) |

`Event` gets matching nullable fields. `CreateEvent` accepts `roomEmail`, `roomName`, `isOnlineMeeting` (the rest — `roomStatus`, `teamsJoinUrl`, `teamsConferenceId`, `teamsDialIn` — are server-managed and rejected/ignored if a client tries to set them, since they aren't fields on `CreateEvent` at all). The room is optional alongside free-text `location`. **Note:** "when a room is picked, `location` defaults to the room name" (Q5) is a **frontend** convention — the backend does not auto-fill `location` from `roomName`; they're independent fields. Teams is **opt-in per event**.

### Room discovery API

All authenticated users. Rooms and room lists cached in memory per pod for 24 h (stale-while-revalidate: after 24 h the cached data is served while a background refresh runs; a failed refresh keeps the old data and retries after 5 min). Search data is preloaded at startup when room booking is not `off`.

- `GET /rooms` — room lists (`GET /places/microsoft.graph.roomlist`)
- `GET /rooms/search?q=&limit=` — name/email search over all rooms (`GET /places/microsoft.graph.room`, all pages via `$top`/`$skip` since Graph `places` doesn't reliably return `@odata.nextLink`, cached 24 h; filtered in the backend since Graph `places` has no free-text search)
- `GET /rooms/{roomListEmail}` — rooms in a list (`GET /places/{email}/microsoft.graph.roomlist/rooms`)
- `POST /rooms/availability` — body: room emails + start/end; uses `POST /users/{deltaEmail}/calendar/getSchedule`

`DummyCloudClient` returns fake room lists/rooms/availability so the frontend can develop locally.

## Implementation Steps

### Phase 0 — Foundations
1. ✅ `FeatureAccess` (`off`/`maintainers`/`all`) in `Environment`, gated on the maintainers group; unit tests.
2. ✅ `GET /features` route.
3. ✅ Update README permission list (`Calendars.ReadWrite`, `Mail.Send`, `User.Read.All`, `Place.Read.All`).

### Phase 1 — Room booking (`FEATURE_ROOM_BOOKING`)
4. ✅ `CloudClient`: `getRoomLists()`, `getRooms(listEmail)`, `getRoomAvailability(...)` in Azure, Dummy and `RecordingCloudClient`.
5. ✅ `/rooms` routes with cache and toggle guard.
6. ✅ V27 migration + `Event`/`CreateEvent`/Database changes; OpenAPI and `SerializationContractTest` updates.
7. ✅ `CloudClient`: `createMasterEvent`, `updateMasterEvent`, `deleteMasterEvent` (returns id + join URL + room status).
8. ✅ Wire master lifecycle into admin create/update/delete routes for **single (non-recurring) events**.
   Implementation note: rather than "insert row then roll back on Graph failure", the master event
   is created/updated **before** any DB write — a Graph failure means nothing is persisted, so
   there is nothing to roll back. On create: Graph call → `addEvent` → `setMasterCalendarEventId`.
   On update: Graph call → `updateEvent` (aborted before touching the DB if Graph fails). On
   delete: best-effort `deleteMasterEvent` (logged, not fatal) before `deleteEvent`, since a
   lingering master event is low-harm.
   **Not supported in v1:** recurring events reject room/Teams fields with 400: `recurrence` on
   create, `editScope=UPCOMING`, and single-occurrence edits of a series event
   (`isRecurringOccurrence`).
9. ✅ Add room/join URL to participant invite body; batch-update invites when they change.
   Implementation note: no separate "batch update invites" trigger was needed — the existing
   `sendNotificationEmail` re-send path already resends the invite body whenever the event is
   updated, and the merged event (with the new room/Teams fields) now flows into that body via
   `buildInviteBodyHtml`. Propagation is therefore opt-in per update, same as any other field
   change (title, time, etc.), not a new special case.
10. ✅ Webhook branch for master events → update `room_status`.
11. ✅ Tests: toggle off/maintainers/all (including value parsing), 400 when disabled (PUT and POST, mixed toggles), master lifecycle including "omit = keep", teams-off 400, room change, status preservation, abort-before-DB-write on create/update Graph failure, rollback on later create failure, non-fatal master delete failure, recurring 400s (UPCOMING and single occurrence), webhook room status (accepted/declined/tentative/none), `/rooms` cache (success cached, failure not), `prepareMasterCalendarEvent` unit tests.

### Phase 2 — Teams (`FEATURE_TEAMS_MEETING`)
12. ✅ Set `isOnlineMeeting`/`onlineMeetingProvider` on master; extract `joinUrl`, `conferenceId`, dial-in from `onlineMeeting`. Ensure master update never includes `body`. (Built in step 7, since room and Teams share the same master-event mechanism.)
13. ✅ Teams-only events (no room) also get a master. (`CreateEvent.wantsMaster()` triggers on `isOnlineMeeting` alone; covered by the webhook test "master event notification for a teams-only event does not set a room status".)
14. ✅ Render Teams block (link + optional dial-in) in participant invite body. (`buildInviteBodyHtml`, step 9.)
15. ✅ Hide Teams fields for non-participants/non-hosts in event read routes. `GET /event` and `GET /event/{id}` now redact `teamsJoinUrl`/`teamsConferenceId`/`teamsDialIn` to `null` unless the caller is a registered participant or host. Room fields are **not** redacted — they're already visible on the calendar invite to anyone who sees it.
16. ✅ Tests for Teams on/off, detail propagation, visibility, and that master PATCH omits `body`.
17. ⬜ Prod test with toggles set to `maintainers`; verify lobby behaviour (organizer is the Delta mailbox, which never joins). **Not done by this agent** — requires the Teams license on `ikkesvar.delta@nav.no` (external prerequisite) and manual verification in prod.

## External Prerequisites

| What | Needed for | Owner |
|---|---|---|
| ✅ `Place.Read.All` **application** permission + admin consent on prod-gcp `delta-backend` app registration (granted; confirm type is Application) | Phase 1 | Azure/identity admins |
| Teams-enabled license on `ikkesvar.delta@nav.no` | Phase 2 | M365 admins |
| Confirm room `BookInPolicy` allows `ikkesvar.delta@nav.no` | Phase 1 | Exchange admins |

## Out of Scope (v1)

- OBO / host as organizer (possible later iteration)
- Emailing host when the room declines
- RSVP beyond signed up / not signed up. Unchanged behaviour: Outlook **Decline** on a participant invite → unregister (existing webhook); **Accepted/Tentative** → no change (still registered). The master event has no human attendees, so it adds no RSVP flow. Possible later iteration: store the Outlook response (`accepted`/`tentative`/`none`) on the participant for host display only, without affecting registration or capacity.
- Frontend (room picker, Teams toggle, join link display) — separate repo, driven by `GET /features`. The full frontend contract (routes, update rules, 400/502 messages, field visibility) is in `src/main/resources/openapi/documentation.yaml`.

## Risks

- **Teams lobby**: with Delta as organizer, lobby bypass depends on tenant defaults — verify in prod test.
- **Shared mailbox license**: IT may require converting to a regular user mailbox.
- **Graph latency**: synchronous master creation adds ~300–800 ms to event save.
- **Contract change**: new nullable fields on `Event` — coordinate with frontend.

## File Index (for resuming)

Main code, in the order the plan touches them:

| File | What's there |
|---|---|
| `Environment.kt` | `maintainersGroupId`, `FeatureAccess`, `featureRoomBooking`/`featureTeamsMeeting`, `isRoomBookingEnabledFor(groups)`/`isTeamsMeetingEnabledFor(groups)` |
| `feature/Routes.kt` | `GET /features` |
| `room/Models.kt` | `RoomList`, `RoomInfo`, `RoomAvailability`, `RoomAvailabilityRequest`, `MasterEventResult` |
| `room/Routes.kt` | `GET /rooms/search` + `searchRooms`, `GET /rooms`, `GET /rooms/{roomListEmail}`, `POST /rooms/availability` |
| `room/RoomCache.kt` | `RoomCatalog` + `StaleWhileRevalidateCache` (24 h, background refresh, preloaded at startup) |
| `email/CloudClient.kt` | `getRoomLists/getRooms/getRoomAvailability`, `createMasterEvent/updateMasterEvent/deleteMasterEvent`, `prepareMasterCalendarEvent` (never touches `body`), `buildInviteBodyHtml` (top-level fn) |
| `event/Models.kt` | `Event`/`CreateEvent` room+Teams fields, `RoomBookingStatus` enum |
| `event/Database.kt` | `toEvent()`, `addEvent`, `updateEvent` (room/Teams columns), `getMasterCalendarEventId`/`setMasterCalendarEventId`/`getEventIdByMasterCalendarEventId`, `setRoomStatus`, `isRecurringOccurrence` |
| `event/Routes.kt` | `validateRoomAndTeamsToggles`, `validateRoomFields`, `requestsRoomOrTeams()`/`toDraftEvent()`/`withMasterResult()`/`mergeRoomStatus()`/`deleteMasterBestEffort()`, master lifecycle and update merge rules in PUT/POST/DELETE `/admin/event`, `hideTeamsDetailsUnlessParticipantOrHost` on the two GET routes |
| `webhook/Routes.kt` | `processMasterEventNotification` branch in `processNotification` |
| `db/migration/V27__room_and_teams_meeting.sql` | the new columns + partial index |
| `nais.yaml` | `DELTA_MAINTAINERS_GROUP_ID` (renamed from `FAGGRUPPE_ADMIN_GROUP_ID`), `FEATURE_ROOM_BOOKING=maintainers`, `FEATURE_TEAMS_MEETING=off` |
| `README.md` | new Graph permissions + new env vars documented |
| `openapi/documentation.yaml` | `CreateEvent`/`Event` schema updates |

Tests, if you want to see current coverage before adding more:

| Test file | Covers |
|---|---|
| `FeatureToggleTest.kt` | `FeatureAccess` parsing and maintainers-group gating |
| `feature/FeatureRoutesTest.kt` | `GET /features` |
| `room/RoomRoutesTest.kt` | `/rooms*` toggle guard + happy paths + availability validation |
| `email/DummyCloudClientMasterEventTest.kt` | `DummyCloudClient` master event methods |
| `email/MasterCalendarEventTest.kt` | `prepareMasterCalendarEvent`: never sets `body`, room as Resource attendee, Teams-only shape |
| `email/InviteBodyTest.kt` | `buildInviteBodyHtml` (room/Teams block, HTML escaping) |
| `event/DatabasesTest.kt` | room/Teams column round-trip, master-calendar-event-id accessors |
| `event/EventRoutesTest.kt` | toggle 400s, master create/update/delete lifecycle, recurring-event 400, Teams visibility redaction |
| `webhook/WebhookRoutesTest.kt` | master event notification → `room_status`, Teams-only master is a no-op |

`ApplicationTest.kt`'s `StubCloudClient` and `support/TestSupport.kt`'s `RecordingCloudClient` were
both updated for the new `CloudClient` methods — extend `RecordingCloudClient` (records calls,
configurable results via `masterEventResult`/`roomListsResult`/etc.) when adding more route tests.
