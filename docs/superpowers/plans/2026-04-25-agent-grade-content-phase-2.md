# Agent-Grade Content — Phase 2 Implementation Plan

> **Status:** Implemented (commits `4cb903b2b..34d2fbf78` on `main`).

**Goal:** Ship a token-budgeted page projection — `GET /api/pages/for-agent/{canonical_id}` and a matching `get_page_for_agent` MCP tool on `/knowledge-mcp` — that bundles the agent-useful subset of a page (summary, key facts, headings outline, typed relations, recent changes, MCP tool hints, verification state) into a single response shape. Authors keep writing narrative articles; agents read this projection.

**Design source:** [docs/wikantik-pages/AgentGradeContentDesign.md](../../wikantik-pages/AgentGradeContentDesign.md)
("Phase 2 — `/for-agent` projection").

**Architecture:**

- A new value type `ForAgentProjection` (record + sub-records) lives in `wikantik-api`. Per-field graceful degradation is encoded on the type: a `degraded` flag and a `missingFields` list, populated by the service when individual extractors throw.
- `ForAgentProjectionService` is a small interface in `wikantik-api`; `DefaultForAgentProjectionService` in `wikantik-main` composes four extractors (`HeadingsOutlineExtractor`, `KeyFactsExtractor`, `RecentChangesAdapter`, `McpToolHintsResolver`), each of which is a unit-tested pure function over the page's parsed frontmatter / body / version history.
- The service reads structural data from `StructuralIndexService` (verification, typed relations, `PageDescriptor`) and page body from `PageManager`. **No new datastore.**
- Memoisation uses the existing `CachingManager`. A new cache alias `wikantik.forAgentCache` (1-hour TTL, 5K entries) keys entries by `(canonicalId, updatedAtMillis)`. Page updates produce a new `updatedAtMillis`, so stale entries are bypassed on read and naturally evicted on heap pressure — no event listener required.
- Two adapters surface the projection: `PageForAgentResource` at `/api/pages/for-agent/*` (servlet) and `GetPageForAgentTool` on `/knowledge-mcp` (MCP). Both call the same service and serialise the same shape.
- A Prometheus histogram `wikantik_for_agent_response_bytes` records every projection's serialised size so an operator can see the budget distribution.

**Tech stack delta:** None. Reuses Phase 1's `Verification` / `Confidence` / `Audience` types, the existing `StructuralIndexService` interface (already exposes `verificationOf`, `outgoingRelations`, `incomingRelations`, `getByCanonicalId`), `FrontmatterParser`, `PageManager.getVersionHistory`, `CachingManager`, and `MeterRegistryHolder`.

---

## What ships

| Layer | Class / endpoint | Behaviour |
|-------|------------------|-----------|
| Value types | `ForAgentProjection`, `HeadingOutline`, `RecentChange`, `McpToolHint`, `KeyFact` (records) | Wire-form fields + degraded flag + missingFields list |
| Service interface | `ForAgentProjectionService.project(canonicalId)` | Returns `Optional<ForAgentProjection>` (empty when canonical_id unknown) |
| Service impl | `DefaultForAgentProjectionService` | Composes four extractors with per-field try / catch, records failures on `missingFields` and toggles `degraded`. Memoised. Always non-throwing. |
| Extractor | `HeadingsOutlineExtractor` | Parses `#`-prefixed Markdown lines into `[{level, text}]`; skips h1; ignores fenced code; caps at 32 entries |
| Extractor | `KeyFactsExtractor` | Frontmatter-authored `key_facts` pass-through; otherwise heuristic from first 3 paragraphs (verb + named-entity-or-number); 6-fact cap, 240-char-per-fact cap |
| Extractor | `RecentChangesAdapter` | Reads `PageManager.getVersionHistory`, returns the most recent N versions descending; tolerates null / throwing provider |
| Extractor | `McpToolHintsResolver` | Frontmatter `mcp_tool_hints` pass-through; otherwise synthesises hints from tags + cluster (`search_knowledge` / `list_pages_by_filter`); 5-hint cap |
| Cache | `wikantik.forAgentCache` (1h TTL, 5K entries) | Memoised by `(canonicalId, updatedAtMillis)` |
| Cache constant | `CachingManager.CACHE_FOR_AGENT` | Public alias name |
| Metric | `wikantik_for_agent_response_bytes` (DistributionSummary) | Records serialised size per projection |
| Engine wiring | `WikiEngine.initKnowledgeGraph()` | Registers `ForAgentProjectionService` as a manager |
| MCP wiring | `KnowledgeMcpInitializer` | Adds `GetPageForAgentTool` when `ForAgentProjectionService` is present |
| REST | `GET /api/pages/for-agent/{canonical_id}` | Public-readable; uses a private Gson with `serializeNulls()` so `runbook: null` survives the wire |
| MCP tool | `get_page_for_agent` on `/knowledge-mcp` | Same payload, accepts `canonical_id` argument |
| Web | `wikantik-war/src/main/webapp/WEB-INF/web.xml` | New servlet + mapping at `/api/pages/for-agent/*` |
| Cache config | `wikantik-cache/src/main/resources/ehcache-wikantik.xml` | New `<cache alias="wikantik.forAgentCache">` block (1h TTL, 5K entries) |

---

## Task ledger (executed)

| # | Task | Commit |
|---|------|--------|
| P2-T1 | `ForAgentProjection` + sub-records + `ForAgentProjectionService` interface in `wikantik-api` | `4cb903b2b` |
| P2-T2 | `HeadingsOutlineExtractor` + 5 unit tests | `21cd97db4` |
| P2-T3 | `KeyFactsExtractor` + 7 unit tests | `989183f86` |
| P2-T4 | `RecentChangesAdapter` + 4 unit tests | `dd4584e5c` |
| P2-T5 | `McpToolHintsResolver` + 5 unit tests | `e0793a8a3` |
| P2-T6 | `wikantik.forAgentCache` alias + `CACHE_FOR_AGENT` constant + `ForAgentMetrics` + bumped `EhcacheCachingManagerTest` count from 7 to 8 | `92965990d` |
| P2-T7 | `DefaultForAgentProjectionService` + 5 unit tests + `WikiEngine` registration | `6ea82315d` |
| P2-T8 | `PageForAgentResource` + 4 unit tests + servlet registration in web.xml | `965bc5a8b` |
| P2-T9 | `GetPageForAgentTool` + 4 unit tests + `KnowledgeMcpInitializer` wiring | `34d2fbf78` |
| P2-T10 | This doc + CLAUDE.md note + final build | (this commit) |

Total commits: 9 functional + 1 doc.
Total new tests: **34** (5 `HeadingsOutlineExtractorTest`, 7 `KeyFactsExtractorTest`,
4 `RecentChangesAdapterTest`, 5 `McpToolHintsResolverTest`,
5 `DefaultForAgentProjectionServiceTest`, 4 `PageForAgentResourceTest`,
4 `GetPageForAgentToolTest`).

---

## Material deviations from the design doc

1. **URL pattern: `/api/pages/for-agent/{id}` instead of `/api/pages/{id}/for-agent`.**
   The design doc named the URL `/api/pages/{canonical_id}/for-agent`,
   but that collides with `PageResource`'s `/api/pages/*` mapping
   (the Servlet API doesn't support tail-segment patterns — only
   prefix). The endpoint is registered at `/api/pages/for-agent/*`
   instead, mirroring the convention from Structural Spine Phase 1's
   `/api/pages/by-id/{canonical_id}`. Same shape, different prefix
   order.

2. **`runbook` ships as a stable `null` in the contract.** Phase 3
   introduces the `runbook:` block; the field is present in the
   wire shape now so Phase 3 can land without an API change. To
   keep the literal `"runbook": null` in the JSON output, the REST
   resource carries its own `Gson` instance configured with
   `serializeNulls()` — the parent's GSON drops nulls and would
   silently elide the field.

3. **Byte-size metric is recorded on the build path, not the
   serialise path.** The design doc placed
   `wikantik_for_agent_response_bytes` "on every projection's
   serialised size". The implementation records an estimate during
   `DefaultForAgentProjectionService.recordMetric` based on the
   variable-length fields of the projection. This means cached-only
   callers and live-build callers both contribute samples, and the
   metric does not depend on which serializer (REST GSON, MCP GSON)
   ran. A future tightening can swap the estimate for the actual
   serialised byte count from the resource layer if percentile-99
   diverges from the estimate.

4. **`KeyFact.text` includes non-string frontmatter entries via
   `toString()` rather than skipping them.** When frontmatter
   contains `key_facts: [..., 42, ...]`, the integer is surfaced
   as the string `"42"` rather than dropped. The design doc didn't
   prescribe behaviour here; `toString()` is the path of least
   surprise for an author who accidentally typed a literal value
   (likely they intended a string).

5. **Cache test bumped from 7 to 8 caches.** Adding `CACHE_FOR_AGENT`
   to the bootstrap registration list bumps `cacheMap.size()` past
   the prior `assertEquals(7, …)` baseline. The test was updated to
   `8` and the post-`registerCache` baseline to `9`.

---

## Out of scope, explicitly deferred

| Item | Why deferred |
|------|--------------|
| `runbook:` block validator + serialisation | Phase 3 of the design — owns its own data model and authoring workflow |
| Frontend admin panel for `/for-agent` debugging | The endpoint is the gate; operators can curl |
| On-demand cache invalidation endpoint | The `(canonical_id, updated_at)` key implicitly invalidates on save; operators rarely need to flush |
| Stable `key_facts` cache by content hash | The for-agent cache already memoises the whole projection; an inner key-facts cache adds complexity for little gain |
| Per-page projection size cap with truncation | Budget tracked via histogram for now; truncation can come if real-world percentile-99 exceeds 8 KB |
| Embedding-aware similarity hints in `mcp_tool_hints` | `find_similar` already exists as a separate MCP tool; for-agent doesn't need to duplicate that surface |
| Replace estimated byte-size metric with serialised-size measurement | Estimate is good enough until p99 diverges from observed reality |

---

## Verification

`mvn clean install -T 1C -DskipITs` is green across all 26 modules
(BUILD SUCCESS, ~3:28 wall clock).

Targeted module test runs:
- `mvn test -pl wikantik-main -Dtest='HeadingsOutlineExtractorTest,KeyFactsExtractorTest,RecentChangesAdapterTest,McpToolHintsResolverTest,DefaultForAgentProjectionServiceTest' -Dsurefire.failIfNoSpecifiedTests=false` — 26 tests, all green.
- `mvn test -pl wikantik-rest -Dtest=PageForAgentResourceTest -Dsurefire.failIfNoSpecifiedTests=false` — 4 tests, all green.
- `mvn test -pl wikantik-knowledge -Dtest=GetPageForAgentToolTest -Dsurefire.failIfNoSpecifiedTests=false` — 4 tests, all green.
- `mvn test -pl wikantik-cache` — 14 tests (count bump), all green.
- `mvn test -pl wikantik-admin-mcp -Dtest=McpToolRegistryTest -Dsurefire.failIfNoSpecifiedTests=false` — 8 tests, unaffected by the new knowledge-side tool, all green.

Manual smoke (after WAR redeploy):

```bash
ID=$( curl -s http://localhost:8080/api/structure/sitemap | jq -r '.data.pages[0].id' )
curl -s "http://localhost:8080/api/pages/for-agent/$ID" \
  | jq '.data | { id, slug, confidence, key_facts: (.key_facts | length), headings: (.headings_outline | length), degraded }'
curl -s http://localhost:8080/metrics | grep '^wikantik_for_agent_response_bytes' | head
```

Net effect: agents calling `/knowledge-mcp` can now ask
`get_page_for_agent` for a token-budgeted projection of any page,
complete with verification state, key facts, headings outline, typed
relations, recent changes, and MCP tool hints — without pulling the
full markdown body. The retrieval surface for agents is now
*structural* (Phase 1), *verified* (Agent-Grade Phase 1), and
*projection-shaped* (this phase).

---

## Authoring workflow (post-Phase 2)

1. **Read the projection over REST** (no auth required for read):
   ```bash
   curl -s http://localhost:8080/api/pages/for-agent/01ABC… | jq
   ```

2. **Read it over MCP** from any agent:
   ```json
   { "tool": "get_page_for_agent",
     "arguments": { "canonical_id": "01ABC…" } }
   ```

3. **Author `key_facts:`** in frontmatter when you want stable
   facts surfaced:
   ```yaml
   ---
   title: Hybrid Retrieval
   key_facts:
     - Retrieval fuses BM25 and dense embeddings via RRF (k=60).
     - Falls back to BM25 when the embedding service is unavailable.
   ---
   ```

4. **Author `mcp_tool_hints:`** when you want explicit pointers
   (especially for runbooks once Phase 3 lands):
   ```yaml
   ---
   mcp_tool_hints:
     - tool: /knowledge-mcp/search_knowledge
       when: When the agent needs to find adjacent pages by topic
     - tool: /knowledge-mcp/get_page_by_id
       when: After search_knowledge — fetch the canonical descriptor
   ---
   ```

5. **Watch the histogram** to confirm new pages stay under budget:
   ```bash
   curl -s http://localhost:8080/metrics | grep wikantik_for_agent_response_bytes
   ```

---

## Next phases (Agent-Grade Content)

- **Phase 3 — Runbook page type:** structured procedural pages with
  schema-validated `runbook:` block (`when_to_use`, `inputs`, `steps`,
  `pitfalls`, `related_tools`, `references`); consumed by the
  `/for-agent` projection's now-empty `runbook` field.
- **Phase 4 — Agent cookbook authoring:** ~15 seed runbooks for the
  scenarios coding agents actually hit.
- **Phase 5 — Retrieval-quality CI:** scheduled `RetrievalQualityRunner`
  with Prometheus dashboards.
- **Phase 6 — Tool-description examples:** worked input/output examples
  on every MCP tool's JSON schema.

When ready for Phase 3, ask and I'll write its plan from the design
doc's "Phase 3 — Runbook type" section.
