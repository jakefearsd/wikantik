# Wiki Ontology — Phase 1 Implementation Plan (Materialization Runtime Core)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. (This session is running it **directly/inline** per the user's instruction.) Steps use checkbox (`- [ ]`) syntax.

**Goal:** Build the self-contained RDF materialization engine in `wikantik-ontology`: a Jena-backed `OntologyModelManager` (TDB2 in prod / in-memory in tests, T-Box loaded, named-graph replace/remove, RDFS `subClassOf` inference), the four Postgres→RDF projectors (Entity/Edge/Page/Concept) that turn domain objects into per-resource named graphs, and an `OntologyRebuildService` that materializes the full A-Box from in-memory inputs.

**Architecture:** Everything in this plan lives in `wikantik-ontology` and depends **only on `wikantik-api` (domain records) + Apache Jena** — *not* on wikantik-main. Projectors are pure functions `domain object → Jena Model`; the rebuild service orchestrates them over plain `List`s and loads the result into the manager's named graphs. This keeps the whole module unit-testable in-memory with zero database and zero WikiEngine coupling.

**Tech Stack:** Java 21, Maven, JUnit 5, Apache Jena 5.2.0 (`jena-arq`, `jena-shacl` already present; add `jena-tdb2`).

**Spec:** `docs/superpowers/specs/2026-06-08-wiki-ontology-design.md` (§2 model, §3 architecture, §5 Phase 1).

---

## SCOPE: Phase 1 is the core; WikiEngine wiring + admin endpoint are **Phase 1b**

The spec's Phase 1 also lists "startup-if-empty" and `POST /admin/ontology/rebuild`. Those require wiring `OntologyModelManager` into `WikiEngine` (its `setManager` + `TYPED_FIELD_WRITERS/READERS` static maps + a `*Subsystem.Services` record), building `PageRecord`s from `KgNodeRepository`/`KgEdgeRepository`/`PageCanonicalIdsDao` + `PageManager`/`FrontmatterParser` (all in **wikantik-main**), an `AdminOntologyResource` + `web.xml` mapping, and the full integration-test reactor gate. That integration is delicate (the `DecompositionArchTest` R-2 rule forbids new `engine.getManager()` callers in wikantik-main) and deserves its own focused plan.

**This plan (Phase 1) delivers a complete, tested materialization engine you can drive programmatically.** Phase 1b (next plan) wires it into the running app + the admin trigger. Splitting keeps every task here pure, fast, and low-risk — no DB, no Tomcat, no WikiEngine.

---

## Conventions & decisions locked here (load-bearing for Phase 1b/2)

- **Module dependencies:** add `jena-tdb2`; `wikantik-ontology` depends only on `wikantik-api` + Jena. No `wikantik-main`.
- **IRIs** (`Iris` helper):
  - namespace `wk:` = `https://wiki.wikantik.com/ns/wikantik#`
  - entity instance = `https://wiki.wikantik.com/id/entity/{uuid}`
  - page instance = `https://wiki.wikantik.com/id/page/{canonical_id}`
  - concept (tag/cluster) = `https://wiki.wikantik.com/id/concept/{sanitized}` (lowercase, whitespace→`-`, keep `/` for sub-cluster paths)
- **Named-graph-per-resource:** each entity, page, and concept owns a named graph whose IRI *is* its resource IRI. An **entity's graph holds its node triples AND its outgoing edges** — so Phase 2 can replace a node + its edges atomically by replacing one graph.
- **node_type → class** (`NodeTypeMapping`): the 9 entity classes + the 5 content classes (article/hub/reference/runbook/design,design_doc → wk:Article/Hub/Reference/Runbook/DesignDoc); **null / unrecognized (e.g. `intelligence-summary`, the ~7 NULL-typed nodes) → `wk:Concept`** (documented default, matches the chunk extractor's default).
- **relationship_type → property:** snake_case → `wk:lowerCamel` (same transform as Phase 0's `ObjectPropertyTest.toLocalName`). Unknown predicate (not in the closed vocab) → **skip + `LOG.warn`** (never silently drop).
- **Provenance:** `prov:wasAttributedTo` derived from `KgNode.provenance()`/`tier()`; `prov:wasDerivedFrom` the source page IRI when `sourcePage()` resolves to a known canonical_id (else omitted — stub nodes carry no derivation).
- **T-Box vs A-Box:** T-Box (`wikantik.ttl`) loads into the dataset **default graph**; A-Box lives in named graphs. Inference = RDFS reasoner over (default ∪ all named graphs).
- **Apache license header:** every new `.java` file starts with the standard ASF header (copy it verbatim from any Phase 0 source file, e.g. `wikantik-ontology/src/test/java/com/wikantik/ontology/TBoxVocabulary.java`). The code blocks below omit it for brevity — **add it**.

## File structure (all under `wikantik-ontology/src/main/java/com/wikantik/ontology/`)

```
Iris.java                          IRI namespace + builders (entity/page/concept/term)
NodeTypeMapping.java               node_type string -> wk: class local name (+ default)
OntologyModelManager.java          Jena Dataset owner: T-Box load, named-graph replace/remove,
                                   clearAbox, inference snapshot; in-mem + TDB2 factories
projection/PageRecord.java         input record for Page/Concept projectors (api-free DTO)
projection/EdgeProjector.java      KgEdge -> Statement (relationship_type -> wk: property)
projection/EntityProjector.java    KgNode (+ its outgoing edges) -> per-entity Model
projection/PageProjector.java      PageRecord -> per-page Model
projection/ConceptProjector.java   distinct tags/clusters -> skos:Concept Models
OntologyRebuildService.java        orchestrates a full rebuild from List<KgNode/KgEdge/PageRecord>
```
Tests mirror these under `src/test/java/com/wikantik/ontology/...`.

---

## Task 1: Add `jena-tdb2` dependency + TDB2 smoke test

**Files:**
- Modify: `wikantik-bom/pom.xml` (add `jena-tdb2` to `dependencyManagement`) — *and* confirm whether the root `pom.xml` `dependencyManagement` is the one actually consumed (Phase 0 added `jena-arq`/`jena-shacl` to the **root** `pom.xml` dependencyManagement, lines ~518–526; add `jena-tdb2` in the **same place** for consistency).
- Modify: `wikantik-ontology/pom.xml` (add the dependency)
- Test: `wikantik-ontology/src/test/java/com/wikantik/ontology/Tdb2SmokeTest.java`

- [ ] **Step 1: Add `jena-tdb2` to the root `pom.xml` `dependencyManagement`**, immediately after the `jena-shacl` block:

```xml
      <dependency>
        <groupId>org.apache.jena</groupId>
        <artifactId>jena-tdb2</artifactId>
        <version>${jena.version}</version>
      </dependency>
```

- [ ] **Step 2: Add the dependency to `wikantik-ontology/pom.xml`**, after the `jena-shacl` dependency:

```xml
    <dependency>
      <groupId>org.apache.jena</groupId>
      <artifactId>jena-tdb2</artifactId>
    </dependency>
```

- [ ] **Step 3: Write the failing TDB2 smoke test** (`Tdb2SmokeTest.java`, package `com.wikantik.ontology`):

```java
package com.wikantik.ontology;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Tdb2SmokeTest {

    @Test
    void canOpenTdb2DatasetAndWriteInATransaction( @TempDir final Path dir ) {
        final Dataset ds = TDB2Factory.connectDataset( dir.toString() );
        try {
            ds.begin( ReadWrite.WRITE );
            final Model g = ds.getNamedModel( "urn:test:g1" );
            g.add( ResourceFactory.createResource( "urn:test:s" ),
                   RDF.type,
                   ResourceFactory.createResource( "urn:test:T" ) );
            ds.commit();
        } finally {
            ds.end();
        }
        ds.begin( ReadWrite.READ );
        try {
            assertTrue( ds.getNamedModel( "urn:test:g1" ).size() == 1, "one triple persisted in TDB2" );
        } finally {
            ds.end();
            ds.close();
        }
    }
}
```

- [ ] **Step 4: Run — verify it passes** (proves `jena-tdb2` resolves + transactional write works):

Run: `mvn -o -pl wikantik-ontology test -Dtest=Tdb2SmokeTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS, 1 test passing. (If `jena-tdb2` can't resolve offline, drop `-o` for this one run to fetch it from Central.)

- [ ] **Step 5: Commit**

```bash
git add pom.xml wikantik-ontology/pom.xml \
        wikantik-ontology/src/test/java/com/wikantik/ontology/Tdb2SmokeTest.java
git commit -m "feat(ontology): add jena-tdb2 dependency + TDB2 transaction smoke test

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: `Iris` — IRI namespace + builders

**Files:** Create `Iris.java`; Test `IrisTest.java`.

- [ ] **Step 1: Write the failing test** (`IrisTest.java`):

```java
package com.wikantik.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IrisTest {

    @Test
    void termIriUsesWkNamespace() {
        assertEquals( "https://wiki.wikantik.com/ns/wikantik#Technology", Iris.term( "Technology" ) );
    }

    @Test
    void entityIriUsesUuid() {
        final UUID id = UUID.fromString( "00000000-0000-0000-0000-0000000000ab" );
        assertEquals( "https://wiki.wikantik.com/id/entity/00000000-0000-0000-0000-0000000000ab",
                Iris.entity( id ) );
    }

    @Test
    void pageIriUsesCanonicalId() {
        assertEquals( "https://wiki.wikantik.com/id/page/01KTGSV4B1PR4RBEGQGBJVNXN0",
                Iris.page( "01KTGSV4B1PR4RBEGQGBJVNXN0" ) );
    }

    @Test
    void conceptIriIsSanitizedLowercaseWithHyphens() {
        assertEquals( "https://wiki.wikantik.com/id/concept/low-cost-index-funds",
                Iris.concept( "Low Cost Index Funds" ) );
    }

    @Test
    void conceptIriKeepsSlashForSubClusterPaths() {
        assertEquals( "https://wiki.wikantik.com/id/concept/retirement-planning/eu-retirement",
                Iris.concept( "retirement-planning/eu-retirement" ) );
    }
}
```

- [ ] **Step 2: Run — verify it fails** (`Iris` does not exist → compile error).

Run: `mvn -o -pl wikantik-ontology test -Dtest=IrisTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILE FAILURE (cannot find symbol `Iris`).

- [ ] **Step 3: Implement `Iris.java`:**

```java
package com.wikantik.ontology;

import java.util.Locale;
import java.util.UUID;

/** Canonical IRI namespace + builders for the Wikantik ontology. */
public final class Iris {

    private Iris() {}

    public static final String NS    = "https://wiki.wikantik.com/ns/wikantik#";
    public static final String ID    = "https://wiki.wikantik.com/id/";
    public static final String SCHEMA = "https://schema.org/";

    /** Ontology term (class/property), e.g. term("Technology") -> wk:Technology. */
    public static String term( final String localName ) {
        return NS + localName;
    }

    /** Entity instance IRI keyed on the kg_nodes UUID. */
    public static String entity( final UUID id ) {
        return ID + "entity/" + id;
    }

    /** Page instance IRI keyed on the rename-stable canonical_id (ULID). */
    public static String page( final String canonicalId ) {
        return ID + "page/" + canonicalId;
    }

    /** SKOS concept (tag/cluster) IRI: lowercase, whitespace collapsed to '-', '/' kept for sub-cluster paths. */
    public static String concept( final String value ) {
        final String slug = value.trim().toLowerCase( Locale.ROOT )
                .replaceAll( "\\s+", "-" )
                .replaceAll( "[^a-z0-9/-]", "" )
                .replaceAll( "-{2,}", "-" );
        return ID + "concept/" + slug;
    }
}
```

- [ ] **Step 4: Run — verify it passes.** Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-ontology/src/main/java/com/wikantik/ontology/Iris.java \
        wikantik-ontology/src/test/java/com/wikantik/ontology/IrisTest.java
git commit -m "feat(ontology): Iris helper for resource + term IRIs

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: `NodeTypeMapping` — node_type → wk: class

**Files:** Create `NodeTypeMapping.java`; Test `NodeTypeMappingTest.java`.

- [ ] **Step 1: Write the failing test:**

```java
package com.wikantik.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NodeTypeMappingTest {

    @Test
    void mapsEachEntityType() {
        assertEquals( "Person",       NodeTypeMapping.classLocalName( "person" ) );
        assertEquals( "Organization", NodeTypeMapping.classLocalName( "organization" ) );
        assertEquals( "Technology",   NodeTypeMapping.classLocalName( "technology" ) );
        assertEquals( "Version",      NodeTypeMapping.classLocalName( "version" ) );
    }

    @Test
    void mapsPageTypesIncludingDesignAliases() {
        assertEquals( "Article",   NodeTypeMapping.classLocalName( "article" ) );
        assertEquals( "Hub",       NodeTypeMapping.classLocalName( "hub" ) );
        assertEquals( "DesignDoc", NodeTypeMapping.classLocalName( "design" ) );
        assertEquals( "DesignDoc", NodeTypeMapping.classLocalName( "design_doc" ) );
    }

    @Test
    void isCaseInsensitive() {
        assertEquals( "Product", NodeTypeMapping.classLocalName( "Product" ) );
    }

    @Test
    void nullOrUnknownDefaultsToConcept() {
        assertEquals( "Concept", NodeTypeMapping.classLocalName( null ) );
        assertEquals( "Concept", NodeTypeMapping.classLocalName( "" ) );
        assertEquals( "Concept", NodeTypeMapping.classLocalName( "intelligence-summary" ) );
    }
}
```

- [ ] **Step 2: Run — verify it fails** (compile error, no `NodeTypeMapping`).

- [ ] **Step 3: Implement `NodeTypeMapping.java`:**

```java
package com.wikantik.ontology;

import java.util.Locale;
import java.util.Map;

/**
 * Maps the free-text kg_nodes.node_type onto a wk: class local name.
 * Unrecognized or null/blank types default to "Concept" (matches the chunk
 * extractor's default and covers untyped / non-standard nodes such as the
 * NULL-typed nodes and "intelligence-summary").
 */
public final class NodeTypeMapping {

    private NodeTypeMapping() {}

    public static final String DEFAULT_CLASS = "Concept";

    private static final Map< String, String > MAP = Map.ofEntries(
            Map.entry( "person", "Person" ),
            Map.entry( "organization", "Organization" ),
            Map.entry( "place", "Place" ),
            Map.entry( "event", "Event" ),
            Map.entry( "product", "Product" ),
            Map.entry( "technology", "Technology" ),
            Map.entry( "concept", "Concept" ),
            Map.entry( "project", "Project" ),
            Map.entry( "version", "Version" ),
            Map.entry( "article", "Article" ),
            Map.entry( "hub", "Hub" ),
            Map.entry( "reference", "Reference" ),
            Map.entry( "runbook", "Runbook" ),
            Map.entry( "design", "DesignDoc" ),
            Map.entry( "design_doc", "DesignDoc" ) );

    /** Returns the wk: class local name for a node_type, defaulting to {@link #DEFAULT_CLASS}. */
    public static String classLocalName( final String nodeType ) {
        if ( nodeType == null || nodeType.isBlank() ) {
            return DEFAULT_CLASS;
        }
        final String key = nodeType.trim().toLowerCase( Locale.ROOT );
        return MAP.getOrDefault( key, DEFAULT_CLASS );
    }
}
```

- [ ] **Step 4: Run — verify it passes** (4 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-ontology/src/main/java/com/wikantik/ontology/NodeTypeMapping.java \
        wikantik-ontology/src/test/java/com/wikantik/ontology/NodeTypeMappingTest.java
git commit -m "feat(ontology): NodeTypeMapping (node_type -> wk: class, default Concept)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: `OntologyModelManager` — Jena dataset owner

**Files:** Create `OntologyModelManager.java`; Test `OntologyModelManagerTest.java`.

Responsibilities: own a Jena `Dataset` (in-memory or TDB2), load the T-Box into the default graph, replace/remove a named graph (transactionally), clear the A-Box, and produce an RDFS inference snapshot over (T-Box ∪ all named graphs).

- [ ] **Step 1: Write the failing test:**

```java
package com.wikantik.ontology;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;

class OntologyModelManagerTest {

    private Model entityGraph( final String entityIri, final String classLocal ) {
        final Model m = ModelFactory.createDefaultModel();
        m.add( ResourceFactory.createResource( entityIri ),
               RDF.type,
               ResourceFactory.createResource( Iris.term( classLocal ) ) );
        return m;
    }

    @Test
    void loadsTBoxAndReplacesNamedGraphs() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final String iri = Iris.entity( java.util.UUID.fromString( "00000000-0000-0000-0000-0000000000a1" ) );
        mgr.replaceNamedGraph( iri, entityGraph( iri, "Technology" ) );

        // The named graph is present...
        assertTrue( mgr.namedGraphExists( iri ) );
        // ...and removing it works.
        mgr.removeNamedGraph( iri );
        assertFalse( mgr.namedGraphExists( iri ) );
    }

    @Test
    void rdfsInferenceDerivesSchemaThingFromTechnology() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final String iri = Iris.entity( java.util.UUID.fromString( "00000000-0000-0000-0000-0000000000a2" ) );
        mgr.replaceNamedGraph( iri, entityGraph( iri, "Technology" ) );

        final Model inf = mgr.inferenceSnapshot();
        // wk:Technology rdfs:subClassOf schema:Thing (transitively, via wk:Entity), so the
        // individual is inferred to be a schema:Thing.
        assertTrue( inf.contains(
                ResourceFactory.createResource( iri ),
                RDF.type,
                ResourceFactory.createResource( Iris.SCHEMA + "Thing" ) ),
                "RDFS inference should type the Technology individual as schema:Thing" );
    }

    @Test
    void clearAboxLeavesTBoxIntact() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final String iri = Iris.entity( java.util.UUID.fromString( "00000000-0000-0000-0000-0000000000a3" ) );
        mgr.replaceNamedGraph( iri, entityGraph( iri, "Concept" ) );
        mgr.clearAbox();
        assertFalse( mgr.namedGraphExists( iri ), "A-Box graphs gone after clear" );
        // T-Box still declares wk:Concept as a class.
        assertTrue( mgr.tboxSnapshot().contains(
                ResourceFactory.createResource( Iris.term( "Concept" ) ),
                RDF.type,
                ResourceFactory.createResource( "http://www.w3.org/2002/07/owl#Class" ) ) );
    }
}
```

- [ ] **Step 2: Run — verify it fails** (no `OntologyModelManager`).

- [ ] **Step 3: Implement `OntologyModelManager.java`:**

```java
package com.wikantik.ontology;

import java.io.InputStream;
import java.util.Iterator;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Owns the Jena {@link Dataset} backing the ontology: the T-Box in the default
 * graph and one named graph per materialized resource. All mutations run in
 * write transactions (required by TDB2; harmless for the in-memory dataset).
 */
public final class OntologyModelManager {

    private static final Logger LOG = LogManager.getLogger( OntologyModelManager.class );
    private static final String TBOX_RESOURCE = "/ontology/wikantik.ttl";

    private final Dataset dataset;

    private OntologyModelManager( final Dataset dataset ) {
        this.dataset = dataset;
    }

    /** In-memory transactional dataset (tests). */
    public static OntologyModelManager inMemory() {
        return new OntologyModelManager( DatasetFactory.createTxnMem() );
    }

    /** Persistent TDB2-backed dataset at {@code dir} (production). */
    public static OntologyModelManager tdb2( final String dir ) {
        return new OntologyModelManager( TDB2Factory.connectDataset( dir ) );
    }

    /** Loads the bundled T-Box into the default graph (replacing any prior T-Box). */
    public void loadTBox() {
        dataset.begin( ReadWrite.WRITE );
        try ( InputStream in = OntologyModelManager.class.getResourceAsStream( TBOX_RESOURCE ) ) {
            if ( in == null ) {
                throw new IllegalStateException( TBOX_RESOURCE + " not found on classpath" );
            }
            final Model def = dataset.getDefaultModel();
            def.removeAll();
            RDFDataMgr.read( def, in, Lang.TURTLE );
            dataset.commit();
        } catch ( final Exception e ) {
            dataset.abort();
            LOG.warn( "failed loading T-Box {}: {}", TBOX_RESOURCE, e.getMessage(), e );
            throw new IllegalStateException( "T-Box load failed", e );
        } finally {
            dataset.end();
        }
    }

    /** Atomically replaces (or creates) the named graph at {@code graphIri} with {@code triples}. */
    public void replaceNamedGraph( final String graphIri, final Model triples ) {
        dataset.begin( ReadWrite.WRITE );
        try {
            dataset.replaceNamedModel( graphIri, triples );
            dataset.commit();
        } catch ( final RuntimeException e ) {
            dataset.abort();
            LOG.warn( "replaceNamedGraph failed for {}: {}", graphIri, e.getMessage(), e );
            throw e;
        } finally {
            dataset.end();
        }
    }

    /** Removes the named graph at {@code graphIri} (no-op if absent). */
    public void removeNamedGraph( final String graphIri ) {
        dataset.begin( ReadWrite.WRITE );
        try {
            dataset.removeNamedModel( graphIri );
            dataset.commit();
        } catch ( final RuntimeException e ) {
            dataset.abort();
            LOG.warn( "removeNamedGraph failed for {}: {}", graphIri, e.getMessage(), e );
            throw e;
        } finally {
            dataset.end();
        }
    }

    public boolean namedGraphExists( final String graphIri ) {
        dataset.begin( ReadWrite.READ );
        try {
            return dataset.containsNamedModel( graphIri )
                    && !dataset.getNamedModel( graphIri ).isEmpty();
        } finally {
            dataset.end();
        }
    }

    /** Removes every named graph (A-Box), leaving the default-graph T-Box intact. */
    public void clearAbox() {
        dataset.begin( ReadWrite.WRITE );
        try {
            for ( final Iterator< String > it = dataset.listNames(); it.hasNext(); ) {
                dataset.removeNamedModel( it.next() );
            }
            dataset.commit();
        } catch ( final RuntimeException e ) {
            dataset.abort();
            throw e;
        } finally {
            dataset.end();
        }
    }

    /** Detached copy of the T-Box (default graph). */
    public Model tboxSnapshot() {
        dataset.begin( ReadWrite.READ );
        try {
            return ModelFactory.createDefaultModel().add( dataset.getDefaultModel() );
        } finally {
            dataset.end();
        }
    }

    /**
     * Detached RDFS inference model over (T-Box default graph ∪ all named graphs).
     * Sized for the current corpus; callers query it outside any transaction.
     */
    public Model inferenceSnapshot() {
        dataset.begin( ReadWrite.READ );
        try {
            final Model union = ModelFactory.createDefaultModel();
            union.add( dataset.getDefaultModel() );
            for ( final Iterator< String > it = dataset.listNames(); it.hasNext(); ) {
                union.add( dataset.getNamedModel( it.next() ) );
            }
            final InfModel inf = ModelFactory.createRDFSModel( union );
            // Detach: materialize into a plain model so the caller is txn-free.
            return ModelFactory.createDefaultModel().add( inf );
        } finally {
            dataset.end();
        }
    }

    /** For Phase 1b shutdown wiring. */
    public void close() {
        dataset.close();
    }
}
```

- [ ] **Step 4: Run — verify it passes** (3 tests; the inference test confirms `subClassOf` entailment).

- [ ] **Step 5: Commit**

```bash
git add wikantik-ontology/src/main/java/com/wikantik/ontology/OntologyModelManager.java \
        wikantik-ontology/src/test/java/com/wikantik/ontology/OntologyModelManagerTest.java
git commit -m "feat(ontology): OntologyModelManager (TDB2/in-mem dataset, T-Box load, named graphs, RDFS inference)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: `EdgeProjector` — KgEdge → wk: property statement

**Files:** Create `projection/EdgeProjector.java`; Test `projection/EdgeProjectorTest.java`.

- [ ] **Step 1: Write the failing test:**

```java
package com.wikantik.ontology.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.ontology.Iris;
import org.apache.jena.rdf.model.Statement;
import org.junit.jupiter.api.Test;

class EdgeProjectorTest {

    private static final UUID S = UUID.fromString( "00000000-0000-0000-0000-0000000000b1" );
    private static final UUID T = UUID.fromString( "00000000-0000-0000-0000-0000000000b2" );

    private KgEdge edge( final String rel ) {
        return new KgEdge( UUID.randomUUID(), S, T, rel, Provenance.HUMAN_AUTHORED,
                Map.of(), null, null, "human", null );
    }

    @Test
    void mapsRelationshipTypeToWkProperty() {
        final Optional< Statement > st = EdgeProjector.toStatement( edge( "is_a" ) );
        assertTrue( st.isPresent() );
        assertEquals( Iris.entity( S ), st.get().getSubject().getURI() );
        assertEquals( Iris.term( "isA" ),  st.get().getPredicate().getURI() );
        assertEquals( Iris.entity( T ), st.get().getObject().asResource().getURI() );
    }

    @Test
    void multiWordRelationshipBecomesLowerCamel() {
        assertEquals( Iris.term( "alternativeTo" ),
                EdgeProjector.toStatement( edge( "alternative_to" ) ).get().getPredicate().getURI() );
        assertEquals( Iris.term( "locatedIn" ),
                EdgeProjector.toStatement( edge( "located_in" ) ).get().getPredicate().getURI() );
    }

    @Test
    void unknownRelationshipIsSkipped() {
        assertTrue( EdgeProjector.toStatement( edge( "frobnicates" ) ).isEmpty(),
                "predicate outside the closed vocabulary is skipped" );
    }
}
```

- [ ] **Step 2: Run — verify it fails.**

- [ ] **Step 3: Implement `projection/EdgeProjector.java`:**

```java
package com.wikantik.ontology.projection;

import java.util.Optional;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.RelationshipTypeVocabulary;
import com.wikantik.ontology.Iris;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Projects a Kg edge into a single wk: object-property statement. */
public final class EdgeProjector {

    private static final Logger LOG = LogManager.getLogger( EdgeProjector.class );

    private EdgeProjector() {}

    /** snake_case relationship_type -> wk: lowerCamel property local name. */
    static String propertyLocalName( final String relationshipType ) {
        final String[] parts = relationshipType.split( "_" );
        final StringBuilder sb = new StringBuilder( parts[ 0 ] );
        for ( int i = 1; i < parts.length; i++ ) {
            sb.append( Character.toUpperCase( parts[ i ].charAt( 0 ) ) ).append( parts[ i ].substring( 1 ) );
        }
        return sb.toString();
    }

    /**
     * Returns the triple {@code entity(source) wk:<prop> entity(target)}, or empty if the
     * relationship_type is outside the closed vocabulary (logged at WARN, never silently dropped).
     */
    public static Optional< Statement > toStatement( final KgEdge edge ) {
        if ( !RelationshipTypeVocabulary.isValid( edge.relationshipType() ) ) {
            LOG.warn( "skipping edge {} -> {}: relationship_type '{}' not in closed vocabulary",
                    edge.sourceId(), edge.targetId(), edge.relationshipType() );
            return Optional.empty();
        }
        return Optional.of( ResourceFactory.createStatement(
                ResourceFactory.createResource( Iris.entity( edge.sourceId() ) ),
                ResourceFactory.createProperty( Iris.term( propertyLocalName( edge.relationshipType() ) ) ),
                ResourceFactory.createResource( Iris.entity( edge.targetId() ) ) ) );
    }
}
```

- [ ] **Step 4: Run — verify it passes** (3 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-ontology/src/main/java/com/wikantik/ontology/projection/EdgeProjector.java \
        wikantik-ontology/src/test/java/com/wikantik/ontology/projection/EdgeProjectorTest.java
git commit -m "feat(ontology): EdgeProjector (relationship_type -> wk: object property)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: `EntityProjector` — KgNode (+ outgoing edges) → per-entity graph

**Files:** Create `projection/EntityProjector.java`; Test `projection/EntityProjectorTest.java`.

The per-entity named graph contains: `rdf:type wk:<class>`, `rdfs:label name`, provenance triples, and the node's **outgoing edges**. The projector is given the node's outgoing edges and a `slug → canonical_id` resolver (for `prov:wasDerivedFrom`).

- [ ] **Step 1: Write the failing test:**

```java
package com.wikantik.ontology.projection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.ontology.Iris;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;

class EntityProjectorTest {

    private static final UUID NID = UUID.fromString( "00000000-0000-0000-0000-0000000000c1" );
    private static final UUID TID = UUID.fromString( "00000000-0000-0000-0000-0000000000c2" );

    private KgNode node( final String type, final String sourcePage ) {
        return new KgNode( NID, "Backpropagation", type, sourcePage, Provenance.HUMAN_AUTHORED,
                Map.of(), null, null, "human", null );
    }

    @Test
    void typesAndLabelsTheEntity() {
        final Model g = EntityProjector.project( node( "technology", null ), List.of(), slug -> null );
        assertTrue( g.contains(
                ResourceFactory.createResource( Iris.entity( NID ) ), RDF.type,
                ResourceFactory.createResource( Iris.term( "Technology" ) ) ) );
        assertTrue( g.contains(
                ResourceFactory.createResource( Iris.entity( NID ) ), RDFS.label,
                g.createLiteral( "Backpropagation" ) ) );
    }

    @Test
    void nullTypeDefaultsToConcept() {
        final Model g = EntityProjector.project( node( null, null ), List.of(), slug -> null );
        assertTrue( g.contains(
                ResourceFactory.createResource( Iris.entity( NID ) ), RDF.type,
                ResourceFactory.createResource( Iris.term( "Concept" ) ) ) );
    }

    @Test
    void includesOutgoingEdges() {
        final KgEdge e = new KgEdge( UUID.randomUUID(), NID, TID, "enables", Provenance.HUMAN_AUTHORED,
                Map.of(), null, null, "human", null );
        final Model g = EntityProjector.project( node( "technology", null ), List.of( e ), slug -> null );
        assertTrue( g.contains(
                ResourceFactory.createResource( Iris.entity( NID ) ),
                ResourceFactory.createProperty( Iris.term( "enables" ) ),
                ResourceFactory.createResource( Iris.entity( TID ) ) ) );
    }

    @Test
    void derivesFromPageWhenSourcePageResolves() {
        final Model g = EntityProjector.project(
                node( "technology", "Backprop.md" ), List.of(),
                slug -> "Backprop.md".equals( slug ) ? "01CANONICALID00000000000000" : null );
        assertTrue( g.contains(
                ResourceFactory.createResource( Iris.entity( NID ) ),
                ResourceFactory.createProperty( "http://www.w3.org/ns/prov#wasDerivedFrom" ),
                ResourceFactory.createResource( Iris.page( "01CANONICALID00000000000000" ) ) ) );
    }

    @Test
    void stubNodeHasNoDerivation() {
        final Model g = EntityProjector.project( node( "technology", null ), List.of(), slug -> null );
        assertFalse( g.contains( (org.apache.jena.rdf.model.Resource) null,
                ResourceFactory.createProperty( "http://www.w3.org/ns/prov#wasDerivedFrom" ),
                (org.apache.jena.rdf.model.RDFNode) null ) );
    }
}
```

- [ ] **Step 2: Run — verify it fails.**

- [ ] **Step 3: Implement `projection/EntityProjector.java`:**

```java
package com.wikantik.ontology.projection;

import java.util.List;
import java.util.function.Function;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.ontology.Iris;
import com.wikantik.ontology.NodeTypeMapping;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

/** Projects a KG node (and its outgoing edges) into its per-entity named graph. */
public final class EntityProjector {

    private static final String PROV = "http://www.w3.org/ns/prov#";

    private EntityProjector() {}

    /**
     * @param node          the KG node
     * @param outgoingEdges edges whose sourceId == node.id()
     * @param slugToCanonicalId resolves a page slug to its canonical_id (null if unknown)
     */
    public static Model project( final KgNode node, final List< KgEdge > outgoingEdges,
                                 final Function< String, String > slugToCanonicalId ) {
        final Model m = ModelFactory.createDefaultModel();
        final Resource subject = m.createResource( Iris.entity( node.id() ) );

        m.add( subject, RDF.type, m.createResource( Iris.term( NodeTypeMapping.classLocalName( node.nodeType() ) ) ) );
        if ( node.name() != null ) {
            m.add( subject, RDFS.label, m.createLiteral( node.name() ) );
        }

        // Provenance: attribution by tier, derivation from the source page (when resolvable).
        m.add( subject, m.createProperty( PROV + "wasAttributedTo" ),
               m.createResource( Iris.NS + ( "machine".equalsIgnoreCase( node.tier() ) ? "MachineAgent" : "HumanAgent" ) ) );
        if ( node.sourcePage() != null ) {
            final String canonicalId = slugToCanonicalId.apply( node.sourcePage() );
            if ( canonicalId != null ) {
                m.add( subject, m.createProperty( PROV + "wasDerivedFrom" ),
                       m.createResource( Iris.page( canonicalId ) ) );
            }
        }

        for ( final KgEdge e : outgoingEdges ) {
            EdgeProjector.toStatement( e ).ifPresent( m::add );
        }
        return m;
    }
}
```

> Note: `prov:wasAttributedTo` points at `wk:MachineAgent` / `wk:HumanAgent` — two `prov:Agent` individuals. Add them to the T-Box in Task 9's adjustment step (a 2-line addition to `wikantik.ttl`) so they are declared; the projector references them regardless.

- [ ] **Step 4: Run — verify it passes** (5 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-ontology/src/main/java/com/wikantik/ontology/projection/EntityProjector.java \
        wikantik-ontology/src/test/java/com/wikantik/ontology/projection/EntityProjectorTest.java
git commit -m "feat(ontology): EntityProjector (node + outgoing edges -> per-entity graph)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: `PageRecord` + `PageProjector`

**Files:** Create `projection/PageRecord.java`, `projection/PageProjector.java`; Test `projection/PageProjectorTest.java`.

- [ ] **Step 1: Create the input DTO `projection/PageRecord.java`** (no failing test needed for a plain record; it's exercised by the projector test):

```java
package com.wikantik.ontology.projection;

import java.util.List;

/**
 * Flattened page input for the Page/Concept projectors. Built in Phase 1b from
 * PageCanonicalIdsDao.Row + FrontmatterParser; kept api-free so this module
 * has no wikantik-main dependency.
 */
public record PageRecord(
        String canonicalId,
        String slug,
        String title,
        String type,        // frontmatter type: hub/article/reference/runbook/design
        String cluster,     // may be null; "parent/sub" for sub-clusters
        List< String > tags,
        String summary,
        String isoDate,     // frontmatter date as ISO string, may be null
        String author       // may be null
) {
    public PageRecord {
        tags = tags == null ? List.of() : List.copyOf( tags );
    }
}
```

- [ ] **Step 2: Write the failing test `projection/PageProjectorTest.java`:**

```java
package com.wikantik.ontology.projection;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.wikantik.ontology.Iris;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

class PageProjectorTest {

    private static final String CID = "01KTGSV4B1PR4RBEGQGBJVNXN0";

    private PageRecord page( final String type, final String cluster, final List< String > tags ) {
        return new PageRecord( CID, "GraphDatabaseFundamentals", "Graph Database Fundamentals",
                type, cluster, tags, "Intro to graph DBs", "2026-03-14", "claude-code-researcher" );
    }

    @Test
    void typesPageByContentClassAndSetsUrlAndTitle() {
        final Model g = PageProjector.project( page( "article", "graph-databases", List.of() ) );
        final var subj = ResourceFactory.createResource( Iris.page( CID ) );
        assertTrue( g.contains( subj, RDF.type, ResourceFactory.createResource( Iris.term( "Article" ) ) ) );
        assertTrue( g.contains( subj,
                ResourceFactory.createProperty( "https://schema.org/url" ),
                ResourceFactory.createResource( "https://wiki.wikantik.com/wiki/GraphDatabaseFundamentals" ) ) );
        assertTrue( g.contains( subj,
                ResourceFactory.createProperty( "http://purl.org/dc/terms/title" ),
                g.createLiteral( "Graph Database Fundamentals" ) ) );
    }

    @Test
    void hubMapsToHubClass() {
        final Model g = PageProjector.project( page( "hub", "graph-databases", List.of() ) );
        assertTrue( g.contains( ResourceFactory.createResource( Iris.page( CID ) ),
                RDF.type, ResourceFactory.createResource( Iris.term( "Hub" ) ) ) );
    }

    @Test
    void linksTagsAndClusterAsDctSubject() {
        final Model g = PageProjector.project( page( "article", "graph-databases", List.of( "databases", "nosql" ) ) );
        final var subj = ResourceFactory.createResource( Iris.page( CID ) );
        final var dctSubject = ResourceFactory.createProperty( "http://purl.org/dc/terms/subject" );
        assertTrue( g.contains( subj, dctSubject, ResourceFactory.createResource( Iris.concept( "databases" ) ) ) );
        assertTrue( g.contains( subj, dctSubject, ResourceFactory.createResource( Iris.concept( "nosql" ) ) ) );
        assertTrue( g.contains( subj, dctSubject, ResourceFactory.createResource( Iris.concept( "graph-databases" ) ) ),
                "the cluster is also a dct:subject concept" );
    }
}
```

- [ ] **Step 3: Run — verify it fails.**

- [ ] **Step 4: Implement `projection/PageProjector.java`:**

```java
package com.wikantik.ontology.projection;

import com.wikantik.ontology.Iris;
import com.wikantik.ontology.NodeTypeMapping;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

/** Projects a page (canonical_id + frontmatter) into its per-page named graph. */
public final class PageProjector {

    private static final String DCT    = "http://purl.org/dc/terms/";
    private static final String SCHEMA = "https://schema.org/";
    private static final String PROV   = "http://www.w3.org/ns/prov#";
    private static final String WIKI_BASE = "https://wiki.wikantik.com/wiki/";

    private PageProjector() {}

    public static Model project( final PageRecord page ) {
        final Model m = ModelFactory.createDefaultModel();
        final Resource subject = m.createResource( Iris.page( page.canonicalId() ) );

        // type: reuses NodeTypeMapping (hub/article/reference/runbook/design -> content class).
        m.add( subject, RDF.type, m.createResource( Iris.term( NodeTypeMapping.classLocalName( page.type() ) ) ) );
        if ( page.title() != null ) {
            m.add( subject, m.createProperty( DCT + "title" ), m.createLiteral( page.title() ) );
        }
        if ( page.slug() != null ) {
            m.add( subject, m.createProperty( SCHEMA + "url" ), m.createResource( WIKI_BASE + page.slug() ) );
        }
        if ( page.summary() != null ) {
            m.add( subject, m.createProperty( DCT + "description" ), m.createLiteral( page.summary() ) );
        }
        if ( page.isoDate() != null ) {
            m.add( subject, m.createProperty( DCT + "created" ), m.createLiteral( page.isoDate() ) );
        }
        if ( page.author() != null ) {
            m.add( subject, m.createProperty( DCT + "creator" ), m.createLiteral( page.author() ) );
        }
        // dct:subject links to tag + cluster concepts.
        for ( final String tag : page.tags() ) {
            m.add( subject, m.createProperty( DCT + "subject" ), m.createResource( Iris.concept( tag ) ) );
        }
        if ( page.cluster() != null && !page.cluster().isBlank() ) {
            m.add( subject, m.createProperty( DCT + "subject" ), m.createResource( Iris.concept( page.cluster() ) ) );
        }
        return m;
    }
}
```

- [ ] **Step 5: Run — verify it passes** (3 tests).

- [ ] **Step 6: Commit**

```bash
git add wikantik-ontology/src/main/java/com/wikantik/ontology/projection/PageRecord.java \
        wikantik-ontology/src/main/java/com/wikantik/ontology/projection/PageProjector.java \
        wikantik-ontology/src/test/java/com/wikantik/ontology/projection/PageProjectorTest.java
git commit -m "feat(ontology): PageRecord + PageProjector (page -> content-class graph + dct:subject concepts)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: `ConceptProjector` — tags/clusters → SKOS scheme

**Files:** Create `projection/ConceptProjector.java`; Test `projection/ConceptProjectorTest.java`.

Builds one `skos:Concept` per distinct tag/cluster across all pages, `skos:inScheme wk:WikiConcepts`, with `skos:broader` from a sub-cluster (`parent/sub`) to its parent.

- [ ] **Step 1: Write the failing test:**

```java
package com.wikantik.ontology.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.wikantik.ontology.Iris;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

class ConceptProjectorTest {

    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";

    private PageRecord page( final String cluster, final List< String > tags ) {
        return new PageRecord( "C" + cluster, "Slug" + cluster, "T", "article", cluster, tags, null, null, null );
    }

    @Test
    void emitsOneConceptPerDistinctTagAndClusterInScheme() {
        final Map< String, Model > graphs = ConceptProjector.project( List.of(
                page( "graph-databases", List.of( "databases", "nosql" ) ),
                page( "graph-databases", List.of( "databases" ) ) ) );
        // distinct: databases, nosql, graph-databases (cluster)
        assertEquals( 3, graphs.size() );
        final Model dbs = graphs.get( Iris.concept( "databases" ) );
        assertTrue( dbs.contains( ResourceFactory.createResource( Iris.concept( "databases" ) ),
                RDF.type, ResourceFactory.createResource( SKOS + "Concept" ) ) );
        assertTrue( dbs.contains( ResourceFactory.createResource( Iris.concept( "databases" ) ),
                ResourceFactory.createProperty( SKOS + "inScheme" ),
                ResourceFactory.createResource( Iris.term( "WikiConcepts" ) ) ) );
    }

    @Test
    void subClusterGetsSkosBroaderToParent() {
        final Map< String, Model > graphs = ConceptProjector.project( List.of(
                page( "retirement-planning/eu-retirement", List.of() ) ) );
        final Model sub = graphs.get( Iris.concept( "retirement-planning/eu-retirement" ) );
        assertTrue( sub.contains(
                ResourceFactory.createResource( Iris.concept( "retirement-planning/eu-retirement" ) ),
                ResourceFactory.createProperty( SKOS + "broader" ),
                ResourceFactory.createResource( Iris.concept( "retirement-planning" ) ) ) );
    }
}
```

- [ ] **Step 2: Run — verify it fails.**

- [ ] **Step 3: Implement `projection/ConceptProjector.java`:**

```java
package com.wikantik.ontology.projection;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.wikantik.ontology.Iris;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

/** Projects distinct tags + clusters across all pages into per-concept SKOS graphs. */
public final class ConceptProjector {

    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";

    private ConceptProjector() {}

    /** Returns a map of concept-IRI -> its named-graph Model (one entry per distinct tag/cluster). */
    public static Map< String, Model > project( final List< PageRecord > pages ) {
        // Collect distinct raw values (preserving the original label for skos:prefLabel).
        final Set< String > values = new LinkedHashSet<>();
        for ( final PageRecord p : pages ) {
            values.addAll( p.tags() );
            if ( p.cluster() != null && !p.cluster().isBlank() ) {
                values.add( p.cluster() );
            }
        }
        final Map< String, Model > out = new HashMap<>();
        for ( final String value : values ) {
            final String iri = Iris.concept( value );
            final Model m = ModelFactory.createDefaultModel();
            final Resource concept = m.createResource( iri );
            m.add( concept, RDF.type, m.createResource( SKOS + "Concept" ) );
            m.add( concept, m.createProperty( SKOS + "inScheme" ), m.createResource( Iris.term( "WikiConcepts" ) ) );
            m.add( concept, m.createProperty( SKOS + "prefLabel" ), m.createLiteral( value ) );
            m.add( concept, RDFS.label, m.createLiteral( value ) );
            // Sub-cluster "parent/sub" -> skos:broader parent.
            final int slash = value.lastIndexOf( '/' );
            if ( slash > 0 ) {
                final String parent = value.substring( 0, slash );
                m.add( concept, m.createProperty( SKOS + "broader" ), m.createResource( Iris.concept( parent ) ) );
            }
            out.put( iri, m );
        }
        return out;
    }
}
```

- [ ] **Step 4: Run — verify it passes** (2 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-ontology/src/main/java/com/wikantik/ontology/projection/ConceptProjector.java \
        wikantik-ontology/src/test/java/com/wikantik/ontology/projection/ConceptProjectorTest.java
git commit -m "feat(ontology): ConceptProjector (tags/clusters -> SKOS concepts + skos:broader)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 9: `OntologyRebuildService` — full A-Box materialization

**Files:**
- Modify: `wikantik-ontology/src/main/resources/ontology/wikantik.ttl` (declare the two PROV agents referenced by EntityProjector)
- Create: `OntologyRebuildService.java`
- Test: `OntologyRebuildServiceTest.java`

- [ ] **Step 1: Add the two PROV agents to `wikantik.ttl`** (append after the SKOS scheme block, before the object-property block):

```turtle
# Provenance agents (referenced by entity projection)
@prefix prov: <http://www.w3.org/ns/prov#> .
wk:HumanAgent   a prov:Agent ; rdfs:label "Human author" .
wk:MachineAgent a prov:Agent ; rdfs:label "LLM extractor" .
```
(If `@prefix prov:` is already declared at the top of the file, do **not** redeclare it — just add the two `wk:*Agent` triples.)

- [ ] **Step 2: Write the failing test `OntologyRebuildServiceTest.java`:**

```java
package com.wikantik.ontology;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.ontology.projection.PageRecord;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

class OntologyRebuildServiceTest {

    private static final UUID N1 = UUID.fromString( "00000000-0000-0000-0000-0000000000d1" );
    private static final UUID N2 = UUID.fromString( "00000000-0000-0000-0000-0000000000d2" );

    @Test
    void fullRebuildMaterializesEntitiesPagesConceptsAndInfersTypes() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();

        final List< KgNode > nodes = List.of(
                new KgNode( N1, "Backpropagation", "technology", "Backprop.md", Provenance.HUMAN_AUTHORED,
                        Map.of(), null, null, "human", null ),
                new KgNode( N2, "Gradient", "concept", null, Provenance.HUMAN_AUTHORED,
                        Map.of(), null, null, "human", null ) );
        final List< KgEdge > edges = List.of(
                new KgEdge( UUID.randomUUID(), N1, N2, "uses", Provenance.HUMAN_AUTHORED,
                        Map.of(), null, null, "human", null ) );
        final List< PageRecord > pages = List.of(
                new PageRecord( "01CANON0000000000000000001", "Backprop.md", "Backprop", "article",
                        "ml", List.of( "neural-networks" ), "x", "2026-01-01", "researcher" ) );

        final OntologyRebuildService svc = new OntologyRebuildService();
        final int graphs = svc.rebuild( mgr, nodes, edges, pages );

        // 2 entities + 1 page + 2 concepts (cluster "ml" + tag "neural-networks") = 5 named graphs.
        assertTrue( graphs >= 5, "expected >= 5 named graphs, got " + graphs );

        // Entity graph present + edge materialized.
        assertTrue( mgr.namedGraphExists( Iris.entity( N1 ) ) );

        // subClassOf inference: the Technology entity is a schema:Thing.
        final Model inf = mgr.inferenceSnapshot();
        assertTrue( inf.contains(
                ResourceFactory.createResource( Iris.entity( N1 ) ), RDF.type,
                ResourceFactory.createResource( Iris.SCHEMA + "Thing" ) ) );
    }

    @Test
    void rebuildIsIdempotent() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final List< KgNode > nodes = List.of(
                new KgNode( N1, "X", "concept", null, Provenance.HUMAN_AUTHORED, Map.of(), null, null, "human", null ) );
        final OntologyRebuildService svc = new OntologyRebuildService();
        final int first  = svc.rebuild( mgr, nodes, List.of(), List.of() );
        final int second = svc.rebuild( mgr, nodes, List.of(), List.of() );
        assertTrue( first == second, "rebuild graph count is stable across runs" );
    }
}
```

- [ ] **Step 3: Run — verify it fails.**

- [ ] **Step 4: Implement `OntologyRebuildService.java`:**

```java
package com.wikantik.ontology;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.ontology.projection.ConceptProjector;
import com.wikantik.ontology.projection.EntityProjector;
import com.wikantik.ontology.projection.PageProjector;
import com.wikantik.ontology.projection.PageRecord;
import org.apache.jena.rdf.model.Model;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Materializes the full A-Box from in-memory inputs: clears existing named
 * graphs, then projects every entity (with its outgoing edges), page, and
 * concept into its own named graph. Pure orchestration — the caller (Phase 1b)
 * supplies the lists from the repositories.
 */
public final class OntologyRebuildService {

    private static final Logger LOG = LogManager.getLogger( OntologyRebuildService.class );

    /** @return the number of named graphs written. */
    public int rebuild( final OntologyModelManager mgr,
                        final List< KgNode > nodes,
                        final List< KgEdge > edges,
                        final List< PageRecord > pages ) {
        mgr.clearAbox();

        // Index outgoing edges by source node, and pages' slug -> canonical_id.
        final Map< UUID, List< KgEdge > > outgoing = new HashMap<>();
        for ( final KgEdge e : edges ) {
            outgoing.computeIfAbsent( e.sourceId(), k -> new ArrayList<>() ).add( e );
        }
        final Map< String, String > slugToCanonical = new HashMap<>();
        for ( final PageRecord p : pages ) {
            if ( p.slug() != null ) {
                slugToCanonical.put( p.slug(), p.canonicalId() );
            }
        }

        int written = 0;
        for ( final KgNode node : nodes ) {
            final Model g = EntityProjector.project(
                    node, outgoing.getOrDefault( node.id(), List.of() ), slugToCanonical::get );
            mgr.replaceNamedGraph( Iris.entity( node.id() ), g );
            written++;
        }
        for ( final PageRecord page : pages ) {
            mgr.replaceNamedGraph( Iris.page( page.canonicalId() ), PageProjector.project( page ) );
            written++;
        }
        for ( final Map.Entry< String, Model > c : ConceptProjector.project( pages ).entrySet() ) {
            mgr.replaceNamedGraph( c.getKey(), c.getValue() );
            written++;
        }
        LOG.info( "ontology rebuild: {} named graphs ({} nodes, {} edges, {} pages)",
                written, nodes.size(), edges.size(), pages.size() );
        return written;
    }
}
```

- [ ] **Step 5: Run — verify it passes** (2 tests; confirms full rebuild + cross-component inference + idempotent graph count).

- [ ] **Step 6: Commit**

```bash
git add wikantik-ontology/src/main/resources/ontology/wikantik.ttl \
        wikantik-ontology/src/main/java/com/wikantik/ontology/OntologyRebuildService.java \
        wikantik-ontology/src/test/java/com/wikantik/ontology/OntologyRebuildServiceTest.java
git commit -m "feat(ontology): OntologyRebuildService (full A-Box materialization) + PROV agents

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 10: Phase 1 verification gate

**Files:** none (verification only).

- [ ] **Step 1: Full module build + RAT** — `mvn -o -pl wikantik-ontology clean install apache-rat:check`. Expected: all Phase 0 + Phase 1 tests pass, `Unapproved: 0`, BUILD SUCCESS.

- [ ] **Step 2: Re-confirm Phase 0 drift guards still pass** (the T-Box edit in Task 9 added PROV agents — make sure `ObjectPropertyTest`/`ClassHierarchyTest` are unaffected). Covered by Step 1's full run, but eyeball that `ClassHierarchyTest` + `ObjectPropertyTest` are still green in the output.

- [ ] **Step 3: Full reactor validate** — `mvn -o validate` — confirms the `jena-tdb2` BOM/root-pom edit didn't break reactor resolution.

- [ ] **Step 4: Confirm with the user before starting Phase 1b** (the WikiEngine wiring + `/admin/ontology/rebuild` endpoint + startup-if-empty + full integration-test reactor gate).

---

## Self-Review (against spec §2/§3/§5 Phase 1)

**Spec coverage:**
- §5 Phase 1 "OntologyModelManager (TDB2 + RDFS inference)" → Task 4 (in-mem + `tdb2()` factory; `inferenceSnapshot()` does RDFS). ✓
- §5 "the 4 projectors → named graphs" → Tasks 5–8 (Edge/Entity/Page/Concept). ✓
- §3.3 "named-graph-per-resource" → every projector output is loaded at its resource IRI; entity graph carries outgoing edges. ✓
- §5 "OntologyRebuildService (full rebuild …)" → Task 9. **"startup-if-empty" + `/admin/ontology/rebuild`** are explicitly deferred to **Phase 1b** (documented in the SCOPE section) because they require wikantik-main/WikiEngine wiring + the IT reactor. ✓ (scoped, not dropped)
- §5 Phase 1 verification gate "golden-triple projector tests; full rebuild == expected graph; subClassOf inference fires" → Tasks 5–8 are golden-triple; Task 9 asserts full-rebuild graph count + `schema:Thing` inference. ✓
- §2.5 PROV-O → entity `wasAttributedTo`/`wasDerivedFrom` (Task 6) + agent declarations (Task 9). ✓
- §2.4 SKOS scheme instances → Task 8 (concepts `inScheme wk:WikiConcepts`, `skos:broader` for sub-clusters). ✓
- Phase 0 carve-outs (NULL node_type, `intelligence-summary`) → handled by `NodeTypeMapping` default → `wk:Concept` (Task 3, tested). ✓

**Placeholder scan:** none — every class + test is complete code. The one cross-task addition (PROV agents in `wikantik.ttl`) is in Task 9 Step 1 with exact Turtle.

**Type consistency:** `KgNode`/`KgEdge` constructors match the verified records (10 components each, `tier` String, `provenanceProposalId` UUID). `Iris.term/entity/page/concept`, `NodeTypeMapping.classLocalName`, `EdgeProjector.toStatement`, `EntityProjector.project(node, edges, Function<String,String>)`, `PageProjector.project(PageRecord)`, `ConceptProjector.project(List<PageRecord>)`, and `OntologyRebuildService.rebuild(mgr, nodes, edges, pages)` are referenced consistently across tasks. `RelationshipTypeVocabulary.isValid` (Phase 0-verified) gates EdgeProjector.

**Dependency boundary:** module stays `wikantik-api` + Jena only (no wikantik-main) — verified: `KgNode`/`KgEdge`/`Provenance`/`RelationshipTypeVocabulary` are all in `wikantik-api`. wikantik-main coupling is entirely in Phase 1b.
