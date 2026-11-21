# Work Management for Small Teams — Implementation Plan (index)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Labels and item type, sprints and estimates, notifications, cross-project find with saved filters, code-host integration, flow and DORA metrics, attachments, and intake for non-members, each releasable on its own.

**Architecture:** Each feature is a facade on `WorkService` with its own store interface and `Jdbc*` class, following `claims()`, `changes()` and `deployments()`. `WorkStanding` stays the only resolver of who sees what. Anything that leaves the system (mail, outbound HTTP, a person's email address) is a port in `wikantik-work` implemented in `com.wikantik.work.runtime` (`wikantik-main`). Metrics are computed at read time; no table stores a metric.

**Tech Stack:** Java 25, PostgreSQL 18 (`Jdbc`, `PostgresTestDb`), JUnit 5, ArchUnit, Mockito, React 19 + vitest, Cargo/Selenide ITs.

**Spec:** `docs/superpowers/specs/2026-11-21-work-team-features-design.md` (its decisions D1–D18). Parent: `docs/superpowers/specs/2026-11-15-work-management-design.md`. Module rules: `wikantik-work/CLAUDE.md`, `wikantik-work-mcp/CLAUDE.md`.

## How this plan is partitioned

This file is the index. It holds what every part shares: the global constraints, the review focus, and the rulings that reconcile the parts. **The tasks are in the eight part plans**, each written against the code as it stands at `66b56b228f` and each self-contained:

| # | Part | Plan | Tasks | Migration | Needs |
|---|---|---|---|---|---|
| 1 | Labels and item type | `2026-11-21-work-team-01-labels-and-type.md` | 10 | V084 | — |
| 2 | Sprints and estimates | `2026-11-21-work-team-02-sprints-and-estimates.md` | 9 | V085 | 1 (creates `WorkFeatures`) |
| 3 | Notifications | `2026-11-21-work-team-03-notifications.md` | 11 | V086 | 1 |
| 4 | Find and saved filters | `2026-11-21-work-team-04-find-and-saved-filters.md` | 12 | V087 | 1; better after 2 and 3 |
| 5 | Code-host integration | `2026-11-21-work-team-05-code-host-integration.md` | 16 | V088 | 1 |
| 6 | Flow and DORA metrics | `2026-11-21-work-team-06-flow-and-dora-metrics.md` | 11 | V089 | 2 and 5 |
| 7 | Attachments | `2026-11-21-work-team-07-attachments.md` | 11 | V090 | 1 |
| 8 | Intake | `2026-11-21-work-team-08-intake.md` | 9 | V091 | all of 1–7 |

89 tasks. **Each part is one execution run and one patch release**: run it, pass the gate (`bin/run-tests.sh --parallel 4`), release, then start the next. Parts share no unfinished state.

Part 1 goes first because it creates the holder every later facade hangs from (R1). Part 4 tags each filter field with the part it comes from and says what to drop if that part is not built. Part 8 goes last by decision and re-runs its role audit over whatever Parts 1–7 added.

Migration numbers assume this order. Take the next free number at execution time and never renumber an applied one.

Each part plan has its own `Decisions` (numbered P<part>-<n>: choices made where the design was silent) and `Deviations from the outline` (where the code proved the first outline wrong). Both are worth reading before approving a part.

## Global Constraints

- `wikantik-work` depends on neither `wikantik-api` nor `wikantik-main`. All SQL goes through `com.wikantik.jdbc.Jdbc`.
- Every caller goes through `WorkService`. Every new public method of `WorkService` or a facade is classified in `WorkServiceVisibilityPostgresTest`; every new `*Service` accessor is added to its `FACADES` set.
- Non-members get **404, never 403**; lists omit what the caller cannot see. `WorkStanding` is the only class that asks a store for a role or the visible projects (`WorkStandingSourceRuleTest`).
- Every state change writes its `work_event` in the same transaction. A multi-statement write gets a rollback test with `FaultInjectingDataSource`.
- Nothing is hard-deleted from `work_item`, `work_event`, `work_comment`, `work_change`, `work_deployment*`. New tables that are preferences (`work_watch`, `work_saved_filter`, `work_webhook`) may delete rows.
- Migrations are idempotent DDL only, grants through `:app_user`, verified as a no-op on re-apply. A schema test per migration, modelled on `WorkChangesSchemaTest`.
- New `wikantik.*` keys are declared in `ini/wikantik.properties` first (section, description, `Type:`, explicit default), then `bin/config-reference.sh --write`.
- No project, product, item, sprint or label value as a metric label. No table that stores a computed metric.
- PMD complexity limits hold with **no suppressions**: split a class instead (the module's facades exist for this reason).
- An archived project is read-only: `WorkConflictException` reason `archived`.
- People appear by display name in every response (`WorkPeople`, `WorkJson`); a login never reaches a non-admin.
- A new work event type gets a constant in `WorkEvent` and a line in `eventText.js` with its test. `WorkEventNames` changes only when an event's values name a person, a queue or an agent (it resolves those to display names and does nothing else).
- All new SPA routes sit under `/work/`, which `web.xml` and `SpaRoutingFilter.SPA_PREFIXES` already cover; add no route outside it.
- MCP: a new tool or argument is added to the work MCP instructions text and `WorkMcpIT.EXPECTED_TOOLS`; tools call `WorkService` and nothing else.
- Each part ends by updating `wikantik-work/CLAUDE.md`, `docs/developer/ProjectReference.md`, `docs/admin/WikantikOperations.md` where an operator must act, and `CHANGELOG.md` `[Unreleased]`.

## Review Focus

1. **A notification, email or webhook about an item its recipient can no longer see.** A member is removed between the event and the dispatch: they get nothing, and their older inbox rows for that project stop showing. (Part 3, Task 3.3.)
2. **A reporter reaching anything but their own items**: the board, find, the activity feed, an item's parent, links, changes, deployments, a sibling's attachment, sprint and flow figures. Every read is driven as a reporter. (Part 8, Task 8.2.)
3. **An attachment fetched by id through another item's URL, or an HTML/SVG file served inline.** Both must be 404 and a forced download respectively. (Part 7, Task 7.2.)
4. **Scope changing after a sprint starts.** An item added, removed, re-estimated or reopened mid-sprint must leave the commitment unchanged and the burndown correct on the day it happened. (Part 2, Task 2.4.)
5. **A forged, replayed or reordered code-host delivery.** An unsigned or tampered one records nothing and looks like any other 404; a repeated or out-of-order one never makes a second change, never overwrites what an agent or person reported, and never moves an item. (Part 5, Tasks 5.2 and 5.4.)
6. **A webhook URL pointing inside the network, or redirecting there.** Refused by `EgressGuard` at save and at every delivery. (Part 3, Task 3.5.)

## Cross-part rulings

Made when the part plans were reconciled. Each affected part plan carries the same text under "Cross-part rulings"; where a part's own text disagrees, the ruling wins.

- **R1 — `WorkService.features()`.** `WorkService` is at the PMD `TooManyMethods` limit of 40. Part 1 creates `WorkFeatures`, returned by one new method `WorkService.features()`. `labelsOf` and every facade accessor added by this plan (`sprints()`, `notifications()`, `webhooks()`, `find()`, `codeHost()`, `flow()`, `attachments()`, `intake()`) live on `WorkFeatures`. `WorkServiceVisibilityPostgresTest` treats them as facades. No suppression and no baseline entry.
- **R2 — one zone.** Days and weeks (burndown, throughput, deployment frequency, the daily email hour) use the work clock's zone. Part 2 makes the wiring clock `Clock.systemDefaultZone()`; nothing else chooses a zone.
- **R3 — reserved project keys.** The router ignores case, so a page at `/work/find` hides a project keyed `FIND`. `WorkInputs.RESERVED_KEYS` (today `ITEM`, `PRODUCTS`, `AGENTS`, `LOG`) gains `DEPLOYMENTS` (Part 1; an existing gap), `INBOX` (Part 3), `FIND` (Part 4) and `SUBMIT` (Part 8). **Before each of those parts is deployed, check production has no project with that key** (`SELECT key FROM work_project WHERE key IN ('DEPLOYMENTS','INBOX','FIND','SUBMIT')`); the owner runs it, since agents do not use credentials against production.
- **R4 — superseded changes.** Part 5 marks a direct change that turns out to be a pull request's merge as superseded (`work_change.superseded_by`). Part 6 excludes superseded rows from every figure.
- **R5 — flagged merges notify leads.** `change_flagged` → the project's leads. Whichever of Parts 3 and 5 is built second adds the rule to `Recipients`.
- **R6 — routes.** `/api/work/projects/*`, `/api/work/items/*` and `/api/work/products/*` each belong to one existing servlet. Routes under them are added inside that resource (through a helper class where the resource would grow too large), never as a new servlet mapping.
- **R7 — attachment caching.** Inline images are served `private, max-age=300`; everything else `private, no-store`.
- **R8 — the event cursor.** Part 3 adds `work_event.tx` (the writing transaction's id) so the notification and webhook cursors cannot skip an event that commits out of id order. No other part reads the column; every part's event writes fill it by default.

## Rulings wanted from the owner

Each part plan proceeds on the choice marked "as planned". None blocks Part 1.

| # | Part | Question | As planned |
|---|---|---|---|
| Q1 | 3 | Do an item's watchers hear when it is assigned, or only the new assignee? The design says the assignee; the first outline said both. | The assignee only. Watchers hear of hand-offs. |
| Q2 | 3 | A project webhook has no reader whose access can be checked. Whose standing does it act under? | The lead who created it. It pauses while that person is not a lead, and another lead can take it over. |
| Q3 | 3 | Email is batched over the last 48 hours of notices and sent at least once (a crash between send and stamp can repeat a message). | Accepted. |
| Q4 | 4 | One route `GET /api/work/items` with two response shapes (a project list as today; a cross-project find without `project`), or a separate `/api/work/find`? | One route. |
| Q5 | 4 | A shared saved filter is pinned to its own project, so it cannot reveal another project's key to its viewers. Cross-project filters stay personal. | Pinned. |
| Q6 | 5 | Is a non-draft pull request "in review" the moment it is opened? | Yes; a draft stays open. |
| Q7 | 5 | Under D17 an unreviewed merge into a non-default branch (a stacked pull request) is flagged too. | Flagged. |
| Q8 | 5 | A commit pushed to a branch naming no item, then fast-forwarded onto the default branch by hand, is never seen as direct and never flagged. Inherent in a receive-only hook. | Documented as a known limit. |
| Q9 | 5 | The hook secret is stored as issued, because verifying an HMAC needs the raw secret. | Stored; never returned after creation. |
| Q10 | 6 | A fix-forward filed under another project still ends this project's recovery clock, so the figure can include the timing of a deployment its members cannot open. The alternative makes a team that fixes forward elsewhere look perfect. | Counted; only the duration is shown. |
| Q11 | 6 | A deployment verified and later rolled back counts toward deployment frequency but ships no changes for lead time. | As the existing "shipped" rule has it. |
| Q12 | 7 | Showing an attached image needs a small hook in the wiki's own link processing (inert unless work text sets it), not only a change in the work renderer. | The hook, with a test that page rendering is unchanged. |
| Q13 | 8 | A reporter watches their item, and watchers hear of merges and deployments, which the design says a reporter cannot see. | Reporters hear only of status changes, comments and attachments. |
| Q14 | 8 | May viewers and members also file through `/work/submit`, or reporters only? | Anyone with a role in the project; the item starts as a draft either way. |
| Q15 | 8 | Files chosen on the submit form upload after the item is filed, since an attachment needs an item. A failed upload leaves a filed item without its file. | Accepted, with the failure shown. |
| Q16 | 1 | A label carried only by cancelled items drops out of suggestions but can still be filtered on. | Accepted. |

## After the last part

- [ ] A whole-branch review on the most capable model, with the six Review Focus lines as its brief.
- [ ] Publish the design to the wiki's work-management hub (owner's standing preference).
