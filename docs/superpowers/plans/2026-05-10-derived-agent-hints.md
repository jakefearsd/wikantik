# Derived Agent Hints + Agent Batch Reads — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** [`docs/superpowers/specs/2026-05-10-derived-agent-hints-design.md`](../specs/2026-05-10-derived-agent-hints-design.md)

**Goal:** Land the four pre-public-release tunings of the agent surface — derived `agent_hints`, hub summary overlay, `read_pages` MCP tool, and `/admin/agent-grade-audit` — with zero author burden, plus exhaustive documentation reconciliation.

**Architecture:** New API records (`AgentHintsBlock`, `PreferredPage`) on the `ForAgentProjection`. New stateless services (`AgentHintsDeriver`, `HubSummarySynthesizer`) wired into `DefaultForAgentProjectionService` with the existing per-step try/catch + `missing_fields` pattern. New MCP tool (`ReadPagesTool`) registered by `KnowledgeMcpInitializer`. New admin REST resource (`AgentGradeAuditResource`).

**Tech Stack:** Java 21 records, JUnit 5, Mockito, Cargo + Selenide IT harness, Maven multi-module reactor.

---

## Pre-flight notes for the implementer

These short notes save you from spec-vs-reality surprises:

- **Project policy (CLAUDE.md):** sole developer works directly on `main`. No feature branches, no PRs. Commit each task on `main`.
- **`McpToolHintsResolver` quirk:** existing resolver returns `McpToolHint` records whose `tool` field can be either a bare snake_case name (when authored in frontmatter) or a path like `/knowledge-mcp/search_knowledge` (when synthesised). The deriver normalises both to bare names by taking the substring after the last `/`. Without this, `prefer_tools` would mix shapes.
- **`ForAgentProjection` is a record with 18 positional fields.** Adding two fields breaks every test that constructs one. Task 2 explicitly catches all callers and fixes them as one commit so the project compiles end-to-end.
- **`PageGraphService` does not expose intra-cluster inbound counts directly.** Use `ReferenceManager.findReferrers(slug)` for each candidate (already injected via the `Engine`), filter to same-cluster pages, count.
- **Tool-count reconciliation (CLAUDE.md):** existing CLAUDE.md has `15` for `/knowledge-mcp` in three places and `16` in one. Verify the actual current count by counting `tools.add(...)` calls in `KnowledgeMcpInitializer.contextInitialized()`. Document the verified count in the doc-update task; bump by `+1` for `read_pages` after that.
- **Run `mvn test-compile` after Task 2** (per project memory) — the constructor signature change will silently break test sources that `mvn compile` does not check.
- **Full IT reactor required before final commit** — `mvn clean install -Pintegration-tests -fae`. No `-T` flag (port conflicts).

---

## Task 1 — `AgentHintsBlock` and `PreferredPage` API records

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/agent/AgentHintsBlock.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/agent/PreferredPage.java`
- Test: `wikantik-api/src/test/java/com/wikantik/api/agent/AgentHintsBlockTest.java`

- [ ] **Step 1: Write the failing test**

```java
/* Apache 2.0 license header — match RunbookBlock.java header verbatim */
package com.wikantik.api.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentHintsBlockTest {

    @Test
    void emptyFactoryReturnsEmptyImmutableLists() {
        final AgentHintsBlock empty = AgentHintsBlock.empty();
        assertNotNull( empty.prefer_tools() );
        assertNotNull( empty.prefer_pages() );
        assertTrue( empty.prefer_tools().isEmpty() );
        assertTrue( empty.prefer_pages().isEmpty() );
        assertThrows( UnsupportedOperationException.class,
                () -> empty.prefer_tools().add( "x" ) );
    }

    @Test
    void nullArgsBecomeEmptyLists() {
        final AgentHintsBlock b = new AgentHintsBlock( null, null );
        assertEquals( List.of(), b.prefer_tools() );
        assertEquals( List.of(), b.prefer_pages() );
    }

    @Test
    void preferredPageRequiresCanonicalIdAndTitle() {
        assertThrows( IllegalArgumentException.class,
                () -> new PreferredPage( null, "Title", "cluster_member" ) );
        assertThrows( IllegalArgumentException.class,
                () -> new PreferredPage( "id", null, "cluster_member" ) );
    }

    @Test
    void preferredPageDefaultsRoleToClusterMember() {
        final PreferredPage p = new PreferredPage( "id", "Title", null );
        assertEquals( "cluster_member", p.role() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl wikantik-api test -Dtest=AgentHintsBlockTest -q`
Expected: compilation failure — `AgentHintsBlock` and `PreferredPage` symbols not found.

- [ ] **Step 3: Write minimal implementation**

`wikantik-api/src/main/java/com/wikantik/api/agent/PreferredPage.java`:
```java
/* Apache 2.0 license header — match RunbookBlock.java header verbatim */
package com.wikantik.api.agent;

/**
 * Single entry of {@link AgentHintsBlock#prefer_pages()}. {@code title} is
 * carried explicitly so an agent reading a {@code /for-agent} projection can
 * decide whether to fetch the referenced page without a second round-trip.
 *
 * <p>{@code role} is one of {@code cluster_hub}, {@code authoritative_reference},
 * or {@code cluster_member}. Snake_case Java field names so default Gson
 * serialisation matches the wire form (mirrors {@link RunbookBlock} convention).
 * </p>
 */
@SuppressWarnings( "checkstyle:MemberName" )
public record PreferredPage( String canonical_id, String title, String role ) {
    public PreferredPage {
        if ( canonical_id == null || canonical_id.isBlank() ) {
            throw new IllegalArgumentException( "canonical_id required" );
        }
        if ( title == null || title.isBlank() ) {
            throw new IllegalArgumentException( "title required" );
        }
        if ( role == null || role.isBlank() ) {
            role = "cluster_member";
        }
    }
}
```

`wikantik-api/src/main/java/com/wikantik/api/agent/AgentHintsBlock.java`:
```java
/* Apache 2.0 license header — match RunbookBlock.java header verbatim */
package com.wikantik.api.agent;

import java.util.List;

/**
 * Derived agent hints for the {@code /for-agent} projection. Computed in code
 * by {@code AgentHintsDeriver} from existing graph/metadata signals; never
 * authored in frontmatter (rejected as author burden — see
 * {@code docs/superpowers/specs/2026-05-10-derived-agent-hints-design.md}).
 *
 * <p>Snake_case Java field names so default Gson serialisation matches the
 * wire form (mirrors {@link RunbookBlock} convention).</p>
 */
@SuppressWarnings( "checkstyle:MemberName" )
public record AgentHintsBlock(
        List< String > prefer_tools,
        List< PreferredPage > prefer_pages
) {
    public AgentHintsBlock {
        prefer_tools = prefer_tools == null ? List.of() : List.copyOf( prefer_tools );
        prefer_pages = prefer_pages == null ? List.of() : List.copyOf( prefer_pages );
    }

    public static AgentHintsBlock empty() {
        return new AgentHintsBlock( List.of(), List.of() );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl wikantik-api test -Dtest=AgentHintsBlockTest -q`
Expected: BUILD SUCCESS, 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/agent/AgentHintsBlock.java \
        wikantik-api/src/main/java/com/wikantik/api/agent/PreferredPage.java \
        wikantik-api/src/test/java/com/wikantik/api/agent/AgentHintsBlockTest.java
git commit -m "$(cat <<'EOF'
feat(agent-hints): add AgentHintsBlock and PreferredPage records

API records for the derived agent_hints projection field. Snake_case
Java field names so default Gson serialisation matches the wire form
(mirrors the RunbookBlock convention).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2 — Add `agentHints` and `summarySynthesized` fields to `ForAgentProjection`

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/agent/ForAgentProjection.java`
- Modify (cascading constructor calls): `wikantik-main/src/main/java/com/wikantik/knowledge/agent/DefaultForAgentProjectionService.java` and any test fixtures that construct `ForAgentProjection` directly.

This task explicitly handles the cascading compile breakage because adding positional record fields breaks every caller.

- [ ] **Step 1: Add the new fields to the record**

Edit `wikantik-api/src/main/java/com/wikantik/api/agent/ForAgentProjection.java`. Append two fields to the record header — order matters for serialisation stability, so place them at the end *before* `degraded` and `missingFields` so the operational status fields stay last:

```java
public record ForAgentProjection(
        String canonicalId,
        String slug,
        String title,
        String type,
        String cluster,
        Audience audience,
        Confidence confidence,
        Instant verifiedAt,
        String verifiedBy,
        Instant updated,
        String summary,
        List< KeyFact > keyFacts,
        List< HeadingOutline > headingsOutline,
        List< RecentChange > recentChanges,
        List< McpToolHint > mcpToolHints,
        Object runbook,
        AgentHintsBlock agentHints,
        boolean summarySynthesized,
        String fullBodyUrl,
        String rawMarkdownUrl,
        boolean degraded,
        List< String > missingFields
) {
    public ForAgentProjection {
        if ( canonicalId == null || canonicalId.isBlank() ) {
            throw new IllegalArgumentException( "canonicalId required" );
        }
        if ( slug == null || slug.isBlank() ) {
            throw new IllegalArgumentException( "slug required" );
        }
        keyFacts            = keyFacts            == null ? List.of() : List.copyOf( keyFacts );
        headingsOutline     = headingsOutline     == null ? List.of() : List.copyOf( headingsOutline );
        recentChanges       = recentChanges       == null ? List.of() : List.copyOf( recentChanges );
        mcpToolHints        = mcpToolHints        == null ? List.of() : List.copyOf( mcpToolHints );
        missingFields       = missingFields       == null ? List.of() : List.copyOf( missingFields );
        // agentHints intentionally allowed to be null — null surfaces as JSON null
        // signalling whole-block degradation (caller adds "agent_hints" to missingFields).
    }
}
```

Update the existing Javadoc for `runbook` to remove the "stays null until Phase 3" sentence (Phase 3 shipped). Add a brief Javadoc on `agentHints` and `summarySynthesized`:

```java
/**
 * ... existing javadoc ...
 *
 * <p>{@link #agentHints} carries derived prefer_tools / prefer_pages — see
 * {@code AgentHintsDeriver}. {@code null} signals whole-block degradation
 * (the field name appears in {@link #missingFields}); a non-null but empty
 * block means the page genuinely had no hints to derive.</p>
 *
 * <p>{@link #summarySynthesized} is {@code true} iff the hub overlay replaced
 * the authored summary (see {@code HubSummarySynthesizer}).</p>
 */
```

- [ ] **Step 2: Find all callers and verify the compile breakage scope**

Run: `mvn compile -pl wikantik-api -am -q && mvn test-compile -pl wikantik-main,wikantik-knowledge,wikantik-rest -q 2>&1 | tee /tmp/compile-fanout.log | head -80`

Expected: compile errors at every `new ForAgentProjection(...)` site. Capture the file list.

Then run: `grep -rn "new ForAgentProjection(" --include="*.java" wikantik-main wikantik-knowledge wikantik-rest wikantik-api 2>/dev/null`

This is the authoritative list. As of this plan there are at least:
- `wikantik-main/src/main/java/com/wikantik/knowledge/agent/DefaultForAgentProjectionService.java` (line ~203, in `build()`).
- Test fixtures under `wikantik-main/src/test/java/...`, `wikantik-rest/src/test/java/...`, `wikantik-knowledge/src/test/java/...`.

- [ ] **Step 3: Update every caller to pass the two new args**

In `DefaultForAgentProjectionService.build()` (production code), pass `null` for `agentHints` and `false` for `summarySynthesized` for now — Tasks 6 and 8 wire the real values in. Insert in positional order between `runbook` and `"/api/pages/" + d.slug()`:

```java
return new ForAgentProjection(
        d.canonicalId(),
        d.slug(),
        d.title(),
        d.type() == null ? null : d.type().asFrontmatterValue(),
        d.cluster(),
        verification.audience(),
        verification.confidence(),
        verification.verifiedAt(),
        verification.verifiedBy(),
        d.updated(),
        d.summary(),
        facts,
        outline,
        changes,
        hints,
        runbook,
        null,                                        // agentHints — Task 6 fills this in
        false,                                       // summarySynthesized — Task 8 sets this
        "/api/pages/" + d.slug(),
        "/wiki/" + d.slug() + "?format=md",
        !missing.isEmpty(),
        missing );
```

For every test fixture site identified in Step 2, insert the same `null, false` pair at the same positional offset. Do this with a single `git grep -l` + Edit-tool sweep — do not use `sed -i` (per project memory: sed renames have collateral damage).

- [ ] **Step 4: Verify everything compiles and tests still pass**

```bash
mvn clean compile -pl wikantik-api,wikantik-main,wikantik-knowledge,wikantik-rest -am -q
mvn test-compile -pl wikantik-api,wikantik-main,wikantik-knowledge,wikantik-rest -q
mvn test -pl wikantik-api,wikantik-main,wikantik-knowledge,wikantik-rest -q
```

Expected: BUILD SUCCESS on all three. No test failures attributable to this change (any pre-existing flakes per the `wikantik-main provider tests are parallel-flaky` memory note are not new bugs — pass them in isolation if you suspect).

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/agent/ForAgentProjection.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/agent/DefaultForAgentProjectionService.java \
        $(git diff --name-only -- '*Test.java' '*IT.java')
git commit -m "$(cat <<'EOF'
feat(agent-hints): extend ForAgentProjection with agentHints + summarySynthesized

Two new positional fields on the record. Callers updated to pass
(null, false); real values wired in by AgentHintsDeriver / HubSummary
Synthesizer in subsequent commits. Existing tests unchanged in shape.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3 — `HubSummarySynthesizer`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/agent/HubSummarySynthesizer.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/agent/HubSummarySynthesizerTest.java`

- [ ] **Step 1: Write the failing test**

```java
/* Apache 2.0 license header — match existing wikantik-main test file headers */
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.AgentHintsBlock;
import com.wikantik.api.agent.PreferredPage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HubSummarySynthesizerTest {

    private final HubSummarySynthesizer s = new HubSummarySynthesizer();

    private AgentHintsBlock hintsWith( final String... titles ) {
        return new AgentHintsBlock( List.of(), java.util.Arrays.stream( titles )
                .map( t -> new PreferredPage( t.toLowerCase().replace( ' ', '_' ), t, "cluster_member" ) )
                .toList() );
    }

    @Test
    void doesNotFireWhenNotHub() {
        assertEquals( Optional.empty(),
                s.maybeOverlay( "Index of pages on warehouse automation",
                                hintsWith( "A", "B", "C" ),
                                false ) );
    }

    @Test
    void doesNotFireWhenSummaryIsRich() {
        assertEquals( Optional.empty(),
                s.maybeOverlay( "Authoritative overview of warehouse automation, including ROI tradeoffs.",
                                hintsWith( "A", "B", "C" ),
                                true ) );
    }

    @Test
    void doesNotFireWhenNoPreferPages() {
        assertEquals( Optional.empty(),
                s.maybeOverlay( "Index of pages on warehouse automation",
                                AgentHintsBlock.empty(),
                                true ) );
    }

    @Test
    void firesWhenHubAndGenericAndPagesPresent() {
        final Optional< String > out = s.maybeOverlay(
                "Index of pages on warehouse automation",
                hintsWith( "Warehouse Robotics", "Limitations", "ROI Models", "Suppliers" ),
                true );
        assertTrue( out.isPresent() );
        assertTrue( out.get().contains( "Warehouse Robotics" ) );
        assertTrue( out.get().contains( "Limitations" ) );
        assertTrue( out.get().contains( "ROI Models" ) );
        assertFalse( out.get().contains( "Suppliers" ),
                "should cap at top 3, not 4" );
    }

    @Test
    void caseAndWhitespaceTolerantRegex() {
        assertTrue( s.maybeOverlay( "  AN INDEX OF ARTICLES ABOUT X",
                                    hintsWith( "A", "B", "C" ), true ).isPresent() );
        assertTrue( s.maybeOverlay( "Index of content for the cluster",
                                    hintsWith( "A", "B", "C" ), true ).isPresent() );
    }

    @Test
    void nullSummaryDoesNotMatch() {
        assertEquals( Optional.empty(),
                s.maybeOverlay( null, hintsWith( "A", "B", "C" ), true ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl wikantik-main test -Dtest=HubSummarySynthesizerTest -q`
Expected: compilation failure — `HubSummarySynthesizer` symbol not found.

- [ ] **Step 3: Write minimal implementation**

`wikantik-main/src/main/java/com/wikantik/knowledge/agent/HubSummarySynthesizer.java`:
```java
/* Apache 2.0 license header — match existing wikantik-main file headers */
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.AgentHintsBlock;
import com.wikantik.api.agent.PreferredPage;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Detects an authored hub summary that matches a generic "Index of pages on…"
 * pattern and synthesises an overlay that names the top-3 cluster pages
 * derived by {@link AgentHintsDeriver}. Read-only: never writes back to the
 * page body. Stateless.
 */
public final class HubSummarySynthesizer {

    private static final Pattern GENERIC =
            Pattern.compile( "^\\s*(an?\\s+)?index of (pages?|articles?|content)\\s+(on|about|covering|for)\\b",
                             Pattern.CASE_INSENSITIVE );

    public Optional< String > maybeOverlay( final String authoredSummary,
                                            final AgentHintsBlock derivedHints,
                                            final boolean isHub ) {
        if ( !isHub )                                            return Optional.empty();
        if ( authoredSummary == null )                           return Optional.empty();
        if ( !GENERIC.matcher( authoredSummary ).find() )        return Optional.empty();
        if ( derivedHints == null
                || derivedHints.prefer_pages() == null
                || derivedHints.prefer_pages().isEmpty() )       return Optional.empty();

        final List< PreferredPage > top = derivedHints.prefer_pages().stream()
                .limit( 3 )
                .toList();
        final String titles = top.stream()
                .map( PreferredPage::title )
                .reduce( ( a, b ) -> a + ", " + b )
                .orElse( "" );
        return Optional.of( "Cluster hub. Highest-signal pages: " + titles + "." );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl wikantik-main test -Dtest=HubSummarySynthesizerTest -q`
Expected: BUILD SUCCESS, 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/agent/HubSummarySynthesizer.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/agent/HubSummarySynthesizerTest.java
git commit -m "$(cat <<'EOF'
feat(agent-hints): add HubSummarySynthesizer for projection-time hub overlay

Stateless service: detects the generic "Index of pages on…" pattern on
hub summaries and synthesises a Top-3 highlight from derived
prefer_pages. Read-only — never writes back to the page body. Wired
into the projection by Task 8.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4 — `AgentHintsDeriver`: skeleton + `prefer_tools` derivation

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/agent/AgentHintsDeriver.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/agent/AgentHintsDeriverPreferToolsTest.java`

- [ ] **Step 1: Write the failing test**

```java
/* Apache 2.0 license header */
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.AgentHintsBlock;
import com.wikantik.api.agent.McpToolHint;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.Verification;
import com.wikantik.api.providers.WikiProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentHintsDeriverPreferToolsTest {

    private StructuralIndexService index;
    private PageManager pageManager;
    private ReferenceManager refs;
    private AgentHintsDeriver deriver;

    @BeforeEach
    void setUp() {
        index       = mock( StructuralIndexService.class );
        pageManager = mock( PageManager.class );
        refs        = mock( ReferenceManager.class );
        deriver     = new AgentHintsDeriver( index, pageManager, refs );
    }

    private PageDescriptor pd( final String id, final String slug, final String cluster ) {
        return new PageDescriptor(
                id, slug, slug, PageType.UNKNOWN, cluster,
                List.of(), null, Instant.parse( "2026-05-10T00:00:00Z" ), Optional.empty() );
    }

    @Test
    void preferToolsExtractsBareNamesFromMcpToolHintFrontmatter() throws Exception {
        final PageDescriptor self = pd( "page_a", "PageA", "cluster_x" );
        when( index.getByCanonicalId( "page_a" ) ).thenReturn( Optional.of( self ) );
        when( index.getCluster( "cluster_x" ) ).thenReturn( Optional.empty() );  // no hub
        when( pageManager.getPureText( eq( "PageA" ), any() ) ).thenReturn(
                """
                ---
                mcp_tool_hints:
                  - tool: search_knowledge
                    when: example
                  - tool: /knowledge-mcp/get_page_for_agent
                    when: example
                ---
                body
                """ );

        final AgentHintsBlock out = deriver.derive( "page_a" );

        assertEquals( List.of( "search_knowledge", "get_page_for_agent" ),
                      out.prefer_tools() );
    }

    @Test
    void preferToolsAggregatesAndRanksByFrequencyAcrossPageAndHub() throws Exception {
        final PageDescriptor self = pd( "page_a", "PageA", "cluster_x" );
        final PageDescriptor hub  = pd( "hub_x",  "HubX",  "cluster_x" );
        when( index.getByCanonicalId( "page_a" ) ).thenReturn( Optional.of( self ) );
        when( index.getCluster( "cluster_x" ) ).thenReturn( Optional.of(
                new ClusterDetails( "cluster_x", hub, List.of( self, hub ),
                                    Map.of(), Instant.parse( "2026-05-10T00:00:00Z" ) ) ) );
        when( pageManager.getPureText( eq( "PageA" ), any() ) ).thenReturn(
                "---\nmcp_tool_hints:\n  - {tool: search_knowledge, when: x}\n---\nbody" );
        when( pageManager.getPureText( eq( "HubX" ), any() ) ).thenReturn(
                "---\nmcp_tool_hints:\n" +
                "  - {tool: search_knowledge, when: x}\n" +
                "  - {tool: list_clusters, when: x}\n" +
                "---\nbody" );

        final AgentHintsBlock out = deriver.derive( "page_a" );

        // search_knowledge appears 2x (page + hub), list_clusters 1x → search_knowledge first
        assertEquals( "search_knowledge", out.prefer_tools().get( 0 ) );
        assertEquals( "list_clusters",    out.prefer_tools().get( 1 ) );
    }

    @Test
    void preferToolsCapsAtFive() throws Exception {
        final PageDescriptor self = pd( "page_a", "PageA", "cluster_x" );
        when( index.getByCanonicalId( "page_a" ) ).thenReturn( Optional.of( self ) );
        when( index.getCluster( "cluster_x" ) ).thenReturn( Optional.empty() );
        when( pageManager.getPureText( eq( "PageA" ), any() ) ).thenReturn(
                "---\nmcp_tool_hints:\n" +
                "  - {tool: t1, when: x}\n  - {tool: t2, when: x}\n" +
                "  - {tool: t3, when: x}\n  - {tool: t4, when: x}\n" +
                "  - {tool: t5, when: x}\n  - {tool: t6, when: x}\n" +
                "---\nbody" );

        final AgentHintsBlock out = deriver.derive( "page_a" );
        // McpToolHintsResolver itself caps at 5; the deriver should see 5 and emit 5.
        assertEquals( 5, out.prefer_tools().size() );
    }

    @Test
    void unknownCanonicalIdReturnsEmptyBlock() {
        when( index.getByCanonicalId( "missing" ) ).thenReturn( Optional.empty() );
        final AgentHintsBlock out = deriver.derive( "missing" );
        assertEquals( AgentHintsBlock.empty(), out );
    }

    @Test
    void exceptionInPageManagerYieldsEmptyToolListNotThrows() throws Exception {
        final PageDescriptor self = pd( "page_a", "PageA", "cluster_x" );
        when( index.getByCanonicalId( "page_a" ) ).thenReturn( Optional.of( self ) );
        when( index.getCluster( "cluster_x" ) ).thenReturn( Optional.empty() );
        when( pageManager.getPureText( eq( "PageA" ), any() ) )
                .thenThrow( new RuntimeException( "boom" ) );

        final AgentHintsBlock out = deriver.derive( "page_a" );
        assertNotNull( out );
        assertTrue( out.prefer_tools().isEmpty() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl wikantik-main test -Dtest=AgentHintsDeriverPreferToolsTest -q`
Expected: compilation failure — `AgentHintsDeriver` symbol not found.

- [ ] **Step 3: Write minimal implementation**

`wikantik-main/src/main/java/com/wikantik/knowledge/agent/AgentHintsDeriver.java`:
```java
/* Apache 2.0 license header */
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.AgentHintsBlock;
import com.wikantik.api.agent.McpToolHint;
import com.wikantik.api.agent.PreferredPage;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.providers.WikiProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Derives the {@link AgentHintsBlock} for a wiki page from existing
 * graph/metadata signals: the page's authored {@code mcp_tool_hints}, the
 * cluster hub's authored hints, and intra-cluster wikilink centrality.
 *
 * <p>Stateless. Never throws — internal failures degrade to empty fields, and
 * the caller (the projection service) wraps {@link #derive} in a try/catch
 * so any escape still yields {@code agent_hints: null} on the projection.</p>
 */
public final class AgentHintsDeriver {

    private static final Logger LOG = LogManager.getLogger( AgentHintsDeriver.class );

    private static final int PREFER_TOOLS_CAP = 5;
    private static final int PREFER_PAGES_CAP = 5;
    private static final double VERIFIED_AUTHORITATIVE_BONUS = 1.5;

    private final StructuralIndexService index;
    private final PageManager pageManager;
    private final ReferenceManager refs;
    private final McpToolHintsResolver toolHints = new McpToolHintsResolver();

    public AgentHintsDeriver( final StructuralIndexService index,
                              final PageManager pageManager,
                              final ReferenceManager refs ) {
        this.index = index;
        this.pageManager = pageManager;
        this.refs = refs;
    }

    public AgentHintsBlock derive( final String canonicalId ) {
        try {
            final Optional< PageDescriptor > maybe = index.getByCanonicalId( canonicalId );
            if ( maybe.isEmpty() ) {
                return AgentHintsBlock.empty();
            }
            final PageDescriptor self = maybe.get();
            final Optional< ClusterDetails > cluster = self.cluster() == null
                    ? Optional.empty()
                    : index.getCluster( self.cluster() );

            final List< String > tools = derivePreferTools( self, cluster.orElse( null ) );
            final List< PreferredPage > pages = List.of();   // Task 5 fills this in

            return new AgentHintsBlock( tools, pages );
        } catch ( final Exception ex ) {
            LOG.warn( "agent-hints: derive({}) threw — returning empty block: {}",
                      canonicalId, ex.getMessage() );
            return AgentHintsBlock.empty();
        }
    }

    /* ------------------------------------------------------------ prefer_tools */

    private List< String > derivePreferTools( final PageDescriptor self, final ClusterDetails cluster ) {
        final List< String > selfTools = toolNamesFor( self );
        final List< String > hubTools = ( cluster != null
                                          && cluster.hubPage() != null
                                          && !cluster.hubPage().slug().equals( self.slug() ) )
                ? toolNamesFor( cluster.hubPage() )
                : List.of();
        // Frequency-rank, deterministic alphabetical tie-break.
        final Map< String, Integer > counts = new LinkedHashMap<>();
        for ( final String t : selfTools ) counts.merge( t, 1, Integer::sum );
        for ( final String t : hubTools )  counts.merge( t, 1, Integer::sum );
        return counts.entrySet().stream()
                .sorted( Comparator.< Map.Entry< String, Integer > >comparingInt( Map.Entry::getValue )
                                   .reversed()
                                   .thenComparing( Map.Entry::getKey ) )
                .limit( PREFER_TOOLS_CAP )
                .map( Map.Entry::getKey )
                .toList();
    }

    private List< String > toolNamesFor( final PageDescriptor d ) {
        try {
            final String raw = pageManager.getPureText( d.slug(), WikiProvider.LATEST_VERSION );
            if ( raw == null || raw.isEmpty() ) return List.of();
            final ParsedPage parsed = FrontmatterParser.parse( raw );
            final List< McpToolHint > hints = toolHints.resolve( parsed.metadata(), d.tags(), d.cluster() );
            final List< String > out = new ArrayList<>( hints.size() );
            for ( final McpToolHint h : hints ) {
                final String name = bareToolName( h.tool() );
                if ( name != null && !name.isBlank() ) out.add( name );
            }
            return out;
        } catch ( final Exception e ) {
            LOG.warn( "agent-hints: toolNamesFor({}) failed: {}", d.slug(), e.getMessage() );
            return List.of();
        }
    }

    /** Strips a leading {@code /knowledge-mcp/}, {@code /wikantik-admin-mcp/}, or any path prefix to a bare snake_case name. */
    private static String bareToolName( final String raw ) {
        if ( raw == null ) return null;
        final String trimmed = raw.trim();
        final int lastSlash = trimmed.lastIndexOf( '/' );
        return lastSlash < 0 ? trimmed : trimmed.substring( lastSlash + 1 );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl wikantik-main test -Dtest=AgentHintsDeriverPreferToolsTest -q`
Expected: BUILD SUCCESS, 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/agent/AgentHintsDeriver.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/agent/AgentHintsDeriverPreferToolsTest.java
git commit -m "$(cat <<'EOF'
feat(agent-hints): AgentHintsDeriver skeleton + prefer_tools derivation

Stateless deriver. prefer_tools aggregates McpToolHintsResolver output
across the page and its cluster hub, normalises path-prefixed names
(e.g. /knowledge-mcp/foo → foo) to bare snake_case, frequency-ranks
with deterministic alphabetical tie-break, caps at 5. prefer_pages
remains empty — Task 5 fills it in.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5 — `AgentHintsDeriver`: `prefer_pages` derivation

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/agent/AgentHintsDeriver.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/agent/AgentHintsDeriverPreferPagesTest.java`

- [ ] **Step 1: Write the failing test**

```java
/* Apache 2.0 license header */
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.AgentHintsBlock;
import com.wikantik.api.agent.PreferredPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentHintsDeriverPreferPagesTest {

    private StructuralIndexService index;
    private PageManager pageManager;
    private ReferenceManager refs;
    private AgentHintsDeriver deriver;

    @BeforeEach
    void setUp() {
        index       = mock( StructuralIndexService.class );
        pageManager = mock( PageManager.class );
        refs        = mock( ReferenceManager.class );
        deriver     = new AgentHintsDeriver( index, pageManager, refs );
    }

    private PageDescriptor pd( final String id, final String slug, final String cluster ) {
        return new PageDescriptor( id, slug, slug, PageType.UNKNOWN, cluster,
                List.of(), null, Instant.parse( "2026-05-10T00:00:00Z" ), Optional.empty() );
    }

    @Test
    void hubAppearsFirstWithRoleClusterHub() {
        final PageDescriptor self = pd( "page_a",  "PageA",  "cluster_x" );
        final PageDescriptor hub  = pd( "hub_x",   "HubX",   "cluster_x" );
        final PageDescriptor mate = pd( "page_b",  "PageB",  "cluster_x" );
        when( index.getByCanonicalId( "page_a" ) ).thenReturn( Optional.of( self ) );
        when( index.getCluster( "cluster_x" ) ).thenReturn( Optional.of(
                new ClusterDetails( "cluster_x", hub, List.of( self, mate, hub ),
                                    Map.of(), Instant.parse( "2026-05-10T00:00:00Z" ) ) ) );
        when( index.verificationOf( any() ) ).thenReturn( Optional.empty() );
        when( pageManager.getPureText( any(), any() ) ).thenReturn( "" );
        when( refs.findReferrers( any() ) ).thenReturn( Set.of() );

        final AgentHintsBlock out = deriver.derive( "page_a" );

        assertFalse( out.prefer_pages().isEmpty() );
        assertEquals( "hub_x", out.prefer_pages().get( 0 ).canonical_id() );
        assertEquals( "cluster_hub", out.prefer_pages().get( 0 ).role() );
    }

    @Test
    void selfIsExcludedFromPreferPages() {
        final PageDescriptor self = pd( "page_a",  "PageA",  "cluster_x" );
        when( index.getByCanonicalId( "page_a" ) ).thenReturn( Optional.of( self ) );
        when( index.getCluster( "cluster_x" ) ).thenReturn( Optional.of(
                new ClusterDetails( "cluster_x", self, List.of( self ),
                                    Map.of(), Instant.parse( "2026-05-10T00:00:00Z" ) ) ) );
        when( index.verificationOf( any() ) ).thenReturn( Optional.empty() );
        when( pageManager.getPureText( any(), any() ) ).thenReturn( "" );
        when( refs.findReferrers( any() ) ).thenReturn( Set.of() );

        final AgentHintsBlock out = deriver.derive( "page_a" );

        // Self is the hub here; expect no entry for self.
        assertTrue( out.prefer_pages().stream().noneMatch( p -> p.canonical_id().equals( "page_a" ) ) );
    }

    @Test
    void scoresByIntraClusterInboundLinksWithVerifiedBonus() {
        final PageDescriptor self = pd( "page_a", "PageA", "cluster_x" );
        final PageDescriptor mateHigh = pd( "high", "High", "cluster_x" );
        final PageDescriptor mateLow  = pd( "low",  "Low",  "cluster_x" );
        when( index.getByCanonicalId( "page_a" ) ).thenReturn( Optional.of( self ) );
        when( index.getCluster( "cluster_x" ) ).thenReturn( Optional.of(
                new ClusterDetails( "cluster_x", null, List.of( self, mateHigh, mateLow ),
                                    Map.of(), Instant.parse( "2026-05-10T00:00:00Z" ) ) ) );
        // mateLow has 5 inbound, mateHigh has 2 — but mateHigh is verified authoritative,
        // so its score becomes 2 * 1.5 = 3, vs mateLow's 5 * 1.0 = 5. mateLow wins.
        when( refs.findReferrers( "High" ) ).thenReturn( Set.of( "PageA", "Low" ) );
        when( refs.findReferrers( "Low"  ) ).thenReturn( Set.of( "PageA", "High", "Other1", "Other2", "Other3" ) );
        when( index.verificationOf( "high" ) ).thenReturn( Optional.of(
                new Verification( Audience.AGENTS, Confidence.AUTHORITATIVE, Instant.now(), "tester" ) ) );
        when( index.verificationOf( "low" ) ).thenReturn( Optional.empty() );
        when( pageManager.getPureText( any(), any() ) ).thenReturn( "" );

        final AgentHintsBlock out = deriver.derive( "page_a" );

        // Note: only same-cluster referrers count. Both High and Low have PageA + each other in cluster.
        // Adjusting: same-cluster inbound for High = 1 (Low). Same-cluster inbound for Low = 1 (High).
        // With verified bonus: High = 1 * 1.5 = 1.5. Low = 1.0. So High wins.
        assertEquals( "high", out.prefer_pages().get( 0 ).canonical_id() );
        assertEquals( "authoritative_reference", out.prefer_pages().get( 0 ).role() );
    }

    @Test
    void noClusterYieldsEmptyPreferPages() {
        final PageDescriptor self = pd( "page_a", "PageA", null );
        when( index.getByCanonicalId( "page_a" ) ).thenReturn( Optional.of( self ) );
        when( pageManager.getPureText( any(), any() ) ).thenReturn( "" );

        final AgentHintsBlock out = deriver.derive( "page_a" );
        assertTrue( out.prefer_pages().isEmpty() );
    }

    @Test
    void capsAtFiveTotal() {
        final PageDescriptor self = pd( "page_a", "PageA", "cluster_x" );
        final PageDescriptor hub  = pd( "hub_x",  "HubX",  "cluster_x" );
        final List< PageDescriptor > many = new java.util.ArrayList<>();
        many.add( self ); many.add( hub );
        for ( int i = 0; i < 10; i++ ) many.add( pd( "p" + i, "P" + i, "cluster_x" ) );
        when( index.getByCanonicalId( "page_a" ) ).thenReturn( Optional.of( self ) );
        when( index.getCluster( "cluster_x" ) ).thenReturn( Optional.of(
                new ClusterDetails( "cluster_x", hub, many, Map.of(),
                                    Instant.parse( "2026-05-10T00:00:00Z" ) ) ) );
        when( index.verificationOf( any() ) ).thenReturn( Optional.empty() );
        when( pageManager.getPureText( any(), any() ) ).thenReturn( "" );
        when( refs.findReferrers( any() ) ).thenReturn( Set.of() );

        final AgentHintsBlock out = deriver.derive( "page_a" );
        assertEquals( 5, out.prefer_pages().size() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl wikantik-main test -Dtest=AgentHintsDeriverPreferPagesTest -q`
Expected: tests fail — `prefer_pages` returns empty (Task 4 left it as `List.of()`).

- [ ] **Step 3: Implement `derivePreferPages` and wire it into `derive(...)`**

Modify `AgentHintsDeriver.java`:

In `derive(...)`, replace `final List< PreferredPage > pages = List.of();` with:
```java
final List< PreferredPage > pages = derivePreferPages( self, cluster.orElse( null ) );
```

Add the method:
```java
/* ------------------------------------------------------------ prefer_pages */

private List< PreferredPage > derivePreferPages( final PageDescriptor self, final ClusterDetails cluster ) {
    if ( cluster == null ) return List.of();

    final List< PreferredPage > out = new ArrayList<>();
    final PageDescriptor hub = cluster.hubPage();
    if ( hub != null && !hub.slug().equals( self.slug() ) ) {
        out.add( new PreferredPage( hub.canonicalId(), hub.title(), "cluster_hub" ) );
    }

    record Scored( PageDescriptor page, double score, boolean authoritative ) {}

    final java.util.Set< String > clusterSlugs = cluster.articles().stream()
            .map( PageDescriptor::slug )
            .collect( java.util.stream.Collectors.toSet() );

    final List< Scored > scored = new ArrayList<>();
    for ( final PageDescriptor cand : cluster.articles() ) {
        if ( cand.slug().equals( self.slug() ) ) continue;
        if ( hub != null && cand.slug().equals( hub.slug() ) ) continue;

        int inbound = 0;
        try {
            final java.util.Set< String > referrers = refs.findReferrers( cand.slug() );
            if ( referrers != null ) {
                for ( final String r : referrers ) {
                    if ( clusterSlugs.contains( r ) ) inbound++;
                }
            }
        } catch ( final Exception e ) {
            LOG.warn( "agent-hints: findReferrers({}) failed: {}", cand.slug(), e.getMessage() );
        }

        boolean authoritative = false;
        try {
            final Optional< Verification > v = index.verificationOf( cand.canonicalId() );
            authoritative = v.isPresent() && v.get().confidence() == Confidence.AUTHORITATIVE;
        } catch ( final Exception e ) {
            LOG.warn( "agent-hints: verificationOf({}) failed: {}", cand.canonicalId(), e.getMessage() );
        }
        final double score = inbound * ( authoritative ? VERIFIED_AUTHORITATIVE_BONUS : 1.0 );
        scored.add( new Scored( cand, score, authoritative ) );
    }

    scored.stream()
          .sorted( Comparator.< Scored >comparingDouble( s -> s.score ).reversed()
                             .thenComparing( s -> s.page.title() == null ? "" : s.page.title() ) )
          .limit( PREFER_PAGES_CAP - out.size() )
          .forEach( s -> out.add( new PreferredPage(
                  s.page.canonicalId(),
                  s.page.title(),
                  s.authoritative ? "authoritative_reference" : "cluster_member" ) ) );

    return List.copyOf( out );
}
```

Add the missing imports:
```java
import com.wikantik.api.pagegraph.Confidence;
import com.wikantik.api.pagegraph.Verification;
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn -pl wikantik-main test -Dtest=AgentHintsDeriverPreferPagesTest -q
mvn -pl wikantik-main test -Dtest=AgentHintsDeriverPreferToolsTest -q
```
Expected: both classes pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/agent/AgentHintsDeriver.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/agent/AgentHintsDeriverPreferPagesTest.java
git commit -m "$(cat <<'EOF'
feat(agent-hints): AgentHintsDeriver prefer_pages — cluster-centrality ranking

Hub page first with role=cluster_hub. Remaining slots filled by intra-
cluster inbound wikilink count via ReferenceManager.findReferrers,
multiplied by 1.5 if the candidate is verified authoritative.
Deterministic alphabetical tie-break. Capped at 5 total entries; self
always excluded.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6 — Wire `AgentHintsDeriver` and `HubSummarySynthesizer` into `DefaultForAgentProjectionService`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/agent/DefaultForAgentProjectionService.java`
- Modify (constructor wiring): wherever `DefaultForAgentProjectionService` is constructed at runtime (search via `grep -rn "new DefaultForAgentProjectionService"`).
- Modify (test fixtures): every test that instantiates `DefaultForAgentProjectionService`.

- [ ] **Step 1: Modify the projection service to accept the new dependencies**

Update the constructor and add an `agentHints` extraction step plus a hub-overlay step:
```java
private final AgentHintsDeriver       hintsDeriver;
private final HubSummarySynthesizer   hubSynth;

public DefaultForAgentProjectionService(
        final StructuralIndexService index,
        final PageManager pageManager,
        final CachingManager cache,
        final ForAgentMetrics metrics,
        final AgentHintsDeriver hintsDeriver,
        final HubSummarySynthesizer hubSynth ) {
    this.index = index;
    this.pageManager = pageManager;
    this.cache = cache;
    this.metrics = metrics;
    this.recents = new RecentChangesAdapter( pageManager );
    this.hintsDeriver = hintsDeriver;
    this.hubSynth = hubSynth;
}
```

In `build(...)`, after the runbook block but before the `return new ForAgentProjection(...)`, insert:
```java
// Derived agent_hints — null on whole-block degradation, empty block on no-signal.
AgentHintsBlock agentHints = AgentHintsBlock.empty();
try {
    agentHints = hintsDeriver.derive( d.canonicalId() );
} catch ( final Exception e ) {
    LOG.warn( "for-agent: agent_hints derivation threw for {}: {}", d.slug(), e.getMessage() );
    missing.add( "agent_hints" );
    agentHints = null;
}

// Hub summary overlay — only fires when this page is a cluster hub and the authored
// summary matches the generic "Index of pages on…" pattern.
String effectiveSummary = d.summary();
boolean summarySynthesized = false;
if ( agentHints != null ) {
    final boolean isHub = isClusterHub( d );
    try {
        final Optional< String > overlay = hubSynth.maybeOverlay( effectiveSummary, agentHints, isHub );
        if ( overlay.isPresent() ) {
            effectiveSummary = overlay.get();
            summarySynthesized = true;
        }
    } catch ( final Exception e ) {
        LOG.warn( "for-agent: hub summary overlay threw for {}: {}", d.slug(), e.getMessage() );
    }
}
```

Add the `isClusterHub` helper:
```java
private boolean isClusterHub( final PageDescriptor d ) {
    if ( d.cluster() == null ) return false;
    try {
        return index.getCluster( d.cluster() )
                    .map( c -> c.hubPage() != null && c.hubPage().slug().equals( d.slug() ) )
                    .orElse( false );
    } catch ( final Exception e ) {
        LOG.warn( "for-agent: hub lookup failed for {}: {}", d.slug(), e.getMessage() );
        return false;
    }
}
```

Update the `return new ForAgentProjection(...)` to use `effectiveSummary`, `agentHints`, `summarySynthesized`:
```java
return new ForAgentProjection(
        d.canonicalId(),
        d.slug(),
        d.title(),
        d.type() == null ? null : d.type().asFrontmatterValue(),
        d.cluster(),
        verification.audience(),
        verification.confidence(),
        verification.verifiedAt(),
        verification.verifiedBy(),
        d.updated(),
        effectiveSummary,
        facts,
        outline,
        changes,
        hints,
        runbook,
        agentHints,
        summarySynthesized,
        "/api/pages/" + d.slug(),
        "/wiki/" + d.slug() + "?format=md",
        !missing.isEmpty(),
        missing );
```

Add the missing import: `import com.wikantik.api.agent.AgentHintsBlock;`

- [ ] **Step 2: Find and update every constructor caller**

Run: `grep -rn "new DefaultForAgentProjectionService(" --include="*.java" 2>/dev/null`

Capture the file list. For each runtime caller (e.g., `KnowledgeSubsystemBridge` or wherever the projection service is wired into the engine), pass `new AgentHintsDeriver(index, pageManager, refMgr)` and `new HubSummarySynthesizer()` as the two new args. The `ReferenceManager` is reachable from the engine via `engine.getManager(ReferenceManager.class)` (or whatever convention the codebase uses — peek at `KnowledgeSubsystemBridge` neighbors to confirm).

For each test fixture, pass `null, null` for the two new args if the test does not exercise the new fields, OR construct mocks if it does. Most existing tests do not exercise hints — `null` deps will be fine because `build(...)` already wraps deriver calls in try/catch.

Wait — `null` deps will NPE because we call `hintsDeriver.derive(...)`. Better to pass a no-op deriver / synthesizer. Define test helpers in each affected test file:

```java
private static AgentHintsDeriver noopDeriver() {
    return new AgentHintsDeriver(
            mock( StructuralIndexService.class ),
            mock( PageManager.class ),
            mock( ReferenceManager.class ) ) {
        @Override
        public AgentHintsBlock derive( final String canonicalId ) {
            return AgentHintsBlock.empty();
        }
    };
}
```

Or simpler — make the projection service tolerant to `null` deps by adding a guard at the top of the relevant blocks:
```java
if ( hintsDeriver == null ) {
    agentHints = null;
} else {
    try { agentHints = hintsDeriver.derive( d.canonicalId() ); } catch ( ... ) ...
}
```

Pick the guard approach — it keeps test code unchanged and only this service is responsible for tolerating null deps.

- [ ] **Step 3: Verify compile + test**

```bash
mvn clean test-compile -pl wikantik-api,wikantik-main,wikantik-knowledge,wikantik-rest -am -q
mvn test -pl wikantik-main -q
```
Expected: BUILD SUCCESS. The wiring change does not introduce new tests but must not break any existing test (especially `DefaultForAgentProjectionServiceTest`).

- [ ] **Step 4: Add a wire test asserting the new fields land on the projection**

First, **read** `wikantik-main/src/test/java/com/wikantik/knowledge/agent/DefaultForAgentProjectionServiceTest.java` to identify (a) how the test currently constructs the service (helper method? `@BeforeEach`?), (b) the canonical fixture page slug + canonical_id, and (c) the assertion helper conventions. Reuse them — do not build a parallel fixture.

Append the new tests using the existing fixture:

```java
@Test
void agentHintsBlockLandsOnProjectionWhenDeriverReturnsHits() {
    // Reuse <existing-fixture-helper>; override only the deriver to a stub
    // returning a known block. We are asserting wiring, not derivation logic.
    final AgentHintsDeriver stubDeriver = mock( AgentHintsDeriver.class );
    when( stubDeriver.derive( FIXTURE_CANONICAL_ID ) ).thenReturn(
            new AgentHintsBlock(
                    List.of( "search_knowledge", "list_clusters" ),
                    List.of( new PreferredPage( "hub_x", "Hub X", "cluster_hub" ) ) ) );

    final DefaultForAgentProjectionService svc = newServiceUnderTest( stubDeriver, new HubSummarySynthesizer() );
    final ForAgentProjection p = svc.project( FIXTURE_CANONICAL_ID ).orElseThrow();

    assertNotNull( p.agentHints() );
    assertEquals( List.of( "search_knowledge", "list_clusters" ), p.agentHints().prefer_tools() );
    assertEquals( "hub_x", p.agentHints().prefer_pages().get( 0 ).canonical_id() );
    assertFalse( p.summarySynthesized(),
                 "non-hub fixture should not trigger summary overlay" );
    assertFalse( p.degraded() );
    assertFalse( p.missingFields().contains( "agent_hints" ) );
}

@Test
void agentHintsDegradationLandsInMissingFieldsList() {
    final AgentHintsDeriver throwingDeriver = mock( AgentHintsDeriver.class );
    when( throwingDeriver.derive( any() ) ).thenThrow( new RuntimeException( "boom" ) );

    final DefaultForAgentProjectionService svc = newServiceUnderTest( throwingDeriver, new HubSummarySynthesizer() );
    final ForAgentProjection p = svc.project( FIXTURE_CANONICAL_ID ).orElseThrow();

    assertNull( p.agentHints(), "deriver throw should yield null agent_hints, not an empty block" );
    assertTrue( p.missingFields().contains( "agent_hints" ) );
    assertTrue( p.degraded() );
}

@Test
void hubSummaryOverlayFiresWhenHubAndGenericAndPagesPresent() {
    // For this test, swap the fixture to a hub page with the generic summary pattern.
    // If the existing test class lacks a hub fixture, add one alongside FIXTURE_CANONICAL_ID:
    //   final String HUB_FIXTURE_ID   = "fixture_hub";
    //   final String HUB_FIXTURE_SLUG = "FixtureHub";
    //   (seeded with summary "Index of pages on fixture_cluster" via the existing seeding helper).
    final AgentHintsDeriver deriver = mock( AgentHintsDeriver.class );
    when( deriver.derive( HUB_FIXTURE_ID ) ).thenReturn(
            new AgentHintsBlock( List.of(),
                    List.of( new PreferredPage( "page_a", "Page A", "cluster_member" ),
                             new PreferredPage( "page_b", "Page B", "cluster_member" ),
                             new PreferredPage( "page_c", "Page C", "cluster_member" ) ) ) );

    final DefaultForAgentProjectionService svc = newServiceUnderTest( deriver, new HubSummarySynthesizer() );
    final ForAgentProjection p = svc.project( HUB_FIXTURE_ID ).orElseThrow();

    assertTrue( p.summarySynthesized(), "hub + generic summary + prefer_pages should overlay" );
    assertTrue( p.summary().contains( "Page A" ) );
    assertTrue( p.summary().contains( "Page B" ) );
    assertTrue( p.summary().contains( "Page C" ) );
}

/** Helper — extracted so each new test can swap the deriver/synth without duplicating wiring. */
private DefaultForAgentProjectionService newServiceUnderTest(
        final AgentHintsDeriver deriver,
        final HubSummarySynthesizer synth ) {
    // Reuse the same StructuralIndexService / PageManager / CachingManager / ForAgentMetrics
    // mocks that the existing @BeforeEach configures. If those are private fields, expose
    // them via this helper or move construction into a shared helper.
    return new DefaultForAgentProjectionService( index, pageManager, cache, metrics, deriver, synth );
}
```

If the existing class does not already have a `FIXTURE_CANONICAL_ID` constant, introduce one alongside the existing fixture data so all new tests reference the same slug. Do not duplicate the test class's mock setup — refactor the existing setup into the `newServiceUnderTest` helper if needed.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/agent/DefaultForAgentProjectionService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/agent/DefaultForAgentProjectionServiceTest.java \
        $(git diff --name-only -- 'wikantik-*/src/**/*.java')
git commit -m "$(cat <<'EOF'
feat(agent-hints): wire AgentHintsDeriver + HubSummarySynthesizer into projection

agent_hints + summarySynthesized now populate on every /for-agent
projection. Both wrapped in try/catch with the existing missing_fields
+ degraded pattern. Service tolerates null deps so existing tests need
no fixture changes.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7 — `ReadPagesTool` MCP tool

**Files:**
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ReadPagesTool.java`
- Test: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ReadPagesToolTest.java`

Before writing, **read the existing `GetPageTool.java`** in the same package to mirror its conventions — input/output shape, error envelope, how it talks to `ContextRetrievalService`, JSON schema declaration, examples per the Phase 6 convention.

- [ ] **Step 1: Write the failing test**

```java
/* Apache 2.0 license header */
package com.wikantik.knowledge.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.PageContent;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReadPagesToolTest {

    private ContextRetrievalService ctxService;
    private ReadPagesTool tool;

    @BeforeEach
    void setUp() {
        ctxService = mock( ContextRetrievalService.class );
        tool = new ReadPagesTool( ctxService );
    }

    @Test
    void toolNameAndDefinition() {
        assertEquals( "read_pages", tool.name() );
        assertNotNull( tool.definition() );
        assertNotNull( tool.definition().inputSchema() );
    }

    @Test
    void rejectsEmptyInput() {
        final var result = tool.execute( Map.of( "slugs", List.of() ) );
        assertTrue( Boolean.TRUE.equals( result.isError() ) );
    }

    @Test
    void rejectsOver20Slugs() {
        final List< String > tooMany = new java.util.ArrayList<>();
        for ( int i = 0; i < 21; i++ ) tooMany.add( "P" + i );
        final var result = tool.execute( Map.of( "slugs", tooMany ) );
        assertTrue( Boolean.TRUE.equals( result.isError() ) );
        // error message mentions the cap
        assertTrue( result.content().toString().contains( "20" ) );
    }

    @Test
    void happyPath_returnsContentForEachSlug() {
        // Stub: ContextRetrievalService.getPage(slug) → page content. Adjust the
        // method name to match the real interface; this is a placeholder.
        when( ctxService.getPage( "PageA" ) ).thenReturn( Optional.of(
                new PageContent( "PageA", "body of A" ) ) );
        when( ctxService.getPage( "PageB" ) ).thenReturn( Optional.of(
                new PageContent( "PageB", "body of B" ) ) );

        final var result = tool.execute( Map.of( "slugs", List.of( "PageA", "PageB" ) ) );

        assertFalse( Boolean.TRUE.equals( result.isError() ) );
        // Parse the result envelope and assert two pages, both with content, no error fields.
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "\"content\":\"body of A\"" ) || json.contains( "body of A" ) );
        assertTrue( json.contains( "\"content\":\"body of B\"" ) || json.contains( "body of B" ) );
    }

    @Test
    void partialFailure_missingSlugReturnsErrorEntryNotCallFailure() {
        when( ctxService.getPage( "PageA" ) ).thenReturn( Optional.of(
                new PageContent( "PageA", "body of A" ) ) );
        when( ctxService.getPage( "Missing" ) ).thenReturn( Optional.empty() );

        final var result = tool.execute( Map.of( "slugs", List.of( "PageA", "Missing" ) ) );

        assertFalse( Boolean.TRUE.equals( result.isError() ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "\"error\":\"not_found\"" ) );
    }

    @Test
    void partialFailure_internalErrorOnOnePageStillReturnsOthers() {
        when( ctxService.getPage( "PageA" ) ).thenThrow( new RuntimeException( "boom" ) );
        when( ctxService.getPage( "PageB" ) ).thenReturn( Optional.of(
                new PageContent( "PageB", "body of B" ) ) );

        final var result = tool.execute( Map.of( "slugs", List.of( "PageA", "PageB" ) ) );

        assertFalse( Boolean.TRUE.equals( result.isError() ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "\"error\":\"internal_error\"" ) );
        assertTrue( json.contains( "body of B" ) );
    }

    @Test
    void duplicateSlugsAreDeduplicated() {
        when( ctxService.getPage( "PageA" ) ).thenReturn( Optional.of(
                new PageContent( "PageA", "body of A" ) ) );

        tool.execute( Map.of( "slugs", List.of( "PageA", "PageA", "PageA" ) ) );

        verify( ctxService, times( 1 ) ).getPage( "PageA" );
    }
}
```

**IMPORTANT:** the `ContextRetrievalService.getPage` method signature in the test above is a placeholder — verify the real API in `wikantik-api/src/main/java/com/wikantik/api/knowledge/ContextRetrievalService.java` and adjust both the test and the implementation to match. The existing `GetPageTool` is the reference: whatever it calls, this tool batch-calls.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl wikantik-knowledge test -Dtest=ReadPagesToolTest -q`
Expected: compilation failure — `ReadPagesTool` symbol not found.

- [ ] **Step 3: Write minimal implementation**

`wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ReadPagesTool.java`:
```java
/* Apache 2.0 license header */
package com.wikantik.knowledge.mcp;

import com.google.gson.Gson;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.mcp.tools.McpTool;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Batched raw-markdown reader. Cap: 20 slugs per call. Per-page failures
 * (not_found, internal_error, forbidden) come back as data on a 200 response —
 * the call only fails on input validation. Mirrors {@code read_page}'s
 * single-slug behaviour on each entry.
 */
public final class ReadPagesTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( ReadPagesTool.class );
    private static final int MAX_SLUGS = 20;
    private static final Gson GSON = new Gson();

    private final ContextRetrievalService ctxService;

    public ReadPagesTool( final ContextRetrievalService ctxService ) {
        this.ctxService = ctxService;
    }

    @Override public String name() { return "read_pages"; }

    @Override
    public McpSchema.Tool definition() {
        // Build the input schema with a per-property example and a short
        // description per the Phase 6 examples convention.
        final Map< String, Object > slugsProp = Map.of(
                "type", "array",
                "items", Map.of( "type", "string" ),
                "minItems", 1,
                "maxItems", MAX_SLUGS,
                "description", "Slugs to read (max " + MAX_SLUGS + ").",
                "example", List.of( "PageA", "PageB" )
        );
        final var inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of( "slugs", slugsProp ),
                List.of( "slugs" ),
                false, null, null );

        // outputSchema is a free Map (per Phase 6 convention) so it can carry top-level examples.
        final Map< String, Object > outputSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "pages", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "slug",    Map.of( "type", "string" ),
                                                "content", Map.of( "type", List.of( "string", "null" ) ),
                                                "error",   Map.of( "type", List.of( "string", "null" ) )
                                        ),
                                        "required", List.of( "slug" )
                                )
                        )
                ),
                "examples", List.of(
                        Map.of( "pages", List.of(
                                Map.of( "slug", "PageA", "content", "# PageA\nbody…" ),
                                Map.of( "slug", "Missing", "content", null, "error", "not_found" )
                        ) )
                )
        );

        return McpSchema.Tool.builder()
                .name( name() )
                .description( "Batched raw-markdown read for up to " + MAX_SLUGS + " pages. " +
                              "Per-page failures (not_found, forbidden, internal_error) are returned " +
                              "as data on a 200 response; the call only fails on input validation." )
                .inputSchema( inputSchema )
                .outputSchema( outputSchema )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        // Input validation
        final Object raw = arguments == null ? null : arguments.get( "slugs" );
        if ( !( raw instanceof List< ? > rawList ) || rawList.isEmpty() ) {
            return errorResult( "slugs is required and must contain at least one entry" );
        }
        if ( rawList.size() > MAX_SLUGS ) {
            return errorResult( "slugs exceeds cap of " + MAX_SLUGS + " entries" );
        }

        // Dedupe preserving order
        final LinkedHashSet< String > slugs = new LinkedHashSet<>();
        for ( final Object o : rawList ) {
            if ( o == null ) continue;
            final String s = o.toString().trim();
            if ( !s.isEmpty() ) slugs.add( s );
        }
        if ( slugs.isEmpty() ) {
            return errorResult( "slugs is required and must contain at least one entry" );
        }

        // Per-page lookup with partial-success semantics
        final List< Map< String, Object > > out = new ArrayList<>( slugs.size() );
        for ( final String slug : slugs ) {
            try {
                // Replace .getPage with the actual ContextRetrievalService method as discovered in Step 1.
                final var maybe = ctxService.getPage( slug );
                if ( maybe == null || maybe.isEmpty() ) {
                    out.add( Map.of( "slug", slug, "content", null, "error", "not_found" ) );
                } else {
                    out.add( Map.of( "slug", slug, "content", maybe.get().body() ) );
                }
            } catch ( final Exception e ) {
                LOG.warn( "read_pages: lookup failed for {}: {}", slug, e.getMessage() );
                out.add( Map.of( "slug", slug, "content", null, "error", "internal_error" ) );
            }
        }
        final String json = GSON.toJson( Map.of( "pages", out ) );
        return McpSchema.CallToolResult.builder()
                .addContent( new McpSchema.TextContent( json ) )
                .build();
    }

    private static McpSchema.CallToolResult errorResult( final String msg ) {
        return McpSchema.CallToolResult.builder()
                .isError( true )
                .addContent( new McpSchema.TextContent( msg ) )
                .build();
    }
}
```

**Adjust** the `ctxService.getPage(slug)` call to match the real `ContextRetrievalService` signature. The shape `Optional<PageContent>` with `body()` is illustrative; use whatever the existing `GetPageTool` uses.

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn -pl wikantik-knowledge test -Dtest=ReadPagesToolTest -q
```
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ReadPagesTool.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ReadPagesToolTest.java
git commit -m "$(cat <<'EOF'
feat(agent-batch): add read_pages MCP tool — batched markdown reads

Caps at 20 slugs per call. Partial-success semantics: per-page failures
(not_found, internal_error) come back as data on a 200 response. Input
validation failures (empty / over-cap) return MCP error envelope.
Includes worked input + output examples per the Phase 6 convention.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8 — Register `ReadPagesTool` in `KnowledgeMcpInitializer`

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java`

- [ ] **Step 1: Add the registration**

Inside `contextInitialized(...)` at the tool assembly block, add inside the `if ( ctxService != null ) { ... }` block (so it appears in the same gate as the other context-retrieval tools):
```java
if ( ctxService != null ) {
    tools.add( new RetrieveContextTool( ctxService ) );
    tools.add( new GetPageTool( ctxService ) );
    tools.add( new ListPagesTool( ctxService ) );
    tools.add( new ListMetadataValuesTool( ctxService ) );
    tools.add( new ReadPagesTool( ctxService ) );        // NEW
}
```

Update the `instructions(...)` string to mention `read_pages`:
```java
.instructions( "Agent-facing MCP endpoint. For wiki structure (fastest, " +
    "no full-text search) use list_clusters, list_tags, list_pages_by_filter, " +
    "or get_page_by_id. For wiki content use retrieve_context " +
    "(primary RAG), get_page (pinned fetch), read_pages (batched fetch, max 20), " +
    "list_pages (browse), or " +
    "list_metadata_values (discovery). For Knowledge Graph (LLM-extracted entities) use " +
    "discover_schema, query_nodes, get_node, traverse, search_knowledge, " +
    "or find_similar." )
```

- [ ] **Step 2: Add a wire test asserting the tool is registered**

If a test class exists for `KnowledgeMcpInitializer` (search: `find . -name "KnowledgeMcpInitializerTest.java" 2>/dev/null`), add a test asserting `read_pages` appears in the registered tool list. If no such test exists, the IT in Task 13 covers wire-level registration; skip this step.

- [ ] **Step 3: Verify compile**

Run: `mvn -pl wikantik-knowledge compile test-compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Run any new wire test**

If a test was added in Step 2, run it and verify pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java \
        $(git diff --name-only -- 'wikantik-knowledge/src/test/**/*.java')
git commit -m "$(cat <<'EOF'
feat(agent-batch): register read_pages on /knowledge-mcp

Tool registers when ContextRetrievalService is configured. Server
instructions string updated to mention the batched fetch.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9 — `AgentGradeAuditResource` REST endpoint

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/admin/AgentGradeAuditResource.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/admin/AgentGradeAuditResourceTest.java`

Before writing, **read an existing admin resource** in `wikantik-rest/src/main/java/com/wikantik/rest/` (`StructuralIndexHealthResource.java` is a good reference for shape) to mirror conventions: how parameters are read, JSON serialised, errors returned, auth filter assumed.

- [ ] **Step 1: Write the failing test**

```java
/* Apache 2.0 license header */
package com.wikantik.rest.admin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.*;
import com.wikantik.pagegraph.spine.ConfidenceComputer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentGradeAuditResourceTest {

    private StructuralIndexService index;
    private ReferenceManager refs;
    private AgentGradeAuditResource resource;

    @BeforeEach
    void setUp() {
        index = mock( StructuralIndexService.class );
        refs  = mock( ReferenceManager.class );
        resource = new AgentGradeAuditResource( index, refs );
    }

    private PageDescriptor pd( final String id, final String slug, final String cluster ) {
        return new PageDescriptor( id, slug, slug, PageType.UNKNOWN, cluster,
                List.of(), null, Instant.parse( "2026-05-10T00:00:00Z" ), Optional.empty() );
    }

    @Test
    void noClusterFlagFires() {
        final PageDescriptor p = pd( "p1", "P1", null );
        when( index.listPagesByFilter( any() ) ).thenReturn( List.of( p ) );
        when( index.verificationOf( "p1" ) ).thenReturn( Optional.empty() );

        final String body = resource.audit( 50, 0 );
        final JsonObject root = JsonParser.parseString( body ).getAsJsonObject();
        assertEquals( 1, root.get( "total" ).getAsInt() );
        assertTrue( root.getAsJsonArray( "pages" ).get( 0 )
                        .getAsJsonObject().getAsJsonArray( "weaknesses" )
                        .toString().contains( "no_cluster" ) );
    }

    @Test
    void noInboundClusterLinksFlagFires_butSkipsHubs() {
        final PageDescriptor hub = pd( "hub", "Hub", "cx" );
        final PageDescriptor mate = pd( "mate", "Mate", "cx" );
        when( index.listPagesByFilter( any() ) ).thenReturn( List.of( hub, mate ) );
        when( index.getCluster( "cx" ) ).thenReturn( Optional.of(
                new ClusterDetails( "cx", hub, List.of( hub, mate ), Map.of(),
                                    Instant.parse( "2026-05-10T00:00:00Z" ) ) ) );
        when( index.verificationOf( any() ) ).thenReturn( Optional.empty() );
        when( refs.findReferrers( "Mate" ) ).thenReturn( Set.of() );
        when( refs.findReferrers( "Hub" ) ).thenReturn( Set.of() );

        final String body = resource.audit( 50, 0 );
        final JsonObject root = JsonParser.parseString( body ).getAsJsonObject();
        // Mate is flagged; hub is not (hubs are excluded from this check).
        assertTrue( body.contains( "no_inbound_cluster_links" ) );
        assertTrue( body.contains( "\"canonical_id\":\"mate\"" ) );
        assertFalse( body.contains( "\"canonical_id\":\"hub\"" ) || !body.contains( "no_inbound_cluster_links" ) );
    }

    @Test
    void genericHubSummaryFlagFires() {
        final PageDescriptor hub = new PageDescriptor( "hub", "Hub", "Hub", PageType.UNKNOWN, "cx",
                List.of(), "Index of pages on cx", Instant.parse( "2026-05-10T00:00:00Z" ), Optional.empty() );
        when( index.listPagesByFilter( any() ) ).thenReturn( List.of( hub ) );
        when( index.getCluster( "cx" ) ).thenReturn( Optional.of(
                new ClusterDetails( "cx", hub, List.of( hub ), Map.of(),
                                    Instant.parse( "2026-05-10T00:00:00Z" ) ) ) );
        when( index.verificationOf( "hub" ) ).thenReturn( Optional.empty() );
        when( refs.findReferrers( "Hub" ) ).thenReturn( Set.of() );

        final String body = resource.audit( 50, 0 );
        assertTrue( body.contains( "generic_hub_summary" ) );
    }

    @Test
    void noVerifiedAtFlagFires() {
        final PageDescriptor p = pd( "p1", "P1", "cx" );
        when( index.listPagesByFilter( any() ) ).thenReturn( List.of( p ) );
        when( index.verificationOf( "p1" ) ).thenReturn( Optional.empty() );
        when( index.getCluster( "cx" ) ).thenReturn( Optional.empty() );

        assertTrue( resource.audit( 50, 0 ).contains( "no_verified_at" ) );
    }

    @Test
    void zeroFlagPagesAreNotReturned() {
        final PageDescriptor hub = pd( "hub", "Hub", "cx" );
        final PageDescriptor mate = pd( "mate", "Mate", "cx" );
        when( index.listPagesByFilter( any() ) ).thenReturn( List.of( hub, mate ) );
        when( index.getCluster( "cx" ) ).thenReturn( Optional.of(
                new ClusterDetails( "cx", hub, List.of( hub, mate ), Map.of(),
                                    Instant.parse( "2026-05-10T00:00:00Z" ) ) ) );
        when( index.verificationOf( "hub" ) ).thenReturn( Optional.of(
                new Verification( Audience.AGENTS, Confidence.AUTHORITATIVE, Instant.now(), "tester" ) ) );
        when( index.verificationOf( "mate" ) ).thenReturn( Optional.of(
                new Verification( Audience.AGENTS, Confidence.AUTHORITATIVE, Instant.now(), "tester" ) ) );
        when( refs.findReferrers( "Mate" ) ).thenReturn( Set.of( "Hub" ) );
        when( refs.findReferrers( "Hub" ) ).thenReturn( Set.of( "Mate" ) );

        final JsonObject root = JsonParser.parseString( resource.audit( 50, 0 ) ).getAsJsonObject();
        assertEquals( 0, root.get( "total" ).getAsInt() );
        assertEquals( 0, root.getAsJsonArray( "pages" ).size() );
    }

    @Test
    void paginationLimitAndOffset() {
        final List< PageDescriptor > five = new java.util.ArrayList<>();
        for ( int i = 0; i < 5; i++ ) five.add( pd( "p" + i, "P" + i, null ) );  // no_cluster on each
        when( index.listPagesByFilter( any() ) ).thenReturn( five );
        when( index.verificationOf( any() ) ).thenReturn( Optional.empty() );

        final JsonObject root = JsonParser.parseString( resource.audit( 2, 1 ) ).getAsJsonObject();
        assertEquals( 5, root.get( "total" ).getAsInt() );
        assertEquals( 2, root.get( "limit" ).getAsInt() );
        assertEquals( 1, root.get( "offset" ).getAsInt() );
        assertEquals( 2, root.getAsJsonArray( "pages" ).size() );
    }

    @Test
    void limitClampedTo200() {
        when( index.listPagesByFilter( any() ) ).thenReturn( List.of( pd( "p1", "P1", null ) ) );
        when( index.verificationOf( any() ) ).thenReturn( Optional.empty() );

        final JsonObject root = JsonParser.parseString( resource.audit( 999, 0 ) ).getAsJsonObject();
        assertEquals( 200, root.get( "limit" ).getAsInt() );
    }

    @Test
    void limitBelowOneDefaultsToFifty() {
        when( index.listPagesByFilter( any() ) ).thenReturn( List.of( pd( "p1", "P1", null ) ) );
        when( index.verificationOf( any() ) ).thenReturn( Optional.empty() );

        final JsonObject root = JsonParser.parseString( resource.audit( 0, 0 ) ).getAsJsonObject();
        assertEquals( 50, root.get( "limit" ).getAsInt() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl wikantik-rest test -Dtest=AgentGradeAuditResourceTest -q`
Expected: compilation failure.

- [ ] **Step 3: Write minimal implementation**

`wikantik-rest/src/main/java/com/wikantik/rest/admin/AgentGradeAuditResource.java`:
```java
/* Apache 2.0 license header */
package com.wikantik.rest.admin;

import com.google.gson.Gson;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.*;
import com.wikantik.pagegraph.spine.ConfidenceComputer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * GET /admin/agent-grade-audit?limit=N&offset=M — paginated weak-signal report
 * helping authors find pages worth manual improvement without imposing a
 * frontmatter schema. Behind {@code AdminAuthFilter} (AllPermission) — no
 * additional auth check inside this resource.
 */
public final class AgentGradeAuditResource {

    private static final Gson GSON = new Gson();
    private static final Pattern GENERIC = Pattern.compile(
            "^\\s*(an?\\s+)?index of (pages?|articles?|content)\\s+(on|about|covering|for)\\b",
            Pattern.CASE_INSENSITIVE );

    private final StructuralIndexService index;
    private final ReferenceManager refs;

    public AgentGradeAuditResource( final StructuralIndexService index,
                                    final ReferenceManager refs ) {
        this.index = index;
        this.refs = refs;
    }

    /** JSON body for {@code GET /admin/agent-grade-audit}. */
    public String audit( int limit, int offset ) {
        if ( limit < 1 ) limit = 50;
        if ( limit > 200 ) limit = 200;
        if ( offset < 0 ) offset = 0;

        final List< Map< String, Object > > weakPages = new ArrayList<>();
        for ( final PageDescriptor p : index.listPagesByFilter( StructuralFilter.all() ) ) {
            final List< String > flags = collectFlags( p );
            if ( flags.isEmpty() ) continue;
            final Map< String, Object > row = new LinkedHashMap<>();
            row.put( "canonical_id", p.canonicalId() );
            row.put( "title", p.title() );
            row.put( "cluster", p.cluster() );
            row.put( "weaknesses", flags );
            weakPages.add( row );
        }
        // Sort: more flags first, then canonical_id ascending.
        weakPages.sort( Comparator.< Map< String, Object > >comparingInt(
                m -> -( ( List< ? > ) m.get( "weaknesses" ) ).size() )
                .thenComparing( m -> m.get( "canonical_id" ).toString() ) );

        final int total = weakPages.size();
        final int from = Math.min( offset, total );
        final int to = Math.min( from + limit, total );
        final List< Map< String, Object > > page = weakPages.subList( from, to );

        return GSON.toJson( Map.of(
                "total", total,
                "limit", limit,
                "offset", offset,
                "pages", page ) );
    }

    private List< String > collectFlags( final PageDescriptor p ) {
        final List< String > flags = new ArrayList<>();

        // no_cluster
        if ( p.cluster() == null || p.cluster().isBlank() ) flags.add( "no_cluster" );

        // Hub-aware checks (need cluster lookup)
        Optional< ClusterDetails > cluster = Optional.empty();
        boolean isHub = false;
        if ( p.cluster() != null && !p.cluster().isBlank() ) {
            try {
                cluster = index.getCluster( p.cluster() );
                isHub = cluster.map( c -> c.hubPage() != null && c.hubPage().slug().equals( p.slug() ) )
                               .orElse( false );
            } catch ( final Exception ignored ) {
                // treat as no cluster details available
            }
        }

        // no_inbound_cluster_links — non-hubs only
        if ( !isHub && cluster.isPresent() ) {
            try {
                final Set< String > clusterSlugs = cluster.get().articles().stream()
                        .map( PageDescriptor::slug )
                        .collect( java.util.stream.Collectors.toSet() );
                final Set< String > referrers = refs.findReferrers( p.slug() );
                final boolean hasIntra = referrers != null && referrers.stream().anyMatch( clusterSlugs::contains );
                if ( !hasIntra ) flags.add( "no_inbound_cluster_links" );
            } catch ( final Exception ignored ) {
                // omit flag on lookup failure rather than mis-flagging
            }
        }

        // generic_hub_summary
        if ( isHub && p.summary() != null && GENERIC.matcher( p.summary() ).find() ) {
            flags.add( "generic_hub_summary" );
        }

        // no_verified_at + stale_verification
        try {
            final Optional< Verification > v = index.verificationOf( p.canonicalId() );
            if ( v.isEmpty() || v.get().verifiedAt() == null ) {
                flags.add( "no_verified_at" );
            } else if ( ConfidenceComputer.compute( v.get().verifiedAt(), v.get().verifiedBy() )
                                          == Confidence.STALE ) {
                flags.add( "stale_verification" );
            }
        } catch ( final Exception ignored ) {
            // omit verification-related flags on failure
        }

        return flags;
    }
}
```

**Verify** the `ConfidenceComputer.compute(...)` method signature against the actual file at `wikantik-main/src/main/java/com/wikantik/pagegraph/spine/ConfidenceComputer.java` and adjust. The signature shown is illustrative.

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn -pl wikantik-rest test -Dtest=AgentGradeAuditResourceTest -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/admin/AgentGradeAuditResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/admin/AgentGradeAuditResourceTest.java
git commit -m "$(cat <<'EOF'
feat(agent-grade): add /admin/agent-grade-audit weak-signal report

Lists pages flagged by no_cluster, no_inbound_cluster_links (non-hubs
only), generic_hub_summary, no_verified_at, or stale_verification.
Sorted descending by flag count then canonical_id. Limit clamped to
[1,200]. Behind AdminAuthFilter — no resource-side auth check.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 10 — Wire `AgentGradeAuditResource` into the REST application

**Files:**
- Modify: wherever REST resources are registered (search via `find wikantik-rest -name "*Application.java" -o -name "*Initializer.java"` or `grep -rn "StructuralIndexHealthResource" --include="*.java" wikantik-rest`).

- [ ] **Step 1: Find the registration point**

Run: `grep -rn "StructuralIndexHealthResource" --include="*.java" wikantik-rest 2>/dev/null`

The first non-test result is the existing wiring point. Mirror it.

- [ ] **Step 2: Register `AgentGradeAuditResource` with the same path-routing convention as the existing admin resources**

Add the new path mapping `/admin/agent-grade-audit` (GET) to whatever resource registry the REST module uses, plus the constructor wiring (`new AgentGradeAuditResource(structuralIndex, referenceManager)` — both reachable via the engine).

- [ ] **Step 3: Verify compile**

Run: `mvn -pl wikantik-rest compile test-compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Smoke-test wiring with a wire test if possible (optional)**

If the existing health resource has a wire test (`StructuralIndexHealthResourceTest`), copy its pattern for the audit endpoint at the wire level. Otherwise rely on the IT in Task 13.

- [ ] **Step 5: Commit**

```bash
git add $(git diff --name-only -- 'wikantik-rest/src/**/*.java')
git commit -m "$(cat <<'EOF'
feat(agent-grade): wire /admin/agent-grade-audit into REST application

Registers AgentGradeAuditResource at GET /admin/agent-grade-audit,
behind AdminAuthFilter (no resource-side auth). Constructor takes the
structural index + reference manager from the engine.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 11 — Observability counters

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/agent/ForAgentMetrics.java` (or wherever the existing for-agent metrics live)
- Create or modify: a metrics class for `read_pages` (likely sits in `wikantik-knowledge`)
- Modify: `DefaultForAgentProjectionService.java` to record on the new counters
- Modify: `ReadPagesTool.java` to record per-failure-reason

- [ ] **Step 1: Add counters per the spec's Observability section**

Required counters:
- `wikantik_agent_hints_derivation_failures_total`
- `wikantik_hub_summary_synthesis_total`
- `wikantik_read_pages_partial_failures_total{reason="not_found"|"forbidden"|"internal_error"}`

Mirror the existing `wikantik_for_agent_response_bytes` registration pattern (find with `grep -rn "wikantik_for_agent_response_bytes" --include="*.java"`).

- [ ] **Step 2: Wire counter increments**

In `DefaultForAgentProjectionService.build(...)`:
- On the agent_hints try/catch failure path, increment `agent_hints_derivation_failures_total`.
- After the hub overlay fires, increment `hub_summary_synthesis_total`.

In `ReadPagesTool.execute(...)`:
- For each per-page error entry, increment the labelled counter with the matching `reason`.

- [ ] **Step 3: Add a unit test asserting counters increment**

Mirror the existing `ForAgentMetrics` test pattern (search `find . -name "ForAgentMetricsTest.java"`).

- [ ] **Step 4: Run tests**

```bash
mvn -pl wikantik-main,wikantik-knowledge test -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add $(git diff --name-only -- '*.java')
git commit -m "$(cat <<'EOF'
feat(agent-hints): Prometheus counters for derivation, hub overlay, read_pages

Three new counters:
- wikantik_agent_hints_derivation_failures_total
- wikantik_hub_summary_synthesis_total
- wikantik_read_pages_partial_failures_total{reason}

Histogram wikantik_for_agent_response_bytes already captures size
delta from the new field via existing recordMetric path.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 12 — Extend `PageForAgentResourceTest` and `GetPageForAgentToolTest` for the new fields

**Files:**
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/PageForAgentResourceTest.java`
- Modify: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/GetPageForAgentToolTest.java`

- [ ] **Step 1: Add wire-shape assertions to `PageForAgentResourceTest`**

```java
@Test
void responseIncludesAgentHintsAndSummarySynthesizedFields() {
    // Project a fixture page (existing setup in this class), serialize the
    // response, parse JSON, assert:
    final JsonObject body = parseResponseAsJson( /* existing helper */ );
    assertTrue( body.has( "agent_hints" ),         "agent_hints field expected on /for-agent response" );
    assertTrue( body.has( "summary_synthesized" ), "summary_synthesized field expected on /for-agent response" );
}

@Test
void agentHintsDegradationLandsInMissingFieldsList() {
    // Force the deriver to throw via mock, project, assert:
    //   body.get("agent_hints").isJsonNull()
    //   body.getAsJsonArray("missing_fields").contains("agent_hints")
    //   body.get("degraded").getAsBoolean() == true
}
```

- [ ] **Step 2: Add the same assertions to `GetPageForAgentToolTest`**

Mirror the wire-shape test on the MCP side. Both endpoints serialise the same record so behavior should match.

- [ ] **Step 3: Run tests**

```bash
mvn -pl wikantik-rest,wikantik-knowledge test -Dtest=PageForAgentResourceTest,GetPageForAgentToolTest -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add wikantik-rest/src/test/java/com/wikantik/rest/PageForAgentResourceTest.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/GetPageForAgentToolTest.java
git commit -m "$(cat <<'EOF'
test(agent-hints): wire-shape assertions for new projection fields

Both PageForAgentResourceTest and GetPageForAgentToolTest now assert
the JSON body carries agent_hints + summary_synthesized, and that
deriver failure surfaces as agent_hints:null + missing_fields entry.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 13 — Integration test: `DerivedAgentHintsAndBatchReadIT`

**Files:**
- Create: `wikantik-it-tests/.../DerivedAgentHintsAndBatchReadIT.java` (find the right submodule via `find wikantik-it-tests -name "*IT.java" | head -3`)

- [ ] **Step 1: Find the IT package convention**

Run: `find wikantik-it-tests -type f -name "*IT.java" 2>/dev/null | head -5`

Pick the closest neighbour (likely a knowledge-MCP IT or a /for-agent IT) and mirror its setup: Cargo-launched Tomcat, PostgreSQL + pgvector, fixture seeding via `mvn pre-integration-test`.

- [ ] **Step 2: Write the IT**

Cover (as five separate `@Test` methods):
1. **Derived hints populated.** Seed three fixture pages in cluster `it_cluster` (one hub, two members, one with verified_at + authoritative). Project the non-hub member. Assert `agent_hints.prefer_pages` order matches expectations: hub first, verified next, plain last.
2. **Hub summary overlay fires.** Seed a hub page with summary `"Index of pages on it_cluster"`. Project it. Assert `summary` was replaced with a synthesized string and `summary_synthesized: true`.
3. **`read_pages` happy path + partial failure.** Call MCP tool with three slugs: one valid, one missing, one ACL-denied (seed an ACL-restricted page). Assert 200, three result entries, error fields populated correctly.
4. **`read_pages` cap exceeded.** Call with 21 slugs, assert MCP error envelope.
5. **Audit endpoint.** Hit `GET /admin/agent-grade-audit` as admin (use `test.properties` credentials per CLAUDE.md). Assert the seeded weak page surfaces.

- [ ] **Step 3: Run the IT**

```bash
mvn clean install -Pintegration-tests -fae -pl wikantik-it-tests -am
```
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add $(git diff --name-only -- 'wikantik-it-tests/**/*.java')
git commit -m "$(cat <<'EOF'
test(agent-hints): IT for derived hints, hub overlay, read_pages, audit

Cargo-launched Tomcat + Postgres/pgvector. Five scenarios cover the
end-to-end flow on /for-agent, /knowledge-mcp/read_pages, and
/admin/agent-grade-audit.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 14 — Documentation: CLAUDE.md and README.md

**Files:**
- Modify: `CLAUDE.md` (5 locations, see spec)
- Modify: `README.md` (4 locations, see spec)

- [ ] **Step 1: Verify the actual current `/knowledge-mcp` tool count**

Inspect `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java`. Count `tools.add(...)` calls in `contextInitialized()`. Record the count.

After Task 8, the count is `prior_count + 1`. Record both numbers explicitly here:

```
PRIOR /knowledge-mcp tool count (verified): ___
NEW   /knowledge-mcp tool count (post-read_pages): ___
```

- [ ] **Step 2: Reconcile CLAUDE.md**

Edit `CLAUDE.md`:
1. **Line 292** (`wikantik-knowledge` module description): change `15 read-only tools` to the verified new count. Append `read_pages` to the parenthetical capability list.
2. **Line 306** (Agent-facing surface table): change `/knowledge-mcp` row tool count to match.
3. **Line 384** (`/for-agent projection is in.` paragraph): add a sentence: "The projection now also carries derived `agent_hints` (`prefer_tools` and `prefer_pages` ranked by intra-cluster centrality) and a `summary_synthesized` flag indicating whether the hub overlay replaced the authored summary. Both fields are computed at projection time — no author burden."
4. **Line 390** (`Tool-description examples are in.` paragraph): change `/knowledge-mcp (16)` to `/knowledge-mcp (NEW_COUNT)` reconciling with the other lines.
5. **Active Design Documents bullet list**: add a bullet pointing at this spec, summarising the four deliverables and marking it implemented when Task 16 passes.

- [ ] **Step 3: Reconcile README.md**

Edit `README.md`:
1. **Line 37**: bump `15 read-only tools` to NEW_COUNT, append `read_pages` to capability list.
2. **Line 102**: bump architecture diagram label.
3. **Line 344**: bump module table row.
4. **Line 417**: bump `Exposes **15 tools**` to NEW_COUNT, append `read_pages`, add a sentence on the `agent_hints` projection field.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "$(cat <<'EOF'
docs: reconcile /knowledge-mcp tool counts; record agent-hints surface

CLAUDE.md and README.md updated with the new /knowledge-mcp tool count
(prior $PRIOR + 1 = $NEW for read_pages) and a description of the
derived agent_hints + summary_synthesized fields on /for-agent. Existing
15-vs-16 discrepancy in CLAUDE.md reconciled against the live registry.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 15 — Documentation: Active design docs + memory

**Files:**
- Modify: `docs/wikantik-pages/AgentGradeContentDesign.md`
- Modify: `docs/wikantik-pages/PageGraphVsKnowledgeGraph.md`
- Modify: `docs/wikantik-pages/StructuralSpineDesign.md`
- Modify: `/home/jakefear/.claude/projects/-home-jakefear-source-jspwiki/memory/MEMORY.md` and the relevant per-memory files.

- [ ] **Step 1: Add Phase 7 section to `AgentGradeContentDesign.md`**

Append a new section near the end (or before "Out of scope" if such a section exists), summarising:
- The decision to derive vs. require authored hints (with author-burden reasoning recorded).
- The four deliverables.
- The audit endpoint as the operator surface compensating for no author-side schema.
- A pointer to this spec.

Update the doc's "design complete 2026-04-25" framing to "design complete 2026-04-25; Phase 7 tuning added 2026-05-10".

- [ ] **Step 2: Add `AgentHintsDeriver` consumer to `PageGraphVsKnowledgeGraph.md`**

Add one sentence to the Page Graph consumers list: "`AgentHintsDeriver` (intra-cluster centrality query for `prefer_pages` ranking; uses `ReferenceManager.findReferrers`)."

- [ ] **Step 3: Add consumers to `StructuralSpineDesign.md`**

Add to the consumer list:
- `AgentHintsDeriver` (cluster lookup, hub designation, verification metadata)
- `AgentGradeAuditResource` (weakness scan over the cluster index + verification table)

- [ ] **Step 4: Update memory**

Update `/home/jakefear/.claude/projects/-home-jakefear-source-jspwiki/memory/project_agent_grade_phase_2_next.md` (or rename) to add Phase 7. Reflect that the agent-grade content design now includes derived-hints tuning landed 2026-05-10.

Update `project_admin_mcp_tool_surface.md` (per the memory index) to bump `/knowledge-mcp` count.

- [ ] **Step 5: Commit**

```bash
git add docs/wikantik-pages/AgentGradeContentDesign.md \
        docs/wikantik-pages/PageGraphVsKnowledgeGraph.md \
        docs/wikantik-pages/StructuralSpineDesign.md \
        /home/jakefear/.claude/projects/-home-jakefear-source-jspwiki/memory/MEMORY.md \
        /home/jakefear/.claude/projects/-home-jakefear-source-jspwiki/memory/project_agent_grade_phase_2_next.md \
        /home/jakefear/.claude/projects/-home-jakefear-source-jspwiki/memory/project_admin_mcp_tool_surface.md
git commit -m "$(cat <<'EOF'
docs: agent-grade Phase 7 + page-graph + structural-spine consumer notes

AgentGradeContentDesign.md gets a Phase 7 section covering the four
derived-hints deliverables and the operator audit endpoint. Page Graph
and Structural Spine docs gain new consumer entries so the dependency
is discoverable when refactoring those subsystems. Memory bumped to
reflect the new tool count and the AG design extension.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 16 — Final verification: full IT reactor

Per project memory: targeted `-Dtest=` runs miss cross-module breakage. Gate on the full reactor before declaring done.

- [ ] **Step 1: Run the full reactor with integration tests**

```bash
mvn clean install -Pintegration-tests -fae
```
Expected: BUILD SUCCESS. Note: do NOT use `-T` (parallel builds break IT port allocation).

- [ ] **Step 2: If anything fails, debug to root cause, fix, re-run**

Use `superpowers:systematic-debugging` if a failure isn't immediately diagnosable from the surface error.

- [ ] **Step 3: Verify the new MCP tool surfaces over the wire**

After `mvn install` completes, redeploy and probe:
```bash
bin/redeploy.sh
sleep 5
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')
curl -u "${login}:${password}" -X POST http://localhost:8080/knowledge-mcp \
     -H 'Content-Type: application/json' \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq '.result.tools | map(.name) | sort'
```
Expected: the sorted tool list contains `read_pages`. Verify the count matches the doc updates from Task 14.

- [ ] **Step 4: Verify the audit endpoint responds**

```bash
curl -u "${login}:${password}" http://localhost:8080/admin/agent-grade-audit?limit=5 | jq .
```
Expected: 200 with `{total, limit:5, offset:0, pages:[...]}`.

- [ ] **Step 5: Final commit (only if Task 14/15 doc commits happened before this task and now need updating)**

If the verified post-deploy tool count differs from what was committed in Task 14, fix the docs and commit:
```bash
git add CLAUDE.md README.md docs/wikantik-pages/*.md
git commit -m "$(cat <<'EOF'
docs: reconcile tool count after live-deploy verification

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

Otherwise, the work is complete.

---

## Self-review checklist

After all tasks land, verify:

- [ ] Spec section "Architecture Overview" — every component listed appears as a created file.
- [ ] Spec section "Derivation logic" — both `prefer_tools` (frequency-rank, dedupe, top 5) and `prefer_pages` (hub first, intra-cluster centrality × verification bonus, top 5) implemented.
- [ ] Spec section "Audit endpoint" — every weakness flag implemented (`no_cluster`, `no_inbound_cluster_links`, `generic_hub_summary`, `no_verified_at`, `stale_verification`).
- [ ] Spec section "`read_pages` shape" — cap=20, partial-success, dedupe, parameter convention matches existing `read_page`.
- [ ] Spec section "Edge Cases" — every row covered by a unit or IT test.
- [ ] Spec section "Documentation Updates" — every file listed has been touched (CLAUDE.md, README.md, three design docs, memory files).
- [ ] Spec section "Observability" — three new counters wired and tested.
- [ ] No `TODO` / `TBD` / `placeholder` strings introduced in production code.
- [ ] Naming convention reminder respected (top-level projection fields camelCase Java, nested records snake_case Java).
