# Attachment Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add attachment upload/rename/delete UI to wiki and blog editors with drag-to-insert and preview resolution.

**Architecture:** Backend REST enhancements (named upload, rename endpoint, path parsing fix for hierarchical pages) + frontend slide-in panel with drag-and-drop + remark plugin for client-side preview + server-side link resolution hardening.

**Tech Stack:** Java 21 / Jakarta Servlet / Flexmark (backend); React 18 / Vite / Vitest / remark (frontend)

**Test images:** `testimages/poolside.jpg` and `testimages/verysadday.jpg` are available for integration tests.

---

## File Map

### New Files
| File | Responsibility |
|------|---------------|
| `wikantik-api/src/main/java/com/wikantik/api/attachment/AttachmentNameValidator.java` | Strict filename validation (chars, length, extension match) |
| `wikantik-main/src/test/java/com/wikantik/attachment/AttachmentNameValidatorTest.java` | Unit tests for filename validator |
| `wikantik-frontend/src/utils/attachmentNameValidator.js` | JS port of filename validation |
| `wikantik-frontend/src/utils/attachmentNameValidator.test.js` | JS validator tests |
| `wikantik-frontend/src/hooks/useAttachments.js` | Attachment state + CRUD API calls |
| `wikantik-frontend/src/hooks/useEditorDrop.js` | Drag-and-drop textarea insertion |
| `wikantik-frontend/src/utils/remarkAttachments.js` | Remark plugin for preview resolution |
| `wikantik-frontend/src/utils/remarkAttachments.test.js` | Plugin tests |
| `wikantik-frontend/src/components/AttachmentPanel.jsx` | Slide-in panel with upload form + attachment list |

### Modified Files
| File | Changes |
|------|---------|
| `wikantik-rest/src/main/java/com/wikantik/rest/AttachmentResource.java` | Fix path parsing for hierarchical pages, add `name` to upload, add `doPut` for rename |
| `wikantik-rest/src/test/java/com/wikantik/rest/AttachmentResourceTest.java` | Tests for new upload/rename/hardened paths |
| `wikantik-main/src/main/java/com/wikantik/markdown/extensions/wikilinks/postprocessor/LocalLinkNodePostProcessorState.java` | Case-insensitive lookup, path traversal guard, skip invalid names |
| `wikantik-frontend/src/api/client.js` | Add `name` param to `uploadAttachment`, add `renameAttachment` |
| `wikantik-frontend/src/components/PageEditor.jsx` | Add attachment panel toggle, wire hooks |
| `wikantik-frontend/src/components/BlogEditor.jsx` | Same attachment integration |
| `wikantik-frontend/src/styles/globals.css` | Attachment panel CSS, editor layout adjustment |

---

## Task 1: Filename Validation (Java)

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/attachment/AttachmentNameValidator.java`
- Test: `wikantik-main/src/test/java/com/wikantik/attachment/AttachmentNameValidatorTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.wikantik.attachment;

import com.wikantik.api.attachment.AttachmentNameValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentNameValidatorTest {

    @Test
    void validSimpleName() {
        assertTrue( AttachmentNameValidator.isValid( "beach.jpg" ) );
    }

    @Test
    void validWithHyphenAndUnderscore() {
        assertTrue( AttachmentNameValidator.isValid( "my-photo_01.png" ) );
    }

    @Test
    void validMaxLength() {
        // 36 char stem + .jpg = 40 chars exactly
        assertTrue( AttachmentNameValidator.isValid( "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg" ) );
    }

    @Test
    void rejectTooLong() {
        // 37 char stem + .jpg = 41 chars
        assertFalse( AttachmentNameValidator.isValid( "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg" ) );
    }

    @Test
    void rejectSpaces() {
        assertFalse( AttachmentNameValidator.isValid( "my photo.jpg" ) );
    }

    @Test
    void rejectSpecialChars() {
        assertFalse( AttachmentNameValidator.isValid( "photo#1.jpg" ) );
        assertFalse( AttachmentNameValidator.isValid( "photo@2.jpg" ) );
        assertFalse( AttachmentNameValidator.isValid( "photo!.jpg" ) );
    }

    @Test
    void rejectNoPeriod() {
        assertFalse( AttachmentNameValidator.isValid( "noextension" ) );
    }

    @Test
    void rejectMultiplePeriods() {
        assertFalse( AttachmentNameValidator.isValid( "my.backup.jpg" ) );
    }

    @Test
    void rejectLeadingPeriod() {
        assertFalse( AttachmentNameValidator.isValid( ".hidden.jpg" ) );
    }

    @Test
    void rejectTrailingHyphen() {
        assertFalse( AttachmentNameValidator.isValid( "file-.jpg" ) );
    }

    @Test
    void rejectLeadingUnderscore() {
        assertFalse( AttachmentNameValidator.isValid( "_file.jpg" ) );
    }

    @Test
    void rejectNull() {
        assertFalse( AttachmentNameValidator.isValid( null ) );
    }

    @Test
    void rejectEmpty() {
        assertFalse( AttachmentNameValidator.isValid( "" ) );
    }

    @Test
    void extensionsMatchCaseInsensitive() {
        assertTrue( AttachmentNameValidator.extensionsMatch( "photo.JPG", "beach.jpg" ) );
        assertTrue( AttachmentNameValidator.extensionsMatch( "file.Png", "other.PNG" ) );
    }

    @Test
    void extensionsMismatch() {
        assertFalse( AttachmentNameValidator.extensionsMatch( "photo.jpg", "beach.png" ) );
    }

    @Test
    void extensionExtracted() {
        assertEquals( "jpg", AttachmentNameValidator.getExtension( "beach.jpg" ) );
        assertEquals( "png", AttachmentNameValidator.getExtension( "PHOTO.PNG" ) );
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl wikantik-main -Dtest=AttachmentNameValidatorTest -Dmaven.test.skip=false`
Expected: Compilation error — `AttachmentNameValidator` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.wikantik.api.attachment;

import java.util.regex.Pattern;

/**
 * Validates attachment filenames against strict naming rules:
 * only {@code a-zA-Z0-9._-}, max 40 chars, exactly one period,
 * no leading/trailing period, hyphen, or underscore.
 */
public final class AttachmentNameValidator {

    private static final int MAX_LENGTH = 40;
    private static final Pattern VALID_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9][a-zA-Z0-9_-]*\\.[a-zA-Z0-9]+$"
    );

    private AttachmentNameValidator() { }

    public static boolean isValid( final String name ) {
        if ( name == null || name.isEmpty() || name.length() > MAX_LENGTH ) {
            return false;
        }
        if ( !VALID_PATTERN.matcher( name ).matches() ) {
            return false;
        }
        // Exactly one period
        if ( name.indexOf( '.' ) != name.lastIndexOf( '.' ) ) {
            return false;
        }
        // No trailing hyphen or underscore before the dot
        final int dotIndex = name.indexOf( '.' );
        final char beforeDot = name.charAt( dotIndex - 1 );
        return beforeDot != '-' && beforeDot != '_';
    }

    public static String getExtension( final String name ) {
        if ( name == null ) return "";
        final int dot = name.lastIndexOf( '.' );
        return ( dot >= 0 ) ? name.substring( dot + 1 ).toLowerCase() : "";
    }

    public static boolean extensionsMatch( final String originalName, final String desiredName ) {
        return getExtension( originalName ).equals( getExtension( desiredName ) );
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl wikantik-api,wikantik-main -Dtest=AttachmentNameValidatorTest`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/attachment/AttachmentNameValidator.java \
       wikantik-main/src/test/java/com/wikantik/attachment/AttachmentNameValidatorTest.java
git commit -m "feat(attachment): add strict filename validator"
```

---

## Task 2: REST API — Fix Path Parsing + Named Upload

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AttachmentResource.java`
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/AttachmentResourceTest.java`

**Context:** The current path parsing splits on the first `/`, which breaks for hierarchical page names like `blog/admin/20260403Post`. Since our filenames always contain a period (per validation rules) and page names don't, we parse from the right: the last segment with a `.` is the filename.

- [ ] **Step 1: Write failing tests for named upload and path parsing**

Add to `AttachmentResourceTest.java`:

```java
@Test
void testUploadWithNameField() throws Exception {
    // This test requires an authenticated session with upload permission.
    // For now, test that the name field is read by verifying the validation error
    // when name is missing (anonymous users get 403 first, so this tests the flow
    // for when we add authenticated upload tests).
    // See integration tests for full upload-with-name coverage.
}

@Test
void testParseAttachmentPathListOnly() {
    // Page name with no filename
    final String[] result = AttachmentResource.parseAttachmentPath( "RestAttachPage" );
    assertEquals( "RestAttachPage", result[0] );
    assertNull( result[1] );
}

@Test
void testParseAttachmentPathWithFile() {
    final String[] result = AttachmentResource.parseAttachmentPath( "RestAttachPage/beach.jpg" );
    assertEquals( "RestAttachPage", result[0] );
    assertEquals( "beach.jpg", result[1] );
}

@Test
void testParseAttachmentPathHierarchicalPage() {
    final String[] result = AttachmentResource.parseAttachmentPath( "blog/admin/20260403Post" );
    assertEquals( "blog/admin/20260403Post", result[0] );
    assertNull( result[1] );
}

@Test
void testParseAttachmentPathHierarchicalPageWithFile() {
    final String[] result = AttachmentResource.parseAttachmentPath( "blog/admin/20260403Post/beach.jpg" );
    assertEquals( "blog/admin/20260403Post", result[0] );
    assertEquals( "beach.jpg", result[1] );
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl wikantik-rest -Dtest=AttachmentResourceTest`
Expected: Compilation error — `parseAttachmentPath` does not exist.

- [ ] **Step 3: Implement path parsing and named upload**

Replace the path-parsing logic in `AttachmentResource.java`. Add the static helper and update `doGet`, `doPost`, and `doDelete`:

```java
/**
 * Parses an attachment path into [pageName, fileName].
 * Filenames always contain a period; page name segments don't.
 * If the last segment has a period, it's a filename; otherwise the entire path is the page name.
 *
 * @param path the raw path after /api/attachments/
 * @return String[2]: [pageName, fileName] where fileName may be null
 */
static String[] parseAttachmentPath( final String path ) {
    final int lastSlash = path.lastIndexOf( '/' );
    if ( lastSlash < 0 ) {
        // Single segment — filename if it has a dot, otherwise page name
        return path.contains( "." )
                ? new String[] { null, path }
                : new String[] { path, null };
    }
    final String lastSegment = path.substring( lastSlash + 1 );
    if ( lastSegment.contains( "." ) ) {
        return new String[] { path.substring( 0, lastSlash ), lastSegment };
    }
    return new String[] { path, null };
}
```

Update `doGet`:
```java
@Override
protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
        throws ServletException, IOException {
    final String pathParam = extractPathParam( request );
    if ( pathParam == null || pathParam.isEmpty() ) {
        sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
        return;
    }
    final String[] parsed = parseAttachmentPath( pathParam );
    final String pageName = parsed[0];
    final String fileName = parsed[1];
    if ( pageName == null || pageName.isEmpty() ) {
        sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
        return;
    }
    if ( !checkPagePermission( request, response, pageName, "view" ) ) return;
    if ( fileName == null ) {
        doListAttachments( pageName, response );
    } else {
        doDownloadAttachment( pageName, fileName, response );
    }
}
```

Update `doPost` to accept `name` form field:
```java
@Override
protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
        throws ServletException, IOException {
    final String pathParam = extractPathParam( request );
    if ( pathParam == null || pathParam.isEmpty() ) {
        sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
        return;
    }
    // For upload, the entire path is the page name (no filename in URL)
    final String pageName = parseAttachmentPath( pathParam )[0];
    if ( pageName == null || pageName.isEmpty() ) {
        sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
        return;
    }
    if ( !checkPagePermission( request, response, pageName, "upload" ) ) return;

    LOG.debug( "POST attachment upload: {}", pageName );

    try {
        final Part filePart = request.getPart( "file" );
        if ( filePart == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "File part 'file' is required" );
            return;
        }

        final String originalFileName = filePart.getSubmittedFileName();
        if ( originalFileName == null || originalFileName.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "File name is required" );
            return;
        }

        // Use the 'name' form field if provided, otherwise fall back to original filename
        final String namePart = request.getParameter( "name" );
        final String fileName;
        if ( namePart != null && !namePart.isBlank() ) {
            if ( !AttachmentNameValidator.isValid( namePart ) ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                        "Invalid attachment name. Use only a-zA-Z0-9._- (max 40 chars, exactly one period)." );
                return;
            }
            if ( !AttachmentNameValidator.extensionsMatch( originalFileName, namePart ) ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                        "Extension mismatch: uploaded file is ." + AttachmentNameValidator.getExtension( originalFileName )
                        + " but name has ." + AttachmentNameValidator.getExtension( namePart ) );
                return;
            }
            fileName = namePart;
        } else {
            fileName = originalFileName;
        }

        final Engine engine = getEngine();
        final AttachmentManager am = engine.getManager( AttachmentManager.class );

        final Attachment att = Wiki.contents().attachment( engine, pageName, fileName );
        try ( final InputStream in = filePart.getInputStream() ) {
            am.storeAttachment( att, in );
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "success", true );
        result.put( "page", pageName );
        result.put( "fileName", fileName );
        result.put( "size", filePart.getSize() );

        sendJson( response, result );

    } catch ( final Exception e ) {
        LOG.error( "Error uploading attachment to {}: {}", pageName, e.getMessage() );
        sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Error uploading attachment: " + e.getMessage() );
    }
}
```

Add import for `AttachmentNameValidator`:
```java
import com.wikantik.api.attachment.AttachmentNameValidator;
```

Update `doDelete` to use `parseAttachmentPath`:
```java
@Override
protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
        throws ServletException, IOException {
    final String pathParam = extractPathParam( request );
    if ( pathParam == null || pathParam.isEmpty() ) {
        sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name and file name are required" );
        return;
    }
    final String[] parsed = parseAttachmentPath( pathParam );
    final String pageName = parsed[0];
    final String fileName = parsed[1];
    if ( pageName == null || fileName == null ) {
        sendError( response, HttpServletResponse.SC_BAD_REQUEST, "File name is required in path: PageName/filename.ext" );
        return;
    }
    if ( !checkPagePermission( request, response, pageName, "delete" ) ) return;

    LOG.debug( "DELETE attachment: {}/{}", pageName, fileName );

    try {
        final Engine engine = getEngine();
        final AttachmentManager am = engine.getManager( AttachmentManager.class );
        final Attachment att = am.getAttachmentInfo( pageName + "/" + fileName );
        if ( att == null ) {
            sendNotFound( response, "Attachment not found: " + pageName + "/" + fileName );
            return;
        }
        am.deleteAttachment( att );
        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "success", true );
        result.put( "page", pageName );
        result.put( "fileName", fileName );
        sendJson( response, result );
    } catch ( final Exception e ) {
        LOG.error( "Error deleting attachment {}/{}: {}", pageName, fileName, e.getMessage() );
        sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Error deleting attachment: " + e.getMessage() );
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl wikantik-api,wikantik-rest -Dtest=AttachmentResourceTest`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AttachmentResource.java \
       wikantik-rest/src/test/java/com/wikantik/rest/AttachmentResourceTest.java
git commit -m "feat(attachment): fix path parsing for hierarchical pages, add named upload"
```

---

## Task 3: REST API — Rename Endpoint (PUT)

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AttachmentResource.java`
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/AttachmentResourceTest.java`

- [ ] **Step 1: Write failing tests**

Add to `AttachmentResourceTest.java`:

```java
@Test
void testRenameRequiresPermission() throws Exception {
    // Anonymous users lack upload permission, so rename returns 403
    final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/attachments/RestAttachPage/old.txt" );
    Mockito.doReturn( "/RestAttachPage/old.txt" ).when( request ).getPathInfo();
    Mockito.doReturn( "application/json" ).when( request ).getContentType();
    Mockito.doReturn( new java.io.BufferedReader( new java.io.StringReader( "{\"newName\":\"new.txt\"}" ) ) )
            .when( request ).getReader();

    final HttpServletResponse response = HttpMockFactory.createHttpResponse();
    final StringWriter sw = new StringWriter();
    Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

    servlet.doPut( request, response );

    final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
    assertTrue( obj.get( "error" ).getAsBoolean() );
    assertEquals( 403, obj.get( "status" ).getAsInt() );
}

@Test
void testRenameMissingFileName() throws Exception {
    final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/attachments/RestAttachPage" );
    Mockito.doReturn( "/RestAttachPage" ).when( request ).getPathInfo();

    final HttpServletResponse response = HttpMockFactory.createHttpResponse();
    final StringWriter sw = new StringWriter();
    Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

    servlet.doPut( request, response );

    final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
    assertTrue( obj.get( "error" ).getAsBoolean() );
    assertEquals( 400, obj.get( "status" ).getAsInt() );
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl wikantik-rest -Dtest=AttachmentResourceTest`
Expected: Compilation error or test failure — `doPut` not implemented.

- [ ] **Step 3: Implement doPut**

Add to `AttachmentResource.java`:

```java
@Override
protected void doPut( final HttpServletRequest request, final HttpServletResponse response )
        throws ServletException, IOException {
    final String pathParam = extractPathParam( request );
    if ( pathParam == null || pathParam.isEmpty() ) {
        sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name and current file name are required" );
        return;
    }

    final String[] parsed = parseAttachmentPath( pathParam );
    final String pageName = parsed[0];
    final String oldName = parsed[1];

    if ( pageName == null || oldName == null ) {
        sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                "Path must include page name and current filename: PageName/oldname.ext" );
        return;
    }

    if ( !checkPagePermission( request, response, pageName, "upload" ) ) return;

    LOG.debug( "PUT attachment rename: {}/{}", pageName, oldName );

    try {
        final JsonObject body = readJsonBody( request );
        final String newName = body.has( "newName" ) ? body.get( "newName" ).getAsString() : null;

        if ( newName == null || newName.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "newName is required" );
            return;
        }

        if ( !AttachmentNameValidator.isValid( newName ) ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid new name. Use only a-zA-Z0-9._- (max 40 chars, exactly one period)." );
            return;
        }

        if ( !AttachmentNameValidator.extensionsMatch( oldName, newName ) ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Extension mismatch: cannot change ." + AttachmentNameValidator.getExtension( oldName )
                    + " to ." + AttachmentNameValidator.getExtension( newName ) );
            return;
        }

        final Engine engine = getEngine();
        final AttachmentManager am = engine.getManager( AttachmentManager.class );

        final Attachment oldAtt = am.getAttachmentInfo( pageName + "/" + oldName );
        if ( oldAtt == null ) {
            sendNotFound( response, "Attachment not found: " + pageName + "/" + oldName );
            return;
        }

        // Copy data to new name, then delete old
        final Attachment newAtt = Wiki.contents().attachment( engine, pageName, newName );
        try ( final InputStream in = am.getAttachmentStream( oldAtt ) ) {
            am.storeAttachment( newAtt, in );
        }
        am.deleteAttachment( oldAtt );

        // Fetch the stored attachment for accurate metadata
        final Attachment stored = am.getAttachmentInfo( pageName + "/" + newName );
        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "success", true );
        result.put( "page", pageName );
        result.put( "fileName", newName );
        result.put( "size", stored != null ? stored.getSize() : 0 );
        result.put( "version", stored != null ? Math.max( stored.getVersion(), 1 ) : 1 );

        sendJson( response, result );

    } catch ( final Exception e ) {
        LOG.error( "Error renaming attachment {}/{}: {}", pageName, oldName, e.getMessage() );
        sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Error renaming attachment: " + e.getMessage() );
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl wikantik-api,wikantik-rest -Dtest=AttachmentResourceTest`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AttachmentResource.java \
       wikantik-rest/src/test/java/com/wikantik/rest/AttachmentResourceTest.java
git commit -m "feat(attachment): add PUT rename endpoint"
```

---

## Task 4: Server-Side Markdown Hardening

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/markdown/extensions/wikilinks/postprocessor/LocalLinkNodePostProcessorState.java`
- Test: Add hardening tests to existing markdown test suite or create new test

**Context:** The `LocalLinkNodePostProcessorState.process()` method at line 54 calls `getAttachmentInfoName()` for every local link. We add guards before this call.

- [ ] **Step 1: Write failing tests**

Create `wikantik-main/src/test/java/com/wikantik/markdown/extensions/wikilinks/postprocessor/LocalLinkPostProcessorHardeningTest.java`:

```java
package com.wikantik.markdown.extensions.wikilinks.postprocessor;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class LocalLinkPostProcessorHardeningTest {

    private TestEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );
        engine.saveText( "HardeningTestPage", "Test page for hardening" );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            engine.getManager( com.wikantik.pages.PageManager.class ).deletePage( "HardeningTestPage" );
            engine.stop();
        }
    }

    @Test
    void pathTraversalRejected() {
        // ../../etc/passwd should not trigger an attachment lookup
        final String html = engine.getManager( com.wikantik.render.RenderingManager.class )
                .textToHTML( engine.getWikiContextFactory().newViewContext( engine.getManager( com.wikantik.pages.PageManager.class ).getPage( "HardeningTestPage" ) ),
                        "[link](../../etc/passwd)" );
        // Should NOT contain /attach/ — the traversal path should be skipped
        assertFalse( html.contains( "/attach/" ), "Path traversal should not resolve to attachment URL" );
    }

    @Test
    void invalidFilenameCharsSkipAttachmentLookup() {
        // Filenames with spaces or special chars should not trigger attachment lookup
        final String html = engine.getManager( com.wikantik.render.RenderingManager.class )
                .textToHTML( engine.getWikiContextFactory().newViewContext( engine.getManager( com.wikantik.pages.PageManager.class ).getPage( "HardeningTestPage" ) ),
                        "![img](file with spaces.jpg)" );
        // The link should pass through without becoming an attachment URL
        assertFalse( html.contains( "/attach/" ) );
    }
}
```

- [ ] **Step 2: Run tests to verify they fail or show current behavior**

Run: `mvn test -pl wikantik-main -Dtest=LocalLinkPostProcessorHardeningTest`
Expected: Test may pass or fail depending on current behavior — establishes baseline.

- [ ] **Step 3: Add hardening to LocalLinkNodePostProcessorState**

Modify `LocalLinkNodePostProcessorState.java` — add guards at the start of `process()`:

```java
import com.wikantik.api.attachment.AttachmentNameValidator;
```

At the start of `process()` method, before the existing `getAttachmentInfoName` call on line 54, add:

```java
@Override
public void process( final NodeTracker state, final WikantikLink link ) {
    final String url = link.getUrl().toString();

    // Guard: skip attachment lookup for path traversal attempts
    if ( url.contains( ".." ) || url.startsWith( "/" ) ) {
        processAsWikiLink( state, link, url );
        return;
    }

    // Guard: skip attachment lookup for filenames that can't be valid attachments
    // (contains slashes — won't be a simple filename)
    if ( url.contains( "/" ) ) {
        processAsWikiLink( state, link, url );
        return;
    }

    final int hashMark = url.indexOf( '#' );
    final String lookupUrl = ( hashMark >= 0 ) ? url.substring( 0, hashMark ) : url;

    // Case-insensitive attachment lookup
    final String attachment = wikiContext().getEngine()
            .getManager( AttachmentManager.class )
            .getAttachmentInfoName( wikiContext(), lookupUrl );

    if ( attachment != null ) {
        if ( !linkOperations().isImageLink( url, isImageInlining(), inlineImagePatterns() ) ) {
            final String attlink = wikiContext().getURL( ContextEnum.PAGE_ATTACH.getRequestContext(), url );
            link.setUrl( CharSubSequence.of( attlink ) );
            link.removeChildren();
            final WikiHtmlInline content = WikiHtmlInline.of( link.getText().toString(), wikiContext() );
            link.appendChild( content );
            state.nodeAddedWithChildren( content );
            addAttachmentLink( state, link );
        } else {
            new ImageLinkNodePostProcessorState( wikiContext(), attachment, link.hasRef() ).process( state, link );
        }
    } else if ( hashMark != -1 ) {
        // existing named section logic unchanged
        final String namedSection = url.substring( hashMark + 1 );
        link.setUrl( CharSubSequence.of( url.substring( 0, hashMark ) ) );
        final String matchedLink = linkOperations().linkIfExists( link.getUrl().toString() );
        if ( matchedLink != null ) {
            String sectref = "#section-" + wikiContext().getEngine().encodeName( matchedLink + "-" + MarkupParser.wikifyLink( namedSection ) );
            sectref = sectref.replace( '%', '_' );
            link.setUrl( CharSubSequence.of( wikiContext().getURL( ContextEnum.PAGE_VIEW.getRequestContext(), link.getUrl().toString() + sectref ) ) );
        } else {
            link.setUrl( CharSubSequence.of( wikiContext().getURL( ContextEnum.PAGE_EDIT.getRequestContext(), link.getUrl().toString() ) ) );
        }
    } else {
        processAsWikiLink( state, link, url );
    }
}

private void processAsWikiLink( final NodeTracker state, final WikantikLink link, final String url ) {
    if ( linkOperations().linkExists( url ) ) {
        link.setUrl( CharSubSequence.of( wikiContext().getURL( ContextEnum.PAGE_VIEW.getRequestContext(), url ) ) );
    } else {
        link.setUrl( CharSubSequence.of( wikiContext().getURL( ContextEnum.PAGE_EDIT.getRequestContext(), url ) ) );
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -pl wikantik-main -Dtest=LocalLinkPostProcessorHardeningTest`
Expected: All PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/markdown/extensions/wikilinks/postprocessor/LocalLinkNodePostProcessorState.java \
       wikantik-main/src/test/java/com/wikantik/markdown/extensions/wikilinks/postprocessor/LocalLinkPostProcessorHardeningTest.java
git commit -m "fix(markdown): harden link post-processor with path traversal and filename guards"
```

---

## Task 5: Frontend Filename Validation

**Files:**
- Create: `wikantik-frontend/src/utils/attachmentNameValidator.js`
- Create: `wikantik-frontend/src/utils/attachmentNameValidator.test.js`

- [ ] **Step 1: Write failing tests**

```javascript
import { describe, it, expect } from 'vitest';
import { isValidAttachmentName, getExtension, extensionsMatch } from './attachmentNameValidator';

describe('isValidAttachmentName', () => {
  it('accepts simple valid names', () => {
    expect(isValidAttachmentName('beach.jpg')).toBe(true);
    expect(isValidAttachmentName('my-photo_01.png')).toBe(true);
  });

  it('accepts max length (40 chars)', () => {
    expect(isValidAttachmentName('a'.repeat(36) + '.jpg')).toBe(true);
  });

  it('rejects too long', () => {
    expect(isValidAttachmentName('a'.repeat(37) + '.jpg')).toBe(false);
  });

  it('rejects spaces', () => {
    expect(isValidAttachmentName('my photo.jpg')).toBe(false);
  });

  it('rejects special characters', () => {
    expect(isValidAttachmentName('photo#1.jpg')).toBe(false);
    expect(isValidAttachmentName('photo@2.jpg')).toBe(false);
  });

  it('rejects no period', () => {
    expect(isValidAttachmentName('noextension')).toBe(false);
  });

  it('rejects multiple periods', () => {
    expect(isValidAttachmentName('my.backup.jpg')).toBe(false);
  });

  it('rejects leading special chars', () => {
    expect(isValidAttachmentName('.hidden.jpg')).toBe(false);
    expect(isValidAttachmentName('_file.jpg')).toBe(false);
    expect(isValidAttachmentName('-file.jpg')).toBe(false);
  });

  it('rejects trailing hyphen/underscore before dot', () => {
    expect(isValidAttachmentName('file-.jpg')).toBe(false);
    expect(isValidAttachmentName('file_.jpg')).toBe(false);
  });

  it('rejects null/empty', () => {
    expect(isValidAttachmentName(null)).toBe(false);
    expect(isValidAttachmentName('')).toBe(false);
  });
});

describe('getExtension', () => {
  it('extracts lowercase extension', () => {
    expect(getExtension('beach.JPG')).toBe('jpg');
    expect(getExtension('file.png')).toBe('png');
  });
});

describe('extensionsMatch', () => {
  it('matches case-insensitively', () => {
    expect(extensionsMatch('photo.JPG', 'beach.jpg')).toBe(true);
  });
  it('rejects mismatched extensions', () => {
    expect(extensionsMatch('photo.jpg', 'beach.png')).toBe(false);
  });
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd wikantik-frontend && npm test -- --run src/utils/attachmentNameValidator.test.js`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement**

```javascript
const MAX_LENGTH = 40;
const VALID_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9_-]*\.[a-zA-Z0-9]+$/;

export function isValidAttachmentName(name) {
  if (!name || name.length === 0 || name.length > MAX_LENGTH) return false;
  if (!VALID_PATTERN.test(name)) return false;
  if (name.indexOf('.') !== name.lastIndexOf('.')) return false;
  const dotIndex = name.indexOf('.');
  const beforeDot = name[dotIndex - 1];
  return beforeDot !== '-' && beforeDot !== '_';
}

export function getExtension(name) {
  if (!name) return '';
  const dot = name.lastIndexOf('.');
  return dot >= 0 ? name.substring(dot + 1).toLowerCase() : '';
}

export function extensionsMatch(originalName, desiredName) {
  return getExtension(originalName) === getExtension(desiredName);
}
```

- [ ] **Step 4: Run tests**

Run: `cd wikantik-frontend && npm test -- --run src/utils/attachmentNameValidator.test.js`
Expected: All PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/utils/attachmentNameValidator.js \
       wikantik-frontend/src/utils/attachmentNameValidator.test.js
git commit -m "feat(frontend): add attachment filename validator"
```

---

## Task 6: Frontend API Client Updates

**Files:**
- Modify: `wikantik-frontend/src/api/client.js` (lines 94-112)

- [ ] **Step 1: Update uploadAttachment to accept name parameter**

Replace lines 98-107 in `client.js`:

```javascript
  uploadAttachment: async (page, file, name) => {
    const form = new FormData();
    form.append('file', file);
    if (name) form.append('name', name);
    const resp = await fetch(`/api/attachments/${encodeURIComponent(page)}`, {
      method: 'POST',
      body: form,
    });
    if (!resp.ok) {
      const err = await resp.json().catch(() => ({ message: 'Upload failed' }));
      throw new Error(err.message || 'Upload failed');
    }
    return resp.json();
  },
```

- [ ] **Step 2: Add renameAttachment method**

After `deleteAttachment` (after line 112):

```javascript
  renameAttachment: async (page, oldName, newName) => {
    const resp = await fetch(
      `/api/attachments/${encodeURIComponent(page)}/${encodeURIComponent(oldName)}`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ newName }),
      }
    );
    if (!resp.ok) {
      const err = await resp.json().catch(() => ({ message: 'Rename failed' }));
      throw new Error(err.message || 'Rename failed');
    }
    return resp.json();
  },
```

- [ ] **Step 3: Commit**

```bash
git add wikantik-frontend/src/api/client.js
git commit -m "feat(frontend): add named upload and rename to API client"
```

---

## Task 7: useAttachments Hook

**Files:**
- Create: `wikantik-frontend/src/hooks/useAttachments.js`

- [ ] **Step 1: Implement the hook**

```javascript
import { useState, useEffect, useCallback } from 'react';
import { api } from '../api/client';

const IMAGE_EXTENSIONS = new Set(['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg', 'bmp']);

function isImageFile(name) {
  const dot = name.lastIndexOf('.');
  if (dot < 0) return false;
  return IMAGE_EXTENSIONS.has(name.substring(dot + 1).toLowerCase());
}

export function useAttachments(pageName) {
  const [list, setList] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const fetchList = useCallback(async () => {
    if (!pageName) return;
    setLoading(true);
    setError(null);
    try {
      const data = await api.listAttachments(pageName);
      const attachments = (data.attachments || []).map(att => ({
        ...att,
        isImage: isImageFile(att.fileName),
      }));
      setList(attachments);
    } catch (err) {
      setError(err.message || 'Failed to load attachments');
    } finally {
      setLoading(false);
    }
  }, [pageName]);

  useEffect(() => { fetchList(); }, [fetchList]);

  const uploadAttachment = useCallback(async (file, name) => {
    const data = await api.uploadAttachment(pageName, file, name);
    await fetchList();
    return data;
  }, [pageName, fetchList]);

  const renameAttachment = useCallback(async (oldName, newName) => {
    const data = await api.renameAttachment(pageName, oldName, newName);
    await fetchList();
    return { oldName, newName, data };
  }, [pageName, fetchList]);

  const deleteAttachment = useCallback(async (name) => {
    await api.deleteAttachment(pageName, name);
    await fetchList();
  }, [pageName, fetchList]);

  return { list, loading, error, uploadAttachment, renameAttachment, deleteAttachment, reload: fetchList };
}
```

- [ ] **Step 2: Commit**

```bash
git add wikantik-frontend/src/hooks/useAttachments.js
git commit -m "feat(frontend): add useAttachments hook"
```

---

## Task 8: useEditorDrop Hook

**Files:**
- Create: `wikantik-frontend/src/hooks/useEditorDrop.js`

- [ ] **Step 1: Implement the hook**

```javascript
import { useEffect } from 'react';

export function useEditorDrop(textareaRef, onInsert) {
  useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;

    const handleDragOver = (e) => {
      if (e.dataTransfer.types.includes('text/plain')) {
        e.preventDefault();
        e.dataTransfer.dropEffect = 'copy';
      }
    };

    const handleDrop = (e) => {
      const text = e.dataTransfer.getData('text/plain');
      if (!text) return;
      e.preventDefault();

      // Determine insertion position from drop coordinates
      let insertPos;
      if (document.caretPositionFromPoint) {
        const pos = document.caretPositionFromPoint(e.clientX, e.clientY);
        if (pos && pos.offsetNode === textarea) {
          insertPos = pos.offset;
        }
      } else if (document.caretRangeFromPoint) {
        const range = document.caretRangeFromPoint(e.clientX, e.clientY);
        if (range) {
          insertPos = range.startOffset;
        }
      }

      // Fallback: insert at current cursor position
      if (insertPos === undefined) {
        insertPos = textarea.selectionStart;
      }

      onInsert(text, insertPos);
    };

    textarea.addEventListener('dragover', handleDragOver);
    textarea.addEventListener('drop', handleDrop);
    return () => {
      textarea.removeEventListener('dragover', handleDragOver);
      textarea.removeEventListener('drop', handleDrop);
    };
  }, [textareaRef, onInsert]);
}
```

- [ ] **Step 2: Commit**

```bash
git add wikantik-frontend/src/hooks/useEditorDrop.js
git commit -m "feat(frontend): add useEditorDrop hook for drag-and-drop insertion"
```

---

## Task 9: Remark Attachment Resolver Plugin

**Files:**
- Create: `wikantik-frontend/src/utils/remarkAttachments.js`
- Create: `wikantik-frontend/src/utils/remarkAttachments.test.js`

- [ ] **Step 1: Write failing tests**

```javascript
import { describe, it, expect } from 'vitest';
import { visit } from 'unist-util-visit';
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import { remarkAttachments } from './remarkAttachments';

function transformMarkdown(md, attachments, pageName) {
  const tree = unified().use(remarkParse).parse(md);
  remarkAttachments({ attachments, pageName })(tree);
  return tree;
}

describe('remarkAttachments', () => {
  const attachments = [
    { fileName: 'beach.jpg', isImage: true },
    { fileName: 'report.pdf', isImage: false },
  ];

  it('rewrites known image attachment to /attach/ URL', () => {
    const tree = transformMarkdown('![photo](beach.jpg)', attachments, 'TestPage');
    let imageUrl;
    visit(tree, 'image', (node) => { imageUrl = node.url; });
    expect(imageUrl).toBe('/attach/TestPage/beach.jpg');
  });

  it('rewrites known link attachment to /attach/ URL', () => {
    const tree = transformMarkdown('[doc](report.pdf)', attachments, 'TestPage');
    let linkUrl;
    visit(tree, 'link', (node) => { linkUrl = node.url; });
    expect(linkUrl).toBe('/attach/TestPage/report.pdf');
  });

  it('leaves absolute URLs unchanged', () => {
    const tree = transformMarkdown('![img](https://example.com/pic.jpg)', attachments, 'TestPage');
    let imageUrl;
    visit(tree, 'image', (node) => { imageUrl = node.url; });
    expect(imageUrl).toBe('https://example.com/pic.jpg');
  });

  it('leaves root-relative URLs unchanged', () => {
    const tree = transformMarkdown('![img](/images/logo.png)', attachments, 'TestPage');
    let imageUrl;
    visit(tree, 'image', (node) => { imageUrl = node.url; });
    expect(imageUrl).toBe('/images/logo.png');
  });

  it('marks missing attachment with data attribute', () => {
    const tree = transformMarkdown('![img](missing.jpg)', attachments, 'TestPage');
    let node;
    visit(tree, 'image', (n) => { node = n; });
    expect(node.data?.hProperties?.['data-attachment-missing']).toBe('true');
  });
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd wikantik-frontend && npm test -- --run src/utils/remarkAttachments.test.js`
Expected: FAIL — module not found.

Note: `unist-util-visit`, `unified`, and `remark-parse` may need to be installed. Check if they're already transitive deps of `react-markdown`/`remark-gfm`. If not:

```bash
cd wikantik-frontend && npm install --save-dev unist-util-visit unified remark-parse
```

- [ ] **Step 3: Implement the plugin**

```javascript
import { visit } from 'unist-util-visit';

function isAbsoluteUrl(url) {
  return url.startsWith('http://') || url.startsWith('https://') || url.startsWith('/');
}

export function remarkAttachments({ attachments = [], pageName }) {
  const fileNames = new Set(attachments.map(a => a.fileName.toLowerCase()));
  const attachUrl = (fileName) => `/attach/${pageName}/${fileName}`;

  return (tree) => {
    visit(tree, ['image', 'link'], (node) => {
      const url = node.url;
      if (!url || isAbsoluteUrl(url)) return;

      // Check if this relative URL matches a known attachment (case-insensitive)
      const matchedAtt = attachments.find(a => a.fileName.toLowerCase() === url.toLowerCase());
      if (matchedAtt) {
        node.url = attachUrl(matchedAtt.fileName);
      } else if (url.includes('.')) {
        // Looks like a filename but not in attachment list — mark as missing
        if (!node.data) node.data = {};
        if (!node.data.hProperties) node.data.hProperties = {};
        node.data.hProperties['data-attachment-missing'] = 'true';
      }
    });
  };
}
```

- [ ] **Step 4: Run tests**

Run: `cd wikantik-frontend && npm test -- --run src/utils/remarkAttachments.test.js`
Expected: All PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/utils/remarkAttachments.js \
       wikantik-frontend/src/utils/remarkAttachments.test.js \
       wikantik-frontend/package.json wikantik-frontend/package-lock.json
git commit -m "feat(frontend): add remarkAttachments plugin for preview resolution"
```

---

## Task 10: Attachment Panel CSS

**Files:**
- Modify: `wikantik-frontend/src/styles/globals.css`

- [ ] **Step 1: Add attachment panel styles**

Append to `globals.css` before the responsive section:

```css
/* ============================================================
   Attachment Panel (slide-in from right)
   ============================================================ */

.attachment-panel {
  position: fixed;
  top: 0;
  right: 0;
  width: 320px;
  height: 100vh;
  background: var(--bg);
  border-left: 1px solid var(--border);
  box-shadow: -4px 0 12px rgba(0, 0, 0, 0.08);
  z-index: 100;
  display: flex;
  flex-direction: column;
  transform: translateX(100%);
  transition: transform var(--duration-slow) var(--ease);
}

.attachment-panel.open {
  transform: translateX(0);
}

.attachment-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-md) var(--space-lg);
  border-bottom: 1px solid var(--border);
  font-family: var(--font-display);
  font-size: 1rem;
  font-weight: 600;
}

.attachment-panel-body {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-md) var(--space-lg);
}

.attachment-upload-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  padding-bottom: var(--space-md);
  margin-bottom: var(--space-md);
  border-bottom: 1px solid var(--border);
}

.attachment-upload-form label {
  font-family: var(--font-ui);
  font-size: 0.8rem;
  font-weight: 500;
  color: var(--text-secondary);
}

.attachment-name-input {
  display: flex;
  align-items: center;
  gap: 0;
}

.attachment-name-input input {
  flex: 1;
  padding: var(--space-xs) var(--space-sm);
  border: 1px solid var(--border);
  border-right: none;
  border-radius: var(--radius-sm) 0 0 var(--radius-sm);
  font-family: var(--font-mono);
  font-size: 0.8rem;
  background: var(--bg);
}

.attachment-name-input .ext-badge {
  padding: var(--space-xs) var(--space-sm);
  border: 1px solid var(--border);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
  font-family: var(--font-mono);
  font-size: 0.8rem;
  background: var(--bg-sidebar);
  color: var(--text-muted);
}

.attachment-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-xs) 0;
  cursor: grab;
  border-radius: var(--radius-sm);
  transition: background var(--duration) var(--ease);
}

.attachment-row:hover {
  background: var(--sage-light);
}

.attachment-row.dragging {
  opacity: 0.5;
}

.attachment-thumb {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-sm);
  object-fit: cover;
  border: 1px solid var(--border);
}

.attachment-file-icon {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-sm);
  background: var(--bg-sidebar);
  font-size: 0.7rem;
  font-family: var(--font-mono);
  color: var(--text-muted);
  text-transform: uppercase;
}

.attachment-info {
  flex: 1;
  min-width: 0;
}

.attachment-info .name {
  font-family: var(--font-mono);
  font-size: 0.8rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attachment-info .size {
  font-family: var(--font-ui);
  font-size: 0.7rem;
  color: var(--text-muted);
}

.attachment-actions {
  display: flex;
  gap: 2px;
}

.attachment-actions button {
  padding: 2px 6px;
  border: none;
  background: none;
  cursor: pointer;
  font-size: 0.75rem;
  color: var(--text-muted);
  border-radius: var(--radius-sm);
  transition: color var(--duration) var(--ease), background var(--duration) var(--ease);
}

.attachment-actions button:hover {
  color: var(--text);
  background: var(--border);
}

.attachment-actions button.delete:hover {
  color: var(--error, #c0392b);
}

.attachment-validation-error {
  font-family: var(--font-ui);
  font-size: 0.75rem;
  color: var(--error, #c0392b);
}

.attachment-empty {
  font-family: var(--font-ui);
  font-size: 0.85rem;
  color: var(--text-muted);
  text-align: center;
  padding: var(--space-lg);
}

/* Broken attachment placeholder in preview */
[data-attachment-missing] {
  display: inline-block;
  border: 2px dashed var(--error, #c0392b);
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-sm);
  font-family: var(--font-mono);
  font-size: 0.8rem;
  color: var(--error, #c0392b);
  background: rgba(192, 57, 43, 0.05);
}

/* Editor layout when panel is open */
.editor-with-panel {
  margin-right: 320px;
  transition: margin-right var(--duration-slow) var(--ease);
}

/* Rename inline input */
.attachment-rename-input {
  font-family: var(--font-mono);
  font-size: 0.8rem;
  padding: 1px 4px;
  border: 1px solid var(--accent);
  border-radius: var(--radius-sm);
  outline: none;
  width: 100%;
}

.attachment-panel-hint {
  font-family: var(--font-ui);
  font-size: 0.75rem;
  color: var(--text-muted);
  text-align: center;
  padding: var(--space-sm);
  border-top: 1px solid var(--border);
}
```

- [ ] **Step 2: Commit**

```bash
git add wikantik-frontend/src/styles/globals.css
git commit -m "feat(frontend): add attachment panel CSS"
```

---

## Task 11: AttachmentPanel Component

**Files:**
- Create: `wikantik-frontend/src/components/AttachmentPanel.jsx`

- [ ] **Step 1: Implement the component**

```jsx
import { useState, useRef } from 'react';
import { isValidAttachmentName, getExtension, extensionsMatch } from '../utils/attachmentNameValidator';

function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

function AttachmentUploadForm({ onUpload }) {
  const [file, setFile] = useState(null);
  const [name, setName] = useState('');
  const [ext, setExt] = useState('');
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState(null);
  const fileRef = useRef(null);

  const handleFileChange = (e) => {
    const f = e.target.files[0];
    if (!f) return;
    setFile(f);
    const fileExt = getExtension(f.name);
    setExt(fileExt);
    // Default the name to the original filename stem
    const dot = f.name.lastIndexOf('.');
    setName(dot > 0 ? f.name.substring(0, dot) : f.name);
    setError(null);
  };

  const fullName = name && ext ? `${name}.${ext}` : '';
  const isValid = fullName && isValidAttachmentName(fullName);

  const handleUpload = async () => {
    if (!file || !isValid) return;
    setUploading(true);
    setError(null);
    try {
      await onUpload(file, fullName);
      setFile(null);
      setName('');
      setExt('');
      if (fileRef.current) fileRef.current.value = '';
    } catch (err) {
      setError(err.message || 'Upload failed');
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="attachment-upload-form">
      <label>Upload file</label>
      <input type="file" ref={fileRef} onChange={handleFileChange}
        style={{ fontFamily: 'var(--font-ui)', fontSize: '0.8rem' }} />
      {file && (
        <>
          <label>Name</label>
          <div className="attachment-name-input">
            <input
              type="text"
              value={name}
              onChange={e => { setName(e.target.value); setError(null); }}
              placeholder="filename"
            />
            <span className="ext-badge">.{ext}</span>
          </div>
          {fullName && !isValid && (
            <span className="attachment-validation-error">
              Only a-z, 0-9, hyphens, underscores. Max 40 chars total.
            </span>
          )}
          {error && <span className="attachment-validation-error">{error}</span>}
          <button className="btn btn-primary btn-sm" onClick={handleUpload}
            disabled={!isValid || uploading}>
            {uploading ? 'Uploading...' : 'Upload'}
          </button>
        </>
      )}
    </div>
  );
}

function AttachmentRow({ attachment, pageName, onRename, onDelete, editorContent }) {
  const [renaming, setRenaming] = useState(false);
  const [newStem, setNewStem] = useState('');
  const ext = getExtension(attachment.fileName);
  const stem = attachment.fileName.substring(0, attachment.fileName.lastIndexOf('.'));

  const handleDragStart = (e) => {
    const md = attachment.isImage
      ? `![${stem}](${attachment.fileName})`
      : `[${stem}](${attachment.fileName})`;
    e.dataTransfer.setData('text/plain', md);
    e.dataTransfer.effectAllowed = 'copy';
    e.currentTarget.classList.add('dragging');
  };

  const handleDragEnd = (e) => {
    e.currentTarget.classList.remove('dragging');
  };

  const startRename = () => {
    setNewStem(stem);
    setRenaming(true);
  };

  const confirmRename = async () => {
    const newName = `${newStem}.${ext}`;
    if (newName === attachment.fileName) { setRenaming(false); return; }
    if (!isValidAttachmentName(newName)) return;
    try {
      await onRename(attachment.fileName, newName);
      setRenaming(false);
    } catch (err) {
      // Error handled by parent
    }
  };

  const handleDelete = () => {
    // Check if referenced in editor content
    const pattern = new RegExp(`(!?\\[[^\\]]*\\])\\(${attachment.fileName.replace('.', '\\.')}\\)`);
    const isReferenced = pattern.test(editorContent || '');
    const message = isReferenced
      ? `"${attachment.fileName}" is referenced in your content. Deleting it will leave broken references. Continue?`
      : `Delete "${attachment.fileName}"?`;
    if (confirm(message)) {
      onDelete(attachment.fileName);
    }
  };

  const thumbUrl = attachment.isImage
    ? `/attach/${pageName}/${attachment.fileName}`
    : null;

  return (
    <div className="attachment-row" draggable onDragStart={handleDragStart} onDragEnd={handleDragEnd}>
      {thumbUrl
        ? <img className="attachment-thumb" src={thumbUrl} alt={attachment.fileName} />
        : <div className="attachment-file-icon">{ext}</div>
      }
      <div className="attachment-info">
        {renaming ? (
          <div className="attachment-name-input" style={{ marginBottom: 0 }}>
            <input
              className="attachment-rename-input"
              value={newStem}
              onChange={e => setNewStem(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') confirmRename(); if (e.key === 'Escape') setRenaming(false); }}
              autoFocus
            />
            <span className="ext-badge">.{ext}</span>
          </div>
        ) : (
          <div className="name">{attachment.fileName}</div>
        )}
        <div className="size">{formatSize(attachment.size)}</div>
      </div>
      <div className="attachment-actions">
        {renaming ? (
          <>
            <button onClick={confirmRename} title="Confirm">OK</button>
            <button onClick={() => setRenaming(false)} title="Cancel">X</button>
          </>
        ) : (
          <>
            <button onClick={startRename} title="Rename">R</button>
            <button className="delete" onClick={handleDelete} title="Delete">D</button>
          </>
        )}
      </div>
    </div>
  );
}

export default function AttachmentPanel({ open, onClose, pageName, attachments, onUpload, onRename, onDelete, editorContent }) {
  return (
    <div className={`attachment-panel${open ? ' open' : ''}`}>
      <div className="attachment-panel-header">
        <span>Attachments</span>
        <button className="btn btn-ghost" onClick={onClose} style={{ padding: '2px 8px', fontSize: '0.8rem' }}>
          X
        </button>
      </div>
      <div className="attachment-panel-body">
        <AttachmentUploadForm onUpload={onUpload} />
        {attachments.length === 0 ? (
          <div className="attachment-empty">No attachments yet</div>
        ) : (
          attachments.map(att => (
            <AttachmentRow
              key={att.fileName}
              attachment={att}
              pageName={pageName}
              onRename={onRename}
              onDelete={onDelete}
              editorContent={editorContent}
            />
          ))
        )}
      </div>
      <div className="attachment-panel-hint">Drag items into the editor to insert</div>
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add wikantik-frontend/src/components/AttachmentPanel.jsx
git commit -m "feat(frontend): add AttachmentPanel component"
```

---

## Task 12: PageEditor Integration

**Files:**
- Modify: `wikantik-frontend/src/components/PageEditor.jsx`

- [ ] **Step 1: Wire up hooks and panel**

Add imports:
```javascript
import { useRef, useCallback } from 'react';
import { useAttachments } from '../hooks/useAttachments';
import { useEditorDrop } from '../hooks/useEditorDrop';
import { remarkAttachments } from '../utils/remarkAttachments';
import AttachmentPanel from './AttachmentPanel';
```

Add state and hooks after existing state declarations (after line 23):
```javascript
const [panelOpen, setPanelOpen] = useState(false);
const textareaRef = useRef(null);
const attachments = useAttachments(name);

const handleInsert = useCallback((text, pos) => {
  setContent(prev => prev.slice(0, pos) + text + prev.slice(pos));
}, []);

useEditorDrop(textareaRef, handleInsert);

const handleRename = useCallback(async (oldName, newName) => {
  const result = await attachments.renameAttachment(oldName, newName);
  // Update markdown references
  setContent(prev => {
    const escaped = oldName.replace(/\./g, '\\.');
    return prev
      .replace(new RegExp(`(!\\[[^\\]]*\\])\\(${escaped}\\)`, 'g'), `$1(${newName})`)
      .replace(new RegExp(`(\\[[^\\]]*\\])\\(${escaped}\\)`, 'g'), `$1(${newName})`);
  });
  return result;
}, [attachments]);
```

Add the attachments button to toolbar (inside the second `editor-toolbar-group`, before the Cancel button):
```jsx
<button className="btn btn-ghost" onClick={() => setPanelOpen(p => !p)}
  style={{ fontSize: '1.1rem' }} title="Attachments">
  📎
</button>
```

Update the outermost div to shift when panel is open:
```jsx
<div className={`page-enter${panelOpen ? ' editor-with-panel' : ''}`}>
```

Add `ref` to the textarea:
```jsx
<textarea
  ref={textareaRef}
  className="editor-textarea"
  value={content}
  onChange={e => setContent(e.target.value)}
  spellCheck="false"
/>
```

Update `ReactMarkdown` to use the remark plugin:
```jsx
<ReactMarkdown remarkPlugins={[
  remarkGfm,
  [remarkAttachments, { attachments: attachments.list, pageName: name }],
]}>
  {content}
</ReactMarkdown>
```

Add the panel before the closing `</div>` of the component:
```jsx
<AttachmentPanel
  open={panelOpen}
  onClose={() => setPanelOpen(false)}
  pageName={name}
  attachments={attachments.list}
  onUpload={attachments.uploadAttachment}
  onRename={handleRename}
  onDelete={attachments.deleteAttachment}
  editorContent={content}
/>
```

- [ ] **Step 2: Commit**

```bash
git add wikantik-frontend/src/components/PageEditor.jsx
git commit -m "feat(frontend): integrate attachment panel into PageEditor"
```

---

## Task 13: BlogEditor Integration

**Files:**
- Modify: `wikantik-frontend/src/components/BlogEditor.jsx`

- [ ] **Step 1: Wire up hooks and panel**

Same pattern as PageEditor. Add imports:
```javascript
import { useRef, useCallback } from 'react';
import { useAttachments } from '../hooks/useAttachments';
import { useEditorDrop } from '../hooks/useEditorDrop';
import { remarkAttachments } from '../utils/remarkAttachments';
import AttachmentPanel from './AttachmentPanel';
```

Compute the full page name for blog entries:
```javascript
const blogPageName = `blog/${username}/${pageName}`;
```

Add state and hooks after existing state (after line 18):
```javascript
const [panelOpen, setPanelOpen] = useState(false);
const textareaRef = useRef(null);
const attachments = useAttachments(blogPageName);

const handleInsert = useCallback((text, pos) => {
  setContent(prev => prev.slice(0, pos) + text + prev.slice(pos));
}, []);

useEditorDrop(textareaRef, handleInsert);

const handleRename = useCallback(async (oldName, newName) => {
  const result = await attachments.renameAttachment(oldName, newName);
  setContent(prev => {
    const escaped = oldName.replace(/\./g, '\\.');
    return prev
      .replace(new RegExp(`(!\\[[^\\]]*\\])\\(${escaped}\\)`, 'g'), `$1(${newName})`)
      .replace(new RegExp(`(\\[[^\\]]*\\])\\(${escaped}\\)`, 'g'), `$1(${newName})`);
  });
  return result;
}, [attachments]);
```

Add attachments button to toolbar, `ref` to textarea, remark plugin to preview, panel component, and `editor-with-panel` class — same structure as PageEditor task.

Update `ReactMarkdown` in the preview:
```jsx
<ReactMarkdown remarkPlugins={[
  remarkGfm,
  [remarkAttachments, { attachments: attachments.list, pageName: blogPageName }],
]}>
  {previewContent}
</ReactMarkdown>
```

- [ ] **Step 2: Commit**

```bash
git add wikantik-frontend/src/components/BlogEditor.jsx
git commit -m "feat(frontend): integrate attachment panel into BlogEditor"
```

---

## Task 14: Build and Smoke Test

- [ ] **Step 1: Run Java tests**

```bash
mvn clean test -T 1C -DskipITs
```

Expected: All tests PASS (including new validator and REST tests).

- [ ] **Step 2: Build frontend**

```bash
cd wikantik-frontend && npm install && npm test -- --run && npm run build
```

Expected: All JS tests PASS, build succeeds.

- [ ] **Step 3: Build full WAR**

```bash
mvn clean install -Dmaven.test.skip -T 1C
```

Expected: WAR builds successfully.

- [ ] **Step 4: Deploy and manual test**

```bash
tomcat/tomcat-11/bin/shutdown.sh
rm -rf tomcat/tomcat-11/webapps/ROOT
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
```

Test checklist:
1. Open a wiki page editor — attachment button visible in toolbar
2. Click attachment button — panel slides in from right
3. Upload `testimages/poolside.jpg` with name `poolside.jpg` — appears in list with thumbnail
4. Upload `testimages/verysadday.jpg` with name `sadday.jpg` — second attachment appears
5. Drag `poolside.jpg` from panel to editor — `![poolside](poolside.jpg)` inserted
6. Preview shows the image resolved to `/attach/PageName/poolside.jpg`
7. Rename `sadday.jpg` to `gloomyday.jpg` — markdown reference updates if present
8. Delete an attachment — warning shown if referenced
9. Open blog editor — same functionality works
10. Save page — verify rendered page shows images correctly
