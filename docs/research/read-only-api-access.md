# Read-only API access for Delta

## First increment

The API accepts M2M tokens carrying the custom `delta.read` application role on `GET /event` and
`GET /category`. The event list can additionally filter public events by one category, a
participant email, and an event-start period (`from` inclusive, `to` exclusive). An app-only token
without the role is forbidden from these reads, and a token carrying the role is forbidden from
every other JWT-authenticated API route, including mutation routes. M2M callers cannot use
`onlyMine` or `onlyJoined`; `GET /event/{id}` remains unavailable to M2M callers until private-event
visibility is decided. Existing user-token behavior is unchanged.

`nais.yaml` grants `delta.read` to `security-champion-stats-backend` in namespace `appsec` on
`prod-gcp`. No other consumer role grants were added.

## Feasibility

**Yes.** The intended model is application-level access control: explicitly authorize a consumer application to use Delta's API, then allow it to read approved data without restricting results to an employee represented by the token. M2M callers with `delta.read` can list public events and categories, and can filter public events by category, participant email, and event start period.

The code uses **events** and **categories**; this note treats the request's “meetings” and “tags” as those concepts. Confirm that these are the intended product terms.

## What exists today

### Filtering by category

`GET /event` accepts comma-separated category IDs in `categories`; `GET /category` lists categories. The database adds a predicate for every supplied ID, so multiple IDs mean an event must match **all** of them. The normal event list is public-only, but `onlyMine=true` or `onlyJoined=true` disables that public-only condition. The M2M attendance lookup requires exactly one category and keeps the public-only filter.

Sources: [`Routes.kt:27–48, 561–563`](../../src/main/kotlin/no/nav/delta/event/Routes.kt), [`Database.kt:379–409, 436–499, 777–788`](../../src/main/kotlin/no/nav/delta/event/Database.kt), [`Models.kt:89–92`](../../src/main/kotlin/no/nav/delta/event/Models.kt), [`V17__add_category.sql:1–9`](../../src/main/resources/db/migration/V17__add_category.sql), [`documentation.yaml:197–241, 393–403`](../../src/main/resources/openapi/documentation.yaml).

### Finding events a user joined

`GET /event?onlyJoined=true` filters using the caller's authenticated email and selects participant registrations (`type = 'PARTICIPANT'`), not hosts. The email is taken from the principal, not from a caller-supplied query parameter. M2M callers cannot use `onlyJoined`; their attendance lookup instead supplies `participantEmail`, exactly one category, and `from`/`to` ISO local date-times. It matches event start times in the half-open interval `[from, to)`.

Sources: [`Routes.kt:29–48, 613–619`](../../src/main/kotlin/no/nav/delta/event/Routes.kt), [`Database.kt:379–409`](../../src/main/kotlin/no/nav/delta/event/Database.kt), [`V16__add_participant_type.sql:1–5`](../../src/main/resources/db/migration/V16__add_participant_type.sql), [`EventRoutesTest.kt:888–914`](../../src/test/kotlin/no/nav/delta/event/EventRoutesTest.kt).

### Authorization and response data

The event API uses `authenticate("jwt")` for JWT signature and issuer validation. The `delta.read` check now applies to M2M tokens on `GET /event` and `GET /category`; tokens carrying this role are rejected from all other JWT-authenticated API routes, including mutations. The event-by-ID route still retrieves by ID without applying the list route's public-only filter, so it is not approved for M2M access.

Sources: [`Authentication.kt:14–22`](../../src/main/kotlin/no/nav/delta/application/Authentication.kt), [`Routes.kt:25–27, 51–65, 504–505`](../../src/main/kotlin/no/nav/delta/event/Routes.kt), [`Database.kt:102–160`](../../src/main/kotlin/no/nav/delta/event/Database.kt).

Event responses include participant and host names/emails. The existing Teams visibility helper hides meeting join details for callers who are not participants or hosts, but does not remove attendee lists.

Sources: [`Models.kt:36–47`](../../src/main/kotlin/no/nav/delta/event/Models.kt), [`Database.kt:436–499`](../../src/main/kotlin/no/nav/delta/event/Database.kt), [`Routes.kt:716–729`](../../src/main/kotlin/no/nav/delta/event/Routes.kt), [`documentation.yaml:873–945`](../../src/main/resources/openapi/documentation.yaml).

`nais.yaml` lists `delta-frontend` and `delta-webhook-relay` as inbound applications and enables the Entra application with `allowAllUsers: true`. It also grants `delta.read` to `security-champion-stats-backend` in `appsec` on `prod-gcp`. The OpenAPI document declares bearer authentication and documents the M2M role requirement on the approved event-list and category operations.

Sources: [`nais.yaml:9–13, 31–36, 47–54`](../../nais.yaml), [`documentation.yaml:197–245, 672–675`](../../src/main/resources/openapi/documentation.yaml).

## M2M authorization model

For `client_credentials` against Entra ID through Nais, use a **custom application role**, not an OAuth scope. Nais documents that app-only tokens are identified by `idtyp: app`, carry application permissions in the `roles` array, and do not carry the delegated `scp` claim. A possible coarse-grained role is `delta.read`, assigned only to applications approved to read Delta data.

Nais' inbound `accessPolicy` specifies which applications can request tokens for Delta. Its per-application `permissions.roles` grants custom roles, which appear in the M2M token. Nais also grants authorized consumers the default `access_as_application` role; Delta should require the custom read role rather than treating that default role as sufficient. See the [Nais Entra reference](https://docs.nais.io/auth/entra-id/reference/) and [M2M guide](https://docs.nais.io/auth/entra-id/how-to/consume-m2m/).

The role is a coarse ACL, not per-user authorization: every approved app holding it can query whatever read endpoints Delta makes available. Delta now requires `idtyp: app` and the `delta.read` role for M2M reads, and rejects role-bearing tokens everywhere else in its JWT-authenticated APIs. Nais' Entra reference describes audience validation; the existing JWT setup verifies the configured issuer and signing key but does not visibly check the production audience, which remains a separate decision.

## Required work and risks

1. **Consumer grants are explicit.** `security-champion-stats-backend` in `appsec` on `prod-gcp` is granted `delta.read`. Add grants for other consumers only after they are approved to access the endpoint data. Do not treat authentication alone or the default `access_as_application` role as permission to read.
2. **Role enforcement and mutation denial are implemented.** Tokens with `delta.read` are accepted only on `GET /event` and `GET /category`; app-only tokens without it are denied on these reads.
3. **Attendance-by-user lookup is implemented.** M2M callers can query `GET /event` by exactly one category, participant email, and start period (`from <= start_time < to`). Only public events are returned. `onlyMine` and `onlyJoined` remain unavailable to M2M callers.
4. **Private-event visibility remains restricted.** M2M attendance lookup includes public events only. `GET /event/{id}` is deliberately unavailable to M2M callers because it bypasses the public-only list filter. The response for matching public events is the existing full event response, including attendee and host names/emails; expanding access to private events would require a separate decision.
5. **OpenAPI and tests are updated.** Signed M2M test tokens verify role-bearing reads, missing-role denial, category/user/period filtering, public-only results, caller-specific filter denial, event-by-ID denial, and rejected writes.

The M2M route tests use locally signed JWTs and an injected test verifier; production verification continues to use the configured JWK provider and issuer. Sources: [`Authentication.kt`](../../src/main/kotlin/no/nav/delta/application/Authentication.kt), [`TestSupport.kt`](../../src/test/kotlin/no/nav/delta/support/TestSupport.kt), [`EventRoutesTest.kt`](../../src/test/kotlin/no/nav/delta/event/EventRoutesTest.kt).

## Suggested first increment

The initial enforcement increment and the category/user/period attendance query are implemented. Before broadening the contract, decide whether attendee fields and private-event reads are within scope.

## Unknowns

- “Tag” is assumed to mean the existing category model.
- The production audience, Entra grants, and deployed inbound policy were not verified from source.
