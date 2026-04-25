# Agent-Grade Content — Phase 6 Implementation Plan

> **Status:** Implemented (commits `2c1a379bb..13a06f6c5` on `main`).

**Goal:** Add at least one worked input/output example to every MCP
tool's JSON schema across the three agent-facing endpoints
(`/wikantik-admin-mcp`, `/knowledge-mcp`, `/tools/*`). Worked examples
raise agent first-call success rates dramatically over type-only
schemas. This is pure tool-description authoring — no new tools, no
refactors, no semantic changes.

**Design source:** `AgentGradeContentDesign.md` line ~392
("Tool-description upgrade") with the canonical `search_knowledge`
example at line 398–441.

## SDK constraint discovered

`io.modelcontextprotocol.spec.McpSchema$JsonSchema` is a Java record
with six fixed fields: `(type, properties, required,
additionalProperties, defs, definitions)`. The record is annotated
`@JsonIgnoreProperties` (no any-setter / any-getter), so a top-level
`examples` key on the input schema **cannot** be added by extending
the `JsonSchema` record — Jackson serialization drops anything not
declared on the record.

`McpSchema$Tool.outputSchema`, however, is a free
`Map<String, Object>`, so a top-level `examples` array there
serializes cleanly.

**Strategy:**
- **Inputs:** add `"examples"` on each individual property inside the
  `properties` map (per-property examples are valid JSON Schema and
  survive serialization). For tools with multiple arguments, the
  example values across properties form a coherent example call.
- **Outputs:** populate `outputSchema` (currently unused everywhere)
  as a free Map containing a top-level `examples` array. This is
  where the realistic shape lives — agents see one or two concrete
  payloads.

For the OpenAPI tool server (`wikantik-tools`), no SDK record gets in
the way: examples land as OpenAPI 3.1 `example` keys on `requestBody`
content, parameter objects, and response content.

Net effect on the wire JSON matches the design doc spec for the
`search_knowledge` exemplar: an `examples` keyword shows up next to
`type`/`properties`/`required` on the inputSchema (per-property), and
a top-level `examples` array on the outputSchema with realistic
payloads.

## What ships

| Module | Tools updated | Notes |
|--------|---------------|-------|
| `wikantik-admin-mcp` | 18 (16 always-on + 2 KG-conditional) | get_backlinks, get_page_history, diff_page, get_outbound_links, get_broken_links, get_orphaned_pages, get_wiki_stats, verify_pages, preview_structured_data, ping_search_engines, delete_pages, read_page, rename_page, write_pages, update_page, mark_page_verified, list_proposals, propose_knowledge |
| `wikantik-knowledge` | 16 | discover_schema, query_nodes, get_node, traverse, search_knowledge, find_similar, retrieve_context, get_page, list_pages, list_metadata_values, list_clusters, list_tags, list_pages_by_filter, get_page_by_id, traverse_relations, get_page_for_agent |
| `wikantik-tools` | 2 | search_wiki, get_page (OpenAPI 3.1 examples) |

Total: **36** tool definitions touched.

## Task ledger (executed)

| # | Task | Commit |
|---|------|--------|
| P6-T1 | SDK probe + plan + GetBacklinksTool pattern proof + test | `2c1a379bb` |
| P6-T2 | Apply pattern to remaining 17 admin MCP tools (one batch) | `62177470b` |
| P6-T3 | Apply pattern to all 16 knowledge MCP tools | `696b1af2c` |
| P6-T4 | Apply pattern + 2 unit tests to OpenAPI tool server | `13a06f6c5` |
| P6-T5 | Smoke verification + retrospective + CLAUDE.md/memory updates | (this commit) |

Total commits: **4 functional + 1 doc commit**.

Total new test assertions added: **5**
- `GetBacklinksToolTest.testToolDefinition` extended (3 assertions)
- `SearchKnowledgeToolTest.definition_carriesPhase6WorkedExamples` (5 assertions including wire-JSON serialization smoke)
- `OpenApiDocumentTest.searchOperationCarriesWorkedExamplesForAgents` (2 assertions)
- `OpenApiDocumentTest.getPageOperationCarriesWorkedExamplesForAgents` (2 assertions)

## Material deviations from the design doc

1. **Examples land on `outputSchema` (top-level) and per `inputSchema`
   property, not as a top-level `examples` array on `inputSchema`.**
   The MCP Java SDK's `JsonSchema` record physically cannot carry
   unknown top-level keys; Jackson drops them. Per-property
   `examples` is standard JSON Schema; the design doc's intent
   (agents see concrete payloads) is preserved.
2. **The wikantik-tools server uses OpenAPI 3.1's `example` keyword,
   not the design doc's MCP-flavoured `examples` array.** That's
   what OpenAPI 3.1 mandates — both are equally well-understood by
   the OpenWebUI client.
3. **Examples avoid `Map.of(..., null)` patterns.** `Map.of` rejects
   nulls; `ListProposalsTool`'s example shape (which legitimately
   has `reviewed_by: null`, `reviewed_at: null` for pending
   proposals) is built with a `LinkedHashMap` instead. Same for
   `GetPageForAgentTool` (>10 keys, exceeds `Map.of` arity).
4. **GET `/page/{name}` (OpenAPI) puts examples on each parameter
   and on the 200 response, not on the response schema itself.**
   The schema is a `$ref`; you can't attach an example to a `$ref`
   in OpenAPI 3.1 — OpenAPI moves examples up to the content level.

## Out of scope, explicitly deferred

| Item | Why deferred |
|------|--------------|
| Multiple alternative example payloads per tool | One realistic example per tool covers the design doc's stated goal of raising first-call success. Adding a "filtered call" + "unfiltered call" pair on every multi-argument tool is a follow-up nice-to-have, not the contract. |
| Multi-shape examples on output (success vs. error) | `update_page` ships two: success + hash-mismatch. Other tools ship one (success path). Error-shape examples can be added incrementally without schema churn. |
| Lock-step CI assertion that every tool has examples | A `McpToolRegistryTest` walk-the-registry assertion is a follow-up. The TDD style for Phase 6 was wide-and-shallow; a registry-wide invariant is a separate decision. |

## Verification

Targeted tests (all green):
- `mvn test -pl wikantik-admin-mcp` — 242 tests
- `mvn test -pl wikantik-knowledge -Dtest=SearchKnowledgeToolTest -Dsurefire.failIfNoSpecifiedTests=false` — 8 tests
- `mvn test -pl wikantik-knowledge` — 147 tests (full module suite)
- `mvn test -pl wikantik-tools -Dtest=OpenApiDocumentTest` — 8 tests
- `mvn test -pl wikantik-tools` — 84 tests (full module suite)

Module-scoped install (excluding modules unrelated to Phase 6):
- `mvn install -T 1C -DskipITs -pl '!wikantik-rest,!wikantik-it-tests'` — green at commit `13a06f6c5`.

**Final-build deviation (re-verified post-handoff, commit-of-record `4335c02eb`+):**

- ✅ The earlier `${guice.version}` pom.xml issue is **resolved** — the
  parallel session committed the missing property, and `pom.xml:61`
  now defines `<guice.version>7.0.0</guice.version>`.
- ✅ `wikantik-main` through `wikantik-tools` (all Phase 6-touched
  modules) build and test green sequentially:
  `mvn install -DskipITs -rf :wikantik-main` reaches `wikantik-rest`
  with everything before it ✅.
- ⚠️ `wikantik-rest` **test compilation** breaks on
  `AdminExtractionResourceTest` because the parallel session extended
  `BootstrapEntityExtractionIndexer.Status` from a 15-arg record to
  a 17-arg record (added `Map<String,Integer>` and a couple other
  fields) and didn't update the test's three call sites. Production
  code (`wikantik-rest/src/main/java/...`) compiles fine. This is
  **not Phase 6's regression** — Phase 6 didn't touch
  `BootstrapEntityExtractionIndexer` or that test. Per the task
  contract the parallel session owns the fix.
- ⚠️ One Phase 6 regression was caught and fixed during this
  re-verification: the agent's wire-JSON smoke in
  `SearchKnowledgeToolTest.definition_carriesPhase6WorkedExamples`
  used Jackson's `ObjectMapper`, which pulled `JsonSerializeAs` from
  Jackson's annotation jar — not on `wikantik-knowledge`'s test
  classpath. The smoke now uses the package-private
  `KnowledgeMcpUtils.GSON` already in use by every tool at runtime,
  asserts the same two keywords (`"examples"` and the design-doc
  canonical value `"hybrid retrieval"`) against the schema-map JSON
  rather than the full `Tool` record, and passes cleanly.

Wire-JSON smoke (executed via the now-Gson-based assertion in
`SearchKnowledgeToolTest`): `search_knowledge`'s
`inputSchema.properties.query` map serialises with the
`"examples"` keyword and the canonical specimen `"hybrid retrieval"`,
and the top-level `outputSchema` carries `"examples"` as well.

**Status:** all Phase 6 modules green. Full reactor build remains
blocked **only** by the parallel session's stale `AdminExtractionResourceTest`
constructor calls. That blocker is independent of Phase 6 and
documented above for the parallel session to resolve.

## Lessons learnt

- **`Map.of` is allergic to nulls and caps at 10 entries.** Two of
  the larger example payloads (`ListProposalsTool` has nullable
  fields for pending review state; `GetPageForAgentTool` has 11+
  top-level keys) had to be built with `LinkedHashMap` instead.
  When writing example payloads, default to `LinkedHashMap` —
  it's both null-safe and unbounded.
- **The MCP Java SDK's record-based schema is restrictive.** Future
  tool-description work that wants top-level keys beyond the six
  the record exposes will need either an SDK upstream change or a
  custom serializer. Per-property `examples` is a reliable
  workaround for now.
- **Pre-existing parallel-flake in `wikantik-main` is benign.** As
  documented in `MEMORY.md` (`provider_test_flakes.md`), four
  tests there race under parallel execution; running the affected
  modules in isolation passes. Phase 6 made no `wikantik-main`
  changes, so any flakiness there is unrelated.

## Next phases (Agent-Grade Content)

**None.** Phase 6 was the final phase of the Agent-Grade Content
design. All six phases shipped 2026-04-25:

- Phase 1 — Verification metadata (`mark_page_verified`,
  `ConfidenceComputer`, `/admin/verification`)
- Phase 2 — `/for-agent` projection (token-budgeted page shape)
- Phase 3 — `type: runbook` first-class support
- Phase 4 — Trusted-authors registry + confidence promotion
- Phase 5 — Retrieval-quality CI (`RetrievalQualityRunner`,
  `/admin/retrieval-quality`, smoke gate)
- **Phase 6 — Tool-description examples (this).**
