# Frontend handoff: room booking and Teams meetings

Brief for the agent implementing this in the Delta frontend (separate repo). Background and
backend design: `docs/teams-meeting-room-booking-plan.md`. **The API contract is
`src/main/resources/openapi/documentation.yaml` in this repo.** Where this document and the
spec disagree, the spec wins.

For `event.inviteMode = SHARED`, use
[`frontend-shared-calendar-handoff.md`](frontend-shared-calendar-handoff.md).
Shared-mode saves are asynchronous: pending calendar sync and Teams provisioning
are distinct from room acceptance and access restrictions. The synchronous
502/no-save behavior below applies to legacy `PER_PARTICIPANT` events only.

## Feature toggle (implement first)

Both features are in prod testing and are enabled only for the Delta maintainers group
(`DELTA_MAINTAINERS_GROUP_ID`). **The frontend must not check the group itself.** Call
`GET /features` and use the response:

```json
{ "roomBooking": true, "teamsMeeting": false }
```

- Fetch it once per session, for example together with the user, and cache it client-side.
- `roomBooking: false` → hide the room picker and don't call `/rooms*` (they return 400).
- `teamsMeeting: false` → hide the Teams toggle.
- **Never send `roomEmail`, `roomName` or `isOnlineMeeting` when the feature is off**. The backend
  returns 400 for them. Leaving them out is always safe (see "Update rules").
- Display of existing data (room name, room status, Teams link) does **not** depend on the
  toggle. Show it whenever the fields are non-null.

The backend decides who is a maintainer from the `groups` claim in the token, so turning a
feature on for everyone later is a backend config change (`maintainers` → `all`) with no
frontend change.

## What to build

1. **Room picker** in the create/edit event form (when `roomBooking`):
   - **Primary: search.** `GET /rooms/search?q=...` (debounce ~250 ms, min 2 characters).
     Terms match in any order, so "fya1 a347" or "kaptein" finds "(RV) FYA1 - A347 Kaptein -
     Videokonf". Results include capacity, floor and wheelchair access.
   - Optional browsing: `GET /rooms` → room lists (buildings), `GET /rooms/{roomListEmail}` →
     rooms in a list.
   - `POST /rooms/availability` with the chosen rooms and the event's start/end, to show
     free/busy. `availabilityView` has one character per slot: `0` free, `1` tentative, `2` busy,
     `3` out of office, `4` working elsewhere.
   - On select, send `roomEmail` = `RoomInfo.emailAddress` and `roomName` = `RoomInfo.displayName`
     (**always both**). Pre-fill the free-text `location` with the room name. The backend does
     not do this for you.
2. **Teams toggle** (when `teamsMeeting`): send `isOnlineMeeting: true`.
3. **Event view:**
   - Room name and `roomStatus`: `PENDING` ("waiting for the room to answer"), `ACCEPTED`
     ("booked"), `DECLINED` ("the room declined, choose another room"). The host gets no email
     for a decline, so the UI has to make it visible.
   - When `isOnlineMeeting` and `teamsJoinUrl` are set: a "Join Teams meeting" link, plus
     `teamsConferenceId`/`teamsDialIn` if they're present.
   - When `isOnlineMeeting` is true but `teamsJoinUrl` is null, the caller isn't registered
     (details are hidden from non-participants). Show something like "Sign up to see the
     Teams link".
4. **Recurring events:** hide or disable room and Teams when creating a series or editing any
   event that has `recurringSeries`. The backend returns 400 for them.

## Update rules (`POST /admin/event/{id}`): read carefully

- `roomEmail`, `roomName` and `isOnlineMeeting` left out or `null` mean **keep the current value**.
  Leaving them out never removes anything.
- **Changing the room:** send the new `roomEmail` and `roomName`. `roomStatus` goes back to
  `PENDING`.
- **Removing a room is not possible.** Don't offer it. The user deletes the event instead.
- **Turning Teams off is not possible.** `isOnlineMeeting: false` on an event with Teams returns
  400. Show the toggle as locked once it's on.
- Editing title or time also moves the room booking and Teams meeting. No extra call is needed.
- Deleting the event cancels the booking and the Teams meeting.

## Errors

- **400**, plain text for local validation (see `components.responses.RoomTeamsBadRequest` in the spec): show the
  message. Possible messages include "Room booking is not enabled", "roomEmail and roomName must
  be set together", "...not supported for recurring events" and "Teams meeting cannot be removed
  from an event; delete the event instead".
- **502** on create/update: the booking or Teams meeting failed in Microsoft Graph, and **nothing
  was saved**. Keep the form open and let the user retry.
- **502** on room lists/search: the Graph lookup failed. Show an error and allow retrying.
- Graph failures on **`POST /rooms/availability`** now return **JSON**, not plain text:
  **400** for a rejected availability request; **502** for service, access, configuration or
  connection failures. The `RoomAvailabilityError` body has `type`, `title`, `status`, `detail`,
  `code`, `upstreamStatus` and `requestId`. `status` is the backend HTTP status; `upstreamStatus`
  is Graph's HTTP status. `code`, `upstreamStatus` and `requestId` can be null.
  Parse JSON based on the response content type; local validation still returns plain text.
  Use `code` for localized messages, with `detail` as the fallback. In particular,
  `ErrorInvalidMergedFreeBusyInterval` means Graph rejected the time range/slot interval,
  **not** that Microsoft is unresponsive: prompt the user to adjust the range or interval
  rather than blindly retrying. Do not turn every 502 into an outage message either.
  Raw Graph exception messages are not exposed.
  Logs include room count, duration in seconds and slot interval, without room addresses or
  free/busy data. These fields help investigate whether failures correlate with short events.
  Per-room errors in a **200** response include Graph's response code when available.
- Saving an event with room/Teams takes roughly 0.3–0.8 s longer. Show a loading state.

## Where to look in the OpenAPI spec

| What | Where |
|---|---|
| Feature flags | `GET /features`, schema `Features` |
| Room search | `GET /rooms/search`, `GET /rooms`, `GET /rooms/{roomListEmail}`, `POST /rooms/availability`; schemas `RoomList`, `RoomInfo`, `RoomAvailabilityRequest`, `RoomAvailability` |
| Request fields | schema `CreateEvent`: `roomEmail`, `roomName`, `isOnlineMeeting` (descriptions have the rules) |
| Response fields | schema `Event`: `roomEmail`, `roomName`, `roomStatus`, `isOnlineMeeting`, `teamsJoinUrl`, `teamsConferenceId`, `teamsDialIn` |
| Update semantics | `POST /admin/event/{id}` description |
| Errors | `components.responses.RoomTeamsBadRequest` / `RoomTeamsBadGateway`; schema `RoomAvailabilityError` for availability lookup failures |
| Visibility of Teams details | `GET /event` and `GET /event/{id}` descriptions |

Don't send `roomStatus` or `teams*` in requests. They're server-managed and ignored.

## Testing locally

Locally, the backend treats the logged-in test user as a maintainer, so setting
`FEATURE_ROOM_BOOKING=maintainers` or `all` (and the same for `FEATURE_TEAMS_MEETING`) turns the
features on. The local cloud client returns fake room lists, rooms and availability, and a fake
Teams link.
