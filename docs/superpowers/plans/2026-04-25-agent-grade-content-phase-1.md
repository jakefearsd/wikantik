# Agent-Grade Content — Phase 1 Implementation Plan

> **Status:** Implemented (commits `cef5c7091..7b0563e9b` on `main`).

**Goal:** Make page verification a first-class signal. Authors mark pages as
verified; the rule engine computes a confidence (authoritative / provisional /
stale); operators see the triage list at `/admin/verification`. Phase 2's
`/for-agent` projection will use this as a top-level field that shapes how
agents weight retrieval results.

**Design source:** [docs/wikantik-pages/AgentGradeContentDesign.md](../../wikantik-pages/AgentGradeContentDesign.md)
("Phase 1 — Verification metadata").

**Architecture:**

- Frontmatter gains four optional fields: `verified_at`, `verified_by`,
  `confidence` (typically computed, but author can pin), `audience`.
- `V014` migration adds two tables: `page_verification` (derived projection
  of frontmatter) and `trusted_authors` (small registry of writers whose
  saves are promoted from provisional to authoritative).
- A pure-function `ConfidenceComputer` resolves the effective confidence
  from `verified_at` + verifier identity + an optional explicit override,
  with a 90-day stale window (configurable via
  `wikantik.verification.stale_days`).
- `DefaultStructuralIndexService.rebuild()` reads verification fields from
  every page's frontmatter and persists the computed result via
  `PageVerificationDao`. Synthesised canonical_ids skip persistence —
  same invariant as canonical_ids and relations.
- `mark_page_verified` MCP tool on `/wikantik-admin-mcp` lets an
  author-configurable agent stamp `verified_at = NOW()` and
  `verified_by = <exchange author>` into a batch of pages. The save
  triggers a structural-index rebuild that propagates the change.
- `/admin/verification` admin REST endpoint lists pages with their
  verification state, with `confidence` and `min_days_stale` filters and
  per-confidence aggregate counts.

**Tech stack delta:** None — reuses Phase 1's `FrontmatterParser` /
`FrontmatterWriter` / `PageSaveHelper` / SnakeYAML / `PageManager` /
`StructuralIndexService`.

---

## What ships

| Layer | Class / endpoint | Behaviour |
|-------|------------------|-----------|
| Migration | `V014__verification_and_runbook.sql` | `page_verification` + `trusted_authors` tables; CHECK constraints on confidence/audience enums |
| Value types | `Confidence`, `Audience`, `Verification` | Wire-form parsing and a defensive `unverified()` factory |
| Rule engine | `ConfidenceComputer` | Pure function: explicit override → stale → authoritative (trusted) → provisional |
| DAO | `PageVerificationDao` | upsert / findByCanonicalId / listCanonicalIdsByConfidence / deleteByCanonicalId; H2-tested |
| DAO | `TrustedAuthorsDao` | Cached `contains` + write-through upsert/remove; H2-tested |
| Service | `StructuralIndexService.verificationOf(canonicalId)` | New interface method; default impl delegates to `PageVerificationDao` |
| Service impl | `DefaultStructuralIndexService.rebuild()` | Pass-1 now persists per-page verification; uses `ConfidenceComputer` against the trusted-authors set |
| Engine wiring | `WikiEngine.initKnowledgeGraph()` | Wires `PageVerificationDao`, `TrustedAuthorsDao`, `ConfidenceComputer` (`wikantik.verification.stale_days` configurable) |
| MCP tool | `mark_page_verified` (author-configurable, `/wikantik-admin-mcp`) | Stamps verified_at = NOW(), verified_by = exchange author into frontmatter; optional `confidence` pin |
| REST | `GET /admin/verification?confidence=&min_days_stale=&limit=` | Triage listing with aggregate counts |

---

## Task ledger (executed)

| # | Task | Commit |
|---|------|--------|
| A1-T1 | V014 migration | `cef5c7091` |
| A1-T2 | Confidence / Audience / Verification value types | `d274ea41e` |
| A1-T3 | PageVerificationDao + 5 H2 tests | `544d1617c` |
| A1-T4 | TrustedAuthorsDao + 6 H2 tests | `df7fb342e` |
| A1-T5 | ConfidenceComputer + 8 unit tests (null-safe `verifiedBy`) | `726ad47ba` |
| A1-T6 | `verificationOf()` on the service interface (read path) | `eeeaa3783` |
| A1-T7 | rebuild() persists verification + computes confidence; SnakeYAML Date handling | `b21e5aab9` |
| A1-T8 | `mark_page_verified` MCP tool + test bumps | `ccdaee39b` |
| A1-T9 | `/admin/verification` endpoint + 4 tests + web.xml | `7b0563e9b` |
| A1-T10 | This plan + CLAUDE.md note + final build | (this commit) |

Total commits: 9 functional + 1 doc.
Total new tests: **23** (5 `PageVerificationDaoTest`, 6 `TrustedAuthorsDaoTest`,
8 `ConfidenceComputerTest`, 1 `DefaultStructuralIndexServiceTest`,
4 `AdminVerificationResourceTest`, plus the McpToolRegistryTest assertion bumps).

---

## Material deviations from the design doc

1. **`verificationOf()` on the service instead of an extension on
   `PageDescriptor`.** The design said "pages have verification fields";
   adding fields to the record would have rippled through nine
   construction sites with no real win. A separate read method on
   `StructuralIndexService` keeps the descriptor lean and lets Phase 2's
   `/for-agent` projection join the two surfaces at read time.

2. **New `MarkPageVerifiedTool` instead of extending `VerifyPagesTool`.**
   The design said "extend `VerifyPagesTool`". The existing tool runs
   read-only structural / SEO checks (a different concept); overloading
   it with a write path would conflate two contracts. A sibling tool
   under the same MCP server, registered as author-configurable, is
   cleaner and ships less risk to the existing tool's callers.

3. **`Verification.unverified()` factory.** Pages without a row in
   `page_verification` get a synthetic default record so callers don't
   have to handle `Optional.empty()` everywhere. The factory makes the
   default explicit (provisional confidence, both audiences, no
   timestamp).

4. **`audience` is parsed permissively.** The design left the wire form
   ambiguous (a list vs. a string). The parser accepts both:
   `audience: [humans, agents]` collapses to `humans-and-agents` at the
   data layer. The DB stores the canonical kebab-case form.

---

## Out of scope, explicitly deferred

| Item | Why deferred |
|------|--------------|
| React admin UI for `/admin/verification` | The endpoint is the gate; UI is convenience |
| Auto-verification of AI-edited pages | Belongs in a dedicated trust-flow design |
| Per-type stale windows (e.g. tighter for runbooks) | Start uniform; tune after observing real usage |
| Verification of historical pages via batch | Operator-driven via `mark_page_verified` |
| Trusted-authors admin tool | Single-developer wiki; manual `INSERT INTO trusted_authors` is fine for now |

---

## Verification

`mvn test` is green for all four affected modules (`wikantik-api`,
`wikantik-main`, `wikantik-rest`, `wikantik-admin-mcp`).

Net effect on retrieval: agents that consume the structural index
(via `list_pages_by_filter` and the upcoming `/for-agent` projection)
can now see whether each page has been verified, by whom, and with
what confidence. Phase 2 makes this a first-class signal in the
projection payload.

---

## Authoring workflow (post-Phase 1)

1. **Mark a page verified via MCP** (admin clients only):
   ```json
   {
     "tool": "mark_page_verified",
     "arguments": {
       "pageNames": ["HybridRetrieval", "AgentMemory"],
       "changeNote": "spot-checked for Q2"
     }
   }
   ```

2. **Mark a page known-stale** (without re-reading it):
   ```json
   {
     "tool": "mark_page_verified",
     "arguments": {
       "pageNames": ["LegacyAuthScheme"],
       "confidence": "stale",
       "changeNote": "deprecated by DatabaseBackedPolicyGrants"
     }
   }
   ```

3. **Triage stale pages**:
   ```bash
   curl -u testbot:$PASS \
     'http://localhost:8080/admin/verification?confidence=stale&limit=50'
   ```

4. **Add a trusted author** (DB-direct for now; an MCP tool can come
   later):
   ```sql
   INSERT INTO trusted_authors (login_name, notes)
   VALUES ('alice', 'primary maintainer');
   ```
   The cache refreshes on the next save event or on
   `TrustedAuthorsDao.refresh()`.

---

## Next phases (Agent-Grade Content)

- **Phase 2 — `/for-agent` projection:** the token-budgeted
  `GET /api/pages/{canonical_id}/for-agent` endpoint that bundles
  summary + key facts + headings + typed relations + verification
  state into a single agent-shaped payload.
- **Phase 3 — Runbook page type:** structured procedural pages with
  schema-validated `runbook:` block; consumed by the `/for-agent`
  projection.
- **Phase 4 — Agent cookbook authoring:** ~15 seed runbooks for the
  scenarios coding agents actually hit.
- **Phase 5 — Retrieval-quality CI:** scheduled `RetrievalQualityRunner`
  with Prometheus dashboards.
- **Phase 6 — Tool-description examples:** worked input/output examples
  on every MCP tool's JSON schema.

When ready for Phase 2, ask and I'll write its plan from the design
doc's "Phase 2 — `/for-agent` projection" section.
