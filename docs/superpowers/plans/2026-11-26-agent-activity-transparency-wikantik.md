# Agent Activity Transparency (Wikantik side) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wikantik emits one standard `mcp_tool_called` event per MCP call, stores agent field reports behind
`file_agent_report`, exposes them to the owner and the digest agent, ships the two skills, and publishes the
suite guidance that the other repositories build against.

**Architecture:** A shared event emitter in `wikantik-mcp-core` used by all three MCP dispatchers. A new,
extraction-ready module `wikantik-agent-reports` (no dependency on `wikantik-api`/`wikantik-main`, own tables,
`Jdbc` only) with thin adapters: one knowledge-MCP tool, one admin-MCP tool, one `/admin` REST resource. The
guidance lives on the wiki; other repositories get requests, not code.

**Tech Stack:** Java 25, Log4j2 `JsonTemplateLayout` (`EcsLayout.json`), MCP Java SDK 2.x
(`McpSyncServerExchange.sessionId()`/`getClientInfo()`), PostgreSQL via `wikantik-jdbc`, JUnit 5 +
`PostgresTestDb`, Cargo ITs.

**Spec:** `docs/superpowers/specs/2026-11-26-agent-activity-transparency-design.md` (wiki:
`SimpleAgilityAgentActivityDesign`). Read §5 and §8a before any task.

## Global Constraints

- Event fields exactly as spec §5.1: `event.action`=`mcp_tool_called`, `event.outcome` `success|failure`,
  `event.duration` (ns), `mcp.server`, `mcp.tool`, `mcp.client.name` (≤64 chars), `mcp.session.hash`,
  `mcp.response.bytes`, `error.type` on failure. `message` = `mcp tool called`.
- **Never** log arguments, search text, page bodies, results, the raw session id, or an `error.message`
  that echoes arguments.
- `mcp.server` values: `knowledge`, `admin`, `work`.
- Session hash: first 12 hex chars of SHA-256 over (per-JVM random salt + session id). No config key, no
  shared secret. Absent session id → `none`.
- Report enums: `kind_of_work` ∈ `operations|content|build|research|other`; `outcome` ∈
  `completed|partial|abandoned`; `context_used[].source` ∈ `wiki|blog|repo|web`; `helped` ∈ `yes|partly|no`.
- Report limits: `task` ≤ 300, `context_missing` ≤ 1000, `friction[].what` ≤ 500, `notes` ≤ 2000,
  `workspace` ≤ 100; lists ≤ 50 entries. Over-limit or invalid → refused with a reason naming the field;
  nothing truncated silently.
- `client`, `session_hash`, `filed_by` are server-filled; any agent-supplied value for them is ignored.
- Reports are append-only; no update/delete path anywhere.
- `wikantik-agent-reports` depends on neither `wikantik-api` nor `wikantik-main`; nothing outside the module
  touches `agent_report*` tables.
- Every new `wikantik.*` key is declared in `ini/wikantik.properties` first (ConfigSurfaceDriftTest).
- New MCP tools go in the instructions file **and** `McpProtocolIT.EXPECTED_TOOLS`.
- TDD: each test is seen to fail before the code that passes it.

## Review Focus

1. A tool that throws or returns `isError` still emits exactly one event, with `failure` — pin in Task 1.
2. A call before `initialize` completes (null client info / null session) emits `client=?`, `hash=none`, never
   throws — Task 1.
3. A report with unicode near the length limit is measured in characters, not bytes — Task 3.
4. An agent supplying `client`/`filed_by` in arguments cannot spoof them — Task 4.
5. A `mcp_read` key can call `file_agent_report` but still cannot call any other write — Task 4 IT.

---

### Task 1: The `mcp_tool_called` event in all three MCP dispatchers

**Files:**
- Create: `wikantik-mcp-core/src/main/java/com/wikantik/mcp/McpCallEvent.java`, `.../SessionHash.java`
- Modify: `wikantik-mcp-core/.../AuditedToolCall.java` (add a `server` argument; emit the event in `finally`),
  `wikantik-admin-mcp/.../McpServerInitializer.java:139,147` (pass `"admin"`),
  `wikantik-knowledge/.../KnowledgeMcpInitializer.java:180` (pass `"knowledge"`),
  `wikantik-work-mcp/.../WorkMcpInitializer.java:165-195` (pass the exchange's client name and session id
  into `dispatch`; emit with `"work"`)
- Test: `wikantik-mcp-core/src/test/java/com/wikantik/mcp/McpCallEventTest.java`, `SessionHashTest.java`;
  extend the existing `WorkMcpInitializer` dispatch test

**Interfaces — Produces:**
- `SessionHash.of(String sessionIdOrNull) -> String` (12 lowercase hex, or `"none"`).
- `McpCallEvent.emit(String server, String tool, String clientName, String sessionHash, boolean success,
  long durationNanos, long responseBytes, String errorTypeOrNull)`.
- `McpCallEvent.clientName(McpSyncServerExchange)` and `McpCallEvent.sessionHash(McpSyncServerExchange)` —
  null-safe, never throw (move `AuditedToolCall.clientName` here).
- Logger name `com.wikantik.mcp.events`; fields carried via `CloseableThreadContext` so the docker
  `EcsLayout.json` (MDC flattened to the root) writes them as top-level keys.

- [ ] **Step 1: Failing tests.** `McpCallEventTest` attaches a `JsonTemplateLayout(eventTemplateUri=
  classpath:EcsLayout.json)` appender to `com.wikantik.mcp.events`, calls `emit("knowledge","get_page",
  "claude-code","abc123def456",true,18_400_000L,512L,null)`, parses the JSON line and asserts top-level
  `event.action=="mcp_tool_called"`, `event.outcome=="success"`, `event.duration=="18400000"`,
  `mcp.server`, `mcp.tool`, `mcp.client.name`, `mcp.session.hash`, `mcp.response.bytes=="512"`,
  `message=="mcp tool called"`; a failure call asserts `event.outcome=="failure"` and `error.type`. A
  65-char client name is cut to 64. After `emit`, `ThreadContext` holds none of the keys.
  `SessionHashTest`: same input → same 12-hex output; different input → different; null/blank → `none`;
  output never contains the input.
  `AuditedToolCall` tests: a supplier that throws emits one `failure` event and rethrows; an `isError` result
  emits `failure`; a null exchange emits `client=?`, `hash=none`.
- [ ] **Step 2:** `mvn test -pl wikantik-mcp-core -Dtest='McpCallEventTest,SessionHashTest,AuditedToolCall*'`
  → FAIL (classes missing).
- [ ] **Step 3: Implement.** Response bytes = UTF-8 length of the result's text contents (0 when none). If
  the MDC is not flattened to the root by the stock layout, say so in the report and switch to a
  `StringMapMessage` + layout-compatible approach; the test is the arbiter. Keep the existing free-text
  audit lines.
- [ ] **Step 4:** Re-run the tests → PASS; `mvn test -pl wikantik-work-mcp,wikantik-admin-mcp,wikantik-knowledge -q`
  → PASS (`mvn install -DskipTests -pl wikantik-mcp-core` first).
- [ ] **Step 5: Commit** `feat(mcp): one mcp_tool_called ECS event per tool call on all three MCP servers`.

### Task 2: Migration for the report store

**Files:** Create `bin/db/migrations/V098__agent_reports.sql`.

- `agent_report` (`id bigserial pk`, `filed_at timestamptz not null default now()`, `client text not null`,
  `session_hash text not null`, `filed_by text not null`, `workspace text`, `kind_of_work text not null`,
  `products text[] not null`, `skills text[] not null`, `window_start timestamptz`, `window_end timestamptz`,
  `task text not null`, `outcome text not null`, `context_missing text`, `notes text`), CHECK constraints for
  the two enums, indexes on `filed_at`, `session_hash`, `kind_of_work`.
- `agent_report_context` (`report_id` fk, `ordinal int`, `source`, `ref`, `helped`; CHECKs) and
  `agent_report_friction` (`report_id` fk, `ordinal`, `product`, `tool`, `what`, `blocking boolean`).
- Idempotent DDL; grants via `:app_user`; no data.

- [ ] **Step 1:** Write it; apply twice against a scratch DB (`PostgresTestDb` applies all migrations — run
  any existing `@RequiresPostgres` test in `wikantik-jdbc`); second apply is a no-op.
- [ ] **Step 2: Commit** `feat(db): V098 agent report tables`.

### Task 3: Module `wikantik-agent-reports` (domain, validation, store, service)

**Files:** Create module `wikantik-agent-reports` (pom: `wikantik-jdbc`, log4j-api; test: junit, mockito-core,
`wikantik-jdbc` test-jar); register in root `pom.xml` `<modules>` after `wikantik-insights`, and in
`wikantik-bom`/`wikantik-war` as the insights module is. Package `com.wikantik.agentreports`. Add
`wikantik-agent-reports/CLAUDE.md` (3 lines: purpose, no api/main dependency, extraction intent).

**Interfaces — Produces:**
- `record AgentReport(Long id, Instant filedAt, String client, String sessionHash, String filedBy,
  String workspace, String kindOfWork, List<String> products, List<String> skills, Instant windowStart,
  Instant windowEnd, String task, String outcome, List<ContextUse> contextUsed, String contextMissing,
  List<Friction> friction, String notes)`; `record ContextUse(String source, String ref, String helped)`;
  `record Friction(String product, String tool, String what, boolean blocking)`.
- `record Filer(String client, String sessionHash, String filedBy)` — the server-filled identity.
- `AgentReportValidator.validate(Map<String,Object> args) -> Validation` where `Validation` is either
  `ok(AgentReport draft)` or `refused(List<String> reasons)`; each reason names the field.
- `AgentReportStore` (interface) + `JdbcAgentReportStore(Jdbc)`: `long insert(AgentReport, Filer)` (one
  transaction across the three tables), `List<AgentReport> list(ReportQuery)`.
- `record ReportQuery(Instant from, Instant to, String kindOfWork, String product, String client,
  String skill, String outcome, int limit)` — nulls mean "any"; `limit` capped at 500.
- `AgentReportService(AgentReportStore, int maxPerHourPerKey, Clock)`: `FileResult file(Map<String,Object>
  args, Filer filer)` → `filed(id)` | `refused(reasons)` | `rateLimited(retryAfterSeconds)`;
  `List<AgentReport> list(ReportQuery)`.

- [ ] **Step 1: Failing tests.** `AgentReportValidatorTest`: minimal valid map → ok; each enum with a bad
  value → refused naming the field; `task` of 301 chars → refused; a 300-char string of `é` → ok (chars, not
  bytes); 51 `products` → refused; `client`/`filed_by`/`session_hash` keys in args are ignored (draft has
  none). `JdbcAgentReportStoreTest` (`@RequiresPostgres`, truncate in `@BeforeEach`): insert + list
  round-trips every field and list order; filters each narrow correctly; `FaultInjectingDataSource` failing on
  the friction insert leaves no `agent_report` row. `AgentReportServiceTest`: 61st call in an hour for one
  `filedBy` → rateLimited; another key unaffected; clock advance clears it.
- [ ] **Step 2:** `mvn test -pl wikantik-agent-reports` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Tests → PASS; `mvn install -DskipTests -pl wikantik-agent-reports -am -q`.
- [ ] **Step 5: Commit** `feat(agent-reports): extraction-ready report store, validation and service`.

### Task 4: `file_agent_report` on `/knowledge-mcp` + wiring + config

**Files:**
- Create: `wikantik-knowledge/.../knowledge/mcp/FileAgentReportTool.java`
- Modify: `KnowledgeMcpInitializer` (register; the tool needs the exchange — follow how the call handler at
  line ~180 passes it), the knowledge instructions resource, `McpProtocolIT.EXPECTED_TOOLS`,
  `ini/wikantik.properties` (`wikantik.agentReports.maxPerHourPerKey = 60`, with description and `Type:`),
  service wiring in `wikantik-main` following `InsightsWiring` (construct `AgentReportService` from the wiki
  datasource; expose it to the knowledge initializer the same way insights services reach the admin MCP).
- Test: `FileAgentReportToolTest` (Mockito), and an IT in `wikantik-it-test-rest` beside `McpProtocolIT`.

**Interfaces — Consumes:** `AgentReportService.file`, `Filer`, `McpCallEvent.clientName/sessionHash`
(Task 1). `filedBy` = the API-key owner resolved by the endpoint's access filter (find where the knowledge
endpoint's key filter records the caller; `WorkMcpActors.actorFor` shows reading it from the transport
context).

- [ ] **Step 1: Failing tests.** Tool test: valid args → result text contains the new id and is not
  `isError`; refused → `isError` with every reason; rate-limited → `isError` naming the retry seconds;
  args carrying `client:"spoof"` → the `Filer` passed to the service has the exchange's client name. The
  tool's description states it is the endpoint's only write and stores to the report store, not pages.
  IT: with an `mcp_read` key, `tools/list` contains `file_agent_report`; calling it returns an id; the same
  key calling an admin-endpoint write is still refused; `list_agent_reports` (Task 5) is not on this endpoint.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement** and update the instructions text (one paragraph: when to file, that the
  `agent-field-report` skill describes it).
- [ ] **Step 4:** Unit tests PASS; `bin/run-tests.sh --module rest` (or the IT module's documented single
  run) PASS; `ConfigSurfaceDriftTest`, `InstructionsRegistryDriftTest` PASS;
  `bin/config-reference.sh --write` and commit the regenerated reference.
- [ ] **Step 5: Commit** `feat(knowledge-mcp): file_agent_report — the endpoint's single write`.

### Task 5: `list_agent_reports` (admin MCP) and `/admin/agent-reports`

**Files:** Create `wikantik-admin-mcp/.../tools/ListAgentReportsTool.java`,
`wikantik-rest/.../AdminAgentReportsResource.java`; modify `McpToolRegistry` (extract a helper as
`readerEngagementTool` was, to stay under the PMD NPath ratchet), admin instructions file,
`McpProtocolIT.EXPECTED_TOOLS`, `web.xml` servlet mapping under `/admin/agent-reports` (behind
`AdminAuthFilter`).

**Interfaces — Consumes:** `AgentReportService.list(ReportQuery)`.
Parameters (both surfaces): `since` (ISO date, default 7 days ago), `until`, `kind_of_work`, `product`,
`client`, `skill`, `outcome`, `limit` (default 100, max 500). Output: `{reports:[…all fields…], count}`.

- [ ] **Step 1: Failing tests.** Tool: filters pass through to `ReportQuery`; bad `since` → `isError` naming
  it; default window = 7 days ending now (fixed `Clock`). Resource: non-admin → 403; admin GET with
  `kind_of_work=build` → 200 JSON with only build reports (use `RestServletBase.sendError` for errors).
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS, plus `mvn -B pmd:check
  -Pcomplexity-gate -pl wikantik-admin-mcp,wikantik-rest`.
- [ ] **Step 5: Commit** `feat(admin): list_agent_reports + /admin/agent-reports`.

### Task 6: The two skills

**Files:** Create `.claude/skills/agent-field-report/SKILL.md` and
`.claude/skills/agent-activity-digest/SKILL.md`, each with frontmatter `name`, `description`, `version: 1`.

- `agent-field-report`: when (end of every substantive task; not one-line lookups), how (call
  `file_agent_report` on the knowledge MCP with the §5.2 fields), honesty rules (`context_missing` and
  `friction` matter most; name skills as `name@version`), never secrets/personal data, never file work
  requests. Include one complete example call.
- `agent-activity-digest`: inputs (`list_agent_reports` for the ISO week; jakemon Loki counts — LogQL
  `sum by (service_name, mcp_server, mcp_tool, event_outcome) (count_over_time({…} | json |
  event_action="mcp_tool_called" [7d]))`, querying Loki's HTTP API on the jakemon central host; record in
  the skill exactly where that host and port are, from `JakemonHub`), the registry, the previous digest;
  output page `AgentActivityDigest<YYYY>W<ww>` in cluster `agent-activity` with the §5.6 sections, written via
  the wiki-content skill; ends with "Candidate requests" for the owner; never files work items; flags skill
  version mismatches against the registry.

- [ ] **Step 1:** Write both; validate the field-report example against Task 3's validator by pasting it
  into a `AgentReportValidatorTest` case (`skillExampleIsValid`) — fails if the skill drifts.
- [ ] **Step 2: Commit** `feat(skills): agent-field-report and agent-activity-digest`.

### Task 7: Suite guidance on the wiki (wiki-content skill, MCP only)

Load the `wiki-content` skill. Read each page before editing (`read_page` → `update_page` with
`expectedContentHash`). Prod is authoritative.

- [ ] Create `SimpleAgilityAgentActivity` (type article, cluster `simple-agility`): the normative contract —
  event table (§5.1), report fields (§5.2), the skill, the registry, the digest, the loop, per-repo table
  (§7). Link from `SimpleAgilityStackHub`.
- [ ] `SimpleAgilityDecisionLog`: three new SA entries (Decision/Why/Consequences): the event + field names
  (supersedes jakemon's 2026-10-24 request shape; consequences: Pressroom and jakemon changes);
  reports in Wikantik behind the knowledge-endpoint write exception, built for extraction; requests only
  from owner review. Link each from the normative page.
- [ ] `SimpleAgilityTelemetryContract`: add `mcp_tool_called` to the events section with a link.
- [ ] `SimpleAgilityObservabilityPlane`: one paragraph — tool-call events are plane data; reports are
  Wikantik product data, and why.
- [ ] `SimpleAgilityAgentCoordination`: new initiative with each repository's step (§7 table); add to the
  Standard Repository Agent Header: "At the end of substantive work, file a field report (skill
  `agent-field-report`)."
- [ ] Create `SimpleAgilityAgentFieldReportSkill` (canonical skill text = Task 6 file) and
  `SimpleAgilitySkillRegistry` (rows for every skill in this repo's `.claude/skills/` and
  `blogger-agent/skills/`, version, repos, introduced date — use git log dates; mark unknown as unknown).
- [ ] Create hub `AgentActivityHub` (`type: hub`, `cluster: agent-activity`).
- [ ] Update this repo's CLAUDE.md header block to match the new Standard Header line.
- [ ] Commit the CLAUDE.md change: `docs(CLAUDE.md): field-report line from the Standard Header`.

### Task 8: Requests to the other repositories

- [ ] Check `SimpleAgilityAgentCoordination` and the work board for existing projects per repo. For jakemon
  (dashboard + panel field move + digest's Loki read path) and Pressroom (`McpToolExecutor` event), write
  a request each, linking `SimpleAgilityAgentActivity` and the SA entry, with acceptance = "a
  `mcp_tool_called` line with all required fields appears in Loki for one call" / "dashboard shows
  Wikantik calls by tool". File them as work items if this session has a work credential; otherwise put
  them on the coordination page and list them for the owner to file.
- [ ] blogger-agent, wiki-content-agent, backoffice, stockroom: coordination-page line each (install the
  skill, adopt the header line; stockroom's future MCP emits the event).

### Task 9: Docs, gate, release

- [ ] Update: root CLAUDE.md module list (new module; knowledge MCP now 17 tools with one write; admin MCP
  24), `McpIntegration` and `FindingTheRightMcpTool` wiki pages (counts derived from `EXPECTED_TOOLS`),
  `docs/developer/ProjectReference.md` agent-facing table, CHANGELOG.
- [ ] Gate: `bin/agent-build.sh start gate -- bin/run-tests.sh --all`; poll to SUCCESS.
- [ ] Release per the repo flow (`printf 'n\n' | bin/cut-release.sh X.Y.Z`; push main + tag); hand the owner
  `! bin/deploy-release.sh X.Y.Z` unless already authorised this session.
- [ ] Verify on prod: one knowledge-MCP call produces a `mcp_tool_called` JSON line in
  `docker logs repo-wikantik-1` with all fields; `file_agent_report` with a test report returns an id;
  `list_agent_reports` shows it; smoke + health OK.
