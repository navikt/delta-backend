# Readability-first issue drafts

These are ready-to-post issue bodies for manual publication in
`navikt/delta-backend`. Use each file's first heading as the issue title and
paste the remainder as its body.

The goal is easier onboarding and safer changes, not a framework rewrite.
The earlier `docs/refactoring-implementation-plan.md` contains supporting
research; this breakdown supersedes its recovery-first prioritization.

## Posting metadata and dependencies

Numbers below identify draft files, not GitHub issue numbers. After posting,
set any blockers using GitHub's native blocking relationships.
No labels, issue types, project, status, parent or assignee are prescribed:
repository tracker metadata was not queried and none is assumed.

| Draft | Title | Blocked by | Priority |
| --- | --- | --- | --- |
| 01 | Synchronize invitations added through shared event edits | None | Small correctness fix |
| 02 | Keep cancelled calendar work recoverable | None | Small correctness fix |
| 03 | Make event creation understandable and atomic | None | Readability first |
| 04 | Make participant changes understandable and concurrency-safe | 03 | Readability first |
| 05 | Make single-event editing easy to follow | 01, 03, 04 | Readability first |
| 06 | Make recurring-event changes explicit and preserve cancellation data | 03, 04, 05 | Readability first |
| 07 | Make event deletion and calendar cleanup easy to follow | 03, 04 | Readability first |
| 08 | Make room and people lookup dependencies explicit | None | Independent readability improvement |

The first event workflow establishes shared transaction and operation
conventions. Participant changes establish the invariant protocol reused by
editing. Recurring editing builds on those rules without duplicating them.
Deletion need not wait for recurring editing; its operation can support
UPCOMING deletion independently. Drafts 06 and 07 must coordinate cancellation
representation if implemented concurrently, but neither requires the other's
completed feature.

## Shared definition of done

Every issue is independently reviewable and must leave the build green.
Routes retain HTTP parsing, caller extraction and response mapping, but not
business policy, commit coordination or Graph orchestration for the migrated
workflow. This does not prohibit ordinary query calls in read-only routes.

Moving the old route block wholesale into a large service class, splitting
files by size, or adding forwarding-only interfaces does not meet the goal.
Use ordinary Kotlin and existing test seams. Update directly affected
documentation, not unrelated endpoints.

Known behavior changes are named in the individual drafts. Preserve all other
contracts, especially persisted invite mode, legacy synchronous room/Teams
saves, shared asynchronous saves, notification suppression and visibility.

## Deferred work

A comprehensive legacy delivery outbox, master-operation journal, worker
runtime redesign and retry-eligibility migration are not prerequisites for
this readability backlog. The research plan records their evidence and
remaining operational decisions; draft separate issues when that scope is
chosen. Readability extraction must not claim to make legacy effects durable
or exactly once.

Real Graph mailbox checks remain necessary when notification or provisioning
behavior changes. Local adapters and wire tests do not prove tenant behavior.
