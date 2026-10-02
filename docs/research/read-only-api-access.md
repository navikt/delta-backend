# Read-only API access for Delta

## Feasibility

**Yes.** The intended model is application-level access control: explicitly authorize a consumer application to use Delta's API, then allow it to read meeting, attendance, and user data without restricting results to an employee represented by the token. Delta already supports some relevant event queries, but it does not visibly enforce a read-only permission. JWT authentication is shared across read and write routes, so an authorization boundary must be added before granting consumer access.

The code uses **events** and **categories**; this note treats the request's “meetings” and “tags” as those concepts. Confirm that these are the intended product terms.

## What exists today

### Filtering by category

`GET /event` accepts comma-separated category IDs in `categories`; `GET /category` lists categories. The database adds a predicate for every supplied ID, so multiple IDs mean an event must match **all** of them. The normal event list is public-only, but `onlyMine=true` or `onlyJoined=true` disables that public-only condition.

Sources: [`Routes.kt:27–48, 561–563`](../../src/main/kotlin/no/nav/delta/event/Routes.kt), [`Database.kt:379–409, 436–499, 777–788`](../../src/main/kotlin/no/nav/delta/event/Database.kt), [`Models.kt:89–92`](../../src/main/kotlin/no/nav/delta/event/Models.kt), [`V17__add_category.sql:1–9`](../../src/main/resources/db/migration/V17__add_category.sql), [`documentation.yaml:197–241, 393–403`](../../src/main/resources/openapi/documentation.yaml).

### Finding events a user joined

`GET /event?onlyJoined=true` filters using the caller's authenticated email and selects participant registrations (`type = 'PARTICIPANT'`), not hosts. The email is taken from the principal, not from a caller-supplied query parameter. A route test already combines `onlyJoined=true` with a category filter.

Sources: [`Routes.kt:29–48, 613–619`](../../src/main/kotlin/no/nav/delta/event/Routes.kt), [`Database.kt:379–409`](../../src/main/kotlin/no/nav/delta/event/Database.kt), [`V16__add_participant_type.sql:1–5`](../../src/main/resources/db/migration/V16__add_participant_type.sql), [`EventRoutesTest.kt:888–914`](../../src/test/kotlin/no/nav/delta/event/EventRoutesTest.kt).

### Authorization and response data

The event API is behind `authenticate("jwt")`; the visible JWT setup configures a JWK provider and issuer but does not visibly enforce a custom scope or role. Read and mutation routes share this authentication boundary. The event-by-ID route retrieves by ID without applying the list route's public-only filter.

Sources: [`Authentication.kt:14–22`](../../src/main/kotlin/no/nav/delta/application/Authentication.kt), [`Routes.kt:25–27, 51–65, 504–505`](../../src/main/kotlin/no/nav/delta/event/Routes.kt), [`Database.kt:102–160`](../../src/main/kotlin/no/nav/delta/event/Database.kt).

Event responses include participant and host names/emails. The existing Teams visibility helper hides meeting join details for callers who are not participants or hosts, but does not remove attendee lists.

Sources: [`Models.kt:36–47`](../../src/main/kotlin/no/nav/delta/event/Models.kt), [`Database.kt:436–499`](../../src/main/kotlin/no/nav/delta/event/Database.kt), [`Routes.kt:716–729`](../../src/main/kotlin/no/nav/delta/event/Routes.kt), [`documentation.yaml:873–945`](../../src/main/resources/openapi/documentation.yaml).

`nais.yaml` lists `delta-frontend` and `delta-webhook-relay` as inbound applications and enables the Entra application with `allowAllUsers: true`. This does not itself implement a read-only permission. The OpenAPI document defines bearer authentication, but the event operations do not declare it or document a scope/role requirement.

Sources: [`nais.yaml:9–13, 31–36, 47–54`](../../nais.yaml), [`documentation.yaml:197–245, 672–675`](../../src/main/resources/openapi/documentation.yaml).

## M2M authorization model

For `client_credentials` against Entra ID through Nais, use a **custom application role**, not an OAuth scope. Nais documents that app-only tokens are identified by `idtyp: app`, carry application permissions in the `roles` array, and do not carry the delegated `scp` claim. A possible coarse-grained role is `delta.read`, assigned only to applications approved to read Delta data.

Nais' inbound `accessPolicy` specifies which applications can request tokens for Delta. Its per-application `permissions.roles` grants custom roles, which appear in the M2M token. Nais also grants authorized consumers the default `access_as_application` role; Delta should require the custom read role rather than treating that default role as sufficient. See the [Nais Entra reference](https://docs.nais.io/auth/entra-id/reference/) and [M2M guide](https://docs.nais.io/auth/entra-id/how-to/consume-m2m/).

The role is a coarse ACL, not per-user authorization: every approved app holding it can query whatever read endpoints Delta makes available, including attendance for a user when the API accepts that user's identifier. The API still must enforce the role on read routes and keep mutation routes separately protected. Nais' Entra reference describes audience validation; verify the production token audience and claims as part of implementation, since application code inspected here does not visibly check them.

## Required work and risks

1. **Add the application ACL.** Grant a custom M2M role (for example `delta.read`) to each approved consumer in Nais' inbound access policy, and require that role in Delta for read routes. Do not treat authentication alone or the default `access_as_application` role as permission to read.
2. **Protect mutations separately.** Ensure the read role cannot create, change, delete, join, or leave events. The current shared JWT authentication boundary does not itself provide this separation.
3. **Provide the requested queries.** Existing category filtering and “joined by authenticated caller” filtering exist. Add or adapt endpoints to allow broad queries such as attendance by a supplied user identifier, since an app-only token represents the application and does not identify a particular employee.
4. **Set the API boundary.** Decide which read endpoints and fields the role exposes. The event-by-ID route appears to return by ID without applying the list route's public-only filter; with the intended broad access, make that behavior explicit rather than relying on an accidental visibility difference.
5. **Document and test the boundary.** Update OpenAPI security declarations. Test that an approved client with the custom role can read and that a token without it is denied; also prove the read role cannot invoke any mutation.

The existing route test validates the joined/category filters, but test support skips JWT verification in local mode; it does not prove production token-claim authorization. Sources: [`Authentication.kt:15–18`](../../src/main/kotlin/no/nav/delta/application/Authentication.kt), [`TestSupport.kt:73–80`](../../src/test/kotlin/no/nav/delta/support/TestSupport.kt), [`EventRoutesTest.kt:888–914`](../../src/test/kotlin/no/nav/delta/event/EventRoutesTest.kt).

## Suggested first increment

1. Define a single read role for broad consumer access, then add approved applications and grant that role in the Nais inbound policy.
2. Enforce that role on the API's read routes and verify it cannot be used on mutation routes.
3. Expose documented queries for events by category and attendance by user identifier; the latter should not be interpreted as “the caller's own attendance.”
4. Treat attendee/user data and private-event visibility as part of the intended data contract, and document which fields and reads the ACL covers.

## Unknowns

- “Tag” is assumed to mean the existing category model.
- The production audience, Entra grants, and deployed inbound policy were not verified from source.
- The specific user identifier and query shape for attendance lookups still need to be defined.
