# Wiki Ontology — Phase 4 Implementation Plan (Agent Retrieval + MCP Tools)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development or superpowers:executing-plans. (This session runs it **directly/inline**.) Steps use checkbox (`- [ ]`).

**Goal:** Make the ontology pay off for agents — (a) ontology-aware query expansion in the hybrid retriever (subClassOf + SKOS narrower), behind a flag, with a test that *demonstrates the lift*; and (b) two read-only `knowledge-mcp` tools, `get_ontology` (the formal T-Box) and `sparql_query` (read-only SPARQL over the same Jena model), taking the surface from 16 → 18 tools.

**Architecture:** A pure `OntologyQueryService` interface in `wikantik-api` (no Jena types) is implemented by `JenaOntologyQueryService` in `wikantik-ontology` (over `OntologyModelManager.inferenceSnapshot()`), and injected as an optional `@Nullable` dependency into `DefaultContextRetrievalService` (the hybrid retriever). When the `wikantik.search.ontologyExpansion.enabled` flag is on, the retriever expands the query string with ontology-derived terms before the BM25/dense passes. The MCP tools live in `wikantik-knowledge`, constructor-injected with the `OntologyModelManager` reached via `getSubsystems().pageGraph().ontologyRebuildCoordinator().modelManager()`.

**Tech Stack:** Java 21, JUnit 5 + Mockito, in-memory Jena (edge tests), the existing `FakeDeps`/`FakeSearchManager` retriever-test harness (benefit demo), Cargo IT (MCP wire test).

**Spec:** `docs/superpowers/specs/2026-06-08-wiki-ontology-design.md` (§4 agent retrieval — "both: smarter pipeline + SPARQL tool").

---

## Decisions & constraints

- **No module cycle:** `wikantik-ontology` depends only on `wikantik-api`; adding it as a `provided` dep of `wikantik-knowledge` is safe. The consumed `OntologyQueryService` interface lives in `wikantik-api` (both sides already depend on it) and uses only plain Java types (`String`/`List<String>`).
- **Opt-in flag:** `wikantik.search.ontologyExpansion.enabled` (boolean, default **false**) — mirrors `wikantik.search.hybrid.enabled`. Off ⇒ zero behavior change (safe to ship dark).
- **Expansion semantics:** match query tokens against class `rdfs:label` + concept `skos:prefLabel`/`rdfs:label` (normalized: lowercase, separators→space); for a matched **class**, add its transitive `subClassOf` subclasses' labels (RDFS-entailed in the inference snapshot); for a matched **concept**, BFS `skos:narrower` (with a visited-set so SKOS cycles terminate) and add those labels. Dedup; drop terms already in the query. Empty when nothing matches (no regression).
- **MCP tools read-only:** `get_ontology` from `tboxSnapshot()` (complements `discover_schema`, which is empirical ABox); `sparql_query` copies `OntologySparqlResource`'s exact read-only block (`QueryFactory.create` rejects UPDATE, result cap, timeout, `inferenceSnapshot()`). Per the MCP-pairing rule: Mockito unit tests **+** a Cargo wire-level IT.
- **Benefit validation:** the live `RetrievalQualityRunner` needs a populated deployment, so the demonstrable lift is a focused A/B unit test (stub `OntologyQueryService`): a relevant page is retrieved **with** expansion and **missed without**. Documented as the honest proof; the full harness A/B is noted as future work.
- **Apache header** on every new `.java`.

## File structure

```
wikantik-api/.../api/ontology/OntologyQueryService.java     (new) pure interface: expandQuery(String) + granular walks
wikantik-ontology/.../ontology/JenaOntologyQueryService.java (new) Jena impl over OntologyModelManager
wikantik-<retriever-module>/.../DefaultContextRetrievalService.java (edit) optional OntologyQueryService + flag + expansion
  + its Deps/FakeDeps builder (edit) optional field
wikantik-<wiring>/.../*WiringHelper or KnowledgeMcpInitializer (edit) build + inject JenaOntologyQueryService behind the flag
wikantik-knowledge/.../mcp/GetOntologyTool.java             (new) T-Box tool
wikantik-knowledge/.../mcp/SparqlQueryTool.java             (new) read-only SPARQL tool
wikantik-knowledge/.../mcp/KnowledgeMcpInitializer.java     (edit) register both (16->18), wire OntologyModelManager
wikantik-knowledge/pom.xml                                  (edit) add wikantik-ontology (provided)
wikantik-it-tests/.../OntologyMcpToolsIT.java               (new) wire-level MCP IT
```

> **Verify at implementation time** (the research left one ambiguity): the exact MODULE of `DefaultContextRetrievalService` (`com.wikantik.knowledge` package may be in `wikantik-main`, not the `wikantik-knowledge` module) and its construction site. `find . -name DefaultContextRetrievalService.java` first; inject the optional dep there and supply it from the wiring site that can reach the coordinator/`modelManager()`.

---

## Task 1: `OntologyQueryService` interface (wikantik-api)

**Files:** Create `wikantik-api/src/main/java/com/wikantik/api/ontology/OntologyQueryService.java`.

- [ ] **Step 1: Create the interface** (no test — pure interface, exercised by Task 2's impl tests):

```java
package com.wikantik.api.ontology;

import java.util.List;

/**
 * Read-only ontology queries for retrieval-time query expansion. Pure Java types only
 * (no Jena) so it can live in wikantik-api and be consumed by the retriever without a
 * module cycle. Implemented by JenaOntologyQueryService in wikantik-ontology.
 */
public interface OntologyQueryService {

    /**
     * Expansion terms for a free-text query: labels of transitive subclasses of any class
     * the query names, plus labels of SKOS-narrower concepts of any concept it names.
     * Deduplicated, excludes terms already present in the query. Empty when nothing matches.
     */
    List< String > expandQuery( String query );
}
```

- [ ] **Step 2: Compile-check** `mvn -o -pl wikantik-api test-compile`. Commit.

```bash
git add wikantik-api/src/main/java/com/wikantik/api/ontology/OntologyQueryService.java
git commit -m "feat(ontology): OntologyQueryService interface (retrieval-time query expansion)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: `JenaOntologyQueryService` + rigorous edge-case tests

**Files:** Create `wikantik-ontology/.../ontology/JenaOntologyQueryService.java`; Test `JenaOntologyQueryServiceTest.java`. **This is where edge-case rigor lives.**

- [ ] **Step 1: Write the failing edge-case test suite.** Build a tiny ontology in-memory (T-Box classes with a deep subClassOf chain + SKOS concepts with a narrower chain that includes a CYCLE), load it into an `OntologyModelManager.inMemory()`, and assert `expandQuery`:

```java
package com.wikantik.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.wikantik.api.ontology.OntologyQueryService;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;

class JenaOntologyQueryServiceTest {

    /** A focused T-Box + ABox: subclass chain Algorithm <- SortingAlgorithm <- QuickSort;
     *  SKOS chain databases -> graph-databases -> property-graph, plus a cycle a<->b. */
    private OntologyModelManager mgrWith( final String extraTurtle ) {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final Model m = ModelFactory.createDefaultModel();
        final String ttl = """
            @prefix wk:   <https://wiki.wikantik.com/ns/wikantik#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
            """ + extraTurtle;
        RDFDataMgr.read( m, new java.io.ByteArrayInputStream( ttl.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) ), Lang.TURTLE );
        mgr.replaceNamedGraph( "urn:test:fixture", m );
        return mgr;
    }

    @Test
    void expandsTransitiveSubclassesOfAMatchedClass() {
        final OntologyQueryService svc = new JenaOntologyQueryService( mgrWith( """
            wk:Algorithm        a owl:Class ; rdfs:label "Algorithm" .
            wk:SortingAlgorithm a owl:Class ; rdfs:subClassOf wk:Algorithm ; rdfs:label "Sorting Algorithm" .
            wk:QuickSort        a owl:Class ; rdfs:subClassOf wk:SortingAlgorithm ; rdfs:label "QuickSort" .
            """ ) );
        final List< String > exp = svc.expandQuery( "tell me about algorithm internals" );
        assertTrue( exp.contains( "Sorting Algorithm" ), "direct subclass label expanded" );
        assertTrue( exp.contains( "QuickSort" ), "TRANSITIVE subclass label expanded (RDFS entailment)" );
        assertFalse( exp.contains( "Algorithm" ), "the matched term itself is not re-added" );
    }

    @Test
    void expandsSkosNarrowerAndTerminatesOnCycles() {
        final OntologyQueryService svc = new JenaOntologyQueryService( mgrWith( """
            <urn:c:databases>      a skos:Concept ; skos:prefLabel "databases" ; skos:narrower <urn:c:graphdb> .
            <urn:c:graphdb>        a skos:Concept ; skos:prefLabel "graph databases" ; skos:narrower <urn:c:pg> .
            <urn:c:pg>             a skos:Concept ; skos:prefLabel "property graph" .
            <urn:c:loopA>          a skos:Concept ; skos:prefLabel "loop a" ; skos:narrower <urn:c:loopB> .
            <urn:c:loopB>          a skos:Concept ; skos:prefLabel "loop b" ; skos:narrower <urn:c:loopA> .
            """ ) );
        final List< String > exp = svc.expandQuery( "databases" );
        assertTrue( exp.contains( "graph databases" ) && exp.contains( "property graph" ),
                "transitive SKOS narrower expanded" );
        // Cycle must not hang or duplicate: querying loop a returns loop b once and terminates.
        final List< String > loop = svc.expandQuery( "loop a" );
        assertTrue( loop.contains( "loop b" ) );
        assertEquals( loop.stream().distinct().count(), loop.size(), "no duplicates from the cycle" );
    }

    @Test
    void noMatchYieldsEmptyExpansion() {
        final OntologyQueryService svc = new JenaOntologyQueryService( mgrWith(
                "wk:Algorithm a owl:Class ; rdfs:label \"Algorithm\" ." ) );
        assertTrue( svc.expandQuery( "completely unrelated zzzqqq" ).isEmpty() );
    }

    @Test
    void matchingIsCaseInsensitiveAndDeduplicates() {
        final OntologyQueryService svc = new JenaOntologyQueryService( mgrWith( """
            wk:Algorithm        a owl:Class ; rdfs:label "Algorithm" .
            wk:SortingAlgorithm a owl:Class ; rdfs:subClassOf wk:Algorithm ; rdfs:label "Sorting Algorithm" .
            """ ) );
        // "ALGORITHM" (different case) still matches; repeated mention doesn't duplicate output.
        final List< String > exp = svc.expandQuery( "ALGORITHM algorithm Algorithm" );
        assertEquals( List.of( "Sorting Algorithm" ), exp );
    }

    @Test
    void nullOrBlankQueryIsSafe() {
        final OntologyQueryService svc = new JenaOntologyQueryService( mgrWith(
                "wk:Algorithm a owl:Class ; rdfs:label \"Algorithm\" ." ) );
        assertTrue( svc.expandQuery( null ).isEmpty() );
        assertTrue( svc.expandQuery( "   " ).isEmpty() );
    }
}
```

- [ ] **Step 2: Run — verify it fails** (no `JenaOntologyQueryService`).

- [ ] **Step 3: Implement `JenaOntologyQueryService.java`.** Match query against a normalized label→resource index built from the inference snapshot; walk subClassOf (already transitive via RDFS entailment) and BFS skos:narrower (visited-set for cycles):

```java
package com.wikantik.ontology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.wikantik.api.ontology.OntologyQueryService;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDFS;

/** Jena-backed {@link OntologyQueryService} over the materialized ontology (inference snapshot). */
public final class JenaOntologyQueryService implements OntologyQueryService {

    private static final String SKOS_NARROWER  = "http://www.w3.org/2004/02/skos/core#narrower";
    private static final String SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";

    private final OntologyModelManager manager;

    public JenaOntologyQueryService( final OntologyModelManager manager ) {
        this.manager = manager;
    }

    @Override
    public List< String > expandQuery( final String query ) {
        if ( query == null || query.isBlank() ) {
            return List.of();
        }
        final Model model = manager.inferenceSnapshot();
        final String normQuery = normalize( query );
        final Set< String > expansion = new LinkedHashSet<>();
        final Set< String > queryTokens = new HashSet<>( List.of( normQuery.split( " " ) ) );

        // Index every labelled resource (class or concept) by its normalized label.
        for ( final StmtIterator it = model.listStatements( null, RDFS.label, (RDFNode) null ); it.hasNext(); ) {
            final Statement st = it.next();
            collectIfMatched( model, st.getSubject(), st.getObject(), normQuery, expansion );
        }
        for ( final StmtIterator it = model.listStatements( null,
                model.createProperty( SKOS_PREF_LABEL ), (RDFNode) null ); it.hasNext(); ) {
            final Statement st = it.next();
            collectIfMatched( model, st.getSubject(), st.getObject(), normQuery, expansion );
        }

        // Drop anything already literally in the query.
        final List< String > out = new ArrayList<>();
        for ( final String term : expansion ) {
            if ( !queryTokens.contains( normalize( term ) ) ) {
                out.add( term );
            }
        }
        return out;
    }

    private void collectIfMatched( final Model model, final Resource subject, final RDFNode labelNode,
                                   final String normQuery, final Set< String > expansion ) {
        if ( !labelNode.isLiteral() ) {
            return;
        }
        final String label = labelNode.asLiteral().getString();
        if ( !containsToken( normQuery, normalize( label ) ) ) {
            return;
        }
        // Matched resource: expand subclasses (transitive via RDFS) + SKOS narrower (BFS).
        for ( final StmtIterator sub = model.listStatements( null, RDFS.subClassOf, subject ); sub.hasNext(); ) {
            addLabels( model, sub.next().getSubject(), expansion );
        }
        narrowerClosure( model, subject, expansion );
    }

    private void narrowerClosure( final Model model, final Resource start, final Set< String > expansion ) {
        final Deque< Resource > queue = new ArrayDeque<>();
        final Set< String > visited = new HashSet<>();
        queue.add( start );
        visited.add( start.toString() );
        while ( !queue.isEmpty() ) {
            final Resource cur = queue.poll();
            for ( final StmtIterator it = model.listStatements( cur,
                    model.createProperty( SKOS_NARROWER ), (RDFNode) null ); it.hasNext(); ) {
                final RDFNode o = it.next().getObject();
                if ( o.isResource() && visited.add( o.toString() ) ) {
                    final Resource narrower = o.asResource();
                    addLabels( model, narrower, expansion );
                    queue.add( narrower );
                }
            }
        }
    }

    private void addLabels( final Model model, final Resource r, final Set< String > expansion ) {
        for ( final StmtIterator it = model.listStatements( r, RDFS.label, (RDFNode) null ); it.hasNext(); ) {
            final RDFNode o = it.next().getObject();
            if ( o.isLiteral() ) { expansion.add( o.asLiteral().getString() ); }
        }
        for ( final StmtIterator it = model.listStatements( r,
                model.createProperty( SKOS_PREF_LABEL ), (RDFNode) null ); it.hasNext(); ) {
            final RDFNode o = it.next().getObject();
            if ( o.isLiteral() ) { expansion.add( o.asLiteral().getString() ); }
        }
    }

    private static String normalize( final String s ) {
        return s.toLowerCase( Locale.ROOT ).replaceAll( "[^a-z0-9]+", " " ).trim();
    }

    /** True if every token of {@code label} appears in {@code haystack} (token-subsequence-agnostic). */
    private static boolean containsToken( final String haystack, final String label ) {
        if ( label.isBlank() ) { return false; }
        final String padded = " " + haystack + " ";
        for ( final String tok : label.split( " " ) ) {
            if ( !padded.contains( " " + tok + " " ) ) { return false; }
        }
        return true;
    }
}
```

- [ ] **Step 4: Run — verify all 5 tests pass.** Commit.

```bash
git add wikantik-ontology/src/main/java/com/wikantik/ontology/JenaOntologyQueryService.java \
        wikantik-ontology/src/test/java/com/wikantik/ontology/JenaOntologyQueryServiceTest.java
git commit -m "feat(ontology): JenaOntologyQueryService (subClassOf + SKOS-narrower query expansion)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Retriever ontology-aware expansion + the BENEFIT-demonstration test

**Files:** (verify module) `DefaultContextRetrievalService.java` + its `Deps`/`FakeDeps` builder + a config flag; Test `DefaultContextRetrievalServiceTest` (add cases).

- [ ] **Step 1: Read `DefaultContextRetrievalService` + `FakeDeps`** — `find . -name DefaultContextRetrievalService.java -not -path '*/target/*'` and `find . -name FakeDeps.java`. Note the constructor/builder, the query→BM25 seam (`searchManager.findPages(...)` at ~line 156), and how `FakeSearchManager` is configured.

- [ ] **Step 2: Add the config flag + optional dep.** Add `wikantik.search.ontologyExpansion.enabled` (default false) to the retriever's config; add an optional `@Nullable OntologyQueryService ontologyQuery` to the Deps/builder (default null). When enabled AND `ontologyQuery != null`, expand the query before BM25:

```java
        String effectiveQuery = query.query();
        if ( ontologyExpansionEnabled && ontologyQuery != null ) {
            final java.util.List< String > extra = ontologyQuery.expandQuery( effectiveQuery );
            if ( !extra.isEmpty() ) {
                effectiveQuery = effectiveQuery + " " + String.join( " ", extra );
                LOG.debug( "ontology expansion added {} terms", extra.size() );
            }
        }
        final ... bm25 = searchManager.findPages( effectiveQuery, ctx );
```
(Exact field/flag plumbing per the class's existing config pattern — mirror `hybrid.enabled`.)

- [ ] **Step 3: Write the BENEFIT-demonstration test** (the lift proof) + edge cases. Using `FakeSearchManager` keyed so the relevant page only matches when the expansion term is present:

```java
    @Test
    void ontologyExpansionRetrievesAPageThatPlainQueryMisses() {
        // FakeSearchManager returns "QuickSortPage" only when the query contains "QuickSort".
        final FakeSearchManager search = new FakeSearchManager();
        search.whenQueryContains( "QuickSort", FakeSearchResult.page( "QuickSortPage" ) );

        // Stub ontology: "algorithm" expands to "QuickSort".
        final OntologyQueryService onto = q -> q.toLowerCase().contains( "algorithm" )
                ? java.util.List.of( "QuickSort" ) : java.util.List.of();

        // WITHOUT expansion: query "algorithm" misses QuickSortPage.
        final DefaultContextRetrievalService off = FakeDeps.minimal()
                .searchManager( search ).ontologyQuery( onto ).ontologyExpansionEnabled( false ).build();
        assertTrue( pageNames( off.retrieve( query( "algorithm" ) ) ).isEmpty(),
                "without expansion, the subclass page is missed" );

        // WITH expansion: same query now retrieves QuickSortPage. THIS IS THE LIFT.
        final DefaultContextRetrievalService on = FakeDeps.minimal()
                .searchManager( search ).ontologyQuery( onto ).ontologyExpansionEnabled( true ).build();
        assertTrue( pageNames( on.retrieve( query( "algorithm" ) ) ).contains( "QuickSortPage" ),
                "ontology expansion retrieves the relevant subclass page the plain query missed" );
    }

    @Test
    void expansionDisabledOrNullServiceIsAStrictNoOp() {
        final FakeSearchManager search = new FakeSearchManager();
        search.captureQueries();
        // null service:
        FakeDeps.minimal().searchManager( search ).ontologyExpansionEnabled( true ).build()
                .retrieve( query( "algorithm" ) );
        assertEquals( "algorithm", search.lastQuery(), "null ontology service must not alter the query" );
    }
```
(Adapt `FakeSearchManager`/`FakeSearchResult`/`pageNames`/`query` helpers to the real test harness — extend `FakeSearchManager` with `whenQueryContains`/`captureQueries`/`lastQuery` if not present, mirroring its existing shape.)

- [ ] **Step 4: Run the new tests + the existing `DefaultContextRetrievalServiceTest`** — all green (the optional dep defaults null, so existing tests are unaffected). Commit.

```bash
git add <retriever module>/src/main/java/.../DefaultContextRetrievalService.java \
        <retriever module>/src/test/java/.../FakeDeps.java \
        <retriever module>/src/test/java/.../FakeSearchManager.java \
        <retriever module>/src/test/java/.../DefaultContextRetrievalServiceTest.java
git commit -m "feat(ontology): ontology-aware query expansion in the hybrid retriever (flagged) + lift demo

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Wire `JenaOntologyQueryService` into the retriever (behind the flag)

**Files:** the wiring site that builds `DefaultContextRetrievalService` (verify module — `*WiringHelper`/subsystem factory in `wikantik-main`, or `KnowledgeMcpInitializer`); `wikantik-knowledge/pom.xml` (+ `wikantik-ontology` provided, if the wiring is there).

- [ ] **Step 1: At the construction site**, read the flag and supply the service only when enabled (so the default path is untouched):

```java
    final boolean ontoExp = Boolean.parseBoolean(
            props.getProperty( "wikantik.search.ontologyExpansion.enabled", "false" ) );
    final OntologyModelManager om = /* coordinator.modelManager(), may be null */;
    final OntologyQueryService oq = ( ontoExp && om != null ) ? new JenaOntologyQueryService( om ) : null;
    // ... pass oq into the DefaultContextRetrievalService builder/Deps ...
```
If the wiring is in a `*WiringHelper`/factory (ArchUnit-whitelisted), reaching `coordinator.modelManager()` is fine. If it's in `KnowledgeMcpInitializer`, it already gets the engine — reach the coordinator via `PageGraphSubsystemBridge.fromLegacyEngine(engine).ontologyRebuildCoordinator()`.

- [ ] **Step 2: Compile + ArchUnit** (`mvn -o -pl wikantik-main test -Dtest=DecompositionArchTest` if the wiring touches wikantik-main; constructing `JenaOntologyQueryService` is not a `getManager` call — store should stay clean; `git checkout` the store if it churns).

- [ ] **Step 3: Commit.**

---

## Task 5: `GetOntologyTool` (MCP) + unit test

**Files:** Create `wikantik-knowledge/.../mcp/GetOntologyTool.java`; Test `GetOntologyToolTest.java`. Model on `DiscoverSchemaTool`/`TraverseTool` (implement `McpTool`: `name()`, `definition()` with read-only annotations, `execute(Map)`); constructor-inject `OntologyModelManager`.

- [ ] **Step 1: Write the failing test** — `execute` returns a JSON payload with `classes` (with `subClassOf`), `objectProperties` (with `domain`/`range`), and the `conceptScheme`. Build it from a small `OntologyModelManager.inMemory()` + `loadTBox()` and assert the bundled T-Box classes (e.g. `Technology`, `Article`) and the 21 predicates appear. (The tool reads `manager.tboxSnapshot()` and projects it to a stable JSON shape.)

- [ ] **Step 2: Run RED.**

- [ ] **Step 3: Implement `GetOntologyTool`** — `execute` reads `manager.tboxSnapshot()`, walks `owl:Class`/`owl:ObjectProperty`/`skos:ConceptScheme`, and returns a `Map`/record serialized via `KnowledgeMcpUtils.GSON` (mirror `DiscoverSchemaTool`'s `McpToolUtils.jsonResult(...)`). Read-only annotations (`readOnlyHint=true`).

- [ ] **Step 4: Run GREEN. Commit.**

---

## Task 6: `SparqlQueryTool` (MCP) + unit test

**Files:** Create `wikantik-knowledge/.../mcp/SparqlQueryTool.java`; Test `SparqlQueryToolTest.java`. Copy `OntologySparqlResource`'s read-only execution block.

- [ ] **Step 1: Write the failing test** — feed a known in-memory model (via a manager) and assert: a valid `SELECT` returns SPARQL-JSON containing an expected binding; an `INSERT`/`UPDATE` string is **rejected** (error result, no mutation); a malformed query → error result.

- [ ] **Step 2: Run RED.**

- [ ] **Step 3: Implement `SparqlQueryTool`** — `execute` takes `{query}`, runs `QueryFactory.create` (rejects UPDATE), caps results + timeout, executes over `manager.inferenceSnapshot()`, returns `ResultSetFormatter`-JSON (SELECT/ASK) or serialized RDF (CONSTRUCT) inside `McpToolUtils.jsonResult`/text. Read-only annotations.

- [ ] **Step 4: Run GREEN. Commit.**

---

## Task 7: Register both tools (16 → 18) + wire `OntologyModelManager`

**Files:** `KnowledgeMcpInitializer.java` (+ `wikantik-knowledge/pom.xml` provided dep on `wikantik-ontology` if not already transitive at compile time).

- [ ] **Step 1: In the tool-assembly block**, reach the manager + register both tools (guarded):

```java
    final OntologyRebuildCoordinator ontoCoord =
        PageGraphSubsystemBridge.fromLegacyEngine( engine ).ontologyRebuildCoordinator();
    final OntologyModelManager ontoMgr = ontoCoord == null ? null : ontoCoord.modelManager();
    if ( ontoMgr != null ) {
        tools.add( new GetOntologyTool( ontoMgr ) );
        tools.add( new SparqlQueryTool( ontoMgr ) );
    }
```

- [ ] **Step 2: Update the live tool-count** references: CLAUDE.md / README / CHANGELOG (16→18) and the `project_admin_mcp_tool_surface` memory.

- [ ] **Step 3: Compile-check `mvn -o -pl wikantik-knowledge -am test-compile`. Commit.**

---

## Task 8: Wire-level MCP IT + gates + benefits/future-work writeup

**Files:** Create `wikantik-it-tests/.../OntologyMcpToolsIT.java` (model the MCP-call harness on an existing knowledge-mcp IT — `McpWritePageCycleIT` or a knowledge-mcp read IT; reuse its `mcp.callTool(...)`/initialize handshake).

- [ ] **Step 1: Write the IT** — over the `/knowledge-mcp` endpoint: `tools/list` includes `get_ontology` + `sparql_query`; `get_ontology` returns a payload naming `Technology`/`Article` + the predicate vocab; `sparql_query` with a `SELECT ... a wk:Article` returns results; `sparql_query` with an `INSERT DATA {...}` is rejected.

- [ ] **Step 2: Focused IT run** — `mvn -o clean install -pl wikantik-it-tests/<knowledge-mcp IT module> -am -Pintegration-tests -Dsurefire.skip=true -Dit.test=OntologyMcpToolsIT -fae` (note the **`clean`** to avoid the stale-war-overlay gotcha, per `reference_it_war_overlay_stale_without_clean`).

- [ ] **Step 3: Full unit reactor** `mvn -o clean install -T 1C -DskipITs` (all modules + ArchUnit + the new unit tests + the lift demo).

- [ ] **Step 4: Full IT reactor** `mvn -o install -Pintegration-tests -Dsurefire.skip=true -fae`; verify the failsafe aggregate (Failures: 0).

- [ ] **Step 5: Docs + memory** — CLAUDE.md `wikantik-knowledge` line (16→18 tools incl. `get_ontology`/`sparql_query`); the agent-surface table row; `wikantik.search.ontologyExpansion.enabled` documented on the `wikantik-ontology` bullet; project memory → Phase 4 complete + **the ontology initiative complete**.

- [ ] **Step 6: BENEFITS & FUTURE-WORK assessment** (the user's explicit ask). Produce a short written assessment (in the summary + a `docs/` note or the spec's status block) covering: (a) the demonstrated retrieval benefit (the lift test) + the honest limits (label-matching is lexical; the live-harness A/B is future work); (b) what makes the agent surface "come together" (get_ontology + sparql_query + the public endpoints); (c) **ontology maintainability future work** — see the list below, to be refined with what's learned during implementation.

---

## Benefits validation & future-work (seed — refine during execution)

**Demonstrated benefit:** the Task 3 A/B test proves ontology expansion retrieves a relevant subclass/narrower-concept page that the literal query misses — the core value of a shared semantic model for retrieval. Honest limits to record: expansion is *lexical* label-matching (no embedding-based concept linking yet); the flag ships **off** pending a live-harness A/B.

**Future work to make ontology maintenance achievable (candidate list):**
1. **Live-harness A/B** — seed `retrieval_query_sets`, run `POST /admin/retrieval-quality/run` with expansion on/off, record ndcg/recall delta; gate the flag-on decision on it.
2. **Curation surface** — an admin view of `discover_schema` (empirical) vs `get_ontology` (formal) drift: node_types in the KG with no `wk:` class, predicates used but unmapped, orphan SKOS concepts.
3. **SHACL at write time (Phase 5)** — validate proposed KG edges against domain/range so the ABox stays ontology-conformant; surface violations via `list_ontology_violations`.
4. **Embedding-assisted concept linking** — replace lexical label-matching with dense nearest-concept lookup (reuse the existing embedder) for fuzzier query→concept mapping.
5. **Ontology-as-data** — consider generating parts of `wikantik.ttl` (the SKOS scheme, the entity classes) from the live corpus + `RelationshipTypeVocabulary`, so the T-Box doesn't drift from the code/data by hand.
6. **Inference cost** — `inferenceSnapshot()` is rebuilt per query/request; add caching keyed on the dataset version once the ABox is large.

---

## Self-Review (against spec §4)

- "(1) existing hybrid retriever ontology-aware (subClassOf + SKOS expansion)" → Tasks 1–4, flagged, with a lift-demonstration test. ✓
- "(2) get_ontology + sparql_query read-only tools over the same endpoint, 16→18" → Tasks 5–7. ✓
- "MCP unit + wire-level IT" (pairing rule) → Tasks 5/6 unit + Task 8 IT. ✓
- "retrieval-quality CI shows demonstrable lift" → honestly scoped to a focused A/B unit test (live harness needs a populated deployment); full-harness A/B recorded as future work. ✓ (transparent, not skipped)

**Placeholders:** the testable cores (interface, Jena impl + edge tests) are complete; the retriever/wiring/tool tasks specify the exact seam + pattern but defer a few names to read-at-edit-time (the `DefaultContextRetrievalService` module + `FakeDeps`/`FakeSearchManager` shape + the MCP IT harness), each flagged — because those are existing structures to mirror, not new designs.

**Type consistency:** `OntologyQueryService.expandQuery(String)→List<String>` used by the impl, the retriever, and the stub in the lift test; `OntologyModelManager.tboxSnapshot()/inferenceSnapshot()` (existing) used by the tools; `getSubsystems().pageGraph().ontologyRebuildCoordinator().modelManager()` (Phase 3 accessor) used in wiring. No new `getManager` callers planned outside whitelisted wiring.
