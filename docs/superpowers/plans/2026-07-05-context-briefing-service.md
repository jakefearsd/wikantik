# Context Briefing Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A server-composed, budgeted "context briefing" (`get_briefing` MCP tool + `GET /api/briefing`) that injects wiki context into Claude Code / Antigravity sessions at session start, per spec `docs/superpowers/specs/2026-07-05-context-briefing-design.md`.

**Architecture:** New `com.wikantik.api.briefing` types (wikantik-api) + `com.wikantik.knowledge.briefing` assembler (wikantik-main) mirroring the existing bundle stack (`BundleServiceWiring` → `KnowledgeSubsystem.Services` → `BundleResource` / `AssembleBundleTool`). The assembler fills a token budget priority-ordered: prompt-refined bundle sections → pinned pages → cluster members → pointers. Client shims (Claude Code hook, Antigravity rules snippet, `wiki-context` skill) live under `clients/`.

**Tech Stack:** Java 21, Maven multi-module, JUnit 5 + Mockito, Gson, MCP Java SDK (`McpSchema`), PostgreSQL migrations under `bin/db/migrations/`.

## Global Constraints

- TDD: every task writes its failing test first (CLAUDE.md).
- Every NEW file (`.java`, `.sql`, `.sh`, `.md` under clients/ excluded — check `.rat-excludes`/pom config if RAT complains) needs the Apache license header or `mvn apache-rat:check` fails. Copy the header from any neighboring file in the same directory.
- No empty catch blocks — minimum `LOG.warn()` with context (CLAUDE.md).
- Migrations are DDL-only, idempotent, use `:app_user` for grants (`bin/db/migrations/README.md`).
- Compile-check per module: `mvn compile -pl <module> -q`; after signature changes run `mvn test-compile -pl <module> -q` too (test sources are not compiled by `mvn compile`).
- Unit-test a single class: `mvn test -pl <module> -Dtest=ClassName`.
- Never run ITs with `-T`. Full IT reactor: `mvn clean install -Pintegration-tests -fae`.
- Stage files by name; never `git add -A`.
- `KnowledgeSubsystem.Services` is a record — adding a component requires updating EVERY `new KnowledgeSubsystem.Services(` call site in lock-step (find them with the grep in Task 4).
- Suggested subagent models per task are noted; when unsure, step up a tier.

---

### Task 1: wikantik-api briefing types + SourceSurface values

**Suggested model:** sonnet

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/briefing/ScopeMode.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/briefing/BriefingRequest.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/briefing/BriefingItem.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/briefing/ContextBriefing.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/briefing/BriefingAssemblyService.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/briefing/BriefingLogEntry.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/briefing/BriefingLogService.java`
- Modify: `wikantik-api/src/main/java/com/wikantik/api/querylog/SourceSurface.java` (add two enum constants)
- Test: `wikantik-api/src/test/java/com/wikantik/api/briefing/BriefingTypesTest.java`

**Interfaces:**
- Consumes: `com.wikantik.api.bundle.{BundleSection, BundleCoverage}` (existing, same module).
- Produces (relied on by Tasks 3–8):
  - `ScopeMode { PREFER, STRICT }` with `static ScopeMode fromWire(String)` (null/blank → PREFER, case-insensitive, unknown → `IllegalArgumentException`).
  - `record BriefingRequest(List<String> pins, List<String> clusters, String prompt, Integer budgetTokens, ScopeMode scopeMode)` with `boolean hasAnySource()`.
  - `record BriefingItem(String slug, String canonicalId, String title, String summary, String origin, boolean included, String content)` — `origin` is `"pin"` or `"cluster"`; `included == false` means pointer (content null).
  - `record ContextBriefing(String prompt, List<BundleSection> sections, BundleCoverage coverage, List<BriefingItem> items, List<String> warnings, int budgetTokens, int usedTokens)`.
  - `interface BriefingAssemblyService { ContextBriefing assemble(BriefingRequest request); }`
  - `record BriefingLogEntry(String pins, String clusters, boolean promptPresent, int budgetRequested, int budgetUsed, int sectionCount, int pinCount, int pointerCount, String surface)`.
  - `interface BriefingLogService { void log(BriefingLogEntry entry); }` — contract: fail-open, never throws, never blocks (same as `QueryLogService`).
  - `SourceSurface.API_BRIEFING("api_briefing")`, `SourceSurface.MCP_GET_BRIEFING("mcp_get_briefing")`.

- [ ] **Step 1: Write the failing test**

`wikantik-api/src/test/java/com/wikantik/api/briefing/BriefingTypesTest.java` (Apache header, then):

```java
package com.wikantik.api.briefing;

import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.querylog.SourceSurface;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BriefingTypesTest {

    @Test
    void scopeModeFromWire() {
        assertEquals( ScopeMode.PREFER, ScopeMode.fromWire( null ) );
        assertEquals( ScopeMode.PREFER, ScopeMode.fromWire( "  " ) );
        assertEquals( ScopeMode.PREFER, ScopeMode.fromWire( "prefer" ) );
        assertEquals( ScopeMode.STRICT, ScopeMode.fromWire( "STRICT" ) );
        assertThrows( IllegalArgumentException.class, () -> ScopeMode.fromWire( "bogus" ) );
    }

    @Test
    void requestDefaultsAndHasAnySource() {
        final BriefingRequest empty = new BriefingRequest( null, null, null, null, null );
        assertEquals( List.of(), empty.pins() );
        assertEquals( List.of(), empty.clusters() );
        assertEquals( ScopeMode.PREFER, empty.scopeMode() );
        assertFalse( empty.hasAnySource() );
        assertTrue( new BriefingRequest( List.of( "A" ), null, null, null, null ).hasAnySource() );
        assertTrue( new BriefingRequest( null, List.of( "c" ), null, null, null ).hasAnySource() );
        assertTrue( new BriefingRequest( null, null, "why?", null, null ).hasAnySource() );
        assertFalse( new BriefingRequest( null, null, "  ", null, null ).hasAnySource() );
    }

    @Test
    void itemRequiresSlugAndModelsPointer() {
        assertThrows( IllegalArgumentException.class,
            () -> new BriefingItem( " ", null, "t", "s", "pin", true, "body" ) );
        final BriefingItem ptr = new BriefingItem( "Q3Goals", "01Q3", "Q3 Goals", "sum", "cluster", false, null );
        assertFalse( ptr.included() );
        assertNull( ptr.content() );
    }

    @Test
    void briefingDefaultsNullCollections() {
        final ContextBriefing b = new ContextBriefing( null, null, null, null, null, 6000, 0 );
        assertEquals( List.of(), b.sections() );
        assertEquals( List.of(), b.items() );
        assertEquals( List.of(), b.warnings() );
        assertEquals( BundleCoverage.empty(), b.coverage() );
    }

    @Test
    void sourceSurfaceWireValues() {
        assertEquals( "api_briefing", SourceSurface.API_BRIEFING.wire() );
        assertEquals( "mcp_get_briefing", SourceSurface.MCP_GET_BRIEFING.wire() );
        assertEquals( SourceSurface.API_BRIEFING, SourceSurface.fromWire( "api_briefing" ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-api -Dtest=BriefingTypesTest -q`
Expected: COMPILATION ERROR (types don't exist).

- [ ] **Step 3: Implement the types**

Each file gets the Apache header + `package com.wikantik.api.briefing;`. Follow the compact-constructor style of `ContextBundle`/`BundleSection` exactly:

```java
public enum ScopeMode {
    PREFER, STRICT;

    /** null/blank → PREFER; otherwise case-insensitive name; unknown → IllegalArgumentException. */
    public static ScopeMode fromWire( final String raw ) {
        if ( raw == null || raw.isBlank() ) {
            return PREFER;
        }
        return switch ( raw.trim().toLowerCase( java.util.Locale.ROOT ) ) {
            case "prefer" -> PREFER;
            case "strict" -> STRICT;
            default -> throw new IllegalArgumentException( "Unknown scope_mode: " + raw );
        };
    }
}
```

```java
public record BriefingRequest( List< String > pins, List< String > clusters, String prompt,
                               Integer budgetTokens, ScopeMode scopeMode ) {
    public BriefingRequest {
        pins = pins == null ? List.of() : List.copyOf( pins );
        clusters = clusters == null ? List.of() : List.copyOf( clusters );
        scopeMode = scopeMode == null ? ScopeMode.PREFER : scopeMode;
    }

    public boolean hasAnySource() {
        return !pins.isEmpty() || !clusters.isEmpty() || ( prompt != null && !prompt.isBlank() );
    }
}
```

```java
public record BriefingItem( String slug, String canonicalId, String title, String summary,
                            String origin, boolean included, String content ) {
    public BriefingItem {
        if ( slug == null || slug.isBlank() ) {
            throw new IllegalArgumentException( "BriefingItem.slug is required" );
        }
    }
}
```

```java
public record ContextBriefing( String prompt, List< BundleSection > sections, BundleCoverage coverage,
                               List< BriefingItem > items, List< String > warnings,
                               int budgetTokens, int usedTokens ) {
    public ContextBriefing {
        sections = sections == null ? List.of() : List.copyOf( sections );
        items = items == null ? List.of() : List.copyOf( items );
        warnings = warnings == null ? List.of() : List.copyOf( warnings );
        coverage = coverage == null ? BundleCoverage.empty() : coverage;
    }
}
```

```java
/** Session-start context briefing assembly (design 2026-07-05). Never synthesizes answers (ADR-0001). */
public interface BriefingAssemblyService {
    ContextBriefing assemble( BriefingRequest request );
}
```

```java
public record BriefingLogEntry( String pins, String clusters, boolean promptPresent,
                                int budgetRequested, int budgetUsed, int sectionCount,
                                int pinCount, int pointerCount, String surface ) {}
```

```java
/** Fire-and-forget briefing telemetry. Implementations MUST be fail-open: never throw, never block. */
public interface BriefingLogService {
    void log( BriefingLogEntry entry );
}
```

In `SourceSurface.java` add after `TOOLS_SEARCH_WIKI( "tools_search_wiki" )` (change `;` to `,`):

```java
    API_BRIEFING( "api_briefing" ),
    MCP_GET_BRIEFING( "mcp_get_briefing" );
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-api -Dtest=BriefingTypesTest -q`
Expected: PASS. Also run `mvn test -pl wikantik-api -q` (whole module — `SourceSurface` has its own tests that must stay green).

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/briefing wikantik-api/src/test/java/com/wikantik/api/briefing wikantik-api/src/main/java/com/wikantik/api/querylog/SourceSurface.java
git commit -m "feat(briefing): api types for context briefing service"
```

---

### Task 2: Shared TokenEstimator in wikantik-util

**Suggested model:** sonnet

**Files:**
- Create: `wikantik-util/src/main/java/com/wikantik/util/TokenEstimator.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ContentChunker.java` (delegate `estimateTokens`)
- Test: `wikantik-util/src/test/java/com/wikantik/util/TokenEstimatorTest.java`

**Interfaces:**
- Produces: `public static int TokenEstimator.estimate(String s)` — `ceil(length/4.0)`, null/empty → 0. Tasks 3+ use this for budget accounting.

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenEstimatorTest {

    @Test
    void fourCharsPerTokenCeiling() {
        assertEquals( 0, TokenEstimator.estimate( null ) );
        assertEquals( 0, TokenEstimator.estimate( "" ) );
        assertEquals( 1, TokenEstimator.estimate( "abc" ) );
        assertEquals( 1, TokenEstimator.estimate( "abcd" ) );
        assertEquals( 2, TokenEstimator.estimate( "abcde" ) );
        assertEquals( 25, TokenEstimator.estimate( "x".repeat( 100 ) ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-util -Dtest=TokenEstimatorTest -q`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement + delegate ContentChunker**

```java
package com.wikantik.util;

/**
 * Heuristic token estimator (4 chars/token) shared by the chunker and the briefing
 * budget accounting so both sides of retrieval agree on size.
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    public static int estimate( final String s ) {
        return s == null || s.isEmpty() ? 0 : ( int )Math.ceil( s.length() / 4.0 );
    }
}
```

In `ContentChunker.java`, replace the body of the private `estimateTokens(String s)` method with `return com.wikantik.util.TokenEstimator.estimate( s );` (keep the method — its callers are unchanged). Do NOT touch the inline `(int) Math.ceil(charCount / 4.0)` logging-path line unless it's a trivial swap.

- [ ] **Step 4: Run tests**

Run: `mvn test -pl wikantik-util -Dtest=TokenEstimatorTest -q` → PASS.
Run: `mvn test -pl wikantik-main -Dtest=ContentChunkerTest -q` → PASS (chunker behavior unchanged; if the test class has a different name, find it: `ls wikantik-main/src/test/java/com/wikantik/knowledge/chunking/`).

- [ ] **Step 5: Commit**

```bash
git add wikantik-util/src/main/java/com/wikantik/util/TokenEstimator.java wikantik-util/src/test/java/com/wikantik/util/TokenEstimatorTest.java wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ContentChunker.java
git commit -m "refactor: extract shared TokenEstimator for briefing budget accounting"
```

---

### Task 3: DefaultBriefingAssemblyService (core assembler)

**Suggested model:** opus — this is the heart of the feature.

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/briefing/BriefingConfig.java`
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/briefing/DefaultBriefingAssemblyService.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/briefing/DefaultBriefingAssemblyServiceTest.java`

**Interfaces:**
- Consumes: `BriefingAssemblyService`/`BriefingRequest`/`ContextBriefing`/`BriefingItem`/`ScopeMode` (Task 1), `TokenEstimator` (Task 2), existing `BundleAssemblyService`, `StructuralIndexService` (`com.wikantik.api.pagegraph`: `getCluster(name)` → `Optional<ClusterDetails>`, `resolveCanonicalIdFromSlug`, `resolveSlugFromCanonicalId`), `PageManager.getPage(String)` / `getPureText(String, PageProvider.LATEST_VERSION)`, `FrontmatterParser.parse(text).metadata()`, `BundleCoverage.recount`.
- Produces:
  - `BriefingConfig`: `public static final String PREFIX = "wikantik.briefing.";` `public static final int DEFAULT_BUDGET = 6000;` `public static final int MAX_BUDGET = 24000;` `public static boolean enabled(Properties)` (key `PREFIX+"enabled"`, default true), `public static int defaultBudget(Properties)` (`PREFIX+"default_budget"`), `public static int maxBudget(Properties)` (`PREFIX+"max_budget"`) — use `TextUtil.getBooleanProperty` and hand-rolled int parse with `LOG.warn` fallback (mirror `BundleServiceWiring.sectionsPerPageFrom`).
  - `public DefaultBriefingAssemblyService( BundleAssemblyService bundleService, StructuralIndexService structuralIndex, PageManager pageManager, int defaultBudget, int maxBudget )` implements `BriefingAssemblyService`. `bundleService` and `structuralIndex` are nullable (degrade with warning); `pageManager` required (NPE via `Objects.requireNonNull`).

**Assembly algorithm (implement exactly):**

1. `budget = clamp(request.budgetTokens() ?? defaultBudget, 200, maxBudget)`; `used = 0`; `warnings = new ArrayList<>()`.
2. **Sections** — if `request.prompt()` non-blank:
   - If `bundleService == null`: add warning `"bundle service unavailable; prompt refinement skipped"` and continue.
   - Else `bundle = bundleService.assemble(prompt)` inside try/catch(`RuntimeException`) → on error `LOG.warn` + warning `"bundle assembly failed; briefing degraded to pins/clusters"` and continue with no sections (fail-soft per spec).
   - **Scoping**: if `request.clusters()` non-empty and `structuralIndex != null`, build `Set<String> inScope` = for each cluster name, `structuralIndex.getCluster(name)` → hub slug (if `hubPage() != null`) + all `articles()` slugs; unknown cluster → warning `"unknown cluster: <name>"`. Then: `PREFER` → stable-sort sections in-scope-first (`List` partition, preserve relative order); `STRICT` → drop out-of-scope sections.
   - Take sections in order while `used + TokenEstimator.estimate(section.text()) <= budget`, accumulating `used`.
   - `coverage = BundleCoverage.recount(bundle.coverage(), keptSections)`.
   - No prompt → `sections = List.of()`, `coverage = BundleCoverage.empty()`.
3. **Pins** — for each pin in `request.pins()` order:
   - Resolve slug: if `pageManager.getPage(pin) != null` → slug = pin; else if `structuralIndex != null` and `structuralIndex.resolveSlugFromCanonicalId(pin)` present and that slug's page exists → use it; else warning `"unknown pin: <pin>"`, skip.
   - Skip (silently) if slug already included as an item.
   - Load body = `pageManager.getPureText(slug, PageProvider.LATEST_VERSION)`; metadata = `FrontmatterParser.parse(body == null ? "" : body).metadata()`; `title = str(meta.get("title"))` falling back to slug; `summary = str(meta.get("summary"))` falling back to `""`; `canonicalId = structuralIndex == null ? null : structuralIndex.resolveCanonicalIdFromSlug(slug).orElse(null)`.
   - **Supersede rule (cross-source dedup):** if any kept bundle section has this slug AND `used - sum(those sections' token estimates) + estimate(body) <= budget`, remove those sections (refund their tokens, recount coverage over the remaining sections) and include the full body — the body is a superset of its own sections. Otherwise, if `used + estimate(body) <= budget` include full body; else pointer item (`included=false, content=null`).
   - Included item: `new BriefingItem(slug, canonicalId, title, summary, "pin", true, body)`, `used += estimate(body)`.
4. **Cluster members** — for each cluster in `request.clusters()` order (skip if `structuralIndex == null` with warning `"structural index unavailable; clusters skipped"`): `getCluster(name)` (already warned if absent in step 2 — don't double-warn: collect unknown clusters once in a `Set`). Order: hub page first (if present), then `articles()` sorted by `PageDescriptor.updated()` descending (null-safe: nulls last). For each member slug not already an item: same include-or-pointer logic as pins with `origin="cluster"` (no supersede pass — only pins supersede sections; cluster members that collide with a bundle-section slug just become pointers if the body doesn't fit).
5. Return `new ContextBriefing(request.prompt(), keptSections, coverage, items, warnings, budget, used)`.

The assembler is **ACL-agnostic** (like `DefaultBundleAssemblyService`); the surfaces gate (Tasks 6–7).

- [ ] **Step 1: Write the failing tests**

Use Mockito for `PageManager` (mock `getPage` to return a `mock(Page.class)` for existing slugs, null otherwise; `getPureText` to return bodies) and a hand-rolled `StructuralIndexService` stub (implement the interface, return canned `ClusterDetails`/`Optional`s, throw `UnsupportedOperationException` for unused methods). `BundleAssemblyService` is a lambda. Reuse `PageDescriptor`/`ClusterDetails` record constructors from `com.wikantik.api.pagegraph`.

Test methods (write ALL of these — each is a few lines with the shared fixture builder):

```java
package com.wikantik.knowledge.briefing;
// imports: api.briefing.*, api.bundle.*, api.pagegraph.*, api.core.Page, PageManager, PageProvider,
// org.junit.jupiter.api.Test, org.mockito.Mockito.*, java.util.*, static org.junit.jupiter.api.Assertions.*

class DefaultBriefingAssemblyServiceTest {

    private static BundleSection sec( String slug, String heading, String text ) {
        return new BundleSection( "01" + slug, slug, List.of( heading ), text, 0.9,
            new CitationHandle( "01" + slug, 1, List.of( heading ), text, "sha" ) );
    }

    // fixture: pageManager knows "BillingProcess" (body ~400 chars) and "Q3Goals" (body ~400 chars)

    @Test void noSourcesReturnsEmptyBriefingWithBudget() { ... assemble(new BriefingRequest(null,null,null,null,null)) → sections empty, items empty, usedTokens 0 ... }

    @Test void promptOnlyFillsSectionsWithinBudget() { ... bundle returns 3 sections of ~100 tokens each, budget 250 → 2 kept, coverage recounted, usedTokens between 200 and 250 ... }

    @Test void pinFullBodyWhenItFits() { ... pins=[BillingProcess], no prompt → one included item origin "pin", content == body, title/summary from frontmatter ... }

    @Test void pinDegradesToPointerWhenBudgetExhausted() { ... budget 200 (floor clamp: use 200), body of 2000 chars → item.included false, content null, usedTokens unchanged ... }

    @Test void unknownPinWarnsAndContinues() { ... pins=[Nope, BillingProcess] → warning contains "unknown pin: Nope", BillingProcess still included ... }

    @Test void pinResolvesCanonicalId() { ... pin "01BILL" not a page name; structuralIndex.resolveSlugFromCanonicalId("01BILL")→"BillingProcess" → included with slug BillingProcess ... }

    @Test void pinSupersedesItsOwnBundleSections() { ... prompt yields 2 sections from slug BillingProcess (60 tokens each) + 1 from Other; pins=[BillingProcess]; budget generous → final sections contain only Other's, BillingProcess appears once as full-body item, usedTokens accounts refund ... }

    @Test void strictScopeDropsOutOfClusterSections() { ... clusters=[billing] (members: BillingProcess), scopeMode STRICT, bundle returns sections from BillingProcess + Rogue → only BillingProcess section kept, coverage recounted ... }

    @Test void preferScopeReordersInScopeFirst() { ... same fixture, PREFER → both kept, BillingProcess section index 0 ... }

    @Test void clusterMembersHubFirstThenByUpdatedDesc() { ... cluster billing: hub BillingHub + articles A (updated older), B (newer) → item order BillingHub, B, A ... }

    @Test void bundleFailureFailsSoftToPins() { ... bundleService throws RuntimeException; pins=[BillingProcess] → no sections, warning contains "degraded", pin still included ... }

    @Test void nullBundleServiceWarnsOnPrompt() { ... service built with null bundleService, prompt given → warning "bundle service unavailable", pins still work ... }

    @Test void budgetClampsToMax() { ... maxBudget 24000, request 999999 → briefing.budgetTokens() == 24000 ... }
}
```

Write these out fully — the `...` above are for plan brevity ONLY; the test file must contain complete arrange/act/assert code following the fixture pattern of `DefaultBundleAssemblyServiceTest` (see that file for the stub style).

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl wikantik-main -Dtest=DefaultBriefingAssemblyServiceTest -q`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement `BriefingConfig` + `DefaultBriefingAssemblyService`**

Per the algorithm above. Style notes: mirror `DefaultBundleAssemblyService` (final class, `LinkedHashMap`/`LinkedHashSet` for order-stable dedup, `LOG = LogManager.getLogger`). Keep the class under ~250 lines by extracting private helpers: `resolvePinSlug`, `loadItem(slug, origin, budget)`, `clusterMemberSlugs(name)`, `partitionByScope(sections, inScope, mode)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl wikantik-main -Dtest=DefaultBriefingAssemblyServiceTest -q`
Expected: PASS (all 13).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/briefing wikantik-main/src/test/java/com/wikantik/knowledge/briefing
git commit -m "feat(briefing): budgeted briefing assembler with scope + supersede dedup"
```

---### Task 4: Markdown renderer

**Suggested model:** sonnet

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/briefing/MarkdownBriefingRenderer.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/briefing/MarkdownBriefingRendererTest.java`

**Interfaces:**
- Produces: `public static String MarkdownBriefingRenderer.render(ContextBriefing b)` — the injection-ready markdown. Used by REST `format=md` (Task 6) and the MCP tool (Task 7).

**Output format (golden — implement exactly):**

```markdown
# Wiki context briefing
_Coverage: strong — 2 sections across 2 pages. Deepen with `assemble_bundle("<question>")`; fetch full pages with `read_pages`._

## Task-relevant sections

### DeployGuide › Setup (deploy-guide @ v7)

<section text>

## Standing context

### Billing Process (`BillingProcess`)

<page body>

## Available on request

- **Q3 Goals** (`Q3Goals`) — One-line summary here.

> Briefing warnings: unknown pin: Nope
```

Rules: the `## Task-relevant sections` block only when `sections` non-empty; heading per section = `slug › headingPath.join(" › ")` with `(canonicalId @ v<version>)` from the citation handle. `## Standing context` only when included items exist; heading per item = `title` + `` (`slug`) ``. `## Available on request` only when pointer items exist; line = `- **title** (`slug`) — summary.` (omit ` — summary` when blank). Warnings blockquote only when warnings non-empty. Coverage line always present (use `coverage.confidence()`, `sectionCount`, `distinctPageCount`).

- [ ] **Step 1: Write the failing golden test** — build a `ContextBriefing` with 1 section, 1 included item, 1 pointer item, 1 warning; assert the full expected string with `assertEquals` (text block). Add a second test: empty briefing renders only the `# Wiki context briefing` header + coverage line (confidence `unknown`, 0 sections across 0 pages).

- [ ] **Step 2: Run** `mvn test -pl wikantik-main -Dtest=MarkdownBriefingRendererTest -q` → COMPILATION ERROR.

- [ ] **Step 3: Implement** — single static method + small private helpers, `StringBuilder`, no dependencies beyond the api types.

- [ ] **Step 4: Run** the test → PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/briefing/MarkdownBriefingRenderer.java wikantik-main/src/test/java/com/wikantik/knowledge/briefing/MarkdownBriefingRendererTest.java
git commit -m "feat(briefing): injection-ready markdown renderer"
```

---

### Task 5: Wiring — BriefingServiceWiring + KnowledgeSubsystem.Services + WikiEngine seams

**Suggested model:** opus — record lock-step across many call sites; ArchUnit constraints apply.

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/briefing/BriefingServiceWiring.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystem.java` (add `BriefingAssemblyService briefingAssemblyService` as the LAST record component of `Services`)
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` (`patchContextRetrievalService` ~line 1583 derives it; `rebuildKnowledgeSubsystemWithPostConstructionServices` ~line 1522 seeds `null`)
- Modify: every other `new KnowledgeSubsystem.Services(` call site (find them first)
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/briefing/BriefingServiceWiringTest.java`

**Interfaces:**
- Produces: `public static BriefingAssemblyService BriefingServiceWiring.build( BundleAssemblyService bundle, StructuralIndexService structuralIndex, PageManager pageManager, Properties props )` — returns `null` if `pageManager == null` OR `BriefingConfig.enabled(props) == false`; otherwise `new DefaultBriefingAssemblyService(bundle, structuralIndex, pageManager, BriefingConfig.defaultBudget(props), BriefingConfig.maxBudget(props))`. Never throws. `bundle`/`structuralIndex` may be null (degraded briefing still works for pins).
- Produces: `KnowledgeSubsystem.Services.briefingAssemblyService()` accessor — consumed by Tasks 6–7 via `getSubsystems().knowledge().briefingAssemblyService()` / `kg.briefingAssemblyService()`.

- [ ] **Step 1: Find all Services call sites**

Run: `grep -rln "new KnowledgeSubsystem.Services(\|new com.wikantik.knowledge.subsystem.KnowledgeSubsystem.Services(" --include=*.java wikantik-*/src`
Record the list. Every one must gain a final argument (usually `null`; the `patchContextRetrievalService` site gets the real build call).

- [ ] **Step 2: Write the failing wiring test**

```java
package com.wikantik.knowledge.briefing;
// Apache header + imports

class BriefingServiceWiringTest {

    @Test
    void nullPageManagerReturnsNull() {
        assertNull( BriefingServiceWiring.build( null, null, null, new Properties() ) );
    }

    @Test
    void disabledPropertyReturnsNull() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.briefing.enabled", "false" );
        assertNull( BriefingServiceWiring.build( null, null, mock( PageManager.class ), p ) );
    }

    @Test
    void buildsServiceWithNullCollaborators() {
        final BriefingAssemblyService svc = BriefingServiceWiring.build( null, null, mock( PageManager.class ), new Properties() );
        assertNotNull( svc );
        // degraded assemble still answers (empty briefing, no throw)
        final ContextBriefing b = svc.assemble( new BriefingRequest( null, null, "q", null, null ) );
        assertTrue( b.warnings().stream().anyMatch( w -> w.contains( "bundle service unavailable" ) ) );
    }
}
```

- [ ] **Step 3: Run** `mvn test -pl wikantik-main -Dtest=BriefingServiceWiringTest -q` → COMPILATION ERROR.

- [ ] **Step 4: Implement**

`BriefingServiceWiring` mirrors `BundleServiceWiring` (static `build`, `LOG.warn` on odd inputs, never throws). Then:

1. Add `com.wikantik.api.briefing.BriefingAssemblyService briefingAssemblyService` as the last component of `KnowledgeSubsystem.Services`.
2. Update every call site from Step 1 with a trailing `null`.
3. In `WikiEngine.patchContextRetrievalService`, after the existing `BundleServiceWiring.build(...)` argument, add:

```java
com.wikantik.knowledge.briefing.BriefingServiceWiring.build(
    // the bundle service just built above — assign it to a local first:
    bundleSvc,
    structuralIndexOrNull(),
    pageSubsystem != null ? pageSubsystem.pages() : null,
    properties )
```

Refactor the method body minimally: compute `final var bundleSvc = com.wikantik.knowledge.bundle.BundleServiceWiring.build(...)` into a local before the record construction, pass `bundleSvc` for the bundle component and reuse it for the briefing build. Add a private helper:

```java
private com.wikantik.api.pagegraph.StructuralIndexService structuralIndexOrNull() {
    try {
        return com.wikantik.pagegraph.subsystem.PageGraphSubsystemBridge
            .fromLegacyEngine( this ).structuralIndexService();
    } catch ( final RuntimeException e ) {
        LOG.warn( "Structural index unavailable for briefing wiring: {}", e.getMessage() );
        return null;
    }
}
```

(Verify the bridge class path first: `grep -rn "class PageGraphSubsystemBridge" --include=*.java wikantik-main/src`. If `fromLegacyEngine` needs an `Engine`, `this` satisfies it. If the accessor name differs, use what `KnowledgeMcpInitializer` uses — `grep -n "structuralIndexService" wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java`.)

- [ ] **Step 5: Compile + test**

Run: `mvn compile -pl wikantik-main -q && mvn test-compile -pl wikantik-main -q` (record change breaks test call sites — fix each with a trailing `null`).
Run: `mvn test -pl wikantik-main -Dtest=BriefingServiceWiringTest -q` → PASS.
Run: `mvn test -pl wikantik-main -Dtest=DecompositionArchTest -q` → PASS (no new `getManager` calls were added; if the freeze store mutates on a red run, restore it: `git checkout -- build-support/` — see the ArchUnit gotcha).

- [ ] **Step 6: Commit**

```bash
git add -u wikantik-main wikantik-knowledge wikantik-rest
git add wikantik-main/src/main/java/com/wikantik/knowledge/briefing/BriefingServiceWiring.java wikantik-main/src/test/java/com/wikantik/knowledge/briefing/BriefingServiceWiringTest.java
git commit -m "feat(briefing): wire BriefingAssemblyService onto KnowledgeSubsystem.Services"
```

(`git add -u <module>` is acceptable here because the record change touches many known files; review `git status` first — no untracked junk.)

---

### Task 6: Briefing log — migration V044 + JdbcBriefingLogService + WikiEngine field

**Suggested model:** sonnet

**Files:**
- Create: `bin/db/migrations/V044__briefing_log.sql`
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/querylog/JdbcBriefingLogService.java`
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/querylog/BriefingLogWiring.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` (field + setter + getter mirroring `queryLogService` at ~lines 1433–1442; construction beside `setQueryLogService` at ~line 1166)
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/querylog/JdbcBriefingLogServiceTest.java`

**Interfaces:**
- Consumes: `BriefingLogService`/`BriefingLogEntry` (Task 1).
- Produces: `WikiEngine.briefingLogService()` (nullable) — consumed by Tasks 7–8. `BriefingLogWiring.build(DataSource, Properties)` reads `wikantik.briefing.log.enabled` (default true), mirrors `QueryLogWiring.build` (`wikantik-main/src/main/java/com/wikantik/knowledge/querylog/QueryLogWiring.java` — copy its executor pattern).

- [ ] **Step 1: Write the migration**

`V044__briefing_log.sql` (copy V041's header comment style; idempotent):

```sql
-- Briefing telemetry (S3 instrumentation, design 2026-07-05).
-- surface ∈ 'api_briefing' | 'mcp_get_briefing'
CREATE TABLE IF NOT EXISTS briefing_log (
    id               BIGSERIAL   PRIMARY KEY,
    pins             TEXT,
    clusters         TEXT,
    prompt_present   BOOLEAN     NOT NULL DEFAULT FALSE,
    budget_requested INTEGER     NOT NULL,
    budget_used      INTEGER     NOT NULL,
    section_count    INTEGER     NOT NULL,
    pin_count        INTEGER     NOT NULL,
    pointer_count    INTEGER     NOT NULL,
    surface          TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_briefing_log_created_at ON briefing_log (created_at);
GRANT SELECT, INSERT ON briefing_log TO :app_user;
GRANT USAGE, SELECT ON SEQUENCE briefing_log_id_seq TO :app_user;
```

Verify idempotency: run `bin/db/migrate.sh --status` mentally — re-applying must be a no-op (IF NOT EXISTS everywhere; GRANTs are naturally idempotent).

- [ ] **Step 2: Write the failing unit test**

Mirror `wikantik-main/src/test/java/com/wikantik/knowledge/querylog/JdbcQueryLogServiceTest.java` if it exists (`ls wikantik-main/src/test/java/com/wikantik/knowledge/querylog/`); otherwise: mock `DataSource`/`Connection`/`PreparedStatement`, use a same-thread `Executor` (`Runnable::run`), assert: (a) insert sets all 9 parameters, (b) `enabled=false` → no connection acquired, (c) SQLException → swallowed with no throw (verify via `assertDoesNotThrow`), (d) null entry → no-op.

- [ ] **Step 3: Run** `mvn test -pl wikantik-main -Dtest=JdbcBriefingLogServiceTest -q` → COMPILATION ERROR.

- [ ] **Step 4: Implement**

`JdbcBriefingLogService implements BriefingLogService` — copy `JdbcQueryLogService`'s structure verbatim (constructor `(DataSource, boolean enabled, Executor)`, async dispatch, every failure `LOG.warn`, never throw). SQL:

```java
"INSERT INTO briefing_log (pins, clusters, prompt_present, budget_requested, budget_used, "
+ "section_count, pin_count, pointer_count, surface) VALUES (?,?,?,?,?,?,?,?,?)"
```

`BriefingLogWiring.build(ds, props)` copies `QueryLogWiring.build` with the `wikantik.briefing.log.enabled` key. In `WikiEngine`: add `private volatile com.wikantik.api.briefing.BriefingLogService briefingLogService;` + `setBriefingLogService(...)` + `public com.wikantik.api.briefing.BriefingLogService briefingLogService()` next to the queryLogService trio (~line 1433), and beside line 1166 add:

```java
this.setBriefingLogService( com.wikantik.knowledge.querylog.BriefingLogWiring.build( ds, props ) );
```

- [ ] **Step 5: Run** the test → PASS. Then `mvn compile -pl wikantik-main -q`.

- [ ] **Step 6: Commit**

```bash
git add bin/db/migrations/V044__briefing_log.sql wikantik-main/src/main/java/com/wikantik/knowledge/querylog/JdbcBriefingLogService.java wikantik-main/src/main/java/com/wikantik/knowledge/querylog/BriefingLogWiring.java wikantik-main/src/test/java/com/wikantik/knowledge/querylog/JdbcBriefingLogServiceTest.java wikantik-main/src/main/java/com/wikantik/WikiEngine.java
git commit -m "feat(briefing): briefing_log telemetry (V044) with fail-open jdbc writer"
```

---

### Task 7: REST surface — BriefingResource + web.xml

**Suggested model:** sonnet

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/BriefingResource.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (servlet + mapping; RateLimitFilter already covers `/api/*` — no filter change needed)
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/BriefingResourceTest.java`

**Interfaces:**
- Consumes: `getSubsystems().knowledge().briefingAssemblyService()` (Task 5), `MarkdownBriefingRenderer.render` (Task 4), `RestServletBase.filterViewable(req, slugs)`, `BundleCoverage.recount`, `WikiEngine.queryLogService()` + `briefingLogService()` (Task 6), `RetrievalActorClassifier.classify` (copy usage from `BundleResource`).
- Produces: `GET /api/briefing` — params `pins`, `clusters` (comma-separated), `prompt`, `budget` (int), `scope_mode`, `format` (`json` default | `md`).

**Behavior (copy `BundleResource.java` as the template — same structure, same Gson pattern `new GsonBuilder().serializeNulls().create()`):**

1. Parse params. Comma-split helper: `Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()`; `budget` → `Integer.parseInt` in try/catch → 400 `"invalid budget"`; `scope_mode` → `ScopeMode.fromWire` catch `IllegalArgumentException` → 400; `format` must be `json`/`md`/absent else 400.
2. `BriefingRequest req = new BriefingRequest(pins, clusters, prompt, budget, mode)`; if `!req.hasAnySource()` → 400 `"at least one of pins, clusters, prompt is required"`.
3. Service null → 503 (same message pattern as BundleResource).
4. `assemble(req)` — catch `RuntimeException` → `LOG.warn` + 500.
5. **ACL gate:** collect slugs from `briefing.sections()` AND `briefing.items()`; `Set<String> viewable = filterViewable(request, allSlugs)`; drop non-viewable sections AND items (pointer items too — 404-hiding semantics); `coverage = BundleCoverage.recount(briefing.coverage(), keptSections)`; rebuild `ContextBriefing` with same budgets/warnings.
6. Logging (both fail-open, after response is prepared): if prompt present → `qlog.log(prompt, actorType(req), SourceSurface.API_BRIEFING, gated.sections().size())`; briefing log → `blog.log(new BriefingLogEntry(String.join(",", pins), String.join(",", clusters), promptPresent, gated.budgetTokens(), gated.usedTokens(), gated.sections().size(), (int) items included count with origin "pin", (int) pointer count, SourceSurface.API_BRIEFING.wire()))`.
7. `format=md` → `resp.setContentType("text/markdown; charset=UTF-8"); resp.getWriter().write(MarkdownBriefingRenderer.render(gated));` else JSON via the Gson instance.

- [ ] **Step 1: Write the failing tests** — Mockito style copied from `BundleResourceTest.java`. Cases: (a) no params → 400; (b) `pins=A` happy path JSON contains `"items"` and item A (stub service; stub `filterViewable` via a request whose session allows A — follow how BundleResourceTest fakes it); (c) `format=md` → content-type `text/markdown` and body starts `# Wiki context briefing`; (d) service null → 503; (e) bad `scope_mode` → 400; (f) ACL: section slug filtered out → coverage recounted (stub filterViewable path); (g) briefing log receives entry with `surface == "api_briefing"` (capture with `ArgumentCaptor`).

- [ ] **Step 2: Run** `mvn test -pl wikantik-rest -Dtest=BriefingResourceTest -q` → COMPILATION ERROR.

- [ ] **Step 3: Implement** `BriefingResource extends RestServletBase` per behavior above. Protected factory methods (`briefingService()`, `queryLogService()`, `briefingLogService()`, `actorType(req)`) exactly like `BundleResource` so tests can override.

- [ ] **Step 4: web.xml** — beside the `BundleResource` entries in `wikantik-war/src/main/webapp/WEB-INF/web.xml`:

```xml
<servlet>
    <servlet-name>BriefingResource</servlet-name>
    <servlet-class>com.wikantik.rest.BriefingResource</servlet-class>
</servlet>
<servlet-mapping>
    <servlet-name>BriefingResource</servlet-name>
    <url-pattern>/api/briefing</url-pattern>
</servlet-mapping>
```

(Place servlet with the servlets, mapping with the mappings — match the file's existing grouping. `/api/briefing` is NOT an SPA route: no `SpaRoutingFilter` change.)

- [ ] **Step 5: Run** tests → PASS. `mvn compile -pl wikantik-rest -q`.

- [ ] **Step 6: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/BriefingResource.java wikantik-rest/src/test/java/com/wikantik/rest/BriefingResourceTest.java wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(briefing): GET /api/briefing with format=md and ACL-gated recount"
```

---

### Task 8: MCP surface — GetBriefingTool + registration

**Suggested model:** sonnet

**Files:**
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/GetBriefingTool.java`
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java` (register after the `AssembleBundleTool` block, ~line 180)
- Test: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/GetBriefingToolTest.java`

**Interfaces:**
- Consumes: `kg.briefingAssemblyService()` (Task 5), `MarkdownBriefingRenderer` (Task 4 — wikantik-knowledge already depends on wikantik-main), `PageViewGate`, `BundleCoverage.recount`, `WikiEngine.{queryLogService,briefingLogService}` suppliers.
- Produces: MCP tool `get_briefing` (`TOOL_NAME = "get_briefing"`) — the 21st `/knowledge-mcp` tool. **Returns markdown text** (it is an injection payload, not data to parse — deliberate asymmetry vs `assemble_bundle`'s JSON).

**Tool definition** (copy `AssembleBundleTool.definition()` structure): input schema properties: `pins` (array of string), `clusters` (array of string), `prompt` (string), `budget` (number), `scope_mode` (string, `prefer|strict`); required: `[]` (validation happens in execute via `hasAnySource`). Description (verbatim):

> Assemble a session-start context briefing: prompt-refined wiki sections plus pinned pages and cluster members, filled into a token budget with pointers for what did not fit. Returns injection-ready markdown. Call this ONCE at the start of a session with the clusters/pins configured for your project and the user's first request as `prompt`. For follow-up questions use assemble_bundle instead. Does NOT synthesize an answer.

Annotations: same as AssembleBundleTool (`new McpSchema.ToolAnnotations(null, true, false, true, null, null)`).

**execute(arguments):** parse args (arrays via the pattern other tools use for list args — see `ReadPagesTool` for array-of-string parsing); `hasAnySource` false → `McpToolUtils.errorResult("at least one of pins, clusters, prompt is required")`; assemble; gate sections+items by `viewGate.canView(slug)`; recount coverage; log (qlog with `SourceSurface.MCP_GET_BRIEFING` when prompt present; briefing log with surface `mcp_get_briefing`); return the markdown: `new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(MarkdownBriefingRenderer.render(gated))), false)` (check `McpToolUtils` for an existing text-result helper first: `grep -n "TextContent" wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/McpToolUtils.java` — use it if present).

**Registration** in `KnowledgeMcpInitializer.contextInitialized`, immediately after the AssembleBundleTool block:

```java
final com.wikantik.api.briefing.BriefingAssemblyService briefingService = kg.briefingAssemblyService();
if ( briefingService != null ) {
    tools.add( new GetBriefingTool( briefingService,
        () -> engine instanceof com.wikantik.WikiEngine we ? we.queryLogService() : null,
        () -> engine instanceof com.wikantik.WikiEngine we ? we.briefingLogService() : null,
        viewGate ) );
}
```

- [ ] **Step 1: Write the failing tests** — copy the fixture style of `AssembleBundleToolTest.java`. Cases: (a) no args → error result; (b) `pins:["A"]` returns markdown containing `# Wiki context briefing` and page A's title; (c) viewGate denying slug A → A absent from markdown AND absent from pointers; (d) definition name is `get_briefing`, schema has the 5 properties; (e) briefing-log captor sees `surface == "mcp_get_briefing"`; (f) service throwing RuntimeException → `errorResult`, no propagation.

- [ ] **Step 2: Run** `mvn test -pl wikantik-knowledge -Dtest=GetBriefingToolTest -q` → COMPILATION ERROR.

- [ ] **Step 3: Implement** tool + registration.

- [ ] **Step 4: Run** tests → PASS. `mvn compile -pl wikantik-knowledge -q && mvn test-compile -pl wikantik-knowledge -q`.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/GetBriefingTool.java wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/GetBriefingToolTest.java wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java
git commit -m "feat(briefing): get_briefing MCP tool (21st knowledge-mcp tool, markdown payload)"
```

---

### Task 9: Integration tests (Cargo)

**Suggested model:** sonnet

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/BriefingIT.java`

**Interfaces:**
- Consumes: the running Cargo Tomcat (`it-wikantik.base.url` system property), MCP client pattern from `KnowledgeMcpToolsIT.java`, REST pattern from `RestApiIT.java`. Startup fixture pages exist (e.g. `SemanticArticle`, `test-cluster` — see the IT seed lag memory: use startup fixtures, NOT freshly-seeded pages).

- [ ] **Step 1: Write the IT**

One class, both surfaces (copy `KnowledgeMcpToolsIT` setup verbatim for the MCP client):

```java
public class BriefingIT {
    // @BeforeAll: baseUrl + McpSyncClient exactly as KnowledgeMcpToolsIT

    @Test
    void restBriefingMdWithCluster() throws Exception {
        // GET {baseUrl}/api/briefing?clusters=test-cluster&format=md  (java.net.http.HttpClient)
        // assert 200, content-type startsWith "text/markdown", body contains "# Wiki context briefing"
    }

    @Test
    void restBriefingJsonWithPin() throws Exception {
        // GET /api/briefing?pins=SemanticArticle → 200, JSON has "items" array with slug SemanticArticle
    }

    @Test
    void restBriefingNoParams400() throws Exception {
        // GET /api/briefing → 400
    }

    @Test
    void mcpGetBriefingReturnsMarkdown() {
        // mcp.callTool("get_briefing", Map.of("pins", List.of("SemanticArticle")))
        // assert result not error; first content is TextContent starting "# Wiki context briefing"
    }
}
```

Fill in the real HTTP/assert code (RestApiIT shows the HttpClient + Gson response parsing idiom). Check the IT profile's fixture pages first: `grep -rn "test-cluster\|SemanticArticle" wikantik-it-tests/wikantik-it-test-rest/src/test/ | head` — reuse whatever cluster/page names existing ITs assert on; substitute accordingly in all four tests.

- [ ] **Step 2: Run the single IT module**

Run (sequential, from repo root — build the war fresh first, see the stale-overlay gotcha):
`mvn clean install -DskipTests -q && mvn clean verify -Pintegration-tests -pl wikantik-it-tests/wikantik-it-test-rest -am -Dtest=ZZZ_NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=BriefingIT -fae`
Expected: BriefingIT 4/4 PASS. (If the module invocation differs, mirror how other single-IT runs are documented in `docs/ProjectReference.md`.)

- [ ] **Step 3: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/BriefingIT.java
git commit -m "test(briefing): wire-level IT for /api/briefing and get_briefing"
```

---

### Task 10: Claude Code client shim

**Suggested model:** sonnet

**Files:**
- Create: `clients/claude-code/briefing-hook.sh` (chmod +x)
- Create: `clients/claude-code/README.md`

**Interfaces:**
- Consumes: `GET /api/briefing?format=md` (Task 7).
- Produces: a `UserPromptSubmit` hook that injects the briefing once per Claude Code session. Env contract: `WIKANTIK_BASE_URL` (required; hook exits 0 silently if unset), `WIKANTIK_BRIEFING_PINS`, `WIKANTIK_BRIEFING_CLUSTERS`, `WIKANTIK_BRIEFING_BUDGET`, `WIKANTIK_BASIC_AUTH` (optional `user:pass` for curl `-u`).

- [ ] **Step 1: Write the hook**

```bash
#!/usr/bin/env bash
# Wikantik context briefing — UserPromptSubmit hook.
# Injects GET /api/briefing (format=md) into context ONCE per Claude Code session.
# Never fails the prompt: any error exits 0 with no output.
set -uo pipefail

INPUT=$(cat) || exit 0
command -v jq >/dev/null 2>&1 || exit 0
[ -n "${WIKANTIK_BASE_URL:-}" ] || exit 0

SESSION_ID=$(printf '%s' "$INPUT" | jq -r '.session_id // empty')
PROMPT=$(printf '%s' "$INPUT" | jq -r '.prompt // empty')
[ -n "$SESSION_ID" ] || exit 0

STATE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/wikantik-briefing"
mkdir -p "$STATE_DIR" || exit 0
STATE_FILE="$STATE_DIR/$SESSION_ID.done"
[ -e "$STATE_FILE" ] && exit 0
: > "$STATE_FILE"   # mark BEFORE fetching: a broken wiki must not retry every prompt

AUTH_ARGS=()
[ -n "${WIKANTIK_BASIC_AUTH:-}" ] && AUTH_ARGS=(-u "$WIKANTIK_BASIC_AUTH")

RESP=$(curl -fsS --max-time 10 "${AUTH_ARGS[@]}" -G "${WIKANTIK_BASE_URL%/}/api/briefing" \
    --data-urlencode "pins=${WIKANTIK_BRIEFING_PINS:-}" \
    --data-urlencode "clusters=${WIKANTIK_BRIEFING_CLUSTERS:-}" \
    --data-urlencode "prompt=$PROMPT" \
    --data-urlencode "budget=${WIKANTIK_BRIEFING_BUDGET:-}" \
    --data-urlencode "format=md") || exit 0

printf '%s\n' "$RESP"
```

Server note this depends on: `BriefingResource` must treat empty-string `pins`/`clusters`/`budget` params as absent (the comma-split helper in Task 7 already yields empty lists for `""`; the budget parse must skip blank before `parseInt` — verify Task 7 did this, fix there if not).

- [ ] **Step 2: Write README.md** — installation (copy `briefing-hook.sh` into the consuming repo or reference it absolutely; `chmod +x`), the exact `.claude/settings.json` snippet:

```json
{
  "env": {
    "WIKANTIK_BASE_URL": "https://wiki.example.com",
    "WIKANTIK_BRIEFING_CLUSTERS": "billing,onboarding",
    "WIKANTIK_BRIEFING_PINS": "CompanyGoals2026"
  },
  "hooks": {
    "UserPromptSubmit": [
      { "hooks": [ { "type": "command", "command": "$CLAUDE_PROJECT_DIR/clients/claude-code/briefing-hook.sh" } ] }
    ]
  }
}
```

plus the env contract table and a "how it degrades" section (no jq / no URL / wiki down → silent no-op).

- [ ] **Step 3: Test manually against the local deployment**

```bash
chmod +x clients/claude-code/briefing-hook.sh
echo '{"session_id":"test-1","prompt":"how does billing work"}' | \
  WIKANTIK_BASE_URL=http://localhost:8080 WIKANTIK_BRIEFING_PINS=Main ./clients/claude-code/briefing-hook.sh
```

Expected: markdown briefing on stdout. Second identical run: empty output (state file). `rm ~/.cache/wikantik-briefing/test-1.done` to reset. Also verify the failure path: `WIKANTIK_BASE_URL=http://localhost:1 ...` → exit 0, no output.

- [ ] **Step 4: Commit**

```bash
git add clients/claude-code/briefing-hook.sh clients/claude-code/README.md
git commit -m "feat(briefing): claude code UserPromptSubmit hook shim"
```

---

### Task 11: Antigravity shim + wiki-context consumption skill

**Suggested model:** sonnet (research sub-step first)

**Files:**
- Create: `clients/antigravity/README.md`
- Create: `clients/antigravity/wikantik-briefing-rules.md`
- Create: `clients/skills/wiki-context/SKILL.md`

- [ ] **Step 1: Research Antigravity's injection capabilities** (spec calls this out as an explicit early task). Web-search: does Antigravity support (a) session-start hooks or auto-run commands, (b) MCP server config, (c) rules files (AGENTS.md / `.agent/rules`)? Write findings at the top of `clients/antigravity/README.md`. If a deterministic injection mechanism exists, document BOTH paths and prefer it; otherwise proceed with the rules-snippet approach.

- [ ] **Step 2: Write the rules snippet** (`wikantik-briefing-rules.md`) — content to paste into the consuming project's rules file:

```markdown
## Wikantik context briefing

At the START of every new session or task, BEFORE any other work, call the
`get_briefing` tool on the wikantik knowledge MCP server with:
- `clusters`: ["<YOUR-CLUSTERS>"]
- `pins`: ["<YOUR-PINS>"]
- `prompt`: the user's first request, verbatim

Treat the returned briefing as authoritative standing context. For follow-up
questions during the session use `assemble_bundle`; fetch full pages with
`read_pages`. Do not call get_briefing again in the same session.
```

- [ ] **Step 3: Write the consumption skill** `clients/skills/wiki-context/SKILL.md` — frontmatter (`name: wiki-context`, `description: Use when you need company/wiki context to ground a task — session briefings, follow-up retrieval, and escalation over the Wikantik knowledge MCP tools`) + body covering, MCP-only (never curl/REST — portability rule): (1) session start → `get_briefing` with configured pins/clusters + first prompt; (2) follow-ups → `assemble_bundle(query)`, check the coverage block, `strong` → cite and proceed, `partial`/`weak` → escalate; (3) escalation ladder → `retrieve_context` for page discovery, `read_pages` (≤20) for full bodies, `traverse`/`query_nodes` for entity questions, `sparql_query` for counts/enumeration; (4) citation discipline → cite `slug @ version` from citation handles; never claim wiki grounding without a retrieved section.

- [ ] **Step 4: Commit**

```bash
git add clients/antigravity clients/skills/wiki-context
git commit -m "feat(briefing): antigravity rules shim + portable wiki-context consumption skill"
```

---

### Task 12: Docs, final verification, dogfood wiring

**Suggested model:** sonnet (docs) — run verification yourself, don't delegate the full IT reactor to a model that might mislabel failures.

**Files:**
- Modify: `CLAUDE.md` (agent-facing surface table: knowledge-mcp 20→21 tools naming `get_briefing`; new `/api/briefing` row; wikantik-knowledge module bullet)
- Modify: `CHANGELOG.md` (feature entry)
- Modify: `.claude/settings.json` in THIS repo (dogfood: hook + env per Task 10's README, clusters/pins chosen from the live wiki)

- [ ] **Step 1: Update docs.** In CLAUDE.md change every "20 read-only tools"/"20 (fully wired)" knowledge-mcp count to 21 and mention `get_briefing`; add the `/api/briefing` row to the agent-facing surface table (Protocol REST/JSON+markdown, Auth same as `/api/bundle`). CHANGELOG entry under Unreleased: `- Context briefing service: get_briefing MCP tool + GET /api/briefing (session-start context injection for coding agents), briefing_log telemetry (V044), client shims under clients/.`

- [ ] **Step 2: Full unit build.** `mvn clean install -DskipITs` (NO `-T 1C` — see the parallel-flake note; if a provider/security test fails, re-run that class in isolation before treating it as a regression). Expected: BUILD SUCCESS including `apache-rat:check` (new files have headers) and `DecompositionArchTest`.

- [ ] **Step 3: Full IT reactor.** `mvn clean install -Pintegration-tests -fae` (sequential; required before any prod-code push per house rule). Expected: all IT modules green; `BriefingIT` 4/4. Known flake: `EditIT#createPageAndTestEditPermissions` — re-run isolated before treating as a blocker.

- [ ] **Step 4: Migration smoke.** `bin/db/migrate.sh --status` against local (env per CLAUDE.md) shows V044 pending; `bin/deploy-local.sh` applies it; re-run `--status` → applied; re-apply is a no-op.

- [ ] **Step 5: Live smoke on local Tomcat.**

```bash
mvn clean install -DskipTests -T 1C && bin/redeploy.sh
sleep 20
curl -s "http://localhost:8080/api/briefing?pins=Main&format=md" | head -20   # expect "# Wiki context briefing"
curl -s -o /dev/null -w '%{http_code}\n' "http://localhost:8080/api/briefing" # expect 400
psql -h localhost -U wikantik -d wikantik -c "SELECT surface, count(*) FROM briefing_log GROUP BY 1;"  # expect api_briefing rows
```

- [ ] **Step 6: Dogfood wiring.** Add the hook + env to this repo's `.claude/settings.json` (pins/clusters that exist in the live wiki — check with the `list_clusters` MCP tool). Start a fresh Claude Code session, confirm the first prompt injects a briefing.

- [ ] **Step 7: Commit + push.**

```bash
git add CLAUDE.md CHANGELOG.md .claude/settings.json
git commit -m "docs(briefing): surface table, changelog, dogfood hook wiring"
git push origin main
```

---

## Post-launch backlog (do NOT build now — spec §Out of scope)

Session dedup state; "what changed since last session" diffs; re-refinement on later prompts; eval arm; briefing admin dashboard over `briefing_log`; prod deploy + `wiki.wikantik.com` rollout (separate release per the release runbook).
