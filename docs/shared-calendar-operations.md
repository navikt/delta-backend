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
Group detection also depends on the tenant accepting the advanced alias query
with the application's group-read permission; verify this before rollout.
