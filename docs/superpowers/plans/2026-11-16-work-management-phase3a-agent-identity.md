# Work Management Phase 3a — Agent Identity and Authority — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every agent has a sponsor whose current standing caps what the agent may read and do, leads can register agents, and agent credentials live in their own store with no link to personal API keys.

**Architecture:** `wikantik-work` gains a credential store, a sponsor on `work_agent`, and one resolver (`WorkStanding`) that turns an actor into an effective role and a set of visible projects. An agent `Actor` carries its sponsor as a resolved person; nothing about the sponsor is stored except the login. The servlet filters in `wikantik-rest` and `wikantik-mcp-core` recognise an agent token by prefix and ask the work module to verify it through a small seam in `wikantik-main`. The phase 2 key-binding code and its guards are deleted last.

**Tech Stack:** Java 25, PostgreSQL 18 (`Jdbc` primitive, `PostgresTestDb`), JUnit 5, ArchUnit, Mockito, React 19 + vitest, Cargo/Selenide ITs.

**Spec:** `docs/superpowers/specs/2026-11-16-work-agent-identity-design.md` (parent: `docs/superpowers/specs/2026-11-15-work-management-design.md`).

**Code map (read before any task):** `.superpowers/sdd/phase3a-code-map.md` — current signatures, line numbers, SQL, tests that pin today's behaviour.

## Global Constraints

- Root `CLAUDE.md`, `wikantik-work/CLAUDE.md`, `wikantik-work-mcp/CLAUDE.md` bind every task. TDD: a failing test first, the failing run captured.
- `wikantik-work` depends on neither `wikantik-api` nor `wikantik-main`. Every caller outside the module goes through `WorkService` and its facades; only `com.wikantik.work*` and `WorkWiring` may touch a store (`WorkAccessArchTest`, `WorkMcpAccessArchTest`).
- Database access only through `com.wikantik.jdbc.Jdbc`. A multi-statement write gets a rollback test (`FaultInjectingDataSource`). Tests use `PostgresTestDb`; never hand-write DDL.
- Migrations are DDL-only, idempotent, use `:app_user` for grants, and applied ones (V074–V078) are never edited. Next numbers: **V079**, then **V080**.
- A non-member gets `WorkNotFoundException` (404), never 403, with the same body a stranger gets. This holds for an agent whose sponsor lacks standing.
- An agent actor is never admin and has no groups (`Actor` compact constructor). An agent never gains access from a person: the sponsor only ever removes.
- A failed directory or store lookup is a `WorkUnavailableException` (503 on REST, "Work is unavailable right now." on MCP). It is never read as "no access", never read as "access", never cached, and changes nothing.
- Every ending of a claim writes exactly one event in the same transaction: `released` with `from_value` `agent:<holder id>`, `to_value` the resulting status, `note` the reason. The event log is append-only.
- Reserved release notes after this plan: `moved`, `reassigned`, `access removed`, `agent retired`, `project archived`, `sponsor access removed`.
- No swallowed exceptions; log values are ids and counts, never tokens, row text or caller-supplied path text. No PMD suppressions: split a class that crosses a limit.
- New `wikantik.*` keys are declared in `ini/wikantik.properties` first (description, `Type:`, default), then `bin/config-reference.sh --write`.
- Exact strings (use verbatim):
  - Token prefix: `wka_`
  - `Work is unavailable right now.` (MCP), `{"error":"Service temporarily unavailable"}` (REST 503), `{"error":"Invalid API key"}` (REST 401)
  - Registration refused: `Only a project lead or a wiki administrator may register an agent.`
  - Takeover refused: `This agent has a sponsor. Ask them to release it, or ask a wiki administrator.`
  - Management refused: `Only this agent's sponsor or a wiki administrator may do that.`
- Commits: stage files by name, 1–3 line messages. Never push. Long Maven runs through `bin/agent-build.sh`. Before running an IT module alone: `mvn install -DskipTests -T 1C`.

## Review Focus

1. **A sponsor with standing only through a group.** A lead who is a member via a `group` row sponsors an agent; removing the group row, or the person leaving the group, must end the agent's access and (within a sweep) its claims. Tests in Tasks 3 and 7.
2. **Directory outage during an agent request.** `person(login)` throws: REST 503, MCP "unavailable", claim not taken, nothing released, nothing cached. Tests in Tasks 2, 5 and 7.
3. **Sponsor removal racing a claim.** Both orders: the claim lands first and is released by the removal, or the removal lands first and the claim is refused. Test in Task 7 using the `CommitGate` pattern.
4. **A personal API key that starts with `wka_`-like text, and an agent token sent to the wrong surface.** An agent token on `/knowledge-mcp`, `/wikantik-admin-mcp`, `/tools/*` or a non-work `/api/*` route is refused and never becomes a session; a `wkk_` key is never looked up in the credential store. Tests in Task 5.
5. **Takeover of an agent holding claims in projects the new sponsor cannot see.** The claims are released in the takeover transaction with `sponsor access removed`; the member rows remain. Test in Task 7.

---

### Task 1: Schema V079 — sponsor, sponsor history, credentials

**Files:**
- Create: `bin/db/migrations/V079__work_agent_sponsor_and_credentials.sql`
- Test: `wikantik-work/src/test/java/com/wikantik/work/WorkAgentsSchemaTest.java` (extend)

**Interfaces:**
- Produces (tables later tasks rely on):
  - `work_agent.sponsor TEXT` (nullable); `work_agent.api_key_id` becomes nullable (`ALTER COLUMN api_key_id DROP NOT NULL`).
  - `work_agent_sponsor_history (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, agent_id BIGINT NOT NULL REFERENCES work_agent (id), sponsor TEXT, since TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(), changed_by TEXT NOT NULL)`, index `(agent_id, since DESC)`. Grants: `SELECT, INSERT` only.
  - `work_agent_credential (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, agent_id BIGINT NOT NULL REFERENCES work_agent (id), token_hash TEXT NOT NULL UNIQUE, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(), created_by TEXT NOT NULL, last_used_at TIMESTAMP WITH TIME ZONE, revoked_at TIMESTAMP WITH TIME ZONE)`, unique index `idx_work_agent_credential_live ON (agent_id) WHERE revoked_at IS NULL`. Grants: `SELECT, INSERT, UPDATE`.
  - Index `idx_work_agent_sponsor ON work_agent (sponsor) WHERE sponsor IS NOT NULL`.

- [ ] **Step 1: Failing schema tests** in `WorkAgentsSchemaTest`: `sponsorColumnIsNullableText`, `apiKeyIdIsNullable`, `aSecondLiveCredentialForOneAgentIsRefused` (second insert with `revoked_at IS NULL` → SQLState `23505`), `aRevokedCredentialDoesNotBlockANewOne`, `sponsorHistoryCannotBeUpdatedOrDeletedByTheAppRole` (follow the existing append-only test for `work_event`; use the least-privilege role helper the existing schema tests use).
- [ ] **Step 2:** Run `mvn test -pl wikantik-work -Dtest=WorkAgentsSchemaTest`; expect failures on missing columns/tables.
- [ ] **Step 3:** Write V079 (idempotent: `ADD COLUMN IF NOT EXISTS`, `CREATE TABLE IF NOT EXISTS`, `CREATE [UNIQUE] INDEX IF NOT EXISTS`).
- [ ] **Step 4:** Tests pass; re-apply check: `bin/db/migrate.sh` twice against the local database is a no-op the second time.
- [ ] **Step 5:** Commit `feat(work): V079 — agent sponsor, sponsor history, agent credentials`.

---

### Task 2: The sponsor travels with the agent actor

**Files:**
- Modify: `wikantik-work/.../WorkDirectory.java`, `Actor.java`, `Agent.java`, `WorkRegistryStore.java`, `JdbcWorkRegistry.java`, `WorkRegistryService.java` (`registerAgent`, `actorForApiKey`), `WorkUnavailableException.java` (reuse)
- Modify: `wikantik-main/.../work/runtime/WikiWorkDirectory.java`
- Test: `ActorTest`, `JdbcWorkRegistryPostgresTest`, `JdbcWorkRegistryRollbackTest`, `WorkAgentIdentityPostgresTest`, `WikiWorkDirectoryTest`, shared fixtures `WorkAgentFixtures`

**Interfaces:**
- Produces:
  - `WorkDirectory`: `record Person(String login, Set<String> groups, boolean admin)`; `default Optional<Person> person(String login)` — empty when no such account; throws `WorkUnavailableException` when the lookup fails. The default composes `exists`/`groupsOf` with `admin=false` (test fakes override it).
  - `Actor`: new last component `Actor sponsor` (null for a person). `static Actor agent(long agentId, String name, Actor sponsor)`; the two-argument `agent(id, name)` is removed. Compact constructor: `sponsor` must be null for HUMAN and, when present, must be HUMAN (else `IllegalArgumentException`).
  - `Agent`: new component `String sponsor` after `runtime` (nullable).
  - `WorkRegistryStore`: `void setSponsor(long agentId, String sponsorOrNull, String changedBy) throws SQLException` — one transaction: `UPDATE work_agent SET sponsor` + `INSERT work_agent_sponsor_history`. `registerAgent(...)` gains a `String sponsor` parameter and writes the first history row in its transaction. `List<SponsorChange> sponsorHistory(long agentId)` with `record SponsorChange(String sponsor, Instant since, String changedBy)`.
  - `WorkRegistryService`: package-private `Actor agentActor(Agent agent)` — returns null when the agent is retired, has no sponsor, or `directory.person(sponsor)` is empty; otherwise `Actor.agent(id, name, Actor.human(p.login(), p.groups(), p.admin()))`. Propagates `WorkUnavailableException`.
- Behaviour change in this task: `registerAgent` records `sponsor = actor.login()` (still admin-only here); `actorForApiKey` returns `agentActor(agent)` for a bound active agent, so a sponsorless agent is no actor.

- [ ] **Step 1: Failing tests.**
  - `ActorTest`: `anAgentCarriesItsSponsor`, `aPersonNeverHasASponsor` (throws), `aSponsorMustBeAPerson` (throws), existing agent-never-admin tests keep passing with the new factory.
  - `JdbcWorkRegistryPostgresTest`: `registerRecordsTheSponsorAndTheFirstHistoryRow`, `setSponsorWritesHistoryInOrder` (null then a login → two more rows, newest first), `agentRowCarriesSponsor`.
  - `JdbcWorkRegistryRollbackTest`: `setSponsorRollsBackWhenTheHistoryInsertFails`.
  - `WorkAgentIdentityPostgresTest`: `anAgentWithNoSponsorIsNoActor`, `anAgentWhoseSponsorAccountIsGoneIsNoActor`, `aDirectoryOutageIsUnavailableNotNoActor` (fake directory throws → `WorkUnavailableException`), `theAgentActorCarriesTheSponsorsGroupsAndAdminFlag`.
  - `WikiWorkDirectoryTest`: `personIsEmptyForAnUnknownLogin`, `personThrowsUnavailableWhenTheUserDatabaseFails`, `personThrowsUnavailableWhenGroupLookupFails`, and the equivalence contract `personAdminFlagMatchesTheSessionAdminFlag` — for an Admin-group user, a plain user and a user in a non-admin group, `person(login).admin()` equals what `WikiWorkActors.actorFor(engine, session)` yields for that user's session.
- [ ] **Step 2:** Run the five classes; capture failures.
- [ ] **Step 3: Implement.** `WikiWorkDirectory.person` must decide `admin` from the login alone with the same outcome as the session rule (`isPermitted(session, new AllPermission(appName))`, `WikiWorkActors:54`): resolve the principals that login would hold (its roles and groups) and ask the authorization layer whether they are granted `AllPermission`. Do not shortcut to "is in a group named Admin" unless the equivalence test proves it for policy-granted roles too. Any `RuntimeException` from the user database, group manager or policy lookup → `WorkUnavailableException` (pattern: `ownerExists`, `WikiWorkDirectory:82`). `exists` keeps its current behaviour for `WorkPeople`.
- [ ] **Step 4:** Update every caller of `Actor.agent(id, name)` and `new Agent(...)` (main and test code; `mvn test-compile -pl wikantik-work,wikantik-main,wikantik-rest,wikantik-work-mcp -am -q`). Fixtures register agents with a sponsor that the fake directory knows.
- [ ] **Step 5:** `mvn test -pl wikantik-work` and the touched classes in main/rest/work-mcp pass. Commit `feat(work): an agent actor carries its sponsor, resolved live through the directory`.

---

### Task 3: `WorkStanding` — the sponsor ceiling on roles and visibility

**Files:**
- Create: `wikantik-work/.../WorkStanding.java`
- Modify: `WorkScopes.java` (`scope`, `itemScope*`, `isMember`), `WorkClaimsService.java` (`claimableProjects`, `myClaims`), `WorkService.java` (`listProjectsWithRoles`, `roleIn`, `assignmentLog`, every `visibleProjects` caller), `WorkRegistryService.java` (`canSeeRegistry`)
- Test: create `WorkStandingPostgresTest`; extend `WorkServiceVisibilityPostgresTest`, `WorkPolicyTest`; create arch rule in `wikantik-work/src/test/.../WorkStandingArchTest.java`

**Interfaces:**
- Consumes: `Actor.sponsor()`; `WorkCatalogStore.roleFor(long projectId, Actor)`, `visibleProjects(Actor)` (unchanged SQL: for an AGENT actor they read the agent's own member rows).
- Produces: `final class WorkStanding` (package-private, constructed with the `WorkStore`):
  - `WorkRole roleIn(long projectId, Actor actor) throws SQLException` — a person: `store.roleFor`. An agent: null if `actor.sponsor()` is null; else the lower of `store.roleFor(projectId, actor)` and the sponsor's role, where the sponsor's role is `LEAD` when `sponsor.admin()` and `store.roleFor(projectId, sponsor)` otherwise; null if either is null.
  - `List<Project> visibleProjects(Actor actor) throws SQLException` — a person: `store.visibleProjects`. An agent: its own visible projects, kept only where the sponsor is admin or the project is in `store.visibleProjects(sponsor)`.
- After this task nothing in `com.wikantik.work` except `WorkStanding` calls `roleFor` or `visibleProjects` on a store. `WorkPolicy.may` keeps its signature; the role passed in is now always the effective role.

- [ ] **Step 1: Failing tests.**
  - `WorkStandingPostgresTest`, table-driven over agent role × sponsor standing: agent `member` with sponsor `lead`/`member` → `MEMBER`; sponsor `viewer` → `VIEWER`; sponsor not a member → null; sponsor admin and not a member → `MEMBER`; agent `viewer` with sponsor `lead` → `VIEWER`; sponsor a member only through a `group` row → `MEMBER`, and null once that row is removed; agent with null sponsor → null. `visibleProjectsIsTheIntersection` (agent in P1, P2; sponsor in P2, P3 → P2 only; admin sponsor → P1, P2).
  - `WorkServiceVisibilityPostgresTest`: add a stranger variant "an agent that is a member of the project but whose sponsor is not" to the existing every-public-method sweep: every read and write answers exactly what a stranger gets (same exception type and message).
  - `WorkStandingArchTest`: no class in `com.wikantik.work` other than `WorkStanding` and the `Jdbc*` stores calls `WorkCatalogStore.roleFor` or `visibleProjects`.
- [ ] **Step 2:** Run; capture failures.
- [ ] **Step 3:** Implement `WorkStanding`; route every caller through it (`WorkScopes` holds one instance and exposes it to the services in the package).
- [ ] **Step 4:** `mvn test -pl wikantik-work` green. Commit `feat(work): one resolver applies the sponsor ceiling to roles and visibility`.

---

### Task 4: Agent credentials in the work module

**Files:**
- Create: `wikantik-work/.../AgentTokens.java`, `WorkCredentialStore.java`, `JdbcWorkCredentials.java`
- Modify: `WorkStore.java` (`WorkCredentialStore credentials()`), `JdbcWorkStore.java` (compose `JdbcWorkCredentials`, as `JdbcWorkClaims` is composed), `WorkRegistryService.java`
- Test: create `AgentTokensTest`, `JdbcWorkCredentialsPostgresTest`, `JdbcWorkCredentialsRollbackTest`, `WorkAgentTokenIdentityPostgresTest`; extend the store arch rule so `WorkCredentialStore` is covered like the other sub-stores

**Interfaces:**
- Produces:
  - `AgentTokens` (public final): `String PREFIX = "wka_"`; `static String generate(SecureRandom)` → `PREFIX` + base64url (no padding) of 32 random bytes; `static boolean looksLikeAgentToken(String bearer)` (starts with `PREFIX`); `static String hash(String token)` → lowercase hex SHA-256.
  - `WorkCredentialStore`: `record Issued(long credentialId, String token)`; `record Live(long credentialId, long agentId)`; `Issued issue(long agentId, String createdBy) throws SQLException` (one transaction: revoke the agent's live credential, insert the new one); `Optional<Live> findLive(String tokenHash) throws SQLException` (`revoked_at IS NULL`); `int revokeAll(long agentId) throws SQLException`; `void touch(long credentialId) throws SQLException` (`UPDATE … SET last_used_at = now() WHERE id = ? AND (last_used_at IS NULL OR last_used_at < now() - INTERVAL '1 minute')`).
  - `WorkRegistryService` (public): `record AgentLogin(long credentialId, long agentId)`; `Optional<AgentLogin> agentForToken(String token) throws SQLException` — empty unless `looksLikeAgentToken` and a live credential matches; calls `touch`; no caching. `Actor actorForAgent(long agentId) throws SQLException` — loads the agent and returns `agentActor(agent)` (null when retired, sponsorless or sponsor gone; `WorkUnavailableException` on a directory outage).
- No token, hash or credential id is ever logged above DEBUG; the token appears only in `Issued`.

- [ ] **Step 1: Failing tests.** `AgentTokensTest` (`generatedTokensStartWithThePrefixAndDiffer`, `hashIsStableHexSha256`, `aPersonalKeyPrefixIsNotAnAgentToken` for `wkk_abc`). `JdbcWorkCredentialsPostgresTest` (`issueReturnsATokenThatVerifies`, `issuingAgainRevokesThePreviousOne`, `aRevokedTokenIsNotFound`, `revokeAllLeavesNothingLive`, `touchWritesAtMostOncePerMinute`, `onlyTheHashIsStored` — the token text appears in no column). Rollback: `issueKeepsTheOldCredentialWhenTheInsertFails`. `WorkAgentTokenIdentityPostgresTest`: `aLiveTokenResolvesToItsAgent`, `aRetiredAgentsTokenIsNoActor`, `aSponsorlessAgentsTokenIsNoActor`, `aPersonalKeyStringNeverReachesTheCredentialTable` (spy store: `findLive` not called for `wkk_…`), `aStoreFailureIsNotAnEmptyResult` (`FaultInjectingDataSource` → `SQLException`, never `Optional.empty`).
- [ ] **Step 2:** Run; capture failures.
- [ ] **Step 3:** Implement. `JdbcWorkCredentials` holds a `Jdbc`, package-private, final.
- [ ] **Step 4:** `mvn test -pl wikantik-work` green; classify the two new public service methods in `WorkServiceVisibilityPostgresTest`'s method table. Commit `feat(work): agent credentials — issue, verify, revoke; a token resolves to an agent actor`.

---

### Task 5: The servlet filters accept agent tokens

**Files:**
- Create: `wikantik-main/.../auth/apikeys/AgentTokenAuth.java`
- Modify: `wikantik-main/.../auth/apikeys/AbstractApiAccessFilter.java`, `wikantik-main/.../work/runtime/WikiWorkActors.java`, `WorkWiring.java`
- Modify: `wikantik-mcp-core/.../mcp/KeyOnlyMcpAccessFilter.java`
- Modify: `wikantik-rest/.../WorkApiKeyFilter.java`, `WorkKeyRequest.java`, `WorkResourceBase.java` (`actor`), `WorkCallers.java`
- Modify: `wikantik-work-mcp/.../WorkMcpActors.java`, `WorkMcpInitializer.java`
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (one key), regenerate the config reference
- Test: `AbstractApiAccessFilter` tests, `KeyOnlyMcpAccessFilterTest`, `WorkApiKeyFilterTest`, `WorkKeyChainTest`, `WikiWorkActorsTest`, `WorkMcpActorsTest`, `WorkMcpSessionActorTest`, `WorkItemResourceTest`

**Interfaces:**
- Consumes: `registry().agentForToken(String)`, `registry().actorForAgent(long)`, `AgentTokens.looksLikeAgentToken`.
- Produces:
  - `AgentTokenAuth` (wikantik-main, no dependency on wikantik-work types in its signature): `record AgentRef(long credentialId, long agentId)`; `interface Verifier { Optional<AgentRef> verify(String token) throws ApiKeyService.LookupFailedException; }`; `String ATTR_AGENT_REF = "wikantik.work.agentRef"`; `static boolean isAgentToken(String bearer)` (prefix `wka_`). `WorkWiring` supplies the verifier (it adapts `agentForToken`; `SQLException` → `LookupFailedException`) and publishes it where the filters can reach it without `getManager` (follow how `WorkWiring` already hands the work service to the engine; do not add to the `DecompositionArchTest` allow-list).
  - `AbstractApiAccessFilter`: when the bearer `isAgentToken`, it is verified through the `Verifier` and **never** sent to `ApiKeyService`. Accepted only when the filter's required scope is `Scope.WORK`; on any other endpoint an agent token is refused exactly as an unknown key is. `clientId` is `"agent:" + agentId`. The request attribute `ATTR_AGENT_REF` holds the `AgentRef`; no `ApiKeyService.Record` is set.
  - `KeyOnlyMcpAccessFilter`: the session binding map is keyed to the `clientId` string (`"key:<id>"` or `"agent:<id>"`), so an agent id cannot collide with a key id.
  - `WorkApiKeyFilter`: same prefix branch; `WorkKeyRequest` carries either a record or an `AgentRef` (two package-private constructors), no principal either way. A per-credential limit applies to both kinds: new key `wikantik.work.api.rateLimit.perKey` (requests per second, default `20`, `Type: integer`, `0` disables), answering 429 `{"error":"Rate limit exceeded"}` with `Retry-After: 1`, using the same limiter class the MCP filter uses.
  - `WikiWorkActors`: `static Actor actorForAgent(WorkService, AgentTokenAuth.AgentRef) throws SQLException`. `WorkResourceBase.actor` and `WorkMcpActors.actorFor` use it when `ATTR_AGENT_REF` is present, and the existing key path otherwise.
  - `WorkCallers.authenticate`: `WorkUnavailableException`, or a `SQLException` wrapped by `actor(...)`, answers 503 `{"error":"Service temporarily unavailable"}`; it no longer becomes a 500.

- [ ] **Step 1: Failing tests.**
  - `WorkApiKeyFilterTest`: `anAgentTokenReachesTheChainWithNoPrincipal`, `anUnknownAgentTokenIs401`, `aCredentialStoreFailureIs503NotCached` (second call after recovery succeeds), `anAgentTokenIsNeverLookedUpAsAnApiKey` (mock `ApiKeyService` never called), `aPersonalKeyIsNeverLookedUpAsAnAgentToken`, `theSixthRequestInASecondIs429` (limit set to 5 in the test), `theLimitIsPerCredential`.
  - `WorkKeyChainTest`: `anAgentTokenWithARememberMeCookieNeverBecomesASession`.
  - `AbstractApiAccessFilter` tests (use the knowledge/admin endpoint configuration): `anAgentTokenIsRefusedWhereTheScopeIsNotWork`.
  - `KeyOnlyMcpAccessFilterTest`: `anAgentAndAKeyWithTheSameNumericIdDoNotShareASession`, `aSecondAgentOnABoundSessionIsRefused`.
  - `WorkMcpSessionActorTest`: `anAgentTokenRunsATool`, `retiringTheAgentStopsTheNextCall`, `aDirectoryOutageAnswersUnavailable`.
  - `WorkItemResourceTest`: `aDirectoryOutageWhileResolvingTheAgentIs503`.
- [ ] **Step 2:** Run; capture failures.
- [ ] **Step 3:** Implement; declare the config key and run `bin/config-reference.sh --write`.
- [ ] **Step 4:** `mvn test -pl wikantik-main -Dtest='*ApiAccessFilter*,WikiWorkActorsTest,ConfigSurfaceDriftTest,ConfigReferenceRegressionTest,DecompositionArchTest'`, then the unit suites of `wikantik-mcp-core`, `wikantik-rest`, `wikantik-work-mcp` (via `bin/agent-build.sh` if long). Commit `feat(work): the work filters accept agent tokens; per-credential limit on /api/work; outages answer 503`.

---

### Task 6: The registry runs on sponsors and credentials

**Files:**
- Modify: `wikantik-work/.../WorkRegistryService.java`, `RegistryChanges.java`, `AgentDraft.java`, `WorkService.java` (`AgentWithToken` unchanged), `Action.java`, `WorkPolicy.java`, `WorkCatalogStore.java` + `JdbcWorkCatalog.java` (`leadsAnyProject`), `WorkRegistryStore.java` + `JdbcWorkRegistry.java` (`agentsVisibleTo`)
- Modify: `wikantik-rest/.../WorkAgentResource.java`, `WorkJson` (agent view)
- Test: `WorkRegistryServiceTest` (rewrite), `WorkPolicyTest`, `JdbcWorkRegistryPostgresTest`, `WorkAgentResourceTest`, `WorkServiceVisibilityPostgresTest`

**Interfaces:**
- Consumes: `WorkCredentialStore.issue/revokeAll`, `setSponsor`, `WorkStanding`.
- Produces:
  - `AgentDraft(String name, String vendor, String runtime, List<String> queues)` — `owner` removed.
  - `Action.REGISTER_AGENT`. `WorkPolicy`: an AGENT actor is denied it; a person is allowed when admin or `leadsAnyProject`.
  - `WorkCatalogStore.leadsAnyProject(Actor person) throws SQLException` — true when the person (login or groups) has a `lead` row in an unarchived project.
  - `WorkRegistryService`:
    - `AgentWithToken registerAgent(Actor, AgentDraft)` — refusal text from Global Constraints (`WorkDeniedException`); sponsor = `actor.login()`; issues a credential; one audit entry `work.agent.registered`.
    - `void updateAgent(Actor, long, AgentDraft)` — allowed for an admin, or for the sponsor while `leadsAnyProject`.
    - `AgentWithToken rotateCredential(Actor, long agentId)` (replaces `rotateAgentKey`; no owner argument) — sponsor or admin.
    - `void retireAgent(Actor, long)` — sponsor or admin; also `credentials().revokeAll`.
    - `Agent takeOver(Actor, long agentId)` — a person only. Admin: always. Otherwise: the agent has no sponsor (null, or `directory.person(sponsor)` empty) and the actor is `lead` of a project the agent has a member row in; else the takeover refusal text. Sets sponsor = `actor.login()`.
    - `void releaseSponsorship(Actor, long agentId)` — the current sponsor or an admin; sets sponsor null.
    - `List<AgentView> listAgents(Actor)` with `record AgentView(Agent agent, String sponsorName, Status status)` and `enum Status { ACTIVE, RETIRED, NO_SPONSOR, SPONSOR_GONE }`. An admin sees all; anyone else sees agents they sponsor plus agents with a member row in a project they can see. `sponsorName` is the display name (the login only for an admin caller, as people are shown elsewhere).
    - Management refusals use the management refusal text. A caller who cannot see the agent at all gets `WorkNotFoundException`, not a denial.
  - Every registry change is audited through `WorkAudit` as phase 2 does (`work.agent.takeover`, `work.agent.sponsor_released` added).
  - REST (relative to `/api/work`): `POST agents` body `{name, vendor, runtime, queues}` → 201 `{agent, token}` with `Cache-Control: no-store`; `PATCH agents/{id}`; `POST agents/{id}/rotate-key` (no body needed) → `{agent, token}`; `POST agents/{id}/retire` → 204; `PUT agents/{id}/sponsor` (no body: the caller takes over) → the agent; `DELETE agents/{id}/sponsor` → 204. Agent JSON: `id, name, vendor, runtime, queues, createdAt, retiredAt, sponsorName, status` (`active|retired|no_sponsor|sponsor_gone`), `sponsoredByMe` (boolean); `apiKeyId` is gone.
- The `AgentKeys` port is no longer called by the registry after this task (it is deleted in Task 8).

- [ ] **Step 1: Failing tests** (names in `WorkRegistryServiceTest` unless noted): `aLeadRegistersAnAgentAndBecomesItsSponsor`, `aMemberWhoLeadsNothingCannotRegister` (exact refusal text), `anAgentCannotRegisterAnAgent`, `registerIssuesACredentialNotAnApiKey` (the `AgentKeys` mock is never called), `rotateCredentialRevokesTheOldToken`, `onlyTheSponsorOrAnAdminRotatesOrRetires`, `aDemotedSponsorCanStillRotateAndRetireButNotEdit`, `anAdminTakesOverAtAnyTime`, `aLeadTakesOverOnlyASponsorlessAgentInTheirProject`, `aLeadCannotDisplaceALivingSponsor` (exact text), `releaseSponsorshipLeavesTheAgentNoActor`, `takeoverAndReleaseWriteHistoryAndAudit`, `aNonAdminSeesOnlyAgentsTheySponsorOrShareAProjectWith`, `anAgentTheyCannotSeeIsNotFoundNotDenied`, `aDirectoryOutageDuringTakeoverIsUnavailableAndChangesNothing`. `WorkAgentResourceTest`: route/JSON shape for each route above, `tokenIsReturnedOnceWithNoStore`, `sponsorLoginIsShownOnlyToAnAdmin`, `aBodylessRotateIsAPlainRotation`.
- [ ] **Step 2:** Run; capture failures.
- [ ] **Step 3:** Implement. Keep `WorkRegistryService` under the PMD limits by moving sponsorship (takeover, release, status) into a package-private `AgentSponsorship` class if needed.
- [ ] **Step 4:** `mvn test -pl wikantik-work`; `wikantik-rest` unit suite; classify new public methods in the visibility test. Commit `feat(work): leads register agents; takeover and release of sponsorship; credentials replace keys in the registry`.

---

### Task 7: Claims follow the sponsor's standing

**Files:**
- Modify: `wikantik-work/.../JdbcWorkClaims.java`, `WorkClaimStore.java`, `WorkClaimsService.java`, `JdbcWorkCatalog.java` + `WorkCatalogStore.java` (`putMember`, `removeMember`), `WorkService.java` (`putMember`, `removeMember`), `WorkRegistryService`/`AgentSponsorship` (takeover, release), `WorkInputs.java` (`RELEASE_REASONS`)
- Modify: `wikantik-main/.../work/runtime/WorkClaimSweeper.java`
- Test: `JdbcWorkClaimsAccessPostgresTest`, `JdbcWorkClaimsConcurrencyPostgresTest`, `WorkClaimListenerPostgresTest`, `WorkInputsAgentTest`, `WorkClaimsServiceTest`, `WorkClaimSweeperTest`, `WorkAccessArchTest`, `WorkMcpAccessArchTest`; create `WorkSponsorStandingPostgresTest`

**Interfaces:**
- Produces:
  - `JdbcWorkClaims.SPONSOR_ACCESS_REMOVED = "sponsor access removed"`; added to `WorkInputs.RELEASE_REASONS` (so a person cannot use it as a deliberate release note).
  - Claim lock order becomes **agent → agent's member row → sponsor's member rows → project → item**. The agent lock also checks `sponsor = ?` (the sponsor the actor was resolved with), so a claim racing a takeover or release is refused. The sponsor lock is skipped when `actor.sponsor().admin()`; otherwise `SELECT 1 FROM work_member WHERE project_id = ? AND ((principal_kind = 'user' AND principal = ?) OR (principal_kind = 'group' AND principal = ANY(?))) AND role IN ('member','lead') FOR SHARE`, and no row means not claimable.
  - `WorkClaimStore`: `List<Holder> claimHolders(Long projectIdOrNull) throws SQLException` with `record Holder(long agentId, String agentName, String sponsor, long projectId)` (distinct agent/project pairs with a live claim); `int releaseForStanding(long projectId, Collection<Long> agentIds, Actor by, String note) throws SQLException`.
  - `WorkCatalogStore.putMember` / `removeMember` gain a last parameter `Collection<Actor> sponsorsToRecheck`: after the row change, in the same transaction, each listed non-admin sponsor's role in the project is re-read and, when it is below `member`, the claims held in that project by agents they sponsor are released with `SPONSOR_ACCESS_REMOVED`, actor = `by`. The returned count includes them. The service builds the list before the transaction from `claimHolders(projectId)`, resolving each sponsor through `directory.person`; a sponsor whose lookup fails is left out (one WARN) and is caught by the sweep.
  - Takeover and release of sponsorship release, in their transaction, the agent's claims in every project where the new sponsor (or nobody) lacks `member` standing; reason `SPONSOR_ACCESS_REMOVED`, actor = the person acting.
  - `WorkClaimsService.sweepStanding() throws SQLException` → the number of claims ended. For each `Holder`: resolve the sponsor once per sweep (`directory.person`); no sponsor or no account → release everywhere; not admin and role below `member` in that project → release there. A lookup failure for a sponsor skips that sponsor's holders and logs one WARN per outage (not per sweep). Events are `released` with the note, attributed to the agent (as `claim_expired` is). Counts `ClaimOutcome.RELEASED`.
  - `WorkClaimSweeper.cycle()` calls `sweepExpiredClaims()` then `sweepStanding()`; both arch tests forbid `sweepStanding` from rest and work-mcp exactly as they forbid `sweepExpiredClaims`.

- [ ] **Step 1: Failing tests.**
  - `JdbcWorkClaimsAccessPostgresTest`: `noClaimWhenTheSponsorIsNotAMember`, `noClaimWhenTheSponsorIsAViewer`, `claimWhenTheSponsorIsAMemberThroughAGroup`, `claimWhenTheSponsorIsAnAdminAndNotAMember`, `noClaimWhenTheSponsorChangedSinceTheActorWasResolved`.
  - `JdbcWorkClaimsConcurrencyPostgresTest` (`CommitGate`): `sponsorRemovalAndClaim_claimFirstIsReleased`, `sponsorRemovalAndClaim_removalFirstRefusesTheClaim`, `takeoverAndClaim_neverLeavesAClaimUnderTheOldSponsor`; a lock-order test that the new order cannot deadlock against `putMember`, `removeMember` and `archiveProject`.
  - `WorkSponsorStandingPostgresTest`: `removingTheSponsorsUserRowReleasesTheirAgentsClaims` (one `released` event, note `sponsor access removed`, `from_value` `agent:<id>`, actor = the lead who removed them), `demotingTheSponsorToViewerReleases`, `demotingTheSponsorFromLeadToMemberReleasesNothing`, `removingAGroupRowReleasesClaimsOfSponsorsWhoStoodOnlyOnThatGroup`, `aSponsorWithAnotherRouteToMemberKeepsTheClaims`, `takeoverReleasesClaimsOutsideTheNewSponsorsReach`, `releaseOfSponsorshipReleasesEveryClaim`, `theSweepEndsClaimsAfterAnAccountDeletion`, `theSweepEndsClaimsAfterAGroupChangeTheModuleWasNotToldAbout`, `aLookupFailureInTheSweepEndsNothingAndWarnsOnce`, `everyEndingIsCountedAsReleased` (listener).
  - `WorkInputsAgentTest`: `sponsorAccessRemovedIsReserved`. `WorkClaimSweeperTest`: `aCycleRunsBothSweeps`, `aFailureInOneSweepDoesNotSkipTheOther`.
- [ ] **Step 2:** Run; capture failures.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** `mvn test -pl wikantik-work` (the concurrency class three times), `WorkClaimSweeperTest`, the two arch tests. Commit `feat(work): claims end when a sponsor's standing does — in the transaction where the module knows, within a sweep where it does not`.

---

### Task 8: Delete the shared-key machinery

**Files:**
- Create: `bin/db/migrations/V080__work_agent_drop_key_binding.sql` (`ALTER TABLE work_agent DROP COLUMN IF EXISTS api_key_id; DROP TABLE IF EXISTS work_agent_key_history;`)
- Delete: `wikantik-rest/.../AgentKeyGuard.java`; `wikantik-work/.../AgentKeys.java`; `wikantik-main/.../work/runtime/WikiAgentKeys.java`; their tests (`WikiAgentKeysTest`, `WorkAgentOwnerDeletionPostgresTest`)
- Modify: `SelfApiKeysResource.java`, `AdminApiKeysResource.java` (remove guards, `agentKey`/`agent` listing fields, `agentKeyName`), `UserDeletionService.java` (remove `agentsKeyedBy`, `announceAgentsWithoutAKey` and their imports), `WorkRegistryService.java` (remove `agentNameForKey`, `isAgentKey`; `actorForApiKey` returns only the owner as a non-admin person), `WorkRegistryStore.java`/`JdbcWorkRegistry.java` (remove `agentByApiKey`, `agentEverBoundTo`, `rebindAgentKey`, `wasEverAgentKey`, the `api_key_id` column from `AGENT_SELECT`/insert), `RegistryChanges.java` (`rotationKey`, `revokeQuietly`), `WorkService` constructor and `WorkWiring` (no `AgentKeys`), `WikiWorkDirectory.ownerExists`, `WikiWorkActors.actorForKey` (personal keys only)
- Test: `SelfApiKeysResourceTest`, `AdminApiKeysResourceTest`, `UserDeletionServiceTest`, `WorkAgentIdentityPostgresTest`, `WikiWorkActorsTest`, `WorkAgentsSchemaTest`

**Interfaces:**
- Produces: `WorkRegistryService.actorForApiKey(int apiKeyId, String ownerLogin)` → always `Actor.human(owner, groups, false)`. No API key is, was or can become an agent.

- [ ] **Step 1: Failing/changed tests first.** `WorkAgentsSchemaTest`: `theKeyBindingColumnAndHistoryTableAreGone`. `SelfApiKeysResourceTest`/`AdminApiKeysResourceTest`: `aWorkScopeKeyRotatesAndRevokesLikeAnyOtherKey`, `listingsCarryNoAgentFields`; delete the agent-guard cases. `WorkAgentIdentityPostgresTest`: `aWorkScopeKeyIsItsOwnerNeverAdminAndNeverAnAgent`; delete the bound/rotated-key cases. `UserDeletionServiceTest`: delete the agent-key cases; add `deletingASponsorWritesNothingToWork` (the sponsor's agents are suspended by computation — assert `actorForAgent` is null afterwards, in a work-module test if simpler).
- [ ] **Step 2:** Run; capture failures.
- [ ] **Step 3:** Delete and simplify. Search to confirm nothing remains: `grep -rn 'AgentKeyGuard\|AgentKeys\|wasEverAgentKey\|agentByApiKey\|api_key_id' --include='*.java' --include='*.jsx' --include='*.js' wikantik-*/src | grep -v api_keys` returns only `api_keys`-table code.
- [ ] **Step 4:** Unit suites of wikantik-work, wikantik-main (via `bin/agent-build.sh`), wikantik-rest, wikantik-work-mcp; root `mvn pmd:check -Pcomplexity-gate` (remove any baseline entry that no longer applies; never add one). Commit `refactor(work): personal API keys are never agents; the key-binding guards, port and tables are gone (V080)`.

---

### Task 9: Frontend

**Files:**
- Modify: `wikantik-frontend/src/api/work.js`, `components/work/WorkAgents.jsx`, `WorkAgentForm.jsx`, `WorkHome.jsx`, `WorkSettings.jsx`, `eventText.js`, `components/MyApiKeys.jsx`, `components/admin/AdminApiKeysPage.jsx`
- Delete: `components/work/WorkOwnerPicker.jsx` (+ test) if nothing else uses it
- Test: `api/work.test.js`, `WorkAgents.test.jsx`, `WorkSettings.test.jsx`, `eventText.test.js`, `MyApiKeys.test.jsx`, `AdminApiKeysPage.test.jsx`

**Interfaces:**
- Consumes the Task 6 REST shapes. `api/work.js`: `registerAgent({name, vendor, runtime, queues})`, `rotateAgentKey(id)`, `takeOverAgent(id)` (`PUT agents/{id}/sponsor`), `releaseAgentSponsorship(id)` (`DELETE agents/{id}/sponsor`). The member list response carries, for an agent member, `effective` (boolean) and `reason` (`no_sponsor | sponsor_gone | sponsor_not_member | sponsor_viewer`) — add these two fields to the members JSON in `WorkProjectResource`/`WorkJson` as part of this task, computed through `WorkStanding`, with a REST unit test.
- Behaviour:
  - `/work/agents` opens for anyone who can see a project (the server decides what they see); the page no longer redirects non-admins. The "Agents & queues" link on `WorkHome` shows for everyone who can see a project. Queue create/edit/retire controls stay admin-only. "Register an agent" shows when the server allows it (try the action; show the server's refusal text verbatim on 403).
  - Each agent row shows "Sponsor: {sponsorName}" and a status line when not active: `Suspended: no sponsor`, `Suspended: its sponsor no longer has an account`, `Retired`. Rows the viewer sponsors get Rotate, Retire, Edit and "Release sponsorship" (confirm: "This agent stops working until someone takes it over."). A sponsorless agent gets "Take over" (confirm: "You become responsible for what this agent does. It can never do more than you can."). Server refusals are shown verbatim.
  - Register form: the owner picker is gone.
  - Project settings, members: an agent member with `effective: false` shows the reason in words: `No effect: this agent has no sponsor`, `No effect: its sponsor no longer has an account`, `No effect: its sponsor is not a member of this project`, `Read-only: its sponsor is a viewer here`. Removing or demoting a **person** asks for confirmation when the server would release claims: use the confirm text "Agents this person sponsors lose their claims in this project."
  - `eventText.js` `releasedText`: a `sponsor access removed` case — person actor: `{actor} changed {agent}'s sponsor's access, ending its claim (now {status})`; agent actor (the sweep): `{agent}'s claim ended because its sponsor lost access (now {status})`.
  - Key pages: remove the agent badge, the "Managed on the Agents page" hint and the `isManageableKey`/`agentKey` exclusions.

- [ ] **Step 1: Failing tests** for each behaviour above (one test per bullet at least; assert exact copy).
- [ ] **Step 2:** `npx vitest run <touched files>`; capture failures.
- [ ] **Step 3:** Implement, reusing `src/components/ui/` and defined CSS tokens only.
- [ ] **Step 4:** Whole `npx vitest run`, `npx vitest run --coverage` twice, `npx eslint src`, `npx vite build`. Commit `feat(work-ui): sponsors on the agents page, leads register and take over, key pages lose agent keys`.

---

### Task 10: Integration tests, docs, release notes, full gate

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-rest/.../WorkIt.java` (helpers mint agents through `POST /api/work/agents` and use `wka_` tokens), `WorkAgentApiIT`, `WorkKeySessionIT`, `WorkMcpIT`, `WorkClaimExpiryIT`, `WorkBoardIT`, `WorkLeakIT`
- Modify: `docs/admin/ApiKeys.md`, `McpAgents.md`, `Security.md`, `WikantikOperations.md` §1.15, `docs/developer/ProjectReference.md`, `wikantik-work/CLAUDE.md`, `wikantik-work-mcp/CLAUDE.md`, `wikantik-work-mcp/src/main/resources/wikantik-work-mcp-instructions.txt`, root `CLAUDE.md` (module line, security bullet on the `work` scope), the parent spec's phase 2 section (a pointer to the 3a spec, no rewrite), `CHANGELOG.md` `[Unreleased]`

**Interfaces:** none new.

- [ ] **Step 1: ITs (written to fail before the fix where they pin new behaviour).**
  - `WorkAgentApiIT`: `aLeadRegistersAnAgentAndItWorksInTheirProject`, `anAgentLosesTheProjectWhenItsSponsorIsRemovedAndGetsItBackWhenTheyReturn` (404 with a stranger's body in between), `aSponsorDemotedToViewerLeavesTheAgentReadOnly`, `rotateStopsTheOldTokenImmediately`, `releaseThenTakeOverByAnotherLead`, `aLeadCannotTakeOverAnAgentThatHasASponsor`; delete `anOwnerCannotRotateOrRevokeTheirAgentsKeyThroughTheGenericKeyEndpoints`.
  - `WorkKeySessionIT`: `anAgentTokenNeverStartsASession`, `anAgentTokenIsRefusedOnTheOtherKeyedSurfaces` (`/knowledge-mcp`, `/wikantik-admin-mcp`, `/tools/*`, a non-work `/api/*` route), `aPersonalWorkKeyStillActsAsItsOwner`.
  - `WorkMcpIT`: every tool once with a `wka_` token; `oneSessionServesOneAgent`; `removingTheSponsorStopsTheNextToolCall`.
  - `WorkClaimExpiryIT` (1-minute sweep in the rest cascade): `aClaimEndsWithinASweepAfterTheSponsorsAccountIsDeleted`, asserting the log row `sponsor access removed`.
  - `WorkBoardIT`: the agent flow registers the agent as the signed-in lead; the Agents page shows "Sponsor:" and the member row; no key token in any assertion message.
- [ ] **Step 2: Docs.** Re-derive every status code, message, metric and route from the source. Remove the "agent keys are managed only here" guidance and the P23/P31/P32 text; describe sponsors, the ceiling rule, takeover and release, the `wka_` token, where it is accepted, the per-credential limit key, `sponsor access removed`, and the two sweeps. Upgrade note: retire phase 2 agents (or revoke their keys) before upgrading; after it they have no sponsor and must be taken over and rotated. `CHANGELOG.md` `[Unreleased]`: Added / Changed / Removed entries in plain language.
- [ ] **Step 3: Gate.** `mvn install -DskipTests -T 1C`; `bin/agent-build.sh start gate3a -- bin/run-tests.sh --parallel 4` to a green terminal state; then `bin/agent-build.sh start cov3a -- mvn clean install -Pcoverage -DskipITs` (floors hold; never set one above 0.90). Fix anything red in this task.
- [ ] **Step 4:** Commit `test+docs(work): phase 3a integration tests, operator docs and release notes`.
