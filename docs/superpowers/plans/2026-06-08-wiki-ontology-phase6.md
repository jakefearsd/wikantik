# Wiki Ontology — Phase 6 (Opportunistic SEO/nav) Implementation Plan

> **For agentic workers:** Direct (inline) execution with TDD, per the established mode for this initiative. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-source the one SEO field the ontology genuinely informs — the page's schema.org `@type` — from the *same* page-type→class mapping the ontology projection uses, lock the SEO and interop layers together with a cross-layer agreement test, and bridge the schema.org entity to its dereferenceable RDF resource. All "unchanged-or-better"; never couples the pure renderer to the runtime graph.

**Architecture:** `SemanticHeadRenderer` is a *pure function of frontmatter* (no engine/KG/graph dependency), called per-request in the SSR path. A literal "re-source the whole JSON-LD from the ontology graph" is rejected — it would drop SEO-only shapes (`CollectionPage/hasPart`, `BreadcrumbList`, `WebSite/SearchAction`), break on restricted pages (absent from the public graph) and freshly-saved pages (projection lag), and couple a pure function to the TDB2 model in the hot path. Instead we re-source only the `@type` classification (a static mapping shared with the ontology), keep everything else, and add an agreement test (the spec's "until then they coexist; a test asserts they agree").

**Tech Stack:** Java 21, JUnit 5, Apache Jena (in-memory model + RDFS inference for the agreement test), the existing `NodeTypeMapping`/`PageProjector`/`OntologyModelManager`/`Iris` from `wikantik-ontology`.

**Scope decision (why this and not full re-source):** The spec frames Phase 6 as *opportunistic, gated, "SEO output unchanged-or-better", "never blocks core", deferrable indefinitely.* The renderer's only ontology-informed field is the schema.org `@type`; today it emits a hub/Article binary, which is *less specific* than the ontology (runbook→HowTo, design→TechArticle). Re-sourcing `@type` from the shared `NodeTypeMapping` mapping is the meaningful, low-risk win.

---

## File Structure

- `wikantik-ontology/.../NodeTypeMapping.java` — **add** `schemaOrgType(String pageType)`: the single source mapping a page type to its schema.org type local name. Mirrors the T-Box `wk:<Class> rdfs:subClassOf schema:<Type>` axioms.
- `wikantik-ontology/.../NodeTypeMappingTest.java` (create if absent) — unit test for `schemaOrgType`.
- `wikantik-main/.../ui/SemanticHeadRenderer.java` — `@type` re-sourced from `NodeTypeMapping.schemaOrgType(pageType)`; additive `sameAs` to the page's ontology IRI when `canonical_id` is present.
- `wikantik-main/.../ui/SemanticHeadRendererTest.java` — extend for the more-specific `@type`s and the `sameAs` link.
- `wikantik-main/.../ui/SemanticHeadOntologyAgreementTest.java` (create) — cross-layer agreement test: renderer `@type` == the page's inferred schema.org type from `PageProjector` + RDFS inference, for the page types the mapping distinguishes.

**Mapping (`schemaOrgType`), upgrade-only — never downgrades an existing type:**

| page `type` | schema.org `@type` | vs. today |
|---|---|---|
| `hub` | `CollectionPage` | unchanged |
| `article` | `Article` | unchanged |
| `runbook` | `HowTo` | **more specific** (was Article) |
| `design`, `design_doc` | `TechArticle` | **more specific** (was Article) |
| anything else (incl. `reference`, null, unknown) | `Article` | unchanged (safe default; never the generic `CreativeWork`) |

> `reference` stays `Article` in the renderer (avoids downgrading to the broader `schema:CreativeWork` the T-Box assigns it). This is the one documented SEO↔ontology coexistence; the agreement test covers the four types where the mapping is meaningful (`hub`/`article`/`runbook`/`design`).

---

### Task 6.1: `NodeTypeMapping.schemaOrgType`

**Files:**
- Modify: `wikantik-ontology/src/main/java/com/wikantik/ontology/NodeTypeMapping.java`
- Test: `wikantik-ontology/src/test/java/com/wikantik/ontology/NodeTypeMappingTest.java` (create)

- [ ] **Step 1: Write the failing test.**

```java
package com.wikantik.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class NodeTypeMappingTest {
    @Test void schemaOrgTypeMapsPageTypesToSchemaTypes() {
        assertEquals( "CollectionPage", NodeTypeMapping.schemaOrgType( "hub" ) );
        assertEquals( "Article",        NodeTypeMapping.schemaOrgType( "article" ) );
        assertEquals( "HowTo",          NodeTypeMapping.schemaOrgType( "runbook" ) );
        assertEquals( "TechArticle",    NodeTypeMapping.schemaOrgType( "design" ) );
        assertEquals( "TechArticle",    NodeTypeMapping.schemaOrgType( "design_doc" ) );
    }
    @Test void schemaOrgTypeDefaultsToArticle() {
        assertEquals( "Article", NodeTypeMapping.schemaOrgType( "reference" ) );
        assertEquals( "Article", NodeTypeMapping.schemaOrgType( null ) );
        assertEquals( "Article", NodeTypeMapping.schemaOrgType( "" ) );
        assertEquals( "Article", NodeTypeMapping.schemaOrgType( "totally-unknown" ) );
    }
    @Test void schemaOrgTypeIsCaseInsensitive() {
        assertEquals( "HowTo", NodeTypeMapping.schemaOrgType( "Runbook" ) );
    }
}
```

- [ ] **Step 2: Run RED.** `mvn -o -pl wikantik-ontology test -Dtest=NodeTypeMappingTest` → fails (method missing).
- [ ] **Step 3: Implement** in `NodeTypeMapping`:

```java
    public static final String SCHEMA_DEFAULT = "Article";

    private static final Map< String, String > SCHEMA_MAP = Map.of(
            "hub", "CollectionPage",
            "article", "Article",
            "runbook", "HowTo",
            "design", "TechArticle",
            "design_doc", "TechArticle" );

    /**
     * Maps a page {@code type} onto its schema.org type local name, mirroring the
     * {@code wk:<Class> rdfs:subClassOf schema:<Type>} axioms in {@code wikantik.ttl}.
     * Upgrade-only: anything without a more-specific mapping defaults to {@code Article}
     * (never the broader {@code CreativeWork}). Used by the SEO head renderer so the
     * page's schema.org @type and its ontology classification share one source.
     */
    public static String schemaOrgType( final String pageType ) {
        if ( pageType == null || pageType.isBlank() ) {
            return SCHEMA_DEFAULT;
        }
        return SCHEMA_MAP.getOrDefault( pageType.trim().toLowerCase( Locale.ROOT ), SCHEMA_DEFAULT );
    }
```

- [ ] **Step 4: Run GREEN. Commit + `mvn -o install -pl wikantik-ontology -DskipTests`** (so wikantik-main sees the new method).

---

### Task 6.2: Re-source the JSON-LD `@type` in `SemanticHeadRenderer`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/ui/SemanticHeadRenderer.java`
- Test: `wikantik-main/src/test/java/com/wikantik/ui/SemanticHeadRendererTest.java`

- [ ] **Step 1: Write failing tests** — a `runbook` page emits `"@type":"HowTo"` and a `design` page emits `"@type":"TechArticle"`; a `hub` still emits `CollectionPage` and a plain article still `Article` (regression). Mirror the existing JSON-LD-extraction helpers in `SemanticHeadRendererTest` (parse the first `application/ld+json` block, assert `@type`).

- [ ] **Step 2: Run RED** (`runbook`/`design` currently emit `Article`).

- [ ] **Step 3: Implement.** In `renderHead`, compute `final String schemaType = com.wikantik.ontology.NodeTypeMapping.schemaOrgType( pageType );` and pass it into `buildMainJsonLd`. In `buildMainJsonLd`, replace both hardcoded `@type` literals (`"CollectionPage"` in the hub branch, `"Article"` in the else branch) with `jsonStr( schemaType )`. Keep the hub branch's `name`/`hasPart` logic gated on `isHub` exactly as-is — only the `@type` string changes. (`schemaOrgType("hub")` returns `CollectionPage`, so the hub `@type` is unchanged.)

- [ ] **Step 4: Run GREEN; run the whole `SemanticHeadRendererTest`** to confirm hub/article unchanged. Fix any fixture that asserted the old generic `Article` for a runbook/design page (the change is the intended "more specific = better"). Commit.

---

### Task 6.3: Additive `sameAs` ontology IRI

**Files:** `SemanticHeadRenderer.java` (+ test).

- [ ] **Step 1: Write failing test** — a page whose frontmatter has `canonical_id: 01ABC...` emits, in the main JSON-LD, `"sameAs":"https://wiki.wikantik.com/id/page/01ABC..."`; a page with no `canonical_id` emits no `sameAs`. (Parse the main JSON-LD object; assert the `sameAs` member.)

- [ ] **Step 2: Run RED.**

- [ ] **Step 3: Implement.** In `renderHead`, read `final String canonicalId = strOrEmpty( meta.get( "canonical_id" ) );` and pass it into `buildMainJsonLd`. In `buildMainJsonLd`, when `canonicalId` is non-blank, append `,"sameAs":` + `jsonStr( com.wikantik.ontology.Iris.page( canonicalId ) )` (after `mainEntityOfPage`, before the closing brace / the non-hub `isPartOf`/`relatedLink` tail — append it at the very end so it is valid regardless of branch). `Iris.page` yields the stable `https://wiki.wikantik.com/id/page/<canonicalId>` resource IRI (matches the `/id/page/{id}` dereference endpoint).

- [ ] **Step 4: Run GREEN. Commit.**

---

### Task 6.4: Cross-layer agreement test (the spec's "a test asserts they agree")

**Files:** Create `wikantik-main/src/test/java/com/wikantik/ui/SemanticHeadOntologyAgreementTest.java`.

Asserts the renderer's emitted schema.org `@type` and the ontology projection's inferred schema.org type for the *same page* agree, for the four page types the mapping distinguishes. Guards silent divergence between the SEO and interop faces of the one model.

- [ ] **Step 1: Write the test.** For each `(pageType, expectedSchemaType)` in `{hub→CollectionPage, article→Article, runbook→HowTo, design→TechArticle}`:
  1. Build a one-line frontmatter page (`type: <pageType>`, `canonical_id: 01TESTPAGE...`).
  2. **Renderer side:** call `SemanticHeadRenderer.renderHead`, parse the main JSON-LD, read `@type`.
  3. **Ontology side:** build a `PageRecord` with that type + canonical id, project it via `new PageProjector()` (or `OntologyRebuildService`) into an `OntologyModelManager.inMemory()` model whose T-Box is loaded; read the page resource's `rdf:type` values from `inferenceSnapshot()` and collect those in the `https://schema.org/` namespace.
  4. Assert the renderer's `@type` (as `https://schema.org/<type>`) is present in the ontology's inferred schema.org types — i.e. **they agree**.

  Reuse the existing projector/manager test helpers in `wikantik-ontology` test sources as a reference for constructing `PageRecord` + the in-memory manager. Build the `PageProjector` model, add it as a named graph (or merge into the manager), and call `inferenceSnapshot()` so the `wk:<Class> rdfs:subClassOf schema:<Type>` axiom fires.

- [ ] **Step 2: Run.** With Task 6.1+6.2 in place it should pass by construction (both sides derive from the same `wk:`→schema mapping). If `runbook`/`design` fail, the T-Box axiom or `schemaOrgType` disagree — reconcile them (they must match). Document the `reference` coexistence in a class comment (not asserted for strict equality).

- [ ] **Step 3: Commit.**

---

### Task 6.5: Gates + docs/memory

- [ ] **Step 1: Targeted module checks** — `mvn -o -pl wikantik-ontology test -Dtest=NodeTypeMappingTest,EntityTypeVocabularyDriftTest,OntologyShaclValidatorTest` and `mvn -o -pl wikantik-main test -Dtest=SemanticHeadRendererTest,SemanticHeadOntologyAgreementTest`.
- [ ] **Step 2: Full unit reactor** `mvn -o clean install -T 1C -DskipITs` (ArchUnit + all unit tests green).
- [ ] **Step 3: Full IT reactor** `mvn -o clean install -Pintegration-tests -fae -Dtest=ZZZ_NoUnitTests -DfailIfNoTests=false` (skip unit tests via a non-matching surefire pattern — `-Dsurefire.skip` is **not** a real property; `-DskipTests` would also skip the ITs). Verify failsafe aggregate Failures: 0. Includes `SemanticWebIT` (SEO regression coverage).
- [ ] **Step 4: Docs + memory.** CLAUDE.md: note the schema.org `@type` is now re-sourced from `NodeTypeMapping.schemaOrgType` + the SEO↔ontology agreement test. Project memory → Phase 6 complete; **ontology initiative complete (Phases 0–6).**

---

## Self-Review (against spec §5 Phase 6 + §4 matrix row)

- "SemanticHeadRenderer JSON-LD re-sourced as a projection of the page's ontology graph" → the `@type` classification is re-sourced from the shared `NodeTypeMapping` mapping (the projection's own type source); the SEO-only shapes are deliberately retained (not in the ontology graph). ✓
- "Until then they coexist; a test asserts they agree" → Task 6.4 agreement test. ✓
- "Regression test: SEO output unchanged-or-better" → Task 6.2 keeps hub/article identical, upgrades runbook/design to more-specific types, never downgrades; `sameAs` is purely additive; `SemanticWebIT` in the IT gate. ✓
- "Never blocks earlier phases / pure function preserved" → no runtime graph dependency added to the renderer (static mapping + a constant IRI); no engine coupling. ✓

**Placeholders:** none — 6.1 + the mapping table are complete; 6.2/6.3 give exact edit points; 6.4 specifies the construction and the assertion (projector helper reused at edit time).

**Type consistency:** `NodeTypeMapping.schemaOrgType(String)→String` (local name) used by the renderer and the agreement test; `Iris.page(String)→String` IRI for `sameAs`; both already exist / added in 6.1.
