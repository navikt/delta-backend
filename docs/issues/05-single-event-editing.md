# Make single-event editing easy to follow

## In short

Developers should be able to explain what an event edit changes, preserves and
notifies without reading Graph compensation and SQL composition inside a
large HTTP handler.

## What to build

Extract single-event editing into a cohesive operation. Give merge rules,
validation, current-state authorization, atomic persistence and calendar
decisions explicit owners. Include single-occurrence edits of recurring events;
leave UPCOMING series changes for their own workflow.

Include category replacement, both through the edit request and the dedicated
category endpoint, without duplicating authorization or transaction rules.

## Acceptance criteria

- [ ] Single-edit and category routes contain no field-merge policy, host lookup orchestration, Graph compensation, commits or direct thread creation.
- [ ] Field-preservation and notification rules can be tested without HTTP or a real Graph mailbox.
- [ ] Omitted/null room and Teams fields preserve their existing meanings; Teams removal and recurring room/Teams restrictions remain intact.
- [ ] Database fields and category changes commit together or roll back together.
- [ ] Authorization and invariant checks occur inside the protected mutation.
- [ ] Capacity edits and concurrent participant changes preserve the chosen existing invariant policy; any unresolved reduction policy is made explicit before behavior changes.
- [ ] Legacy master updates retain synchronous behavior and existing room-status merging; Graph failure does not save the edit.
- [ ] Shared attendee changes, details changes and category-only changes produce the appropriate outgoing intent without unrelated notifications.
- [ ] Existing JSON, status, visibility and notification-suppression contracts remain stable.

## Implementation context and proof

Start with `POST /admin/event/{id}` and `POST /admin/event/{id}/category`
in `event/Routes.kt`, `updateEvent()`/`setCategories()` in `event/Database.kt`,
and shared `updateInternal()`.

The current route owns field merging, master lookup/create/update, compensation,
separate persistence calls and notification preparation. Moving the whole block
unchanged into a large service is not sufficient: the edit sequence and its
business decisions must be navigable, with transport and SQL details hidden.

Use `EventRoutesTest`, `SharedCalendarRoutesTest`, operation tests and real
PostgreSQL rollback/concurrency tests. Preserve SDK wire tests for payload
semantics. Business orchestration must not depend on `ApplicationCall` or
HTTP-aware exception methods; routes retain compatible response mapping.

## Out of scope

UPCOMING series edits, a new PATCH contract, changing nullable request semantics,
legacy asynchronous saves and comprehensive delivery recovery.
