# Wiki Ontology — Phase 3 Implementation Plan (Public Read-Only Endpoints + ACL Split)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development or superpowers:executing-plans. (This session runs it **directly/inline**.) Steps use checkbox (`- [ ]`).

**Goal:** Expose the materialized ontology to the world as standards-based RDF — a public read-only SPARQL endpoint, per-resource JSON-LD/Turtle dereferencing, and full dumps — **without leaking ACL-restricted content**.

**Architecture:** The dataset the public endpoints serve is **public-only by construction**: at materialization time, a request-free anonymous-view check (`WikiSession.guestSession(engine)` + `PermissionFilter.canAccess(guest, slug, "view")`) filters the projection inputs so restricted pages, their derived entities, and edges touching them are **never projected**. Restricted content can't leak because it isn't in the dataset. The auth check lives in `wikantik-main` (where auth is available); the pure filter logic + endpoints stay in `wikantik-ontology`/`wikantik-rest`.

**Tech Stack:** Java 21, JUnit 5 + Mockito (unit), Apache Jena ARQ (SPARQL exec + RDF serialization), Cargo IT (the leak-prevention test). `wikantik-ontology`, `wikantik-main`, `wikantik-rest`, `wikantik-http`.

**Spec:** `docs/superpowers/specs/2026-06-08-wiki-ontology-design.md` (§3.2 endpoints, §3.3.5 public/restricted split, §7 ACL-leakage requirement).

---

## Decisions & constraints

- **Public-only dataset.** Restricted resources are *excluded from projection*, not stored-and-filtered. This is the simplest bypass-proof design (absent ⇒ unqueryable). The spec's "separate restricted named graphs" is deferred to whenever an authenticated/internal view is needed (likely Phase 4, which can query the KG directly for authed agents).
- **Anonymous-view check (request-free):** one cached `Session guest = WikiSession.guestSession(engine)` + `new PermissionFilter(engine).canAccess(guest, slug, "view")`. Reused across all pages in a rebuild.
- **What's projected publicly:** a **page** iff `canAccess(guest, slug, view)`; a **KG node** iff it's a stub (`sourcePage()==null`) **or** its `sourcePage` is anonymously viewable; an **edge** iff **both** endpoint nodes are public; a **concept** iff it comes from a public page. *(Stub nodes are treated public — consistent with `GraphRoleClassifier` (stub ≠ restricted). Flagged as a reviewable call.)*
- **SPARQL is read-only:** parse with `QueryFactory.create` (rejects UPDATE syntactically → 400); enforce a result `LIMIT` cap + a query timeout. `/sparql` accepts `GET ?query=` and `POST` (form or `application/sparql-query`).
- **Public, no auth, permissive CORS:** endpoints extend `RestServletBase` but override CORS to emit `Access-Control-Allow-Origin: *` (no cookies/credentials involved). `AdminAuthFilter`/`SpaRoutingFilter` don't touch these paths. `POST /sparql` gets a narrow CSRF exemption.
- **Out of scope (flagged):** `WikiPageFormatFilter`'s missing ACL check on `/wiki/{slug}?format=md|json` is a separate pre-existing leak — not fixed here.
- **Apache header** on every new `.java`.

## File structure

```
wikantik-ontology/.../ontology/
    PublicProjectionFilter.java      (new) pure: filter nodes/edges/pages/concepts by a Predicate<String> isPublic
    OntologyModelManager.java        (edit) add unionSnapshot() + namedGraphSnapshot(iri)
wikantik-main/.../ontology/runtime/
    OntologyRebuildCoordinator.java  (edit) expose modelManager()
    OntologyPageSync.java            (edit) gate onPageSaved with an injected isPublic predicate
    OntologyWiringHelper.java        (edit) build the guest-session isPublic predicate; wrap the coordinator's
                                            suppliers + the page-sync with the public filter
wikantik-rest/.../rest/
    PublicRdfServletBase.java        (new) shared: modelManager() accessor + permissive-CORS override
    OntologySparqlResource.java      (new) GET/POST /sparql — read-only SELECT/ASK/CONSTRUCT
    OntologyResourceResource.java    (new) GET /id/{type}/{id} — JSON-LD/Turtle of one resource's graph
    OntologyExportResource.java      (new) GET /export/ontology.ttl (T-Box) + /export/graph.nt (A-Box)
    CsrfProtectionFilter.java        (edit, in wikantik-http) exempt POST /sparql
wikantik-war/.../web.xml             (edit) register the 3 servlets
wikantik-it-tests/.../OntologyPublicEndpointsIT.java   (new) the leak-prevention IT (centerpiece)
```

---

## Task 1: `PublicProjectionFilter` (pure ACL split logic)

**Files:** Create `wikantik-ontology/src/main/java/com/wikantik/ontology/PublicProjectionFilter.java`; Test alongside.

- [ ] **Step 1: Write the failing test** (`PublicProjectionFilterTest`):

```java
package com.wikantik.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.ontology.projection.PageRecord;
import org.junit.jupiter.api.Test;

class PublicProjectionFilterTest {

    private static final UUID PUB = UUID.fromString( "00000000-0000-0000-0000-0000000000f1" );
    private static final UUID RES = UUID.fromString( "00000000-0000-0000-0000-0000000000f2" );
    private static final UUID STUB = UUID.fromString( "00000000-0000-0000-0000-0000000000f3" );

    private final Predicate< String > isPublic = slug -> "PublicPage".equals( slug );

    private KgNode node( final UUID id, final String sourcePage ) {
        return new KgNode( id, "n", "concept", sourcePage, Provenance.HUMAN_AUTHORED, Map.of(), null, null, "human", null );
    }

    @Test
    void publicNodesKeepStubsAndPublicSourcedDropRestricted() {
        final List< KgNode > nodes = List.of(
                node( PUB, "PublicPage" ), node( RES, "SecretPage" ), node( STUB, null ) );
        final List< KgNode > pub = PublicProjectionFilter.publicNodes( nodes, isPublic );
        final Set< UUID > ids = PublicProjectionFilter.publicNodeIds( nodes, isPublic );
        assertTrue( ids.contains( PUB ) && ids.contains( STUB ) );
        assertTrue( !ids.contains( RES ), "restricted-sourced node excluded" );
        assertEquals( 2, pub.size() );
    }

    @Test
    void publicEdgesRequireBothEndpointsPublic() {
        final Set< UUID > pubIds = Set.of( PUB, STUB );
        final List< KgEdge > edges = List.of(
                new KgEdge( UUID.randomUUID(), PUB, STUB, "uses", Provenance.HUMAN_AUTHORED, Map.of(), null, null, "human", null ),
                new KgEdge( UUID.randomUUID(), PUB, RES,  "uses", Provenance.HUMAN_AUTHORED, Map.of(), null, null, "human", null ) );
        final List< KgEdge > pub = PublicProjectionFilter.publicEdges( edges, pubIds );
        assertEquals( 1, pub.size(), "edge touching a restricted node is dropped" );
        assertEquals( STUB, pub.get( 0 ).targetId() );
    }

    @Test
    void publicPagesKeepOnlyViewable() {
        final PageRecord pubP = new PageRecord( "C1", "PublicPage", "T", "article", "ml", List.of(), null, null, null );
        final PageRecord secP = new PageRecord( "C2", "SecretPage", "T", "article", "ml", List.of(), null, null, null );
        final List< PageRecord > pub = PublicProjectionFilter.publicPages( List.of( pubP, secP ), isPublic );
        assertEquals( 1, pub.size() );
        assertEquals( "PublicPage", pub.get( 0 ).slug() );
    }
}
```

- [ ] **Step 2: Run — verify it fails.**

Run: `mvn -o -pl wikantik-ontology test -Dtest=PublicProjectionFilterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement `PublicProjectionFilter.java`:**

```java
package com.wikantik.ontology;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.ontology.projection.PageRecord;

/**
 * Pure ACL split: selects the resources that may appear in the PUBLIC ontology dataset.
 * A page is public iff isPublic(slug); a node iff it is a stub (no source page) or its
 * source page is public; an edge iff both endpoints are public; a page-record iff its
 * slug is public. No auth here — the caller supplies the anonymous-view predicate.
 */
public final class PublicProjectionFilter {

    private PublicProjectionFilter() {}

    public static List< KgNode > publicNodes( final List< KgNode > nodes, final Predicate< String > isPublic ) {
        return nodes.stream()
                .filter( n -> n.sourcePage() == null || isPublic.test( n.sourcePage() ) )
                .collect( Collectors.toList() );
    }

    public static Set< java.util.UUID > publicNodeIds( final List< KgNode > nodes, final Predicate< String > isPublic ) {
        return publicNodes( nodes, isPublic ).stream().map( KgNode::id ).collect( Collectors.toSet() );
    }

    public static List< KgEdge > publicEdges( final List< KgEdge > edges, final Set< java.util.UUID > publicNodeIds ) {
        return edges.stream()
                .filter( e -> publicNodeIds.contains( e.sourceId() ) && publicNodeIds.contains( e.targetId() ) )
                .collect( Collectors.toList() );
    }

    public static List< PageRecord > publicPages( final List< PageRecord > pages, final Predicate< String > isPublic ) {
        return pages.stream()
                .filter( p -> p.slug() != null && isPublic.test( p.slug() ) )
                .collect( Collectors.toList() );
    }
}
```

- [ ] **Step 4: Run — verify 3 tests pass.**

- [ ] **Step 5: Commit**

```bash
git add wikantik-ontology/src/main/java/com/wikantik/ontology/PublicProjectionFilter.java \
        wikantik-ontology/src/test/java/com/wikantik/ontology/PublicProjectionFilterTest.java
git commit -m "feat(ontology): PublicProjectionFilter (ACL split — public-only projection inputs)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: `OntologyModelManager` snapshot accessors for serving

**Files:** Modify `OntologyModelManager.java`; Test `OntologyModelManagerTest.java` (add cases).

- [ ] **Step 1: Add failing tests** to `OntologyModelManagerTest`:

```java
    @Test
    void unionSnapshotIncludesTBoxAndNamedGraphs() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final String iri = Iris.entity( java.util.UUID.fromString( "00000000-0000-0000-0000-0000000000b9" ) );
        mgr.replaceNamedGraph( iri, entityGraph( iri, "Technology" ) );
        final org.apache.jena.rdf.model.Model union = mgr.unionSnapshot();
        // T-Box class present + the A-Box triple present, no inference.
        assertTrue( union.contains(
                org.apache.jena.rdf.model.ResourceFactory.createResource( Iris.term( "Technology" ) ),
                org.apache.jena.vocabulary.RDF.type,
                org.apache.jena.rdf.model.ResourceFactory.createResource( "http://www.w3.org/2002/07/owl#Class" ) ) );
        assertTrue( union.contains(
                org.apache.jena.rdf.model.ResourceFactory.createResource( iri ),
                org.apache.jena.vocabulary.RDF.type,
                org.apache.jena.rdf.model.ResourceFactory.createResource( Iris.term( "Technology" ) ) ) );
    }

    @Test
    void namedGraphSnapshotReturnsJustThatGraph() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final String iri = Iris.entity( java.util.UUID.fromString( "00000000-0000-0000-0000-0000000000ba" ) );
        mgr.replaceNamedGraph( iri, entityGraph( iri, "Concept" ) );
        final org.apache.jena.rdf.model.Model g = mgr.namedGraphSnapshot( iri );
        assertTrue( g.size() == 1, "only the resource's own triples" );
    }
```

- [ ] **Step 2: Run — verify failure** (methods missing).

- [ ] **Step 3: Implement** — add to `OntologyModelManager` (after `inferenceSnapshot()`):

```java
    /** Detached union of the T-Box (default graph) + all named graphs — NO inference. For dumps/SPARQL base. */
    public Model unionSnapshot() {
        dataset.begin( ReadWrite.READ );
        try {
            final Model union = ModelFactory.createDefaultModel();
            union.add( dataset.getDefaultModel() );
            for ( final Iterator< String > it = dataset.listNames(); it.hasNext(); ) {
                union.add( dataset.getNamedModel( it.next() ) );
            }
            return union;
        } finally {
            dataset.end();
        }
    }

    /** Detached copy of a single named (resource) graph; empty model if absent. */
    public Model namedGraphSnapshot( final String graphIri ) {
        dataset.begin( ReadWrite.READ );
        try {
            return ModelFactory.createDefaultModel().add( dataset.getNamedModel( graphIri ) );
        } finally {
            dataset.end();
        }
    }
```

- [ ] **Step 4: Run — verify all `OntologyModelManagerTest` pass.**

- [ ] **Step 5: Commit**

```bash
git add wikantik-ontology/src/main/java/com/wikantik/ontology/OntologyModelManager.java \
        wikantik-ontology/src/test/java/com/wikantik/ontology/OntologyModelManagerTest.java
git commit -m "feat(ontology): OntologyModelManager union + named-graph snapshots for serving

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Coordinator `modelManager()` + `OntologyPageSync` visibility gate

**Files:** Modify `OntologyRebuildCoordinator.java`, `OntologyPageSync.java`, `OntologyPageSyncTest.java`.

- [ ] **Step 1: Add `modelManager()` to `OntologyRebuildCoordinator`** (so endpoints can read the dataset):

```java
    /** The materialized model (read access for the public endpoints). */
    public OntologyModelManager modelManager() {
        return manager;
    }
```

- [ ] **Step 2: Add an `isPublic` predicate to `OntologyPageSync`** so a save of a now-restricted page removes its graph instead of projecting it. Change the constructor + `onPageSaved`:

```java
    private final java.util.function.Predicate< String > isPublic;

    public OntologyPageSync( final OntologyModelManager manager, final PageCanonicalIdsDao dao,
                             final PageManager pageManager, final java.util.function.Predicate< String > isPublic ) {
        this.manager = manager;
        this.dao = dao;
        this.pageManager = pageManager;
        this.isPublic = isPublic;
    }

    public void onPageSaved( final String slug ) {
        final Optional< PageCanonicalIdsDao.Row > row = dao.findBySlug( slug );
        if ( row.isEmpty() ) {
            LOG.warn( "onPageSaved: no canonical_id row for slug '{}'; skipping", slug );
            return;
        }
        final PageRecord record = PageRecordBuilder.fromRow( row.get(), pageManager );
        if ( !isPublic.test( slug ) ) {
            // Page is (now) ACL-restricted: ensure it's absent from the public dataset.
            manager.removeNamedGraph( Iris.page( record.canonicalId() ) );
            slugToCanonical.put( slug, record.canonicalId() );
            return;
        }
        manager.replaceNamedGraph( Iris.page( record.canonicalId() ), PageProjector.project( record ) );
        for ( final Map.Entry< String, Model > c : ConceptProjector.project( List.of( record ) ).entrySet() ) {
            manager.replaceNamedGraph( c.getKey(), c.getValue() );
        }
        slugToCanonical.put( slug, record.canonicalId() );
    }
```

- [ ] **Step 3: Update `OntologyPageSyncTest`** — every `new OntologyPageSync(mgr, dao, pageManager)` becomes `new OntologyPageSync(mgr, dao, pageManager, slug -> true)` (public by default in existing tests), and **add one new test** for the restriction gate:

```java
    @Test
    void onPageSavedRemovesGraphWhenPageBecomesRestricted() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        when( dao.findBySlug( "Solo" ) ).thenReturn( Optional.of( row( "Solo" ) ) );
        when( pageManager.getPureText( "Solo", -1 ) ).thenReturn( "---\ntags: [graphs]\n---\nbody" );
        // First public:
        OntologyPageSync pub = new OntologyPageSync( mgr, dao, pageManager, slug -> true );
        pub.onPageSaved( "Solo" );
        assertTrue( mgr.namedGraphExists( Iris.page( CID ) ) );
        // Now restricted: a save must remove it.
        OntologyPageSync res = new OntologyPageSync( mgr, dao, pageManager, slug -> false );
        res.onPageSaved( "Solo" );
        assertFalse( mgr.namedGraphExists( Iris.page( CID ) ), "restricted page graph removed on save" );
    }
```
(Update the `sync(mgr)` helper in the test to pass `slug -> true`.)

- [ ] **Step 4: Run** `OntologyPageSyncTest` + `OntologyRebuildCoordinatorTest` — all green.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyRebuildCoordinator.java \
        wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyPageSync.java \
        wikantik-main/src/test/java/com/wikantik/ontology/runtime/OntologyPageSyncTest.java
git commit -m "feat(ontology): coordinator.modelManager() + OntologyPageSync ACL-visibility gate

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Wire the public filter into `OntologyWiringHelper`

**Files:** Modify `OntologyWiringHelper.java`.

Build the request-free anonymous-view predicate and apply `PublicProjectionFilter` to the coordinator's three suppliers; pass the predicate to `OntologyPageSync`.

- [ ] **Step 1: Build the predicate + filter the suppliers.** Replace the coordinator construction block + the page-sync construction:

```java
        // Request-free anonymous-view check: a page/node is public iff a GUEST session may view it.
        final com.wikantik.api.core.Session guest = com.wikantik.WikiSession.guestSession( engine );
        final com.wikantik.auth.permissions.PermissionFilter permFilter =
                new com.wikantik.auth.permissions.PermissionFilter( engine );
        final java.util.function.Predicate< String > isPublic =
                slug -> permFilter.canAccess( guest, slug, "view" );

        final OntologyRebuildCoordinator coordinator = new OntologyRebuildCoordinator(
                mgr,
                () -> com.wikantik.ontology.PublicProjectionFilter.publicNodes(
                        nodeRepo.getAllNodes( Tier.MACHINE ), isPublic ),
                () -> com.wikantik.ontology.PublicProjectionFilter.publicEdges(
                        edgeRepo.getAllEdges( Tier.MACHINE ),
                        com.wikantik.ontology.PublicProjectionFilter.publicNodeIds(
                                nodeRepo.getAllNodes( Tier.MACHINE ), isPublic ) ),
                () -> com.wikantik.ontology.PublicProjectionFilter.publicPages( pageBuilder.build(), isPublic ),
                true );

        engine.setManager( OntologyRebuildCoordinator.class, coordinator );
        LOG.info( "ontology runtime wired (tdb2 dir={})", dir );

        // Event-incremental sync (with the same ACL gate).
        final OntologyPageSync pageSync = new OntologyPageSync( mgr, pageDao, pageManager, isPublic );
        new OntologyEventListener( pageSync ).register( pageManager, filterManager );
```
(Confirm the `Session` import path — research says `com.wikantik.api.core.Session`; verify before finalizing. `PermissionFilter` is `com.wikantik.auth.permissions.PermissionFilter`.)

- [ ] **Step 2: Compile-check** + **ArchUnit** (constructing `PermissionFilter` / `WikiSession.guestSession` is not a `getManager` call, so the store stays untouched):

Run: `mvn -o -pl wikantik-main test-compile` then `mvn -o -pl wikantik-main test -Dtest=DecompositionArchTest`
Expected: BUILD SUCCESS both; `git status` on `archunit_store/` clean. If ArchUnit unexpectedly fails, `git checkout` the store and investigate before re-freezing.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyWiringHelper.java
git commit -m "feat(ontology): apply anonymous-view ACL filter to public projection inputs

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: `PublicRdfServletBase` + `OntologySparqlResource`

**Files:** Create `PublicRdfServletBase.java`, `OntologySparqlResource.java` in `wikantik-rest`.

- [ ] **Step 1: Create `PublicRdfServletBase`** (shared accessor + permissive CORS):

```java
package com.wikantik.rest;

import com.wikantik.ontology.OntologyModelManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Base for the public read-only RDF endpoints: model accessor + permissive (credential-less) CORS. */
public abstract class PublicRdfServletBase extends RestServletBase {

    private static final long serialVersionUID = 1L;

    /** The materialized PUBLIC dataset manager, or null if the ontology runtime is unavailable. */
    protected OntologyModelManager modelManager() {
        final var coordinator = getSubsystems().pageGraph().ontologyRebuildCoordinator();
        return coordinator == null ? null : coordinator.modelManager();
    }

    /** Fully public, credential-less CORS — these endpoints carry no session. */
    @Override
    protected void setCorsHeaders( final HttpServletRequest request, final HttpServletResponse response ) {
        response.setHeader( "Access-Control-Allow-Origin", "*" );
        response.setHeader( "Access-Control-Allow-Methods", "GET, POST, OPTIONS" );
        response.setHeader( "Access-Control-Allow-Headers", "Accept, Content-Type" );
    }
}
```
(Confirm `RestServletBase.setCorsHeaders` is `protected` and overridable, and that `getSubsystems().pageGraph().ontologyRebuildCoordinator()` is the Phase-1b accessor. Both verified in earlier phases.)

- [ ] **Step 2: Implement `OntologySparqlResource`** (`GET ?query=` + `POST`):

```java
package com.wikantik.rest;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import com.wikantik.ontology.OntologyModelManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QueryParseException;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Public read-only SPARQL endpoint over the materialized public ontology dataset. */
public class OntologySparqlResource extends PublicRdfServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( OntologySparqlResource.class );
    private static final long RESULT_CAP = 10_000;
    private static final long TIMEOUT_MS = 30_000;

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        runQuery( req.getParameter( "query" ), req, resp );
    }

    @Override
    protected void doPost( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        String q = req.getParameter( "query" );
        if ( q == null && req.getContentType() != null
                && req.getContentType().startsWith( "application/sparql-query" ) ) {
            q = new String( req.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8 );
        }
        runQuery( q, req, resp );
    }

    private void runQuery( final String queryString, final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        if ( queryString == null || queryString.isBlank() ) {
            sendError( resp, HttpServletResponse.SC_BAD_REQUEST, "missing 'query'" );
            return;
        }
        final OntologyModelManager mgr = modelManager();
        if ( mgr == null ) {
            sendError( resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ontology service not available" );
            return;
        }
        final Query query;
        try {
            query = QueryFactory.create( queryString );   // rejects SPARQL UPDATE syntactically
        } catch ( final QueryParseException e ) {
            sendError( resp, HttpServletResponse.SC_BAD_REQUEST, "invalid SPARQL query: " + e.getMessage() );
            return;
        }
        if ( !query.hasLimit() || query.getLimit() > RESULT_CAP ) {
            query.setLimit( RESULT_CAP );
        }
        final Model data = mgr.inferenceSnapshot();   // serve with subClassOf entailment
        try ( QueryExecution qe = QueryExecutionFactory.create( query, data ) ) {
            qe.setTimeout( TIMEOUT_MS, TimeUnit.MILLISECONDS );
            final OutputStream out = resp.getOutputStream();
            if ( query.isSelectType() ) {
                resp.setContentType( "application/sparql-results+json" );
                final ResultSet rs = qe.execSelect();
                ResultSetFormatter.outputAsJSON( out, rs );
            } else if ( query.isAskType() ) {
                resp.setContentType( "application/sparql-results+json" );
                ResultSetFormatter.outputAsJSON( out, qe.execAsk() );
            } else if ( query.isConstructType() || query.isDescribeType() ) {
                final Model m = query.isConstructType() ? qe.execConstruct() : qe.execDescribe();
                final Lang lang = negotiateRdf( req );
                resp.setContentType( lang == Lang.JSONLD ? "application/ld+json" : "text/turtle" );
                RDFDataMgr.write( out, m, lang );
            } else {
                sendError( resp, HttpServletResponse.SC_BAD_REQUEST, "unsupported query form" );
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "SPARQL execution failed: {}", e.getMessage() );
            sendError( resp, HttpServletResponse.SC_BAD_REQUEST, "query execution failed: " + e.getMessage() );
        }
    }

    private static Lang negotiateRdf( final HttpServletRequest req ) {
        final String accept = req.getHeader( "Accept" );
        if ( accept != null && accept.contains( "json" ) ) {
            return Lang.JSONLD;
        }
        return Lang.TURTLE;
    }
}
```

- [ ] **Step 3: Compile-check** `mvn -o -pl wikantik-rest -am test-compile`. Expected: BUILD SUCCESS. (Servlet behavior is covered by the IT in Task 8.)

- [ ] **Step 4: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/PublicRdfServletBase.java \
        wikantik-rest/src/main/java/com/wikantik/rest/OntologySparqlResource.java
git commit -m "feat(ontology): public read-only SPARQL endpoint (/sparql)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: `/id/{type}/{id}` dereferencing + `/export/*` dumps

**Files:** Create `OntologyResourceResource.java`, `OntologyExportResource.java` in `wikantik-rest`.

- [ ] **Step 1: `OntologyResourceResource`** (`GET /id/{type}/{id}` → that resource's named graph as JSON-LD/Turtle):

```java
package com.wikantik.rest;

import java.io.IOException;

import com.wikantik.ontology.Iris;
import com.wikantik.ontology.OntologyModelManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;

/** Per-resource RDF dereferencing: GET /id/{type}/{id} -> the resource's named graph. */
public class OntologyResourceResource extends PublicRdfServletBase {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final OntologyModelManager mgr = modelManager();
        if ( mgr == null ) {
            sendError( resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ontology service not available" );
            return;
        }
        // pathInfo: /{type}/{id}  e.g. /entity/<uuid>, /page/<canonicalId>, /concept/<slug...>
        final String pathInfo = req.getPathInfo();
        if ( pathInfo == null || pathInfo.length() < 2 ) {
            sendError( resp, HttpServletResponse.SC_BAD_REQUEST, "expected /id/{type}/{id}" );
            return;
        }
        final String iri = Iris.ID + pathInfo.substring( 1 );   // ID base + "type/id"
        final Model g = mgr.namedGraphSnapshot( iri );
        if ( g.isEmpty() ) {
            sendError( resp, HttpServletResponse.SC_NOT_FOUND, "no public resource at " + iri );
            return;
        }
        final Lang lang = ( req.getHeader( "Accept" ) != null && req.getHeader( "Accept" ).contains( "json" ) )
                ? Lang.JSONLD : Lang.TURTLE;
        resp.setContentType( lang == Lang.JSONLD ? "application/ld+json" : "text/turtle" );
        RDFDataMgr.write( resp.getOutputStream(), g, lang );
    }
}
```

- [ ] **Step 2: `OntologyExportResource`** (`GET /export/ontology.ttl` = T-Box; `GET /export/graph.nt` = full public A-Box+T-Box):

```java
package com.wikantik.rest;

import java.io.IOException;

import com.wikantik.ontology.OntologyModelManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;

/** Bulk RDF dumps for ingestion: /export/ontology.ttl (T-Box) + /export/graph.nt (public A-Box). */
public class OntologyExportResource extends PublicRdfServletBase {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final OntologyModelManager mgr = modelManager();
        if ( mgr == null ) {
            sendError( resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ontology service not available" );
            return;
        }
        final String path = req.getPathInfo() == null ? "" : req.getPathInfo();
        if ( path.endsWith( "ontology.ttl" ) ) {
            resp.setContentType( "text/turtle" );
            RDFDataMgr.write( resp.getOutputStream(), mgr.tboxSnapshot(), Lang.TURTLE );
        } else if ( path.endsWith( "graph.nt" ) ) {
            resp.setContentType( "application/n-triples" );
            RDFDataMgr.write( resp.getOutputStream(), mgr.unionSnapshot(), Lang.NTRIPLES );
        } else {
            sendError( resp, HttpServletResponse.SC_NOT_FOUND, "use /export/ontology.ttl or /export/graph.nt" );
        }
    }
}
```

- [ ] **Step 3: Compile-check** `mvn -o -pl wikantik-rest -am test-compile`. Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/OntologyResourceResource.java \
        wikantik-rest/src/main/java/com/wikantik/rest/OntologyExportResource.java
git commit -m "feat(ontology): /id/{type}/{id} dereferencing + /export/* RDF dumps

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: web.xml registration + CSRF exemption for POST /sparql

**Files:** Modify `wikantik-war/.../web.xml`; modify `CsrfProtectionFilter` (in wikantik-http).

- [ ] **Step 1: Register the three servlets + mappings** (near the other servlets; `AdminAuthFilter`/`SpaRoutingFilter` don't cover these paths):

```xml
    <servlet>
        <servlet-name>OntologySparqlResource</servlet-name>
        <servlet-class>com.wikantik.rest.OntologySparqlResource</servlet-class>
    </servlet>
    <servlet>
        <servlet-name>OntologyResourceResource</servlet-name>
        <servlet-class>com.wikantik.rest.OntologyResourceResource</servlet-class>
    </servlet>
    <servlet>
        <servlet-name>OntologyExportResource</servlet-name>
        <servlet-class>com.wikantik.rest.OntologyExportResource</servlet-class>
    </servlet>
```
```xml
    <servlet-mapping>
        <servlet-name>OntologySparqlResource</servlet-name>
        <url-pattern>/sparql</url-pattern>
    </servlet-mapping>
    <servlet-mapping>
        <servlet-name>OntologyResourceResource</servlet-name>
        <url-pattern>/id/*</url-pattern>
    </servlet-mapping>
    <servlet-mapping>
        <servlet-name>OntologyExportResource</servlet-name>
        <url-pattern>/export/*</url-pattern>
    </servlet-mapping>
```

- [ ] **Step 2: Exempt `POST /sparql` from the CSRF synchronizer-token check.** Read `CsrfProtectionFilter.isRestApiEndpoint` (research: checks `servletPath.startsWith("/api/")` or `"/admin/"`). Add `/sparql` (it's public + read-only + carries no ambient credentials, so CSRF is inapplicable):

```java
        // Public read-only SPARQL: no ambient credentials, so CSRF tokens don't apply.
        if ( servletPath.equals( "/sparql" ) ) {
            return true;
        }
```
Place it inside `isRestApiEndpoint` (or the equivalent exemption method — confirm the exact method + variable name by reading the file first). Keep it an exact-match on `/sparql`.

- [ ] **Step 3: Confirm there's an `isRestApiEndpoint` (or analogous) unit test** in wikantik-http; if so, add a case asserting `/sparql` is exempt. (If the filter has tests, mirror them; otherwise the IT covers it.)

- [ ] **Step 4: Compile-check** `mvn -o -pl wikantik-http,wikantik-rest -am test-compile`. Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-war/src/main/webapp/WEB-INF/web.xml \
        wikantik-http/src/main/java/com/wikantik/http/CsrfProtectionFilter.java
git commit -m "feat(ontology): register public RDF servlets + exempt POST /sparql from CSRF

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: The leak-prevention IT + full reactor gates + docs

**Files:** Create `wikantik-it-tests/.../OntologyPublicEndpointsIT.java` (REST IT module; model the HTTP harness on `AdminEndpointsCoverageIT`/`AdminOntologyRebuildIT`).

This is the centerpiece. It must seed a **restricted** page (ACL `[{ALLOW view Admin}]`) and a **public** page, trigger a rebuild, then assert across `/sparql`, `/export/graph.nt`, and `/id/page/{cid}` that the **public** page appears and the **restricted** page does **not**.

- [ ] **Step 1: Write the IT.** Harness (copy verbatim from `AdminOntologyRebuildIT`): static `baseUrl` from `it-wikantik.base.url`, `secureCookieOverHttp()`, admin login (`janne`/`myP@5sw0rd`), `get`/`post`/`getAnonymous`. Flow:
  1. As admin, create a **public** page `OntologyPublicPg` (no ACL) and a **restricted** page `OntologySecretPg` with body containing `[{ALLOW view Admin}]`, via the existing page-save REST API (mirror how other ITs create pages — read a sibling that POSTs to `/api/pages` or similar; confirm the create path).
  2. As admin, `POST /admin/ontology/rebuild`; poll `GET /admin/ontology/status` until `state == "IDLE"` (Awaitility-style loop with the HttpClient).
  3. **Anonymously** (no auth), `GET /export/graph.nt` → assert the body contains the public page's slug/title and does **NOT** contain `OntologySecretPg` (nor its title).
  4. **Anonymously**, `GET /sparql?query=` with a URL-encoded `SELECT ?s WHERE { ?s a <https://wiki.wikantik.com/ns/wikantik#Article> }` → assert the result references the public page IRI and not the restricted one. (Resolve the public page's canonical_id from `/wiki/{slug}?format=json` or the page API if needed, or just assert by title/url substring in the dump for step 3 and a non-empty/required-absent check here.)
  5. `GET /sparql` with a `POST` (form `query=...`) → assert 200 (proves CSRF exemption works).
  6. Assert anonymous `GET /export/graph.nt` returns 200 (public, no auth) and `Access-Control-Allow-Origin: *` header present.

  Keep assertions substring-based on the dump where possible (robust); the critical assertions are **presence of public** + **absence of restricted**.

- [ ] **Step 2: Run the IT focused** (Cargo boot):

Run: `mvn -o install -pl wikantik-it-tests/wikantik-it-test-rest -am -Pintegration-tests -Dit.test=OntologyPublicEndpointsIT -fae`
Expected: the IT passes — restricted page absent from all public surfaces.

- [ ] **Step 3: Full unit reactor** — `mvn -o clean install -T 1C -DskipITs`. Expected: BUILD SUCCESS (+ ArchUnit green).

- [ ] **Step 4: Full IT reactor** — `mvn -o install -Pintegration-tests -Dsurefire.skip=true -fae`. Verify via failsafe aggregate (Failures: 0, Errors: 0).

- [ ] **Step 5: Commit the IT.**

- [ ] **Step 6: Docs + memory** — add the public endpoints to the CLAUDE.md agent-facing surface table (`/sparql`, `/id/*`, `/export/*` — public, read-only, ACL-filtered) and the `wikantik-ontology` bullet; add `wikantik.cors.allowedOrigins` note if relevant; update project memory to Phase 3 complete. **Also record the `WikiPageFormatFilter` ACL-leak finding as a separate follow-up** (memory + surface to the user).

---

## Self-Review (against spec §3.2 / §3.3.5 / §7)

**Spec coverage:**
- `/sparql` read-only SELECT/CONSTRUCT/ASK + guardrails (timeout, result cap, UPDATE rejected) → Task 5. ✓
- `/id/{type}/{id}` content negotiation (JSON-LD/Turtle) → Task 6. ✓
- `/export/*.ttl|.nt` dumps → Task 6. ✓
- **Public/restricted split (§3.3.5, §7 — the security core):** public-only projection via `PublicProjectionFilter` + the request-free guest-session check; pages/nodes/edges/concepts all gated; incremental save removes a now-restricted page's graph → Tasks 1, 3, 4. Verified by the leak-prevention IT (Task 8) — restricted page absent from SPARQL + dump + dereference. ✓
- Public, no-auth, permissive CORS; not under AdminAuthFilter/SpaRouting; CSRF exemption for POST /sparql → Tasks 5, 7. ✓

**Placeholder scan:** `PublicProjectionFilter`, the manager snapshots, the page-sync gate, and all three servlets are complete code. Two spots are flagged to confirm-at-edit-time: the exact `Session` import (`com.wikantik.api.core.Session`) and the `CsrfProtectionFilter` exemption method name/variable — both read-then-edit. The IT (Task 8 Step 1) specifies the flow precisely but defers the page-create call to the sibling IT's proven pattern (read it).

**Type consistency:** `PublicProjectionFilter.publicNodes/publicNodeIds/publicEdges/publicPages` used consistently across Task 1, 4; `OntologyModelManager.unionSnapshot()/namedGraphSnapshot()/tboxSnapshot()/inferenceSnapshot()`; `OntologyRebuildCoordinator.modelManager()`; `OntologyPageSync(manager, dao, pageManager, isPublic)` (4-arg) updated everywhere; `getSubsystems().pageGraph().ontologyRebuildCoordinator()` (Phase 1b accessor). `Iris.ID`/`Iris.term` reused. No new `getManager` callers → ArchUnit store untouched.

**Security note carried out of band:** `WikiPageFormatFilter` serves restricted page bodies anonymously (no ACL check) — a real, pre-existing leak, out of Phase 3 scope, to be raised with the user + recorded for a separate fix.
