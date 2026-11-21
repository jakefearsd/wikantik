# Work Management for Small Teams — Part 3: Notifications — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** People hear about the work they watch, are assigned or are mentioned on, in an inbox, by batched email and through project webhooks (generic signed JSON or Slack), and never about an item they can no longer see.
**Architecture:** The event log is the outbox. A background dispatcher reads `work_event` past a stored cursor, applies auto-watching, works out recipients with a pure rule class, drops anyone `WorkStanding` says cannot see the project, and writes `work_notification` rows in the same transaction that moves the cursor. Email and webhooks leave through two ports in `wikantik-work` (`WorkMailer`, `WorkOutbound`) implemented in `com.wikantik.work.runtime`, the outbound one over the connectors' `EgressGuard`.
**Tech Stack:** Java 25, PostgreSQL 18 (`Jdbc`, `PostgresTestDb`, `FaultInjectingDataSource`, `xid8`), JUnit 5, Mockito, Gson, JDK `HttpClient`, Jakarta Mail through `MailUtil`, React 19 + vitest, Cargo IT.
**Spec:** docs/superpowers/specs/2026-11-21-work-team-features-design.md, section 3; index and global constraints: docs/superpowers/plans/2026-11-21-work-team-features.md
**Depends on:** No other part. It uses nothing from Parts 1 and 2. The migration is numbered V086 on the assumption that Parts 1 (V084) and 2 (V085) ran first; take the next free number at execution time. If Part 1 has shipped, the generic webhook payload's `item` object also carries `type` (`Item.type().wire()`); if Part 3 runs first, leave `type` out and Part 1's Task 1.2 adds it. Part 5 (Task 5.3) later adds one rule to `Recipients`; Part 8 later narrows `WorkStanding.seesProject` to item level.

## Cross-part rulings

Added when the eight part plans were reconciled (index: `docs/superpowers/plans/2026-11-21-work-team-features.md`). **Where a ruling and the text below disagree, the ruling wins.**

- **R1 — new facades hang off `WorkService.features()`, not `WorkService`.** `WorkService` is at the PMD `TooManyMethods` limit (40). Part 1 creates `com.wikantik.work.WorkFeatures` (built in `WorkService`'s constructor, returned by the one new method `WorkService.features()`); every facade accessor this plan calls `WorkService.x()` is `WorkService.features().x()`. `WorkServiceVisibilityPostgresTest` treats `WorkFeatures`' accessors as facades (its `FACADES` set). No suppression, no baseline entry.
- **R2 — one zone.** `dailyHour` is read in the work clock's zone (`clock.getZone()`), which Part 2 sets to the server's default; if Part 2 is not yet built it is UTC.
- **R3 — reserved project keys.** `INBOX` is added to `WorkInputs.RESERVED_KEYS` (as `LOG` already is) in addition to P3-19.
- **R5 — flagged merges.** If Part 5 is already built when this part runs, `Recipients` also carries: `change_flagged` → the project's leads, reason `acknowledge`. If not, Part 5's Task 5.3c adds it.

## What this part builds on

Verified in the code on 2026-11-21.

- `WorkEvent( long id, long projectId, Long itemId, String itemKey, Instant at, String actor, Actor.Kind actorKind, Long agentId, String type, String fromValue, String toValue, String note )` (`wikantik-work/src/main/java/com/wikantik/work/WorkEvent.java`). Event values the rules read:
  - `assigned`: `from_value`/`to_value` are the assignment text, a login, `queue:<key>`, or null (`WorkEventDiff.changeEvents`; also written by `JdbcWorkStore.createItem` when an item is created with a target).
  - `handoff`: `status|target` on both sides, target as above, possibly empty.
  - `status`: wire statuses. `claim_expired`: actor is the agent (CLAUDE.md, claims).
  - **`commented`, `comment_edited`, `comment_removed`: `to_value` is the comment id as decimal text** (`JdbcWorkDiscussion.addComment`/`editComment`/`removeComment`: `String.valueOf( c.id() )`). This is how an event is tied to its mentions.
- Mentions: `work_mention( id, item_id, comment_id, mentioned, created_at )` (V074), one row per mentioned login per comment, inserted in the comment's transaction (`JdbcWorkDiscussion.insertMentions`). An edit **deletes and re-inserts** the comment's rows (V075, `editComment`), so the rows always describe the comment's current text and their `created_at` cannot tell an old mention from a new one.
- `work_event` columns (V074): no CHECK on `type`; `actor_kind` default `'user'`. Grants `SELECT, INSERT`. Events are appended through `JdbcWorkCatalog.appendEvent( Connection, WorkEvent )`, which requires attribution (`requireAttributed`).
- `WorkStanding` (package-private, `WorkStanding.java`): `Optional< WorkRole > roleIn( long projectId, Actor actor )` and `List< Project > visibleProjects( Actor actor )`. A wiki admin gets no role from `roleFor`; `WorkScopes.scope` lets an admin through separately. `WorkStandingSourceRuleTest` scans the flat `com.wikantik.work` package only (not sub-packages) and fails any non-`Jdbc*` class whose source contains `store.roleFor(` or `store.visibleProjects(`: every class of this part therefore lives in the flat package.
- `WorkRole { VIEWER, MEMBER, LEAD }` with `atLeast( WorkRole )`. `Action.MANAGE_PROJECT` (lead, management, so an agent is refused by `WorkPolicy.may`).
- `WorkScopes` (package-private): `scope( Actor, String projectKey )`, `itemScope( Actor, String itemKey )`, `standing()`, `person( String login ) : Optional< WorkDirectory.Person >`, `displayName( String )`. `Scope.require( Action, Item )`, `Scope.requireWritable()` (conflict `archived`).
- `WorkDirectory` (`WorkDirectory.java`): `person( login ) : Optional< Person( login, groups, admin ) >` throws `WorkUnavailableException` on a failed lookup; `displayName( login )`. `WikiWorkDirectory` (`wikantik-main/src/main/java/com/wikantik/work/runtime/WikiWorkDirectory.java`) reads users through `AuthSubsystemBridge.fromLegacyEngine( engine ).users().getUserDatabase().findByLoginName( login )`, whose `UserProfile.getEmail()` (`com.wikantik.auth.user.UserProfile`) is the address; groups through `WikiWorkGroups` over `GroupManager` (`Group.members() : Principal[]`).
- `WorkAckStore.state( itemId ) : Ack( required, acknowledgedBy, acknowledgedAt )`, `Ack.waiting()`. Columns `work_item.ack_required`, `acknowledged_at` (V081). `Item.reporter()`, `Item.reporterAgentId()` (null for a person).
- `WorkService` constructors end at `( WorkStore, WorkRenderer, WorkDirectory, Clock, WorkSettings, WorkListener, WorkAudit )`; 30 call sites use the shorter ones. Facades `registry()`, `claims()`, `changes()`, `deployments()` are public non-final classes (REST tests mock them).
- `WorkListener` (default no-op methods) wrapped by `GuardedListener`; `WorkMetrics` registers `wikantik_work_*` counters with no project label. `WorkAudit.record( actorLogin, action, targetType, target, detail )`.
- `WorkServiceVisibilityPostgresTest` (`wikantik-work/src/test/java/com/wikantik/work/`): sets `WRITES`, `SYSTEM`, `OWN`, `YES_OR_NO`, `INSTANCE_READS`, `FACADES`, `READS` by method name across the service and facades. Every `READS` method is driven with fixtures (Actor, Strings `PLAT`/`PLAT-1`/…, `int` 10, `long` 1 and 999999, null otherwise) first as the lead `lena` and the member agent `codexActor`, catching only `WorkNotFoundException`; it must return non-empty data at least once, and each stranger must get not found or an empty collection. So a read must not throw `WorkDeniedException` at `codexActor`.
- `WorkAgentFixtures.TABLES` is the truncate list; `PostgresTestDb.truncate` is `RESTART IDENTITY CASCADE`, so tables with no foreign key to a truncated table must be listed by name.
- `FaultInjectingDataSource.failOn( int nthStatement, RuntimeException )`, `reset()`.
- `WorkClaimSweeper` (`…/runtime/WorkClaimSweeper.java`): daemon thread, `INTERVAL` 60 s, `FULL_BATCH` 500, `MAX_PASSES_PER_CYCLE` 10, `ALIVE_GAUGE` `wikantik_work_sweeper_alive`, `start( WorkService, Duration )`, `alive()`, `close()`. Started by `WikiEngine.startWorkClaimSweeper()` from the boot step `work-claim-sweeper`, which also registers the gauge; closed in `WikiEngine.shutdown()` through `closeQuietly( WorkClaimSweeper.class )`. Tests: `WorkClaimSweeperTest`, `WikiEngineWorkSweeperTest`.
- `WorkWiring.build( DataSource, Properties, Engine )` (`…/runtime/WorkWiring.java`) constructs the service with `new WorkSettings( claimExpiry( props ) )`.
- `MailUtil.sendMessage( Properties, String to, String subject, String content ) throws AddressException, MessagingException` (`wikantik-util/src/main/java/com/wikantik/util/MailUtil.java`); it logs the address at INFO itself. Callers pass `CoreSubsystemBridge.fromLegacyEngine( engine ).properties().asProperties()` (`UserProfileCreationNotifier`). Mail always has defaults (`mail.smtp.host = 127.0.0.1`), so "mail is configured" cannot be detected; see `docs/admin/SendingEmailFromTheWiki.md`.
- `EgressGuard` (`wikantik-connectors/src/main/java/com/wikantik/connectors/http/EgressGuard.java`): `static void check( URI ) throws EgressBlockedException` (http/https only; host must not resolve to loopback, private, link-local, multicast, any-local or IPv6 ULA unless `-Dwikantik.connectors.egress.allowPrivate=true`), `static boolean isRedirect( int )`, `static URI followsSafely( URI current, String location ) throws EgressBlockedException`, `MAX_REDIRECTS = 5`. Messages name the host, never the path.
- `wikantik.baseURL` (blank by default) is read as `props.getProperty( "wikantik.baseURL", "" )` elsewhere (`WikiPageUrls`, `ExportService`). Config keys live in `wikantik-main/src/main/resources/ini/wikantik.properties`, section `# [Work]`; enum types are written `# Type: enum(a|b)`.
- REST: `WorkResourceBase` (`wikantik-rest/src/main/java/com/wikantik/rest/`) with `protected static route( Builder, method, pattern, Handler )`, nested `Call` (`c.svc`, `c.actor`, `c.pathParam( n )`, `c.query`, `c.body()`, `c.json`, `c.tree`, `c.created`, `c.noContent`) and `toTree( Object, Actor )` (Gson plus `WorkJson.enrich`, which renders event people by display name). `/api/work/projects/*` belongs to `WorkProjectResource`, so a project sub-route cannot be a separate servlet. `WorkMeResource` builds `GET /api/work/me`; its test mocks `svc.deployments()`. Servlets are declared and mapped in `wikantik-war/src/main/webapp/WEB-INF/web.xml`. Resource tests use `WorkHttp.wire( servlet, mockService, ALICE )` and `WorkHttp.call( … )`.
- Frontend: routes in `wikantik-frontend/src/main.jsx` under `/work`; item page `/work/item/:key`; `api/work.js` (`request`, `json`, `query`, `enc`); `Sidebar.jsx` shows the Work entry when `useWorkNavVisible()`; `eventText.js` `settingsText` switches on `ev.note`, and `eventText` may return `standalone: true` so `eventSentence` omits the actor.
- IT: `wikantik-it-tests/wikantik-it-test-rest`, helpers in `WorkIt` (`admin()`, `account( admin, login, display )`, `await( surface, caller, path, predicate )`), engine properties in `src/test/config/it-rest-cascade.properties`.

## Decisions

- **P3-1. The cursor is a transaction id, not an event id.** Event ids are taken at insert but committed in any order, so a cursor of "the last event id read" skips an event whose transaction commits after a higher id was read. V086 adds `work_event.tx xid8 NOT NULL DEFAULT pg_current_xact_id()`; a reader takes only events with `tx < pg_snapshot_xmin( pg_current_snapshot() )` (every transaction that could still add such an event has ended), in `(tx, id)` order, and its cursor is the pair `(tx, event_id)` of the last event consumed. Nothing is ever skipped. Cost if wrong: a transaction left open anywhere in the database holds the window back, so notifications are delayed (never lost) until it ends; the operations doc says so. The `ALTER` rewrites `work_event` once.
- **P3-2. Tying a comment event to its mentions.** For `commented` and `comment_edited`, the comment is `to_value` (digits only; anything else means no mentions). "Mentioned by this event" means the comment's current `work_mention` rows minus every login that already has a `mentioned` notification for a `commented`/`comment_edited` event of the same item and comment. So `commented` notifies all of them, and an edit notifies only names it added (the outline's "every other type → nobody" would have left a name added by an edit unheard). A comment removed before dispatch notifies nobody and subscribes nobody; its author still starts watching.
- **P3-3. Leads include group leads.** `WorkDirectory` gains `default Set< String > groupMembers( String group )` (empty by default); a `group` member row with role `lead` contributes its members. Without it a team whose lead role is held by a group would never hear about acknowledgements or a webhook switched off.
- **P3-4. The standing check.** `WorkStanding.seesProject( long projectId, Actor actor ) : boolean` is true for a wiki admin, or when `roleIn` holds a role `atLeast( WorkRole.VIEWER )`. A recipient is resolved through `WorkDirectory.person( login )` into `Actor.human( login, groups, admin )`; no account means no row. A directory failure (`WorkUnavailableException`) rolls back the whole dispatch batch; the next cycle retries it.
- **P3-5. Preferences write no `work_event`.** Watching, muting, read state and the email choice are a person's own settings, like a saved filter, not project state. They are not in any history.
- **P3-6. Opening an item marks its notifications read by an explicit call** (`POST /api/work/inbox/read/{key}`) from the item page, not as a side effect of `GET /api/work/items/{key}`, which stays a pure read (the visibility test drives it as one).
- **P3-7. A webhook switched off after 24 hours of failures** writes one project `settings` event (note `webhook`, from the host, to `failed`), attributed to the webhook's owner because the event log has no system actor before Part 5. Its notifications have the new reason `webhook_disabled` and reach every lead, the owner included (the rule "never the person who did it" does not apply: nobody did it). No `WorkAudit` record: the audit trail is for a person's act.
- **P3-8. A webhook speaks for an owner.** `work_webhook.owner` is the lead who added it or last changed it. Deliveries happen only while the owner is a lead of the project or a wiki admin; otherwise the webhook is paused (cursor kept, no failure counted) and its view says `paused: true`. This is the design's "checked against the reader's standing at the moment of delivery" for a reader outside the wiki: a lead removed from the team cannot keep streaming the project to their own endpoint. Another lead resumes it by saving it.
- **P3-9. Webhook URLs:** `https` only, no user-info, at most 2,000 characters, at most 10 webhooks per project. Re-enabling a switched-off webhook resumes from now, not from where it stopped (a day of backlog to an endpoint that was down is noise).
- **P3-10. Only leads and admins see a project's webhooks.** For anyone else in the project, `list` returns an empty list rather than refusing (an agent member must not get `WorkDeniedException` from a read).
- **P3-11. Email:** only rows created in the last 48 hours are mailed, so turning email on, or an outage, never sends history. A message is sent and then its rows are stamped (at least once: a crash between the two repeats one message). `off` stamps nothing; a person with no address, no account, or rows they can no longer see has those rows stamped without sending. Email needs an absolute base URL: when email is enabled and both `wikantik.work.notify.baseUrl` and `wikantik.baseURL` are blank, wiring logs a WARN and email stays off. Webhook payloads then carry paths.
- **P3-12. One node sends.** Email and webhook sends run outside any database transaction (a transaction held across a network call would hold back P3-1's window). Two wiki nodes would each send; the operations doc says to run notifications on one node. The inbox dispatch is safe with any number (cursor row lock plus `UNIQUE ( recipient, event_id )`).
- **P3-13. Agents** are never recipients and cannot watch or set an email choice (`WorkDeniedException` "Agents are not notified."); their inbox reads are empty and their `watchState` is `none`.
- **P3-14. Archived projects** refuse watch changes and every webhook write with `archived`, as every other write does.
- **P3-15. Slack text is escaped** (`&` → `&amp;`, `<` → `&lt;`, `>` → `&gt;`) in titles, names and event text, so an item titled `<!channel>` cannot ping a channel.
- **P3-16. Order.** Webhooks deliver in `(tx, id)` order; events of one transaction keep their order, and two overlapping transactions arrive in the order they began. Payloads carry `event.id` and `event.at`. The inbox dispatcher processes each batch in event-id order.
- **P3-17. Two keys beyond the outline:** `wikantik.work.notify.intervalSeconds` (how often the dispatcher runs; the IT sets 2) and the 48-hour window (a constant, not a key).
- **P3-18. No retention** for `work_notification` in this part; the app role has no `DELETE` on it. Rows are small and the inbox index is `(recipient, id DESC)`.
- **P3-19. `/work/inbox` shadows a project keyed `INBOX`**, exactly as `/work/log` shadows `LOG` today (React Router matches case-insensitively). Not fixed here.

## Review Focus

1. **Lost access between the event and the dispatch, or after it.** A member removed before the dispatch gets no row; one removed after it stops seeing and counting the row; email re-checks before sending. Task 3.3b (`WorkInboxPostgresTest`, `WorkDispatchPostgresTest`), Task 3.4 (`WorkEmailPostgresTest`).
2. **An event that commits after a higher event id was read.** It is still notified, exactly once. Task 3.3a (`WorkDispatchOrderPostgresTest`).
3. **A crash in the middle of a dispatch.** At every statement, a failure leaves no row and the cursor unmoved. Task 3.3a (`JdbcWorkNotificationsRollbackTest`).
4. **A webhook URL pointing inside the network, or redirecting there.** Refused at save and at every hop of every delivery. Task 3.5a (service), Task 3.5b (`WikiWorkOutboundTest`).
5. **A webhook outliving its owner's standing,** or showing its URL path or secret anywhere after creation (response, `toString`, log, error text). Tasks 3.5a, 3.5b, 3.6a.
6. **Text leaving through email or Slack:** comment or body text in `summary` mode, a login in any payload, a `<!channel>` title. Tasks 3.2 (`NoticeTextTest`), 3.4 (`EmailDigestTest`), 3.5a (`WebhookPayloadsTest`).
7. **An agent becoming a recipient** by commenting, being "mentioned", or reporting an item. Tasks 3.2, 3.3b.

---

### Task 3.1: Schema V086 and watching

**Files:**
- Create: `bin/db/migrations/V086__work_notifications.sql`, `wikantik-work/src/main/java/com/wikantik/work/WatchState.java`, `WorkWatchStore.java`, `JdbcWorkWatches.java`
- Modify: `WorkStore.java` (accessor), `JdbcWorkStore.java` (construct), `wikantik-work/src/test/java/com/wikantik/work/WorkAgentFixtures.java` (`TABLES` gains `"work_notify_cursor", "work_notify_pref"`, which no foreign key reaches)
- Test: `WorkNotificationsSchemaTest.java`, `JdbcWorkWatchesPostgresTest.java`

**Interfaces — produces:**
```sql
ALTER TABLE work_event ADD COLUMN IF NOT EXISTS tx xid8 NOT NULL DEFAULT pg_current_xact_id();
CREATE INDEX IF NOT EXISTS idx_work_event_tx ON work_event (tx, id);
CREATE INDEX IF NOT EXISTS idx_work_event_project_tx ON work_event (project_id, tx, id);
CREATE TABLE IF NOT EXISTS work_watch (
  item_id BIGINT NOT NULL REFERENCES work_item (id),
  login   TEXT   NOT NULL,
  state   TEXT   NOT NULL CHECK (state IN ('watching','muted')),
  PRIMARY KEY (item_id, login));
CREATE INDEX IF NOT EXISTS idx_work_watch_login ON work_watch (login);
CREATE TABLE IF NOT EXISTS work_notification (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  recipient  TEXT   NOT NULL,
  event_id   BIGINT NOT NULL REFERENCES work_event (id),
  project_id BIGINT NOT NULL REFERENCES work_project (id),
  item_id    BIGINT REFERENCES work_item (id),
  reason     TEXT   NOT NULL CHECK (reason IN
             ('assigned','mentioned','watching','acknowledge','claim_expired','webhook_disabled')),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  read_at    TIMESTAMP WITH TIME ZONE,
  emailed_at TIMESTAMP WITH TIME ZONE,
  UNIQUE (recipient, event_id));
CREATE INDEX IF NOT EXISTS idx_work_notification_inbox ON work_notification (recipient, id DESC);
CREATE INDEX IF NOT EXISTS idx_work_notification_unemailed ON work_notification (created_at) WHERE emailed_at IS NULL;
CREATE TABLE IF NOT EXISTS work_notify_cursor (
  name TEXT PRIMARY KEY, tx xid8 NOT NULL, event_id BIGINT NOT NULL);
CREATE TABLE IF NOT EXISTS work_notify_pref (
  login TEXT PRIMARY KEY,
  email_mode TEXT NOT NULL CHECK (email_mode IN ('each','daily','off')),
  last_digest_on DATE);
CREATE TABLE IF NOT EXISTS work_webhook (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id      BIGINT NOT NULL REFERENCES work_project (id),
  url             TEXT   NOT NULL,
  secret          TEXT,
  format          TEXT   NOT NULL CHECK (format IN ('generic','slack')),
  event_types     TEXT[] NOT NULL DEFAULT '{}',
  owner           TEXT   NOT NULL,
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  cursor_tx       xid8   NOT NULL,
  cursor_event_id BIGINT NOT NULL,
  failing_since   TIMESTAMP WITH TIME ZONE,
  last_error      TEXT,
  last_delivered_at TIMESTAMP WITH TIME ZONE,
  disabled_at     TIMESTAMP WITH TIME ZONE,
  CHECK (format <> 'generic' OR secret IS NOT NULL));
CREATE INDEX IF NOT EXISTS idx_work_webhook_project ON work_webhook (project_id);
GRANT SELECT, INSERT, UPDATE, DELETE ON work_watch, work_webhook, work_notify_pref TO :app_user;
GRANT SELECT, INSERT, UPDATE ON work_notification, work_notify_cursor TO :app_user;
```
The header comment says: the event log is the outbox; `tx` is the inserting transaction (P3-1); preference tables may delete rows; `work_notification` is never deleted.
- `enum WatchState implements WireEnum { WATCHING("watching"), MUTED("muted"), NONE("none") }` with `fromWire( String )` (field `state`). `NONE` is never stored.
- `public interface WorkWatchStore` (all `throws SQLException`): `void watch( long itemId, String login )` (`INSERT … ON CONFLICT DO NOTHING`, so a mute stays), `void subscribe( long itemId, String login )` (upsert to `watching`, overriding a mute), `void mute( long itemId, String login )` (upsert `muted`), `void unwatch( long itemId, String login )` (delete), `WatchState stateOf( long itemId, String login )`, `Set< String > watchers( long itemId )` (state `watching` only).
- `JdbcWorkWatches` also has package-private `Connection`-taking twins of `watch`, `subscribe` and `watchers` for the dispatch transaction.
- `WorkStore.watches() : WorkWatchStore`.

- [ ] **Step 1: Failing schema tests** in `WorkNotificationsSchemaTest` (model on `WorkChangesSchemaTest`; read the file as `Path.of( "..", "bin", "db", "migrations", "V086__work_notifications.sql" )`): `everyEventGetsTheTransactionThatWroteIt` (two inserts in one transaction share `tx`, a later transaction's is greater), `aWatchStateOutsideTheListIsRefused` (`23514`), `aNotificationReasonOutsideTheListIsRefused` (`23514`), `onePersonGetsOneRowPerEvent` (`23505`), `aProjectLevelNotificationNeedsNoItem`, `aGenericWebhookNeedsASecret` (`23514`; a `slack` one with null secret inserts), `theAppRoleCannotDeleteNotifications` (the GRANT statements parsed from the file equal the two lines above), `v086IsANoOpWhenReapplied` (apply twice with `:app_user` → `CURRENT_USER`).
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest=WorkNotificationsSchemaTest` — FAIL, relation `work_watch` does not exist.
- [ ] **Step 3:** Write the migration. Apply it twice by hand with `psql` against the dev database; no error.
- [ ] **Step 4: Failing store tests** in `JdbcWorkWatchesPostgresTest`: `watchDoesNotUndoAMute`, `subscribeOverridesAMute`, `watchersOmitTheMuted`, `unwatchRemovesTheRow`, `stateOfIsNoneWithoutARow`.
- [ ] **Step 5:** Implement `WatchState`, `WorkWatchStore`, `JdbcWorkWatches`, the accessor and the `TABLES` change. `mvn test -pl wikantik-work -Dtest='WorkNotificationsSchemaTest,JdbcWorkWatchesPostgresTest'` — PASS.
- [ ] **Step 6: Commit** `git add bin/db/migrations/V086__work_notifications.sql wikantik-work/src/main/java/com/wikantik/work/{WatchState,WorkWatchStore,JdbcWorkWatches,WorkStore,JdbcWorkStore}.java wikantik-work/src/test/java/com/wikantik/work/{WorkNotificationsSchemaTest,JdbcWorkWatchesPostgresTest,WorkAgentFixtures}.java` — `feat(work): notification tables, event transaction ids and watching (V086)`.

---

### Task 3.2: Who hears what (pure rules)

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/NoticeReason.java`, `EventFacts.java`, `Recipients.java`, `AutoWatch.java`, `NoticeText.java`
- Test: `RecipientsTest.java`, `AutoWatchTest.java`, `NoticeTextTest.java`

**Interfaces — produces:**
- `enum NoticeReason implements WireEnum { MENTIONED("mentioned"), ASSIGNED("assigned"), ACKNOWLEDGE("acknowledge"), CLAIM_EXPIRED("claim_expired"), WEBHOOK_DISABLED("webhook_disabled"), WATCHING("watching") }`; declaration order is strength, strongest first; `fromWire`.
- `public record EventFacts( String reporter, Set< String > watchers, Set< String > mentioned, Set< String > leads, Set< String > leadGroups, boolean ackPending, boolean commentRemoved )`. `reporter` is null when an agent reported the item. `mentioned` is "mentioned by this event" (P3-2). `EventFacts withGroupLeads( Set< String > members )` returns a copy whose `leads` also holds `members` and whose `leadGroups` is empty.
- `final class Recipients { static Map< String, NoticeReason > of( WorkEvent e, EventFacts f ) }` returning a `TreeMap` (login → strongest reason). Rules:

| Event | Who, with reason |
|---|---|
| `assigned` | the person in `to_value` (not `queue:…`, not null): `assigned` |
| `handoff` | the person after `|` in `to_value`: `assigned`; watchers: `watching` |
| `commented` | `f.mentioned`: `mentioned`; watchers: `watching`; nobody when `f.commentRemoved` |
| `comment_edited` | `f.mentioned` only: `mentioned`; nobody when `f.commentRemoved` |
| `status` | watchers: `watching`; when `to_value` is `done`, `actorKind` is `AGENT` and `f.ackPending`: leads: `acknowledge` |
| `handoff` resulting in `done` by an agent with `f.ackPending` | leads: `acknowledge` (in addition to the row above) |
| `review_requested`, `change_merged`, `shipped`, `rolled_back` | watchers: `watching` |
| `claim_expired` | `f.reporter` when not null: `claim_expired` |
| `settings` with note `webhook` and `to_value` `failed` | leads: `webhook_disabled`; the actor is **not** removed (P3-7) |
| anything else | nobody |

  Then, except for the webhook row, the actor is removed when `actorKind` is `HUMAN`.
- `final class AutoWatch { record Change( String login, boolean subscribe ) {} static List< Change > of( WorkEvent e, String reporter, Set< String > mentioned, boolean commentRemoved ) }`: `created` by a person → `( reporter, false )`; `assigned`/`handoff` to a person → `( person, true )`; `commented` by a person → `( actor, false )`; `commented`/`comment_edited` not removed → `( m, true )` for each mentioned; nothing else. An agent actor never yields a change for itself.
- `final class NoticeText { static String line( WorkEvent e, NoticeReason reasonOrNull, String recipientOrNull, Function< String, String > displayName ) }`: plain text, one line, the actor named first (an agent by `e.actor()`, a person by `displayName`). Status words replace `_` with a space; a `queue:<key>` target reads "the <key> queue"; the recipient reads "you".

| Event | Line |
|---|---|
| `assigned` | `<A> assigned this to <you\|name\|the X queue>`; to null: `<A> unassigned this` |
| `handoff` | `<A> handed this to <you\|name\|the X queue> as <status>` |
| `status` | `<A> moved this from <a> to <b>` |
| `commented` | reason `mentioned`: `<A> mentioned you in a comment`; else `<A> commented` |
| `comment_edited` | `<A> mentioned you in an edited comment` |
| `review_requested` | `<A> asked for a review` |
| `change_merged` | `<A> merged a change` |
| `shipped` | `<A> shipped this` |
| `rolled_back` | `<A> rolled back a deployment that carried this` |
| `claim_expired` | `<A>'s claim lapsed and the item went back to its queue` |
| `settings`, note `webhook`, to `failed` | `The webhook to <from_value> was switched off after failing for 24 hours.` |
| anything else | `<A> updated this` |

  It never prints `note`, `from_value` or `to_value` raw except the host in the webhook line and status words; no login ever appears (people go through `displayName`).

- [ ] **Step 1: Failing tests.** `RecipientsTest`: one method per table row (`anAssignmentReachesTheNewAssigneeOnly`, `anAssignmentToAQueueReachesNobody`, `aHandoffReachesTheNewAssigneeAndWatchers`, `aCommentReachesTheMentionedAndWatchers`, `aRemovedCommentReachesNobody`, `anEditedCommentReachesOnlyNewlyMentioned`, `aStatusChangeReachesWatchers`, `anAgentsCloseNeedingAcknowledgementReachesLeads`, `aPersonsCloseReachesNoLeads`, `anAcknowledgedCloseReachesNoLeads`, `changeAndDeploymentEventsReachWatchers`, `aLapsedClaimReachesThePersonWhoReportedIt`, `aLapsedClaimOnAnAgentsItemReachesNobody`, `aWebhookSwitchedOffReachesEveryLeadIncludingItsOwner`, `otherTypesReachNobody` over `created`, `edited`, `field`, `claimed`, `released`, `member`, `linked`), plus `theActorNeverAppears` (actor is a watcher, mentioned and assignee at once) and `mentionedAndWatchingGetsMentionedOnce`. `AutoWatchTest`: `aPersonWhoReportsWatches`, `anAgentWhoReportsWatchesNothing`, `anAssigneeIsSubscribed`, `aCommenterWatchesAndTheMentionedAreSubscribed`, `anAgentCommenterWatchesNothingButItsMentionsSubscribe`, `aRemovedCommentSubscribesNobody`. `NoticeTextTest`: one per table row, `anAgentIsNamedByItsName`, `aLoginNeverAppears` (display-name function maps `lena` → `Lena Lead`; assert no line contains `lena`), `aNoteIsNeverPrinted` (status event with note `secret note`).
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='RecipientsTest,AutoWatchTest,NoticeTextTest'` — compilation failure.
- [ ] **Step 3:** Implement the five classes. Re-run — PASS.
- [ ] **Step 4: Commit** `git add wikantik-work/src/main/java/com/wikantik/work/{NoticeReason,EventFacts,Recipients,AutoWatch,NoticeText}.java wikantik-work/src/test/java/com/wikantik/work/{RecipientsTest,AutoWatchTest,NoticeTextTest}.java` — `feat(work): notification recipients, auto-watch and notice text`.

---

### Task 3.3a: The dispatch transaction (store)

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/NoticeView.java`, `EmailMode.java`, `WorkNotificationStore.java`, `JdbcWorkNotifications.java`
- Modify: `WorkStore.java` (`notifications()`), `JdbcWorkStore.java`
- Test: `JdbcWorkNotificationsPostgresTest.java`, `WorkDispatchOrderPostgresTest.java`, `JdbcWorkNotificationsRollbackTest.java`

**Interfaces — consumes:** `AutoWatch.of`, `EventFacts`, `NoticeReason` (Task 3.2); `JdbcWorkWatches` connection twins (3.1); `JdbcWorkStore.EVENT_COLUMNS`, `EVENT_FROM`, `EVENT_ROW`.

**Interfaces — produces:**
- `public record NoticeView( long id, NoticeReason reason, Instant createdAt, Instant readAt, WorkEvent event, String projectKey, String itemTitle )` (`itemTitle` null for a project-level notice; the item key is `event.itemKey()`).
- `public interface WorkNotificationStore`:
  - `@FunctionalInterface interface NoticePlanner { Map< String, NoticeReason > plan( WorkEvent e, EventFacts facts ) throws SQLException; }`
  - `int dispatch( int maxEvents, NoticePlanner planner ) throws SQLException` — see below.
  - `List< NoticeView > inbox( String recipient, Set< Long > projectIds, Long beforeId, int limit )`, `int unread( String recipient, Set< Long > projectIds )`, `int markRead( String recipient, long itemId, Instant at )`, `int markAllRead( String recipient, Set< Long > projectIds, Instant at )`: every one filters `project_id = ANY( ? )` in the statement and returns empty / 0 with no query when the set is empty.
  - `EmailMode emailMode( String login )` (`EACH` without a row), `void setEmailMode( String login, EmailMode mode )` (upsert), where `enum EmailMode implements WireEnum { EACH("each"), DAILY("daily"), OFF("off") }` (field `email`).
- `dispatch` runs in one `jdbc.inTransaction`:
  1. `INSERT INTO work_notify_cursor (name, tx, event_id) VALUES ('inbox', pg_snapshot_xmin(pg_current_snapshot()), 0) ON CONFLICT (name) DO NOTHING`; when a row was inserted, return 0 (a fresh install notifies no history).
  2. `SELECT tx::text, event_id FROM work_notify_cursor WHERE name = 'inbox' FOR UPDATE`.
  3. `SELECT <EVENT_COLUMNS>, e.tx::text AS tx FROM work_event e JOIN work_project p … LEFT JOIN work_item i … WHERE (e.tx, e.id) > (?::xid8, ?) AND e.tx < pg_snapshot_xmin(pg_current_snapshot()) ORDER BY e.tx, e.id LIMIT ?`.
  4. Remember the last row in `(tx, id)` order; then handle the rows in `id` order. An event with no item is skipped unless it is a `settings` event with note `webhook` and `to_value` `failed`. For an item event: read the item's `reporter`, `reporter_agent_id`; for `commented`/`comment_edited` with a digits-only `to_value`, read `commentRemoved` and the P3-2 mentions:
     ```sql
     SELECT DISTINCT m.mentioned FROM work_mention m
      WHERE m.comment_id = ? AND NOT EXISTS (
            SELECT 1 FROM work_notification n JOIN work_event pe ON pe.id = n.event_id
             WHERE n.recipient = m.mentioned AND n.reason = 'mentioned' AND pe.item_id = ?
               AND pe.type IN ('commented','comment_edited') AND pe.to_value = ? )
     ```
     apply `AutoWatch.of( … )` through the watch twins on this connection, then read watchers (after auto-watch), the lead rows (`SELECT principal_kind, principal FROM work_member WHERE project_id = ? AND role = 'lead' AND principal_kind IN ('user','group')`, split into `leads` and `leadGroups`), and `ackPending` (`ack_required AND acknowledged_at IS NULL AND status = 'done'`). Call the planner; insert each returned row `ON CONFLICT (recipient, event_id) DO NOTHING`.
  5. `UPDATE work_notify_cursor SET tx = ?::xid8, event_id = ? WHERE name = 'inbox'` with the remembered last row. Return the number of events read.

- [ ] **Step 1: Failing tests.** `JdbcWorkNotificationsPostgresTest` (a planner lambda that returns fixed rows): `theFirstDispatchStartsAtTheCurrentEndOfTheLog` (events written before it are never notified), `aDispatchConsumesEventsAndMovesTheCursor`, `aSecondDispatchFindsNothingNew`, `twoDispatchersProduceEachRowOnce` (two threads, a `CountDownLatch`, 50 events; row count equals the expected, no duplicate), `autoWatchIsAppliedBeforeThePlannerSeesWatchers`, `aCommentsMentionsReachThePlannerOnce` (comment mentions `otto`; a later edit adding `pia` gives the planner `{pia}` only), `aRemovedCommentIsReportedRemoved`, `groupLeadsArriveAsGroups`, `inboxReadsOnlyTheGivenProjects`, `unreadAndMarkReadCountOnlyTheGivenProjects`, `emailModeDefaultsToEach`.
  `WorkDispatchOrderPostgresTest`: `anEventCommittedAfterAHigherIdIsNotSkipped` — connection A (`autoCommit` off) inserts an event (lower id) and stays open; connection B inserts and commits an event (higher id); `dispatch` returns 0; A commits; `dispatch` returns 2 and both events have their rows. `aTransactionThatStartedEarlierHoldsTheWindow` — A takes an xid first (`SELECT pg_current_xact_id()`), B inserts and commits, A inserts and stays open; `dispatch` returns 0; after A commits, both are dispatched once.
  `JdbcWorkNotificationsRollbackTest.everyFailurePointLeavesNothing`: seed the cursor with one dispatch, write one `commented` event that mentions a person; then for `n = 1, 2, …` until a run succeeds: `faulty.reset(); faulty.failOn( n, new RuntimeException( "boom " + n ) )`, call `dispatch` through a store over `faulty`, and when it throws assert no `work_notification` row, no `work_watch` row, and the cursor row unchanged. Assert at least five failure points were exercised and the final run wrote the rows.
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='JdbcWorkNotificationsPostgresTest,WorkDispatchOrderPostgresTest,JdbcWorkNotificationsRollbackTest'` — compilation failure.
- [ ] **Step 3:** Implement. `xid8` values travel as text (`tx::text`, `?::xid8` with `setString`).
- [ ] **Step 4:** Re-run — PASS. `mvn test-compile -pl wikantik-work`.
- [ ] **Step 5: Commit** `git add wikantik-work/src/main/java/com/wikantik/work/{NoticeView,EmailMode,WorkNotificationStore,JdbcWorkNotifications,WorkStore,JdbcWorkStore}.java wikantik-work/src/test/java/com/wikantik/work/{JdbcWorkNotificationsPostgresTest,WorkDispatchOrderPostgresTest,JdbcWorkNotificationsRollbackTest}.java` — `feat(work): dispatch notifications from the event log by transaction window`.

---

### Task 3.3b: The notifications service and the inbox

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/WorkNotificationsService.java`, `NoticePlanning.java`, `NotifySettings.java`, `EmailDetail.java`, `NotifyPreference.java`, `WorkMailer.java`, `WorkOutbound.java`, `WorkNotifyPorts.java`
- Modify: `WorkService.java` (constructor, `notifications()`), `WorkStanding.java` (`seesProject`), `WorkDirectory.java` (`email`, `groupMembers`), `WorkListener.java`, `GuardedListener.java`, `WorkMetrics.java`
- Test: `WorkDispatchPostgresTest.java`, `WorkInboxPostgresTest.java`, `WorkStandingPostgresTest.java` (extend), `WorkServiceVisibilityPostgresTest.java` (extend), `WorkMetricsTest.java` (extend: `notificationsAreCountedByChannelAndOutcome`, no project tag)

**Interfaces — consumes:** `WorkNotificationStore` (3.3a), `Recipients.of` (3.2), `WorkWatchStore` (3.1).

**Interfaces — produces:**
- `WorkStanding.boolean seesProject( long projectId, Actor actor ) throws SQLException`: `actor.instanceAdmin() || roleIn( projectId, actor ).filter( r -> r.atLeast( WorkRole.VIEWER ) ).isPresent()`. Javadoc: "a wiki admin, or viewer or above".
- `WorkDirectory`: `default Optional< String > email( String login ) { return Optional.empty(); }` (throws `WorkUnavailableException` when the directory cannot be read), `default Set< String > groupMembers( String group ) { return Set.of(); }`.
- `public record NotifySettings( Duration dispatchInterval, boolean emailEnabled, EmailDetail emailDetail, Duration emailInterval, int dailyHour, ZoneId zone, String baseUrl, boolean webhooksEnabled )` with `public static final NotifySettings OFF` (60 s, false, `SUMMARY`, 5 min, 8, `ZoneOffset.UTC`, `""`, false).
- `enum EmailDetail implements WireEnum { SUMMARY("summary"), FULL("full") }`.
- `@FunctionalInterface public interface WorkMailer { void send( String to, String subject, String text ) throws IOException; WorkMailer NONE = …throws IOException( "mail is not wired" ); }`.
- `public interface WorkOutbound { void check( URI url ); int post( URI url, String contentType, String body, Map< String, String > headers ) throws IOException; final class Refused extends IOException { public Refused( String message ) } WorkOutbound NONE = … }`. `check` throws `WorkValidationException( "url", <reason> )`; `NONE.check` says "Webhooks are not available on this wiki."; `NONE.post` throws `Refused`.
- `public record WorkNotifyPorts( WorkMailer mailer, WorkOutbound outbound, NotifySettings settings ) { public static final WorkNotifyPorts NONE = new WorkNotifyPorts( WorkMailer.NONE, WorkOutbound.NONE, NotifySettings.OFF ); }`.
- `WorkService( WorkStore, WorkRenderer, WorkDirectory, Clock, WorkSettings, WorkListener, WorkAudit, WorkNotifyPorts )`; the current 7-argument constructor delegates with `WorkNotifyPorts.NONE`. `public WorkNotificationsService notifications()`.
- `public record NotifyPreference( EmailMode email, boolean emailAvailable, boolean webhooksAvailable )`.
- `public class WorkNotificationsService` (constructed with store, scopes, people, clock, listener, ports):
  - `List< NoticeView > inbox( Actor a, Long beforeId, int limit )` — limit clamped 1–100 (0 → 50); project set from `standing().visibleProjects( a )`; an agent gets an empty list.
  - `int unread( Actor a )`, `void markRead( Actor a, String itemKey )` (`itemScope` first: an unseen item is not found), `void markAllRead( Actor a )`.
  - `WatchState watchState( Actor a, String itemKey )` (an agent gets `NONE`), `WatchState setWatch( Actor a, String itemKey, WatchState state )`: `itemScope`; agent → `WorkDeniedException( "Agents are not notified." )`; `requireWritable()`; `WATCHING` → `subscribe`, `MUTED` → `mute`, `NONE` → `unwatch`; returns the state now stored.
  - `NotifyPreference preference( Actor a )`, `NotifyPreference setPreference( Actor a, EmailMode mode )` (agent refused as above; `emailAvailable` = `settings.emailEnabled()`, `webhooksAvailable` = `settings.webhooksEnabled()`).
  - `int dispatch( int maxEvents )` — `store.notifications().dispatch( maxEvents, new NoticePlanning( scopes, people-directory, listener ) )`.
  - `NotifySettings settings()`.
  - `int sendEmail( Instant now )` (Task 3.4).
- `final class NoticePlanning implements WorkNotificationStore.NoticePlanner`, one instance per dispatch call: expands `leadGroups` through `WorkDirectory.groupMembers`, calls `Recipients.of`, then keeps a login only when `scopes.person( login )` is present and `standing().seesProject( e.projectId(), Actor.human( p.login(), p.groups(), p.admin() ) )`. Person and standing answers are cached per call. Reports `listener.notified( "inbox", "delivered" )` per kept row and `"dropped"` per removed one.
- `WorkListener.default void notified( String channel, String outcome )` (channel `inbox`, `email`, `webhook`; outcome `delivered`, `dropped`, `failed`); `GuardedListener` forwards it guarded; `WorkMetrics` counts `wikantik_work_notifications_total{channel,outcome}`.
- Visibility classification: `FACADES` += `notifications`; `READS` += `watchState`; `WRITES` += `markRead`, `setWatch`; `OWN` += `inbox`, `unread`, `markAllRead`, `preference`, `setPreference` (widen its Javadoc: "the caller's own record, or the caller's own notifications and settings"); `SYSTEM` += `dispatch`, `sendEmail`, `settings`. `everyProjectScopedWriteHidesInvisibleProject` gains `service.notifications().markRead( mallory, "PLAT-1" )` and `setWatch( mallory, "PLAT-1", WatchState.WATCHING )`.

- [ ] **Step 1: Failing tests.** `WorkStandingPostgresTest`: `seesProjectIsTrueForAViewer`, `seesProjectIsFalseForAStranger`, `seesProjectIsTrueForAWikiAdminWithNoRow`, `seesProjectIsFalseForAMemberAgentUnderAStrangerSponsor`.
  `WorkDispatchPostgresTest` (real service, store, a directory stub with `groupMembers` and `person`): `anAssigneeHearsOfTheAssignment`, `watchersHearOfAStatusChange`, `theActorHearsNothing`, `anAgentIsNeverARecipient` (an agent comments mentioning a person and is itself named in a raw `work_mention` row: no row for the agent, no watch for the agent), `aMemberRemovedBeforeDispatchGetsNoRow` (Review Focus 1), `anAccountThatIsGoneGetsNoRow`, `aGroupLeadHearsAnAgentsUnacknowledgedClose`, `aDirectoryOutageRollsTheBatchBack` (`person` throws `WorkUnavailableException`: `dispatch` throws, no rows, the next `dispatch` with a working directory writes them), `aMutedWatcherHearsNothingUntilMentioned`, `metricsCountDeliveredAndDropped`.
  `WorkInboxPostgresTest`: `theInboxShowsNewestFirstWithTitles`, `aMemberRemovedAfterDispatchNoLongerSeesOrCountsTheRow` (Review Focus 1), `aStrangersInboxIsEmpty`, `markReadNeedsAVisibleItem` (stranger: not found), `markAllReadTouchesOnlyTheCallersRows`, `ownMethodsTouchOnlyTheCallersRows` (otto's `markAllRead`, `setPreference` leave lena's rows and preference as they were), `agentsCannotWatchOrSetEmail`, `setWatchOnAnArchivedProjectIsArchived`, `watchStateRoundTrips`.
  `WorkServiceVisibilityPostgresTest`: run it — it fails on classification until the sets above are updated.
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='WorkStandingPostgresTest,WorkDispatchPostgresTest,WorkInboxPostgresTest,WorkServiceVisibilityPostgresTest'` — compilation failure, then classification failure.
- [ ] **Step 3:** Implement. Keep `WorkNotificationsService` under the PMD limits by putting the planner in `NoticePlanning` and email in `EmailSender` (Task 3.4); no suppressions.
- [ ] **Step 4:** Re-run — PASS. `mvn test-compile -pl wikantik-work,wikantik-rest,wikantik-work-mcp,wikantik-main`.
- [ ] **Step 5: Commit** `git add` the files listed above by name — `feat(work): notifications service, inbox and standing at dispatch and read`.

---

### Task 3.3c: The dispatcher thread and its wiring

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/work/runtime/WorkDaemon.java`, `WorkNotifyDispatcher.java`, `WorkNotifyWiring.java`
- Modify: `WorkClaimSweeper.java` (delegate its loop to `WorkDaemon`; public API unchanged), `WorkWiring.java` (pass `WorkNotifyWiring.ports( props, engine )`), `WikiWorkDirectory.java` (`email`, `groupMembers`), `wikantik-main/src/main/java/com/wikantik/WikiEngine.java`, `wikantik-main/src/main/resources/ini/wikantik.properties`
- Test: `WorkClaimSweeperTest` (unchanged, must stay green), `WorkNotifyDispatcherTest.java`, `WorkNotifyWiringTest.java`, `WikiWorkDirectoryTest.java` (extend), `wikantik-main/src/test/java/com/wikantik/WikiEngineWorkDispatcherTest.java` (model on `WikiEngineWorkSweeperTest`)

**Interfaces — produces:**
- `final class WorkDaemon implements AutoCloseable` (package-private): `WorkDaemon( String threadName, Duration interval, Runnable cycle, String stoppedMessage )`, `void start()`, `boolean alive()`, `boolean closed()`, `void close()`: the loop, timed wait, uncaught-throwable handler (LOG.error with `stoppedMessage`, then the default handler) and bounded join moved verbatim from `WorkClaimSweeper`.
- `public final class WorkNotifyDispatcher implements AutoCloseable`: `public static final String ALIVE_GAUGE = "wikantik_work_dispatcher_alive"`, `static final int FULL_BATCH = 500`, `static final int MAX_PASSES_PER_CYCLE = 10`, `public static WorkNotifyDispatcher start( WorkService service, Clock clock )` (interval from `service.notifications().settings().dispatchInterval()`), `alive()`, `close()`. One cycle: dispatch passes while a pass returns `FULL_BATCH` (at most 10); then `service.webhooks().deliver( now )` (Task 3.5b; until then this step is absent); then, when `emailInterval` has passed since the last email run, `service.notifications().sendEmail( now )`. Each step is caught separately (`SQLException | RuntimeException` → WARN "…; the next cycle will try again", DEBUG when closed), so one failing never skips the others.
- `WikiEngine`: `void startWorkNotifyDispatcher()` and the test seam `void startWorkNotifyDispatcher( Function< WorkService, WorkNotifyDispatcher > starter )`, mirroring `startWorkClaimSweeper` (close any previous one, register the gauge before starting, LOG.error and continue on a start failure); boot step `BootStep.abortRestOnFailure( "work-notify-dispatcher", env -> startWorkNotifyDispatcher() )` after `work-claim-sweeper`; `shutdown()` adds `closeQuietly( WorkNotifyDispatcher.class )` beside the sweeper's.
- `final class WorkNotifyWiring` (static): `static NotifySettings settings( Properties props )`, `static WorkNotifyPorts ports( Properties props, Engine engine )` (mailer and outbound come in Tasks 3.4 and 3.5b; until then `WorkMailer.NONE`, `WorkOutbound.NONE`). Constants and keys (declared in `ini/wikantik.properties` `[Work]` first):

| Constant | Key | Default | `Type:` | Out of range |
|---|---|---|---|---|
| `PROP_INTERVAL` | `wikantik.work.notify.intervalSeconds` | `60` | `int` | outside 1–3600 → 60, WARN |
| `PROP_BASE_URL` | `wikantik.work.notify.baseUrl` | (blank) | `url` | `Blank means: wikantik.baseURL; when both are blank email stays off and webhook payloads carry paths` |

  Trailing `/` is stripped from the resolved base URL.
- `WikiWorkDirectory.email( login )`: `findByLoginName( login ).getEmail()`, blank → empty; `NoSuchPrincipalException` → empty; any other `RuntimeException` → WARN and `WorkUnavailableException`. `groupMembers( group )`: the group manager's group by name, its `members()` names; unknown group → empty set with a DEBUG line.

- [ ] **Step 1:** Extract `WorkDaemon`; run `mvn test -pl wikantik-main -Dtest='WorkClaimSweeperTest,WikiEngineWorkSweeperTest'` (after `mvn install -pl wikantik-work -DskipTests -q`) — PASS unchanged.
- [ ] **Step 2: Failing tests.** `WorkNotifyDispatcherTest` (mock `WorkService`, `WorkNotificationsService`; model on `WorkClaimSweeperTest`): `dispatchesOnSchedule`, `drainsAFullBatchBacklogWithinOneCycle` (returns 500, 500, 3 → three calls), `stopsAfterTenPasses`, `aFailedDispatchDoesNotSkipEmail`, `emailRunsOnlyWhenItsIntervalHasPassed`, `closeStopsTheThread`, `anUncaughtErrorLeavesItNotAlive`. `WorkNotifyWiringTest`: `defaultsAreOff`, `anIntervalOutOfRangeFallsBackTo60`, `theBaseUrlFallsBackToTheWikisAndLosesItsTrailingSlash`. `WikiWorkDirectoryTest`: `emailIsTheProfilesAddress`, `anUnknownLoginHasNoEmail`, `aBlankAddressIsNoEmail`, `groupMembersListsTheGroup`. `WikiEngineWorkDispatcherTest`: `startsWhenWorkIsWired`, `startsNothingWhenWorkIsOff`, `theGaugeReadsZeroAfterClose`, `aStartFailureDoesNotStopTheWiki`, `shutdownClosesIt`.
- [ ] **Step 3:** `mvn test -pl wikantik-main -Dtest='WorkNotifyDispatcherTest,WorkNotifyWiringTest,WikiWorkDirectoryTest,WikiEngineWorkDispatcherTest'` — FAIL.
- [ ] **Step 4:** Implement; declare the two keys; `bin/config-reference.sh --write`.
- [ ] **Step 5:** Re-run — PASS. `mvn test -pl wikantik-main -Dtest='ConfigSurfaceDriftTest,ConfigReferenceRegressionTest'` — PASS.
- [ ] **Step 6: Commit** `git add` the files above plus `docs/ConfigurationReference.md` and the regenerated wiki page file — `feat(work): notification dispatcher thread beside the claim sweeper`.

---

### Task 3.4: Email

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/EmailDigest.java`, `EmailSender.java`, `PendingEmail.java`; `wikantik-main/src/main/java/com/wikantik/work/runtime/WikiWorkMailer.java`
- Modify: `WorkNotificationStore.java`, `JdbcWorkNotifications.java` (pending and stamping), `WorkNotificationsService.java` (`sendEmail`), `WorkNotifyWiring.java`, `ini/wikantik.properties`
- Test: `EmailDigestTest.java`, `WorkEmailPostgresTest.java`, `JdbcWorkNotificationsRollbackTest.java` (extend), `wikantik-main/src/test/java/com/wikantik/work/runtime/WikiWorkMailerTest.java`, `WorkNotifyWiringTest.java` (extend)

**Interfaces — produces:**
- Keys (declared first, then `bin/config-reference.sh --write`):

| Constant | Key | Default | `Type:` |
|---|---|---|---|
| `PROP_EMAIL_ENABLED` | `wikantik.work.notify.email.enabled` | `false` | `boolean` |
| `PROP_EMAIL_DETAIL` | `wikantik.work.notify.email.detail` | `summary` | `enum(summary\|full)` (unknown → summary, WARN) |
| `PROP_EMAIL_INTERVAL` | `wikantik.work.notify.email.intervalMinutes` | `5` | `int` (1–60, else 5, WARN) |
| `PROP_EMAIL_DAILY_HOUR` | `wikantik.work.notify.email.dailyHour` | `8` | `int` (0–23, server time zone, else 8, WARN) |

  The description of `detail` says mail leaves through a relay the wiki does not control, so `summary` sends the item key, title, who did what and a link, and `full` adds comment text. `WorkNotifyWiring.settings` sets `emailEnabled` false with a WARN when the resolved base URL is blank (P3-11); `zone` is `ZoneId.systemDefault()`.
- `public record PendingEmail( String recipient, EmailMode mode, LocalDate lastDigestOn, NoticeView notice, String commentText )` (`commentText` null unless the event is `commented`/`comment_edited` and the comment is not removed).
- Store: `List< PendingEmail > pendingEmail( Instant since, int limit )` — rows with `emailed_at IS NULL AND created_at >= ?`, joined to `work_notify_pref` (no row = `each`), ordered by recipient then id, limit 2,000; the comment joined with `LEFT JOIN work_comment c ON e.type IN ('commented','comment_edited') AND c.id = CASE WHEN e.to_value ~ '^[0-9]{1,18}$' THEN e.to_value::bigint END AND c.removed_at IS NULL`. `void markEmailed( String recipient, Collection< Long > ids, Instant at, LocalDate digestOn )` — one transaction: `UPDATE work_notification SET emailed_at = ? WHERE recipient = ? AND id = ANY( ? ) AND emailed_at IS NULL`, and when `digestOn` is not null an upsert of `work_notify_pref.last_digest_on` (mode kept, `daily`).
- `final class EmailDigest { record Email( String subject, String text ) {} static Email render( List< PendingEmail > rows, EmailDetail detail, String baseUrl, Function< String, String > displayName ) }`:
  - Subject: `Work: 1 update on PLAT-12`, `Work: 3 updates on PLAT-12`, `Work: 5 updates on 3 items`; a message holding only a switched-off webhook: `Work: a webhook was switched off`. No title is ever in the subject.
  - Body: per item (ordered by its first notice), a line `PLAT-12 <title with CR/LF replaced by spaces>`, then one `- <NoticeText.line(…)>` per notice, oldest first, then `  <baseUrl>/work/item/PLAT-12`; a blank line between items. Project-level notices go last, without a link. Footer: `You get these because you watch these items, were assigned them or were mentioned.` and `Choose how often at <baseUrl>/work/inbox`.
  - `full` only: under a comment notice, the comment text, mention tokens `@[Name](user:<id>)` rewritten to `@Name`, cut at 500 characters with `…`, each line prefixed `    > `.
- `final class EmailSender` (package-private, used by `WorkNotificationsService.sendEmail( Instant now )`): returns 0 at once when `!settings.emailEnabled()`. Otherwise reads `pendingEmail( now.minus( 48h ), 2000 )` and, per recipient: `OFF` → skip; `DAILY` → skip unless the hour in `zone` is at least `dailyHour` and `lastDigestOn` is before today; `person` empty → stamp all; rows whose project the person no longer `seesProject` → stamped, never sent; `email` empty → stamp all; nothing visible → stamp; else render, `mailer.send`, then `markEmailed( …, DAILY ? today : null )`, `listener.notified( "email", "delivered" )`. An `IOException` from the mailer: WARN `Work email to {} failed; it will be retried: {}` with the **login** and the exception class only, `notified( "email", "failed" )`, rows left unstamped. A `WorkUnavailableException` for one recipient: WARN and the next recipient. Returns the number of messages sent; never throws for one recipient's failure.
- `public final class WikiWorkMailer implements WorkMailer`: `send` calls `MailUtil.sendMessage( CoreSubsystemBridge.fromLegacyEngine( engine ).properties().asProperties(), to, subject, text )` and wraps `MessagingException` in `IOException`. `WorkNotifyWiring.ports` uses it when email is enabled.

- [ ] **Step 1: Failing tests.** `EmailDigestTest`: `summaryCarriesNoCommentOrBodyText` (Review Focus 6), `fullCarriesTheCommentCutAt500`, `mentionTokensBecomeNames`, `itemsAreGroupedAndNoticesOldestFirst`, `theSubjectNeverCarriesATitle`, `aTitleWithANewlineStaysOnOneLine`, `aSwitchedOffWebhookReadsAsSuch`, `noLoginAppears`. `WorkEmailPostgresTest` (recording `WorkMailer`, fixed `Clock`): `twoEventsOnOneItemInACycleAreOneMessage`, `offSendsNothing`, `dailySendsOnceADayAfterTheHour`, `aFailingMailerLeavesRowsForTheNextCycle`, `emailDisabledNeverCallsTheMailer`, `aPersonWithNoAddressHasRowsStamped`, `aMemberRemovedBeforeTheEmailIsNotSentIt` (Review Focus 1), `rowsOlderThan48HoursAreNeverMailed`. `JdbcWorkNotificationsRollbackTest.markEmailedWithADigestIsAllOrNothing` (fail on statement 2: no row stamped, no `last_digest_on`). `WikiWorkMailerTest`: `aMessagingExceptionBecomesAnIOException` and `itPassesTheWikisMailProperties`, through a package-private constructor that takes the send function (`interface Sender { void send( Properties, String, String, String ) throws MessagingException; }`, `MailUtil::sendMessage` in production). `WorkNotifyWiringTest`: `emailNeedsABaseUrl`, `detailFallsBackToSummary`, `theDailyHourIsBounded`.
- [ ] **Step 2:** Run `mvn test -pl wikantik-work -Dtest='EmailDigestTest,WorkEmailPostgresTest,JdbcWorkNotificationsRollbackTest'` — FAIL.
- [ ] **Step 3:** Implement; declare the keys; `bin/config-reference.sh --write`.
- [ ] **Step 4:** Re-run those and `mvn test -pl wikantik-main -Dtest='WikiWorkMailerTest,WorkNotifyWiringTest,ConfigSurfaceDriftTest,ConfigReferenceRegressionTest'` (after `mvn install -pl wikantik-work -DskipTests -q`) — PASS.
- [ ] **Step 5: Commit** `git add` the files above by name — `feat(work): email notifications, batched, summary by default`.

---

### Task 3.5a: Webhooks — records, payloads and management

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/Webhook.java`, `WebhookFormat.java`, `WebhookDraft.java`, `WebhookPatch.java`, `WebhookView.java`, `WebhookCreated.java`, `WebhookEvent.java`, `WebhookPayloads.java`, `WorkWebhookStore.java`, `JdbcWorkWebhooks.java`, `WorkWebhooksService.java`
- Modify: `WorkStore.java` (`webhooks()`), `JdbcWorkStore.java`, `WorkService.java` (`webhooks()`), `WorkConflictException.java` (`WEBHOOKS_OFF = "webhooks_off"`), `WorkServiceVisibilityPostgresTest.java`
- Test: `WebhookPayloadsTest.java`, `JdbcWorkWebhooksPostgresTest.java`, `JdbcWorkWebhooksRollbackTest.java`, `WorkWebhooksServicePostgresTest.java`

**Interfaces — produces:**
- `enum WebhookFormat implements WireEnum { GENERIC("generic"), SLACK("slack") }` (field `format`).
- `public record Webhook( long id, long projectId, URI url, WebhookFormat format, String secret, List< String > eventTypes, String owner, Instant createdAt, String cursorTx, long cursorEventId, Instant failingSince, String lastError, Instant lastDeliveredAt, Instant disabledAt )`; `toString()` overridden to `Webhook[id=…, project=…, target=<scheme>://<host>/…, format=…]` (never the path or secret); `String target()` returns that masked form.
- `public record WebhookDraft( String url, String format, List< String > eventTypes )`; `public record WebhookPatch( String url, List< String > eventTypes, Boolean enabled )` (null = unchanged).
- `public record WebhookView( long id, String format, String target, List< String > eventTypes, String ownerName, Instant createdAt, Instant lastDeliveredAt, Instant failingSince, String lastError, Instant disabledAt, boolean paused )` — no URL, no secret.
- `public record WebhookCreated( WebhookView webhook, String secret )` (`secret` null for Slack); `toString()` masks the secret as `AgentWithToken` does.
- `public record WebhookEvent( WorkEvent event, String tx, String projectKey, String itemTitle, WorkStatus itemStatus )`.
- `final class WebhookPayloads`:
  - `static final Set< String > SENDABLE` = `created, edited, status, assigned, handoff, field, commented, linked, unlinked, claimed, released, claim_expired, acknowledged, change_opened, change_updated, review_requested, change_reviewed, change_merged, change_abandoned, deployment_requested, shipped, rolled_back` (what a webhook's `eventTypes` may name; empty = every item event).
  - `static String generic( WebhookEvent w, String baseUrl, Function< String, String > displayName )`: Gson, field order fixed: `{"event":{"id","type","at","actor","text"[,"from","to"]},"item":{"key","title","status","url"[,"type"]},"project":{"key"}}`. `actor` is a display name or an agent's name; `text` is `NoticeText.line( e, null, null, displayName )`; `from`/`to` only for `status` (wire values); `url` is `<baseUrl>/work/item/<KEY>` (a path when the base is blank). No note, no raw values, no body, no comment text, no login.
  - `static String slack( WebhookEvent w, String baseUrl, Function< String, String > displayName )`: `{"text":"<url|KEY> title: text"}` with title and text escaped (P3-15); without an absolute base, `KEY title: text`.
  - `static String signature( String secret, String body )`: `"sha256=" + lowercase hex( HMAC-SHA256( secret UTF-8, body UTF-8 ) )`.
  - `static String newSecret()`: 32 bytes from `SecureRandom`, hex.
- `public interface WorkWebhookStore` (all `throws SQLException`):
  - `Webhook add( Project p, URI url, WebhookFormat format, String secret, List< String > eventTypes, Actor by )` — one transaction: insert with `cursor_tx = pg_snapshot_xmin(pg_current_snapshot())`, `cursor_event_id = 0`, `owner = by.login()`; append a `settings` event (no item, note `webhook`, `from_value` host, `to_value` `added`).
  - `Optional< Webhook > byId( long id )`, `List< Webhook > forProject( long projectId )`, `List< Webhook > enabled()`.
  - `Webhook update( Webhook current, URI url, List< String > eventTypes, Boolean enabled, Actor by )` — one transaction, `owner = by.login()`; `enabled = true` on a disabled one clears `disabled_at`, `failing_since`, `last_error` and resets the cursor to the current window start (P3-9); `enabled = false` sets `disabled_at`; event `to_value` `changed`, `enabled` or `disabled`.
  - `void remove( Webhook current, Actor by )` — `DELETE` and a `removed` event, one transaction.
- `public class WorkWebhooksService` (store, scopes, people, clock, listener, audit, ports):
  - `List< WebhookView > list( Actor a, String projectKey )` — `scope` (stranger: not found); not lead and not admin → empty list (P3-10).
  - `WebhookCreated add( Actor a, String projectKey, WebhookDraft d )` — order: `scope`; `!webhooksEnabled` → `WorkConflictException( "Webhooks are turned off on this wiki.", WEBHOOKS_OFF )`; `requireWritable()`; `require( Action.MANAGE_PROJECT, null )`; URL: absolute, `https`, no user-info, ≤ 2,000 characters, else `WorkValidationException( "url", "Give an https address with no user name or password in it." )`; `ports.outbound().check( uri )`; 10 already → `WorkValidationException( "url", "A project has at most 10 webhooks." )`; format via `WebhookFormat.fromWire`; every event type in `SENDABLE`, else `WorkValidationException( "eventTypes", "<type> is not an event a webhook can send." )`; secret `newSecret()` for generic, null for Slack; then `audit.record( a.login(), "work.webhook.add", "work_webhook", projectKey + ":" + host, null )`.
  - `WebhookView update( Actor a, String projectKey, long id, WebhookPatch p )`, `void remove( Actor a, String projectKey, long id )` — same gates (a changed URL is checked again); an id that is unknown or of another project is `WorkNotFoundException`; audit `work.webhook.update` / `work.webhook.remove`.
  - `int deliver( Instant now )` — Task 3.5b.
  - `paused` in a view: the owner is not a lead (via `standing().roleIn`) and not an admin (via `scopes.person`).
- Visibility: `FACADES` += `webhooks`; `READS` already holds `list`; `WRITES` += `add`, `remove` (`update` is there); `SYSTEM` += `deliver`. The fixture adds one webhook to `PLAT` through `store.webhooks().add( … )` so `list` returns real data to `lena`; the project-scoped write test gains `add`, `update` and `remove` as `mallory`.

- [ ] **Step 1: Failing tests.** `WebhookPayloadsTest`: `theSignatureMatchesAKnownVector` (key `Jefe`, body `what do ya want for nothing?` → `sha256=5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843`, RFC 4231 case 2), `genericCarriesNoNoteNoLoginAndNoText` (assigned event to login `otto` with note `private`: payload has neither `otto` nor `private`, has `Otto Member`), `statusCarriesFromAndTo`, `slackEscapesMentionsAndAmpersands` (title `<!channel> & co` → `&lt;!channel&gt; &amp; co`), `aBlankBaseGivesAPath`, `newSecretsDiffer`. `JdbcWorkWebhooksPostgresTest`: `addStartsAtTheCurrentWindowAndWritesASettingsEvent`, `updateMakesTheUpdaterTheOwner`, `reEnablingResetsTheCursorAndClearsTheFailure`, `removeDeletesTheRowAndKeepsTheEvent`, `toStringNeverShowsThePathOrSecret`. `JdbcWorkWebhooksRollbackTest`: `aFailedSettingsEventLeavesNoWebhook` (fail on statement 2 of `add`), and the same for `update` and `remove`. `WorkWebhooksServicePostgresTest` (recording outbound whose `check` refuses `10.0.0.5` and `127.0.0.1`): `aLeadAddsAndSeesTheSecretOnce` (later `list` and `update` results contain neither the secret nor the path), `aPrivateAddressIsRefusedAtAdd` (`url`; Review Focus 4), `httpAndUserInfoAreRefused`, `anUnknownEventTypeIsRefused`, `anEleventhWebhookIsRefused`, `aMemberCannotAddAndListsNothing`, `anAgentCannotAdd`, `aStrangerGetsNotFound`, `anotherProjectsIdIsNotFound`, `anArchivedProjectIsArchived`, `webhooksOffIsRefused`, `eachChangeIsAuditedWithTheHostOnly`, `aPausedWebhookSaysSo` (owner demoted to member).
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='WebhookPayloadsTest,JdbcWorkWebhooks*,WorkWebhooksServicePostgresTest,WorkServiceVisibilityPostgresTest'` — FAIL.
- [ ] **Step 3:** Implement. Re-run — PASS. `mvn test-compile -pl wikantik-work,wikantik-rest,wikantik-main`.
- [ ] **Step 4: Commit** `git add` the files above by name — `feat(work): project webhooks, generic signed and Slack, managed by leads`.

---

### Task 3.5b: Webhooks — delivery, failure and the outbound port

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/work/runtime/WikiWorkOutbound.java`
- Modify: `WorkWebhookStore.java`, `JdbcWorkWebhooks.java`, `WorkWebhooksService.java` (`deliver`), `WorkNotifyDispatcher.java` (call `deliver` each cycle), `WorkNotifyWiring.java`, `ini/wikantik.properties`
- Test: `WorkWebhookDeliveryPostgresTest.java`, `JdbcWorkWebhooksRollbackTest.java` (extend), `wikantik-main/src/test/java/com/wikantik/work/runtime/WikiWorkOutboundTest.java`, `WorkNotifyDispatcherTest.java` (extend), `WorkNotifyWiringTest.java` (extend)

**Interfaces — produces:**
- Key: `PROP_WEBHOOKS_ENABLED` = `wikantik.work.notify.webhooks.enabled`, default `false`, `Type: boolean`; its description names the connectors' egress rule and `wikantik.connectors.egress.allowPrivate`, which also lets webhooks reach private addresses.
- Store additions:
  - `List< WebhookEvent > pending( Webhook w, int limit )`: `WHERE e.project_id = ? AND e.item_id IS NOT NULL AND (e.tx, e.id) > (?::xid8, ?) AND e.tx < pg_snapshot_xmin(pg_current_snapshot()) ORDER BY e.tx, e.id LIMIT ?`, joined to item title and status and the project key. No type filter in SQL, so the cursor passes over events the webhook does not send.
  - `boolean advance( long id, String fromTx, long fromEventId, String toTx, long toEventId, Instant deliveredAt )`: compare-and-set on the cursor pair; with `deliveredAt` not null also sets `last_delivered_at` and clears `failing_since` and `last_error`.
  - `void recordFailure( long id, Instant at, String error )`: `failing_since = COALESCE( failing_since, ? )`, `last_error = ?`.
  - `boolean disableForFailure( Webhook w, Instant at )`: one transaction, `UPDATE work_webhook SET disabled_at = ? WHERE id = ? AND disabled_at IS NULL`, and only when a row changed a `settings` event attributed to the owner (kind person), note `webhook`, `from_value` host, `to_value` `failed`. Returns whether it switched it off.
- `WorkWebhooksService.int deliver( Instant now )`: 0 at once when webhooks are off. Per `enabled()` webhook: resolve the owner (`scopes.person`); not a lead and not an admin → skip (paused, nothing changes); a lookup failure → WARN, skip. Read `pending( w, 100 )`; for each event in that order: not sendable under its `eventTypes` → consumed; else build the body, headers `Content-Type: application/json`, `X-Wikantik-Event: <type>`, `X-Wikantik-Delivery: <event id>`, and for generic `X-Wikantik-Signature: <signature>`; `post`; 2xx → consumed, `notified( "webhook", "delivered" )`; anything else, or an `IOException` → stop. Then `advance` to the last consumed (with `deliveredAt = now` when at least one was posted and the last attempt succeeded); on a stop, `recordFailure( id, now, error )` where `error` is `HTTP <status>`, the `Refused` message (host only), or the exception's simple class name, and `notified( "webhook", "failed" )`; when `failing_since` (after the update) is at least 24 hours before `now`, `disableForFailure`. WARN per failure with the webhook id and host only. Returns events posted.
- `public final class WikiWorkOutbound implements WorkOutbound`: `check( uri )` calls `EgressGuard.check`, mapping `EgressBlockedException` to `WorkValidationException( "url", e.getMessage() )`. `post`: `EgressGuard.check` first; `HttpClient` with `followRedirects( NEVER )`, connect and request timeouts 10 s, body discarded; on 307 or 308 the next hop is `EgressGuard.followsSafely( current, location )` and the same body is posted again, at most `EgressGuard.MAX_REDIRECTS` hops; any other 3xx is returned as the status (a failure); a blocked hop throws `WorkOutbound.Refused( e.getMessage() )`. A package-private constructor takes a `Hop` function `( URI, String body, Map headers ) → HopResult( int status, String location )` so tests need no network.

- [ ] **Step 1: Failing tests.** `WorkWebhookDeliveryPostgresTest` (recording outbound with scripted results, fixed `Clock`): `eventsArriveInOrderWithAValidSignature`, `aFailureStopsAndTheRetryResumesInOrder` (Review Focus 4's order clause), `eventsNotSelectedAreSkippedAndPassed`, `projectLevelEventsAreNeverSent`, `twentyFourHoursOfFailureSwitchesItOffAndNotifiesLeadsOnce` (after `disableForFailure`, a `dispatch` gives each lead one `webhook_disabled` row; a second `deliver` writes nothing more), `aSuccessResetsTheFailureClock`, `aWebhookWhoseOwnerIsNoLongerALeadSendsNothing` (Review Focus 5; cursor unmoved; after another lead's `update`, deliveries resume), `aRefusedRedirectCountsAsAFailure`, `webhooksOffDeliverNothing`, `theLastErrorNeverHoldsThePath`. `JdbcWorkWebhooksRollbackTest.aFailedSwitchOffEventLeavesItOn`. `WikiWorkOutboundTest`: `loopbackIsRefusedAtCheck` (`https://127.0.0.1/x`), `aPrivateRangeIsRefusedAtCheck` (`https://10.0.0.5/x`), `aPublicAddressPasses` (`https://93.184.215.14/x`, an IP literal, so no DNS), `aRedirectToLoopbackIsRefusedAfterOneHop` (stub hop answers 307 `Location: https://127.0.0.1/`: `Refused`, one hop sent), `aBlockedFirstHopSendsNothing`, `a302IsReturnedAsAFailureNotFollowed`, `redirectsStopAtTheLimit`. `WorkNotifyDispatcherTest.deliversWebhooksEachCycle`, `aFailedDeliveryDoesNotSkipEmail`. `WorkNotifyWiringTest.webhooksDefaultOff`.
- [ ] **Step 2:** Run the `wikantik-work` and `wikantik-main` test classes above — FAIL.
- [ ] **Step 3:** Implement; declare the key; `bin/config-reference.sh --write`.
- [ ] **Step 4:** Re-run, plus `ConfigSurfaceDriftTest`, `ConfigReferenceRegressionTest` — PASS.
- [ ] **Step 5: Commit** `git add` the files above by name — `feat(work): deliver webhooks in order through the egress guard; switch off after a day of failures`.

---

### Task 3.6a: REST

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/WorkInboxResource.java`, `WorkWebhookRoutes.java`
- Modify: `WorkProjectResource.java` (`WorkWebhookRoutes.register( r )` in `routes`), `WorkMeResource.java`, `wikantik-war/src/main/webapp/WEB-INF/web.xml`
- Test: `WorkInboxResourceTest.java`, `WorkWebhookRoutesTest.java`, `WorkMeResourceTest.java` (setUp also mocks `svc.notifications()`; new case)

**Interfaces — produces:**
- `WorkInboxResource` (servlet `WorkInboxResource`, mapped to `/api/work/inbox`, `/api/work/inbox/*`, `/api/work/watch/*`, `/api/work/notify-prefs`):

| Route | Body | Answer |
|---|---|---|
| `GET inbox?before=<id>&limit=<n>` | | 200 `{"notices":[{id, reason, createdAt, readAt, projectKey, itemKey, itemTitle, event}], "unread": n}`; `event` through `toTree( WorkEvent, viewer, lookup )`, so people are display names |
| `GET inbox/unread` | | 200 `{"unread": n}` |
| `POST inbox/read/{key}` | | 204; unseen item 404 |
| `POST inbox/read-all` | | 204 |
| `GET watch/{key}` | | 200 `{"state":"watching"\|"muted"\|"none"}`; unseen 404 |
| `PUT watch/{key}` | `{"state":…}` | 200 `{"state":…}`; bad value 400 field `state`; agent 403; archived 409 `archived` |
| `GET notify-prefs` | | 200 `{"email":"each"\|"daily"\|"off","emailAvailable":b,"webhooksAvailable":b}` |
| `PUT notify-prefs` | `{"email":…}` | 200 same shape; bad value 400 field `email`; agent 403 |

- `WorkWebhookRoutes` (final, static `register( SegmentRouteTable.Builder< Handler > r )`, same package so it can use the protected `route`, `Handler` and `Call`):

| Route | Body | Answer |
|---|---|---|
| `GET projects/{key}/webhooks` | | 200 `[WebhookView…]` |
| `POST projects/{key}/webhooks` | `{"url","format","eventTypes"}` | 201 `{"webhook":WebhookView,"secret":"…"\|null}` |
| `PATCH projects/{key}/webhooks/{id}` | `{"url"?,"eventTypes"?,"enabled"?}` | 200 `WebhookView` |
| `DELETE projects/{key}/webhooks/{id}` | | 204 |

  A non-numeric `{id}` is 404. Errors through the base class: not found 404, denied 403, `archived`/`webhooks_off` 409, validation 400 naming `url`, `format` or `eventTypes`.
- `GET /api/work/me` gains `"notify":{"emailAvailable":b,"webhooksAvailable":b}` from `svc.notifications().preference( actor )`.

- [ ] **Step 1: Failing tests.** `WorkInboxResourceTest` (mocked service): one per route row, `aStrangersReadOfAnItemIs404`, `noticesNameDisplayNamesNotLogins`, `aBadStateIs400NamingState`. `WorkWebhookRoutesTest` (through `WorkProjectResource`): `listReturnsViewsWithoutUrlOrSecret` (JSON has no `url` and no `secret` key), `addReturnsTheSecretOnce201`, `aStrangerGets404OnAllFour`, `aMemberGets403OnAdd`, `aPrivateUrlIs400NamingUrl`, `webhooksOffIs409`, `aNonNumericIdIs404`. `WorkMeResourceTest.meCarriesTheNotifyFlags`.
- [ ] **Step 2:** `mvn test -pl wikantik-rest -Dtest='WorkInboxResourceTest,WorkWebhookRoutesTest,WorkMeResourceTest'` (after `mvn install -pl wikantik-work,wikantik-main -DskipTests -q`) — FAIL.
- [ ] **Step 3:** Implement; declare the servlet and its four mappings in `web.xml` beside `WorkMeResource`.
- [ ] **Step 4:** Re-run — PASS. `mvn test -pl wikantik-rest -Dtest='WorkAccessArchTest,WorkProjectResourceTest'` — PASS.
- [ ] **Step 5: Commit** `git add wikantik-rest/src/main/java/com/wikantik/rest/{WorkInboxResource,WorkWebhookRoutes,WorkProjectResource,WorkMeResource}.java wikantik-rest/src/test/java/com/wikantik/rest/{WorkInboxResourceTest,WorkWebhookRoutesTest,WorkMeResourceTest}.java wikantik-war/src/main/webapp/WEB-INF/web.xml` — `feat(work): inbox, watch, preference and webhook routes`.

---

### Task 3.6b: UI

**Files:**
- Create (each with a `.test.jsx`/`.test.js`): `wikantik-frontend/src/components/work/WorkInbox.jsx`, `WorkNotifyPrefs.jsx`, `WorkWatchButton.jsx`, `WorkWebhooks.jsx`, `wikantik-frontend/src/hooks/useWorkUnread.js`
- Modify: `wikantik-frontend/src/main.jsx` (route `inbox` under `/work`, before `:project`), `api/work.js`, `components/Sidebar.jsx` (+ `Sidebar.test.jsx`), `components/work/WorkHome.jsx` (+test), `components/work/WorkItemView.jsx` (+test), `components/work/WorkSettings.jsx` (+test), `components/work/eventText.js` (+ `eventText.test.js`), `styles/work.css`

**Interfaces — produces:**
- `api/work.js`: `getInbox( params )`, `getUnread()`, `markItemRead( key )`, `markAllRead()`, `getWatch( key )`, `setWatch( key, state )`, `getNotifyPrefs()`, `setNotifyPrefs( email )`, `listWebhooks( projectKey )`, `addWebhook( projectKey, body )`, `updateWebhook( projectKey, id, body )`, `removeWebhook( projectKey, id )` over the routes of Task 3.6a.
- `useWorkUnread()`: `{ unread }`; polls `getUnread()` every 60 s only while `useWorkNavVisible()` is true and `document.visibilityState === 'visible'`; a failed poll keeps the last value and `console.warn`s.
- `Sidebar.jsx`: the Work link shows `<span className="work-unread-badge" aria-label="N unread notifications">N</span>` when `unread > 0`.
- `WorkHome.jsx`: an `Inbox` link (with the count) beside "Assignments and claims".
- `WorkInbox.jsx` (`/work/inbox`): `WorkNotifyPrefs` at the top; the notices newest first, unread ones marked, each with its reason label (`Mentioned`, `Assigned`, `Needs acknowledging`, `Claim lapsed`, `Webhook switched off`, `Watching`), `eventSentence( event )`, the item key and title linking to `/work/item/KEY`; "Mark all read"; "Load more" with `before` = the last id.
- `WorkNotifyPrefs.jsx`: three radio choices (Each update, batched; Once a day; Off); when `emailAvailable` is false, only the line "Email notifications are off on this wiki."
- `WorkWatchButton.jsx`: shows Watch / Watching / Muted; a menu sets `watching`, `muted` or `none`; hidden for an agent session (not reachable in the SPA) and on archived projects disabled.
- `WorkItemView.jsx`: renders `WorkWatchButton`; after the item loads, calls `markItemRead( key )` once (failure: `console.warn`, nothing shown).
- `WorkWebhooks.jsx`, in `WorkSettings.jsx` only for a lead or admin and only when `me.notify.webhooksAvailable`: the list (format, target, owner, last delivery, failing since and last error, switched off, paused with "Its owner is no longer a lead; save it to take it over."), an add form (https URL, format, event-type checkboxes from a constant matching `WebhookPayloads.SENDABLE`), the one-time secret panel after adding ("Copy this secret now. It is not shown again."), turn on/off, remove through `WorkConfirm`.
- `eventText.js` `settingsText` case `webhook`: `added` → `added a webhook to <host>`, `changed` → `changed the webhook to <host>`, `removed` → `removed the webhook to <host>`, `disabled`/`enabled` → `turned off`/`turned on the webhook to <host>`, `failed` → `{ text: 'The webhook to <host> was switched off after failing for 24 hours.', standalone: true }`.
- CSS uses only tokens already defined in `src/styles` (no new `var(--x)` without a definition).

- [ ] **Step 1: Failing vitest.** `useWorkUnread.test.js`: `pollsEverySixtySecondsWhileVisible`, `doesNotPollWhenWorkIsHidden`. `Sidebar.test.jsx`: `theWorkEntryShowsTheUnreadCount`. `WorkInbox.test.jsx`: `listsNoticesWithReasonAndLink`, `markAllReadClearsTheBadge`, `loadMoreAsksForOlder`. `WorkNotifyPrefs.test.jsx`: `theChoiceIsHiddenWhenEmailIsOff`, `choosingDailySavesIt`. `WorkWatchButton.test.jsx`: `mutingCallsSetWatch`. `WorkItemView.test.jsx`: `openingAnItemMarksItsNoticesRead`. `WorkWebhooks.test.jsx`: `theSecretIsShownOnceAfterAdding`, `aMemberSeesNoWebhookSection`, `hiddenWhenWebhooksAreOff`, `aPausedWebhookSaysWhy`. `eventText.test.js`: one per webhook action, and `eventSentence` of a `failed` one does not name the actor.
- [ ] **Step 2:** `cd wikantik-frontend && npx vitest run src/hooks/useWorkUnread.test.js src/components/Sidebar.test.jsx src/components/work/` — FAIL.
- [ ] **Step 3:** Implement. Re-run — PASS. `npm run lint` — clean.
- [ ] **Step 4: Commit** `git add` each file above by name — `feat(work-ui): inbox, unread badge, watching, email choice and webhooks`.

---

### Task 3.7: IT, gate and docs

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/WorkNotifyIT.java`
- Modify: `wikantik-it-tests/wikantik-it-test-rest/src/test/config/it-rest-cascade.properties` (`wikantik.work.notify.intervalSeconds = 2`, with a comment saying why), the docs below

- [ ] **Step 1: IT** `WorkNotifyIT.anAssigneeHearsAndLosesItWithTheirAccess`: admin creates project `NOTE`; `WorkIt.account` makes `notelena` (lead) and `noteotto` (member); lena creates a task assigned to Otto by display name; `WorkIt.await( "inbox", otto, "/api/work/inbox", body -> body.contains( "NOTE-1" ) )`; the notice's `reason` is `assigned` and its event names Lena by display name; admin removes Otto; Otto's `GET /api/work/inbox` has no notices and `unread` 0; Otto's `GET /api/work/projects/NOTE/webhooks` is 404. Second method `aLeadManagesAWebhookAndNeverSeesTheSecretAgain`: with `wikantik.work.notify.webhooks.enabled` off in this module, `POST …/webhooks` is 409 `webhooks_off` (the delivery path is covered by unit tests; the IT wiki cannot reach a public URL).
- [ ] **Step 2:** `bin/run-tests.sh --module rest` through `bin/agent-build.sh start notify-it -- bin/run-tests.sh --module rest`; poll `status`/`wait` until it terminates — SUCCESS.
- [ ] **Step 3: Docs.**
  - `wikantik-work/CLAUDE.md`: a "Notifications" section: the event log is the outbox; cursors are `(tx, event_id)` over `tx < pg_snapshot_xmin(…)` and must never become a bare event id (P3-1); `Recipients` is the one rule table and `AutoWatch` the one auto-watch table; standing is `WorkStanding.seesProject` at dispatch, at email time and (through the visible-project set) at read; agents are never recipients; preferences write no event; a comment event names its comment in `to_value`, and its mentions are "current rows minus those already notified"; webhooks act for an owner lead, go through `WorkOutbound` (`EgressGuard` on every hop), never return URL or secret after creation, and a new item event type must be added to `WebhookPayloads.SENDABLE` and `NoticeText`; `wikantik_work_notifications_total{channel,outcome}` and `wikantik_work_dispatcher_alive` carry no project label. Add V086 to the migrations line.
  - `docs/developer/ProjectReference.md`: the work-management status block gains "Part 3, notifications: inbox, email, webhooks (V086)" with the dispatcher, the two gauges and the facades `notifications()` and `webhooks()`.
  - `docs/admin/WikantikOperations.md`: a new section 1.17 "Work notifications: email and webhooks": the keys and defaults; set `wikantik.work.notify.baseUrl` or `wikantik.baseURL` before turning email on; configure mail first (`SendingEmailFromTheWiki.md`), then `wikantik.work.notify.email.enabled=true`; `detail=summary` is the default because the relay is outside the wiki's control, and `full` puts comment text in mail; webhooks: `wikantik.work.notify.webhooks.enabled=true`, https only, private addresses refused unless `wikantik.connectors.egress.allowPrivate` is set (which also opens connectors); one node sends; `wikantik_work_dispatcher_alive` 0 means notifications stopped; a transaction left open delays dispatch without losing anything (find it in `pg_stat_activity`, `state = 'idle in transaction'`).
  - `docs/admin/SendingEmailFromTheWiki.md`: a paragraph that work notifications use the same mail session and their own switch.
  - `docs/admin/Security.md`: webhooks as a new egress path (egress guard on every hop, HMAC-SHA256 signature header, URL and secret never returned, deliveries pause when the owning lead loses the role); email carries title and action only by default; inbox and email re-check standing.
  - `docs/superpowers/specs/2026-11-15-work-management-design.md`: the non-goal "Email or push notifications" becomes "Push notifications to agents" with a pointer to the 2026-11-21 design, section 3.
  - `CHANGELOG.md` `[Unreleased]`: one entry for notifications (inbox, watching, batched email, webhooks), naming the three keys an operator turns on.
  - `docs/ConfigurationReference.md` and the wiki page `WikantikConfigurationReference` are regenerated (Tasks 3.3c, 3.4, 3.5b), never hand-edited.
- [ ] **Step 4: Gate.** `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`; poll `bin/agent-build.sh status gate` / `wait gate 540` until SUCCESS. `mvn pmd:check -Pcomplexity-gate -pl wikantik-work,wikantik-main,wikantik-rest` — no new violation.
- [ ] **Step 5: Commit** `git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/WorkNotifyIT.java wikantik-it-tests/wikantik-it-test-rest/src/test/config/it-rest-cascade.properties wikantik-work/CLAUDE.md docs/developer/ProjectReference.md docs/admin/WikantikOperations.md docs/admin/SendingEmailFromTheWiki.md docs/admin/Security.md docs/superpowers/specs/2026-11-15-work-management-design.md CHANGELOG.md` — `docs(work): notifications`.

## Deviations from the outline

1. **Cursor shape.** Outline: `work_notify_cursor( name, event_id )` and `work_webhook.cursor_event_id`. Now: `work_event.tx xid8` plus `(tx, event_id)` cursors (`work_notify_cursor.tx`, `work_webhook.cursor_tx`). An event-id cursor skips events committed out of id order (P3-1).
2. **`work_notification.item_id` is nullable and the reasons gain `webhook_disabled`.** Outline: item required, a switched-off webhook notified as `acknowledge`. A switched-off webhook is a project-level event with no item, and "acknowledge" means something else in the UI (P3-7).
3. **`work_webhook.owner`** replaces `created_by` (it changes hands on update, P3-8); the table also gains `created_at`, `last_delivered_at` and a CHECK that a generic webhook has a secret.
4. **Settings and ports.** Outline: modify `WorkSettings`. Now: `NotifySettings` and `WorkNotifyPorts`, passed through a new `WorkService` constructor, so the 30 existing construction sites and `WorkSettings` stay as they are.
5. **`assigned` reaches the new assignee only; watchers hear of hand-offs.** Outline: watchers on `assigned` too. The design lists watchers on "hand-offs", and the assignee on assignment.
6. **`comment_edited` notifies newly mentioned people.** Outline: every other type reaches nobody (P3-2).
7. **The standing check** is `WorkStanding.seesProject` (viewer or above, or a wiki admin), not "reporter-without-ownership" (the reporter role does not exist until Part 8).
8. **Watching API.** Outline: `watch`/`mute`/`unwatch` service methods and `/api/work/watch/*`. Now: `watchState` and `setWatch( actor, key, state )`, `GET|PUT /api/work/watch/{key}`.
9. **Webhook routes** live in `WorkWebhookRoutes`, registered by `WorkProjectResource`, not a `WorkWebhookResource` servlet: `/api/work/projects/*` is already that servlet's mapping. Update and remove are `PATCH|DELETE /api/work/projects/{key}/webhooks/{id}`.
10. **Generic payload.** Outline: `event:{id,type,at,actor,from,to,note}`. Now: `event:{id,type,at,actor,text}` with `from`/`to` for `status` only: raw values carry logins (assignments) and ids, and a note is typed text.
11. **`WorkOutbound`** gains `check( URI )` for the save-time refusal, and `post` follows only 307/308 (re-posting the body); other redirects are failures.
12. **New key `wikantik.work.notify.intervalSeconds`** (P3-17), and email covers 48 hours of rows only (P3-11).
13. **The UI flags** come from `GET /api/work/me` (`notify`), not `/api/capabilities`.
14. **`WorkDaemon`** is extracted from `WorkClaimSweeper` so the dispatcher does not copy its thread loop.
15. **Task 3.3 is split** into 3.3a (store transaction), 3.3b (service and inbox), 3.3c (thread and wiring); Task 3.5 into 3.5a (management) and 3.5b (delivery); Task 3.6 into 3.6a (REST) and 3.6b (UI).
