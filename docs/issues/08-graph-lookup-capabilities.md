# Make room and people lookup dependencies explicit

## In short

Room and people lookup should be easy to understand and test without knowing
Delta's mail, subscriptions and calendar synchronization. Give those consumers
focused contracts instead of the entire cloud client.

## What to build

Migrate room catalog loading, live availability and people search to required,
consumer-shaped capabilities. Keep one production Graph transport and a
temporary compatibility facade where needed; do not duplicate credentials or
create an interface for every endpoint.

Keep caching, validation and failure policy identifiable. Isolate synchronous
external calls at an explicit blocking-execution seam without introducing
suspension inside the cache's existing synchronized section.

## Acceptance criteria

- [ ] Room and directory consumers depend only on capabilities they use; unrelated calendar/mail methods are not required by their fakes.
- [ ] Capability implementations are required explicitly rather than inheriting runtime unsupported defaults.
- [ ] Room catalog caching and live availability remain separate policies; existing stale-refresh and failure-backoff behavior is preserved.
- [ ] Feature/business validation can be exercised without Ktor; routes retain request parsing and HTTP outcome mapping.
- [ ] Small recording fakes fail explicitly on unexpected calls, while SDK-backed tests continue proving request/response mapping.
- [ ] Room/directory route fixtures do not start PostgreSQL solely for unrelated setup.
- [ ] A blocked provider does not prevent an independent health request from completing before the provider is released.
- [ ] Endpoint paths, JSON, access rules, validation limits and existing HTTP failures remain compatible.
- [ ] Characterization tests cover schedule identity, per-room errors and paging completion; discrepancies become explicitly scoped fixes rather than hidden changes during extraction.

## Implementation context and proof

`email/CloudClient.kt` combines unrelated capabilities. `RoomCatalog` in
`room/RoomCache.kt`, `roomApi` and `directoryApi` need narrow subsets.
Room/directory models already avoid much of the SDK leakage; build on that.

`RecordingCloudClient` and directory test delegation demonstrate current
substitution burden. `RoomRoutesTest` initializes a database that the room
workflow does not use. Preserve clock/executor seams in cache tests.

Availability currently uses response position as its primary room identity;
Graph documents `scheduleId` as the identifying SMTP address. Paging can
return accumulated results without establishing completeness. Characterize
both at the SDK seam, but do not bundle a new completeness/response policy
into a nominally behavior-preserving extraction.

Use `RoomRoutesTest`, `StaleWhileRevalidateCacheTest`,
`DirectoryRoutesTest` and relevant SDK adapter tests. Inspect current tests and
worktree before extending them; these areas may have concurrent changes.
Interceptor tests prove payload behavior, not production transport cancellation
or tenant behavior.

Sources:
https://learn.microsoft.com/en-us/graph/api/resources/scheduleinformation?view=graph-rest-1.0
https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/-i-o.html

## Out of scope

Rewriting all of `CloudClient`, changing shared-calendar errors or subscription
models, blanket retries, cache replacement and claiming coroutine timeout
automatically aborts blocking transport calls.
