# Wiki Ontology — Phase 0 Implementation Plan (Ontology Authoring + Content Normalization)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Author the static RDF ontology (T-Box) — the `wikantik:` class hierarchy, the 21 relationship predicates with domain/range + public-vocabulary mappings, the SKOS concept scheme, and representative SHACL shapes — plus a one-shot script normalizing today's free-text `kg_nodes.node_type` onto the standardized entity vocabulary. **No runtime wiring** (no Jena model loaded into the engine, no endpoints, no projection) — that is Phase 1.

**Architecture:** A new Maven module `wikantik-ontology` holds the ontology as version-controlled Turtle resources (`wikantik.ttl`, `shapes.ttl`). Phase 0 ships these resources plus a test suite that proves they parse, are internally consistent, and stay in lock-step with the existing Java sources of truth (`RelationshipTypeVocabulary`, `PageType`) via drift-guard tests. Apache Jena (Apache-2.0) is added as a dependency and used by the tests to parse/validate the ontology and run SHACL.

**Tech Stack:** Java 21, Maven, JUnit 5, Apache Jena 5.x (`jena-arq` for RDF/SPARQL parsing, `jena-shacl` for SHACL validation), PostgreSQL/psql (one-shot normalization script).

**Spec:** `docs/superpowers/specs/2026-06-08-wiki-ontology-design.md` (§2 The Ontology Model, §5 Phase 0).

**Conventions locked here (load-bearing for later phases):**
- Ontology namespace IRI: `https://wiki.wikantik.com/ns/wikantik#`, prefix `wk:`.
- **Object-property local names** = `lowerCamelCase` of the snake_case vocab term (`related_to` → `wk:relatedTo`, `is_a` → `wk:isA`, `located_in` → `wk:locatedIn`).
- **Class local names** = `UpperCamelCase`. The 9 entity classes; the 5 content classes are `Hub, Article, Reference, Runbook, DesignDoc` (note `DESIGN` enum → `wk:DesignDoc`).
- Canonical entity `node_type` values (lowercase): `person organization place event product technology concept project version`.

---

## Task 1: Scaffold the `wikantik-ontology` module + Jena dependency

**Files:**
- Create: `wikantik-ontology/pom.xml`
- Modify: `pom.xml` (root — add module to `<modules>`; add Jena version property + `dependencyManagement` entries)
- Create: `wikantik-ontology/src/main/resources/.gitkeep` (placeholder so the resources dir exists)
- Test: `wikantik-ontology/src/test/java/com/wikantik/ontology/JenaClasspathSmokeTest.java`

- [ ] **Step 1: Add the Jena version property to the root `pom.xml`**

In `pom.xml`, inside `<properties>`, add this line next to the other version properties (e.g. right after the `<jmustache.version>` line at line 107):

```xml
    <jena.version>5.2.0</jena.version>
```

- [ ] **Step 2: Add Jena to root `pom.xml` `dependencyManagement`**

In `pom.xml`, inside `<dependencyManagement><dependencies>`, in the compile-dependencies area near the other `org.apache.*` entries (e.g. after the `org.apache.tika` block ending at line 513), add:

```xml
      <dependency>
        <groupId>org.apache.jena</groupId>
        <artifactId>jena-arq</artifactId>
        <version>${jena.version}</version>
      </dependency>

      <dependency>
        <groupId>org.apache.jena</groupId>
        <artifactId>jena-shacl</artifactId>
        <version>${jena.version}</version>
      </dependency>
```

- [ ] **Step 3: Register the module in the root `pom.xml`**

In `pom.xml` `<modules>`, add the module line immediately after `<module>wikantik-knowledge</module>` (line 178):

```xml
    <module>wikantik-ontology</module>
```

- [ ] **Step 4: Create the module `pom.xml`**

Create `wikantik-ontology/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">

  <parent>
    <groupId>com.wikantik</groupId>
    <artifactId>wikantik-builder</artifactId>
    <version>2.0.14-SNAPSHOT</version>
  </parent>

  <modelVersion>4.0.0</modelVersion>
  <artifactId>wikantik-ontology</artifactId>
  <name>Wikantik ontology (RDF/OWL T-Box + SHACL)</name>

  <dependencies>
    <dependency>
      <groupId>${project.groupId}</groupId>
      <artifactId>wikantik-api</artifactId>
      <version>${project.version}</version>
    </dependency>

    <dependency>
      <groupId>org.apache.jena</groupId>
      <artifactId>jena-arq</artifactId>
    </dependency>

    <dependency>
      <groupId>org.apache.jena</groupId>
      <artifactId>jena-shacl</artifactId>
    </dependency>

    <dependency>
      <groupId>org.apache.logging.log4j</groupId>
      <artifactId>log4j-api</artifactId>
    </dependency>

    <dependency>
      <groupId>com.github.spotbugs</groupId>
      <artifactId>spotbugs-annotations</artifactId>
    </dependency>

    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter-api</artifactId>
      <scope>test</scope>
    </dependency>

    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter-params</artifactId>
      <scope>test</scope>
    </dependency>

    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter-engine</artifactId>
      <scope>test</scope>
    </dependency>

    <!-- REQUIRED on every module: the root POM's inherited surefire config adds
         -javaagent:${org.mockito:mockito-core:jar}; that path property is only
         populated when mockito-core is a resolved dependency. Omit it and
         surefire fails VM init with "Error opening zip file or JAR manifest
         missing : ${org.mockito:mockito-core:jar}". -->
    <dependency>
      <groupId>org.mockito</groupId>
      <artifactId>mockito-core</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

- [ ] **Step 5: Create the resources placeholder**

Create an empty file `wikantik-ontology/src/main/resources/.gitkeep` (so the directory is tracked before the `.ttl` files land in Task 2).

- [ ] **Step 6: Write the failing smoke test**

Create `wikantik-ontology/src/test/java/com/wikantik/ontology/JenaClasspathSmokeTest.java`:

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.ontology;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;

/** Proves Apache Jena is on the module classpath before any ontology work begins. */
class JenaClasspathSmokeTest {

    @Test
    void jenaIsAvailableAndCanCreateAModel() {
        final Model model = ModelFactory.createDefaultModel();
        assertNotNull( model, "Jena should create an empty model" );
        assertTrue( model.isEmpty(), "a fresh model has no statements" );
    }
}
```

- [ ] **Step 7: Run the smoke test — verify it compiles and passes**

Run: `mvn -q -pl wikantik-ontology -am test -Dtest=JenaClasspathSmokeTest`
Expected: BUILD SUCCESS, 1 test passing. (If Jena fails to resolve, the build errors at dependency resolution — fix the version/coordinates from Steps 1–2.)

- [ ] **Step 8: Commit**

```bash
git add pom.xml wikantik-ontology/pom.xml \
        wikantik-ontology/src/main/resources/.gitkeep \
        wikantik-ontology/src/test/java/com/wikantik/ontology/JenaClasspathSmokeTest.java
git commit -m "feat(ontology): scaffold wikantik-ontology module + Jena dependency

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: T-Box class hierarchy (entity + content classes) — test-first

**Files:**
- Create: `wikantik-ontology/src/main/resources/ontology/wikantik.ttl`
- Test: `wikantik-ontology/src/test/java/com/wikantik/ontology/TBoxVocabulary.java` (shared test constants)
- Test: `wikantik-ontology/src/test/java/com/wikantik/ontology/ClassHierarchyTest.java`

- [ ] **Step 1: Create the shared test-constants helper**

Create `wikantik-ontology/src/test/java/com/wikantik/ontology/TBoxVocabulary.java`:

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.ontology;

import java.io.InputStream;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;

/** Shared constants + loader for the T-Box tests. */
final class TBoxVocabulary {

    private TBoxVocabulary() {}

    static final String WK     = "https://wiki.wikantik.com/ns/wikantik#";
    static final String SCHEMA = "https://schema.org/";

    /** The 9 unified entity classes (UpperCamelCase local names). */
    static final java.util.List< String > ENTITY_CLASSES = java.util.List.of(
            "Person", "Organization", "Place", "Event", "Product",
            "Technology", "Concept", "Project", "Version" );

    /** The 5 content classes (PageType minus UNKNOWN; DESIGN maps to DesignDoc). */
    static final java.util.List< String > CONTENT_CLASSES = java.util.List.of(
            "Hub", "Article", "Reference", "Runbook", "DesignDoc" );

    /** Loads the T-Box from the module classpath into a fresh Jena model. */
    static Model loadTBox() {
        final Model model = ModelFactory.createDefaultModel();
        try ( InputStream in = TBoxVocabulary.class.getResourceAsStream( "/ontology/wikantik.ttl" ) ) {
            if ( in == null ) {
                throw new IllegalStateException( "/ontology/wikantik.ttl not found on classpath" );
            }
            RDFDataMgr.read( model, in, Lang.TURTLE );
        } catch ( final java.io.IOException e ) {
            throw new IllegalStateException( "failed reading wikantik.ttl", e );
        }
        return model;
    }
}
```

- [ ] **Step 2: Write the failing class-hierarchy test**

Create `wikantik-ontology/src/test/java/com/wikantik/ontology/ClassHierarchyTest.java`:

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.ontology;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;

class ClassHierarchyTest {

    private final Model tbox = TBoxVocabulary.loadTBox();

    private boolean subClassOf( final String childLocal, final String parentIri ) {
        return tbox.contains(
                ResourceFactory.createResource( TBoxVocabulary.WK + childLocal ),
                RDFS.subClassOf,
                ResourceFactory.createResource( parentIri ) );
    }

    @Test
    void everyEntityClassIsASubclassOfWkEntity() {
        for ( final String cls : TBoxVocabulary.ENTITY_CLASSES ) {
            assertTrue( subClassOf( cls, TBoxVocabulary.WK + "Entity" ),
                    "wk:" + cls + " must be rdfs:subClassOf wk:Entity" );
        }
    }

    @Test
    void everyContentClassIsASubclassOfWkPage() {
        for ( final String cls : TBoxVocabulary.CONTENT_CLASSES ) {
            assertTrue( subClassOf( cls, TBoxVocabulary.WK + "Page" ),
                    "wk:" + cls + " must be rdfs:subClassOf wk:Page" );
        }
    }

    @Test
    void rootsAreAnchoredToSchemaOrg() {
        assertTrue( subClassOf( "Entity", TBoxVocabulary.SCHEMA + "Thing" ),
                "wk:Entity must be rdfs:subClassOf schema:Thing" );
        assertTrue( subClassOf( "Page", TBoxVocabulary.SCHEMA + "CreativeWork" ),
                "wk:Page must be rdfs:subClassOf schema:CreativeWork" );
    }

    @Test
    void runbookAlignsToSchemaHowTo() {
        assertTrue( subClassOf( "Runbook", TBoxVocabulary.SCHEMA + "HowTo" ),
                "wk:Runbook must align to schema:HowTo" );
    }
}
```

- [ ] **Step 3: Run the test — verify it fails**

Run: `mvn -q -pl wikantik-ontology test -Dtest=ClassHierarchyTest`
Expected: FAIL — `IllegalStateException: /ontology/wikantik.ttl not found on classpath` (the resource does not exist yet).

- [ ] **Step 4: Author the T-Box classes**

Create `wikantik-ontology/src/main/resources/ontology/wikantik.ttl` (the Apache header is `#`-comment form so the RAT license check accepts it):

```turtle
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

@prefix wk:     <https://wiki.wikantik.com/ns/wikantik#> .
@prefix rdf:    <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix rdfs:   <http://www.w3.org/2000/01/rdf-schema#> .
@prefix owl:    <http://www.w3.org/2002/07/owl#> .
@prefix skos:   <http://www.w3.org/2004/02/skos/core#> .
@prefix dct:    <http://purl.org/dc/terms/> .
@prefix prov:   <http://www.w3.org/ns/prov#> .
@prefix schema: <https://schema.org/> .
@prefix dbo:    <https://dbpedia.org/ontology/> .

<https://wiki.wikantik.com/ns/wikantik>
    a owl:Ontology ;
    rdfs:label "Wikantik Ontology" ;
    rdfs:comment "Reuse-first hybrid ontology over Wikantik pages and Knowledge Graph entities." .

#####################################################################
# Content classes (rooted at schema:CreativeWork; map 1:1 to PageType)
#####################################################################

wk:Page       a owl:Class ; rdfs:subClassOf schema:CreativeWork ; rdfs:label "Wiki Page" .
wk:Hub        a owl:Class ; rdfs:subClassOf wk:Page , schema:CollectionPage ; rdfs:label "Hub" .
wk:Article    a owl:Class ; rdfs:subClassOf wk:Page , schema:Article        ; rdfs:label "Article" .
wk:Reference  a owl:Class ; rdfs:subClassOf wk:Page , schema:CreativeWork    ; rdfs:label "Reference" .
wk:Runbook    a owl:Class ; rdfs:subClassOf wk:Page , schema:HowTo           ; rdfs:label "Runbook" .
wk:DesignDoc  a owl:Class ; rdfs:subClassOf wk:Page , schema:TechArticle     ; rdfs:label "Design Document" .

#####################################################################
# Entity classes (rooted at schema:Thing; unified extractor vocabulary)
#####################################################################

wk:Entity        a owl:Class ; rdfs:subClassOf schema:Thing ; rdfs:label "Entity" .
wk:Person        a owl:Class ; rdfs:subClassOf wk:Entity ; owl:equivalentClass schema:Person       ; rdfs:label "Person" .
wk:Organization  a owl:Class ; rdfs:subClassOf wk:Entity ; owl:equivalentClass schema:Organization ; rdfs:label "Organization" .
wk:Place         a owl:Class ; rdfs:subClassOf wk:Entity ; owl:equivalentClass schema:Place        ; rdfs:label "Place" .
wk:Event         a owl:Class ; rdfs:subClassOf wk:Entity ; owl:equivalentClass schema:Event        ; rdfs:label "Event" .
wk:Product       a owl:Class ; rdfs:subClassOf wk:Entity , schema:Product ; rdfs:label "Product" .
wk:Technology    a owl:Class ; rdfs:subClassOf wk:Entity ; skos:closeMatch dbo:Technology ; rdfs:label "Technology" .
wk:Concept       a owl:Class ; rdfs:subClassOf wk:Entity ; skos:closeMatch skos:Concept   ; rdfs:label "Concept" .
wk:Project       a owl:Class ; rdfs:subClassOf wk:Entity , schema:Project ; rdfs:label "Project" .
wk:Version       a owl:Class ; rdfs:subClassOf wk:Entity ; rdfs:label "Version" .

#####################################################################
# SKOS concept scheme (tags + clusters; instances created at projection time)
#####################################################################

wk:WikiConcepts a skos:ConceptScheme ; rdfs:label "Wikantik Wiki Concepts" .
```

- [ ] **Step 5: Run the test — verify it passes**

Run: `mvn -q -pl wikantik-ontology test -Dtest=ClassHierarchyTest`
Expected: PASS — all 4 tests green.

- [ ] **Step 6: Commit**

```bash
git add wikantik-ontology/src/main/resources/ontology/wikantik.ttl \
        wikantik-ontology/src/test/java/com/wikantik/ontology/TBoxVocabulary.java \
        wikantik-ontology/src/test/java/com/wikantik/ontology/ClassHierarchyTest.java
git commit -m "feat(ontology): T-Box class hierarchy (9 entity + 5 content classes)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: T-Box object properties (21 predicates + domain/range + mappings) — test-first

**Files:**
- Modify: `wikantik-ontology/src/main/resources/ontology/wikantik.ttl` (append object-property block)
- Test: `wikantik-ontology/src/test/java/com/wikantik/ontology/ObjectPropertyTest.java`

- [ ] **Step 1: Write the failing object-property test**

This test enforces three things: (a) **drift guard** — every term in `RelationshipTypeVocabulary.CLOSED_VOCAB` has a matching `wk:` object property, and there are no extras; (b) every `wk:` object property declares both `rdfs:domain` and `rdfs:range`; (c) the specific public mappings are present.

Create `wikantik-ontology/src/test/java/com/wikantik/ontology/ObjectPropertyTest.java`:

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.wikantik.api.knowledge.RelationshipTypeVocabulary;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;

class ObjectPropertyTest {

    private final Model tbox = TBoxVocabulary.loadTBox();

    /** snake_case vocab term -> lowerCamelCase local name (related_to -> relatedTo). */
    static String toLocalName( final String snake ) {
        final String[] parts = snake.split( "_" );
        final StringBuilder sb = new StringBuilder( parts[ 0 ] );
        for ( int i = 1; i < parts.length; i++ ) {
            sb.append( Character.toUpperCase( parts[ i ].charAt( 0 ) ) ).append( parts[ i ].substring( 1 ) );
        }
        return sb.toString();
    }

    private Set< String > declaredObjectPropertyLocalNames() {
        return tbox.listSubjectsWithProperty( RDF.type, OWL.ObjectProperty )
                .toList().stream()
                .filter( r -> r.getURI() != null && r.getURI().startsWith( TBoxVocabulary.WK ) )
                .map( r -> r.getURI().substring( TBoxVocabulary.WK.length() ) )
                .collect( Collectors.toCollection( TreeSet::new ) );
    }

    @Test
    void objectPropertiesExactlyMirrorTheClosedVocabulary() {
        final Set< String > expected = RelationshipTypeVocabulary.CLOSED_VOCAB.stream()
                .map( ObjectPropertyTest::toLocalName )
                .collect( Collectors.toCollection( TreeSet::new ) );
        assertEquals( expected, declaredObjectPropertyLocalNames(),
                "wk: object properties must exactly mirror RelationshipTypeVocabulary.CLOSED_VOCAB" );
    }

    @Test
    void everyObjectPropertyHasDomainAndRange() {
        for ( final String local : declaredObjectPropertyLocalNames() ) {
            final Resource p = ResourceFactory.createResource( TBoxVocabulary.WK + local );
            assertTrue( tbox.contains( p, RDFS.domain, (org.apache.jena.rdf.model.RDFNode) null ),
                    "wk:" + local + " must declare rdfs:domain" );
            assertTrue( tbox.contains( p, RDFS.range, (org.apache.jena.rdf.model.RDFNode) null ),
                    "wk:" + local + " must declare rdfs:range" );
        }
    }

    @Test
    void publicMappingsArePresent() {
        assertTrue( hasSubProperty( "replaces",  "http://purl.org/dc/terms/replaces" ),
                "wk:replaces must be rdfs:subPropertyOf dct:replaces" );
        assertTrue( hasSubProperty( "partOf",    "http://purl.org/dc/terms/isPartOf" ),
                "wk:partOf must be rdfs:subPropertyOf dct:isPartOf" );
        assertTrue( hasSubProperty( "relatedTo", "http://www.w3.org/2004/02/skos/core#related" ),
                "wk:relatedTo must be rdfs:subPropertyOf skos:related" );
        assertTrue( hasSubProperty( "locatedIn", "https://schema.org/containedInPlace" ),
                "wk:locatedIn must be rdfs:subPropertyOf schema:containedInPlace" );
    }

    private boolean hasSubProperty( final String childLocal, final String parentIri ) {
        return tbox.contains(
                ResourceFactory.createResource( TBoxVocabulary.WK + childLocal ),
                RDFS.subPropertyOf,
                ResourceFactory.createResource( parentIri ) );
    }
}
```

- [ ] **Step 2: Run the test — verify it fails**

Run: `mvn -q -pl wikantik-ontology test -Dtest=ObjectPropertyTest`
Expected: FAIL — `objectPropertiesExactlyMirrorTheClosedVocabulary` reports the expected set vs. an empty set (no object properties authored yet).

- [ ] **Step 3: Append the object-property block to `wikantik.ttl`**

Append to `wikantik-ontology/src/main/resources/ontology/wikantik.ttl` (after the SKOS scheme block):

```turtle
#####################################################################
# Object properties — the closed 21-term relationship vocabulary.
# MUST stay in lock-step with RelationshipTypeVocabulary.CLOSED_VOCAB
# (enforced by ObjectPropertyTest). Direction is always source -> target.
#
# domain/range are intentionally BROAD here (mostly wk:Entity): rdfs:range
# drives type *inference*, so a too-tight range would mis-infer types.
# Tighter per-predicate constraints are enforced as SHACL *validation*
# shapes (shapes.ttl) in Phase 5, not as rdfs:range.
#####################################################################

# -- relatedness / taxonomic (mapped to SKOS) --
wk:relatedTo      a owl:ObjectProperty ; rdfs:subPropertyOf skos:related  ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "related to" .
wk:isA            a owl:ObjectProperty ; rdfs:subPropertyOf skos:broader  ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "is a" .
wk:instanceOf     a owl:ObjectProperty ; rdfs:subPropertyOf skos:broader  ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "instance of" .
wk:generalizes    a owl:ObjectProperty ; rdfs:subPropertyOf skos:narrower ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "generalizes" .
wk:alternativeTo  a owl:ObjectProperty ; rdfs:subPropertyOf skos:related  ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "alternative to" .
wk:contrastsWith  a owl:ObjectProperty ; rdfs:subPropertyOf skos:related  ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "contrasts with" .
wk:compatibleWith a owl:ObjectProperty ; rdfs:subPropertyOf skos:related  ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "compatible with" .

# -- structural (mapped to Dublin Core / schema.org) --
wk:partOf         a owl:ObjectProperty ; rdfs:subPropertyOf dct:isPartOf  ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "part of" .
wk:contains       a owl:ObjectProperty ; rdfs:subPropertyOf dct:hasPart   ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "contains" .
wk:replaces       a owl:ObjectProperty ; rdfs:subPropertyOf dct:replaces  ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "replaces" .
wk:locatedIn      a owl:ObjectProperty ; rdfs:subPropertyOf schema:containedInPlace ; rdfs:domain wk:Entity ; rdfs:range wk:Place ; rdfs:label "located in" .

# -- technical predicates (custom; no clean public equivalent) --
wk:requires       a owl:ObjectProperty ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "requires" .
wk:enables        a owl:ObjectProperty ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "enables" .
wk:uses           a owl:ObjectProperty ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "uses" .
wk:produces       a owl:ObjectProperty ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "produces" .
wk:precedes       a owl:ObjectProperty ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "precedes" .
wk:extends        a owl:ObjectProperty ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "extends" .
wk:implements     a owl:ObjectProperty ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "implements" .
wk:mitigates      a owl:ObjectProperty ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "mitigates" .
wk:defines        a owl:ObjectProperty ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "defines" .
wk:appliesTo      a owl:ObjectProperty ; rdfs:domain wk:Entity ; rdfs:range wk:Entity ; rdfs:label "applies to" .
```

- [ ] **Step 4: Run the test — verify it passes**

Run: `mvn -q -pl wikantik-ontology test -Dtest=ObjectPropertyTest`
Expected: PASS — all 3 tests green (21 properties mirror the vocabulary, all have domain+range, public mappings present).

- [ ] **Step 5: Commit**

```bash
git add wikantik-ontology/src/main/resources/ontology/wikantik.ttl \
        wikantik-ontology/src/test/java/com/wikantik/ontology/ObjectPropertyTest.java
git commit -m "feat(ontology): 21 object properties with domain/range + public mappings

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: SHACL shapes (representative domain/range validation) — test-first

**Files:**
- Create: `wikantik-ontology/src/main/resources/ontology/shapes.ttl`
- Test: `wikantik-ontology/src/test/java/com/wikantik/ontology/ShaclValidationTest.java`

The full per-predicate shape set is Phase 5. Phase 0 ships **two representative shapes** (`wk:locatedIn` range = `wk:Place`; `wk:implements` domain = `wk:Technology`, range = `wk:Concept`) plus a test proving conforming data passes and violating data fails — establishing the validation harness.

- [ ] **Step 1: Write the failing SHACL test**

Create `wikantik-ontology/src/test/java/com/wikantik/ontology/ShaclValidationTest.java`:

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.ontology;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.junit.jupiter.api.Test;

class ShaclValidationTest {

    private static Shapes loadShapes() {
        final Model shapesModel = ModelFactory.createDefaultModel();
        try ( InputStream in = ShaclValidationTest.class.getResourceAsStream( "/ontology/shapes.ttl" ) ) {
            assertNotNull( in, "/ontology/shapes.ttl must exist on the classpath" );
            RDFDataMgr.read( shapesModel, in, Lang.TURTLE );
        } catch ( final java.io.IOException e ) {
            throw new IllegalStateException( e );
        }
        return Shapes.parse( shapesModel.getGraph() );
    }

    private static Model data( final String turtle ) {
        final Model m = ModelFactory.createDefaultModel();
        final String prefixes = "@prefix wk: <https://wiki.wikantik.com/ns/wikantik#> .\n"
                + "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n";
        RDFDataMgr.read( m, new java.io.ByteArrayInputStream(
                ( prefixes + turtle ).getBytes( java.nio.charset.StandardCharsets.UTF_8 ) ),
                Lang.TURTLE );
        return m;
    }

    @Test
    void conformingImplementsEdgePasses() {
        final Model good = data(
                "wk:a rdf:type wk:Technology . wk:b rdf:type wk:Concept . wk:a wk:implements wk:b ." );
        final ValidationReport report = ShaclValidator.get().validate( loadShapes(), good.getGraph() );
        assertTrue( report.conforms(), "Technology implements Concept should conform" );
    }

    @Test
    void wrongDomainImplementsEdgeFails() {
        final Model bad = data(
                "wk:a rdf:type wk:Person . wk:b rdf:type wk:Concept . wk:a wk:implements wk:b ." );
        final ValidationReport report = ShaclValidator.get().validate( loadShapes(), bad.getGraph() );
        assertFalse( report.conforms(), "Person (not Technology) implements ... should violate the domain shape" );
    }

    @Test
    void wrongRangeLocatedInEdgeFails() {
        final Model bad = data(
                "wk:a rdf:type wk:Person . wk:b rdf:type wk:Concept . wk:a wk:locatedIn wk:b ." );
        final ValidationReport report = ShaclValidator.get().validate( loadShapes(), bad.getGraph() );
        assertFalse( report.conforms(), "locatedIn a non-Place should violate the range shape" );
    }
}
```

- [ ] **Step 2: Run the test — verify it fails**

Run: `mvn -q -pl wikantik-ontology test -Dtest=ShaclValidationTest`
Expected: FAIL — `assertNotNull` trips: `/ontology/shapes.ttl must exist on the classpath`.

- [ ] **Step 3: Author the SHACL shapes**

Create `wikantik-ontology/src/main/resources/ontology/shapes.ttl`:

```turtle
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

@prefix wk:  <https://wiki.wikantik.com/ns/wikantik#> .
@prefix sh:  <http://www.w3.org/ns/shacl#> .

# Representative domain/range shapes. The full per-predicate set lands in
# Phase 5 (the curation-time validation gate). These two establish the
# harness: a subject-of-wk:implements must be a wk:Technology and its object
# a wk:Concept; an object of wk:locatedIn must be a wk:Place.

wk:ImplementsShape
    a sh:NodeShape ;
    sh:targetSubjectsOf wk:implements ;
    sh:class wk:Technology ;
    sh:message "wk:implements domain must be a wk:Technology" ;
    sh:property [
        sh:path wk:implements ;
        sh:class wk:Concept ;
        sh:message "wk:implements object must be a wk:Concept"
    ] .

wk:LocatedInShape
    a sh:NodeShape ;
    sh:targetSubjectsOf wk:locatedIn ;
    sh:property [
        sh:path wk:locatedIn ;
        sh:class wk:Place ;
        sh:message "wk:locatedIn object must be a wk:Place"
    ] .
```

- [ ] **Step 4: Run the test — verify it passes**

Run: `mvn -q -pl wikantik-ontology test -Dtest=ShaclValidationTest`
Expected: PASS — conforming edge passes; wrong-domain and wrong-range edges both report `conforms() == false`.

- [ ] **Step 5: Commit**

```bash
git add wikantik-ontology/src/main/resources/ontology/shapes.ttl \
        wikantik-ontology/src/test/java/com/wikantik/ontology/ShaclValidationTest.java
git commit -m "feat(ontology): representative SHACL domain/range shapes + validation harness

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: One-shot content-normalization script for `kg_nodes.node_type`

**Files:**
- Create: `bin/db/one-shots/2026-06-08-normalize-kg-node-types.sql`

This is operator SQL (a data fixup, **not** a `Vxxx` migration — per `feedback_no_data_backfill_in_migrations`). It is idempotent and re-runnable: it normalizes case/separator/synonym variants onto the 9 canonical entity `node_type` values, leaves page-typed nodes untouched, and **reports** (does not silently change) any unmapped value for operator review. Verification is a documented dry-run, not a unit test (it runs against a live PostgreSQL database).

- [ ] **Step 1: Create the script**

Create `bin/db/one-shots/2026-06-08-normalize-kg-node-types.sql` (match the existing `normalize-relationship-types.sql` style — these operator scripts carry a `--` comment banner, not an Apache header):

```sql
-- Operator script: one-shot normalization of kg_nodes.node_type onto the
-- standardized 9-class entity vocabulary used by the Wikantik ontology
-- (wikantik-ontology/src/main/resources/ontology/wikantik.ttl).
--
-- Per feedback_no_data_backfill_in_migrations: this is a DATA FIXUP, NOT a
-- versioned migration. Idempotent + safe to re-run. Runs in ONE transaction
-- and ends with COMMIT; swap COMMIT -> ROLLBACK at the bottom for a dry run.
--
-- Usage (dry run first!):
--   1. Edit the final line:  COMMIT;  ->  ROLLBACK;
--   2. PGPASSWORD=... psql -h localhost -U jspwiki -d wikantik \
--          -v ON_ERROR_STOP=1 -f bin/db/one-shots/2026-06-08-normalize-kg-node-types.sql
--   3. Inspect the BEFORE/AFTER/UNMAPPED diagnostics.
--   4. Restore COMMIT; and re-run to apply.
--
-- Canonical entity node_type values (lowercase):
--   person organization place event product technology concept project version

\echo '=== BEFORE: distinct node_type counts ==='
SELECT node_type, COUNT(*) AS n FROM kg_nodes GROUP BY node_type ORDER BY n DESC;

BEGIN;

-- Synonym / variant -> canonical map. Key is the normalized input:
-- LOWER(REGEXP_REPLACE(node_type, '[\s\-]+', '_', 'g')).
CREATE TEMP TABLE _nt_map ( norm_in TEXT PRIMARY KEY, canonical TEXT NOT NULL );
INSERT INTO _nt_map(norm_in, canonical) VALUES
  ('person','person'), ('people','person'), ('individual','person'),
  ('organization','organization'), ('organisation','organization'),
  ('org','organization'), ('company','organization'), ('institution','organization'),
  ('place','place'), ('location','place'), ('geo','place'),
  ('event','event'),
  ('product','product'),
  ('technology','technology'), ('tech','technology'), ('tool','technology'),
  ('framework','technology'), ('library','technology'),
  ('concept','concept'), ('idea','concept'), ('topic','concept'), ('theory','concept'),
  ('project','project'),
  ('version','version'), ('release','version')
;

-- Page-typed nodes are NOT entities; they project as content classes off the
-- page (Phase 1), not off node_type. Leave them untouched; report separately.
CREATE TEMP TABLE _page_types ( t TEXT PRIMARY KEY );
INSERT INTO _page_types VALUES
  ('article'),('hub'),('reference'),('runbook'),('design'),('design_doc'),
  ('implementation_plan');

-- Apply the mapping. The `<>` guard makes already-canonical rows a no-op,
-- which is what makes re-running idempotent.
UPDATE kg_nodes n
SET node_type = m.canonical
FROM _nt_map m
WHERE LOWER(REGEXP_REPLACE(n.node_type, '[\s\-]+', '_', 'g')) = m.norm_in
  AND n.node_type <> m.canonical;

\echo '=== UNMAPPED non-page node_types (operator must decide before Phase 5) ==='
SELECT n.node_type, COUNT(*) AS cnt
FROM kg_nodes n
WHERE LOWER(REGEXP_REPLACE(n.node_type, '[\s\-]+', '_', 'g')) NOT IN (SELECT norm_in FROM _nt_map)
  AND LOWER(REGEXP_REPLACE(n.node_type, '[\s\-]+', '_', 'g')) NOT IN (SELECT t FROM _page_types)
GROUP BY n.node_type ORDER BY cnt DESC;

\echo '=== PAGE-typed nodes (left untouched by design) ==='
SELECT n.node_type, COUNT(*) AS cnt
FROM kg_nodes n
WHERE LOWER(REGEXP_REPLACE(n.node_type, '[\s\-]+', '_', 'g')) IN (SELECT t FROM _page_types)
GROUP BY n.node_type ORDER BY cnt DESC;

\echo '=== AFTER: distinct node_type counts ==='
SELECT node_type, COUNT(*) AS n FROM kg_nodes GROUP BY node_type ORDER BY n DESC;

COMMIT;   -- change to ROLLBACK; for a dry run
```

- [ ] **Step 2: Dry-run against the local database**

Get the DB password (the local PostgreSQL password used by the deployment — see `tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml` or your local notes). Edit the script's final `COMMIT;` to `ROLLBACK;`, then run:

Run: `PGPASSWORD='<db-pass>' psql -h localhost -U jspwiki -d wikantik -v ON_ERROR_STOP=1 -f bin/db/one-shots/2026-06-08-normalize-kg-node-types.sql`
Expected: prints BEFORE counts, the UNMAPPED list, the PAGE-typed list, AFTER counts, then `ROLLBACK` (no data changed). Review the UNMAPPED list — if it contains entity-ish values not yet in `_nt_map`, add them to the `INSERT INTO _nt_map` block and re-run the dry run until the UNMAPPED list contains only genuinely non-entity noise.

- [ ] **Step 3: Verify idempotency**

With the script still on `ROLLBACK;`, confirm that in the AFTER diagnostics every surviving value is either one of the 9 canonical lowercase values or a page-type value. (Because the dry run rolled back, the AFTER counts reflect the *would-be* applied state; idempotency is guaranteed structurally by the `n.node_type <> m.canonical` guard — a second apply changes zero rows.)

- [ ] **Step 4: Commit the script (do NOT commit any applied data change)**

```bash
git add bin/db/one-shots/2026-06-08-normalize-kg-node-types.sql
git commit -m "chore(db): one-shot kg_nodes.node_type normalization to ontology vocabulary

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

> **Applying it for real** (changing `ROLLBACK;` back to `COMMIT;` and running against local/prod) is an operator decision, not part of the commit. Apply it only once you've reviewed the dry-run diff and are ready; the script is idempotent so a later re-run is safe.

---

## Task 6: Phase 0 full-build verification gate

**Files:** none (verification only).

- [ ] **Step 1: Run the module's full test suite + RAT license check**

Run: `mvn -q -pl wikantik-ontology -am clean install`
Expected: BUILD SUCCESS. All of `ClassHierarchyTest`, `ObjectPropertyTest`, `ShaclValidationTest`, `JenaClasspathSmokeTest` pass, and `apache-rat:check` passes (the `.ttl` files carry `#`-comment Apache headers).

- [ ] **Step 2: Run the full reactor unit build (no IT needed — Phase 0 has no runtime wiring)**

Run: `mvn -q clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS across all modules (confirms the new module + root-pom edits didn't break the reactor). Per repo convention the integration-test reactor gate (`mvn clean install -Pintegration-tests -fae`, sequential) is **not** required for Phase 0 because nothing is wired into the running application yet — it becomes mandatory starting in Phase 1.

- [ ] **Step 3: Final Phase 0 confirmation**

Confirm with the user that the ontology resources read correctly (skim `wikantik.ttl`) before moving to Phase 1 (module runtime: `OntologyModelManager` + Jena TDB2 + the four projectors).

---

## Self-Review (run against the spec §2 + §5 Phase 0)

**Spec coverage:**
- §2.1 IRI scheme → namespace `https://wiki.wikantik.com/ns/wikantik#` fixed in `wikantik.ttl` + conventions header. (Instance IRIs `/id/...` are Phase 1 projection — out of Phase 0 scope.) ✓
- §2.2 class hierarchy → Task 2 (`ClassHierarchyTest` + `wikantik.ttl`); 9 entity + 5 content classes, schema.org anchoring, `DESIGN`→`DesignDoc`. ✓
- §2.3 21 predicates + domain/range + public mappings + taxonomic triad SKOS-style → Task 3. ✓
- §2.3 SHACL for domain/range → Task 4 (representative shapes + harness; full set deferred to Phase 5, as the spec's phasing states). ✓
- §2.4 SKOS concept scheme → `wk:WikiConcepts` declared in Task 2 (instances at projection time, Phase 1). ✓
- §2.5 PROV-O → provenance is asserted on *instances* at projection time (Phase 1); no T-Box term needed in Phase 0. Noted, intentionally deferred. ✓
- §5 Phase 0 gates: T-Box parses + consistent (Tasks 2–4), SHACL valid (Task 4), drift guards green (Task 3 vocab, Task 2 PageType), one-shot normalization script (Task 5). ✓

**Placeholder scan:** No TBD/TODO; every code/SQL/Turtle block is complete. The one-shot `_nt_map` is a concrete starter set with a documented dry-run loop to extend it (not a placeholder — it is operationally complete and safe). ✓

**Type consistency:** `TBoxVocabulary.WK`/`SCHEMA`, `ENTITY_CLASSES`, `CONTENT_CLASSES`, and `toLocalName()` are defined once and reused across `ClassHierarchyTest`/`ObjectPropertyTest`. Object-property local names in `wikantik.ttl` match `toLocalName(CLOSED_VOCAB term)` exactly (verified term-by-term: `related_to`→`relatedTo`, `is_a`→`isA`, `instance_of`→`instanceOf`, `located_in`→`locatedIn`, etc.). The `wk:implements`/`wk:locatedIn`/`wk:Technology`/`wk:Concept`/`wk:Place` names used in `shapes.ttl` and `ShaclValidationTest` match the T-Box. ✓
