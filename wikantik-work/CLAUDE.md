# wikantik-work

Work management (`com.wikantik.work.*`, `work_*`, flag `wikantik.work.enabled`, default `false`). No dependency on `wikantik-api` or `wikantik-main` (wiring and rendering: `wikantik-main` `com.wikantik.work.runtime`); all SQL via `com.wikantik.jdbc.Jdbc`. Work items never reach the page store, search index, bundles, sitemap, change feed or Page Graph. Design/plans: `docs/superpowers/{specs,plans}/2026-11-*-work-*.md`. Before changing an area read `docs/developer/work/<area>.md` (`overview`, `agents-claims`, `changes-autonomy`, `deployments`, `sprints`, `notifications`, `find`, `attachments`, `codehost`, `flow-dora`, `intake`, `migrations`, `mcp`). A class Javadoc that states a rule is the authority.

**Invariants only: one line each, file ≤ 10 KB. Reference detail goes to `docs/developer/work/<area>.md`, never here.**

## Invariants (a red guard means you broke one)

Visibility and standing
- Every caller goes through `WorkService`; stores have no visibility check (`WorkAccessArchTest`, in wikantik-rest). A new public `WorkService`/facade method must be classified in `WorkServiceVisibilityPostgresTest` (`READS`/`WRITES`/`LEAD_READS`/`FACADES`).
- Non-members get **404, never 403**; lists omit the project; unknown and unseen ids are the same `WorkNotFoundException`. Wiki Admins see all.
- `WorkStanding` is the one resolver of roles and visible projects; the sponsor is a ceiling, never a source, looked up per request, never stored (`WorkStandingSourceRuleTest`, `WorkStandingPostgresTest`).
- A reporter row is no standing: `roleIn`/`seesProject`/`visibleProjects` never return it. Reporters are answered only by `intake()` and the named `WorkStanding` checks; the reduced view is an explicit field list, no login, agent/item id or note (`ReportedItemViewTest`, `WorkIntakeIT`); `ReporterEvents` is the one allow-list of what they are told; no user id reaches them (`WorkPeople.displayForReporter`), and their HTML is never rendered from the stored body.
- Only a human is admin or reporter (`Actor` drops `admin` for non-`HUMAN`). No API key becomes an agent; callers never supply `actor_kind`. A directory/store failure is `WorkUnavailableException`, never "no actor", never cached (`ActorTest`).
- A deployment is seen only through its carried items (`DeploymentAccess`); return only `DeploymentView`, never a raw `Deployment` (`WorkDeploymentAccessPostgresTest`). An attachment is reached only through its own item: the store `WHERE` has item id and attachment id; never load by attachment id alone.
- Responses name people by display name (`WorkPeople`, `WorkJson`); logins only to their owner and admins. Agent attribution is by id columns, never names (`JdbcWorkStoreAttributionPostgresTest`).

Append-only and transactions
- Nothing in a work record is hard-deleted; `work_event`, `work_deployment_event`, `work_product_event`, `work_agent_sponsor_history` are append-only. Every state change writes its event in the same transaction (`work_event`, or `work_product_event` for products). Derived rows (notifications, skipped notices, attachment blob bytes) may be pruned.
- One definition of a status move: `StatusMoves` (Java `of`, SQL `movedSql`/`toSql`/`intoSql`). Flow figures, sprint burndown, reporter rule read moves only through it; new ways to move go there (`StatusMovesPostgresTest`).
- One resolver of an item's policy: `WorkAutonomy.resolve`; no other class reads `autonomy()`, `allowDirect()`, `doneMeans()`, `defaultAutonomy()`, `autonomyCeiling()` to decide. Resolve afresh on every gated call, never cache (`WorkAutonomySourceRuleTest`).
- Cursors: activity and assignment log `(at, id)`; notifications `(tx, event_id)` under `pg_snapshot_xmin`; find `(sort value, id)`, no visibility in it.
- Flow and DORA are computed at read time, nothing stored, flow store SELECT-only (`WorkFlowNoStoreRuleTest`); `superseded_by` rows count in no figure over `work_change` (`aSupersededDirectChangeIsCountedNowhere`).

Lock order (keep)
- Claim: agent row, agent's member row, sponsor's member rows (skipped for a wiki-admin sponsor), project row, then item; all `FOR SHARE` (`JdbcWorkClaimsConcurrencyPostgresTest`, `...AccessPostgresTest`, `...SponsorPostgresTest`).
- Change write: change row, then item row. Code host: product advisory lock `pg_advisory_xact_lock(1464550216, product_id)`, then `work_change` rows in id order, then item `FOR SHARE`; written once in `JdbcCodeHostRows` (`WorkChangeRacePostgresTest`).
- Sprint: sprint rows (id order), then item rows (`JdbcSprintTransitions`, `lockSprints`). Archive: project row, then items. Intake cap: `pg_advisory_xact_lock(hashtextextended('work-intake:'||login,0))`.
- Gate decisions run on the locked row in the store callback, not the service's earlier read (`WorkChangeGatesPostgresTest`).

Agents, gates, fencing
- An agent is an actor only via `registry().agentForToken` then `actorForAgent` (null if retired, sponsorless, sponsor gone). Tokens `wka_`, SHA-256 only stored.
- An agent's write is fenced under the item row lock: another agent's claim gives `ClaimFenceException`, nothing written; change tools too (`Action.fenced()`, `WorkClaimRacePostgresTest`). People are never fenced.
- The store reports what happened (`claimWithOutcome`, `ItemWrite`); the service never infers it. Every claim ending writes its event in the same transaction and is counted by `wikantik_work_claims_total`; reserved release words are refused as notes (`WorkClaimListenerPostgresTest`).
- Gates restrict agents only; a person is never refused at a gate. Every agent gate decision goes through `WorkScopes.decide` (`WorkGatesTest`, `WorkItemGatesPostgresTest`). An agent never moves an item out of `draft`; new items by agents start `draft` (`WorkDraftRulePostgresTest`). No `in_progress` in a queue without a claim (`ItemTargets.requireClaimForQueuedWork`). Leaving an epic's `in_production` done-means is a loosening: keep parent changes behind `ItemGates.requireNoLoosening`.
- Agents are `viewer`/`member`, never `lead` (V091 CHECK). Blank member principals refused (V095 CHECK).
- A live change holds its item: no `done`, no product change (`JdbcWorkItemWrites.requireNoLiveChange`); `in_production` done-means is checked in the locked item write (`requireShipped`). Verified needs non-blank evidence, plain text, never markup. States move only forward (`DeploymentState.mayBecome`) on the locked row. The deploy gate is the product's `deploy_gate`, never the autonomy level.

Text, rendering, hooks
- Item and comment text renders links-as-typed (`Context.VAR_LINKS_AS_TYPED`), filters off, with no page, title, alias, heading or attachment lookup. `work-attachment:<id>` rewriting is a string function; `VAR_VIEWER_SENSITIVE` stays set.
- Attachment type from magic bytes (`FileKinds.detect`); only PNG/JPEG/GIF/WebP inline; every download has `nosniff` and the sandbox CSP.
- Code-host hook (receive only, no outbound call): every failure before the 413 and every signature failure is the **same 404 bytes and headers**; `authenticate` always does the same two reads and exactly one HMAC (dummy key when unknown); unverified refusals log at DEBUG and echo nothing. `apply` takes a `HookDelivery` only `authenticate` can create. Never add a distinguishable answer (`WorkHookResource` Javadoc, `WorkCodeHostIT`).
- The code host never writes `work_item`; it fills in change records only. Lifecycle is a last-writer-wins register by host time, merged terminal; one pull request is one change (rules: `JdbcWorkCodeHost` Javadoc, `WorkCodeHostOrderLimitsPostgresTest`). Attribution only by the host-asserted pusher (a commit address never attributes). `Actor.Kind.SYSTEM` is refused by every store writer except `JdbcWorkCodeHost*`. The hook secret is shown once, never in an event, audit, log or history.
- A `change_flagged` notice is live only while its flag waits: every notice read uses `FlagNotices.liveNoticeSql` (`WorkFlagNoticesPostgresTest`).
- Webhooks: `WorkOutbound` + `EgressGuard`, https only, URL/secret never returned. A new item event type goes in `WebhookPayloads.SENDABLE` and `NoticeText`. Recipient rules only in `Recipients`/`AutoWatch`. A transient failure is never skipped as poison (`TransientFailure`).
- Reporter limits: a reporter-only uploader's share of one item is capped (`reporterItemBytes`, 413 `reporter_full`) and removed files still count; filing is capped per reporter per rolling 24 h over every project incl. where they are a member (`maxPerDay`, 429 `intake_limit`; count+insert under the advisory lock).
- A `claim_expired` row is a system event: the assignment-log `actorKind` filter never hides it.
- Owner ruling: a person's task or story starts `ready`; an epic and every agent's item start `draft` (`WorkPolicy.startStatus`). Owner ruling (D12): there are no internal comments; a reporter sees the whole discussion on their item.
- No project, product or item labels on any work metric.
- `blocked_by` links are same-project only. An archived project is read-only (reason `archived`, REST 409). Reserved keys: `INBOX`, `FIND`, `SUBMIT`.

## Accepted limits (owner decisions — do not tighten or 'fix' without the owner)

- Global-sequence ids (attachment, comment, event) reveal overall volume; they never grant access.
- `RemoteIpValve` `internalProxies` stays at Tomcat's default (WikantikOperations 1.19).
- Code-host order limits and unpruned register rows (`WorkCodeHostOrderLimitsPostgresTest`).
- Flow and DORA figures are as-of-now; no snapshot.

## Structure rules (PMD limits, no suppressions)

`WorkService`, `JdbcWorkStore`, `WorkPolicy.may` are at their PMD limits (`TooManyMethods` max 40, `GodClass`: `build-support/pmd-complexity-ruleset.xml`). New facades go on `WorkFeatures`, new SQL in a new `Jdbc*` class, agent refusals in `WorkPolicy.agentRefusal`, store accessors on `store.notifyStores()`. No fold-back, no suppression. `Item`, `ItemDraft`, `ItemFilter` keep canonical constructors only; keep policy/ack state out of `ItemView`.

## Schema

Migrations are DDL only, `V076`-`V099` (there is **no V093**; do not renumber); never edit an applied one (`migrations.md`). Tests: `PostgresTestDb`, fixed clocks, `DispatchAwait`, fake mail/outbound ports.
