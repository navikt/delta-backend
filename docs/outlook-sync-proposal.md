# Delta: why Outlook updates get lost, and a proposed fix

Read against delta-backend `6a349de5` and delta-frontend `518db4d1`. Code links point to those commits.

## TL;DR

When a host edits an event, some participants' Outlook copies keep the old details while others on the same event get the update. Signing off and on again gives that person a correct invite.

This is an implementation problem in how Delta writes to Graph, not a limit of the per-participant model. Every participant copy is updated fire-and-forget. Delta never retries or records a failed write. The SDK retries single calls a few times, but not items inside a batch, and edits are sent as a batch today. A copy that returns 404 is never re-created. Signing off and on only works because signup always creates a brand new invite.

Proposal: fix today's model first by building the sync worker from ADR-0001 §2, then pilot the shared event behind a toggle, then invitations. The first step is needed either way, because ADR v1 only applies to new events, so every existing event would keep the bug.

## Root cause

### 1. Failed calendar writes are dropped

- After an edit the route saves, starts a raw `Thread` and returns 200 ([Routes.kt L436-451][r-edit]).
- `batchUpdateOrCreateEvents` turns every non-2xx item into a `Left` ([CloudClient.kt L393-394][c-left]). If the outer batch call throws, every participant is marked failed, including items that may already have gone through in an earlier chunk ([L397-403][c-catch]).
- Failed results only go to a `log.error` ([Email.kt L43-60][e-batch]).
- A participant with a stored `calendar_event_id` is always PATCHed, never re-created, so a 404 is permanent ([CloudClient.kt L353-361][c-patch]).
- Signup builds a fresh invite from the current event ([Routes.kt L506-531][r-signup]). That is why signing off and on gives the participant a correct invite again.

### 2. Throttling on the Delta mailbox (likely main trigger, confirm in logs)

All calendar calls go through one mailbox, and Outlook allows four concurrent requests per app and mailbox ([Outlook limits][ms-limits]).

- Until 2026-03-12 the edit route started one thread per participant ([e04517a9], replaced in [541036f5]). Any event with more than four participants put more than four requests on the mailbox at once. The SDK retries a throttled single call a few times, and whatever still failed was dropped. Before 2026-03-11 those failures weren't logged at all ([2ab2bd30] added the first `log.error`), so that period left no trace.
- Since 2026-03-12 updates go in one `$batch`. Graph sends at most four items from a batch to Outlook at a time, so a batch on its own stays inside the limit ([Outlook limits][ms-limits]).
- Items can still get 429 when other traffic hits the same mailbox at the same moment: the other pod (two replicas, [nais.yaml L21-23][nais]), signup and sign-off threads, master event writes, room lookups, and probably the webhook, which does one GET per change notification ([webhook/Routes.kt L109-119][w-get]). I haven't checked whether our own PATCHes trigger those notifications.
- The SDK does not retry batch items. Microsoft: "throttled requests that were part of a batch aren't retried automatically" ([Throttling and batching][ms-batch]).

### 3. The e-mail checkbox controls the calendar sync

"Send e-post til deltakere ved endringer" decides whether participant copies are updated at all ([Routes.kt L447-449][r-gate], series edits [L292-300][r-gate-series]). Delta sends no separate e-mail on edit, so the only thing the checkbox switches off is the Outlook update. Unticking it for a small change freezes everyone's invite. It is shown on every edit, ticked by default ([createEventForm.tsx L337, L359][f-default]), with no help text ([L977-983][f-checkbox]). The flag came in with [376ec5fc] and the checkbox with delta-frontend [b41bad6c], both in September 2024.

Room and Teams live on the master event, which is updated on every single-event edit that has a room or Teams meeting, regardless of the checkbox ([Routes.kt L346-371][r-master]). So a room change can be booked correctly while participants' invites still show the old room.

### 4. Smaller issues

- The participant list is read before the edit is saved ([Routes.kt L420-436][r-snapshot]). Someone who signs up in between gets an invite built from the old event.
- The work runs in raw threads (nine places in Routes.kt start one). If the pod stops, the work is gone and nothing picks it up.
- Shortening a recurring series deletes the occurrences without cancelling their invites ([RecurringDatabase.kt L224-231][rec-shorten]), which leaves invites nobody can update.
- We store default event ids. Graph says the id "changes when the item is moved" unless `Prefer: IdType="ImmutableId"` is set ([event resource][ms-event]). Probably rare in an organizer calendar, but it would show up as 404s.

Not the cause: the Graph note that an attendees-only update notifies only changed attendees ([event-update notes][ms-update]) is the right rule for the ADR's design, but today's PATCH sends subject, body, start, end, location and attendees together ([CloudClient.kt L257-269][c-payload]).

## Before writing code: confirm with prod data (half a day)

1. Check how far back prod logs go.
2. Grep `Failed to update/create calendar event for` and group by the end of the message:
   - `Batch request failed with status 429`: throttling
   - `status 404`: dead ids
   - `status 5xx`: transient Exchange errors
   - `Batch request failed` with a stack trace: the whole call threw (over 20 participants, or the outer call was throttled)
3. Correlate with pod name, and with `Failed to get attendee status for` in the same seconds.
4. For an event where participants have stale invites, ask the host whether the checkbox was ticked, and compare edit and signup times. The `event` and `participant` tables have no `created_at` or `updated_at`, so use the request log (`POST /admin/event/{id}` for edits, `POST /user/event/{id}` for signups).

Many 429s around edit times confirms #2. Few errors while people still see stale invites points to the checkbox or signup timing.

## Proposal

### Phase 0: make today's sync reliable (1-2 weeks)

**PR1: retry inside the batch (1-2 days)**

- Retry each failed item on 429, 503 and 504 after that item's `Retry-After`, with a cap on attempts.
- Send and handle each 20-item chunk on its own, so an exception in chunk 3 doesn't throw away the results of chunks 1 and 2.
- Set `transactionId` on creates. Graph documents it as the way "to avoid redundant POST operations in case of client retries" ([event resource][ms-event]).
- On 404, re-read the participant. If they are still registered, create a new invite and store the new id.
- Log event id, participant row id, status, Retry-After and Graph request-id. Remove e-mail addresses from these log lines ([Email.kt L27, L35, L52, L69][e-pii]) and drop the `println(preparedStatement)` in `registerForEvent` ([Database.kt L535-536][d-println]).

**PR2: durable outbox and one worker**

Write a `calendar_sync` row in the same transaction as the change (edit, signup, sign-off, delete, series edit) and let one worker process the rows. This replaces the nine threads. Things to get right:

- DELETE rows must carry the `calendar_event_id` (and the title for the cancellation mail), because the participant or event row is gone by the time the worker runs. No cascading FK.
- Never merge away a DELETE. Sign-off followed by sign-on must give "delete old invite, create new". Only merge consecutive updates for the same participant.
- Claim rows with a lease (`UPDATE ... SET locked_until`, commit), call Graph outside the transaction, and record the result in a new one. Don't hold DB locks during Graph calls: the pool is five connections on REPEATABLE_READ ([DatabaseConfig.kt L14-18][db-pool]).
- Build update payloads from the DB state when the row runs, not when it was enqueued. That removes the snapshot race and the ordering problem between quick edits.
- Signups and sign-offs go before bulk series updates. A series edit can be up to 130 occurrences ([Recurrence.kt L11][rec-max]) times the participants.
- Keep a toggle that falls back to the thread path. Prod is the only environment that talks to real Graph.
- Shape the table so ADR §2 can reuse it (a target that is either a participant copy or a shared event).

**PR3: checkbox and heal**

- Decide what the checkbox means (see open questions).
- One-off heal for upcoming events: GET each organizer copy (`$select=subject,start,end,location`), compare with Delta (normalize time zones, Graph returns UTC by default), PATCH only the ones that differ, and POST for participants without an id. Run it in chunks with a daily cap, and count with a dry run first. Exchange allows 10,000 recipients per mailbox per day, applied to "all outbound and internal messages" ([Exchange Online limits][ms-exo]). Give people a heads-up that they may get one corrected invite.

**PR4: mailto separator (frontend, small)**

"Send e-post til deltakere" in the export menu joins addresses with `", "`, while "Kopier alle e-postadresser" uses `";"` ([exportParticipants.tsx L12-13][f-mailto]). The comma list may not split into separate recipients in Outlook. Worth testing with a long recipient list in Outlook desktop and switching to `";"`.

**Tests**

- Every edit test posts `sendNotificationEmail = false` (for example [EventRoutesTest.kt L308][t-false]), so the edit and series-edit sync never runs in tests. `RecordingCloudClient` always succeeds ([TestSupport.kt L121][t-fake]), so no failure path is tested either.
- Add a scripted fake with results per participant: 429 with Retry-After, 404, 503, and an exception on chunk 2.
- Worker tests: sign-off then sign-on gives one DELETE and one POST, deleting an event keeps its DELETE rows, 429 reschedules by Retry-After, and a signup isn't stuck behind a series edit. Testcontainers is already set up.

### Phase 1: shared event (ADR §1-2) as a pilot

Behind the existing feature toggle, for new non-recurring events from a few pilot hosts, after a spike against a real test mailbox. The spike should check:

- that an attendees-only PATCH notifies only the added or removed people in our tenant
- that existing responses survive a PATCH of the full attendee list
- that editing the body keeps the Teams join info. The ADR plans a read-modify-write ([ADR L61][adr-body]), while the master event code says touching the body removes it ([CloudClient.kt L156-161][c-body])
- whether `/cancel` frees the room, and what forwarding does to the organizer copy
- the attendee cap: Graph says 500 attendees per event ([event resource][ms-event]), the ADR caps at 490 ([ADR L199][adr-cap]), but `participantLimit` can be unlimited

The ADR also needs to say what the series (UPCOMING) path does in shared mode, and what happens when a row is enqueued while the worker holds it.

### Phase 2: invitations and groups (ADR §3)

A separate track once Phase 1 is stable. It needs `User.ReadBasic.All` and a security review, and it isn't needed to fix the lost updates.

## Why not the alternatives

**Ship ADR-0001 as written now.** v1 only covers new events: "Existing events keep their personal invites until the event is over" ([ADR L210][adr-new]). Every existing event and series would keep losing updates, and the first action items are a permission request and a security review. Alternative C is rejected because "each edit is still N Graph writes, which is why updates get lost today" ([ADR L230][adr-altc]), but updates are lost because nothing retries or records a failure, not because there are N writes. With one shared event a single lost PATCH would hit everyone, so the worker is needed in both models.

**Only patch the retry.** Worth doing first (PR1), but the threads, the snapshot race, the checkbox and the lack of visibility would remain.

## About the earlier shared-event version

The shared-event version from 2023 ([bfe52c81]) PATCHed subject, body, start, end, location and the full attendee list on every signup. That counts as a details update, which goes to all attendees. Graph documents that "an event update that includes only the attendees property in the request body sends a meeting update to only the attendees that have changed" ([event-update notes][ms-update]). So the update spam most likely came from what we sent, not from the shared model itself. The Phase 1 spike should confirm this in our tenant before anything is rolled out.

## Open questions

1. **Checkbox.** Graph has no documented way to update an attendee's copy without sending them an update, so "don't send e-mail" can only mean "don't update calendars". My suggestion: always sync changes to time, place, room, Teams and description, and remove the checkbox or limit it to cosmetic fields. Worth asking a couple of hosts what they use it for first.
2. **Heal.** Run it once for upcoming events after PR2, with a heads-up in advance.
3. **Worker exclusivity.** Leases with per-row claiming rather than relying on leader election alone, since leadership can move between pods.
4. **Monitoring.** Structured logs and an alert on rows that keep failing or are older than an hour now, metrics later.

[r-edit]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/main/kotlin/no/nav/delta/event/Routes.kt#L436-L451
[r-gate]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/main/kotlin/no/nav/delta/event/Routes.kt#L447-L449
[r-gate-series]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/main/kotlin/no/nav/delta/event/Routes.kt#L292-L300
[r-master]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/main/kotlin/no/nav/delta/event/Routes.kt#L346-L371
[r-snapshot]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/main/kotlin/no/nav/delta/event/Routes.kt#L420-L436
[r-signup]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/main/kotlin/no/nav/delta/event/Routes.kt#L506-L531
[c-payload]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/main/kotlin/no/nav/delta/email/CloudClient.kt#L257-L269
[c-patch]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/main/kotlin/no/nav/delta/email/CloudClient.kt#L353-L361
[c-left]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/main/kotlin/no/nav/delta/email/CloudClient.kt#L393-L394
[c-catch]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/main/kotlin/no/nav/delta/email/CloudClient.kt#L397-L403
[c-body]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/main/kotlin/no/nav/delta/email/CloudClient.kt#L156-L161
[e-batch]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/main/kotlin/no/nav/delta/email/Email.kt#L43-L60
[e-pii]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/main/kotlin/no/nav/delta/email/Email.kt#L27
[d-println]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/main/kotlin/no/nav/delta/event/Database.kt#L535-L536
[rec-shorten]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/main/kotlin/no/nav/delta/event/RecurringDatabase.kt#L224-L231
[rec-max]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/main/kotlin/no/nav/delta/event/Recurrence.kt#L11
[w-get]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/main/kotlin/no/nav/delta/webhook/Routes.kt#L109-L119
[db-pool]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/main/kotlin/no/nav/delta/plugins/DatabaseConfig.kt#L14-L18
[nais]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/nais.yaml#L21-L23
[t-false]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/test/kotlin/no/nav/delta/event/EventRoutesTest.kt#L308
[t-fake]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/src/test/kotlin/no/nav/delta/support/TestSupport.kt#L121
[adr-body]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/docs/adr/0001-shared-calendar-event.md?plain=1#L61
[adr-cap]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/docs/adr/0001-shared-calendar-event.md?plain=1#L199
[adr-new]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/docs/adr/0001-shared-calendar-event.md?plain=1#L210
[adr-altc]: https://github.com/navikt/delta-backend/blob/6a349de5b69d64b57680145cc51b8b76377e2663/docs/adr/0001-shared-calendar-event.md?plain=1#L230
[f-checkbox]: https://github.com/navikt/delta-frontend/blob/518db4d10557d928e57e55fa6c436c4a901f52c8/src/components/createEventForm.tsx#L977-L983
[f-default]: https://github.com/navikt/delta-frontend/blob/518db4d10557d928e57e55fa6c436c4a901f52c8/src/components/createEventForm.tsx#L337-L359
[b41bad6c]: https://github.com/navikt/delta-frontend/commit/b41bad6c
[f-mailto]: https://github.com/navikt/delta-frontend/blob/518db4d10557d928e57e55fa6c436c4a901f52c8/src/app/event/%5Bid%5D/exportParticipants.tsx#L12-L13
[e04517a9]: https://github.com/navikt/delta-backend/commit/e04517a9
[541036f5]: https://github.com/navikt/delta-backend/commit/541036f5
[2ab2bd30]: https://github.com/navikt/delta-backend/commit/2ab2bd30
[376ec5fc]: https://github.com/navikt/delta-backend/commit/376ec5fc
[bfe52c81]: https://github.com/navikt/delta-backend/commit/bfe52c81
[ms-limits]: https://learn.microsoft.com/en-us/graph/throttling-limits#outlook-service-limits
[ms-batch]: https://learn.microsoft.com/en-us/graph/throttling#throttling-and-batching
[ms-update]: https://learn.microsoft.com/en-us/graph/api/event-update?view=graph-rest-1.0#notes-for-updating-specific-properties
[ms-event]: https://learn.microsoft.com/en-us/graph/api/resources/event?view=graph-rest-1.0
[ms-exo]: https://learn.microsoft.com/en-us/office365/servicedescriptions/exchange-online-service-description/exchange-online-limits#sending-limits
