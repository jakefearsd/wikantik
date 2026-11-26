# Agent activity transparency — design

Status: draft for owner review, 2026-11-26. Scope: the Simple Agility suite (Wikantik, Pressroom, jakemon,
the agent repositories, and products not yet built such as Stockroom).

## 1. Intent

The owner runs much of the suite through agents: administration, content generation (wiki and
blog.wikantik.com), and building the products themselves. Claude Code and Antigravity reach the products over
MCP. The owner wants to understand, and then improve:

1. **How well the wiki works as a context supplier.** When an agent is given a job, does it reach for the
   wiki, find the right pages, and get them in a usable form? The best observed case: the wiki surfaced that a
   request conflicted with the documented architecture, which turned a narrow change into a suite-level
   conversation (this design is that conversation).
2. **Whether each product's MCP surface gives the right level of service** for good outcomes.
3. **How usage responds to skills.** Skills exist but are not organized. The owner will introduce them
   deliberately and wants to see the change in behaviour, by kind of work, before and after.
4. **Learning.** The owner wants to see how agents actually behave with MCP, not only scores.

Out of scope: the inner workings of Claude Code, Gemini or Antigravity (tokens, cost, model internals),
except where they show whether the owner's instructions steer agents to the intended content.

## 2. Goals, non-goals, anti-goals

**Goals**
- G1. One comparable record of every MCP tool call, in every product, now and future.
- G2. Structured outcome and friction reports from agents, comparable across agents and products.
- G3. A periodic digest (a wiki page) and jakemon dashboards to learn from both.
- G4. A known record of which skills exist, where, and since when, so effects can be compared.
- G5. Improvement requests flow into Wikantik work management, where agents build them.

**Non-goals (now)**
- Agents filing work requests themselves. Requests come **only from the owner's review** of the digest.
  A skill may relax this later, once trust is earned; this design does not.
- Server-side inference of hits/misses from call sequences (reformulation rate, read-after-search). Kept as
  a later cross-check (§9), not built in this pass.
- Collecting client telemetry (Claude Code OpenTelemetry export).
- Any role for the back-office suite (Umami, listmonk). Considered and dropped: nothing here is reader data.

**Anti-goals** (this fails even if it works if…)
- It pollutes the wiki corpus or retrieval with report noise.
- It logs content: search text, page bodies, prompts or secrets into the plane.
- Measurement depends on agents stamping identifiers on every call (they cannot today).
- The report store becomes entangled with Wikantik's core so it can never be extracted.

## 3. Constraints

- **Where data lives** (`SimpleAgilityObservabilityPlane`): observation telemetry goes to the plane;
  data that is a functional input to a product stays in that product. Tool-call events are observation →
  jakemon (Loki). Reports drive work requests and become context → product data.
- **Telemetry contract** (`SimpleAgilityTelemetryContract`, SA-6): ECS JSON logs; events are log lines with
  `event.action`; no high-cardinality Loki labels.
- **Wikantik never calls a chat model.** Anything narrative (the digest) is written by an agent.
- **Cross-repo changes** need `SA-<n>` entries on `SimpleAgilityDecisionLog` in the same piece of work.
- **Pressroom and blogger-agent** may be inspected but not modified by this work; their changes are
  requested through `SimpleAgilityAgentCoordination` and work items, and built by their own agents.
- The knowledge endpoint (`/knowledge-mcp`) is read-only by design; the owner grants **one exception**
  for filing reports (§5.3).

## 4. Approaches considered (for where reports live)

The event half is common to all: every product emits one standard event per MCP call.

| | Reports live in | For | Against |
|---|---|---|---|
| A | jakemon, as log events | one store | reports are product input (drive work), long free text; Loki retention and text search are poor |
| **B (chosen)** | **Wikantik, a small dedicated report store** behind one tool on the knowledge endpoint | reports sit beside the context they judge; digests become context; work items link to reports | one more table + tool; the wiki becomes the collection point for a suite concern |
| C | Wikantik work management, a "field reports" project | no new storage | blurs observations with requests; every reporting agent needs a work credential |

**Owner's ruling:** B, with the write exception on the knowledge endpoint. The owner is not fully satisfied
with the knowledge-endpoint exception but has no better option yet. Build B **loosely coupled** so it can
be extracted into its own service later (§5.2).

## 5. Design

### 5.1 The tool-call event (all products)

Every product with an MCP server writes one ECS log line per `tools/call`, after the outcome is known.
This supersedes jakemon's 2026-10-24 request (`jakemon/docs/requests/2026-10-24-wikantik-mcp-tool-metrics.md`),
generalising its Wikantik-specific field names to product-neutral ones.

| Field | Required | Value |
|---|---|---|
| `@timestamp`, `log.level`, `message`, `service.name` | yes | contract fields; `message` = `mcp tool called` |
| `event.action` | yes | exactly `mcp_tool_called` |
| `event.outcome` | yes | `success`, or `failure` (tool error, exception, or refused before running) |
| `event.duration` | yes | nanoseconds |
| `mcp.server` | yes | endpoint name within the product, e.g. `knowledge`, `admin`, `work`, `pressroom` |
| `mcp.tool` | yes | registered tool name; `unknown` for unrecognized |
| `mcp.client.name` | yes | MCP `clientInfo.name` as sent (`claude-code`, `sa-agent`, …), truncated to 64 chars |
| `mcp.session.hash` | yes | short stable hash of the MCP session id (never the raw id) |
| `mcp.response.bytes` | recommended | size of the tool result |
| `error.type`, `error.message` | on failure | ECS error fields; `error.message` must not echo arguments |
| `trace.id` | when known | as elsewhere |

**Never** logged: arguments, search text, page/post bodies, results. Wikantik's search text stays in its own
`retrieval_query_log`, as today. None of these fields becomes a Loki label.

The free-text lines that exist today (Wikantik `AuditedToolCall`, `WorkMcpInitializer`; Pressroom
`McpToolExecutor` "calls" logger) may stay for grep, but the event is the contract.

### 5.2 The agent report store (Wikantik, extraction-ready)

A new module, `wikantik-agent-reports`, shaped like `wikantik-insights`: depends on neither `wikantik-api`
nor `wikantik-main`, holds a `Jdbc`, owns its own tables (`agent_report*`, new migration), and exposes a
service interface. The knowledge-endpoint tool and the admin surfaces are thin adapters over that
service. Nothing else in Wikantik reads its tables. Extraction later = move the module behind HTTP.

**A report** (one per task; small, structured, bounded):

| Field | Notes |
|---|---|
| `id`, `filed_at` | server-assigned |
| `client`, `session_hash` | from the MCP session, same values as the events (server-filled, not agent-claimed) |
| `filed_by` | API-key owner (server-filled) |
| `workspace` | repository or project the agent worked in (agent-supplied, e.g. `jspwiki`, `blogger-agent`) |
| `kind_of_work` | `operations` \| `content` \| `build` \| `research` \| `other` — the primary grouping |
| `products` | list: `wikantik`, `pressroom`, `jakemon`, `stockroom`, … — products the task touched |
| `skills` | list of `name@version` the agent used |
| `window_start`, `window_end` | when the task ran; used to join to events |
| `task` | one-line description (≤ 300 chars) |
| `outcome` | `completed` \| `partial` \| `abandoned` |
| `context_used` | list of `{source, ref, helped: yes\|partly\|no}`; `source` = `wiki` \| `blog` \| `repo` \| `web`; `ref` = page slug / URL / path |
| `context_missing` | free text ≤ 1000: what the agent needed and did not find, or found wrong |
| `friction` | list of `{product, tool, what (≤ 500), blocking: bool}` |
| `notes` | free text ≤ 2000 |

**Subject area is derived, not asked:** the digest maps `context_used` wiki refs to their clusters, so the
three dimensions the owner wants (kind of work, product, subject) are all present without burdening the agent.

Validation: unknown enum values are refused with a reason; over-length text is refused, not truncated;
the server strips nothing silently. Reports are append-only (no edit, no delete through the tool).
Retention: kept indefinitely (volume is tens per week).

### 5.3 Surfaces

- **`file_agent_report`** on `/knowledge-mcp` — the endpoint's single write, accepted under the existing
  `mcp_read` scope. Its description says so. It writes only to the report store, never to pages. It joins
  `wikantik-mcp-instructions.txt`/the knowledge instructions and `McpProtocolIT.EXPECTED_TOOLS` per
  the repository's drift rules. Rate-limited per key.
- **`list_agent_reports`** on the admin MCP (filters: window, kind, product, client, skill, outcome) and a
  matching `/admin/agent-reports` REST resource — what the digest agent and the owner read.
- **Raw reports are never in retrieval or the page corpus.** Only digests are pages.

### 5.4 The reporting skill

One shared skill, `agent-field-report`, usable by Claude Code and Antigravity.

- **Canonical text on the wiki**, page `SimpleAgilityAgentFieldReportSkill`; each repository carries an
  installed copy (`.claude/skills/…` for Claude Code, `skills/…` for Antigravity) stamped with the version
  it was installed from.
- **When:** at the end of every substantive task. Not for one-line lookups.
- **What:** fill the report fields honestly, especially `context_missing` and `friction`; name skills used.
- **Never** put secrets, credentials or personal data in a report.
- The skill does **not** file work requests (non-goal; owner's review only).

### 5.5 The skill registry

Wiki page `SimpleAgilitySkillRegistry`: one row per skill — name, version, purpose, repositories installed
in, date introduced, date of each version change. It is the record that dates "before" and "after", and the
digest and dashboards use it to annotate changes. Updating it is part of installing or changing any skill.

### 5.6 The digest

- A wiki page per period, `AgentActivityDigest<YYYY>W<ww>` (weekly to start), in a new cluster
  `agent-activity` with a hub, written by an agent following the `agent-activity-digest` skill, run when the
  owner asks (a schedule may come later).
- **Inputs:** `list_agent_reports` for the period; event counts from jakemon (Loki via its query API) per
  product, tool, client and outcome; the skill registry.
- **Contents:** volume and mix by kind of work, product and derived subject area; what context helped most and
  what was missing or wrong (with page links); friction grouped by product and tool, blocking first;
  skill changes in the period and the before/after on the affected kinds of work; changes since last period;
  a short list of **candidate requests** for the owner. The owner decides which become work items.
- Visibility: the digest describes internal operations. It should be readable by the agents that need it
  and by the owner, not necessarily by the public (builder to settle; see §8).

### 5.7 jakemon dashboards

A suite "Agent activity" dashboard reading `event.action="mcp_tool_called"` across `service.name`:
calls and failure rate per product/server/tool, latency and response size per tool, client mix, sessions
per day, tools never called in the period, and skill-registry dates as annotations. jakemon's existing
two "by tool" panels move to the new field names.

### 5.8 The improvement loop

Owner reads digest + dashboards → owner (or an agent at the owner's direction) files work items in Wikantik
work management against the right product, linking the reports and digest behind them → agents build them
through the normal work flow (autonomy gates unchanged).

## 6. Architectural guidance changes (the wiki)

- New normative page **`SimpleAgilityAgentActivity`**: this contract — the event, the report, the skill,
  the registry, the digest, the loop. Linked from `SimpleAgilityStackHub`.
- **`SimpleAgilityTelemetryContract`**: add `mcp_tool_called` to the events section with the field table.
- **`SimpleAgilityObservabilityPlane`**: note that agent tool-call events are plane data and agent reports
  are product data (Wikantik), with the reason.
- **`SimpleAgilityDecisionLog`**: new SA entries — (1) the `mcp_tool_called` event and field names
  (superseding jakemon's request shape); (2) agent reports live in Wikantik behind a knowledge-endpoint
  write exception, built for extraction; (3) requests come only from owner review.
- **`SimpleAgilityAgentCoordination`**: a new initiative listing each repository's adoption step.
- **Standard Repository Agent Header** (`SimpleAgilityAgentCoordination` §2): add "file a field report at
  the end of substantive work (skill `agent-field-report`)", so every repository's agents inherit it.

## 7. Per-repository work

**This work builds Wikantik only** (the code and the wiki pages of §6). Every other repository gets a
request, through `SimpleAgilityAgentCoordination` and a work item, built by its own agent.

| Repo | Work beyond installing the skill | Who |
|---|---|---|
| Wikantik | §5.1 events on knowledge/admin/work MCP; §5.2–5.3 module, migration, tools, admin resource; skills installed; §6 wiki pages; `agent-activity-digest` skill | **this work** |
| jakemon | §5.7 dashboard (JSON); move its two existing "by tool" panels to the new field names. Possibly a read path for the digest agent to Loki (§8) | requested |
| Pressroom | §5.1 event from `McpToolExecutor` (small code change: one ECS line per call, with client name and session hash) | requested |
| blogger-agent (Antigravity) | none — skill install + header | requested |
| wiki-content-agent, backoffice | none — skill install + header | requested |
| stockroom | none today (no MCP server); its future MCP server must emit §5.1 from day one | requested (header + contract) |

## 8. Left to the builder (marked calls)

- Hash algorithm and length for `mcp.session.hash` (my call: first 12 hex chars of SHA-256, keyed by a
  per-deployment salt so hashes don't correlate across products unless the same session). *Builder may
  simplify to an unsalted hash if cross-product correlation is wanted — check with owner.*
- How jakemon's Loki is queried by the digest agent (direct Loki HTTP API vs jakemon's planned agent-facing
  query server).
- Whether `agent-activity` is a private cluster, and how private clusters interact with knowledge-MCP keys.
- Exact skill install mechanism per agent (copy script vs. instruction in the header).
- Rate limit numbers for `file_agent_report`.

## 8a. Settled after the builder check (designer's calls; owner may overturn)

1. **Server-filled identity.** Tool handlers already receive the `McpSyncServerExchange`
   (`AuditedToolCall.run` reads the client name from it). `file_agent_report` takes `client` and the session
   id from the same exchange and hashes it with the same helper as the events. If the session id is not
   reachable from the exchange in the SDK version in use, a small `wikantik-mcp-core` change comes first;
   agent-supplied identity is never the fallback.
2. **Joining reports to events.** Exact on `session_hash` within a product; `window_start/end` only narrows
   a session that ran several tasks. `session_hash` is indexed.
3. **Hash salt is per product, no shared secret.** MCP session ids are minted per connection by each
   server, so hashes could never correlate across products anyway. Cross-product joining is the report's
   job (`products`, the window); no secret crosses a repository.
4. **Order.** The SA entries and `SimpleAgilityAgentActivity` land first, as part of the Wikantik work; they
   are the contract the requests link to (requests link the page, not a copied field table). Wikantik
   ships without waiting for Pressroom or jakemon.
5. **Changeover is a hard cut.** jakemon's two "by tool" panels read fields Wikantik never emitted (they
   are empty today), so there is nothing to keep alive.
6. **Event coverage.** All three Wikantik MCP servers (knowledge, admin, work), at the dispatcher: every
   call that reaches a tool, including failures and tool-level refusals. Auth/rate-limit refusals in the
   servlet filters stay in `SecurityLog` and are out of scope. `/tools/*` is not MCP and keeps its
   `wikantik_tools_*` metrics.
7. **Rate limit** is per API key; agents sharing one key share its budget (acceptable at current volume).
8. **The digest agent needs no work credential.** It writes candidate requests into the digest; the owner
   files work items (§2 non-goal).
9. **Skill versions** come from a `version:` line in each `SKILL.md` frontmatter; the installer stamps it;
   the digest compares reported versions with the registry and lists mismatches. No automated install
   check in this pass.

## 9. Later (explicitly deferred)

- Server-side cross-check: reformulation and read-after-search patterns from `retrieval_query_log` + events.
- A scheduled digest run.
- A skill that lets agents file blocking-friction requests directly.
- Extracting the report store into its own service.
