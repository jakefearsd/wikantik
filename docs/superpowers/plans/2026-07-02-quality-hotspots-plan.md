# Quality Hotspots: AdminKnowledgeResource + DCRS Decomposition + Complexity Ratchet

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Decompose the two measured worst classes in critical areas — `AdminKnowledgeResource` (WMC 288, 1666 LOC, admin KG write surface) and `DefaultContextRetrievalService` (WMC 141, 644 LOC, retrieval core) — via behavior-preserving extract-class refactors, then install a CI complexity ratchet so hotspots can only shrink (the WikiEngine 1909→2337 and post-split regrowth lesson: splits without tripwires regress).

**Architecture:** Pure extract-class. `AdminKnowledgeResource` keeps its servlet entry + existing `Resource` dispatch table; handler-method groups move verbatim into package-scoped handler classes under `com.wikantik.rest.knowledge`. `DefaultContextRetrievalService` keeps `retrieve()` orchestration + page projection; related-pages, page-listing, and contributing-chunk assembly move to collaborators. The ratchet is a `complexity-gate` Maven profile (`pmd:check` + `build-support/pmd-complexity-ruleset.xml` + a baseline exclude file) run as a CI step — new/worsened-into-scope violations fail, baseline entries burn down monotonically.

**Tech Stack:** Java 21, Maven, JUnit 5 + Mockito, maven-pmd-plugin 3.28 (PMD 7.x), GitHub Actions.

## Global Constraints

- **Behavior-preserving refactors ONLY.** Method bodies move VERBATIM (imports/visibility adjusted). No logic, ordering, wire-format, or log-message changes. The existing test suites are the characterization net and MUST pass unmodified except for imports/construction plumbing:
  - AdminKnowledgeResource: `AdminKnowledgeResourceTest` (9), `...MockTest` (55), `...HandlerCoverageTest` (36), `...BulkTest` (14), `...EdgeCurationTest` (8), `...JudgeTest` (9), `...JudgeStatusTest` (4) = 135 tests in wikantik-rest.
  - DCRS: `DefaultContextRetrievalServiceTest`, `ContextRetrievalRecordsTest`, `ContextRetrievalServiceInitializerTest` + the whole wikantik-knowledge suite (188 tests).
- **Read `docs/wikantik-pages/HybridRetrieval.md` before Task 4** (CLAUDE.md rule for the retrieval subsystem). DCRS extraction must not touch retrieval semantics — the recall numbers there are eval-gated.
- Wire contract of every `/admin/knowledge-graph/*` endpoint is FROZEN: same paths, verbs, status codes, JSON shapes, error strings.
- New handler classes are package-private final, in `com.wikantik.rest.knowledge`; no new public API surface in wikantik-rest.
- No new dependencies. No `git add -A`. Commit per task with the trailer:
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` + `Claude-Session: https://claude.ai/code/session_01GasDrNqpAq1TmmRkZKwYfr`
- Focused test command per task; one full-reactor `mvn clean install -DskipTests -T 1C` + targeted module test pass at the end (full IT reactor exceeds this environment's wall budget; existing REST/knowledge unit suites are the gate).

## File Structure

- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java` (1666 → target ≤ 400 LOC: table + dispatch + shared IO helpers)
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/knowledge/` — `KgNodeAdminHandlers.java`, `KgEdgeAdminHandlers.java`, `KgProposalAdminHandlers.java`, `KgJudgeAdminHandlers.java`, `HubProposalAdminHandlers.java`, `KgMaintenanceAdminHandlers.java`
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java` (644 → target ≤ 350 LOC)
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/retrieval/` — `RelatedPagesFinder.java`, `PageListEngine.java`, `ContributingChunkAssembler.java` (+ focused unit tests for each)
- Create: `build-support/pmd-complexity-baseline.properties`; Modify: `pom.xml` (complexity-gate profile), `.github/workflows/ci-cd.yml` (gate step), `CLAUDE.md` (one line in Code Quality)

---

## Task 1: Extract proposal + judge handlers from AdminKnowledgeResource

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java`
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/knowledge/KgProposalAdminHandlers.java`
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/knowledge/KgJudgeAdminHandlers.java`

**Interfaces:**
- Consumes: the resource's existing private helpers. First inventory them (`sendJson`/`sendError`/`sendNotFound` come from `RestServletBase`; `parseUuid(String,HttpServletResponse)`, `actor(HttpServletRequest)`, `parseIntParam`, body-reading helpers are local). Widen the local helpers the moved code needs to `static` package-visible methods on a new tiny `com.wikantik.rest.knowledge.AdminKnowledgeIo` (move them verbatim; the resource delegates to them so nothing else changes).
- Produces: `KgProposalAdminHandlers` and `KgJudgeAdminHandlers` — package-private final classes whose public-to-package methods take exactly what the current handler methods take (typically `(KnowledgeGraphService service, HttpServletRequest request, HttpServletResponse response, String[] segments)`), plus constructor-injected collaborators the bodies already reach for (e.g. the engine accessor the resource uses to fetch repos). Signatures must let the `Resource` table entries in AdminKnowledgeResource swap from `this::handleX` to `proposalHandlers::handleX` with no other edit.

- [ ] **Step 1: Inventory the move set.** In AdminKnowledgeResource, list every method whose name involves proposals (approve/reject/judge/bulk — includes `doBulkProposalAction`, the case-arms at ~490–650) and the judge trio (`handleGetJudge`, `handlePostJudge`, `handleDeleteJudgeTimeout`). Record each signature and every private field/helper it touches. Expected: ~10–14 methods.
- [ ] **Step 2: Create `AdminKnowledgeIo`** with the shared helpers (verbatim move; resource delegates). Run: `mvn -pl wikantik-rest test -Dtest='AdminKnowledge*' -q` → 135 tests green.
- [ ] **Step 3: Move the proposal group** into `KgProposalAdminHandlers` (bodies verbatim; construct it in the resource where the dispatch table is built; table entries point at it). Compile + run the same 135.
- [ ] **Step 4: Move the judge group** into `KgJudgeAdminHandlers`, same recipe. Run the 135.
- [ ] **Step 5: Commit** — `refactor(rest): extract KG proposal + judge admin handlers from AdminKnowledgeResource`

## Task 2: Extract node + edge handlers

**Files:** Modify `AdminKnowledgeResource.java`; Create `KgNodeAdminHandlers.java`, `KgEdgeAdminHandlers.java` (same package/pattern as Task 1).

- [ ] **Step 1:** Move the node group (`handleGetNodes` incl. by-id/mentions/similar branches, `handleGetSimilarNodes`, node curation POST/DELETE handlers) into `KgNodeAdminHandlers`.
- [ ] **Step 2:** Move the edge group (edge list/by-id/audit at ~360–382, `handlePostEdgeUpsert` + its before-state audit block at ~792–840, edge delete) into `KgEdgeAdminHandlers`. The best-effort audit write moves with it VERBATIM — that block is audit-coverage-sensitive.
- [ ] **Step 3:** `mvn -pl wikantik-rest test -Dtest='AdminKnowledge*' -q` → 135 green. Commit — `refactor(rest): extract KG node + edge admin handlers`.

## Task 3: Extract hub-proposal + maintenance handlers; slim the resource

**Files:** Modify `AdminKnowledgeResource.java`; Create `HubProposalAdminHandlers.java`, `KgMaintenanceAdminHandlers.java`.

- [ ] **Step 1:** Move the hub-proposal group (`handleGetHubProposals`, `handlePostHubProposals` + generate/bulk-approve/bulk-reject/threshold-approve/by-id-action methods, ~1261–1425) into `HubProposalAdminHandlers`.
- [ ] **Step 2:** Move maintenance (`handleGetPagesWithoutFrontmatter`, `handleGetEmbeddings`, `handlePostEmbeddings`, `handleGetBackfillStatus`, `handlePostBackfillFrontmatter`, `handlePostSyncHubMemberships`) into `KgMaintenanceAdminHandlers`.
- [ ] **Step 3:** Verify the residual resource is table + dispatch + construction only. Target ≤ 400 LOC; report the final number. Run the 135 + `mvn -pl wikantik-rest test -q` (full module, 1073).
- [ ] **Step 4:** Commit — `refactor(rest): extract hub-proposal + maintenance handlers; AdminKnowledgeResource is now dispatch-only`.

## Task 4: Decompose DefaultContextRetrievalService

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java`
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/retrieval/RelatedPagesFinder.java` — takes `MentionIndex`; receives `fetchRelatedPages`, `fetchRelatedPagesBatch`, `describeSharedEntities`, `RELATED_PAGES_LIMIT`, `REASON_ENTITY_LIMIT` verbatim.
- Create: `.../retrieval/PageListEngine.java` — takes `PageManager`, `FrontmatterMetadataCache` (+ whatever `metadataFor` needs); receives `listPages`, `matchesFilter`, and the coercion helpers (`stringOrEmpty`, `stringOrNull`, `stringList`) verbatim.
- Create: `.../retrieval/ContributingChunkAssembler.java` — takes `ChunkVectorIndex`, `ContentChunkRepository`; receives `fetchContributingChunks`, `interestingPageNames`, `groupChunksByInterestingPage`, `shapeChunkOutput` verbatim.
- Test: one focused unit-test class per new collaborator (reuse `testfakes/` fakes — `FakeDeps`, `FakePageManager`, `FakeMentionIndex` already exist).

**PRE-READ (mandatory):** `docs/wikantik-pages/HybridRetrieval.md`. No changes to `retrieve()` ordering, `applyHybridAndGraphRerank`, `expandWithOntology`, or any scoring/dedup behavior.

- [ ] **Step 1:** Read DCRS fully; confirm the three groups have no hidden coupling into `retrieve()` beyond constructor fields. If `metadataFor` is shared between `getPage` and `listPages`, keep ONE copy (in `PageListEngine`) and have DCRS call it — do not duplicate.
- [ ] **Step 2:** Extract the three collaborators one at a time, running `mvn -pl wikantik-knowledge test -q` (188) after each.
- [ ] **Step 3:** Add the three focused unit-test classes (happy path + 1–2 edge cases each, using the existing fakes — real behavior, not mock-echo).
- [ ] **Step 4:** Report DCRS's final LOC (target ≤ 350). Commit — `refactor(knowledge): extract RelatedPagesFinder, PageListEngine, ContributingChunkAssembler from DCRS`.

## Task 5: Complexity ratchet (CI gate)

**Files:** Create `build-support/pmd-complexity-baseline.properties`; Modify root `pom.xml`, `.github/workflows/ci-cd.yml`, `CLAUDE.md`.

- [ ] **Step 1: Add the `complexity-gate` profile** to root pom.xml (next to the existing `complexity-report` profile), configuring maven-pmd-plugin with: the complexity ruleset, `<failOnViolation>true</failOnViolation>`, `<excludeFromFailureFile>${maven.multiModuleProjectDirectory}/build-support/pmd-complexity-baseline.properties</excludeFromFailureFile>`, `<printFailingErrors>true</printFailingErrors>`, `<includeTests>false</includeTests>`, `<aggregate>false</aggregate>`.
- [ ] **Step 2: Generate the baseline** AFTER Tasks 1–4 land (so the fixed classes start clean): run `mvn pmd:check -Pcomplexity-gate -q` reactor-wide, collect failures, and write the exclude file. Format (maven-pmd-plugin): one line per class, `com.example.ClassName=Rule1,Rule2` (verify the exact accepted format against the plugin's behavior empirically — if `=RuleList` filtering is unsupported in this version, fall back to bare-FQCN lines, which exclude the class from all failure rules, and note it in the file header).
- [ ] **Step 3: Meaningfulness check (do not skip).** Remove one known-violating class's entry from the baseline → `mvn pmd:check -Pcomplexity-gate -pl <its module>` MUST fail citing it → restore the entry → passes. This is the R-5 lesson: a gate that can't fail is decoration.
- [ ] **Step 4: CI step** in `ci-cd.yml` after the test step: `run: mvn -B -T 1C pmd:check -Pcomplexity-gate`. Header comment: what it is, how to burn down (shrink the class, delete its line), and that adding NEW lines requires justification in review.
- [ ] **Step 5: CLAUDE.md** — add one line under Code Quality: `mvn pmd:check -Pcomplexity-gate` (CI-enforced complexity ratchet; baseline in build-support/pmd-complexity-baseline.properties, entries only come out).
- [ ] **Step 6:** Commit — `build(quality): CI complexity ratchet — pmd:check gate with burn-down baseline`.

## Task 6: Final verification

- [ ] `mvn clean install -DskipTests -T 1C` → BUILD SUCCESS.
- [ ] `mvn test -pl wikantik-rest,wikantik-knowledge -q` → all green (1073 + 188).
- [ ] `mvn pmd:check -Pcomplexity-gate -T 1C` → passes on the clean tree.
- [ ] Re-run the coupling/complexity measurement on the two refactored classes; record before/after (AdminKnowledgeResource WMC 288/1666 LOC → ?, DCRS WMC 141/644 LOC → ?) in the final commit message or ledger.

## Self-Review Notes
- Spec coverage: both approved items (resource decomposition; DCRS decomposition + ratchet) have tasks; DKGS deliberately excluded — it is a thin facade over repositories (regrowth = interface surface, not drift) and the ratchet covers it going forward.
- No placeholders; move-sets are named by method with line anchors; formats to verify empirically are explicitly flagged (excludeFromFailureFile format, Step 5.2).
- Type consistency: handler class names used identically across Tasks 1–3; collaborator names identical across Task 4 steps.
- The 135/188 characterization suites are named per task; every task ends green + committed.
