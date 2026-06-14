# Phase 2 — Derived Pages — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ingest PDF/office/text documents as first-class "derived pages" — source retained as an attachment, body extracted via Apache Tika, regenerable by reflow — so document corpora flow into search, the Knowledge Graph, the ontology, and the RAG bundle.

**Architecture:** A pure `SourceExtractor` (Tika → markdown) — in a **new `wikantik-ingest` module** that isolates the heavy document-parsing dependencies (PDFBox/POI) — feeds a `DerivedPageIngestionService` (wikantik-main) that stores the source as an attachment and saves a derived page (frontmatter `derived_from` marks it; the page rides every existing rail). Two thin entry points — a REST multipart endpoint and an HTTP-client CLI — sit over that service. A `DerivedReflowService` re-extracts from the retained source, clobbering only the body. All keyed off one small `DerivedPage` contract.

**Tech Stack:** Java 21, Apache Tika 3.3.0 (`tika-parsers-standard-package`), flexmark-html2md-converter 0.64.8 (XHTML→markdown), JUnit 5 + Mockito. New module **`wikantik-ingest`** holds the pure extractor; `com.wikantik.ingest.*`.

**Spec:** `docs/superpowers/specs/2026-06-14-derived-pages-design.md` · **ADR:** `docs/adr/0004-derived-page-body-is-machine-owned-regenerable.md`

**Verified seams (from recon — use these exact APIs):**
- Programmatic save: `PageSaveHelper.saveText(String pageName, String text, SaveOptions options)` — `SaveOptions` carries a `metadata` map; when set it calls `FrontmatterWriter.write(metadata, text)` internally. Page gets `canonical_id` free via `StructuralSpinePageFilter.preSave`.
- Frontmatter: `FrontmatterParser.parse(String) → ParsedPage(Map<String,Object> metadata, String body)`; `FrontmatterWriter.write(Map<String,Object>, String body) → String`.
- Attachments: construct `Wiki.contents().attachment(engine, pageName, fileName)`; `AttachmentManager.storeAttachment(Attachment, InputStream)`; read back `am.getAttachmentInfo(pageName + "/" + fileName)` then `am.getAttachmentStream(att)`.
- REST upload pattern: `AttachmentResource.doPost` — `request.getPart("file")`, `filePart.getSubmittedFileName()`, `checkPagePermission(req,resp,pageName,"upload")`, `sendJson(response, obj)`. Base class `RestServletBase`.
- Admin endpoint pattern: `AdminOntologyResource` — extends `RestServletBase`, dispatch on `extractPathParam(request)`, `sendJsonWithStatus(response, code, Map)`. `/admin/*` is behind `AdminAuthFilter` (web.xml) — `/admin/derived/*` inherits it.
- CLI: `wikantik-extract-cli` — `main` → `Args.parse` → `run`; fat-jar via shade; `bin/<name>.sh` wrapper. (Ours is an HTTP client, not JDBC.)
- Chunker: `ContentChunker.chunk(String pageName, ParsedPage page)` ignores `page.metadata()` today — strategy selection hooks UPSTREAM of this call.
- Tika 3.3.0 is version-managed in the root BOM only; the new `wikantik-ingest` module ADDs `tika-parsers-standard-package`. flexmark-html2md-converter (0.64.8) is NOT BOM-managed — declare it with `<version>${flexmark.version}</version>`. Logging is **Log4j2**. Every new `.java` file needs the Apache license header. **A new Maven module MUST declare `mockito-core` (test scope)** or surefire fails on the inherited `-javaagent` mockito path.

---

### Task 1: `wikantik-ingest` module + `DerivedPage` contract

**Files:**
- Create: `wikantik-ingest/pom.xml` + add `<module>wikantik-ingest</module>` to the root `pom.xml` `<modules>`
- Modify: `wikantik-main/pom.xml` (add a dependency on `wikantik-ingest`)
- Create: `wikantik-main/src/main/java/com/wikantik/derived/DerivedPage.java`
- Test: `wikantik-main/src/test/java/com/wikantik/derived/DerivedPageTest.java`

- [ ] **Step 1: Create the `wikantik-ingest` module.** New `wikantik-ingest/pom.xml` (copy the `<parent>` + groupId/version coordinates from a small sibling like `wikantik-event/pom.xml`) declaring ONLY these dependencies: `org.apache.tika:tika-parsers-standard-package` (no version — managed by the root BOM), `com.vladsch.flexmark:flexmark-html2md-converter` with `<version>${flexmark.version}</version>` (0.64.8, NOT BOM-managed), and test-scope `org.junit.jupiter:junit-jupiter` + **`org.mockito:mockito-core` (MANDATORY — a new module without it fails surefire on the inherited `-javaagent`)**. Add `<module>wikantik-ingest</module>` to the root `pom.xml` `<modules>` BEFORE `wikantik-main`. Then add to `wikantik-main/pom.xml`'s `<dependencies>`: `com.wikantik:wikantik-ingest:${project.version}`. Verify the empty module builds: `mvn -q -pl wikantik-ingest -am install -DskipTests`.

- [ ] **Step 2: Write the failing test** `DerivedPageTest`:
```java
package com.wikantik.derived;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DerivedPageTest {

    @Test
    void isDerivedWhenDerivedFromPresent() {
        assertTrue( DerivedPage.isDerived( Map.of( DerivedPage.DERIVED_FROM, "Doc.pdf" ) ) );
        assertFalse( DerivedPage.isDerived( Map.of( "type", "article" ) ) );
        assertFalse( DerivedPage.isDerived( null ) );
    }

    @Test
    void derivedFromReturnsSourceName() {
        assertEquals( "Doc.pdf",
            DerivedPage.derivedFrom( Map.of( DerivedPage.DERIVED_FROM, "Doc.pdf" ) ).orElse( null ) );
        assertTrue( DerivedPage.derivedFrom( Map.of() ).isEmpty() );
    }

    @Test
    void sha256IsStableHexOfBytes() {
        final String a = DerivedPage.sha256( "hello".getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
        final String b = DerivedPage.sha256( "hello".getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
        assertEquals( a, b );
        assertEquals( 64, a.length() );
        assertNotEquals( a, DerivedPage.sha256( "world".getBytes( java.nio.charset.StandardCharsets.UTF_8 ) ) );
    }

    @Test
    void pageNameSlugifiesFilename() {
        assertEquals( "Research Paper", DerivedPage.pageNameFor( "Research Paper.pdf" ) );
        assertEquals( "my-doc v2", DerivedPage.pageNameFor( "my-doc v2.docx" ) );
    }
}
```

- [ ] **Step 3: Run it — expect FAIL** (`mvn -q -pl wikantik-main -am test -Dtest=DerivedPageTest -Dsurefire.failIfNoSpecifiedTests=false`)

- [ ] **Step 4: Implement `DerivedPage`** (Apache header; the frontmatter contract + helpers):
```java
package com.wikantik.derived;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;

/** The derived-page frontmatter contract (provenance keys) + small shared helpers. */
public final class DerivedPage {
    private DerivedPage() {}

    /** Presence of this key marks a page as derived; value is the source attachment filename. */
    public static final String DERIVED_FROM            = "derived_from";
    public static final String DERIVED_EXTRACTOR       = "derived_extractor";
    public static final String DERIVED_EXTRACTOR_VERSION = "derived_extractor_version";
    public static final String DERIVED_SOURCE_SHA      = "derived_source_sha";

    public static boolean isDerived( final Map< String, Object > metadata ) {
        return metadata != null && metadata.get( DERIVED_FROM ) != null
            && !metadata.get( DERIVED_FROM ).toString().isBlank();
    }

    public static Optional< String > derivedFrom( final Map< String, Object > metadata ) {
        if ( metadata == null ) { return Optional.empty(); }
        final Object v = metadata.get( DERIVED_FROM );
        return v == null || v.toString().isBlank() ? Optional.empty() : Optional.of( v.toString() );
    }

    /** Lowercase hex SHA-256 of the source bytes — idempotency / dedup key. */
    public static String sha256( final byte[] bytes ) {
        try {
            final byte[] d = MessageDigest.getInstance( "SHA-256" ).digest( bytes );
            final StringBuilder sb = new StringBuilder( d.length * 2 );
            for ( final byte b : d ) {
                sb.append( Character.forDigit( ( b >> 4 ) & 0xF, 16 ) ).append( Character.forDigit( b & 0xF, 16 ) );
            }
            return sb.toString();
        } catch ( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 unavailable", e );
        }
    }

    /** Stable page name from the source filename (NOT the extracted title — see spec §A). */
    public static String pageNameFor( final String filename ) {
        final int dot = filename.lastIndexOf( '.' );
        return ( dot > 0 ? filename.substring( 0, dot ) : filename ).trim();
    }
}
```

- [ ] **Step 5: Run the test — expect PASS** (4 tests). **Step 6: Commit** (`feat(derived): DerivedPage contract + Tika/flexmark-html2md deps`).

---

### Task 2: `SourceExtractor` interface + value types

**Files (all in the new `wikantik-ingest` module):**
- Create: `wikantik-ingest/src/main/java/com/wikantik/ingest/ExtractionResult.java`
- Create: `wikantik-ingest/src/main/java/com/wikantik/ingest/ExtractionException.java`
- Create: `wikantik-ingest/src/main/java/com/wikantik/ingest/SourceExtractor.java`

- [ ] **Step 1: Write the three types** (no test — pure declarations; verified by Task 3's test). Apache headers on each.
```java
// ExtractionResult.java
package com.wikantik.ingest;
import java.util.Map;
/** Output of extracting a source document: the markdown body, an optional title, and raw metadata. */
public record ExtractionResult( String markdownBody, String extractedTitle, Map< String, String > metadata ) {
    public boolean isEmpty() { return markdownBody == null || markdownBody.isBlank(); }
}
```
```java
// ExtractionException.java
package com.wikantik.ingest;
/** Thrown when a source document cannot be parsed/extracted. */
public class ExtractionException extends Exception {
    public ExtractionException( final String message, final Throwable cause ) { super( message, cause ); }
    public ExtractionException( final String message ) { super( message ); }
}
```
```java
// SourceExtractor.java
package com.wikantik.ingest;
import java.io.InputStream;
/** Extracts a markdown body from a source document stream. Pure — no wiki coupling. */
public interface SourceExtractor {
    ExtractionResult extract( InputStream source, String contentType, String filename ) throws ExtractionException;
    /** Whether this extractor handles the given MIME type. */
    boolean supports( String contentType );
}
```

- [ ] **Step 2: Compile-check** (`mvn -q -pl wikantik-ingest -am test-compile`). **Step 3: Commit** (`feat(ingest): SourceExtractor interface + value types`).

---

### Task 3: `TikaSourceExtractor` (Tika → markdown)

**Files (in the `wikantik-ingest` module):**
- Create: `wikantik-ingest/src/main/java/com/wikantik/ingest/TikaSourceExtractor.java`
- Test: `wikantik-ingest/src/test/java/com/wikantik/ingest/TikaSourceExtractorTest.java`
- Fixtures: `wikantik-ingest/src/test/resources/derived/sample.txt`, `sample.docx`, `sample.pdf` (commit small real files; generate the docx/pdf with any tool — a one-paragraph doc with one `# Heading` is enough)

- [ ] **Step 1: Write the failing test** (`sample.txt` is deterministic; the binary fixtures assert non-empty + title/structure leniently since exact Tika output varies):
```java
package com.wikantik.ingest;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class TikaSourceExtractorTest {

    private final TikaSourceExtractor ex = new TikaSourceExtractor();

    private InputStream fixture( final String name ) {
        return getClass().getResourceAsStream( "/derived/" + name );
    }

    @Test
    void supportsCommonDocTypes() {
        assertTrue( ex.supports( "application/pdf" ) );
        assertTrue( ex.supports( "text/plain" ) );
        assertTrue( ex.supports( "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ) );
        assertFalse( ex.supports( "image/png" ) );
    }

    @Test
    void extractsPlainText() throws Exception {
        try ( InputStream in = fixture( "sample.txt" ) ) {
            final ExtractionResult r = ex.extract( in, "text/plain", "sample.txt" );
            assertFalse( r.isEmpty() );
            assertTrue( r.markdownBody().toLowerCase().contains( "hello" ) );
        }
    }

    @Test
    void extractsDocxWithBodyText() throws Exception {
        try ( InputStream in = fixture( "sample.docx" ) ) {
            final ExtractionResult r = ex.extract( in, null, "sample.docx" );
            assertFalse( r.isEmpty(), "docx extraction should not be blank" );
        }
    }

    @Test
    void emptyExtractionIsReportedNotBlankSilently() throws Exception {
        // An empty text stream → isEmpty()==true (caller flags it, does not save a blank page).
        try ( InputStream in = new java.io.ByteArrayInputStream( "   ".getBytes() ) ) {
            assertTrue( ex.extract( in, "text/plain", "blank.txt" ).isEmpty() );
        }
    }
}
```

- [ ] **Step 2: Run it — expect FAIL.**

- [ ] **Step 3: Implement `TikaSourceExtractor`** — `AutoDetectParser` → `ToXMLContentHandler` (XHTML) → `FlexmarkHtmlConverter` → markdown; title from Tika `Metadata` (`dc:title`/`title`); `supports()` via Tika `Detector`/a static allowlist of the v1 MIME types (pdf, txt, md, docx, pptx, xlsx). Wrap parse failures in `ExtractionException` (never an empty catch — Log4j2 `LOG.warn`). Set a sane Tika write limit (-1 = unlimited or a large cap) so large PDFs don't truncate. Skeleton:
```java
// key calls:
final AutoDetectParser parser = new AutoDetectParser();
final ToXMLContentHandler handler = new ToXMLContentHandler();   // XHTML
final Metadata md = new Metadata();
if ( filename != null ) { md.set( TikaCoreProperties.RESOURCE_NAME_KEY, filename ); }
try ( source ) { parser.parse( source, handler, md, new ParseContext() ); }
final String xhtml = handler.toString();
final String markdown = FlexmarkHtmlConverter.builder().build().convert( xhtml ).strip();
final String title = md.get( TikaCoreProperties.TITLE );   // may be null
return new ExtractionResult( markdown, title, /* selected md fields */ Map.of() );
```

- [ ] **Step 4: Run the test — expect PASS** (`mvn -q -pl wikantik-ingest test -Dtest=TikaSourceExtractorTest`). **Step 5: Commit** (`feat(ingest): Tika source extractor (XHTML→markdown)`). Stage the fixtures too.

---

### Task 4: `DerivedPageIngestionService`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/derived/IngestOptions.java`, `IngestResult.java`, `DerivedPageIngestionService.java`
- Test: `wikantik-main/src/test/java/com/wikantik/derived/DerivedPageIngestionServiceTest.java`

**Behavior:** `ingest(byte[] source, String filename, String contentType, IngestOptions opts) → IngestResult`:
1. `sha = DerivedPage.sha256(source)`; `pageName = DerivedPage.pageNameFor(filename)` (suffix-disambiguate only on collision with a *different* sha).
2. If the page exists, is derived, and its `derived_source_sha == sha` and `!opts.force()` → return `IngestResult.unchanged(pageName)`.
3. Store source as attachment: `Wiki.contents().attachment(engine, pageName, filename)` + `attachmentManager.storeAttachment(att, new ByteArrayInputStream(source))`.
4. `ExtractionResult er = extractor.extract(new ByteArrayInputStream(source), contentType, filename)`; if `er.isEmpty()` → return `IngestResult.failed(pageName, "empty extraction")` (do NOT save a blank page).
5. Build metadata map: `derived_from=filename`, `derived_extractor="tika"`, `derived_extractor_version=<CURRENT_VERSION const>`, `derived_source_sha=sha`, `type="reference"`, `title=er.extractedTitle()` (when present); preserve any existing body-independent frontmatter on update.
6. `pageSaveHelper.saveText(pageName, er.markdownBody(), SaveOptions.with(metadata, author, changeNote))`.
7. Return `IngestResult.created(...)` or `.updated(...)`.

- [ ] **Step 1: Write the failing test** — mock `AttachmentManager`, `PageManager`/`PageSaveHelper`, and a stub `SourceExtractor`; assert: (a) source stored as attachment; (b) `saveText` called with a body-only string + metadata containing all `derived_*` keys + `derived_from`==filename + sha; (c) re-ingest with same sha → `UNCHANGED`, no `saveText`; (d) re-ingest with changed sha → `UPDATED`, `saveText` called; (e) empty extraction → `FAILED`, no `saveText`. (Use the real `PageSaveHelper`/`SaveOptions` API from recon; if `PageSaveHelper` is hard to mock, inject a thin functional `PageWriter` seam the service calls and the production wiring binds to `PageSaveHelper`.)

- [ ] **Step 2: Run it — expect FAIL. Step 3: Implement** `IngestOptions` (record: `boolean force, String author`), `IngestResult` (record: `enum Status{CREATED,UPDATED,UNCHANGED,FAILED}; String pageName; String message`), and `DerivedPageIngestionService` per the behavior above. A `CURRENT_EXTRACTOR_VERSION` int constant lives on the service (bumped when the extractor improves; drives reflow staleness). **Step 4: PASS. Step 5: Commit** (`feat(derived): ingestion service (store source + extract + save derived page)`).

---

### Task 5: REST ingest endpoint

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/DerivedIngestResource.java`
- Modify: the war `web.xml` (servlet + mapping `/api/ingest`) — find it via `grep -rn "AttachmentResource" **/web.xml` and mirror that registration
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/DerivedIngestResourceTest.java`

- [ ] **Step 1: Write the failing test** — mirror `AttachmentResourceTest` harness; mock the ingestion service (reachable via a subsystem accessor — wire it the same way `DerivedPageIngestionService` is exposed; see note). Assert: a multipart POST with a `file` part + permission → calls `ingest(...)` and returns JSON `{ "page": "...", "status": "created" }`; missing `file` part → 400; lacking create permission → 403.

- [ ] **Step 2: FAIL. Step 3: Implement** `DerivedIngestResource extends RestServletBase`, `doPost` mirroring `AttachmentResource.doPost`: read `request.getPart("file")`, `filePart.getSubmittedFileName()`, `filePart.getContentType()`, bytes via `filePart.getInputStream().readAllBytes()`; permission check (`checkPagePermission(req,resp,pageName,"upload")` or a wiki `createPages` check — match how new-page creation is gated); call the ingestion service; `sendJson(response, Map.of("page",…, "status",…))`. The service is obtained via `getSubsystems()` — expose `DerivedPageIngestionService` on the relevant subsystem accessor the same ArchUnit-safe way Phase 3 exposed `citationRepository()` (no new `WikiEngine.getManager` call site in the servlet). **Step 4: PASS.** **Step 5:** register the servlet in `web.xml` (`/api/ingest`). **Step 6: Commit** (`feat(derived): POST /api/ingest REST endpoint`).

---

### Task 6: Batch CLI (`IngestDocumentsCli`)

**Files:**
- Create: `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/IngestDocumentsCli.java`
- Create: `bin/ingest-documents.sh` (wrapper mirroring `bin/kg-extract.sh`)
- Test: `wikantik-extract-cli/src/test/java/com/wikantik/extractcli/IngestDocumentsCliTest.java`

**This is an HTTP client, not JDBC** (a full page-save needs the engine). It walks a folder and POSTs each supported document (multipart `file`) to `<base-url>/api/ingest` with admin auth, tallying created/updated/unchanged/failed.

- [ ] **Step 1: Write the failing test** — unit-test the pure pieces: `Args.parse` (base URL, dir, token, `--force`); the file walk + extension filter (`.pdf/.txt/.md/.docx/.pptx/.xlsx`); and the per-file result tally. Stub the HTTP call behind a `Function<File,String>` seam so the test asserts the walk/tally without a live server.

- [ ] **Step 2: FAIL. Step 3: Implement** mirroring `BootstrapExtractionCli`'s `main`→`Args`→`run` shape; use `java.net.http.HttpClient` to POST multipart to `/api/ingest`. Add the shade `mainClass` entry (mirror the existing one) and `bin/ingest-documents.sh` (auto-rebuild jar if stale, then `java -cp … com.wikantik.extractcli.IngestDocumentsCli "$@"`). **Step 4: PASS. Step 5: Commit** (`feat(derived): batch ingest CLI (HTTP client over /api/ingest)`).

---

### Task 7: Reflow service + admin endpoint

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/derived/DerivedReflowService.java`
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/AdminDerivedResource.java`
- Modify: war `web.xml` (`/admin/derived/*`)
- Tests: `DerivedReflowServiceTest` (wikantik-main), `AdminDerivedResourceTest` (wikantik-rest)

- [ ] **Step 1: Write the failing `DerivedReflowServiceTest`** — mock managers; a derived page with hand-added `tags`/`verification` frontmatter + a stale `derived_extractor_version`; `reflow(pageName)` → re-reads the source attachment, re-extracts (stub extractor returns new body), saves a new version whose body == new extraction but whose `tags`/`verification` survive and `derived_extractor_version` == current. Also `staleCount()` returns pages with version < current.

- [ ] **Step 2: FAIL. Step 3: Implement `DerivedReflowService`:** `reflow(pageName)` — load page text → `FrontmatterParser.parse` → guard `DerivedPage.isDerived` → read source attachment bytes (`am.getAttachmentInfo(pageName + "/" + derivedFrom)` → `am.getAttachmentStream(att).readAllBytes()`) → `extractor.extract(...)` → rebuild metadata = existing metadata with `derived_extractor_version` bumped + body replaced → `pageSaveHelper.saveText(pageName, newBody, SaveOptions.with(metadata,...))`. `reflowAll()` iterates derived pages. `staleCount()`/`status()` counts `derived_extractor_version < CURRENT`. Never an empty catch — log + count failures.
- [ ] **Step 4: PASS.** **Step 5: Implement `AdminDerivedResource`** mirroring `AdminOntologyResource`: `POST /admin/derived/reflow` (optional `?page=`, else corpus-wide → `reflowAll`), `GET /admin/derived/status` (`{ derivedTotal, staleCount, currentExtractorVersion }`), `sendJsonWithStatus`. Register `/admin/derived/*` in `web.xml` (inherits `AdminAuthFilter`). **Step 6: Test + Commit** (`feat(derived): reflow service + /admin/derived endpoints`).

---

### Task 8: Content-type-aware chunking strategy seam

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ChunkingStrategySelector.java`
- Modify: the service that calls `ContentChunker.chunk(...)` (find it: `grep -rn "\.chunk(" wikantik-main/src/main/java | grep -i chunk`)
- Test: `ChunkingStrategySelectorTest`

- [ ] **Step 1: Write the failing test** — `ChunkingStrategySelector.configFor(ParsedPage)` returns the default `ContentChunker.Config` for a hand-authored page AND for a derived page in v1 (flat-and-tuned — same config), but the selector reads `metadata()` so per-type tuning is a one-line change later. Assert it returns a non-null `Config` and that a derived page (with `derived_from`) currently maps to the same default config (documents the seam without changing behavior).

- [ ] **Step 2: FAIL. Step 3: Implement** the thin selector (reads `DerivedPage.isDerived` / a `source_mime` hint; returns the configured default `Config` for all in v1) and call it at the chunk site, passing its result into `ContentChunker`. **YAGNI:** do not add real per-type configs yet — the spec gates that on harness evidence. **Step 4: PASS. Step 5: Commit** (`feat(derived): chunking strategy-selection seam (flat-and-tuned v1)`).

---

### Task 9: UI affordances (frontend)

**Files:**
- Modify: `wikantik-frontend/src/components/AttachmentPanel.jsx` (add an "Ingest as derived page" action that POSTs to `/api/ingest`)
- Modify: the editor component (add a "machine-owned body — reflow overwrites hand edits; curate in frontmatter/tags/KG" banner shown when the page frontmatter has `derived_from`)
- Test: a vitest for the new action/banner (mirror existing `AttachmentPanel` tests; reuse `src/components/ui/` per the shared-UI rule)

- [ ] **Step 1–5:** TDD per the frontend test pattern; reuse the shared UI layer; the banner is a small presentational component gated on a `derived_from` prop. Commit (`feat(derived): ingest action + machine-owned-body banner`). *(If the editor's frontmatter isn't readily available client-side, derive the banner flag from the page projection that already feeds the reader.)*

---

### Task 10: Integration test + docs + full build + IT reactor

**Files:** an IT (mirror an existing REST IT), `CHANGELOG.md`, `CLAUDE.md`, the spec status line.

- [ ] **Step 1: Wire-level IT** — POST a small fixture PDF to `/api/ingest` → assert the derived page exists, is searchable, and appears in `GET /api/bundle`; then add a `tags:` value by hand, POST `/admin/derived/reflow?page=…`, and assert the body refreshed while the hand-added tag survived. Use `RestSeedHelper.awaitAdminReady`; tolerate structural-index seed lag (poll). If a full Cargo IT is too flaky for the search/bundle assertions, split: a Testcontainers persistence IT for ingest+reflow (mirror `CitationEdgesIT`) plus a lighter Cargo check that the endpoint is reachable.
- [ ] **Step 2: Docs** — `## [Unreleased]` CHANGELOG entry; CLAUDE.md: add derived pages to the architecture map (the `wikantik-main` ingestion subsystem, `/api/ingest`, `/admin/derived/*`, the `derived_from` provenance marker); note the Tika dependency. Update the spec status line.
- [ ] **Step 3: Full unit build** — `mvn clean install -T 1C -DskipITs` (BUILD SUCCESS; fix any fallout — esp. the new Tika transitive deps and any subsystem-accessor ripple).
- [ ] **Step 4: Full IT reactor** — `mvn clean install -Pintegration-tests -fae` (use the `-Dtest=ZZZ_NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false` skip + verify the real `mvn` exit + Reactor Summary, per the known wrapper-exit gotcha). All IT modules green.
- [ ] **Step 5: Commit** (`test(derived): end-to-end IT + docs for derived pages`).

---

## Self-review notes (author)

- **Spec coverage:** A→Task 1; B→Tasks 2–3; C(core)→Task 4, C(REST)→Task 5, C(CLI)→Task 6; D→Task 7; E→Task 8; cross-cutting UI/edit-at-own-risk→Task 9; rails verification + harness→Task 10. The four confirmed decisions are honored: marker = `derived_from` presence (Task 1/4); idempotency = filename-named + update-in-place + sha-dedup (Task 4); manual reflow + staleness count (Task 7); structured-where-Tika-provides fidelity (Task 3). Out-of-scope items (auto-reflow, parent-child chunking, OCR, merge) are not built.
- **Type consistency:** `DerivedPage.*` constants, `ExtractionResult`/`SourceExtractor`, `IngestOptions`/`IngestResult`, `DerivedPageIngestionService`, `DerivedReflowService` referenced consistently across tasks. Page-save via `PageSaveHelper.saveText` + `SaveOptions.metadata`; frontmatter via `FrontmatterWriter.write`.
- **Known-gotcha guards:** new `wikantik-ingest` module holds the Tika/flexmark-html2md deps + MUST declare `mockito-core` test-scope; root `<modules>` + `wikantik-main`→`wikantik-ingest` dep wired in Task 1; `-am … -Dsurefire.failIfNoSpecifiedTests=false` test invocation + the IT-reactor wrapper-exit/Reactor-Summary check (Tasks 3, 10); ArchUnit-safe subsystem accessor for the new service (Task 5); page name from filename not title for reflow stability (Task 1/4/7); Log4j2 + Apache headers + stage-by-name throughout.
- **Confirm-against-live flagged:** flexmark-html2md-converter version management (Task 1); exact `PageSaveHelper`/`SaveOptions` construction + whether to inject a `PageWriter` seam for testability (Task 4); the `ContentChunker.chunk` call site (Task 8); web.xml registration pattern (Tasks 5, 7); the editor frontmatter availability for the banner (Task 9).
