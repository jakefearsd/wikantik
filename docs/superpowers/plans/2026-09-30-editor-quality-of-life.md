# Editor Quality-of-Life Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the most frequent editing friction in the SPA editor — paste/drop uploads, live link completion with heading anchors, preview signals (missing links, plugin/citation chips, code highlighting), version restore, and an outline/backlinks side rail — and repair five defects found in the review.

**Architecture:** Two small server changes (`GET /api/pages` gains `q=` and `names=`; a shared `AttachmentUploadPolicy` enforced at both user-upload entry points), one parity fix (`HeadingSlugs` adopts the page view's slug algorithm, pinned by a shared JSON case table), one dead-code removal (page locking). Everything else is client-side: new pure utils and hooks, thin wiring into `CodeEditor.jsx` / `PageEditor.jsx`, and remark/rehype plugins on the existing react-markdown preview.

**Tech Stack:** Java 25 / Maven / JUnit 5 / Mockito (`TestEngine`, `HttpMockFactory`); React 19, CodeMirror 6 (`@uiw/react-codemirror`), react-markdown 10 + unified 11, vitest 4 + happy-dom + Testing Library; new deps `@codemirror/language-data`, `lowlight`, `mdast-util-to-string`.

**Spec:** `docs/superpowers/specs/2026-09-30-editor-quality-of-life-design.md`

## Global Constraints

- TDD: every test is written and seen failing (for the stated reason) before the code that passes it.
- Never swallow exceptions. Java: at least `LOG.warn( "…{}", …, e.getMessage() )` with context. JS: `console.warn('[area] what failed', err?.message || err)`. No empty `catch {}` in new code.
- REST errors go through `RestServletBase.sendError( response, status, message )`, never `response.sendError`.
- No new config keys. Constants: completion page limit **20**; link-search debounce **150 ms**; missing-link check debounce **500 ms**; outline debounce **300 ms**; `names=` cap **50** (400 above); rail **240 px** open / **28 px** collapsed; rail breakpoint **1100 px**.
- Attachment names follow the server rules: `[A-Za-z0-9._-]`, exactly one `.`, ≤ **40** chars, stem starts alphanumeric, no `-`/`_` immediately before the `.`.
- Pasted clipboard images are named `pasted-YYYYMMDD-HHMMSS.<ext>`; collisions get `-2`, `-3`, … on the stem.
- Heading anchors: the page view's `slugify` + duplicate numbering in `wikantik-frontend/src/utils/headings.js` is canonical; only **h2/h3** carry anchors.
- `q=` and `names=` results are ACL-filtered via the existing `filterViewable`; a page the caller cannot view is treated as nonexistent.
- Upload policy (`wikantik.attachment.{maxsize,allowed,forbidden}`) is enforced only at `AttachmentServlet` and `AttachmentResource`; size → **413**, type → **415**; defaults blank.
- `localStorage` access always in try/catch; the UI must render correctly without it.
- Colours are CSS custom properties with `[data-theme="dark"]` overrides (this app themes via `data-theme`, see `styles/globals.css`).
- PMD complexity gate: no new entries in `build-support/pmd-complexity-baseline.properties`; extract helpers instead.
- Stage files by name (never `git add -A`). Every commit message ends with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg
  ```
- Frontend commands run from `wikantik-frontend/`: `npx vitest run <path>`, `npm run lint`. Java single-class runs: `mvn test -pl <module> -Dtest=<Class> -q` (add `-am -Dsurefire.failIfNoSpecifiedTests=false` only if a dependency module changed and is not installed).

## Rulings (plan-level decisions against the spec)

- **R1** The unsaved-page upload prompt is an inline banner with **Save and upload** / **Cancel**, not a toast action — `ToastProvider` exposes only `success/error/info/dismiss(message)`, no actions.
- **R2** `](` completion is suppressed when the target has a URL scheme (`/^[a-z][a-z0-9+.-]*:/i`, covers `http:`, `https:`, `mailto:`, `cite:`) or starts with `/` — not on a literal `http` prefix, so a page such as `HttpClient` stays completable.
- **R3** The shared upload policy keeps `AttachmentServlet`'s existing admin exemption (callers with admin permissions bypass size/type checks) at both entry points.
- **R4** The server marks missing-page links `class="createpage"` but the SPA never styled that class; this plan adds the style (applies to both the page view and the preview).
- **R5** The REST IT is a new test in the existing `WikiPageFormatAclIT` (anonymous = non-admin caller, restricted fixture already present) rather than a new IT class with copied scaffolding.
- **R6** One highlighting library: `lowlight` (highlight.js grammars, hast output). The preview uses it as a rehype plugin; the page view converts its hast to DOM with a ~15-line helper.

## Review Focus

1. **Rich-text paste** (copying from a word processor puts both text and a rendered image on the clipboard) must paste the text, not upload an image — pinned in Task 6.
2. **One upload in a multi-file drop fails** — the others still upload, only the failed placeholder is removed, and the failure is toasted by name — pinned in Task 6.
3. **Link search or heading fetch fails** (server down, 403, network) — completion returns no options instead of throwing, logs a warning, and a later keystroke retries (no poisoned cache) — pinned in Task 5.
4. **Restoring a version that no longer loads** (404/500 on `?version=N`) — the editor keeps the current content, shows an error, and does not pre-fill the restore change note — pinned in Task 9.
5. **Storage unavailable** (private window / blocked site data: `localStorage` throws) — the rail still renders with its viewport default and toggling still works — pinned in Task 10.

---

## File Structure

**Server**
- Create `wikantik-rest/src/main/java/com/wikantik/rest/PageNameQuery.java` — pure ranking + `names=` parsing.
- Modify `wikantik-rest/src/main/java/com/wikantik/rest/PageListResource.java` — `q=`, `names=`.
- Create `wikantik-main/src/main/java/com/wikantik/attachment/AttachmentUploadPolicy.java` — size/type policy.
- Modify `wikantik-main/src/main/java/com/wikantik/attachment/AttachmentServlet.java` — delegate to policy.
- Modify `wikantik-rest/src/main/java/com/wikantik/rest/AttachmentResource.java` — enforce policy.
- Modify `wikantik-main/src/main/java/com/wikantik/export/HeadingSlugs.java` — canonical slugs.
- Delete page-locking code (Task 4 lists every file).

**Shared fixture**
- Create `wikantik-frontend/src/utils/__fixtures__/heading-slugs.json` — read by vitest and JUnit.

**Frontend (all under `wikantik-frontend/src/`)**
- Modify `utils/headings.js` — export `slugify`, add `headingsFromMarkdown`.
- Modify `api/client.js` — `listPages({ q, names })`.
- Rewrite `utils/wikiLinkComplete.js` — async sources for `[[`, `](`, `#`.
- Modify `utils/attachmentNameValidator.js` — `pastedImageName`, `normalizeAttachmentName`, `uniqueAttachmentName`, `attachmentMarkup`.
- Create `utils/editorFileEvents.js` — clipboard/drag file extraction.
- Create `hooks/useAttachmentUpload.js` — placeholder → upload → markup.
- Create `utils/remarkWikiMarkup.js` — plugin/directive chips + citation badges.
- Create `utils/wikiLinkTargets.js` — link-target classification + collection + `remarkMissingLinks`.
- Create `hooks/useMissingPages.js` — debounced batch existence check.
- Create `utils/codeHighlight.js` + `hooks/useLowlight.js` — highlighting.
- Create `utils/editorCursorStore.js` + `hooks/useEditorCursor.js` + `hooks/useDebouncedValue.js` + `hooks/useRailOpen.js`.
- Create `components/editor/EditorRail.jsx`, `components/editor/EditorStatusBar.jsx`.
- Modify `components/CodeEditor.jsx`, `components/PageEditor.jsx`, `components/PageView.jsx`, `components/ChangeNotesPanel.jsx`, `components/DiffViewer.jsx`, `components/BacklinksPanel.jsx`, `styles/globals.css`, `package.json` (+ lockfile).

---

### Task 1: `GET /api/pages` — `q=` search and `names=` existence check

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/PageNameQuery.java`
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/PageNameQueryTest.java`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/PageListResource.java` (doGet, ~lines 68–128)
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/PageListResourceTest.java`
- Modify: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/WikiPageFormatAclIT.java`

**Interfaces:**
- Produces: `GET /api/pages?q=<text>&limit=20` → `{pages:[{name,…}], total, offset, limit}` ranked exact → prefix → substring (case-insensitive), alphabetical within rank. `GET /api/pages?names=A,B,C` → same shape, only names that exist and are viewable; > 50 names → 400. Both ACL-filtered.

- [ ] **Step 1: Write the failing pure-helper test**

```java
package com.wikantik.rest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageNameQueryTest {

    private static final Function< String, String > ID = Function.identity();

    @Test
    void blankQueryKeepsEverythingAlphabetically() {
        assertEquals( List.of( "Alpha", "Beta", "Gamma" ),
                PageNameQuery.rankBySubstring( List.of( "Gamma", "Alpha", "Beta" ), ID, "  " ) );
        assertEquals( List.of( "Alpha", "Beta" ),
                PageNameQuery.rankBySubstring( List.of( "Beta", "Alpha" ), ID, null ) );
    }

    @Test
    void ranksExactThenPrefixThenSubstringCaseInsensitively() {
        final List< String > names = List.of( "MachineLearning", "Learning", "LearningRate", "DeepLearningNotes", "Unrelated" );
        assertEquals( List.of( "Learning", "LearningRate", "DeepLearningNotes", "MachineLearning" ),
                PageNameQuery.rankBySubstring( names, ID, "learning" ) );
    }

    @Test
    void alphabeticalWithinARank() {
        assertEquals( List.of( "Abc", "AbcOne", "AbcTwo", "XAbc", "YAbc" ),
                PageNameQuery.rankBySubstring( List.of( "YAbc", "AbcTwo", "XAbc", "Abc", "AbcOne" ), ID, "ABC" ) );
    }

    @Test
    void parseNamesTrimsDropsBlanksAndDuplicatesKeepingOrder() {
        assertEquals( List.of( "B", "A", "C" ), PageNameQuery.parseNames( " B, A,,B , C ," ) );
        assertEquals( List.of(), PageNameQuery.parseNames( null ) );
        assertEquals( List.of(), PageNameQuery.parseNames( " , " ) );
    }
}
```

- [ ] **Step 2: Run it — expect compilation failure (`PageNameQuery` not found)**

Run: `mvn test -pl wikantik-rest -Dtest=PageNameQueryTest -q`

- [ ] **Step 3: Implement `PageNameQuery`**

```java
/* Apache license header — copy verbatim from PageListResource.java */
package com.wikantik.rest;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Pure helpers behind {@link PageListResource}'s {@code q=} search and {@code names=} existence check. */
final class PageNameQuery {

    /** Most page names a single {@code names=} request may check. */
    static final int MAX_NAMES = 50;

    private PageNameQuery() {
    }

    /**
     * Case-insensitive substring match on {@code nameOf}, ranked exact match, then prefix match, then
     * any other substring match; alphabetical within a rank. A null/blank {@code q} keeps every item,
     * alphabetically.
     */
    static < T > List< T > rankBySubstring( final Collection< T > items, final Function< T, String > nameOf, final String q ) {
        final Comparator< T > alphabetical = Comparator.comparing( nameOf );
        if ( q == null || q.isBlank() ) {
            return items.stream().sorted( alphabetical ).toList();
        }
        final String needle = q.trim().toLowerCase( Locale.ROOT );
        return items.stream()
                .filter( item -> nameOf.apply( item ).toLowerCase( Locale.ROOT ).contains( needle ) )
                .sorted( Comparator.comparingInt( ( T item ) -> rank( nameOf.apply( item ), needle ) )
                        .thenComparing( alphabetical ) )
                .toList();
    }

    private static int rank( final String name, final String needle ) {
        final String lower = name.toLowerCase( Locale.ROOT );
        if ( lower.equals( needle ) ) {
            return 0;
        }
        return lower.startsWith( needle ) ? 1 : 2;
    }

    /** Splits a comma-separated {@code names=} value: trimmed, blanks dropped, duplicates removed, order kept. */
    static List< String > parseNames( final String raw ) {
        if ( raw == null ) {
            return List.of();
        }
        return Arrays.stream( raw.split( "," ) )
                .map( String::trim )
                .filter( s -> !s.isEmpty() )
                .distinct()
                .toList();
    }
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `mvn test -pl wikantik-rest -Dtest=PageNameQueryTest -q`

- [ ] **Step 5: Write the failing resource tests** — append to `PageListResourceTest` (existing `setUp` saves `RestListAlpha/Beta/Gamma`; requests from `HttpMockFactory` are anonymous). Add a helper beside `doGetList`:

```java
    private String doGetParams( final java.util.Map< String, String > params ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages" );
        params.forEach( ( k, v ) -> Mockito.doReturn( v ).when( request ).getParameter( k ) );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        servlet.doGet( request, response );
        return sw.toString();
    }

    private java.util.List< String > names( final String json ) {
        final JsonArray pages = gson.fromJson( json, JsonObject.class ).getAsJsonArray( "pages" );
        final java.util.List< String > out = new java.util.ArrayList<>();
        pages.forEach( p -> out.add( p.getAsJsonObject().get( "name" ).getAsString() ) );
        return out;
    }

    @Test
    void qMatchesCaseInsensitiveSubstringRanked() throws Exception {
        engine.saveText( "BetaRestList", "Prefix-ranked sibling." );
        try {
            final java.util.List< String > got = names( doGetParams( java.util.Map.of( "q", "restlistbeta" ) ) );
            assertEquals( java.util.List.of( "RestListBeta" ), got );
            final java.util.List< String > ranked = names( doGetParams( java.util.Map.of( "q", "beta" ) ) );
            assertEquals( "BetaRestList", ranked.get( 0 ), "prefix match ranks before substring match" );
            assertTrue( ranked.contains( "RestListBeta" ) );
        } finally {
            engine.deleteQuietly( "BetaRestList" );
        }
    }

    @Test
    void namesReturnsOnlyExistingPages() throws Exception {
        final java.util.List< String > got = names( doGetParams(
                java.util.Map.of( "names", "RestListAlpha,NoSuchPageXyz,RestListGamma" ) ) );
        assertEquals( java.util.List.of( "RestListAlpha", "RestListGamma" ), got );
    }

    @Test
    void namesAboveCapIs400() throws Exception {
        final String many = java.util.stream.IntStream.rangeClosed( 1, 51 )
                .mapToObj( i -> "P" + i ).collect( java.util.stream.Collectors.joining( "," ) );
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages" );
        Mockito.doReturn( many ).when( request ).getParameter( "names" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        servlet.doGet( request, response );
        Mockito.verify( response ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        assertTrue( gson.fromJson( sw.toString(), JsonObject.class ).get( "message" ).getAsString().contains( "50" ) );
    }

    @Test
    void qAndNamesHideRestrictedPagesFromAnonymous() throws Exception {
        engine.saveText( "RestListSecret", "[{ALLOW view Admin}]\nRestricted." );
        try {
            assertFalse( names( doGetParams( java.util.Map.of( "q", "RestListSecret" ) ) ).contains( "RestListSecret" ) );
            assertEquals( java.util.List.of(), names( doGetParams( java.util.Map.of( "names", "RestListSecret" ) ) ) );
        } finally {
            engine.deleteQuietly( "RestListSecret" );
        }
    }
```

- [ ] **Step 6: Run — expect FAIL** (`q` and `names` ignored: wrong lists, no 400)

Run: `mvn test -pl wikantik-rest -Dtest=PageListResourceTest -q`

- [ ] **Step 7: Wire `q=` / `names=` into `PageListResource.doGet`**

Read `names` alongside the other params and validate it with the other 400 checks:

```java
        final String q = request.getParameter( "q" );
        final String namesParam = request.getParameter( "names" );
        final List< String > wantedNames = PageNameQuery.parseNames( namesParam );
        // … existing limit/offset validation …
        if ( wantedNames.size() > PageNameQuery.MAX_NAMES ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "names accepts at most " + PageNameQuery.MAX_NAMES + " page names (got " + wantedNames.size() + ")" );
            return;
        }
```

Replace the `allPages` load + prefix/sort block with a candidate set and the ranked selection (keep the existing `ProviderException` catch and message):

```java
        final Collection< Page > candidates;
        try {
            candidates = namesParam != null ? pagesNamed( pm, wantedNames ) : pm.getAllPages();
        } catch ( final ProviderException e ) {
            LOG.error( "Error listing pages: {}", e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error listing pages: " + e.getMessage() );
            return;
        }

        // Prefix filter, then q= ranking (alphabetical when q is absent), then the ACL filter below.
        List< Page > filtered = PageNameQuery.rankBySubstring(
                candidates.stream().filter( page -> prefix == null || page.getName().startsWith( prefix ) ).toList(),
                Page::getName, q );
```

Add the helper (keeps `doGet` under the complexity gate):

```java
    /** The pages among {@code names} that exist ({@code names=} existence check); unknown names are dropped. */
    private static Collection< Page > pagesNamed( final PageManager pm, final List< String > names ) {
        return names.stream().map( pm::getPage ).filter( java.util.Objects::nonNull ).toList();
    }
```

The existing `filterViewable` step, pagination and response building stay unchanged — they now apply to both modes. Update the class Javadoc parameter list with `q` and `names`.

- [ ] **Step 8: Run — expect PASS**, then the complexity gate for the module

Run: `mvn test -pl wikantik-rest -Dtest=PageListResourceTest+PageNameQueryTest -q`
Run: `mvn pmd:check -Pcomplexity-gate -pl wikantik-rest -q` — expect no new violations. If `doGet` trips a rule, extract the param validation into a helper; never add a baseline entry.

- [ ] **Step 9: Add the REST IT** — in `WikiPageFormatAclIT`, next to `restrictedPageIsNotServedToAnonymousViaFormatMd`, add a test that reuses the class's existing admin-login/put helpers and page constants (read the class first; mirror its request helpers exactly):

```java
    @Test
    void restrictedPageIsNotDisclosedByPageSearchOrNamesCheck() throws Exception {
        // Fixtures: PUBLIC_PAGE and SECRET_PAGE ([{ALLOW view Admin}]) are created by the
        // restrictedPageIsNotServedToAnonymousViaFormatMd flow's helpers — create them here the same
        // way if that test does not run first (use the same admin login + PUT pattern), then log out.
        final HttpResponse< String > q = anonymousGet( "/api/pages?q=FormatAcl&limit=20" );
        assertEquals( 200, q.statusCode(), q.body() );
        assertTrue( q.body().contains( PUBLIC_PAGE ), "public page must be searchable: " + q.body() );
        assertFalse( q.body().contains( SECRET_PAGE ), "restricted page must not appear in q= results: " + q.body() );

        final HttpResponse< String > n = anonymousGet( "/api/pages?names=" + PUBLIC_PAGE + "," + SECRET_PAGE );
        assertEquals( 200, n.statusCode(), n.body() );
        assertTrue( n.body().contains( PUBLIC_PAGE ) );
        assertFalse( n.body().contains( SECRET_PAGE ), "restricted page must not be reported as existing: " + n.body() );
    }
```
`anonymousGet` is whatever the class already uses for its anonymous `format=md` requests (a GET without the admin cookie); if the class only has an inline request, extract it into a private helper and reuse it in both tests. Update the class Javadoc to say it covers read-surface ACL for both `/wiki/{slug}?format=` and `/api/pages` `q=`/`names=`. The IT runs in the Task 11 gate.

- [ ] **Step 10: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/PageNameQuery.java \
        wikantik-rest/src/test/java/com/wikantik/rest/PageNameQueryTest.java \
        wikantik-rest/src/main/java/com/wikantik/rest/PageListResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/PageListResourceTest.java \
        wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/WikiPageFormatAclIT.java
git commit -m "feat(rest): GET /api/pages q= ranked search and names= existence check (ACL-filtered)" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg"
```

---

### Task 2: Shared attachment upload policy (defect 3)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/attachment/AttachmentUploadPolicy.java`
- Create: `wikantik-main/src/test/java/com/wikantik/attachment/AttachmentUploadPolicyTest.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/attachment/AttachmentServlet.java` (fields ~98–102, `init` ~146–159, `setUploadConstraints` ~194–198, `isTypeAllowed` ~200–217, `upload` ~448, `executeUpload` ~533–541)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AttachmentResource.java` (`doPost`, ~lines 262–270)
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/AttachmentResourceTest.java`

**Interfaces:**
- Produces: `new AttachmentUploadPolicy( String[] allowed, String[] forbidden, long maxSize )`, `AttachmentUploadPolicy.fromProperties( Properties )`, `boolean isTypeAllowed( String name )`, `boolean isSizeAllowed( long size )`, `long maxSize()`.
- `POST /api/attachments/{page}` → 413 `"File exceeds maximum size (N bytes)"`, 415 `"Files of type .ext may not be uploaded to this wiki"`; admins exempt.

- [ ] **Step 1: Write the failing policy test** — the cases move the servlet's existing semantics (suffix match, forbidden wins, empty allowed list = allow all):

```java
package com.wikantik.attachment;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentUploadPolicyTest {

    @Test
    void emptyPolicyAllowsEverything() {
        final AttachmentUploadPolicy p = AttachmentUploadPolicy.fromProperties( new Properties() );
        assertTrue( p.isTypeAllowed( "a.exe" ) );
        assertTrue( p.isSizeAllowed( Integer.MAX_VALUE ) );
    }

    @Test
    void blankPropertiesMeanNoRestriction() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.attachment.maxsize", "" );
        props.setProperty( "wikantik.attachment.allowed", "" );
        props.setProperty( "wikantik.attachment.forbidden", "" );
        final AttachmentUploadPolicy p = AttachmentUploadPolicy.fromProperties( props );
        assertTrue( p.isTypeAllowed( "a.exe" ) );
        assertTrue( p.isSizeAllowed( 10_000_000L ) );
    }

    @Test
    void forbiddenSuffixRejectsCaseInsensitively() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.attachment.forbidden", ".exe .BAT" );
        final AttachmentUploadPolicy p = AttachmentUploadPolicy.fromProperties( props );
        assertFalse( p.isTypeAllowed( "setup.EXE" ) );
        assertFalse( p.isTypeAllowed( "run.bat" ) );
        assertTrue( p.isTypeAllowed( "notes.txt" ) );
    }

    @Test
    void allowListRestrictsAndForbiddenWins() {
        final AttachmentUploadPolicy p = new AttachmentUploadPolicy(
                new String[]{ ".png", ".pdf" }, new String[]{ ".pdf" }, 100 );
        assertTrue( p.isTypeAllowed( "a.png" ) );
        assertFalse( p.isTypeAllowed( "a.pdf" ), "forbidden wins over allowed" );
        assertFalse( p.isTypeAllowed( "a.txt" ), "not on the allow list" );
        assertFalse( p.isTypeAllowed( "" ) );
        assertFalse( p.isTypeAllowed( null ) );
    }

    @Test
    void sizeLimitIsInclusive() {
        final AttachmentUploadPolicy p = new AttachmentUploadPolicy( null, null, 100 );
        assertTrue( p.isSizeAllowed( 100 ) );
        assertFalse( p.isSizeAllowed( 101 ) );
        assertEquals( 100, p.maxSize() );
    }
}
```

- [ ] **Step 2: Run — expect compilation failure**

Run: `mvn test -pl wikantik-main -Dtest=AttachmentUploadPolicyTest -q`

- [ ] **Step 3: Implement the policy**

```java
/* Apache license header — copy verbatim from AttachmentServlet.java */
package com.wikantik.attachment;

import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.util.TextUtil;

import java.util.Locale;
import java.util.Properties;

/**
 * The operator's attachment upload policy — {@code wikantik.attachment.maxsize}, {@code .allowed},
 * {@code .forbidden} — applied at every user-upload entry point ({@link AttachmentServlet} and the
 * REST {@code AttachmentResource}). Deliberately not applied inside {@code storeAttachment}, so
 * connector/ingest syncs that store source attachments are not subject to an upload policy.
 * Extension patterns are case-insensitive suffixes; a forbidden match always rejects; an empty
 * allow list allows every non-forbidden name.
 */
public final class AttachmentUploadPolicy {

    private final String[] allowedPatterns;
    private final String[] forbiddenPatterns;
    private final long maxSize;

    public AttachmentUploadPolicy( final String[] allowedPatterns, final String[] forbiddenPatterns, final long maxSize ) {
        this.allowedPatterns = allowedPatterns != null ? lower( allowedPatterns ) : new String[ 0 ];
        this.forbiddenPatterns = forbiddenPatterns != null ? lower( forbiddenPatterns ) : new String[ 0 ];
        this.maxSize = maxSize;
    }

    public static AttachmentUploadPolicy fromProperties( final Properties props ) {
        return new AttachmentUploadPolicy(
                patterns( TextUtil.getStringProperty( props, AttachmentManager.PROP_ALLOWEDEXTENSIONS, null ) ),
                patterns( TextUtil.getStringProperty( props, AttachmentManager.PROP_FORBIDDENEXTENSIONS, null ) ),
                TextUtil.getIntegerProperty( props, AttachmentManager.PROP_MAXSIZE, Integer.MAX_VALUE ) );
    }

    private static String[] patterns( final String value ) {
        if ( value == null || value.isBlank() ) {
            return new String[ 0 ];
        }
        return value.trim().split( "\\s+" );
    }

    private static String[] lower( final String[] in ) {
        final String[] out = new String[ in.length ];
        for ( int i = 0; i < in.length; i++ ) {
            out[ i ] = in[ i ] == null ? "" : in[ i ].toLowerCase( Locale.ROOT );
        }
        return out;
    }

    public long maxSize() {
        return maxSize;
    }

    public boolean isSizeAllowed( final long size ) {
        return size <= maxSize;
    }

    public boolean isTypeAllowed( final String name ) {
        if ( name == null || name.isEmpty() ) {
            return false;
        }
        final String lower = name.toLowerCase( Locale.ROOT );
        for ( final String forbidden : forbiddenPatterns ) {
            if ( !forbidden.isEmpty() && lower.endsWith( forbidden ) ) {
                return false;
            }
        }
        for ( final String allowed : allowedPatterns ) {
            if ( !allowed.isEmpty() && lower.endsWith( allowed ) ) {
                return true;
            }
        }
        return allowedPatterns.length == 0;
    }
}
```

- [ ] **Step 4: Run — expect PASS.** If `blankPropertiesMeanNoRestriction` fails on `maxsize`, check what `TextUtil.getIntegerProperty` returns for `""`: if it throws or returns 0, read the value with `getStringProperty` and fall back to `Integer.MAX_VALUE` when blank, logging nothing (blank is the documented default).

- [ ] **Step 5: Refactor `AttachmentServlet` onto the policy (behaviour-preserving; existing tests are the net).** Replace the three fields with `private AttachmentUploadPolicy uploadPolicy = new AttachmentUploadPolicy( null, null, Integer.MAX_VALUE );`. In `init`, replace the allowed/forbidden/maxSize parsing with `uploadPolicy = AttachmentUploadPolicy.fromProperties( props );`. `setUploadConstraints(...)` becomes `this.uploadPolicy = new AttachmentUploadPolicy( allowedPatterns, forbiddenPatterns, maxSize );`. In `upload`, `upload.setMaxFileSize( uploadPolicy.maxSize() );`. In `executeUpload`, `contentLength > maxSize` → `!uploadPolicy.isSizeAllowed( contentLength )` with message `"File exceeds maximum size (" + uploadPolicy.maxSize() + " bytes)"`, and `!isTypeAllowed( filename )` → `!uploadPolicy.isTypeAllowed( filename )`. Delete the private `isTypeAllowed`.

Run: `mvn test -pl wikantik-main -Dtest='AttachmentServlet*Test*+AttachmentUploadPolicyTest' -q` — expect PASS (including the existing `.exe` forbidden test at `AttachmentServletTest` ~line 511).

- [ ] **Step 6: Write the failing REST tests** — in `AttachmentResourceTest`, using its existing `mockFilePart(...)` and `doUploadAsAuthenticated(...)` helpers (the latter stubs only `checkPagePermission`; the session stays the anonymized mock session, i.e. non-admin). The policy is read per request from `engine.getWikiProperties()`, so set it in the test and remove it in `finally`:

```java
    @Test
    void forbiddenExtensionIs415AndNothingStored() throws Exception {
        engine.getWikiProperties().setProperty( "wikantik.attachment.forbidden", ".exe" );
        try {
            final Part filePart = mockFilePart( "tool.exe", "MZ".getBytes( StandardCharsets.UTF_8 ) );
            final JsonObject obj = gson.fromJson(
                    doUploadAsAuthenticated( "RestAttachPage", filePart, null, "multipart/form-data; boundary=x" ),
                    JsonObject.class );
            assertEquals( 415, obj.get( "status" ).getAsInt(), obj.toString() );
            assertTrue( obj.get( "message" ).getAsString().contains( ".exe" ) );
            assertNull( engine.getManager( AttachmentManager.class ).getAttachmentInfo( "RestAttachPage/tool.exe" ),
                    "a rejected upload must not be stored" );
        } finally {
            engine.getWikiProperties().remove( "wikantik.attachment.forbidden" );
        }
    }

    @Test
    void oversizeUploadIs413() throws Exception {
        engine.getWikiProperties().setProperty( "wikantik.attachment.maxsize", "10" );
        try {
            final Part filePart = mockFilePart( "big.txt", "12345678901".getBytes( StandardCharsets.UTF_8 ) );
            final JsonObject obj = gson.fromJson(
                    doUploadAsAuthenticated( "RestAttachPage", filePart, null, "multipart/form-data; boundary=x" ),
                    JsonObject.class );
            assertEquals( 413, obj.get( "status" ).getAsInt(), obj.toString() );
            assertTrue( obj.get( "message" ).getAsString().contains( "10 bytes" ) );
            assertNull( engine.getManager( AttachmentManager.class ).getAttachmentInfo( "RestAttachPage/big.txt" ) );
        } finally {
            engine.getWikiProperties().remove( "wikantik.attachment.maxsize" );
        }
    }

    @Test
    void uploadAtExactlyTheSizeLimitIsAccepted() throws Exception {
        engine.getWikiProperties().setProperty( "wikantik.attachment.maxsize", "10" );
        try {
            final Part filePart = mockFilePart( "edge.txt", "1234567890".getBytes( StandardCharsets.UTF_8 ) );
            final JsonObject obj = gson.fromJson(
                    doUploadAsAuthenticated( "RestAttachPage", filePart, null, "multipart/form-data; boundary=x" ),
                    JsonObject.class );
            assertTrue( obj.get( "success" ).getAsBoolean(), obj.toString() );
        } finally {
            engine.getWikiProperties().remove( "wikantik.attachment.maxsize" );
        }
    }
```
The existing `testUploadSuccessUsesOriginalFileNameWhenNameFieldAbsent` already proves the default (blank) policy accepts uploads. If `engine.getWikiProperties()` returns a copy rather than the live `Properties` (check `TestEngine`), set the keys via the `Properties` passed to `new TestEngine( props )` in a dedicated engine for these tests instead — do not weaken the assertions.

- [ ] **Step 7: Run — expect FAIL** (REST path ignores the policy; uploads succeed)

Run: `mvn test -pl wikantik-rest -Dtest=AttachmentResourceTest -q`

- [ ] **Step 8: Enforce in `AttachmentResource.doPost`** — after the `Attachment att` is built and before `storeAttachment`:

```java
            if ( rejectedByUploadPolicy( request, response, engine, att, fileName, filePart.getSize() ) ) {
                return;
            }
```

```java
    /**
     * Applies the operator's upload policy ({@link AttachmentUploadPolicy}) — the same one the legacy
     * {@code AttachmentServlet} enforces, including its admin exemption. Returns true when a 413/415
     * has been sent.
     */
    private boolean rejectedByUploadPolicy( final HttpServletRequest request, final HttpServletResponse response,
                                            final Engine engine, final Attachment att, final String fileName,
                                            final long size ) throws IOException {
        if ( Wiki.context().create( engine, request, att ).hasAdminPermissions() ) {
            return false;
        }
        final AttachmentUploadPolicy policy = AttachmentUploadPolicy.fromProperties( engine.getWikiProperties() );
        if ( !policy.isSizeAllowed( size ) ) {
            sendError( response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "File exceeds maximum size (" + policy.maxSize() + " bytes)" );
            return true;
        }
        if ( !policy.isTypeAllowed( fileName ) ) {
            sendError( response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    "Files of type ." + AttachmentNameValidator.getExtension( fileName ) + " may not be uploaded to this wiki" );
            return true;
        }
        return false;
    }
```
Add `import com.wikantik.attachment.AttachmentUploadPolicy;`.

- [ ] **Step 9: Run — expect PASS**; then `mvn pmd:check -Pcomplexity-gate -pl wikantik-main,wikantik-rest -q`.

- [ ] **Step 10: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/attachment/AttachmentUploadPolicy.java \
        wikantik-main/src/test/java/com/wikantik/attachment/AttachmentUploadPolicyTest.java \
        wikantik-main/src/main/java/com/wikantik/attachment/AttachmentServlet.java \
        wikantik-rest/src/main/java/com/wikantik/rest/AttachmentResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AttachmentResourceTest.java
git commit -m "fix(attachments): enforce configured upload size/type policy on the REST upload path (413/415)" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg"
```

---

### Task 3: Heading-slug parity + `headingsFromMarkdown` (defect 6)

**Files:**
- Create: `wikantik-frontend/src/utils/__fixtures__/heading-slugs.json`
- Modify: `wikantik-frontend/src/utils/headings.js`, `wikantik-frontend/src/utils/headings.test.js`
- Modify: `wikantik-frontend/package.json` (+ `package-lock.json`)
- Modify: `wikantik-main/src/main/java/com/wikantik/export/HeadingSlugs.java`, `wikantik-main/src/test/java/com/wikantik/export/HeadingSlugsTest.java`

**Interfaces:**
- Produces (JS): `slugify(text: string): string`; `headingsFromMarkdown(md: string): Array<{ level: number, text: string, line: number, id: string|null }>` — `id` set only for h2/h3, using the page view's slug + duplicate numbering; `line` is 1-based within `md`.
- Produces (Java): `HeadingSlugs.slug( String )` identical to JS `slugify`; `headingsBySlug( String )` numbers duplicate h2/h3 slugs `-2`, `-3`, ….

- [ ] **Step 1: Create the shared case table**

```json
[
  { "heading": "Overview", "slug": "overview" },
  { "heading": "Case Study: The 2026 Iran War Shock", "slug": "case-study-the-2026-iran-war-shock" },
  { "heading": "2. Advanced Portfolio Optimization (HRP, 2025)", "slug": "2-advanced-portfolio-optimization-hrp-2025" },
  { "heading": "A & B", "slug": "a-b" },
  { "heading": "API_Keys & Tokens", "slug": "apikeys-tokens" },
  { "heading": "Über Café", "slug": "ber-caf" },
  { "heading": "multiple   spaces", "slug": "multiple-spaces" },
  { "heading": "a -- b", "slug": "a-b" },
  { "heading": "-leading and trailing-", "slug": "leading-and-trailing" },
  { "heading": "non breaking", "slug": "non-breaking" },
  { "heading": "Tabs\tinside", "slug": "tabs-inside" },
  { "heading": "Émoji 🚀 rocket", "slug": "moji-rocket" }
]
```

- [ ] **Step 2: Failing JS tests** — append to `utils/headings.test.js`:

```js
import cases from './__fixtures__/heading-slugs.json';
import { renderToStaticMarkup } from 'react-dom/server';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import { slugify, headingsFromMarkdown, extractHeadings } from './headings';

describe('slugify — shared case table (also asserted by HeadingSlugsTest.java)', () => {
  it.each(cases)('$heading → $slug', ({ heading, slug }) => {
    expect(slugify(heading)).toBe(slug);
  });
});

describe('headingsFromMarkdown', () => {
  const md = '# Title\n\n## Setup\n\ntext\n\n### Setup\n\n#### Deep\n\n## Using `Foo` and **bold**\n';

  it('returns every heading with level, plain text and 1-based source line', () => {
    expect(headingsFromMarkdown(md).map(({ level, text, line }) => ({ level, text, line }))).toEqual([
      { level: 1, text: 'Title', line: 1 },
      { level: 2, text: 'Setup', line: 3 },
      { level: 3, text: 'Setup', line: 7 },
      { level: 4, text: 'Deep', line: 9 },
      { level: 2, text: 'Using Foo and bold', line: 11 },
    ]);
  });

  it('assigns ids only to h2/h3, numbering duplicates like the page view', () => {
    expect(headingsFromMarkdown(md).map((h) => h.id)).toEqual([null, 'setup', 'setup-2', null, 'using-foo-and-bold']);
  });

  it('matches extractHeadings ids on the rendered HTML', () => {
    const html = renderToStaticMarkup(
      <ReactMarkdown remarkPlugins={[remarkGfm, remarkMath]}>{md}</ReactMarkdown>,
    );
    const fromHtml = extractHeadings(html).map((h) => h.id);
    const fromMd = headingsFromMarkdown(md).filter((h) => h.id).map((h) => h.id);
    expect(fromMd).toEqual(fromHtml);
  });

  it('returns [] for empty input', () => {
    expect(headingsFromMarkdown('')).toEqual([]);
    expect(headingsFromMarkdown(null)).toEqual([]);
  });
});
```
(If `headings.test.js` is `.js`, JSX needs the file renamed to `headings.test.jsx`; do that with `git mv` and keep existing tests.)

- [ ] **Step 3: Run — expect FAIL** (`slugify`/`headingsFromMarkdown` not exported)

Run: `npx vitest run src/utils/headings.test.jsx`

- [ ] **Step 4: Declare the runtime deps actually imported** (they are installed transitively today; runtime code must declare them):

Run (from `wikantik-frontend/`): `npm install --save unified@^11.0.5 remark-parse@^11.0.0 unist-util-visit@^5.1.0 mdast-util-to-string@^4.0.0`
Then remove `unified`, `remark-parse`, `unist-util-visit` from `devDependencies` in `package.json` if npm left duplicates, and run `npm install` once more so the lockfile is consistent.

- [ ] **Step 5: Implement in `utils/headings.js`** — change `function slugify` to `export function slugify`, keep `uniqueId` as is, and add:

```js
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import { toString } from 'mdast-util-to-string';
import { visit } from 'unist-util-visit';

// Same syntax extensions as the editor preview, so heading text is extracted the way it renders.
const markdownParser = unified().use(remarkParse).use(remarkGfm).use(remarkMath);

/**
 * Headings of a markdown body in document order: { level, text, line, id }.
 * `id` is the anchor the page view assigns (h2/h3 only — see extractHeadings — same slugify and
 * duplicate numbering); null for other levels. `line` is the 1-based source line.
 */
export function headingsFromMarkdown(md) {
  if (!md) return [];
  const tree = markdownParser.parse(md);
  const seen = {};
  const out = [];
  visit(tree, 'heading', (node) => {
    const text = toString(node).trim();
    let id = null;
    if (node.depth === 2 || node.depth === 3) {
      const base = slugify(text);
      id = uniqueId(base, seen);
      seen[base] = (seen[base] || 0) + 1;
    }
    out.push({ level: node.depth, text, line: node.position.start.line, id });
  });
  return out;
}
```

- [ ] **Step 6: Run — expect PASS**

Run: `npx vitest run src/utils/headings.test.jsx`

- [ ] **Step 7: Failing Java tests** — in `HeadingSlugsTest`, change the `"a--b"` expectation to `"a-b"` and add:

```java
    @Test
    void slugMatchesSharedFrontendCaseTable() throws Exception {
        // Shared with wikantik-frontend/src/utils/headings.test.jsx — the page view's slugify is canonical.
        final java.nio.file.Path fixture = java.nio.file.Path.of(
                "..", "wikantik-frontend", "src", "utils", "__fixtures__", "heading-slugs.json" );
        final com.google.gson.JsonArray cases = com.google.gson.JsonParser
                .parseString( java.nio.file.Files.readString( fixture ) ).getAsJsonArray();
        assertFalse( cases.isEmpty() );
        for ( final com.google.gson.JsonElement e : cases ) {
            final com.google.gson.JsonObject c = e.getAsJsonObject();
            final String heading = c.get( "heading" ).getAsString();
            assertEquals( c.get( "slug" ).getAsString(), HeadingSlugs.slug( heading ), heading );
        }
    }

    @Test
    void duplicateH2H3HeadingsAreNumberedLikeThePageView() {
        final Map< String, String > m = HeadingSlugs.headingsBySlug( "## Setup\n\n### Setup\n\n## Setup\n" );
        assertEquals( java.util.List.of( "setup", "setup-2", "setup-3" ), java.util.List.copyOf( m.keySet() ) );
    }
```

- [ ] **Step 8: Run — expect FAIL**

Run: `mvn test -pl wikantik-main -Dtest=HeadingSlugsTest -q`

- [ ] **Step 9: Implement** — replace `slug` and `headingsBySlug` in `HeadingSlugs.java` (update the class Javadoc: "Heading slugs identical to the page view's anchors (`wikantik-frontend/src/utils/headings.js` `slugify`), which is what `Page#slug` links in the corpus target."):

```java
    /** JavaScript's {@code \s}: ASCII whitespace plus the Unicode space separators, as in headings.js. */
    private static final Pattern WHITESPACE =
            Pattern.compile( "[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+" );
    private static final Pattern NON_SLUG = Pattern.compile( "[^a-z0-9-]" );
    private static final Pattern HYPHEN_RUN = Pattern.compile( "-+" );
    private static final Pattern EDGE_HYPHEN = Pattern.compile( "^-|-$" );

    public static String slug( final String headingText ) {
        String s = headingText.toLowerCase( Locale.ROOT );
        s = WHITESPACE.matcher( s ).replaceAll( "-" );
        s = NON_SLUG.matcher( s ).replaceAll( "" );
        s = HYPHEN_RUN.matcher( s ).replaceAll( "-" );
        return EDGE_HYPHEN.matcher( s ).replaceAll( "" );
    }

    /**
     * Slug → heading text for every heading. h2/h3 get the page view's ids (duplicates numbered
     * {@code -2}, {@code -3}, …); other levels have no anchor in the view and are recorded under
     * their base slug only when it is free.
     */
    public static Map< String, String > headingsBySlug( final String markdownBody ) {
        final Map< String, String > out = new LinkedHashMap<>();
        final Map< String, Integer > seen = new HashMap<>();
        final Document doc = PARSER.parse( markdownBody );
        for ( final Node n : doc.getDescendants() ) {
            if ( n instanceof Heading h ) {
                final String text = new TextCollectingVisitor().collectAndGetText( h ).trim();
                final String base = slug( text );
                if ( h.getLevel() == 2 || h.getLevel() == 3 ) {
                    final int count = seen.merge( base, 1, Integer::sum ) - 1;
                    out.putIfAbsent( count == 0 ? base : base + "-" + ( count + 1 ), text );
                } else {
                    out.putIfAbsent( base, text );
                }
            }
        }
        return out;
    }
```
Add imports `java.util.HashMap`, `java.util.regex.Pattern`.

- [ ] **Step 10: Run the export suite — expect PASS** (update any converter/export test expectation that encoded the old `a--b`-style slugs; the new values come from the case table)

Run: `mvn test -pl wikantik-main -Dtest='HeadingSlugsTest+ObsidianPageConverterTest+ExportServiceTest' -q`

- [ ] **Step 11: Commit**

```bash
git add wikantik-frontend/src/utils/__fixtures__/heading-slugs.json wikantik-frontend/src/utils/headings.js \
        wikantik-frontend/src/utils/headings.test.jsx wikantik-frontend/package.json wikantik-frontend/package-lock.json \
        wikantik-main/src/main/java/com/wikantik/export/HeadingSlugs.java \
        wikantik-main/src/test/java/com/wikantik/export/HeadingSlugsTest.java
# plus the old headings.test.js path if it was git-mv'd, and any export test you updated
git commit -m "fix(export): heading slugs match the page view's anchors; add headingsFromMarkdown" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg"
```

---

### Task 4: Remove dead page-locking code (defect 5)

**Files (delete):**
- `wikantik-api/src/main/java/com/wikantik/api/pages/PageLock.java`
- `wikantik-api/src/test/java/com/wikantik/api/pages/PageLockTest.java`
- `wikantik-main/src/main/java/com/wikantik/page/subsystem/lifecycle/PageLockService.java`
- `wikantik-main/src/main/java/com/wikantik/page/subsystem/lifecycle/DefaultPageLockService.java`
- `wikantik-main/src/test/java/com/wikantik/page/subsystem/lifecycle/DefaultPageLockServiceTest.java`
- `wikantik-main/src/test/java/com/wikantik/pages/PageLockTest.java`

**Files (modify):**
- `wikantik-api/src/main/java/com/wikantik/api/managers/PageManager.java` — remove `lockPage`, `unlockPage`, `getCurrentLock`, `getActiveLocks` (~lines 160–194) and the `PageLock` import.
- `wikantik-main/src/main/java/com/wikantik/pages/DefaultPageManager.java` — remove the four overrides and the lock-service field/wiring (~lines 152, 194–197).
- `wikantik-main/src/main/java/com/wikantik/page/subsystem/PageSubsystem.java`, `PageSubsystemFactory.java` — remove the lock-service component and its construction (including starting its reaper).
- `wikantik-main/src/main/java/com/wikantik/auth/acl/DefaultAclManager.java` — remove the no-op lock release (~lines 306–311) and the `PageLock` import.
- Test doubles: `wikantik-main/src/test/java/com/wikantik/test/StubPageManager.java`, `wikantik-main/src/test/java/com/wikantik/export/FailingPureTextPageManager.java`, `wikantik-knowledge/src/test/java/com/wikantik/knowledge/testfakes/FakePageManager.java` — remove lock overrides.
- Tests referencing locks: `wikantik-main/src/test/java/com/wikantik/pages/DefaultPageManagerCITest.java`, `wikantik-main/src/test/java/com/wikantik/auth/acl/DefaultAclManagerCITest.java` — delete only the lock-specific test methods/stubbing.
- `build-support/pmd-ruleset.xml` ~line 45 — drop `PageLock` from the comment's list.

This is a deletion task: the "failing test" is the compiler. There is no behaviour to preserve (nothing ever acquires a lock: no REST endpoint, no UI).

- [ ] **Step 1: Confirm deadness before deleting.** Run:

```bash
grep -rn "lockPage\|getCurrentLock\|getActiveLocks\|unlockPage" --include=*.java --include=*.jsp --include=*.js --include=*.jsx . \
  | grep -v /target/ | grep -v node_modules | grep -v "/test/"
```
Expected: only `PageManager.java`, `DefaultPageManager.java`, the lifecycle service, and the `DefaultAclManager` unlock. Any other production caller → STOP and report (not dead).

- [ ] **Step 2: Delete and edit the files listed above.**

- [ ] **Step 3: Verify nothing references the removed API**

```bash
grep -rn "PageLock\|lockPage\|getCurrentLock\|getActiveLocks\|unlockPage" --include=*.java --include=*.xml . | grep -v /target/
```
Expected: no output.

- [ ] **Step 4: Compile and run the affected suites**

Run: `mvn -q install -DskipTests -pl wikantik-api,wikantik-main -am` then
`mvn test -pl wikantik-main -Dtest='DefaultPageManagerCITest+DefaultAclManagerCITest' -q` and `mvn test-compile -pl wikantik-knowledge,wikantik-rest -q`.
Expected: PASS / compiles. Architecture tests run in the Task 11 gate (`DecompositionArchTest` mutates its freeze store even when red — if it fails there, restore the store from git before retrying).

- [ ] **Step 5: Commit** (stage every deleted and modified path by name: `git add` the modified files, `git rm` the deleted ones)

```bash
git commit -m "refactor: remove unused page-locking service, API and reaper thread" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg"
```

---

### Task 5: Live link completion — `[[`, `](`, `#` headings, new-page item (defect 1)

**Files:**
- Modify: `wikantik-frontend/src/api/client.js` (`listPages`, ~line 112)
- Rewrite: `wikantik-frontend/src/utils/wikiLinkComplete.js`, `wikantik-frontend/src/utils/wikiLinkComplete.test.js`
- Modify: `wikantik-frontend/src/components/CodeEditor.jsx` (props + `wikiLinkAutocomplete`)
- Modify: `wikantik-frontend/src/components/PageEditor.jsx` (remove the `listPages({ limit: 1000 })` snapshot ~lines 190–199; add sources)
- Modify: `wikantik-frontend/src/components/PageEditor.test.jsx`

**Interfaces:**
- Consumes: `GET /api/pages?q=` / `names=` (Task 1); `headingsFromMarkdown` (Task 3); `titleToSlug`, `isValidSlug` from `utils/slugUtils.js`.
- Produces: `api.listPages({ prefix, q, names, limit, offset })`; `createWikiLinkSource({ searchPages, getHeadings, getAttachmentNames })` returning an async CodeMirror completion source; `CodeEditor` prop `linkCompletion: { searchPages, getHeadings, getAttachmentNames }` (replaces `getLinkCompletions`).

- [ ] **Step 1: Failing completion tests** — replace `utils/wikiLinkComplete.test.js`:

```js
import { describe, it, expect, vi } from 'vitest';
import { createWikiLinkSource } from './wikiLinkComplete';

// Minimal CodeMirror CompletionContext stand-in.
function ctx(textBefore) {
  return {
    aborted: false,
    matchBefore(re) {
      const m = textBefore.match(re);
      if (!m) return null;
      return { from: textBefore.length - m[0].length, to: textBefore.length, text: m[0] };
    },
  };
}

const HEADINGS = [
  { level: 1, text: 'Title', line: 1, id: null },
  { level: 2, text: 'Setup', line: 3, id: 'setup' },
  { level: 3, text: 'Install Steps', line: 5, id: 'install-steps' },
];

function deps(overrides = {}) {
  return {
    searchPages: vi.fn(async () => ['MachineLearning', 'MachineLearningHub']),
    getHeadings: vi.fn(async () => HEADINGS),
    getAttachmentNames: vi.fn(() => ['diagram.png', 'notes.pdf']),
    ...overrides,
  };
}

describe('createWikiLinkSource', () => {
  it('returns null without a trigger', async () => {
    expect(await createWikiLinkSource(deps())(ctx('plain text'))).toBeNull();
  });

  it('[[ searches pages live and inserts [Name](Name), unfiltered by CodeMirror', async () => {
    const d = deps();
    const res = await createWikiLinkSource(d)(ctx('see [[machine'));
    expect(d.searchPages).toHaveBeenCalledWith('machine');
    expect(res.from).toBe(4);
    expect(res.filter).toBe(false);
    expect(res.options[0]).toMatchObject({ label: 'MachineLearning', apply: '[MachineLearning](MachineLearning)' });
  });

  it('[[ offers a new-page link when nothing matches exactly', async () => {
    const res = await createWikiLinkSource(deps())(ctx('[[retirement planning'));
    const last = res.options[res.options.length - 1];
    expect(last.label).toBe('Link to new page: RetirementPlanning');
    expect(last.apply).toBe('[retirement planning](RetirementPlanning)');
  });

  it('no new-page item when a result matches exactly (case-insensitive)', async () => {
    const res = await createWikiLinkSource(deps())(ctx('[[machinelearning'));
    expect(res.options.some((o) => o.label.startsWith('Link to new page'))).toBe(false);
  });

  it('[[Page# completes h2/h3 headings of the target page with the view anchor', async () => {
    const d = deps();
    const res = await createWikiLinkSource(d)(ctx('[[MachineLearning#inst'));
    expect(d.getHeadings).toHaveBeenCalledWith('MachineLearning');
    expect(res.options).toHaveLength(1);
    expect(res.options[0]).toMatchObject({ label: 'Install Steps', apply: '[Install Steps](MachineLearning#install-steps)' });
  });

  it('](target completes pages and attachments, replacing only the target', async () => {
    const res = await createWikiLinkSource(deps({ searchPages: vi.fn(async () => ['NotesIndex']) }))(ctx('[x](not'));
    expect(res.from).toBe(4);
    expect(res.options.map((o) => o.apply)).toEqual(['NotesIndex', 'notes.pdf', 'Not']);
  });

  it('](# completes headings of the page being edited', async () => {
    const d = deps();
    const res = await createWikiLinkSource(d)(ctx('[x](#se'));
    expect(d.getHeadings).toHaveBeenCalledWith(null);
    expect(res.from).toBe(5);
    expect(res.options[0]).toMatchObject({ label: 'Setup', apply: 'setup' });
  });

  it('](target stays quiet for URLs with a scheme or a leading slash', async () => {
    const d = deps();
    const src = createWikiLinkSource(d);
    expect(await src(ctx('[x](https://exa'))).toBeNull();
    expect(await src(ctx('[x](mailto:me'))).toBeNull();
    expect(await src(ctx('[x](/wiki/Fo'))).toBeNull();
    expect(d.searchPages).not.toHaveBeenCalled();
  });

  it('a page named HttpClient is still completable (no literal http suppression)', async () => {
    const res = await createWikiLinkSource(deps({ searchPages: vi.fn(async () => ['HttpClient']) }))(ctx('[x](Http'));
    expect(res.options[0].apply).toBe('HttpClient');
  });

  it('returns null and warns when the page search fails, then retries on the next call', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const searchPages = vi.fn()
      .mockRejectedValueOnce(new Error('503'))
      .mockResolvedValueOnce(['MachineLearning']);
    const src = createWikiLinkSource(deps({ searchPages }));
    expect(await src(ctx('[[mach'))).toBeNull();
    expect(warn).toHaveBeenCalled();
    expect((await src(ctx('[[mach'))).options[0].label).toBe('MachineLearning');
    warn.mockRestore();
  });

  it('returns null when the heading fetch fails', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const res = await createWikiLinkSource(deps({ getHeadings: vi.fn(async () => { throw new Error('404'); }) }))(ctx('[[Page#x'));
    expect(res).toBeNull();
    warn.mockRestore();
  });

  it('returns null when aborted during the debounce', async () => {
    const d = deps();
    const c = ctx('[[mach');
    const p = createWikiLinkSource(d)(c);
    c.aborted = true;
    expect(await p).toBeNull();
    expect(d.searchPages).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run — expect FAIL**

Run: `npx vitest run src/utils/wikiLinkComplete.test.js`

- [ ] **Step 3: Implement `utils/wikiLinkComplete.js`**

```js
// CodeMirror 6 completion source for internal links. Triggers:
//   [[frag           → live page search; inserts [Name](Name)
//   [[Page#frag      → the target page's h2/h3 headings; inserts [Heading](Page#anchor)
//   ](frag           → pages + this page's attachments; replaces only the link target
//   ](Page#frag      → headings of Page; ](#frag → headings of the page being edited
// Anchors come from headingsFromMarkdown (the page view's ids). Results are ranked server-side, so
// CodeMirror's own filtering is disabled (filter: false keeps our order).
import { titleToSlug, isValidSlug } from './slugUtils';

const MAX_OPTIONS = 20;
const SEARCH_DEBOUNCE_MS = 150;
const WIKI_TRIGGER = /\[\[([^\]\n#]*)(?:#([^\]\n]*))?$/;
const TARGET_TRIGGER = /\]\(([^)\s#]*)(?:#([^)\s]*))?$/;
const HAS_SCHEME_OR_ROOT = /^(?:[a-z][a-z0-9+.-]*:|\/)/i;

const sleep = (ms) => new Promise((resolve) => { setTimeout(resolve, ms); });

async function safely(label, promiseFn) {
  try {
    return await promiseFn();
  } catch (err) {
    console.warn(`[link-complete] ${label} failed`, err?.message || err);
    return null;
  }
}

function newPageOption(fragment, names, applyFor) {
  const text = (fragment || '').trim();
  if (!text) return null;
  const slug = titleToSlug(text);
  if (!isValidSlug(slug)) return null;
  const taken = new Set(names.map((n) => n.toLowerCase()));
  if (taken.has(slug.toLowerCase()) || taken.has(text.toLowerCase())) return null;
  return { label: `Link to new page: ${slug}`, type: 'newpage', apply: applyFor(slug, text) };
}

/**
 * @param {object} deps
 * @param {(q: string) => Promise<string[]>} deps.searchPages  ranked, ACL-filtered page names
 * @param {(page: string|null) => Promise<Array<{text: string, id: string|null}>>} deps.getHeadings
 *        headings of `page`, or of the page being edited when null
 * @param {() => string[]} [deps.getAttachmentNames]  attachment file names of the page being edited
 */
export function createWikiLinkSource({ searchPages, getHeadings, getAttachmentNames = () => [] }) {
  async function headingOptions(page, fragment, applyFor) {
    const headings = await safely('heading lookup', () => getHeadings(page || null));
    if (!headings) return null;
    const frag = fragment.toLowerCase();
    return headings
      .filter((h) => h.id && h.text.toLowerCase().includes(frag))
      .slice(0, MAX_OPTIONS)
      .map((h) => ({ label: h.text, detail: `#${h.id}`, type: 'heading', apply: applyFor(h) }));
  }

  async function pageNames(fragment, context) {
    await sleep(SEARCH_DEBOUNCE_MS);
    if (context.aborted) return null;
    const names = await safely('page search', () => searchPages(fragment));
    return context.aborted ? null : names;
  }

  const result = (from, options) => (options && options.length ? { from, options, filter: false } : null);

  async function completeWiki(match, context) {
    const [, page, heading] = WIKI_TRIGGER.exec(match.text);
    if (heading !== undefined) {
      const options = await headingOptions(page, heading, (h) => `[${h.text}](${page}#${h.id})`);
      return context.aborted ? null : result(match.from, options);
    }
    const names = await pageNames(page, context);
    if (!names) return null;
    const options = names.slice(0, MAX_OPTIONS).map((name) => ({ label: name, type: 'wikilink', apply: `[${name}](${name})` }));
    const created = newPageOption(page, names, (slug, text) => `[${text}](${slug})`);
    return result(match.from, created ? [...options, created] : options);
  }

  async function completeTarget(match, context) {
    const [, target, heading] = TARGET_TRIGGER.exec(match.text);
    if (HAS_SCHEME_OR_ROOT.test(target)) return null;
    const from = match.from + 2; // replace the link target only, never the "]("
    if (heading !== undefined) {
      const options = await headingOptions(target, heading, (h) => (target ? `${target}#${h.id}` : h.id));
      return context.aborted ? null : result(target ? from : from + 1, options);
    }
    const names = await pageNames(target, context);
    if (!names) return null;
    const frag = target.toLowerCase();
    const pages = names.slice(0, MAX_OPTIONS).map((name) => ({ label: name, type: 'wikilink', apply: name }));
    const attachments = getAttachmentNames()
      .filter((n) => n.toLowerCase().includes(frag))
      .map((n) => ({ label: n, type: 'attachment', apply: n }));
    const created = newPageOption(target, names, (slug) => slug);
    return result(from, [...pages, ...attachments, ...(created ? [created] : [])]);
  }

  return async (context) => {
    const wiki = context.matchBefore(WIKI_TRIGGER);
    if (wiki) return completeWiki(wiki, context);
    const target = context.matchBefore(TARGET_TRIGGER);
    if (target) return completeTarget(target, context);
    return null;
  };
}
```
Check the `](#se` case: for an empty target, the heading options replace from after the `#` (`from + 1`), inserting just the anchor id — matching the test's `from: 5` and `apply: 'setup'`.

- [ ] **Step 4: Run — expect PASS**

Run: `npx vitest run src/utils/wikiLinkComplete.test.js`

- [ ] **Step 5: `api.listPages` gains `q` and `names`**

```js
  listPages: ({ prefix, q, names, limit = 100, offset = 0 } = {}) => {
    const params = new URLSearchParams({ limit, offset });
    if (prefix) params.set('prefix', prefix);
    if (q) params.set('q', q);
    if (names && names.length) params.set('names', names.join(','));
    return request(`/api/pages?${params}`);
  },
```

- [ ] **Step 6: Wire `CodeEditor`** — replace the `getLinkCompletions` prop with `linkCompletion` (held in a ref so the extension is built once), and update the prop docs:

```js
  const linkCompletionRef = useRef(linkCompletion);
  linkCompletionRef.current = linkCompletion;
  // …
  const wikiLinkAutocomplete = useMemo(
    () => autocompletion({
      override: [createWikiLinkSource({
        searchPages: (q) => linkCompletionRef.current?.searchPages(q) ?? Promise.resolve([]),
        getHeadings: (page) => linkCompletionRef.current?.getHeadings(page) ?? Promise.resolve([]),
        getAttachmentNames: () => linkCompletionRef.current?.getAttachmentNames?.() ?? [],
      })],
    }),
    [],
  );
```

- [ ] **Step 7: Failing PageEditor test** — in `PageEditor.test.jsx` add a `describe('link completion wiring')` that renders the editor for an existing page and asserts `api.listPages` is **not** called with `{ limit: 1000 }` on mount (snapshot removed): `expect(api.listPages).not.toHaveBeenCalledWith(expect.objectContaining({ limit: 1000 }))`. Run it: expect FAIL.

- [ ] **Step 8: Replace the snapshot in `PageEditor.jsx`** — delete the `pageNamesRef` effect and `getPageNames`; add (import `headingsFromMarkdown` from `../utils/headings`):

```js
  // Live link completion: server-ranked, ACL-filtered page search; heading lists per target page are
  // fetched once per editing session (the page being edited uses the live draft).
  const headingCacheRef = useRef(new Map());
  const attachmentNamesRef = useRef([]);
  useEffect(() => {
    attachmentNamesRef.current = (attachments.list || []).map((a) => a.fileName);
  });
  const linkCompletion = useMemo(() => ({
    searchPages: (q) => api.listPages({ q, limit: 20 }).then((d) => (d.pages || []).map((p) => p.name)),
    getHeadings: (page) => {
      if (!page) return Promise.resolve(headingsFromMarkdown(bodyRef.current));
      const cache = headingCacheRef.current;
      if (!cache.has(page)) {
        cache.set(page, api.getPage(page)
          .then((p) => headingsFromMarkdown(p.content || ''))
          .catch((err) => { cache.delete(page); throw err; }));
      }
      return cache.get(page);
    },
    getAttachmentNames: () => attachmentNamesRef.current,
  }), []);
```
(A failed fetch is evicted and rethrown so the completion source logs it and the next keystroke retries — Review Focus #3.) Pass `linkCompletion={linkCompletion}` to `<CodeEditor>` instead of `getLinkCompletions`.

- [ ] **Step 9: Run — expect PASS**, plus lint

Run: `npx vitest run src/components/PageEditor.test.jsx src/components/CodeEditor.test.jsx src/utils/wikiLinkComplete.test.js && npm run lint`

- [ ] **Step 10: Commit**

```bash
git add wikantik-frontend/src/api/client.js wikantik-frontend/src/utils/wikiLinkComplete.js \
        wikantik-frontend/src/utils/wikiLinkComplete.test.js wikantik-frontend/src/components/CodeEditor.jsx \
        wikantik-frontend/src/components/PageEditor.jsx wikantik-frontend/src/components/PageEditor.test.jsx
git commit -m "feat(editor): live link completion with heading anchors, ](-target and new-page items" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg"
```

---

### Task 6: Paste and drop uploads (defect 2)

**Files:**
- Modify: `wikantik-frontend/src/utils/attachmentNameValidator.js`, `…/attachmentNameValidator.test.js`
- Create: `wikantik-frontend/src/utils/editorFileEvents.js`, `…/editorFileEvents.test.js`
- Create: `wikantik-frontend/src/hooks/useAttachmentUpload.js`, `…/useAttachmentUpload.test.js`
- Modify: `wikantik-frontend/src/components/CodeEditor.jsx` (paste/drop DOM handlers, `onFiles` prop)
- Modify: `wikantik-frontend/src/components/PageEditor.jsx` (drag hint gating, `onFiles`, unsaved-page banner, `saveContent({ stay })`)
- Modify: `wikantik-frontend/src/components/PageEditor.test.jsx`

**Interfaces:**
- Produces: `pastedImageName(mime, now?)`, `normalizeAttachmentName(original) → string|null`, `uniqueAttachmentName(name, existing[]) → string`, `attachmentMarkup(name, isImage) → string`; `filesFromPaste(clipboardData) → File[]`, `filesFromDrop(dataTransfer) → File[]`, `dragCarriesFiles(dataTransfer) → boolean`; `useAttachmentUpload({ existingNames, upload, updateBody, toast }) → (files, pos, { pasted }) => Promise<void>`; `CodeEditor` prop `onFiles(files, pos, { pasted })`.

- [ ] **Step 1: Failing name tests** — append to `attachmentNameValidator.test.js`:

```js
import { pastedImageName, normalizeAttachmentName, uniqueAttachmentName, attachmentMarkup, isValidAttachmentName } from './attachmentNameValidator';

describe('pastedImageName', () => {
  const at = new Date(2026, 8, 30, 14, 12, 3);
  it('stamps local time and maps the MIME type', () => {
    expect(pastedImageName('image/png', at)).toBe('pasted-20260930-141203.png');
    expect(pastedImageName('image/jpeg', at)).toBe('pasted-20260930-141203.jpg');
    expect(pastedImageName('image/x-unknown', at)).toBe('pasted-20260930-141203.png');
  });
});

describe('normalizeAttachmentName', () => {
  it.each([
    ['Screen Shot 2026-09-30 at 14.12.03.png', 'Screen-Shot-2026-09-30-at-14-12-03.png'],
    ['report.final.v2.pdf', 'report-final-v2.pdf'],
    ['__weird__name__.txt', 'weird__name.txt'],
    ['café menu.jpg', 'caf-menu.jpg'],
    ['a'.repeat(60) + '.jpeg', 'a'.repeat(35) + '.jpeg'],
  ])('%s → %s', (input, expected) => {
    expect(normalizeAttachmentName(input)).toBe(expected);
    expect(isValidAttachmentName(expected)).toBe(true);
  });
  it('falls back to "file" when the stem is empty', () => {
    expect(normalizeAttachmentName('###.png')).toBe('file.png');
  });
  it('returns null without a usable extension', () => {
    expect(normalizeAttachmentName('README')).toBeNull();
    expect(normalizeAttachmentName('.bashrc')).toBeNull();
    expect(normalizeAttachmentName('x.')).toBeNull();
  });
});

describe('uniqueAttachmentName', () => {
  it('suffixes the stem on a case-insensitive collision', () => {
    expect(uniqueAttachmentName('a.png', ['A.png', 'a-2.png'])).toBe('a-3.png');
    expect(uniqueAttachmentName('b.png', ['a.png'])).toBe('b.png');
  });
  it('stays within 40 chars', () => {
    const long = 'x'.repeat(36) + '.png';
    const out = uniqueAttachmentName(long, [long]);
    expect(out.length).toBeLessThanOrEqual(40);
    expect(isValidAttachmentName(out)).toBe(true);
    expect(out.endsWith('-2.png')).toBe(true);
  });
});

describe('attachmentMarkup', () => {
  it('matches the attachment-row drag format', () => {
    expect(attachmentMarkup('diagram.png', true)).toBe('![diagram](diagram.png)');
    expect(attachmentMarkup('notes.pdf', false)).toBe('[notes](notes.pdf)');
  });
});
```

- [ ] **Step 2: Run — expect FAIL**

Run: `npx vitest run src/utils/attachmentNameValidator.test.js`

- [ ] **Step 3: Implement** — append to `utils/attachmentNameValidator.js`:

```js
const MIME_EXTENSIONS = {
  'image/png': 'png', 'image/jpeg': 'jpg', 'image/gif': 'gif',
  'image/webp': 'webp', 'image/svg+xml': 'svg', 'image/bmp': 'bmp',
};
const pad2 = (n) => String(n).padStart(2, '0');

/** Name for a pasted clipboard image: pasted-YYYYMMDD-HHMMSS.<ext> (local time; png when the MIME type is unknown). */
export function pastedImageName(mimeType, now = new Date()) {
  const ext = MIME_EXTENSIONS[mimeType] || 'png';
  const date = `${now.getFullYear()}${pad2(now.getMonth() + 1)}${pad2(now.getDate())}`;
  const time = `${pad2(now.getHours())}${pad2(now.getMinutes())}${pad2(now.getSeconds())}`;
  return `pasted-${date}-${time}.${ext}`;
}

function cleanStem(stem) {
  return stem
    .replace(/[\s.]+/g, '-')
    .replace(/[^a-zA-Z0-9_-]/g, '')
    .replace(/-+/g, '-')
    .replace(/^[-_]+|[-_]+$/g, '');
}

/**
 * Normalise a file name to the server's attachment-name rules (isValidAttachmentName):
 * spaces and inner periods become '-', other disallowed characters are dropped, the stem is trimmed
 * so the whole name is ≤ 40 chars. Returns null when there is no usable extension.
 */
export function normalizeAttachmentName(original) {
  const raw = String(original || '');
  const dot = raw.lastIndexOf('.');
  if (dot <= 0 || dot === raw.length - 1) return null;
  const ext = raw.slice(dot + 1).replace(/[^a-zA-Z0-9]/g, '');
  if (!ext) return null;
  const room = MAX_LENGTH - 1 - ext.length;
  if (room < 1) return null;
  const stem = cleanStem(cleanStem(raw.slice(0, dot)).slice(0, room)) || 'file';
  const name = `${stem}.${ext}`;
  return isValidAttachmentName(name) ? name : null;
}

/** `name`, or its stem suffixed -2, -3, … until it does not collide (case-insensitively) with `existing`. */
export function uniqueAttachmentName(name, existing = []) {
  const taken = new Set(existing.map((n) => n.toLowerCase()));
  if (!taken.has(name.toLowerCase())) return name;
  const dot = name.lastIndexOf('.');
  const stem = name.slice(0, dot);
  const ext = name.slice(dot + 1);
  for (let n = 2; ; n++) {
    const suffix = `-${n}`;
    const room = MAX_LENGTH - 1 - ext.length - suffix.length;
    const candidate = `${cleanStem(stem.slice(0, room))}${suffix}.${ext}`;
    if (!taken.has(candidate.toLowerCase())) return candidate;
  }
}

/** The markup an attachment-row drag produces: ![stem](name) for images, [stem](name) otherwise. */
export function attachmentMarkup(name, isImage) {
  const stem = name.slice(0, name.lastIndexOf('.'));
  return isImage ? `![${stem}](${name})` : `[${stem}](${name})`;
}
```
(`cleanStem` is applied twice around the truncation so a cut that lands on `-`/`_` is re-trimmed. `'__weird__name__'` → `weird__name` because only leading/trailing `-`/`_` runs are stripped.)

- [ ] **Step 4: Run — expect PASS.** Adjust an expected string only if it violates the stated rules — never the rules.

- [ ] **Step 5: Failing file-event tests** — `utils/editorFileEvents.test.js`:

```js
import { describe, it, expect } from 'vitest';
import { filesFromPaste, filesFromDrop, dragCarriesFiles } from './editorFileEvents';

const png = new File(['x'], 'image.png', { type: 'image/png' });
const clip = (text, files) => ({ getData: (t) => (t === 'text/plain' ? text : ''), files });

describe('filesFromPaste', () => {
  it('returns clipboard files when there is no text', () => {
    expect(filesFromPaste(clip('', [png]))).toEqual([png]);
  });
  it('prefers text: rich-text copies also carry a rendered image (Review Focus #1)', () => {
    expect(filesFromPaste(clip('Quarterly summary', [png]))).toEqual([]);
  });
  it('handles a missing clipboard', () => {
    expect(filesFromPaste(null)).toEqual([]);
  });
});

describe('filesFromDrop / dragCarriesFiles', () => {
  it('extracts dropped files', () => {
    expect(filesFromDrop({ files: [png] })).toEqual([png]);
    expect(filesFromDrop(null)).toEqual([]);
  });
  it('detects file drags only', () => {
    expect(dragCarriesFiles({ types: ['Files'] })).toBe(true);
    expect(dragCarriesFiles({ types: ['text/plain'] })).toBe(false);
    expect(dragCarriesFiles(undefined)).toBe(false);
  });
});
```

- [ ] **Step 6: Run — expect FAIL; implement `utils/editorFileEvents.js`; run — expect PASS**

```js
/**
 * Files carried by a paste — or none when the clipboard also has text: copying from a word processor
 * puts both the text and a rendered image of it on the clipboard, and the text is what was meant.
 */
export function filesFromPaste(clipboardData) {
  if (!clipboardData) return [];
  if (clipboardData.getData && clipboardData.getData('text/plain')) return [];
  return Array.from(clipboardData.files || []);
}

/** Files carried by a drop (OS file drags). */
export function filesFromDrop(dataTransfer) {
  if (!dataTransfer) return [];
  return Array.from(dataTransfer.files || []);
}

/** True when a drag carries OS files (not text or an attachment-row drag). */
export function dragCarriesFiles(dataTransfer) {
  return !!dataTransfer && Array.from(dataTransfer.types || []).includes('Files');
}
```

- [ ] **Step 7: Failing hook tests** — `hooks/useAttachmentUpload.test.js`:

```js
import { describe, it, expect, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useAttachmentUpload } from './useAttachmentUpload';

function harness({ upload, existingNames = [] } = {}) {
  let body = 'Hello world';
  const updateBody = (fn) => { body = fn(body); };
  const toast = { error: vi.fn() };
  const up = upload || vi.fn(async () => ({ success: true }));
  const { result } = renderHook(() => useAttachmentUpload({ existingNames, upload: up, updateBody, toast }));
  return { run: (files, pos, opts) => act(() => result.current(files, pos, opts)), body: () => body, toast, upload: up };
}

const img = (name) => new File(['x'], name, { type: 'image/png' });
const doc = (name) => new File(['x'], name, { type: 'application/pdf' });

describe('useAttachmentUpload', () => {
  it('names pasted images by timestamp and replaces the placeholder with image markup', async () => {
    const h = harness();
    await h.run([img('image.png')], 5, { pasted: true });
    const [[, name]] = h.upload.mock.calls;
    expect(name).toMatch(/^pasted-\d{8}-\d{6}\.png$/);
    expect(h.body()).toBe(`Hello![${name.replace('.png', '')}](${name}) world`);
  });

  it('keeps and normalises dropped file names; non-images get link markup', async () => {
    const h = harness();
    await h.run([doc('Q3 report.pdf')], 0, { pasted: false });
    expect(h.upload.mock.calls[0][1]).toBe('Q3-report.pdf');
    expect(h.body()).toBe('[Q3-report](Q3-report.pdf)Hello world');
  });

  it('avoids collisions with existing attachments and within the batch', async () => {
    const h = harness({ existingNames: ['shot.png'] });
    await h.run([img('shot.png'), img('shot.png')], 0, { pasted: false });
    expect(h.upload.mock.calls.map((c) => c[1])).toEqual(['shot-2.png', 'shot-3.png']);
  });

  it('a failed upload removes only its placeholder and toasts; later files still upload (Review Focus #2)', async () => {
    const upload = vi.fn()
      .mockResolvedValueOnce({})
      .mockRejectedValueOnce(new Error('Files of type .exe may not be uploaded'))
      .mockResolvedValueOnce({});
    const h = harness({ upload });
    await h.run([doc('a.pdf'), doc('b.exe'), doc('c.pdf')], 0, { pasted: false });
    expect(upload).toHaveBeenCalledTimes(3);
    expect(h.body()).toContain('[a](a.pdf)');
    expect(h.body()).toContain('[c](c.pdf)');
    expect(h.body()).not.toContain('Uploading b.exe');
    expect(h.toast.error).toHaveBeenCalledWith(expect.stringContaining('b.exe'));
  });

  it('skips a file with no usable name and says so', async () => {
    const h = harness();
    await h.run([doc('README')], 0, { pasted: false });
    expect(h.upload).not.toHaveBeenCalled();
    expect(h.toast.error).toHaveBeenCalledWith(expect.stringContaining('README'));
  });
});
```

- [ ] **Step 8: Run — expect FAIL; implement `hooks/useAttachmentUpload.js`; run — expect PASS**

```js
import { useCallback, useEffect, useRef } from 'react';
import { pastedImageName, normalizeAttachmentName, uniqueAttachmentName, attachmentMarkup } from '../utils/attachmentNameValidator';

const isImage = (file) => /^image\//.test(file.type || '');

/**
 * Paste/drop upload for the editor. All placeholders are inserted at `pos` up front (one per line),
 * then files upload one at a time; each placeholder becomes the attachment markup, or is removed
 * (and the failure toasted) if its upload fails.
 *
 * @param {object} o
 * @param {string[]} o.existingNames  attachment names already on the page
 * @param {(file: File, name: string) => Promise<unknown>} o.upload  uploads and refreshes the list
 * @param {(fn: (prev: string) => string) => void} o.updateBody  functional body setter
 * @param {{ error: (msg: string) => void }} o.toast
 */
export function useAttachmentUpload({ existingNames, upload, updateBody, toast }) {
  const namesRef = useRef(existingNames);
  useEffect(() => { namesRef.current = existingNames; });

  return useCallback(async (files, pos, { pasted = false } = {}) => {
    const taken = [...(namesRef.current || [])];
    const plan = [];
    for (const file of files) {
      const base = pasted && isImage(file) ? pastedImageName(file.type) : normalizeAttachmentName(file.name);
      if (!base) {
        toast.error(`Cannot upload "${file.name}": it has no usable file name or extension`);
        continue;
      }
      const name = uniqueAttachmentName(base, taken);
      taken.push(name);
      plan.push({ file, name, placeholder: `![Uploading ${name}…]()` });
    }
    if (plan.length === 0) return;
    updateBody((prev) => prev.slice(0, pos) + plan.map((p) => p.placeholder).join('\n') + prev.slice(pos));
    for (const { file, name, placeholder } of plan) {
      try {
        await upload(file, name);
        updateBody((prev) => prev.replace(placeholder, attachmentMarkup(name, isImage(file))));
      } catch (err) {
        updateBody((prev) => prev.replace(placeholder, ''));
        toast.error(`Upload of ${name} failed: ${err?.message || err}`);
      }
    }
  }, [upload, updateBody, toast]);
}
```

- [ ] **Step 9: CodeEditor paste/drop handlers** — add prop `onFiles` (ref-held like the others) and a DOM-handler extension included in `extensions`:

```js
  const onFilesRef = useRef(onFiles);
  onFilesRef.current = onFiles;
  const fileDropExtension = useMemo(() => EditorView.domEventHandlers({
    paste(event, view) {
      const files = filesFromPaste(event.clipboardData);
      if (!files.length || !onFilesRef.current) return false;
      event.preventDefault();
      onFilesRef.current(files, view.state.selection.main.head, { pasted: true });
      return true;
    },
    drop(event, view) {
      const files = filesFromDrop(event.dataTransfer);
      if (!files.length || !onFilesRef.current) return false;
      event.preventDefault();
      const pos = view.posAtCoords({ x: event.clientX, y: event.clientY }) ?? view.state.selection.main.head;
      onFilesRef.current(files, pos, { pasted: false });
      return true;
    },
  }), []);
```
(The CodeMirror stub in tests cannot exercise these handlers; their logic lives in the tested utils. Real behaviour is checked in Task 11's manual pass.)

- [ ] **Step 10: Failing PageEditor tests**
  - In `PageEditor.test.jsx`, add a `describe('drop hint')`: `fireEvent.dragEnter` on the editor pane with `dataTransfer: { types: ['Files'] }` shows a hint reading **"Drop to upload"**; with `{ types: ['text/plain'] }` no hint appears.
  - The existing CodeMirror stub cannot deliver paste/drop events to `onFiles`, so the upload flow is tested through a seam: create `PageEditor.uploads.test.jsx` that mocks `./CodeEditor` with a stub rendering a button (`data-testid="fake-paste"`) whose click calls `props.onFiles([new File(['x'], 'image.png', { type: 'image/png' })], 0, { pasted: true })`. In that file assert:
    1. new page → clicking the stub button shows `data-testid="upload-needs-save"` with "Save the page once to add attachments", and `api.uploadAttachment` is not called;
    2. clicking **Save and upload** calls `api.savePage` once, does **not** navigate away, then calls `api.uploadAttachment` with the page name and a `pasted-…png` name;
    3. existing page → clicking the stub button calls `api.uploadAttachment` directly (no banner).
  Copy the module mocks from `PageEditor.test.jsx` (api, useAuth, useDraft, ToastProvider) and mock `../hooks/useAttachments` to return `{ list: [], uploadAttachment: (f, n) => api.uploadAttachment('P', f, n), … }`.

Run: `npx vitest run src/components/PageEditor.test.jsx src/components/PageEditor.uploads.test.jsx` — expect FAIL.

- [ ] **Step 11: Wire `PageEditor`**
  - Import `dragCarriesFiles` and `useAttachmentUpload`.
  - `handleDragEnter(e)`: `if (!dragCarriesFiles(e.dataTransfer)) return;` before incrementing; `handleDragLeave(e)`: same guard; `handleDragOver(e)`: `if (dragCarriesFiles(e.dataTransfer)) e.preventDefault();`. Hint text → `Drop to upload`.
  - Upload function:
    ```js
    const uploadFiles = useAttachmentUpload({
      existingNames: (attachments.list || []).map((a) => a.fileName),
      upload: attachments.uploadAttachment,
      updateBody: setBody,
      toast,
    });
    const [pendingUpload, setPendingUpload] = useState(null);
    const handleFiles = useCallback((files, pos, opts) => {
      if (isNew) { setPendingUpload({ files, pos, opts }); return; }
      uploadFiles(files, pos, opts);
    }, [isNew, uploadFiles]);
    ```
    Pass `onFiles={handleFiles}` to `<CodeEditor>`.
  - `saveContent` gains `{ stay = false } = {}` (the Save button passes a click event — destructuring an event yields `stay === undefined`, i.e. false). On success with `stay`: `setIsNew(false); setOriginalVersion(res?.version ?? originalVersion); setLoadedContent(reconstructContent(metadata, body)); return true;` instead of navigating. Return `false` from every failure branch.
  - Banner (after the draft-restore banner):
    ```jsx
    {pendingUpload && (
      <div className="info-banner" role="status" data-testid="upload-needs-save">
        <span>Save the page once to add attachments.</span>
        <button type="button" className="btn btn-primary btn-sm" disabled={saving || hasBlockingErrors}
          onClick={async () => {
            const p = pendingUpload;
            if (await saveContent({ stay: true })) {
              setPendingUpload(null);
              uploadFiles(p.files, p.pos, p.opts);
            }
          }}>
          Save and upload
        </button>
        <button type="button" className="btn-link" onClick={() => setPendingUpload(null)}>Cancel</button>
      </div>
    )}
    ```

- [ ] **Step 12: Run — expect PASS**, plus lint

Run: `npx vitest run src/components src/hooks src/utils && npm run lint`

- [ ] **Step 13: Commit**

```bash
git add wikantik-frontend/src/utils/attachmentNameValidator.js wikantik-frontend/src/utils/attachmentNameValidator.test.js \
        wikantik-frontend/src/utils/editorFileEvents.js wikantik-frontend/src/utils/editorFileEvents.test.js \
        wikantik-frontend/src/hooks/useAttachmentUpload.js wikantik-frontend/src/hooks/useAttachmentUpload.test.js \
        wikantik-frontend/src/components/CodeEditor.jsx wikantik-frontend/src/components/PageEditor.jsx \
        wikantik-frontend/src/components/PageEditor.test.jsx wikantik-frontend/src/components/PageEditor.uploads.test.jsx
git commit -m "feat(editor): paste and drop files to upload attachments; drop hint only for file drags" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg"
```

---

### Task 7: Preview signals — missing links, plugin chips, citation badges

**Files:**
- Create: `wikantik-frontend/src/utils/remarkWikiMarkup.js`, `…/remarkWikiMarkup.test.js`
- Create: `wikantik-frontend/src/utils/wikiLinkTargets.js`, `…/wikiLinkTargets.test.js`
- Create: `wikantik-frontend/src/hooks/useMissingPages.js`, `…/useMissingPages.test.js`
- Modify: `wikantik-frontend/src/components/PageEditor.jsx` (preview `remarkPlugins`)
- Modify: `wikantik-frontend/src/styles/globals.css`

**Interfaces:**
- Consumes: `api.listPages({ names, limit })` (Task 5).
- Produces: `remarkWikiMarkup()`; `pluginChipLabel(inner)`; `wikiLinkTarget(url) → string|null`; `citeTarget(url) → string|null`; `collectWikiLinkTargets(md) → string[]`; `remarkMissingLinks({ missing: Set<string lowercased> })`; `useMissingPages(markdown) → Set<string>` (lowercased names).

- [ ] **Step 1: Failing chip/badge tests** — `utils/remarkWikiMarkup.test.js` renders through the real preview stack:

```js
import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { remarkWikiMarkup, pluginChipLabel } from './remarkWikiMarkup';

const html = (md) => renderToStaticMarkup(
  <ReactMarkdown remarkPlugins={[remarkGfm, remarkWikiMarkup]}>{md}</ReactMarkdown>,
);

describe('pluginChipLabel', () => {
  it.each([
    ['TableOfContents', '⚙ TableOfContents'],
    ['com.example.plugins.Weather city=Oslo', '⚙ Weather'],
    ['ALLOW view Admin', '🔒 ALLOW view Admin'],
    ['DENY edit Guest', '🔒 DENY edit Guest'],
    ['SET alias=Foo', '≔ SET alias=Foo'],
    ['$username', '$username'],
  ])('%s → %s', (inner, label) => expect(pluginChipLabel(inner)).toBe(label));
});

describe('remarkWikiMarkup', () => {
  it('renders [{X}]() as a chip with the full markup in its title', () => {
    const out = html('Before [{TableOfContents}]() after');
    expect(out).toContain('class="wiki-plugin-chip"');
    expect(out).toContain('title="[{TableOfContents}]"');
    expect(out).toContain('⚙ TableOfContents');
    expect(out).not.toContain('<a ');
  });
  it('renders bare [{X}] as a chip', () => {
    expect(html('[{ALLOW view Admin}]\n\nBody')).toContain('🔒 ALLOW view Admin');
  });
  it('leaves plugin syntax inside code alone', () => {
    const out = html('`[{TableOfContents}]()`\n\n```\n[{ALLOW view Admin}]\n```');
    expect(out).not.toContain('wiki-plugin-chip');
  });
  it('adds a citation badge after a cite:// link', () => {
    const out = html('A [claim](cite://TargetPage/setup-steps "the quoted span") here.');
    expect(out).toContain('class="wiki-cite-badge"');
    expect(out).toContain('↗ TargetPage § setup-steps');
    expect(out).toContain('title="the quoted span"');
  });
});
```

- [ ] **Step 2: Run — expect FAIL; implement `utils/remarkWikiMarkup.js`**

```js
import { visit, SKIP } from 'unist-util-visit';
import { toString } from 'mdast-util-to-string';

// Bare `[{…}]`, optionally followed by `()` — remark leaves it as literal text.
const BARE_PLUGIN = /\[\{([^}\n]*)\}\](?:\(\))?/g;
const MAX_LABEL = 40;
const truncate = (s) => (s.length > MAX_LABEL ? `${s.slice(0, MAX_LABEL - 1)}…` : s);

/** Label for the inside of `[{…}]`: directives keep their text, plugins show their unqualified name. */
export function pluginChipLabel(inner) {
  const text = inner.trim();
  const first = text.split(/\s+/)[0] || '';
  const upper = first.toUpperCase();
  if (upper === 'ALLOW' || upper === 'DENY') return `🔒 ${truncate(text)}`;
  if (upper === 'SET') return `≔ ${truncate(text)}`;
  if (first.startsWith('$')) return truncate(text);
  return `⚙ ${first.split('.').pop()}`;
}

function chipNode(inner) {
  return {
    type: 'wikiPluginChip',
    data: {
      hName: 'span',
      hProperties: { className: ['wiki-plugin-chip'], title: `[{${inner}}]` },
      hChildren: [{ type: 'text', value: pluginChipLabel(inner) }],
    },
  };
}

function citeParts(url) {
  const rest = url.slice('cite://'.length);
  const slash = rest.indexOf('/');
  const decode = (s) => { try { return decodeURIComponent(s); } catch { return s; } };
  return slash < 0
    ? { target: decode(rest), heading: '' }
    : { target: decode(rest.slice(0, slash)), heading: decode(rest.slice(slash + 1)) };
}

function citeBadge(link) {
  const { target, heading } = citeParts(link.url);
  return {
    type: 'wikiCiteBadge',
    data: {
      hName: 'span',
      hProperties: { className: ['wiki-cite-badge'], title: link.title || '', 'data-cite-target': target },
      hChildren: [{ type: 'text', value: `↗ ${target}${heading ? ` § ${heading}` : ''}` }],
    },
  };
}

function mergeAdjacentText(tree) {
  visit(tree, (node) => {
    if (!node.children) return;
    const merged = [];
    for (const child of node.children) {
      const prev = merged[merged.length - 1];
      if (child.type === 'text' && prev && prev.type === 'text') prev.value += child.value;
      else merged.push(child);
    }
    node.children = merged;
  });
}

/** remark plugin: `[{…}]` plugin/directive markup → labelled chips; `cite://` links → link + target badge. */
export function remarkWikiMarkup() {
  return (tree) => {
    visit(tree, 'link', (node, index, parent) => {
      if (!parent || index == null) return undefined;
      if (node.url === '') {
        const m = /^\{([\s\S]*)\}$/.exec(toString(node));
        if (m) {
          parent.children[index] = chipNode(m[1]);
          return SKIP;
        }
      }
      if (node.url.startsWith('cite://')) {
        parent.children.splice(index + 1, 0, citeBadge(node));
        return [SKIP, index + 2];
      }
      return undefined;
    });
    mergeAdjacentText(tree);
    visit(tree, 'text', (node, index, parent) => {
      if (!parent || index == null) return undefined;
      BARE_PLUGIN.lastIndex = 0;
      const parts = [];
      let last = 0;
      let m;
      while ((m = BARE_PLUGIN.exec(node.value))) {
        if (m.index > last) parts.push({ type: 'text', value: node.value.slice(last, m.index) });
        parts.push(chipNode(m[1]));
        last = m.index + m[0].length;
      }
      if (parts.length === 0) return undefined;
      if (last < node.value.length) parts.push({ type: 'text', value: node.value.slice(last) });
      parent.children.splice(index, 1, ...parts);
      return [SKIP, index + parts.length];
    });
  };
}

export { citeParts };
```

Run: `npx vitest run src/utils/remarkWikiMarkup.test.js` — expect PASS.

- [ ] **Step 3: Failing target tests** — `utils/wikiLinkTargets.test.js`:

```js
import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import ReactMarkdown from 'react-markdown';
import { wikiLinkTarget, collectWikiLinkTargets, remarkMissingLinks } from './wikiLinkTargets';

describe('wikiLinkTarget', () => {
  it.each([
    ['PageName', 'PageName'],
    ['PageName#setup', 'PageName'],
    ['My%20Page', 'My Page'],
    ['#local', null],
    ['https://x.org', null],
    ['mailto:a@b', null],
    ['cite://T/h', null],
    ['/wiki/Foo', null],
    ['diagram.png', null],
    ['Other/file.pdf', null],
    ['', null],
  ])('%s → %s', (url, expected) => expect(wikiLinkTarget(url)).toBe(expected));
});

describe('collectWikiLinkTargets', () => {
  it('collects distinct page targets plus cite targets, skipping code and names with commas', () => {
    const md = '[a](Alpha) [b](Alpha#x) [c](cite://Beta/h "s") `[d](Gamma)` [e](A,B) ![i](pic.png)';
    expect(collectWikiLinkTargets(md)).toEqual(['Alpha', 'Beta']);
  });
});

describe('remarkMissingLinks', () => {
  it('marks links to missing pages like the page view marks create-links', () => {
    const out = renderToStaticMarkup(
      <ReactMarkdown remarkPlugins={[[remarkMissingLinks, { missing: new Set(['ghost']) }]]}>
        {'[g](Ghost) [h](Home)'}
      </ReactMarkdown>,
    );
    expect(out).toContain('<a href="Ghost" class="createpage" data-missing-page="Ghost"');
    expect(out).toContain('<a href="Home">');
  });
});
```

- [ ] **Step 4: Run — expect FAIL; implement `utils/wikiLinkTargets.js`; run — expect PASS**

```js
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import { visit } from 'unist-util-visit';
import { citeParts } from './remarkWikiMarkup';

const SCHEME = /^[a-z][a-z0-9+.-]*:/i;
const parser = unified().use(remarkParse).use(remarkGfm);

/** The wiki page a link URL points at, or null for external/anchor/cite/attachment links. */
export function wikiLinkTarget(url) {
  if (!url || url.startsWith('#') || url.startsWith('/') || SCHEME.test(url)) return null;
  let page = url.split('#')[0];
  try { page = decodeURIComponent(page); } catch (err) { console.warn('[link-targets] undecodable link target', url, err?.message); }
  // A '.' or '/' means an attachment reference (same rule as remarkAttachments).
  if (!page || page.includes('/') || page.includes('.')) return null;
  return page;
}

/** The page a cite:// link grounds in, or null. */
export function citeTarget(url) {
  return url && url.startsWith('cite://') ? (citeParts(url).target || null) : null;
}

/**
 * Distinct page names linked from `md` (wiki links and cite:// targets), sorted. Names containing a
 * comma are skipped: they cannot be sent through the comma-separated `names=` check.
 */
export function collectWikiLinkTargets(md) {
  const out = new Set();
  visit(parser.parse(md || ''), 'link', (node) => {
    const t = wikiLinkTarget(node.url) || citeTarget(node.url);
    if (t && !t.includes(',')) out.add(t);
  });
  return [...out].sort();
}

/** remark plugin: give links to missing pages the server's create-link class. `missing` holds lowercased names. */
export function remarkMissingLinks({ missing } = {}) {
  return (tree) => {
    if (!missing || missing.size === 0) return;
    visit(tree, 'link', (node) => {
      const target = wikiLinkTarget(node.url) || citeTarget(node.url);
      if (!target || !missing.has(target.toLowerCase())) return;
      node.data = node.data || {};
      node.data.hProperties = {
        ...(node.data.hProperties || {}),
        className: ['createpage'],
        'data-missing-page': target,
        title: `${target} does not exist yet`,
      };
    });
  };
}
```
If the `<a …` attribute order in the rendered HTML differs from the test string, assert the attributes individually (`toContain('class="createpage"')`, `toContain('data-missing-page="Ghost"')`) rather than changing the implementation.

- [ ] **Step 5: Failing hook tests** — `hooks/useMissingPages.test.js` (fake timers; mock `../api/client`):

```js
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

vi.mock('../api/client', () => ({ api: { listPages: vi.fn() } }));
import { api } from '../api/client';
import { useMissingPages } from './useMissingPages';

beforeEach(() => { vi.useFakeTimers(); api.listPages.mockReset(); });
afterEach(() => { vi.useRealTimers(); });

const flush = async () => { await act(async () => { vi.advanceTimersByTime(500); }); await act(async () => {}); };

describe('useMissingPages', () => {
  it('checks link targets after 500 ms and reports the missing ones (lowercased)', async () => {
    api.listPages.mockResolvedValue({ pages: [{ name: 'Home' }] });
    const { result } = renderHook(({ md }) => useMissingPages(md), { initialProps: { md: '[a](Home) [b](Ghost)' } });
    expect(api.listPages).not.toHaveBeenCalled();
    await flush();
    expect(api.listPages).toHaveBeenCalledWith({ names: ['Ghost', 'Home'], limit: 50 });
    expect([...result.current]).toEqual(['ghost']);
  });

  it('only checks new targets and drops removed ones', async () => {
    api.listPages.mockResolvedValue({ pages: [] });
    const { result, rerender } = renderHook(({ md }) => useMissingPages(md), { initialProps: { md: '[b](Ghost)' } });
    await flush();
    rerender({ md: '[c](Other)' });
    await flush();
    expect(api.listPages).toHaveBeenLastCalledWith({ names: ['Other'], limit: 50 });
    expect([...result.current]).toEqual(['other']);
  });

  it('compares case-insensitively with the canonical names the server returns', async () => {
    api.listPages.mockResolvedValue({ pages: [{ name: 'Home' }] });
    const { result } = renderHook(() => useMissingPages('[a](home)'));
    await flush();
    expect(result.current.size).toBe(0);
  });

  it('chunks more than 50 targets', async () => {
    api.listPages.mockResolvedValue({ pages: [] });
    const md = Array.from({ length: 51 }, (_, i) => `[x](P${i})`).join(' ');
    renderHook(() => useMissingPages(md));
    await flush();
    expect(api.listPages).toHaveBeenCalledTimes(2);
  });

  it('warns and marks nothing when the check fails', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.listPages.mockRejectedValue(new Error('503'));
    const { result } = renderHook(() => useMissingPages('[b](Ghost)'));
    await flush();
    expect(result.current.size).toBe(0);
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });
});
```

- [ ] **Step 6: Run — expect FAIL; implement `hooks/useMissingPages.js`; run — expect PASS**

```js
import { useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import { collectWikiLinkTargets } from '../utils/wikiLinkTargets';

const CHECK_DELAY_MS = 500;
const MAX_NAMES = 50;

/**
 * Lowercased names of pages linked from `markdown` that do not exist (or that the caller cannot
 * view). Checked in batches through GET /api/pages?names=, 500 ms after the last edit; results are
 * cached for the editing session so only newly typed targets hit the server.
 */
export function useMissingPages(markdown) {
  const [missing, setMissing] = useState(() => new Set());
  const known = useRef(new Map()); // lowercased name → exists

  useEffect(() => {
    let cancelled = false;
    const id = setTimeout(() => {
      const targets = collectWikiLinkTargets(markdown);
      const unknown = targets.filter((t) => !known.current.has(t.toLowerCase()));
      const chunks = [];
      for (let i = 0; i < unknown.length; i += MAX_NAMES) chunks.push(unknown.slice(i, i + MAX_NAMES));
      Promise.all(chunks.map((names) => api.listPages({ names, limit: MAX_NAMES })))
        .then((results) => {
          const existing = new Set(results.flatMap((r) => (r.pages || []).map((p) => p.name.toLowerCase())));
          unknown.forEach((t) => known.current.set(t.toLowerCase(), existing.has(t.toLowerCase())));
          if (!cancelled) {
            setMissing(new Set(targets.map((t) => t.toLowerCase()).filter((t) => known.current.get(t) === false)));
          }
        })
        .catch((err) => console.warn('[missing-pages] existence check failed', err?.message || err));
    }, CHECK_DELAY_MS);
    return () => { cancelled = true; clearTimeout(id); };
  }, [markdown]);

  return missing;
}
```

- [ ] **Step 7: Wire the preview in `PageEditor.jsx`** — `const missingPages = useMissingPages(previewContent);` and:

```jsx
            <ReactMarkdown remarkPlugins={[
              remarkGfm,
              remarkMath,
              remarkWikiMarkup,
              [remarkMissingLinks, { missing: missingPages }],
              [remarkAttachments, { attachments: attachments.list, pageName: name }],
            ]} rehypePlugins={[rehypeKatex, rehypeSourceLine]}>
```
Add a PageEditor test: page body `[x](Ghost)`, `api.listPages` resolving `{ pages: [] }` for the `names` call → after advancing timers the preview contains an anchor with class `createpage`. Mirror the fake-timer pattern above. Run: expect PASS after wiring.

- [ ] **Step 8: Styles** — append to `styles/globals.css` (R4):

```css
/* Missing-page links: the server render emits class="createpage"; the editor preview uses the same class. */
.article-prose a.createpage {
  color: var(--danger);
  text-decoration: underline dashed;
  text-underline-offset: 2px;
}

.wiki-plugin-chip,
.wiki-cite-badge {
  display: inline-block;
  padding: 0 var(--space-xs);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-elevated);
  color: var(--text-muted);
  font-family: var(--font-ui);
  font-size: 0.75em;
  line-height: 1.6;
  vertical-align: baseline;
  white-space: nowrap;
}
.wiki-cite-badge { margin-left: 0.25em; }
```

- [ ] **Step 9: Run the frontend suite + lint — expect PASS**

Run: `npx vitest run && npm run lint`

- [ ] **Step 10: Commit**

```bash
git add wikantik-frontend/src/utils/remarkWikiMarkup.js wikantik-frontend/src/utils/remarkWikiMarkup.test.js \
        wikantik-frontend/src/utils/wikiLinkTargets.js wikantik-frontend/src/utils/wikiLinkTargets.test.js \
        wikantik-frontend/src/hooks/useMissingPages.js wikantik-frontend/src/hooks/useMissingPages.test.js \
        wikantik-frontend/src/components/PageEditor.jsx wikantik-frontend/src/components/PageEditor.test.jsx \
        wikantik-frontend/src/styles/globals.css
git commit -m "feat(editor): preview marks missing-page links and shows plugin/citation chips" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg"
```

---

### Task 8: Code highlighting — editor, preview, page view

**Files:**
- Modify: `wikantik-frontend/package.json` (+ lockfile) — `@codemirror/language-data`, `lowlight`
- Create: `wikantik-frontend/src/utils/codeHighlight.js`, `…/codeHighlight.test.js`
- Create: `wikantik-frontend/src/hooks/useLowlight.js`
- Modify: `wikantik-frontend/src/components/CodeEditor.jsx`, `components/PageEditor.jsx`, `components/PageView.jsx`, `components/PageView.test.jsx`, `styles/globals.css`

**Interfaces:**
- Produces: `loadLowlight(): Promise<Lowlight>` (memoised dynamic import); `codeLanguage(className) → string|null`; `rehypeHighlightCode({ lowlight })`; `highlightCodeBlocks(container, lowlight)`; `hastToDom(node, doc)`; `useLowlight(enabled) → Lowlight|null`.

- [ ] **Step 1: Add deps**

Run (in `wikantik-frontend/`): `npm install --save @codemirror/language-data@^6.5.2 lowlight@^3`
Then `node -e "import('lowlight').then(m=>console.log(typeof m.createLowlight, typeof m.common))"` → `function object`.

- [ ] **Step 2: Failing tests** — `utils/codeHighlight.test.js`:

```js
import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import ReactMarkdown from 'react-markdown';
import { createLowlight, common } from 'lowlight';
import { codeLanguage, rehypeHighlightCode, highlightCodeBlocks } from './codeHighlight';

const lowlight = createLowlight(common);

describe('codeLanguage', () => {
  it.each([
    ['language-js', 'js'],
    [['language-Java'], 'java'],
    ['foo language-c++ bar', 'c++'],
    ['', null],
    [undefined, null],
  ])('%s → %s', (cls, lang) => expect(codeLanguage(cls)).toBe(lang));
});

describe('rehypeHighlightCode (preview)', () => {
  const render = (md, opts) => renderToStaticMarkup(
    <ReactMarkdown rehypePlugins={[[rehypeHighlightCode, opts]]}>{md}</ReactMarkdown>,
  );
  it('highlights fenced blocks that declare a known language', () => {
    const out = render('```js\nconst x = 1;\n```', { lowlight });
    expect(out).toContain('hljs-keyword');
    expect(out).toContain('class="language-js hljs"');
  });
  it('leaves undeclared or unknown languages and inline code untouched', () => {
    expect(render('```\nconst x = 1;\n```', { lowlight })).not.toContain('hljs');
    expect(render('```nosuchlang\nx\n```', { lowlight })).not.toContain('hljs');
    expect(render('`const x`', { lowlight })).not.toContain('hljs');
  });
  it('is a no-op until the highlighter has loaded', () => {
    expect(render('```js\nconst x = 1;\n```', {})).not.toContain('hljs');
  });
});

describe('highlightCodeBlocks (page view DOM pass)', () => {
  it('highlights server-rendered blocks once, preserving text', () => {
    const root = document.createElement('div');
    root.innerHTML = '<pre><code class="language-js">const x = 1;</code></pre><pre><code>plain</code></pre>';
    highlightCodeBlocks(root, lowlight);
    highlightCodeBlocks(root, lowlight); // idempotent
    const code = root.querySelector('code.language-js');
    expect(code.querySelectorAll('.hljs-keyword')).toHaveLength(1);
    expect(code.textContent).toBe('const x = 1;');
    expect(root.querySelectorAll('pre')[1].innerHTML).toBe('<code>plain</code>');
  });
  it('does nothing without a container or highlighter', () => {
    expect(() => highlightCodeBlocks(null, lowlight)).not.toThrow();
    expect(() => highlightCodeBlocks(document.createElement('div'), null)).not.toThrow();
  });
});
```

- [ ] **Step 3: Run — expect FAIL; implement `utils/codeHighlight.js`; run — expect PASS**

```js
import { visit } from 'unist-util-visit';

const LANG_CLASS = /(?:^|\s)language-([\w+#-]+)/;
let lowlightPromise = null;

/** lowlight with highlight.js's "common" grammars, loaded once and code-split out of the main bundle. */
export function loadLowlight() {
  if (!lowlightPromise) {
    lowlightPromise = import('lowlight').then(({ createLowlight, common }) => createLowlight(common));
  }
  return lowlightPromise;
}

const toArray = (cls) => (Array.isArray(cls) ? cls : String(cls || '').split(/\s+/).filter(Boolean));

/** The declared language of a code element's class (`language-x`), lowercased, or null. */
export function codeLanguage(className) {
  const m = LANG_CLASS.exec(toArray(className).join(' '));
  return m ? m[1].toLowerCase() : null;
}

function hastText(node) {
  if (node.type === 'text') return node.value;
  return (node.children || []).map(hastText).join('');
}

/** rehype plugin (editor preview): highlight `pre > code.language-x` once `lowlight` has loaded. */
export function rehypeHighlightCode({ lowlight } = {}) {
  return (tree) => {
    if (!lowlight) return;
    visit(tree, 'element', (node, _index, parent) => {
      if (node.tagName !== 'code' || !parent || parent.tagName !== 'pre') return;
      const lang = codeLanguage(node.properties?.className);
      if (!lang || !lowlight.registered(lang)) return;
      node.children = lowlight.highlight(lang, hastText(node)).children;
      node.properties.className = [...toArray(node.properties.className), 'hljs'];
    });
  };
}

/** Converts lowlight's hast (spans with classes, text) to DOM nodes. */
export function hastToDom(node, doc) {
  if (node.type === 'text') return doc.createTextNode(node.value);
  const el = doc.createElement(node.tagName);
  const cls = toArray(node.properties?.className);
  if (cls.length) el.className = cls.join(' ');
  (node.children || []).forEach((child) => el.appendChild(hastToDom(child, doc)));
  return el;
}

/** Page-view DOM pass over server HTML: highlight `pre > code.language-x`; idempotent. */
export function highlightCodeBlocks(container, lowlight) {
  if (!container || !lowlight) return;
  container.querySelectorAll('pre > code').forEach((code) => {
    if (code.dataset.highlighted) return;
    const lang = codeLanguage(code.className);
    if (!lang || !lowlight.registered(lang)) return;
    const tree = lowlight.highlight(lang, code.textContent);
    code.replaceChildren(...tree.children.map((n) => hastToDom(n, code.ownerDocument)));
    code.classList.add('hljs');
    code.dataset.highlighted = '1';
  });
}
```

`hooks/useLowlight.js`:

```js
import { useEffect, useState } from 'react';
import { loadLowlight } from '../utils/codeHighlight';

/** The highlighter once loaded (null before, or while `enabled` is false — no download for pages without code). */
export function useLowlight(enabled) {
  const [lowlight, setLowlight] = useState(null);
  useEffect(() => {
    if (!enabled || lowlight) return undefined;
    let alive = true;
    loadLowlight()
      .then((l) => { if (alive) setLowlight(l); })
      .catch((err) => console.warn('[highlight] failed to load the highlighter', err?.message || err));
    return () => { alive = false; };
  }, [enabled, lowlight]);
  return lowlight;
}
```

- [ ] **Step 4: Editor + preview wiring**
  - `CodeEditor.jsx`: `import { languages } from '@codemirror/language-data';` and `markdown({ codeLanguages: languages })` in `extensions`.
  - `PageEditor.jsx`: `const lowlight = useLowlight(/(^|\n)(```|~~~)/.test(previewContent));` and `rehypePlugins={[rehypeKatex, [rehypeHighlightCode, { lowlight }], rehypeSourceLine]}`.

- [ ] **Step 5: Failing PageView test** — in `PageView.test.jsx`, mock `../utils/codeHighlight`'s `loadLowlight` to resolve `createLowlight(common)` (import both from `lowlight` inside the mock factory via `vi.importActual`), give the mocked page `contentHtml: '<pre><code class="language-js">const x = 1;</code></pre>'`, and assert (with `waitFor`) that the rendered article contains `.hljs-keyword`. Run: expect FAIL.

- [ ] **Step 6: PageView wiring** — next to the `renderMath` effect (declare `useLowlight` with PageView's other hooks, above any early `return`):

```js
  const lowlight = useLowlight(!!page?.contentHtml && page.contentHtml.includes('class="language-'));
  // Same DOM-pass pattern as renderMath: idempotent, re-runs when the article is re-rendered.
  useEffect(() => {
    highlightCodeBlocks(articleRef.current, lowlight);
  }, [page, lowlight]);
```
Run: expect PASS.

- [ ] **Step 7: Theme tokens** — append to `styles/globals.css` (add the tokens inside the existing `:root` block and the existing `[data-theme="dark"]` block rather than new blocks):

```css
/* :root */
  --code-keyword: #7c3aed; --code-string: #0b7a3e; --code-number: #b35c00; --code-comment: #8a8580;
  --code-title: #1f5fbf;   --code-type: #a1307e;   --code-attr: #7a5c00;
/* [data-theme="dark"] */
  --code-keyword: #c4a5ff; --code-string: #7fd8a0; --code-number: #ffb27a; --code-comment: #8f877f;
  --code-title: #8fb8ff;   --code-type: #f08fcf;   --code-attr: #e6c56b;
```

```css
.hljs-keyword, .hljs-selector-tag, .hljs-built_in { color: var(--code-keyword); }
.hljs-string, .hljs-regexp, .hljs-addition { color: var(--code-string); }
.hljs-number, .hljs-literal, .hljs-symbol, .hljs-deletion { color: var(--code-number); }
.hljs-comment, .hljs-quote, .hljs-meta { color: var(--code-comment); font-style: italic; }
.hljs-title, .hljs-section { color: var(--code-title); }
.hljs-type, .hljs-variable, .hljs-template-variable { color: var(--code-type); }
.hljs-attr, .hljs-attribute, .hljs-name, .hljs-tag { color: var(--code-attr); }
```

- [ ] **Step 8: Build check (bundle split) + suite + lint**

Run: `npx vitest run && npm run lint && npm run build`
Expected: build succeeds and emits a separate chunk containing lowlight (check `dist/assets/` for a chunk other than the main entry that includes `createLowlight`: `grep -l createLowlight dist/assets/*.js` lists a non-index chunk).

- [ ] **Step 9: Commit**

```bash
git add wikantik-frontend/package.json wikantik-frontend/package-lock.json \
        wikantik-frontend/src/utils/codeHighlight.js wikantik-frontend/src/utils/codeHighlight.test.js \
        wikantik-frontend/src/hooks/useLowlight.js wikantik-frontend/src/components/CodeEditor.jsx \
        wikantik-frontend/src/components/PageEditor.jsx wikantik-frontend/src/components/PageView.jsx \
        wikantik-frontend/src/components/PageView.test.jsx wikantik-frontend/src/styles/globals.css
git commit -m "feat(frontend): syntax highlighting for fenced code in editor, preview and page view" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg"
```

---

### Task 9: Restore a version (editor-first)

**Files:**
- Modify: `wikantik-frontend/src/components/ChangeNotesPanel.jsx`, `…/ChangeNotesPanel.test.jsx`
- Modify: `wikantik-frontend/src/components/PageView.jsx` (~line 587: pass `canEdit`)
- Modify: `wikantik-frontend/src/components/DiffViewer.jsx`, `…/DiffViewer.test.jsx`
- Modify: `wikantik-frontend/src/components/PageEditor.jsx` (load effect ~lines 232–263, banner), `…/PageEditor.test.jsx`

**Interfaces:**
- Consumes: `api.getPage(name, { version })` (existing).
- Produces: route state `{ restoreVersion: number }` on `/edit/{name}`; `ChangeNotesPanel` prop `canEdit: boolean`.

- [ ] **Step 1: Failing ChangeNotesPanel tests** — with history `[{version:3},{version:2},{version:1}]` (newest first, as the server returns):
  - `canEdit` → rows v2 and v1 show a **Restore** link (`data-testid="restore-v2"`, `restore-v1`) whose `href` is `/edit/<page>`; v3 (current) has none;
  - clicking `restore-v2` inside a `MemoryRouter` with a `/edit/:name` route renders a probe component that reads `useLocation().state` → `{ restoreVersion: 2 }`;
  - without `canEdit` no Restore links render.

Run: `npx vitest run src/components/ChangeNotesPanel.test.jsx` — expect FAIL.

- [ ] **Step 2: Implement** — `ChangeNotesPanel({ pageName, canEdit = false })`; compute `const current = Math.max(...versions.map((v) => v.version));`; when `canEdit`, add a fifth header `''` and a cell per row:

```jsx
<td style={{ padding: 'var(--space-xs) 0 var(--space-xs) var(--space-sm)', whiteSpace: 'nowrap' }}>
  {v.version !== current && (
    <Link to={`/edit/${pageName}`} state={{ restoreVersion: v.version }} data-testid={`restore-v${v.version}`}
      style={{ fontFamily: 'var(--font-ui)', fontSize: '0.8rem', color: 'var(--accent)', textDecoration: 'none' }}>
      Restore
    </Link>
  )}
</td>
```
In `PageView.jsx`: `<ChangeNotesPanel pageName={name} canEdit={!!page?.permissions?.edit} />`. Run: expect PASS.

- [ ] **Step 3: Failing DiffViewer tests** — mock `api.getPage` → `{ permissions: { edit: true } }`, history v1..v3: the page shows a link **Restore version 1** (the default "from" version) with state `{ restoreVersion: 1 }`; changing the From select to v3 (the current version) hides it; with `permissions.edit: false` it never shows; if `api.getPage` rejects, no link and a `console.warn` (spy). Run: expect FAIL.

- [ ] **Step 4: Implement** — in `DiffViewer`:

```js
  const [canEdit, setCanEdit] = useState(false);
  useEffect(() => {
    let ignore = false;
    api.getPage(name)
      .then((p) => { if (!ignore) setCanEdit(!!p?.permissions?.edit); })
      .catch((err) => console.warn('[diff] could not load page permissions', err?.message || err));
    return () => { ignore = true; };
  }, [name]);
  const currentVersion = versions && versions.length ? versions[0].version : null;
```
and after the selects:

```jsx
{canEdit && fromVer != null && fromVer !== currentVersion && (
  <Link to={`/edit/${name}`} state={{ restoreVersion: fromVer }} data-testid="diff-restore"
    className="btn btn-ghost btn-sm">
    Restore version {fromVer}
  </Link>
)}
```
(import `Link` from `react-router-dom`). Run: expect PASS.

- [ ] **Step 5: Failing PageEditor tests** — `describe('restore mode')`, rendering the editor with `initialEntries={[{ pathname: '/edit/P', state: { restoreVersion: 2 } }]}`:
  - `api.getPage` called with `('P')` then `('P', { version: 2 })`; the editor shows version 2's body; the structured frontmatter reflects version 2's metadata; the change-note input value is `Restored version 2`; `data-testid="restore-banner"` reads "Editing a copy of version 2 — saving creates version 4." when the current version is 3;
  - Save sends `expectedVersion: 3` and the restored content;
  - the draft-restore prompt is not shown even when `useDraft` returns a draft;
  - **Review Focus #4:** when `api.getPage('P', { version: 2 })` rejects, the editor shows the current (v3) body, an error banner containing "version 2", no restore banner, and an empty change note.

Run: expect FAIL.

- [ ] **Step 6: Implement** — in the load effect, read `const restoreVersion = location.state?.restoreVersion;` (add it to the effect deps) and restructure the success branch:

```js
    api.getPage(name).then(async (page) => {
      const meta = page.metadata || {};
      const pageBody = page.content || '';
      const full = reconstructContent(meta, pageBody);
      setLoadedContent(full);                // dirty baseline = current server content
      setOriginalVersion(page.version);      // expectedVersion stays the CURRENT version
      setMarkupSyntax(page.markupSyntax || 'markdown');
      setIsNew(false);
      if (restoreVersion) {
        try {
          const old = await api.getPage(name, { version: restoreVersion });
          setMetadata(old.metadata || {});
          setBody(old.content || '');
          setChangeNote(`Restored version ${restoreVersion}`);
          setRestoring({ from: restoreVersion, current: page.version });
          return;
        } catch (err) {
          console.warn('[editor] could not load version for restore', restoreVersion, err?.message || err);
          setError(`Could not load version ${restoreVersion}: ${err?.message || 'request failed'}`);
        }
      }
      setMetadata(meta);
      setBody(pageBody);
      if (draft && draft.content && draft.content !== full) setRestorePrompt(true);
    })
```
Add `const [restoring, setRestoring] = useState(null);` and the banner after the draft banner:

```jsx
{restoring && (
  <div className="info-banner" role="status" data-testid="restore-banner">
    Editing a copy of version {restoring.from} — saving creates version {restoring.current + 1}.
  </div>
)}
```
Run: expect PASS; then `npx vitest run src/components && npm run lint`.

- [ ] **Step 7: Commit**

```bash
git add wikantik-frontend/src/components/ChangeNotesPanel.jsx wikantik-frontend/src/components/ChangeNotesPanel.test.jsx \
        wikantik-frontend/src/components/PageView.jsx wikantik-frontend/src/components/DiffViewer.jsx \
        wikantik-frontend/src/components/DiffViewer.test.jsx wikantik-frontend/src/components/PageEditor.jsx \
        wikantik-frontend/src/components/PageEditor.test.jsx
git commit -m "feat(history): restore a version by opening it in the editor (normal save path)" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg"
```

---

### Task 10: Editor side rail (outline + backlinks) and status bar

**Files:**
- Create: `wikantik-frontend/src/utils/editorCursorStore.js`, `…/editorCursorStore.test.js`
- Create: `wikantik-frontend/src/hooks/useEditorCursor.js`, `hooks/useDebouncedValue.js`, `hooks/useRailOpen.js`, `hooks/useRailOpen.test.js`
- Create: `wikantik-frontend/src/components/editor/EditorRail.jsx`, `…/EditorRail.test.jsx`
- Create: `wikantik-frontend/src/components/editor/EditorStatusBar.jsx`, `…/EditorStatusBar.test.jsx`
- Modify: `wikantik-frontend/src/components/BacklinksPanel.jsx` (optional `emptyText`), `components/CodeEditor.jsx` (`getCursor`), `components/CodeEditor.test.jsx`, `components/PageEditor.jsx`, `styles/globals.css`

**Interfaces:**
- Consumes: `headingsFromMarkdown` (Task 3); `readingTime` (`utils/readingTime.js`); `BacklinksPanel`; `CodeEditor` handle `getViewport()`, `jumpToLineAligned(line, offset)`.
- Produces: `createEditorCursorStore() → { get, set(patch), subscribe }` with state `{ line, col, selectionText, topLine }`; `useEditorCursor(store)`; `useDebouncedValue(value, ms)`; `useRailOpen() → [open, toggle]`; CodeEditor handle `getCursor() → { line, col, selectionText } | null`; `<EditorRail body pageName isNew cursorStore open onToggle onJump />`; `<EditorStatusBar body cursorStore />`.

- [ ] **Step 1: Failing store + rail-state tests**

`utils/editorCursorStore.test.js`:
```js
import { describe, it, expect, vi } from 'vitest';
import { createEditorCursorStore } from './editorCursorStore';

describe('createEditorCursorStore', () => {
  it('starts at line 1 and notifies subscribers only on real changes', () => {
    const s = createEditorCursorStore();
    expect(s.get()).toEqual({ line: 1, col: 1, selectionText: '', topLine: 1 });
    const l = vi.fn();
    const unsub = s.subscribe(l);
    s.set({ line: 1 });
    expect(l).not.toHaveBeenCalled();
    s.set({ line: 4, col: 2 });
    expect(l).toHaveBeenCalledTimes(1);
    expect(s.get().line).toBe(4);
    unsub();
    s.set({ line: 5 });
    expect(l).toHaveBeenCalledTimes(1);
  });
});
```

`hooks/useRailOpen.test.js` (Review Focus #5):
```js
import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useRailOpen } from './useRailOpen';

const mq = (matches) => vi.spyOn(window, 'matchMedia').mockImplementation(() => ({ matches }));
afterEach(() => { vi.restoreAllMocks(); try { localStorage.clear(); } catch { /* storage may be unavailable in some tests */ } });

describe('useRailOpen', () => {
  it('defaults open at ≥1100px and closed below', () => {
    mq(true);
    expect(renderHook(() => useRailOpen()).result.current[0]).toBe(true);
    vi.restoreAllMocks();
    mq(false);
    expect(renderHook(() => useRailOpen()).result.current[0]).toBe(false);
  });
  it('remembers the choice', () => {
    mq(true);
    const { result } = renderHook(() => useRailOpen());
    act(() => result.current[1]());
    expect(result.current[0]).toBe(false);
    expect(renderHook(() => useRailOpen()).result.current[0]).toBe(false);
  });
  it('works when storage throws', () => {
    mq(true);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied'); });
    const { result } = renderHook(() => useRailOpen());
    expect(result.current[0]).toBe(true);
    act(() => result.current[1]());
    expect(result.current[0]).toBe(false);
  });
});
```
(The `catch` comment in `afterEach` is test-harness cleanup, not production code.)

Run: expect FAIL. Implement:

`utils/editorCursorStore.js`:
```js
/**
 * Tiny external store for the editor's cursor position, selection and top visible line. Updates fire
 * on every keystroke and scroll; routing them through a store lets only the rail and status bar
 * re-render, never PageEditor and its preview.
 */
export function createEditorCursorStore() {
  let state = { line: 1, col: 1, selectionText: '', topLine: 1 };
  const listeners = new Set();
  return {
    get: () => state,
    set(patch) {
      if (Object.keys(patch).every((k) => patch[k] === state[k])) return;
      state = { ...state, ...patch };
      listeners.forEach((l) => l());
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}
```

`hooks/useEditorCursor.js`:
```js
import { useSyncExternalStore } from 'react';
export function useEditorCursor(store) {
  return useSyncExternalStore(store.subscribe, store.get);
}
```

`hooks/useDebouncedValue.js`:
```js
import { useEffect, useState } from 'react';
export function useDebouncedValue(value, ms) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return debounced;
}
```

`hooks/useRailOpen.js`:
```js
import { useCallback, useState } from 'react';

const KEY = 'wikantik.editor.railOpen';
const WIDE = '(min-width: 1100px)';

function initialOpen() {
  try {
    const stored = localStorage.getItem(KEY);
    if (stored === 'true' || stored === 'false') return stored === 'true';
  } catch (err) {
    console.warn('[editor-rail] could not read the saved rail state', err?.message || err);
  }
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia(WIDE).matches
    : true;
}

/** Side-rail open state: remembered per browser; defaults open on wide viewports. */
export function useRailOpen() {
  const [open, setOpen] = useState(initialOpen);
  const toggle = useCallback(() => {
    setOpen((prev) => {
      const next = !prev;
      try {
        localStorage.setItem(KEY, String(next));
      } catch (err) {
        console.warn('[editor-rail] could not save the rail state', err?.message || err);
      }
      return next;
    });
  }, []);
  return [open, toggle];
}
```
Run: expect PASS.

- [ ] **Step 2: Failing component tests**

`components/editor/EditorStatusBar.test.jsx`:
```jsx
import { describe, it, expect } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import EditorStatusBar from './EditorStatusBar';
import { createEditorCursorStore } from '../../utils/editorCursorStore';

describe('EditorStatusBar', () => {
  it('shows words, reading time and cursor; selection count when selecting', () => {
    const store = createEditorCursorStore();
    const body = 'one two three\n\n```\ncode words here\n```\nfour';
    render(<EditorStatusBar body={body} cursorStore={store} />);
    expect(screen.getByTestId('status-words')).toHaveTextContent('4 words');
    expect(screen.getByTestId('editor-status-bar')).toHaveTextContent('1 min read');
    expect(screen.getByTestId('editor-status-bar')).toHaveTextContent('Ln 1, Col 1');
    act(() => store.set({ line: 3, col: 5, selectionText: 'two three' }));
    expect(screen.getByTestId('status-words')).toHaveTextContent('2 of 4 words');
    expect(screen.getByTestId('editor-status-bar')).toHaveTextContent('Ln 3, Col 5');
  });
});
```

`components/editor/EditorRail.test.jsx`:
```jsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import EditorRail from './EditorRail';
import { createEditorCursorStore } from '../../utils/editorCursorStore';

vi.mock('../BacklinksPanel', () => ({
  default: ({ pageName }) => <div data-testid="backlinks-stub">backlinks:{pageName}</div>,
}));

const BODY = '# Title\n\n## Setup\n\ntext\n\n### Install\n\n##### Too deep\n';

function renderRail(props = {}) {
  const store = createEditorCursorStore();
  const onJump = vi.fn();
  const onToggle = vi.fn();
  render(<EditorRail body={BODY} pageName="P" isNew={false} cursorStore={store}
    open onToggle={onToggle} onJump={onJump} {...props} />);
  return { store, onJump, onToggle };
}

describe('EditorRail', () => {
  it('lists h1–h4 of the draft, indented by level, and jumps on click', () => {
    const { onJump } = renderRail();
    const items = screen.getAllByRole('listitem');
    expect(items.map((li) => li.textContent)).toEqual(['Title', 'Setup', 'Install']);
    expect(items.map((li) => li.dataset.level)).toEqual(['1', '2', '3']);
    fireEvent.click(screen.getByRole('button', { name: 'Install' }));
    expect(onJump).toHaveBeenCalledWith(7);
  });

  it('highlights the section at the editor top line', () => {
    const { store } = renderRail();
    act(() => store.set({ topLine: 5 }));
    expect(screen.getByRole('button', { name: 'Setup' })).toHaveAttribute('aria-current', 'true');
    expect(screen.getByRole('button', { name: 'Install' })).not.toHaveAttribute('aria-current');
  });

  it('shows empty states', () => {
    renderRail({ body: 'no headings', isNew: true });
    expect(screen.getByText('No headings yet')).toBeInTheDocument();
    expect(screen.getByText('No backlinks yet')).toBeInTheDocument();
    expect(screen.queryByTestId('backlinks-stub')).toBeNull();
  });

  it('renders backlinks for an existing page', () => {
    renderRail();
    expect(screen.getByTestId('backlinks-stub')).toHaveTextContent('backlinks:P');
  });

  it('collapsed: only the strip, whose button toggles', () => {
    const { onToggle } = renderRail({ open: false });
    expect(screen.queryByRole('listitem')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Show outline and backlinks' }));
    expect(onToggle).toHaveBeenCalled();
  });
});
```
The outline reads the debounced body, whose initial value is the first `body` — so these tests need no timer advance. Add one more test with fake timers: rerender with a new body, assert the outline is unchanged before 300 ms and updated after.

Run: expect FAIL.

- [ ] **Step 3: Implement**

`components/editor/EditorStatusBar.jsx`:
```jsx
import { useMemo } from 'react';
import { readingTime } from '../../utils/readingTime';
import { useEditorCursor } from '../../hooks/useEditorCursor';

const fmt = (n) => n.toLocaleString('en-US');

export default function EditorStatusBar({ body, cursorStore }) {
  const { line, col, selectionText } = useEditorCursor(cursorStore);
  const { words, minutes } = useMemo(() => readingTime(body), [body]);
  const selected = selectionText ? readingTime(selectionText).words : 0;
  return (
    <div className="editor-status-bar" data-testid="editor-status-bar">
      <span data-testid="status-words">{selected ? `${fmt(selected)} of ${fmt(words)} words` : `${fmt(words)} words`}</span>
      <span aria-hidden="true">·</span>
      <span>{minutes} min read</span>
      <span aria-hidden="true">·</span>
      <span>Ln {line}, Col {col}</span>
    </div>
  );
}
```

`components/editor/EditorRail.jsx`:
```jsx
import { useMemo } from 'react';
import BacklinksPanel from '../BacklinksPanel';
import { headingsFromMarkdown } from '../../utils/headings';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import { useEditorCursor } from '../../hooks/useEditorCursor';

const OUTLINE_DEBOUNCE_MS = 300;

export default function EditorRail({ body, pageName, isNew, cursorStore, open, onToggle, onJump }) {
  const debounced = useDebouncedValue(body, OUTLINE_DEBOUNCE_MS);
  const outline = useMemo(() => headingsFromMarkdown(debounced).filter((h) => h.level <= 4), [debounced]);
  const { topLine } = useEditorCursor(cursorStore);
  let active = -1;
  outline.forEach((h, i) => { if (h.line <= topLine) active = i; });

  if (!open) {
    return (
      <aside className="editor-rail collapsed" data-testid="editor-rail">
        <button type="button" className="editor-rail-toggle" aria-label="Show outline and backlinks" onClick={onToggle}>◂</button>
      </aside>
    );
  }
  return (
    <aside className="editor-rail" data-testid="editor-rail">
      <button type="button" className="editor-rail-toggle" aria-label="Hide side rail" onClick={onToggle}>▸</button>
      <section>
        <h4 className="editor-rail-heading">Outline</h4>
        {outline.length === 0 ? <p className="editor-rail-empty">No headings yet</p> : (
          <ul className="editor-rail-outline">
            {outline.map((h, i) => (
              <li key={`${h.line}-${h.text}`} data-level={h.level} style={{ paddingLeft: `${(h.level - 1) * 12}px` }}>
                <button type="button" aria-current={i === active ? 'true' : undefined} onClick={() => onJump(h.line)}>
                  {h.text || '(untitled)'}
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
      <section>
        <h4 className="editor-rail-heading">Backlinks</h4>
        {isNew ? <p className="editor-rail-empty">No backlinks yet</p>
          : <BacklinksPanel pageName={pageName} emptyText="No backlinks yet" />}
      </section>
    </aside>
  );
}
```

`BacklinksPanel`: add prop `emptyText` — where it currently `return null` for no backlinks, return `emptyText ? <p className="editor-rail-empty">{emptyText}</p> : null` (page-view behaviour unchanged; add one `BacklinksPanel.test.jsx` case for `emptyText`).

Run: expect PASS.

- [ ] **Step 4: CodeEditor `getCursor`** — add to the imperative handle (and extend the `CodeEditor.test.jsx` stub view with `doc.lineAt(pos)` and `sliceDoc(from, to)` backed by the textarea; test: caret at offset 8 of `"abc\ndefgh"` → `{ line: 2, col: 5, selectionText: '' }`, selection 0–3 → `selectionText: 'abc'`):

```js
    getCursor() {
      const view = viewRef.current;
      if (!view) return null;
      const { from, to, head } = view.state.selection.main;
      const lineObj = view.state.doc.lineAt(head);
      return { line: lineObj.number, col: head - lineObj.from + 1, selectionText: view.state.sliceDoc(from, to) };
    },
```

- [ ] **Step 5: PageEditor wiring**

```js
  const [cursorStore] = useState(createEditorCursorStore);
  const [railOpen, toggleRail] = useRailOpen();
  const handleViewChange = useCallback(() => {
    const editor = editorRef.current;
    const vp = editor?.getViewport();
    const cursor = editor?.getCursor?.();
    cursorStore.set({ ...(cursor || {}), ...(vp ? { topLine: vp.topLine } : {}) });
    syncPreview();
  }, [cursorStore, syncPreview]);
  const jumpToHeading = useCallback((line) => editorRef.current?.jumpToLineAligned(line, 0), []);
```
Pass `onViewChange={handleViewChange}` (was `syncPreview`). Add a toolbar button beside **Attach**: `<button className="btn btn-ghost" data-testid="editor-rail-toggle" onClick={toggleRail} aria-pressed={railOpen}>Outline</button>`. Wrap the existing `.editor-container` and add the rail + status bar:

```jsx
      <div className={`editor-layout${railOpen ? ' rail-open' : ''}`}>
        <div className="editor-container">{/* existing two panes, unchanged */}</div>
        <EditorRail body={body} pageName={name} isNew={isNew} cursorStore={cursorStore}
          open={railOpen} onToggle={toggleRail} onJump={jumpToHeading} />
      </div>
      <EditorStatusBar body={body} cursorStore={cursorStore} />
```
Add a PageEditor test: the rail and status bar render; the toolbar toggle flips `aria-pressed`; mock `./BacklinksPanel` in `PageEditor.test.jsx` (it calls `api.getBacklinks`, which the test's api mock lacks — add `getBacklinks: vi.fn(() => Promise.resolve({ backlinks: [] }))` to the api mock instead of mocking the component if simpler).

- [ ] **Step 6: Layout CSS** — append to `styles/globals.css`:

```css
.editor-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 28px;
  gap: var(--space-sm);
}
.editor-layout.rail-open { grid-template-columns: minmax(0, 1fr) 240px; }

.editor-rail {
  height: 75vh;
  overflow-y: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-elevated);
  padding: var(--space-sm);
  font-family: var(--font-ui);
  font-size: 0.85rem;
}
.editor-rail.collapsed { padding: var(--space-xs) 0; text-align: center; }
.editor-rail-toggle { background: none; border: none; cursor: pointer; color: var(--text-muted); }
.editor-rail-heading {
  margin: var(--space-sm) 0 var(--space-xs);
  font-size: 0.7rem; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase;
  color: var(--text-muted);
}
.editor-rail-outline { list-style: none; margin: 0; padding: 0; }
.editor-rail-outline button {
  width: 100%; text-align: left; background: none; border: none; padding: 2px var(--space-xs);
  border-radius: var(--radius-sm); color: var(--text); cursor: pointer;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.editor-rail-outline button:hover { background: var(--bg); }
.editor-rail-outline button[aria-current="true"] { color: var(--accent); font-weight: 600; }
.editor-rail-empty { color: var(--text-muted); margin: 0; }

.editor-status-bar {
  display: flex; gap: var(--space-sm); justify-content: flex-end;
  padding: var(--space-xs) var(--space-sm);
  font-family: var(--font-ui); font-size: 0.75rem; color: var(--text-muted);
}

@media (max-width: 1099px) {
  .editor-layout, .editor-layout.rail-open { grid-template-columns: minmax(0, 1fr); }
  .editor-rail {
    position: fixed; top: 0; right: 0; bottom: 0; width: min(280px, 85vw); height: auto;
    z-index: 20; box-shadow: var(--shadow-lg, 0 0 24px rgba(0, 0, 0, 0.2));
  }
  .editor-rail.collapsed { display: none; }
}
```
(If `--shadow-lg` is not defined in `globals.css`, keep the literal fallback as written.)

- [ ] **Step 7: Run the frontend suite + lint — expect PASS**

Run: `npx vitest run && npm run lint`

- [ ] **Step 8: Commit**

```bash
git add wikantik-frontend/src/utils/editorCursorStore.js wikantik-frontend/src/utils/editorCursorStore.test.js \
        wikantik-frontend/src/hooks/useEditorCursor.js wikantik-frontend/src/hooks/useDebouncedValue.js \
        wikantik-frontend/src/hooks/useRailOpen.js wikantik-frontend/src/hooks/useRailOpen.test.js \
        wikantik-frontend/src/components/editor/EditorRail.jsx wikantik-frontend/src/components/editor/EditorRail.test.jsx \
        wikantik-frontend/src/components/editor/EditorStatusBar.jsx wikantik-frontend/src/components/editor/EditorStatusBar.test.jsx \
        wikantik-frontend/src/components/BacklinksPanel.jsx wikantik-frontend/src/components/BacklinksPanel.test.jsx \
        wikantik-frontend/src/components/CodeEditor.jsx wikantik-frontend/src/components/CodeEditor.test.jsx \
        wikantik-frontend/src/components/PageEditor.jsx wikantik-frontend/src/components/PageEditor.test.jsx \
        wikantik-frontend/src/styles/globals.css
git commit -m "feat(editor): side rail with live outline and backlinks; word-count status bar" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg"
```

---

### Task 11: Full gate, manual check, docs

**Files:**
- Modify (if needed): `wikantik-*/pom.xml` coverage floors only if a module's measured coverage rose (never lower one).

- [ ] **Step 1: Canonical gate** (long — run detached and poll)

```bash
bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4
bin/agent-build.sh wait gate 540   # repeat until SUCCESS or FAILED
```
Expected: SUCCESS. On failure, read `bin/agent-build.sh tail gate 80`, fix at the root (systematic-debugging), re-run. A red gate is never deferred as pre-existing.

- [ ] **Step 2: Static gates**

```bash
mvn pmd:check -Pcomplexity-gate -q
bin/agent-build.sh start cov -- mvn clean install -Pcoverage -DskipITs
bin/agent-build.sh wait cov 540
(cd wikantik-frontend && npx vitest run --coverage && npm run lint)
```
Expected: no PMD violations, JaCoCo floors met, vitest coverage thresholds met, ESLint 0.

- [ ] **Step 3: Manual browser pass** against a local deploy (`mvn clean install -DskipTests -T 1C && bin/redeploy.sh`, log in with `test.properties` credentials). Verify in real CodeMirror (the unit tests stub it):
  1. paste a screenshot → placeholder, then `![pasted-…](pasted-….png)`, image renders in the preview;
  2. drag a PDF from the desktop → "Drop to upload" hint, then `[name](name.pdf)`; dragging an attachment row still inserts its markup;
  3. `[[mach` offers live results in server order; `[[SomePage#` offers headings; `](#` offers this page's headings; "Link to new page" appears for an unknown title;
  4. a link to a nonexistent page turns dashed/red in the preview within ~½ s; `[{TableOfContents}]()` shows a chip;
  5. a ```` ```java ```` block is highlighted in the editor, the preview, and the saved page view (light and dark theme);
  6. History → Restore on an old version → editor opens with the banner and change note; save creates a new version;
  7. rail outline click jumps; status bar counts update; rail state survives a reload; at < 1100 px the rail is a drawer.
  Report anything that does not behave as listed.

- [ ] **Step 4: Update the wiki design page** — set `EditorQualityOfLifeDesign` `status: active` and change its status line to "implemented on main <date> (not yet released)" via the admin MCP `update_page` tool (read first for `expectedContentHash`).

- [ ] **Step 5: Commit any coverage-floor raises** (only if made)

```bash
git add <each pom.xml changed>
git commit -m "build: raise coverage floors after editor QoL work" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg"
```
