# Wiki Ontology — Phase 5 Implementation Plan (SHACL Conformance Toolkit)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development or superpowers:executing-plans. (This session runs it **directly/inline**.) Steps use checkbox (`- [ ]`).

**Goal:** Make ontology conformance observable — a SHACL validator that reports where the materialized graph violates the T-Box's domain/range shapes, surfaced through an admin endpoint so curators (and a future write-time gate) can see drift.

**Architecture:** `OntologyShaclValidator` (wikantik-ontology) loads the bundled `shapes.ttl` once and validates a Jena `Model` (the materialized graph's inference snapshot) via `jena-shacl`, returning structured violations. `GET /admin/ontology/violations` (extending the Phase-1b `AdminOntologyResource`) runs it over the live materialized graph behind `AdminAuthFilter`.

**Tech Stack:** Java 21, JUnit 5, Apache Jena `jena-shacl` (already a dep), Cargo IT.

**Spec:** `docs/superpowers/specs/2026-06-08-wiki-ontology-design.md` (§5 Phase 5 — the SHACL-gate portion).

---

## Scope & rationale

- **Phase 5 = the SHACL conformance AUDIT toolkit**, not a hard write-time rejection gate. Rationale: domain/range shapes can produce false positives against real LLM-extracted data (e.g. page-typed nodes as edge endpoints), so the safe sequence is **audit first** (surface real violations) → tune shapes → only then consider a write-time gate. The audit is also the higher-value maintainability win (item #2 from the Phase 4 future-work).
- **Deferred to a separate follow-up (Phase 5b):** unifying the chunk/page extractors onto the 9-class vocabulary (a behavior change to the LLM pipeline) and the hard write-time SHACL gate on the curation/acceptance path. The audit tool tells us whether/where these are needed first.
- **Shapes:** keep the Phase-0 representative shapes (`shapes.ttl`: `wk:implements` domain/range, `wk:locatedIn` range). The validator works against whatever shapes are present; expanding the shape set is a tuning task informed by what the audit surfaces (noted, not done blindly here).
- **Read-only + admin-gated:** the endpoint is GET, behind `AdminAuthFilter` (curator concern, not public). No new `getManager` (uses the existing `service()` accessor) → ArchUnit untouched.
- **Apache header** on every new `.java`.

## File structure

```
wikantik-ontology/.../ontology/OntologyShaclValidator.java   (new) shapes.ttl + Model -> List<Violation>
wikantik-ontology/.../ontology/OntologyShaclValidatorTest.java (new) conforming + violating
wikantik-rest/.../rest/AdminOntologyResource.java            (edit) GET /admin/ontology/violations
wikantik-it-tests/.../AdminOntologyRebuildIT.java            (edit) add a violations-endpoint assertion
```

---

## Task 1: `OntologyShaclValidator`

**Files:** Create `OntologyShaclValidator.java` + `OntologyShaclValidatorTest.java` (wikantik-ontology).

- [ ] **Step 1: Write the failing test** — validate a conforming graph (empty violations) and a violating graph (a `wk:Person` that `wk:implements` something — violates the `implements` domain shape):

```java
package com.wikantik.ontology;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;

class OntologyShaclValidatorTest {

    private Model data( final String turtle ) {
        final Model m = ModelFactory.createDefaultModel();
        final String prefixes = "@prefix wk: <https://wiki.wikantik.com/ns/wikantik#> .\n"
                + "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n";
        RDFDataMgr.read( m, new java.io.ByteArrayInputStream(
                ( prefixes + turtle ).getBytes( java.nio.charset.StandardCharsets.UTF_8 ) ), Lang.TURTLE );
        return m;
    }

    @Test
    void conformingGraphHasNoViolations() {
        final Model good = data(
                "wk:a rdf:type wk:Technology . wk:b rdf:type wk:Concept . wk:a wk:implements wk:b ." );
        assertTrue( new OntologyShaclValidator().validate( good ).isEmpty() );
    }

    @Test
    void violatingGraphReportsStructuredViolation() {
        final Model bad = data(
                "wk:a rdf:type wk:Person . wk:b rdf:type wk:Concept . wk:a wk:implements wk:b ." );
        final List< OntologyShaclValidator.Violation > v = new OntologyShaclValidator().validate( bad );
        assertFalse( v.isEmpty(), "Person implementing... must violate the implements domain shape" );
        assertTrue( v.get( 0 ).focusNode().contains( "#a" ), "violation cites the offending focus node" );
        assertTrue( v.get( 0 ).message() != null && !v.get( 0 ).message().isBlank(),
                "violation carries a human-readable message" );
    }
}
```

- [ ] **Step 2: Run — verify it fails** (`mvn -o -pl wikantik-ontology test -Dtest=OntologyShaclValidatorTest -Dsurefire.failIfNoSpecifiedTests=false`).

- [ ] **Step 3: Implement `OntologyShaclValidator.java`** (loads `shapes.ttl` once; converts `jena-shacl` report entries to a plain record):

```java
package com.wikantik.ontology;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.shacl.validation.ReportEntry;

/** Validates a materialized ontology graph against the bundled SHACL shapes ({@code shapes.ttl}). */
public final class OntologyShaclValidator {

    /** One SHACL violation, flattened to plain strings for JSON/agent consumption. */
    public record Violation( String focusNode, String path, String message ) {}

    private final Shapes shapes;

    public OntologyShaclValidator() {
        final Model shapesModel = ModelFactory.createDefaultModel();
        try ( InputStream in = OntologyShaclValidator.class.getResourceAsStream( "/ontology/shapes.ttl" ) ) {
            if ( in == null ) {
                throw new IllegalStateException( "/ontology/shapes.ttl not found on classpath" );
            }
            RDFDataMgr.read( shapesModel, in, Lang.TURTLE );
        } catch ( final java.io.IOException e ) {
            throw new IllegalStateException( "failed loading shapes.ttl", e );
        }
        this.shapes = Shapes.parse( shapesModel.getGraph() );
    }

    /** Returns the SHACL violations in {@code data}; empty when it conforms. */
    public List< Violation > validate( final Model data ) {
        final ValidationReport report = ShaclValidator.get().validate( shapes, data.getGraph() );
        if ( report.conforms() ) {
            return List.of();
        }
        final List< Violation > out = new ArrayList<>();
        for ( final ReportEntry e : report.getEntries() ) {
            out.add( new Violation(
                    e.focusNode() == null ? null : e.focusNode().toString(),
                    e.resultPath() == null ? null : e.resultPath().toString(),
                    e.message() ) );
        }
        return out;
    }
}
```
> If `ReportEntry.message()` isn't the exact accessor (verify at compile), use the available message getter — the jar shows `focusNode()/resultPath()/value()`; `message()` is expected but confirm.

- [ ] **Step 4: Run — verify it passes.** Commit.

```bash
git add wikantik-ontology/src/main/java/com/wikantik/ontology/OntologyShaclValidator.java \
        wikantik-ontology/src/test/java/com/wikantik/ontology/OntologyShaclValidatorTest.java
git commit -m "feat(ontology): OntologyShaclValidator (materialized graph -> SHACL violations)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: `GET /admin/ontology/violations`

**Files:** Modify `AdminOntologyResource.java` (wikantik-rest). Install wikantik-ontology first so the validator is on the rest classpath.

- [ ] **Step 1: Add the `violations` action to `doGet`** (alongside the existing `status`):

```java
        final String action = extractPathParam( request );
        if ( "status".equals( action ) ) {
            handleStatus( response );
        } else if ( "violations".equals( action ) ) {
            handleViolations( response );
        } else {
            sendNotFound( response, "Unknown ontology endpoint: " + action );
        }
```

- [ ] **Step 2: Add `handleViolations`** — run the validator over the materialized inference snapshot (so `sh:class` resolves types via subClassOf), return a JSON envelope:

```java
    private static final com.wikantik.ontology.OntologyShaclValidator SHACL_VALIDATOR =
            new com.wikantik.ontology.OntologyShaclValidator();

    private void handleViolations( final HttpServletResponse response ) throws IOException {
        final OntologyRebuildCoordinator svc = service();
        if ( svc == null || svc.modelManager() == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ontology service not available" );
            return;
        }
        final java.util.List< com.wikantik.ontology.OntologyShaclValidator.Violation > violations =
                SHACL_VALIDATOR.validate( svc.modelManager().inferenceSnapshot() );
        sendJsonWithStatus( response, 200, java.util.Map.of(
                "violations", violations,
                "count", violations.size() ) );
    }
```
(Confirm `AdminOntologyResource` already has `service()`, `sendJsonWithStatus`, `sendError`, `sendNotFound` — all from Phase 1b. The `Violation` record serializes cleanly via the resource's Gson.)

- [ ] **Step 3: Compile-check** `mvn -o install -pl wikantik-ontology -DskipTests -q` then `mvn -o -pl wikantik-rest -am test-compile`. Commit.

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminOntologyResource.java
git commit -m "feat(ontology): GET /admin/ontology/violations (SHACL conformance audit)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: IT + gates + docs

**Files:** Modify `AdminOntologyRebuildIT.java` (add a violations-endpoint test); docs.

- [ ] **Step 1: Add an IT test** to `AdminOntologyRebuildIT` — as admin, `GET /admin/ontology/violations` → 200 with a `violations` array + `count` (the clean IT corpus likely has 0; the assertion proves the endpoint is wired, validates, and is admin-gated). Also assert anonymous GET → 403.

```java
    @Test
    void violations_returnsEnvelopeForAdmin() throws Exception {
        try {
            loginAsAdmin();
            final HttpResponse< String > resp = get( "/admin/ontology/violations" );
            assertEquals( 200, resp.statusCode(), "violations endpoint: " + resp.body() );
            final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertTrue( body.has( "violations" ) && body.has( "count" ),
                    "violations envelope: " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }

    @Test
    void violations_anonymousIs403() throws Exception {
        final HttpResponse< String > resp = postAnonymous( "/admin/ontology/violations" );
        // (use an anonymous GET helper; AdminAuthFilter must reject)
        assertEquals( 403, resp.statusCode() );
    }
```
(Adapt to the IT's existing helpers — it has `get`, `loginAsAdmin`, `logoutAdmin`, and a `postAnonymous`; add a `getAnonymous` if needed mirroring `postAnonymous`.)

- [ ] **Step 2: Focused IT** — `mvn -o clean install -pl wikantik-it-tests/wikantik-it-test-rest -am -Pintegration-tests -Dsurefire.skip=true -Dit.test=AdminOntologyRebuildIT -fae` (**`clean`** per the war-overlay gotcha).

- [ ] **Step 3: Full unit reactor** `mvn -o clean install -T 1C -DskipITs`.

- [ ] **Step 4: Full IT reactor** `mvn -o install -Pintegration-tests -Dsurefire.skip=true -fae`; verify failsafe aggregate (Failures: 0).

- [ ] **Step 5: Docs + memory** — add `/admin/ontology/violations` to the admin-surface note; project memory → Phase 5 (SHACL audit) complete; record that the write-time gate + extractor harmonization remain as Phase 5b, informed by what the audit surfaces.

---

## Self-Review (against spec §5 Phase 5, SHACL portion)

- "SHACL gate on proposed KG edges" → delivered as an **audit** (`OntologyShaclValidator` + `/admin/ontology/violations`), with the hard write-time gate explicitly deferred (audit-first is the safe order; documented, not skipped). ✓
- "admin `list_ontology_violations`" → delivered as the admin REST endpoint `/admin/ontology/violations` (reuses the Phase-1b admin surface; equivalent curator-facing capability). ✓
- "SHACL rejects bad edges" → the validator detects domain/range violations (unit test: `wk:Person` implementing → violation). ✓
- "Extractor harmonization onto the 9-class vocabulary" → **deferred to Phase 5b** (independent LLM-pipeline change; the audit surfaces whether it's needed). ✓ (scoped, transparent)

**Placeholders:** validator + tests are complete; the endpoint edit + IT mirror existing Phase-1b/3 structures (confirm `ReportEntry.message()` + the IT's anon-GET helper at edit time).

**Type consistency:** `OntologyShaclValidator.validate(Model) -> List<Violation>` used by the endpoint; `service().modelManager().inferenceSnapshot()` (Phase 3/4 accessor); `Violation(focusNode, path, message)` serialized by the resource Gson.
