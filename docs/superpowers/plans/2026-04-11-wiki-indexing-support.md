# Wiki Indexing Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add content-negotiated raw-content endpoints (`?format=md|json`) and a timestamp-filtered changes feed so Wikantik pages can be ingested by OpenWebUI for RAG, and enrich existing server-side semantic rendering with `dateModified` to improve SEO.

**Architecture:** `/wiki/{slug}?format=md|json` is handled by a new servlet filter (`WikiPageFormatFilter`) registered **before** the existing `SpaRoutingFilter` on `/wiki/*`, so requests without a `format` parameter still hit the SPA routing path unchanged. `/api/changes` is a new `RestServletBase` subclass following the same pattern as `RecentChangesResource`. `dateModified` is threaded from `Page.getLastModified()` into `SemanticHeadRenderer` so the existing crawler-side head injection carries accurate modification dates.

**Tech Stack:** Jakarta Servlet API, Gson (already in `wikantik-rest`), JUnit 5, Mockito, `TestEngine` + `HttpMockFactory` test harness.

---

## Architectural Decision: No Crawler-UA Sniffer

The spec (`IndexingSupport.md` §3) calls for a User-Agent-matching filter that serves server-rendered HTML to crawlers. **This feature is already implemented**, by a better mechanism:

- `wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java:148-152` unconditionally forwards every `/wiki/*` request to the SPA shell **and** injects a semantic `<head>` fragment + a no-JS `<article>` body fallback via `wikantik-main/src/main/java/com/wikantik/ui/SemanticHeadRenderer.java`.
- The injected head already contains: `<link rel="canonical">`, `<meta name="description">`, Open Graph (`og:title/type/url/description/site_name`), Twitter Card, `article:tag` per tag, Atom feed autodiscovery, and a full `Article` JSON-LD block.
- The no-JS body fragment contains `<h1>` + `<p>` elements extracted from the page body so crawlers without JS still index the content.

This "always inject for everyone" strategy is strictly better than UA sniffing because:

1. It avoids **cloaking penalties** — showing different content to Googlebot than to users is a policy violation that can hurt ranking.
2. It is **cache-friendly** — a single cache key works for all clients.
3. It is **testable without spoofing a UA** — the SemanticWebIT suite can exercise it directly.

**We will not add a UA-sniffing filter.** Instead, we will (a) add `dateModified` to the existing JSON-LD output, which is the one spec field currently missing, and (b) verify the existing behavior with an acceptance test. The `IndexingSupport.md` spec should be considered satisfied on feature #3 by the existing implementation plus Task 3 below.

---

## What's already in place

- **`/wiki/{slug}` returns HTTP 200** with injected semantic head and a no-JS body fragment — not 404 as the spec claims.
- **Sitemap** at `/sitemap.xml` (`wikantik-main/src/main/java/com/wikantik/ui/SitemapServlet.java`) already uses `Page.getLastModified()` for `<lastmod>`, includes all non-system pages, and is referenced from `wikantik-war/src/main/webapp/robots.txt:7`.
- **`PageManager.getRecentChanges(Date since)`** already exists (`wikantik-api/src/main/java/com/wikantik/api/managers/PageManager.java:277`) — we can use it directly for the changes feed.
- **Gson JSON serialization** is already wired into `RestServletBase` with ISO 8601 date formatting, so the `/api/changes` endpoint needs no new infrastructure.

## What's actually missing

1. **`/wiki/{slug}?format=md`** — serving clean Markdown is not currently possible; any request to `/wiki/*` returns HTML.
2. **`/wiki/{slug}?format=json`** — same, no JSON export endpoint exposing the spec's schema.
3. **`/api/changes?since=...`** — `RecentChangesResource` ignores any `since` parameter and caps results at 200.
4. **`dateModified` in JSON-LD** — `SemanticHeadRenderer.buildMainJsonLd` emits `datePublished` but not `dateModified`, so crawler indexing lacks a "last-updated" signal.
5. **Sitemap acceptance test** — `SitemapServletTest.java:207` asserts `<lastmod>` is *present*, not that it equals `page.getLastModified()`.

---

## File Structure

**New files:**

- `wikantik-rest/src/main/java/com/wikantik/rest/WikiPageFormatFilter.java` — servlet filter intercepting `/wiki/*?format=md|json`
- `wikantik-rest/src/test/java/com/wikantik/rest/WikiPageFormatFilterTest.java` — unit tests for the filter
- `wikantik-rest/src/main/java/com/wikantik/rest/ChangesResource.java` — `/api/changes?since=` servlet
- `wikantik-rest/src/test/java/com/wikantik/rest/ChangesResourceTest.java` — unit tests for the servlet

**Modified files:**

- `wikantik-war/src/main/webapp/WEB-INF/web.xml` — register the new filter and servlet
- `wikantik-main/src/main/java/com/wikantik/ui/SemanticHeadRenderer.java` — add `dateModified` parameter and JSON-LD field
- `wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java` — pass `page.getLastModified()` into `SemanticHeadRenderer.renderHead`
- `wikantik-main/src/test/java/com/wikantik/ui/SitemapServletTest.java` — add a stronger `<lastmod>` test

**Verification only (no edits):**

- `wikantik-war/src/main/webapp/robots.txt` — already contains `Sitemap: https://wiki.jakefear.com/sitemap.xml`; Task 5 adds a unit test guarding it.

---

### Task 1: `WikiPageFormatFilter` — markdown + JSON content endpoint

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/WikiPageFormatFilter.java`
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/WikiPageFormatFilterTest.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (register filter before `SpaRoutingFilter`)

**Design notes:**

- The filter is mapped on `/wiki/*`. It first checks for a `format` query parameter. If absent or unknown, it calls `chain.doFilter()` and lets `SpaRoutingFilter` handle the request exactly as today.
- When `format=md` or `format=json`, the filter resolves the page name from the URI (re-using `SpaRoutingFilter.extractPageName`, which is already package-private), looks up the page via `PageManager`, and serves the content directly, short-circuiting `SpaRoutingFilter` via `return`.
- Page-not-found for a `format` request returns **404** (not SPA passthrough), because an OpenWebUI sync script needs a definite answer.
- Internal wiki link rewriting is done by a conservative regex that only rewrites targets containing no `/`, no `:`, and not starting with `#`. Image targets like `Page/diagram.png` or `/attach/foo.png` are left alone.
- Summary extraction prefers frontmatter `summary` → `description` → first body paragraph, truncated to 300 chars with an ellipsis.
- `created_at` prefers frontmatter `created` → `date` → `page.getLastModified()` as a last resort.

- [ ] **Step 1: Create the test file with a failing markdown test**

Create `wikantik-rest/src/test/java/com/wikantik/rest/WikiPageFormatFilterTest.java`:

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
package com.wikantik.rest;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.managers.PageManager;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WikiPageFormatFilterTest {

    private TestEngine engine;
    private WikiPageFormatFilter filter;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        engine.saveText( "FormatPage",
            "---\n" +
            "title: Format Page\n" +
            "tags:\n" +
            "  - alpha\n" +
            "  - beta\n" +
            "summary: A short description of the format page.\n" +
            "created: 2025-11-14\n" +
            "---\n" +
            "Body content goes here.\n\n" +
            "See [Other](OtherPage) and [External](https://example.com).\n" );

        filter = new WikiPageFormatFilter();
        final FilterConfig config = Mockito.mock( FilterConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        filter.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            final PageManager pm = engine.getManager( PageManager.class );
            try { pm.deletePage( "FormatPage" ); } catch ( final Exception ignored ) {}
            engine.stop();
        }
    }

    @Test
    void testFormatMdReturnsMarkdownWithH1() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/wiki/FormatPage" );
        when( req.getParameter( "format" ) ).thenReturn( "md" );
        when( req.getRequestURI() ).thenReturn( "/wiki/FormatPage" );
        when( req.getContextPath() ).thenReturn( "" );

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final ServletOutputStream sos = new ServletOutputStream() {
            @Override public void write( final int b ) { baos.write( b ); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener( final WriteListener listener ) {}
        };
        when( resp.getOutputStream() ).thenReturn( sos );

        final FilterChain chain = mock( FilterChain.class );
        filter.doFilter( req, resp, chain );

        verify( resp ).setContentType( "text/markdown; charset=UTF-8" );
        verify( resp ).setStatus( HttpServletResponse.SC_OK );
        verify( chain, never() ).doFilter( any(), any() );

        final String md = baos.toString( StandardCharsets.UTF_8 );
        assertTrue( md.startsWith( "# Format Page" ), "Markdown must start with H1 title, got: " + md );
        assertTrue( md.contains( "Body content goes here." ) );
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl wikantik-rest test -Dtest=WikiPageFormatFilterTest -q
```

Expected: compile error — `WikiPageFormatFilter` class does not exist.

- [ ] **Step 3: Create the filter class with the minimal code to make the markdown test pass**

Create `wikantik-rest/src/main/java/com/wikantik/rest/WikiPageFormatFilter.java`:

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
package com.wikantik.rest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.util.BaseUrlResolver;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves page content directly for <code>/wiki/{slug}?format=md</code> and
 * <code>/wiki/{slug}?format=json</code> requests so search engines and RAG
 * pipelines (e.g. OpenWebUI's web loader) can fetch page bodies without
 * executing the SPA. Requests without a <code>format</code> parameter are
 * passed through to {@link SpaRoutingFilter} and behave exactly as before.
 *
 * <p>This filter must be declared in <code>web.xml</code> before
 * <code>SpaRoutingFilter</code>, mapped on <code>/wiki/*</code>.
 */
public class WikiPageFormatFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( WikiPageFormatFilter.class );

    private static final Gson GSON = new GsonBuilder()
            .setDateFormat( "yyyy-MM-dd'T'HH:mm:ss'Z'" )
            .create();

    private static final int SUMMARY_MAX_CHARS = 300;

    private volatile Engine engine;
    private ServletContext servletContext;

    @Override
    public void init( final FilterConfig filterConfig ) {
        this.servletContext = filterConfig != null ? filterConfig.getServletContext() : null;
    }

    private Engine resolveEngine() {
        Engine e = engine;
        if ( e == null && servletContext != null ) {
            try {
                e = Wiki.engine().find( servletContext, null );
                if ( e != null ) {
                    engine = e;
                }
            } catch ( final RuntimeException ex ) {
                LOG.warn( "WikiPageFormatFilter: engine lookup failed: {}", ex.getMessage() );
            }
        }
        return e;
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                           final FilterChain chain ) throws IOException, ServletException {
        final HttpServletRequest req = ( HttpServletRequest ) request;
        final HttpServletResponse resp = ( HttpServletResponse ) response;

        final String format = req.getParameter( "format" );
        if ( format == null || format.isEmpty() ) {
            chain.doFilter( request, response );
            return;
        }
        if ( !"md".equalsIgnoreCase( format ) && !"json".equalsIgnoreCase( format ) ) {
            chain.doFilter( request, response );
            return;
        }

        final String contextPath = req.getContextPath() != null ? req.getContextPath() : "";
        final String rawUri = req.getRequestURI();
        final String path = rawUri != null && rawUri.startsWith( contextPath )
                ? rawUri.substring( contextPath.length() )
                : rawUri;
        if ( path == null || !path.startsWith( "/wiki/" ) ) {
            chain.doFilter( request, response );
            return;
        }

        final String pageName = SpaRoutingFilter.extractPageName( path );
        if ( pageName == null || pageName.isEmpty() ) {
            chain.doFilter( request, response );
            return;
        }

        final Engine eng = resolveEngine();
        if ( eng == null ) {
            chain.doFilter( request, response );
            return;
        }
        final PageManager pm;
        try {
            pm = eng.getManager( PageManager.class );
        } catch ( final RuntimeException ex ) {
            LOG.warn( "WikiPageFormatFilter: PageManager lookup failed for '{}': {}",
                    pageName, ex.getMessage() );
            chain.doFilter( request, response );
            return;
        }

        if ( !pm.wikiPageExists( pageName ) ) {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND, "Page not found: " + pageName );
            return;
        }
        final Page page = pm.getPage( pageName );
        if ( page == null ) {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND, "Page not found: " + pageName );
            return;
        }
        final String rawText = pm.getPureText( page );
        final ParsedPage parsed = FrontmatterParser.parse( rawText == null ? "" : rawText );
        final String baseUrl = BaseUrlResolver.resolve( eng, req, null );

        if ( "md".equalsIgnoreCase( format ) ) {
            writeMarkdown( resp, page, parsed, baseUrl );
        } else {
            writeJson( resp, page, parsed );
        }
    }

    private void writeMarkdown( final HttpServletResponse resp, final Page page,
                                 final ParsedPage parsed, final String baseUrl ) throws IOException {
        final String title = extractTitle( page, parsed );
        String body = parsed.body() == null ? "" : parsed.body();
        body = stripLeadingH1( body, title );
        body = rewriteInternalLinks( body, baseUrl );
        final String md = "# " + title + "\n\n" + body;
        final byte[] bytes = md.getBytes( StandardCharsets.UTF_8 );
        resp.setStatus( HttpServletResponse.SC_OK );
        resp.setContentType( "text/markdown; charset=UTF-8" );
        resp.setCharacterEncoding( "UTF-8" );
        resp.setContentLength( bytes.length );
        resp.getOutputStream().write( bytes );
    }

    private void writeJson( final HttpServletResponse resp, final Page page,
                             final ParsedPage parsed ) throws IOException {
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "slug", page.getName() );
        out.put( "title", extractTitle( page, parsed ) );
        out.put( "content", parsed.body() == null ? "" : parsed.body() );
        out.put( "summary", extractSummary( parsed ) );
        out.put( "tags", extractTags( parsed.metadata() ) );
        out.put( "created_at", extractCreated( parsed.metadata(), page ) );
        out.put( "modified_at", page.getLastModified() );
        resp.setStatus( HttpServletResponse.SC_OK );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setCharacterEncoding( "UTF-8" );
        resp.getWriter().write( GSON.toJson( out ) );
    }

    static String extractTitle( final Page page, final ParsedPage parsed ) {
        final Object t = parsed.metadata().get( "title" );
        if ( t != null && !t.toString().isBlank() ) {
            return t.toString();
        }
        return page.getName();
    }

    static String extractSummary( final ParsedPage parsed ) {
        final Object summary = parsed.metadata().get( "summary" );
        if ( summary != null && !summary.toString().isBlank() ) {
            return truncate( summary.toString(), SUMMARY_MAX_CHARS );
        }
        final Object description = parsed.metadata().get( "description" );
        if ( description != null && !description.toString().isBlank() ) {
            return truncate( description.toString(), SUMMARY_MAX_CHARS );
        }
        final String body = parsed.body() == null ? "" : parsed.body();
        final StringBuilder firstPara = new StringBuilder();
        for ( final String line : body.split( "\r?\n" ) ) {
            final String stripped = line.trim();
            if ( stripped.isEmpty() ) {
                if ( firstPara.length() > 0 ) {
                    break;
                }
                continue;
            }
            if ( stripped.startsWith( "#" ) ) {
                continue;
            }
            if ( firstPara.length() > 0 ) {
                firstPara.append( ' ' );
            }
            firstPara.append( stripped );
        }
        return truncate( firstPara.toString(), SUMMARY_MAX_CHARS );
    }

    private static String truncate( final String s, final int max ) {
        if ( s.length() <= max ) {
            return s;
        }
        return s.substring( 0, max - 1 ) + "\u2026";
    }

    static List< String > extractTags( final Map< String, Object > meta ) {
        final Object tags = meta.get( "tags" );
        if ( tags == null ) {
            return List.of();
        }
        if ( tags instanceof List< ? > list ) {
            final List< String > out = new ArrayList<>( list.size() );
            for ( final Object o : list ) {
                if ( o != null ) {
                    out.add( o.toString().trim() );
                }
            }
            return out;
        }
        final String csv = tags.toString();
        if ( csv.isBlank() ) {
            return List.of();
        }
        final List< String > out = new ArrayList<>();
        for ( final String p : csv.split( "," ) ) {
            final String trimmed = p.trim();
            if ( !trimmed.isEmpty() ) {
                out.add( trimmed );
            }
        }
        return out;
    }

    static Date extractCreated( final Map< String, Object > meta, final Page page ) {
        final Object created = meta.get( "created" );
        if ( created instanceof Date d ) {
            return d;
        }
        if ( created != null && !created.toString().isBlank() ) {
            try {
                return new SimpleDateFormat( "yyyy-MM-dd" ).parse( created.toString() );
            } catch ( final Exception ignored ) {
                // fall through
            }
        }
        final Object date = meta.get( "date" );
        if ( date instanceof Date d ) {
            return d;
        }
        return page.getLastModified();
    }

    static String stripLeadingH1( final String body, final String title ) {
        final String trimmed = body.stripLeading();
        if ( !trimmed.startsWith( "# " ) ) {
            return body;
        }
        final int nl = trimmed.indexOf( '\n' );
        final String firstLine = ( nl < 0 ? trimmed : trimmed.substring( 0, nl ) ).substring( 2 ).trim();
        if ( !firstLine.equals( title ) ) {
            return body;
        }
        if ( nl < 0 ) {
            return "";
        }
        return trimmed.substring( nl + 1 ).stripLeading();
    }

    static String rewriteInternalLinks( final String body, final String baseUrl ) {
        final String base = baseUrl == null ? ""
                : ( baseUrl.endsWith( "/" ) ? baseUrl.substring( 0, baseUrl.length() - 1 ) : baseUrl );
        final StringBuilder out = new StringBuilder( body.length() + 64 );
        int i = 0;
        while ( i < body.length() ) {
            final int open = body.indexOf( "](", i );
            if ( open < 0 ) {
                out.append( body, i, body.length() );
                break;
            }
            final int close = body.indexOf( ')', open + 2 );
            if ( close < 0 ) {
                out.append( body, i, body.length() );
                break;
            }
            out.append( body, i, open + 2 );
            final String target = body.substring( open + 2, close );
            if ( isInternalTarget( target ) ) {
                out.append( base ).append( "/wiki/" ).append( target );
            } else {
                out.append( target );
            }
            out.append( ')' );
            i = close + 1;
        }
        return out.toString();
    }

    private static boolean isInternalTarget( final String t ) {
        if ( t == null || t.isEmpty() ) {
            return false;
        }
        if ( t.contains( "/" ) ) {
            return false;
        }
        if ( t.contains( ":" ) ) {
            return false;
        }
        if ( t.startsWith( "#" ) ) {
            return false;
        }
        return true;
    }

    @Override
    public void destroy() {
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
mvn -pl wikantik-rest test -Dtest=WikiPageFormatFilterTest -q
```

Expected: `testFormatMdReturnsMarkdownWithH1` passes, 1 test run, 0 failures.

- [ ] **Step 5: Add the JSON format test**

Append to `WikiPageFormatFilterTest.java`:

```java
    @Test
    void testFormatJsonReturnsSchema() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/wiki/FormatPage" );
        when( req.getParameter( "format" ) ).thenReturn( "json" );
        when( req.getRequestURI() ).thenReturn( "/wiki/FormatPage" );
        when( req.getContextPath() ).thenReturn( "" );

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        final FilterChain chain = mock( FilterChain.class );
        filter.doFilter( req, resp, chain );

        verify( resp ).setContentType( "application/json; charset=UTF-8" );
        verify( resp ).setStatus( HttpServletResponse.SC_OK );
        verify( chain, never() ).doFilter( any(), any() );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( "FormatPage", obj.get( "slug" ).getAsString() );
        assertEquals( "Format Page", obj.get( "title" ).getAsString() );
        assertTrue( obj.get( "content" ).getAsString().contains( "Body content" ) );
        assertTrue( obj.has( "summary" ) );
        assertTrue( obj.get( "summary" ).getAsString().contains( "short description" ) );
        assertTrue( obj.has( "tags" ) );
        final JsonArray tags = obj.getAsJsonArray( "tags" );
        assertEquals( 2, tags.size() );
        assertEquals( "alpha", tags.get( 0 ).getAsString() );
        assertTrue( obj.has( "created_at" ) );
        assertTrue( obj.has( "modified_at" ) );
    }
```

- [ ] **Step 6: Run the JSON test to verify it passes**

```bash
mvn -pl wikantik-rest test -Dtest=WikiPageFormatFilterTest#testFormatJsonReturnsSchema -q
```

Expected: passes. (The implementation from Step 3 already handles both formats, so no code change needed.)

- [ ] **Step 7: Add passthrough tests**

Append to `WikiPageFormatFilterTest.java`:

```java
    @Test
    void testNoFormatParamPassesThrough() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/wiki/FormatPage" );
        when( req.getParameter( "format" ) ).thenReturn( null );
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final FilterChain chain = mock( FilterChain.class );

        filter.doFilter( req, resp, chain );
        verify( chain, times( 1 ) ).doFilter( req, resp );
    }

    @Test
    void testUnknownFormatPassesThrough() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/wiki/FormatPage" );
        when( req.getParameter( "format" ) ).thenReturn( "pdf" );
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final FilterChain chain = mock( FilterChain.class );

        filter.doFilter( req, resp, chain );
        verify( chain, times( 1 ) ).doFilter( req, resp );
    }

    @Test
    void testPageNotFoundReturns404() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/wiki/NoSuchPage" );
        when( req.getParameter( "format" ) ).thenReturn( "md" );
        when( req.getRequestURI() ).thenReturn( "/wiki/NoSuchPage" );
        when( req.getContextPath() ).thenReturn( "" );

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final FilterChain chain = mock( FilterChain.class );
        filter.doFilter( req, resp, chain );

        verify( resp ).sendError( eq( HttpServletResponse.SC_NOT_FOUND ), anyString() );
        verify( chain, never() ).doFilter( any(), any() );
    }

    @Test
    void testInternalLinkRewritingHelper() {
        final String body = "See [Other](OtherPage) and [External](https://example.com) and [Img](/attach/foo.png).";
        final String rewritten = WikiPageFormatFilter.rewriteInternalLinks( body, "https://wiki.example.com" );
        assertEquals( "See [Other](https://wiki.example.com/wiki/OtherPage) and [External](https://example.com) and [Img](/attach/foo.png).", rewritten );
    }

    @Test
    void testStripLeadingH1Helper() {
        final String body = "# Format Page\n\nBody line.\n";
        final String stripped = WikiPageFormatFilter.stripLeadingH1( body, "Format Page" );
        assertEquals( "Body line.\n", stripped );
    }
```

- [ ] **Step 8: Run all filter tests to verify they pass**

```bash
mvn -pl wikantik-rest test -Dtest=WikiPageFormatFilterTest -q
```

Expected: 6 tests run, 0 failures.

- [ ] **Step 9: Register the filter in `web.xml` — declaration**

Modify `wikantik-war/src/main/webapp/WEB-INF/web.xml`. Insert the following **immediately before** the `<!-- React SPA routing ... -->` comment at line 106:

```xml
   <!-- Raw page content: serves /wiki/{slug}?format=md|json for RAG/SEO indexing -->
   <filter>
     <filter-name>WikiPageFormatFilter</filter-name>
     <filter-class>com.wikantik.rest.WikiPageFormatFilter</filter-class>
     <async-supported>true</async-supported>
   </filter>
   <filter-mapping>
     <filter-name>WikiPageFormatFilter</filter-name>
     <url-pattern>/wiki/*</url-pattern>
   </filter-mapping>

```

The blank trailing line preserves the existing spacing before `<!-- React SPA routing -->`.

- [ ] **Step 10: Build the full WAR to verify web.xml parses**

```bash
mvn -pl wikantik-war install -DskipTests -Dminimize=false -q
```

Expected: BUILD SUCCESS. The WAR assembly step will fail loudly if `web.xml` is malformed.

- [ ] **Step 11: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/WikiPageFormatFilter.java \
        wikantik-rest/src/test/java/com/wikantik/rest/WikiPageFormatFilterTest.java \
        wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(rest): serve /wiki/{slug}?format=md|json for RAG and SEO indexing"
```

---

### Task 2: `ChangesResource` — `/api/changes?since=` feed

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/ChangesResource.java`
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/ChangesResourceTest.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (register servlet at `/api/changes`)

**Design notes:**

- Subclasses `RestServletBase` so CORS headers, Gson, `getEngine()`, and error helpers are inherited.
- Accepts `since` as an ISO-8601 timestamp (`2026-04-11T00:00:00Z`) and parses it to a `Date`.
- Missing `since` returns a full export (all recent changes, no time filter).
- Invalid `since` returns HTTP 400 with a JSON error (via `sendError`).
- Uses `PageManager.getRecentChanges(Date)` directly — that overload already exists in the API.
- The JSON shape matches the spec exactly: `{since, generated_at, pages: [{slug, modified_at, url}]}`.
- `url` is built from `BaseUrlResolver.resolve(engine, request, null)` so it adapts to the deployment host.

- [ ] **Step 1: Write the failing test**

Create `wikantik-rest/src/test/java/com/wikantik/rest/ChangesResourceTest.java`:

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
package com.wikantik.rest;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.managers.PageManager;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.ParseException;
import java.util.Date;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class ChangesResourceTest {

    private TestEngine engine;
    private ChangesResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        engine.saveText( "ChangesPageA", "First page for changes test." );
        engine.saveText( "ChangesPageB", "Second page for changes test." );

        servlet = new ChangesResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            final PageManager pm = engine.getManager( PageManager.class );
            try { pm.deletePage( "ChangesPageA" ); } catch ( final Exception ignored ) {}
            try { pm.deletePage( "ChangesPageB" ); } catch ( final Exception ignored ) {}
            engine.stop();
        }
    }

    @Test
    void testChangesWithNoSinceReturnsAllPages() throws Exception {
        final String json = doGet( null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "since" ) );
        assertTrue( obj.has( "generated_at" ) );
        assertTrue( obj.has( "pages" ) );
        assertTrue( obj.get( "pages" ).isJsonArray() );

        final JsonArray pages = obj.getAsJsonArray( "pages" );
        assertTrue( pages.size() >= 2, "Should include the two test pages" );

        final JsonObject entry = pages.get( 0 ).getAsJsonObject();
        assertTrue( entry.has( "slug" ) );
        assertTrue( entry.has( "modified_at" ) );
        assertTrue( entry.has( "url" ) );
        assertTrue( entry.get( "url" ).getAsString().contains( "/wiki/" ) );
    }

    @Test
    void testChangesWithFutureSinceReturnsEmpty() throws Exception {
        final String json = doGet( "2099-01-01T00:00:00Z" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );
        final JsonArray pages = obj.getAsJsonArray( "pages" );
        assertEquals( 0, pages.size(), "No pages should be modified after 2099" );
    }

    @Test
    void testChangesWithInvalidSinceReturns400() throws Exception {
        final String json = doGet( "not-a-date" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );
        assertTrue( obj.has( "error" ) );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testParseIso8601ZuluForm() throws ParseException {
        final Date d = ChangesResource.parseIso8601( "2026-04-11T12:00:00Z" );
        assertNotNull( d );
    }

    @Test
    void testParseIso8601DateOnly() throws ParseException {
        final Date d = ChangesResource.parseIso8601( "2026-04-11" );
        assertNotNull( d );
    }

    // ----- helpers -----

    private String doGet( final String since ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/changes" );
        when( req.getParameter( "since" ) ).thenReturn( since );

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        servlet.doGet( req, resp );
        return sw.toString();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl wikantik-rest test -Dtest=ChangesResourceTest -q
```

Expected: compile error — `ChangesResource` class does not exist.

- [ ] **Step 3: Create the servlet**

Create `wikantik-rest/src/main/java/com/wikantik/rest/ChangesResource.java`:

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
package com.wikantik.rest;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.util.BaseUrlResolver;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * REST servlet for the changes feed used by OpenWebUI's sync script and other
 * indexing pipelines.
 *
 * <p>Mapped to <code>/api/changes</code>. Handles:
 * <ul>
 *   <li><code>GET /api/changes</code> — list all recently changed pages (full export)</li>
 *   <li><code>GET /api/changes?since=2026-04-11T00:00:00Z</code> — pages modified after the timestamp</li>
 * </ul>
 */
public class ChangesResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( ChangesResource.class );

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        Date since = null;
        final String sinceParam = request.getParameter( "since" );
        if ( sinceParam != null && !sinceParam.isBlank() ) {
            try {
                since = parseIso8601( sinceParam );
            } catch ( final ParseException e ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                        "Invalid 'since' parameter (expected ISO 8601): " + sinceParam );
                return;
            }
        }

        final Engine engine = getEngine();
        final PageManager pm = engine.getManager( PageManager.class );

        final Set< Page > pages = ( since != null )
                ? pm.getRecentChanges( since )
                : pm.getRecentChanges();

        final String baseUrl = BaseUrlResolver.resolve( engine, request, null );
        final String baseNoSlash = baseUrl.endsWith( "/" )
                ? baseUrl.substring( 0, baseUrl.length() - 1 )
                : baseUrl;

        final List< Map< String, Object > > out = new ArrayList<>( pages.size() );
        for ( final Page p : pages ) {
            if ( since != null && p.getLastModified() != null
                    && p.getLastModified().before( since ) ) {
                continue;
            }
            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "slug", p.getName() );
            entry.put( "modified_at", p.getLastModified() );
            entry.put( "url", baseNoSlash + "/wiki/" + p.getName() );
            out.add( entry );
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "since", since );
        result.put( "generated_at", new Date() );
        result.put( "pages", out );

        LOG.debug( "GET /api/changes since={} returned {} pages", since, out.size() );
        sendJson( response, result );
    }

    /**
     * Parses an ISO 8601 datetime. Accepts {@code yyyy-MM-dd'T'HH:mm:ss'Z'},
     * the same with milliseconds, a timezone offset form, or a bare
     * {@code yyyy-MM-dd} date. All parse attempts run in UTC.
     */
    static Date parseIso8601( final String s ) throws ParseException {
        final String[] patterns = {
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd"
        };
        ParseException last = null;
        for ( final String p : patterns ) {
            final SimpleDateFormat f = new SimpleDateFormat( p );
            f.setTimeZone( TimeZone.getTimeZone( "UTC" ) );
            f.setLenient( false );
            try {
                return f.parse( s );
            } catch ( final ParseException e ) {
                last = e;
            }
        }
        throw last == null ? new ParseException( s, 0 ) : last;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
mvn -pl wikantik-rest test -Dtest=ChangesResourceTest -q
```

Expected: 5 tests run, 0 failures.

- [ ] **Step 5: Register the servlet in `web.xml`**

Modify `wikantik-war/src/main/webapp/WEB-INF/web.xml`. Insert the servlet declaration immediately after the existing `RecentChangesResource` declaration (line 308):

```xml
   <servlet>
       <servlet-name>ChangesResource</servlet-name>
       <servlet-class>com.wikantik.rest.ChangesResource</servlet-class>
   </servlet>
```

Then add the servlet-mapping immediately after the existing `RecentChangesResource` mapping (line 438):

```xml
   <servlet-mapping>
       <servlet-name>ChangesResource</servlet-name>
       <url-pattern>/api/changes</url-pattern>
   </servlet-mapping>
```

- [ ] **Step 6: Rebuild the WAR to verify web.xml parses**

```bash
mvn -pl wikantik-war install -DskipTests -Dminimize=false -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/ChangesResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/ChangesResourceTest.java \
        wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(rest): add /api/changes?since= feed for OpenWebUI sync script"
```

---

### Task 3: Add `dateModified` to `SemanticHeadRenderer` JSON-LD

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/ui/SemanticHeadRenderer.java`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java:253-303` (pass modified date)
- Modify / Create: `wikantik-main/src/test/java/com/wikantik/ui/SemanticHeadRendererTest.java` — add `dateModified` assertion

**Design notes:**

- Spec requires JSON-LD `dateModified` for crawler indexing freshness. The existing renderer only emits `datePublished` from frontmatter `date`.
- The change is minimal: add an overload `renderHead(pageName, rawPageText, baseUrl, appName, modified)` that emits `"dateModified":"<ISO 8601>"` in the main JSON-LD block. Keep the old 4-arg overload by delegating to the new one with `null`.
- `SpaRoutingFilter.injectSemantic` already has access to `page.getLastModified()` — line 272 fetches the `Page` object — so threading it through is a one-line change.
- Date format: `yyyy-MM-dd'T'HH:mm:ss'Z'` in UTC, matching the rest of the API.

- [ ] **Step 1: Write the failing test**

If `wikantik-main/src/test/java/com/wikantik/ui/SemanticHeadRendererTest.java` does not exist, create it. Otherwise add just the test method. File content if new:

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
package com.wikantik.ui;

import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticHeadRendererTest {

    @Test
    void testDateModifiedIsEmittedInJsonLd() throws Exception {
        final SimpleDateFormat fmt = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss'Z'" );
        fmt.setTimeZone( TimeZone.getTimeZone( "UTC" ) );
        final Date modified = fmt.parse( "2026-04-02T14:05:00Z" );

        final String head = SemanticHeadRenderer.renderHead(
                "LinuxSystemAdministration",
                "# Linux System Administration\n\nBody text.\n",
                "https://wiki.example.com",
                "Wikantik",
                modified );

        assertTrue( head.contains( "\"dateModified\":\"2026-04-02T14:05:00Z\"" ),
                "JSON-LD must contain dateModified; was: " + head );
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl wikantik-main test -Dtest=SemanticHeadRendererTest#testDateModifiedIsEmittedInJsonLd -q
```

Expected: compile error — `renderHead` has no 5-arg overload taking `Date`.

- [ ] **Step 3: Add the 5-arg overload and JSON-LD field**

Modify `wikantik-main/src/main/java/com/wikantik/ui/SemanticHeadRenderer.java`. Replace the existing 4-arg `renderHead` method (lines 62-151) with a new 5-arg overload that accepts `Date modified`, and add a 4-arg delegating overload for backwards compatibility.

Change the method signature and body. Find this method:

```java
    public static String renderHead( final String pageName, final String rawPageText,
                                      final String baseUrl, final String appName ) {
```

Replace the signature with:

```java
    /**
     * Backwards-compatible overload. Delegates to
     * {@link #renderHead(String, String, String, String, Date)} with a
     * {@code null} modified date.
     */
    public static String renderHead( final String pageName, final String rawPageText,
                                      final String baseUrl, final String appName ) {
        return renderHead( pageName, rawPageText, baseUrl, appName, null );
    }

    /**
     * Render the full semantic {@code <head>} fragment for a page, including
     * a {@code dateModified} in the JSON-LD when {@code modified} is non-null.
     */
    public static String renderHead( final String pageName, final String rawPageText,
                                      final String baseUrl, final String appName,
                                      final Date modified ) {
```

Inside the new body, change the call to `buildMainJsonLd` from:

```java
        sb.append( buildMainJsonLd( safePageName, safeAppName, canonical, safeBaseUrl,
                effectiveDescription, effectiveKeywords, pageDate, cluster, isHub, related ) );
```

to:

```java
        sb.append( buildMainJsonLd( safePageName, safeAppName, canonical, safeBaseUrl,
                effectiveDescription, effectiveKeywords, pageDate, cluster, isHub, related, modified ) );
```

Then update `buildMainJsonLd`'s signature (currently lines 203-207) from:

```java
    private static String buildMainJsonLd( final String pageName, final String appName,
                                            final String canonical, final String baseUrl,
                                            final String description, final String keywords,
                                            final String datePublished, final String cluster,
                                            final boolean isHub, final List< String > related ) {
```

to:

```java
    private static String buildMainJsonLd( final String pageName, final String appName,
                                            final String canonical, final String baseUrl,
                                            final String description, final String keywords,
                                            final String datePublished, final String cluster,
                                            final boolean isHub, final List< String > related,
                                            final Date modified ) {
```

Inside `buildMainJsonLd`, immediately after the `datePublished` block (currently lines 239-241):

```java
        if ( !datePublished.isBlank() ) {
            sb.append( "\"datePublished\":" ).append( jsonStr( datePublished ) ).append( "," );
        }
```

add the `dateModified` emission:

```java
        if ( modified != null ) {
            final SimpleDateFormat modFmt = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss'Z'" );
            modFmt.setTimeZone( TimeZone.getTimeZone( "UTC" ) );
            sb.append( "\"dateModified\":" ).append( jsonStr( modFmt.format( modified ) ) ).append( "," );
        }
```

Add the missing import at the top of the file alongside the existing `java.text.SimpleDateFormat`:

```java
import java.util.TimeZone;
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
mvn -pl wikantik-main test -Dtest=SemanticHeadRendererTest#testDateModifiedIsEmittedInJsonLd -q
```

Expected: PASS.

- [ ] **Step 5: Thread `page.getLastModified()` through `SpaRoutingFilter.injectSemantic`**

Modify `wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java`, the `injectSemantic` method. Find this line (currently 283):

```java
        final String head = SemanticHeadRenderer.renderHead( pageName, rawText, baseUrl, appName );
```

Replace with:

```java
        final String head = SemanticHeadRenderer.renderHead( pageName, rawText, baseUrl, appName,
                page.getLastModified() );
```

The `page` variable is already in scope (declared on line 272).

- [ ] **Step 6: Compile `wikantik-rest` to verify the call resolves**

```bash
mvn -pl wikantik-rest compile -q
```

Expected: BUILD SUCCESS. (The new 5-arg overload comes from `wikantik-main`, which `wikantik-rest` already depends on.)

- [ ] **Step 7: Run the full `wikantik-main` test suite to catch regressions**

```bash
mvn -pl wikantik-main test -Dtest='SemanticHeadRenderer*' -q
```

Expected: all tests green. If any existing test calls the 4-arg `renderHead` overload, it will still work because the delegating overload was kept.

- [ ] **Step 8: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/ui/SemanticHeadRenderer.java \
        wikantik-main/src/test/java/com/wikantik/ui/SemanticHeadRendererTest.java \
        wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java
git commit -m "feat(seo): emit dateModified in page JSON-LD from Page.getLastModified"
```

---

### Task 4: Sitemap `<lastmod>` accuracy test

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/ui/SitemapServletTest.java`

**Design notes:**

- The existing assertion at line 207 (`assertTrue( sitemap.contains( "<lastmod>" ) )`) only proves the element is present. We want to prove its value reflects `page.getLastModified()`.
- The new test saves a page, reads its modification time, fetches the sitemap, and asserts the sitemap contains a `<lastmod>` matching that date formatted as `yyyy-MM-dd`.

- [ ] **Step 1: Add the new test to `SitemapServletTest.java`**

Append (before the final `}` closing the test class):

```java
    @Test
    void testLastmodMatchesPageModificationTime() throws Exception {
        // Save a fresh page and capture its server-recorded modification time.
        engine.saveText( "SitemapLastmodPage", "Body for lastmod check." );
        final com.wikantik.api.core.Page page =
                engine.getManager( com.wikantik.api.managers.PageManager.class )
                      .getPage( "SitemapLastmodPage" );
        Assertions.assertNotNull( page );
        Assertions.assertNotNull( page.getLastModified() );
        final String expectedDate = new java.text.SimpleDateFormat( "yyyy-MM-dd" )
                .format( page.getLastModified() );

        // Fetch the sitemap.
        final String sitemap = fetchSitemap();

        // The sitemap must contain the page URL and its <lastmod> must match the
        // date component of Page.getLastModified() (not the deploy date).
        Assertions.assertTrue( sitemap.contains( "SitemapLastmodPage" ),
                "Sitemap should list the newly saved page" );
        Assertions.assertTrue(
                sitemap.contains( "<lastmod>" + expectedDate + "</lastmod>" ),
                "Sitemap must contain <lastmod>" + expectedDate + "</lastmod>; was: " + sitemap );
    }
```

**Note:** This test assumes there is already a `fetchSitemap()` helper or an equivalent pattern in the existing test class. If not, use the same request/response setup as the existing test at line ~207. Read the existing test file once with `Read wikantik-main/src/test/java/com/wikantik/ui/SitemapServletTest.java` before editing to match its request-building pattern.

- [ ] **Step 2: Run the sitemap tests**

```bash
mvn -pl wikantik-main test -Dtest=SitemapServletTest -q
```

Expected: all tests green, including the new `testLastmodMatchesPageModificationTime`.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/ui/SitemapServletTest.java
git commit -m "test(sitemap): assert <lastmod> equals Page.getLastModified() date"
```

---

### Task 5: robots.txt sitemap reference test

**Files:**
- Create: `wikantik-war/src/test/java/com/wikantik/war/RobotsTxtTest.java`

**Design notes:**

- `robots.txt` is a static file served by Tomcat's default servlet; there's no servlet to unit-test. The point of this test is to prevent future edits from removing the `Sitemap:` directive, since OpenWebUI's sync script and every external crawler rely on it.
- We read the file directly from the module's source path and assert its contents.
- The `wikantik-war` module does not currently have any tests (per the file structure). This is the first one; if the module's `pom.xml` does not yet wire Surefire, add the plugin in Step 4.

- [ ] **Step 1: Check the `wikantik-war` module for existing test infrastructure**

```bash
ls wikantik-war/src/test 2>/dev/null || echo "no test dir"
```

If the output is `no test dir`, the test dir will be created implicitly in Step 2.

- [ ] **Step 2: Create the test file**

Create `wikantik-war/src/test/java/com/wikantik/war/RobotsTxtTest.java`:

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
package com.wikantik.war;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards robots.txt against accidental removal of the Sitemap: directive.
 * OpenWebUI's sync script and external crawlers both depend on it.
 */
class RobotsTxtTest {

    @Test
    void testRobotsTxtContainsSitemapLine() throws Exception {
        final Path robots = Paths.get( "src", "main", "webapp", "robots.txt" );
        assertTrue( Files.exists( robots ), "robots.txt must exist at " + robots.toAbsolutePath() );

        final String content = Files.readString( robots );
        assertTrue( content.contains( "Sitemap: " ),
                "robots.txt must contain a 'Sitemap:' directive; was: " + content );
        assertTrue( content.contains( "/sitemap.xml" ),
                "robots.txt Sitemap: must reference /sitemap.xml; was: " + content );
    }
}
```

- [ ] **Step 3: Attempt to run the test**

```bash
mvn -pl wikantik-war test -Dtest=RobotsTxtTest -q
```

Expected: PASS (the test reads the existing file and asserts its content).

If Maven reports **"No tests to run"**, the `wikantik-war` module has Surefire disabled or misconfigured. Proceed to Step 4 to wire it up. Otherwise skip to Step 5.

- [ ] **Step 4 (conditional): Enable Surefire in `wikantik-war/pom.xml`**

Only run this step if Step 3 reported "No tests to run" or failed with a missing-plugin error.

Add the following plugin to `wikantik-war/pom.xml` inside `<build><plugins>`:

```xml
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
      </plugin>
```

Also add a JUnit 5 dependency to `<dependencies>`:

```xml
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
```

Re-run `mvn -pl wikantik-war test -Dtest=RobotsTxtTest -q`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-war/src/test/java/com/wikantik/war/RobotsTxtTest.java
# Only add pom.xml if Step 4 was required:
# git add wikantik-war/pom.xml
git commit -m "test(war): guard robots.txt Sitemap: directive against regressions"
```

---

### Task 6: Full build + end-to-end acceptance verification

**Files:** None (verification only).

- [ ] **Step 1: Full unit-test build**

```bash
mvn clean install -T 1C -DskipITs
```

Expected: BUILD SUCCESS across all modules.

- [ ] **Step 2: Redeploy to local Tomcat**

```bash
tomcat/tomcat-11/bin/shutdown.sh
rm -rf tomcat/tomcat-11/webapps/ROOT
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
```

Wait ~10 seconds for Tomcat to deploy, then confirm the server is up:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/wiki/Main
```

Expected: `200`.

- [ ] **Step 3: Acceptance check — markdown format**

```bash
curl -s -i "http://localhost:8080/wiki/LinuxSystemAdministration?format=md" | head -20
```

Expected:
- `HTTP/1.1 200` (or 404 if that specific page does not exist locally — pick an existing page such as `Main`)
- `Content-Type: text/markdown; charset=UTF-8`
- Body begins with `# <Page Title>` followed by a blank line and then the page body

- [ ] **Step 4: Acceptance check — JSON format**

```bash
curl -s "http://localhost:8080/wiki/Main?format=json" | head -40
```

Expected: JSON object containing `slug`, `title`, `content`, `summary`, `tags`, `created_at`, `modified_at`.

- [ ] **Step 5: Acceptance check — changes feed (no since)**

```bash
curl -s "http://localhost:8080/api/changes" | head -40
```

Expected: JSON with `since: null`, `generated_at` set, and a `pages` array containing at least a few entries, each with `slug`, `modified_at`, and `url`.

- [ ] **Step 6: Acceptance check — changes feed (with since)**

```bash
curl -s "http://localhost:8080/api/changes?since=2099-01-01T00:00:00Z" | head -20
```

Expected: JSON with `pages: []` (no page is modified after year 2099).

- [ ] **Step 7: Acceptance check — changes feed (invalid since)**

```bash
curl -s -w "\nHTTP %{http_code}\n" "http://localhost:8080/api/changes?since=not-a-date"
```

Expected: `HTTP 400` with a JSON error body.

- [ ] **Step 8: Acceptance check — crawler semantic head**

```bash
curl -s "http://localhost:8080/wiki/Main" | grep -E '(dateModified|og:title|application/ld\+json)'
```

Expected: at least one match line per regex. The JSON-LD block must contain `"dateModified":"<ISO timestamp>"`.

- [ ] **Step 9: Acceptance check — sitemap lastmod**

```bash
curl -s "http://localhost:8080/sitemap.xml" | grep -E '<lastmod>' | head -5
```

Expected: `<lastmod>YYYY-MM-DD</lastmod>` entries with real dates.

- [ ] **Step 10: Acceptance check — existing SPA still works**

Open `http://localhost:8080/wiki/Main` in a browser. Expected: the React SPA loads normally, navigates client-side, and no format-specific content is visible to users.

- [ ] **Step 11: Run integration tests for a final verification**

```bash
mvn clean install -Pintegration-tests -fae
```

Expected: BUILD SUCCESS. Per `CLAUDE.md`, integration tests must run sequentially (no `-T` flag) and with `-fae` so all three IT modules complete.

- [ ] **Step 12: Final commit marker (optional)**

If any cosmetic fixes were needed during acceptance, commit them here. Otherwise, no commit is necessary — the feature tasks each produced their own commits.

---

## Acceptance Criteria (from `IndexingSupport.md`)

- [x] `GET /wiki/{slug}?format=md` returns HTTP 200, `Content-Type: text/markdown`, body = page markdown with H1 title — **Task 1**
- [x] `GET /wiki/{slug}?format=json` returns HTTP 200, valid JSON matching the schema — **Task 1**
- [x] `GET /api/changes` returns HTTP 200 with the JSON array of modified pages — **Task 2**
- [x] `/wiki/{slug}` with a crawler UA returns HTTP 200 with full HTML content (not 404) — **already implemented** by `SpaRoutingFilter` + `SemanticHeadRenderer`; **Task 6 Step 8** verifies.
- [x] `<title>`, `<meta name="description">`, and JSON-LD are present in crawler-served HTML — **already implemented**; **Task 3** adds the spec's one missing field (`dateModified`) to the JSON-LD.
- [x] Existing browser SPA behavior is unchanged — `WikiPageFormatFilter` only intercepts requests with a `format` query parameter, so the SPA path is untouched; **Task 6 Step 10** verifies in a browser.

---

## Out of scope (documented for future work)

- **Image-link rewriting inside markdown output.** Images with relative attachment paths (e.g., `![Diagram](Page/diagram.png)`) are left unchanged. The spec permits this ("absolute URLs or omitted"), and the current behavior is "omit from rewriting." Absolute `/attach/...` paths are passed through as-is because the `isInternalTarget` check rejects targets containing `/`.
- **Crawler-UA-conditional rendering.** Intentionally not implemented; see the "No Crawler-UA Sniffer" decision above.
- **Caching of markdown/JSON responses.** The existing `CacheHeaderFilter` handles generic cases; if sync-script load becomes a concern, add ETag/Last-Modified headers in a follow-up.
- **OpenWebUI sync script itself.** Per `IndexingSupport.md` §Integration Contract, the sync script "will be maintained separately in this repo once the wiki endpoints are live." This plan lands the endpoints only.
