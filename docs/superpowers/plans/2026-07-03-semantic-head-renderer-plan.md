# SemanticHeadRenderer Decomposition Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Decompose `SemanticHeadRenderer` (worst NPath in the codebase: 46,080 in `renderHead`, 7,200 in `buildMainJsonLd`; WMC 92, TCC 1.3%, 516 LOC) into a derivation model + two emitters, with **byte-identical output**, deleting its 6-rule ratchet-baseline entry — the first real burn-down of the new complexity gate.

**Architecture:** Two-phase split along the code's existing seam. `PageSeoModel` (package-private record + `from(...)` factory) captures every derived value currently computed at the top of `renderHead` (titles, canonical, description/keywords fallbacks, schemaType, image selection, hub/cluster/related, canonicalId). `HeadTagWriter` emits the `<title>`/`<link>`/meta/OG/Twitter block from the model. `JsonLdEmitter` owns the three JSON-LD builders (+`jsonStr`), with `buildMainJsonLd`'s 13-parameter signature replaced by the model. `SemanticHeadRenderer` keeps its public API (`renderHead` ×2 overloads, `renderBodyFragment`) as thin orchestration — the single caller (`SpaRoutingFilter`, wikantik-rest) is untouched. All new classes stay in `com.wikantik.ui` and are **package-private** (same package as the facade — no visibility widening anywhere).

**Tech Stack:** Java 21, Maven, JUnit 5 (46-test characterization suite `SemanticHeadRendererTest` + `SemanticHeadOntologyAgreementTest`), PMD complexity gate.

## Global Constraints

- **Output must be byte-identical.** This refactor splits method bodies (not whole-method moves), so the 46 characterization tests + the ontology-agreement test are the primary gate: they must pass **unmodified**. Statement order, appended strings, `NL` placement, escaping calls — all preserved exactly. If any test fails, the refactor is wrong, not the test.
- Design-doc context (read first): `docs/superpowers/specs/2026-06-08-wiki-ontology-design.md` Phase 6 — the schema.org `@type` is re-sourced from `NodeTypeMapping.schemaOrgType` (upgrade-only) and the `sameAs` → `/id/page/{canonical_id}` link must survive intact; `SemanticHeadOntologyAgreementTest` pins this. Do NOT re-source anything else from the runtime graph (deliberate prior decision).
- Public API frozen: `SemanticHeadRenderer.renderHead(String,String,String,String)`, `renderHead(String,String,String,String,Date)`, `renderBodyFragment(String,String)` — same signatures, static, public. New collaborators package-private in `com.wikantik.ui`.
- **Born clean:** every new class must have ZERO entries in `build-support/pmd-complexity-baseline.properties`; `com.wikantik.ui.SemanticHeadRenderer=...` (line ~226) must be DELETED entirely (or, if a residual rule genuinely survives, shrunk with a justification comment — target is full deletion).
- No behavior flags, no config, no new deps. Commit per task with trailers:
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` + `Claude-Session: https://claude.ai/code/session_01GasDrNqpAq1TmmRkZKwYfr`

## File Structure

- Modify: `wikantik-main/src/main/java/com/wikantik/ui/SemanticHeadRenderer.java` (516 → target ≤ 150 LOC facade)
- Create: `wikantik-main/src/main/java/com/wikantik/ui/PageSeoModel.java` (record + factory; holds derived values incl. the parsed metadata map for anything emitters still read directly)
- Create: `wikantik-main/src/main/java/com/wikantik/ui/HeadTagWriter.java` (meta/link/OG/Twitter emission; `escAttr` moves here if only emitters use it — follow call sites)
- Create: `wikantik-main/src/main/java/com/wikantik/ui/JsonLdEmitter.java` (`buildMainJsonLd` → model-taking, `buildBreadcrumbJsonLd`, `buildWebSiteJsonLd`, `jsonStr`)
- Modify: `build-support/pmd-complexity-baseline.properties` (delete the SemanticHeadRenderer line + header note documenting the first burn-down)
- Tests: existing suites unmodified; ADD small direct tests for `PageSeoModel.from` derivations (fallback chains: summary>description>generic, title-with-app dedup, image default vs custom, schemaType upgrade-only).

---

## Task 1: Extract `PageSeoModel` (derivation phase)

**Files:** Create `PageSeoModel.java`; Modify `SemanticHeadRenderer.java`; Create `PageSeoModelTest.java`.

**Interfaces (Produces):** package-private `record PageSeoModel(...)` with a static factory `PageSeoModel from( String pageName, String rawPageText, String baseUrl, String appName, Date modified )`. Components (inventory from renderHead lines ~92-125 and the image block ~146-152 — copy the derivations verbatim): `safePageName, safeAppName, safeBaseUrl, canonical, documentTitle, effectiveTitle, effectiveDescription, effectiveKeywords, pageDate (datePublished), cluster, isHub, related, tags, schemaType, canonicalId, imageUrl, hasCustomImage, modified, metadata (the raw Map<String,Object>)`. Include whatever else the emission code reads — do the inventory first; the list above is a floor, not a ceiling. Small helpers used only in derivation (`strOrEmpty`, `dateOrString`, `stringList`, `stripTrailingSlash`, `titleWithApp`) move with it (package-private static on PageSeoModel or kept as its private statics — follow call sites; if emission also uses one, keep a single copy accessible package-privately).

- [ ] **Step 1:** Inventory every derived local in both `renderHead` overloads AND `renderBodyFragment` (line 239 — it re-derives some of the same values; if it can consume the model, thread it through; if its derivations differ, leave it alone this task and note it).
- [ ] **Step 2:** Create the record + factory with derivations copied verbatim (same order, same null-guards). `renderHead` builds the model then uses `model.xxx()` in place of the old locals — the emission block itself does not otherwise change in this task.
- [ ] **Step 3:** `mvn -pl wikantik-main test -Dtest='SemanticHead*' -q` → 47 green, unmodified.
- [ ] **Step 4:** Add `PageSeoModelTest` — direct tests for the fallback chains (summary>description>generic; title-app dedup incl. the "already contains app name" case; custom vs default image; schemaType upgrade-only via NodeTypeMapping; canonical URL shape; null-safety of all inputs). Real assertions on derived values.
- [ ] **Step 5:** Commit — `refactor(ui): extract PageSeoModel derivation from SemanticHeadRenderer`

## Task 2: Extract `JsonLdEmitter` + `HeadTagWriter` (emission phase)

**Files:** Create `JsonLdEmitter.java`, `HeadTagWriter.java`; Modify `SemanticHeadRenderer.java`.

- [ ] **Step 1:** Move `buildMainJsonLd` into `JsonLdEmitter`, changing its signature to `(PageSeoModel model)` — every former parameter is on the model; the BODY's field-emission order and string content stay byte-identical. Move `buildBreadcrumbJsonLd`, `buildWebSiteJsonLd`, `jsonStr` verbatim. Run the 47.
- [ ] **Step 2:** Move the head-tag emission block (title/canonical/description/keywords/robots/OG/Twitter appends, renderHead ~lines 128-238) into `HeadTagWriter.write( StringBuilder sb, PageSeoModel model )` (or returning String — pick whichever preserves the exact concatenation order with the JSON-LD script tags that follow; the final output must be byte-identical). `escAttr` moves to whichever class is its sole user, or stays shared package-private. Run the 47.
- [ ] **Step 3:** `SemanticHeadRenderer.renderHead` should now read as: build model → write head tags → append JSON-LD scripts. `renderBodyFragment` per Task 1's finding. Report final LOC of all four classes.
- [ ] **Step 4:** Full module gate for the touched area: `mvn -pl wikantik-main test -Dtest='SemanticHead*,PageSeoModel*' -q` green; then `mvn -pl wikantik-main test -Dtest='*Spa*' -pl wikantik-rest -q` — wait, SpaRoutingFilter tests live in wikantik-rest: run `mvn -pl wikantik-rest test -Dtest='SpaRoutingFilter*' -q` to confirm the SSR caller's tests still pass.
- [ ] **Step 5:** Commit — `refactor(ui): extract JsonLdEmitter + HeadTagWriter; SemanticHeadRenderer is a thin facade`

## Task 3: Ratchet burn-down + verification

**Files:** Modify `build-support/pmd-complexity-baseline.properties`.

- [ ] **Step 1:** Delete `com.wikantik.ui.SemanticHeadRenderer=CognitiveComplexity,CyclomaticComplexity,ExcessiveParameterList,GodClass,NPathComplexity,NcssCount` (line ~226). Run `mvn -pl wikantik-main pmd:check -Pcomplexity-gate` → MUST pass with the line gone (that IS the burn-down proof). If it fails: the decomposition left a violation — fix the code (further split), do NOT re-add the line without explicit justification.
- [ ] **Step 2:** Confirm the three new classes are absent from any gate failure (born clean). If one trips a rule, simplify it — new code doesn't get baselined.
- [ ] **Step 3:** Add one line to the baseline header's history note: first burn-down entry removed (SemanticHeadRenderer, 6 rules) on 2026-07-03.
- [ ] **Step 4:** Final verification: `mvn clean install -DskipTests -T 1C` green; `mvn -pl wikantik-main test -Dtest='SemanticHead*,PageSeoModel*' -q` + `mvn -pl wikantik-rest test -q` green; measure before/after (NPath 46,080/7,200, WMC 92, 516 LOC → report new numbers via the complexity ruleset on the four files).
- [ ] **Step 5:** Commit — `build(quality): burn down SemanticHeadRenderer baseline entry — first ratchet reduction`

## Self-Review Notes
- The byte-identical constraint is stated in Global Constraints and re-stated per task; the 46+1 tests are the gate; new tests are additive only.
- Phase-6 ontology couplings (schemaType re-sourcing, sameAs) called out with the pinning test named.
- Task boundaries: each ends green + committable; Task 3 is the measurable payoff (baseline line deleted, gate proves it).
- renderBodyFragment ambiguity is explicitly assigned to Task 1 Step 1 (inventory decides thread-through vs leave-alone) rather than hand-waved.
