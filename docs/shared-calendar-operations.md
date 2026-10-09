# Shared calendar operations

`FEATURE_SHARED_CALENDAR` starts at `off`. Enable `maintainers` only when the
frontend handles pending/failed saves and `User.Read.All` has admin consent
for people search. Existing SHARED events continue synchronizing when the flag
is turned off; it changes the model selected for new events, not existing ones.

## Recovery

Calendar work is persisted in PostgreSQL, including cancellation after deleting
the Delta event. Restarting a pod is not a reason to recreate the event manually.
Hosts can retry failed work through `POST /admin/event/{id}/calendar/retry`.
Inspect the sanitized failure before retrying permission or mailbox-configuration
problems. Do not include recipient addresses in incident logs.

Permanent Graph failures log the HTTP status, a bounded machine-readable error
code and the operation. `CLASSIFY_ATTENDEE` means the directory group/alias lookup
failed; it can fail before the calendar creation POST is sent. `CREATE_EVENT`
means creation or its immediate Teams metadata refresh failed. `READ_EVENT`,
`UPDATE_ATTENDEES`, `UPDATE_DETAILS` and `CANCEL_EVENT` identify later sync steps.
Nested directory failures retain `CLASSIFY_ATTENDEE` rather than the outer
operation. The worker does not log Graph error messages, request URLs, payloads
or exception causes, because these can contain recipient or event data.

For a 400, first use the operation and code to distinguish an unsupported
directory query from an invalid calendar request or a locally rejected group
invitation (`GroupInvitationsNotSupported`). Do not retry unchanged permanent
failures repeatedly. After the cause is corrected, retry the existing Delta
event through the host retry endpoint instead of creating a duplicate event.

### Unsupported attendee-classification query

Production returned `400 Request_UnsupportedQuery CLASSIFY_ATTENDEE` for the
combined `mail eq ... or proxyAddresses/any(p: ... or ...)` filter, before the
calendar creation request. Delta now queries `/groups` separately using:

```text
mail eq '<normalized-email>'
proxyAddresses/any(p:p eq 'smtp:<normalized-email>')
proxyAddresses/any(p:p eq 'SMTP:<normalized-email>')
```

Each request selects only `id`, uses `$top=1`, `$count=true` and
`ConsistencyLevel: eventual`, and stops as soon as it finds a group. All three
must return a complete empty result before the address is treated as an
individual. Errors never bypass group validation. This costs up to three
directory requests per newly checked address; sync skips known attendees.

The [group filter capabilities](https://learn.microsoft.com/en-us/graph/aad-advanced-queries#group-properties)
document equality on `mail` and `proxyAddresses/any(p:p)`, and
[List groups](https://learn.microsoft.com/en-us/graph/api/group-list?view=graph-rest-1.0)
supports the query parameters and `GroupMember.Read.All` application permission.
Those primitive filters being supported does not prove the old compound filter
was supported. The observed rejection establishes a compatibility problem, not
evidence of a recent Microsoft contract change. The split queries still need
verification with the production tenant.

Group classification has documented limits: `/groups` excludes dynamic
distribution groups, and directory replication/eventual-consistency delays can
hide recent group or alias changes. An empty result is not proof that every
Exchange recipient type is an individual. Do not use this check to claim
support for arbitrary distribution-list invitations.

## Graph REST contract review

Reviewed against the current Microsoft Graph **v1.0** REST documentation on
2026-10-09. This is a code/documentation comparison, not a live mailbox test or
confirmation of application permissions granted in Entra. No historical
breaking contract change was established.

| Operation | REST contract and application permission | Review |
| --- | --- | --- |
| Create legacy, master or shared event | [POST /users/{mailbox}/calendar/events](https://learn.microsoft.com/en-us/graph/api/user-post-events?view=graph-rest-1.0); `Calendars.ReadWrite` | Endpoint and creation fields match: human/resource attendees, `responseRequested`, `transactionId`, `isOnlineMeeting` and `teamsForBusiness`. |
| Read event and RSVP/Teams metadata | [GET /users/{mailbox}/events/{id}](https://learn.microsoft.com/en-us/graph/api/event-get?view=graph-rest-1.0); `Calendars.Read` (covered by `Calendars.ReadWrite`) | `$select` fields match. A missing Teams join URL is not evidence of completed provisioning. |
| Change attendees | [PATCH /users/{mailbox}/events/{id}](https://learn.microsoft.com/en-us/graph/api/event-update?view=graph-rest-1.0); `Calendars.ReadWrite` | Attendees-only payload implements the documented selective-notification rule. Removing distribution-list members is the documented exception. Notification delivery and response preservation still require mailbox tests. |
| Change details | [PATCH event](https://learn.microsoft.com/en-us/graph/api/event-update?view=graph-rest-1.0); `Calendars.ReadWrite` | Shared updates read and preserve the existing Teams meeting blob; legacy master updates leave the body untouched. |
| Cancel shared event | [POST /users/{mailbox}/events/{id}/cancel](https://learn.microsoft.com/en-us/graph/api/event-cancel?view=graph-rest-1.0); `Calendars.ReadWrite` | Organizer-only action, optional comment, `202 Accepted`; not equivalent to confirmed delivery of cancellations. |
| Delete legacy/master event | [DELETE /users/{mailbox}/events/{id}](https://learn.microsoft.com/en-us/graph/api/event-delete?view=graph-rest-1.0); `Calendars.ReadWrite` | Supported; successful response is `204 No Content`. |
| Batch legacy calendar writes | [POST /$batch](https://learn.microsoft.com/en-us/graph/json-batching); underlying operation permissions | Graph limits each batch to 20 requests. SDK batch collection handles splitting; subrequest failures must be handled separately from outer success. |
| Search people | [GET /users](https://learn.microsoft.com/en-us/graph/api/user-list?view=graph-rest-1.0); `User.Read.All` | `$filter` with `startswith`, `$select` and `$top` match. Pagination uses `@odata.nextLink`, not unsupported `$skip`. |
| Read user display name | [GET /users/{id-or-userPrincipalName}](https://learn.microsoft.com/en-us/graph/api/user-get?view=graph-rest-1.0); `User.Read.All` | Supported for an ID or UPN. An arbitrary SMTP alias is not a documented substitute for UPN. |
| Classify group addresses | [GET /groups](https://learn.microsoft.com/en-us/graph/api/group-list?view=graph-rest-1.0); `GroupMember.Read.All` supported | Separate primitive filters replace the rejected compound query; dynamic distribution groups and replication delays remain limitations. |
| Send explanatory email | [POST /users/{mailbox}/sendMail](https://learn.microsoft.com/en-us/graph/api/user-sendmail?view=graph-rest-1.0); `Mail.Send` | `message` and `saveToSentItems` match. `202 Accepted` is acceptance for processing, not proof of delivery. |
| List room lists, rooms and rooms within a list | [GET /places/...](https://learn.microsoft.com/en-us/graph/api/place-list?view=graph-rest-1.0); `Place.Read.All` | Paths and `$top`/`$skip` match. A room-list collection is addressed by email, not place ID. The configured page size of 999 is not a documented `/places` maximum. |
| Check room availability | [POST /users/{mailbox}/calendar/getSchedule](https://learn.microsoft.com/en-us/graph/api/calendar-getschedule?view=graph-rest-1.0); `Calendars.ReadBasic` (covered by `Calendars.ReadWrite`) | Request fields match; service limits and response errors must be respected independently of event creation. |
| Create, renew and delete subscriptions | [POST /subscriptions](https://learn.microsoft.com/en-us/graph/api/subscription-post-subscriptions?view=graph-rest-1.0), [PATCH /subscriptions/{id}](https://learn.microsoft.com/en-us/graph/api/subscription-update?view=graph-rest-1.0), [DELETE /subscriptions/{id}](https://learn.microsoft.com/en-us/graph/api/subscription-delete?view=graph-rest-1.0); calendar read permission | Event resource and methods match. The 71-hour lifetime is within the [10,080-minute Outlook event limit](https://learn.microsoft.com/en-us/graph/change-notifications-overview#subscription-lifetime). |
| Receive notifications | [Webhook delivery contract](https://learn.microsoft.com/en-us/graph/change-notifications-delivery-webhooks) | Plain-text validation-token echo, client-state verification and prompt acknowledgment match. |

Event GET/PATCH documentation does not establish `If-Match` enforcement for
events. A quoted ETag is not proof that forwards cannot race with an attendee
update. This remains an explicit tenant test, not a verified concurrency
guarantee. Teams support likewise depends on the organizer mailbox's allowed
online-meeting providers and provisioning; REST payload compatibility alone
does not establish either.

The [event resource contract](https://learn.microsoft.com/en-us/graph/api/resources/event?view=graph-rest-1.0)
limits the entire attendee collection to 500, including resources. Adapter
creation and attendees-only updates now enforce that total before any directory
or calendar request. The repository already applies a more conservative
490-attendee ceiling, including the room.

The documentation does not guarantee that resending existing `attendee.status`
in an organizer PATCH preserves responses without notification, nor that an
organizer event moved to Deleted Items by `/cancel` remains readable with
`isCancelled=true`. Both remain mailbox checks; cancellation recovery also
handles `404` as complete.

Pending calendar sync does not confirm the room booking. Room acceptance and
Teams provisioning must be inspected independently.

Transient transport failures and Teams provisioning use exponential backoff
(30 seconds initially, up to 15 minutes), respecting a longer Graph Retry-After.
After ten attempts, work becomes FAILED and requires a host retry. Delivered
meeting-detail changes are checkpointed: waiting for a Teams URL does not resend
the same detail update to every attendee.

Creation retries reuse both the transaction ID and the original creation payload.
If deletion follows a lost creation response, recovery resolves that same creation
before cancelling it. The recovery payload is cleared once the Graph ID is
durably recorded, or cancellation completes.

Rejected Outlook acceptances produce a separate explanatory email to that person,
not a meeting update to other attendees. Delivery is at least once: a crash after
sending but before recording acknowledgement can repeat that explanatory email.

RSVP timestamps are compared and persisted at PostgreSQL's microsecond precision.
Replaying the same higher-precision Graph response must not create another refusal
after the first one has been acknowledged. Responses separated by at least one
microsecond remain ordered; sub-microsecond differences cannot be distinguished
by the stored timestamp.

## Monitoring

Nais scrapes `/internal/metrics` in Prometheus text format. Business metrics
contain no event IDs, people, addresses or free-text error labels.

Suggested alert rules:

```promql
max(delta_calendar_oldest_pending_seconds{app="delta-backend"}) > 3600
```

```promql
max(delta_calendar_oldest_failed_seconds{app="delta-backend"}) > 3600
```

```promql
max(delta_calendar_recipients_today{app="delta-backend"}) >= 7000
```

Set up these rules in the team's Grafana alerting with the team's existing
notification contact point. Database-backed gauges are repeated by each replica:
use `max`, not `sum`. Attempt/reconciliation counters are per process; use
`sum(rate(...))` for them.

Recipient accounting estimates sends requested by Delta, not all traffic in the
mailbox. Other senders, retries with ambiguous outcomes and Exchange forwarding
can consume quota too. The 70% threshold is an early warning, not a sending quota
enforcement mechanism.

## Real mailbox checks

Local dummy and recording clients cannot prove delivery in Outlook. Use an
organizer mailbox and at least two individually addressed colleagues/test
mailboxes, with informed agreement before sending anything.

1. Create a meeting; confirm each attendee gets one invitation.
2. Accept, decline and tentatively accept; confirm no other attendee gets an
   update and Delta applies the expected registration/host rules.
3. Add and remove one person with an attendees-only PATCH. Confirm only the
   changed person receives mail and existing responses survive.
4. Forward from an attendee mailbox. Confirm the forwardee appears on the
   organizer copy and is adopted without an outgoing PATCH.
5. Exercise a forward arriving between GET and PATCH, recording conditional-write
   support and whether the new attendee survives.
6. Reinvite a declined person; verify cancellation followed by a new invite
   without disturbing other people's responses.
7. Edit meeting details as a control; confirm everyone receives the intended
   update and the Teams join section remains intact.
8. Cancel; confirm the room is freed and all attendees receive cancellation.
9. Check unsupported forwarded distribution-list behavior separately; Microsoft
   documents an all-attendee update when removing a distribution-list member.

Delegated tests in a personal calendar are useful, but repeat critical cases
with app-only access to the organizer mailbox. Never paste access tokens into
committed scripts or documentation.

Conditional event updates remain a tenant-validation item. A current snapshot
and a genuine ETag are used where available, but event-update If-Match behavior
is not a proven guarantee against a forward arriving during the GET/PATCH window.
Group detection also depends on the tenant accepting the separate alias queries
with the application's group-read permission; verify this before rollout.
