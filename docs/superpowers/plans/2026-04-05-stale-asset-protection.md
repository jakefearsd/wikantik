# Stale Asset Protection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate blank pages after SPA redeployment by adding three defensive layers: auto-recovery, build version checking, and cache header coverage.

**Architecture:** Three independent layers — (1) inline script in `index.html` catches chunk-load 404s and reloads once, (2) Vite build-version plugin + Java filter + SPA detection shows a toast when versions drift, (3) a `CacheHeaderFilter` ensures correct `Cache-Control` on `/assets/*` and `/index.html` regardless of how they're requested.

**Tech Stack:** Vite (inline plugin), Java servlet filters (JUnit 5 + Mockito), React (useState/useEffect), CSS.

---

### Task 1: CacheHeaderFilter — Test and Implementation

**Files:**
- Create: `wikantik-http/src/main/java/com/wikantik/http/filter/CacheHeaderFilter.java`
- Create: `wikantik-http/src/test/java/com/wikantik/http/filter/CacheHeaderFilterTest.java`

- [ ] **Step 1: Write the test class**

```java
/*
 * Copyright 2025 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wikantik.http.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

/**
 * Tests for {@link CacheHeaderFilter} — ensures correct Cache-Control headers
 * for hashed assets, index.html, and passthrough for everything else.
 */
class CacheHeaderFilterTest {

    private CacheHeaderFilter filter;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() throws Exception {
        filter = new CacheHeaderFilter();
        filter.init( null );
        response = mock( HttpServletResponse.class );
        chain = mock( FilterChain.class );
    }

    @Test
    void testHashedJsAssetGetsImmutableCache() throws Exception {
        final HttpServletRequest request = mockRequest( "/assets/index-BCNdZRMf.js" );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "Cache-Control", "public, max-age=31536000, immutable" );
        verify( chain ).doFilter( request, response );
    }

    @Test
    void testHashedCssAssetGetsImmutableCache() throws Exception {
        final HttpServletRequest request = mockRequest( "/assets/index-CCel3tKT.css" );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "Cache-Control", "public, max-age=31536000, immutable" );
        verify( chain ).doFilter( request, response );
    }

    @Test
    void testIndexHtmlGetsNoCache() throws Exception {
        final HttpServletRequest request = mockRequest( "/index.html" );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "Cache-Control", "no-cache" );
        verify( chain ).doFilter( request, response );
    }

    @Test
    void testNonHashedAssetDoesNotSetCacheHeader() throws Exception {
        final HttpServletRequest request = mockRequest( "/favicon.svg" );

        filter.doFilter( request, response, chain );

        verify( response, never() ).setHeader( eq( "Cache-Control" ), anyString() );
        verify( chain ).doFilter( request, response );
    }

    @Test
    void testApiCallDoesNotSetCacheHeader() throws Exception {
        final HttpServletRequest request = mockRequest( "/api/pages/Main" );

        filter.doFilter( request, response, chain );

        verify( response, never() ).setHeader( eq( "Cache-Control" ), anyString() );
        verify( chain ).doFilter( request, response );
    }

    @Test
    void testSpaRouteDoesNotSetCacheHeader() throws Exception {
        final HttpServletRequest request = mockRequest( "/wiki/Main" );

        filter.doFilter( request, response, chain );

        verify( response, never() ).setHeader( eq( "Cache-Control" ), anyString() );
        verify( chain ).doFilter( request, response );
    }

    @Test
    void testShortHashNotMatchedAsImmutable() throws Exception {
        // Hash must be 6+ chars to match Vite pattern
        final HttpServletRequest request = mockRequest( "/assets/index-AB.js" );

        filter.doFilter( request, response, chain );

        verify( response, never() ).setHeader( eq( "Cache-Control" ), anyString() );
        verify( chain ).doFilter( request, response );
    }

    private HttpServletRequest mockRequest( final String uri ) {
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getRequestURI() ).thenReturn( uri );
        return request;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-http -Dtest=CacheHeaderFilterTest -q`
Expected: Compilation failure — `CacheHeaderFilter` class does not exist yet.

- [ ] **Step 3: Write the CacheHeaderFilter implementation**

```java
/*
 * Copyright 2025 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wikantik.http.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Sets {@code Cache-Control} headers for static assets and the SPA entry point.
 *
 * <ul>
 *   <li>{@code /assets/*} with a Vite content hash (6+ alphanumeric chars before the
 *       extension) receives {@code public, max-age=31536000, immutable} — safe because
 *       the hash changes whenever the file content changes.</li>
 *   <li>{@code /index.html} receives {@code no-cache} — forces the browser to revalidate
 *       on every request so it always gets the latest asset references.</li>
 *   <li>All other paths pass through without a {@code Cache-Control} header.</li>
 * </ul>
 */
public class CacheHeaderFilter implements Filter {

    /** Matches Vite-style content-hashed filenames, e.g. {@code index-BCNdZRMf.js}. */
    private static final Pattern HASHED_ASSET = Pattern.compile( "-[A-Za-z0-9]{6,}\\." );

    @Override
    public void init( final FilterConfig filterConfig ) throws ServletException {
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                           final FilterChain chain ) throws IOException, ServletException {
        final HttpServletRequest req = ( HttpServletRequest ) request;
        final HttpServletResponse resp = ( HttpServletResponse ) response;
        final String path = req.getRequestURI();

        if ( path.startsWith( "/assets/" ) && HASHED_ASSET.matcher( path ).find() ) {
            resp.setHeader( "Cache-Control", "public, max-age=31536000, immutable" );
        } else if ( "/index.html".equals( path ) ) {
            resp.setHeader( "Cache-Control", "no-cache" );
        }

        chain.doFilter( request, response );
    }

    @Override
    public void destroy() {
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-http -Dtest=CacheHeaderFilterTest -q`
Expected: All 7 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-http/src/main/java/com/wikantik/http/filter/CacheHeaderFilter.java \
       wikantik-http/src/test/java/com/wikantik/http/filter/CacheHeaderFilterTest.java
git commit -m "feat: add CacheHeaderFilter for immutable hashed assets and no-cache index.html"
```

---

### Task 2: Clean Up SpaRoutingFilter and Register CacheHeaderFilter

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java`
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/SpaRoutingFilterTest.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`

- [ ] **Step 1: Update SpaRoutingFilterTest — remove asset caching tests, keep passthrough tests**

The three tests `testHashedAssetSetsImmutableCacheHeader`, `testHashedCssAssetSetsImmutableCacheHeader`, and `testNonHashedStaticAssetDoesNotSetImmutableCache` should be removed since `CacheHeaderFilter` now owns asset caching. The passthrough tests (`testJsAssetsPassThrough`, `testCssAssetsPassThrough`, `testFaviconPassesThrough`) stay because `SpaRoutingFilter` still lets static assets through.

In `wikantik-rest/src/test/java/com/wikantik/rest/SpaRoutingFilterTest.java`, remove:

```java
    // ---- Cache-Control header tests ----

    @Test
    void testSpaForwardSetsNoCacheHeader() throws Exception {
        final HttpServletRequest request = mockRequest( "/wiki/SomePage" );
        final RequestDispatcher dispatcher = mock( RequestDispatcher.class );
        when( request.getRequestDispatcher( "/index.html" ) ).thenReturn( dispatcher );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "Cache-Control", "no-cache" );
    }

    @Test
    void testHashedAssetSetsImmutableCacheHeader() throws Exception {
        final HttpServletRequest request = mockRequest( "/assets/index-BCNdZRMf.js" );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "Cache-Control", "public, max-age=31536000, immutable" );
    }

    @Test
    void testHashedCssAssetSetsImmutableCacheHeader() throws Exception {
        final HttpServletRequest request = mockRequest( "/assets/index-CCel3tKT.css" );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "Cache-Control", "public, max-age=31536000, immutable" );
    }

    @Test
    void testNonHashedStaticAssetDoesNotSetImmutableCache() throws Exception {
        final HttpServletRequest request = mockRequest( "/favicon.svg" );

        filter.doFilter( request, response, chain );

        verify( response, never() ).setHeader( eq( "Cache-Control" ), anyString() );
    }
```

Replace with a single test that verifies the SPA forward still sets `no-cache` (this header remains because it's about SPA routing, not static asset caching):

```java
    // ---- Cache-Control header tests ----

    @Test
    void testSpaForwardSetsNoCacheHeader() throws Exception {
        final HttpServletRequest request = mockRequest( "/wiki/SomePage" );
        final RequestDispatcher dispatcher = mock( RequestDispatcher.class );
        when( request.getRequestDispatcher( "/index.html" ) ).thenReturn( dispatcher );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "Cache-Control", "no-cache" );
    }
```

- [ ] **Step 2: Remove dead asset caching code from SpaRoutingFilter**

In `wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java`, replace the static asset block (lines 75–84):

```java
        // Let static assets through (JS, CSS, images, fonts, favicon)
        if ( path.contains( "." ) && !path.endsWith( ".html" ) ) {
            // Hashed assets (e.g. /assets/index-BCNdZRMf.js) can be cached forever —
            // the hash changes whenever the content changes.
            if ( path.startsWith( "/assets/" ) && HASHED_ASSET.matcher( path ).find() ) {
                resp.setHeader( "Cache-Control", "public, max-age=31536000, immutable" );
            }
            chain.doFilter( request, response );
            return;
        }
```

With the simplified version (no caching code — `CacheHeaderFilter` handles it):

```java
        // Let static assets through (JS, CSS, images, fonts, favicon).
        // Cache headers are set by CacheHeaderFilter.
        if ( path.contains( "." ) && !path.endsWith( ".html" ) ) {
            chain.doFilter( request, response );
            return;
        }
```

Also remove the now-unused `HASHED_ASSET` pattern and `java.util.regex.Pattern` import from `SpaRoutingFilter`.

Remove this field:

```java
    /** Matches Vite-style content-hashed filenames, e.g. {@code index-BCNdZRMf.js}. */
    private static final Pattern HASHED_ASSET = Pattern.compile( "-[A-Za-z0-9]{6,}\\." );
```

Remove this import:

```java
import java.util.regex.Pattern;
```

- [ ] **Step 3: Run SpaRoutingFilter tests**

Run: `mvn test -pl wikantik-rest -Dtest=SpaRoutingFilterTest -q`
Expected: All remaining tests pass (redirect, forward, passthrough tests).

- [ ] **Step 4: Register CacheHeaderFilter in web.xml**

In `wikantik-war/src/main/webapp/WEB-INF/web.xml`, add the `CacheHeaderFilter` definition and mapping **before** the `SpaRoutingFilter` block (insert before line 84 — the `<!-- React SPA routing -->` comment):

```xml
   <!-- Cache headers: immutable for hashed assets, no-cache for index.html -->
   <filter>
     <filter-name>CacheHeaderFilter</filter-name>
     <filter-class>com.wikantik.http.filter.CacheHeaderFilter</filter-class>
     <async-supported>true</async-supported>
   </filter>
   <filter-mapping>
     <filter-name>CacheHeaderFilter</filter-name>
     <url-pattern>/*</url-pattern>
   </filter-mapping>

```

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java \
       wikantik-rest/src/test/java/com/wikantik/rest/SpaRoutingFilterTest.java \
       wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "refactor: move asset cache headers to CacheHeaderFilter, remove dead code from SpaRoutingFilter"
```

---

### Task 3: BuildVersionFilter — Test and Implementation

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/BuildVersionFilter.java`
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/BuildVersionFilterTest.java`

- [ ] **Step 1: Write the test class**

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

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.*;

/**
 * Tests for {@link BuildVersionFilter} — verifies the X-Build-Version header is
 * set from build-version.txt, and gracefully handles missing version files.
 */
class BuildVersionFilterTest {

    @Test
    void testAddsVersionHeaderWhenFileExists() throws Exception {
        final BuildVersionFilter filter = new BuildVersionFilter();
        final FilterConfig config = mockConfigWithVersion( "1712345678901" );
        filter.init( config );

        final HttpServletRequest request = mock( HttpServletRequest.class );
        final HttpServletResponse response = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "X-Build-Version", "1712345678901" );
        verify( chain ).doFilter( request, response );
    }

    @Test
    void testNoHeaderWhenVersionFileMissing() throws Exception {
        final BuildVersionFilter filter = new BuildVersionFilter();
        final FilterConfig config = mockConfigWithVersion( null );
        filter.init( config );

        final HttpServletRequest request = mock( HttpServletRequest.class );
        final HttpServletResponse response = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );

        filter.doFilter( request, response, chain );

        verify( response, never() ).setHeader( eq( "X-Build-Version" ), anyString() );
        verify( chain ).doFilter( request, response );
    }

    @Test
    void testVersionIsTrimmed() throws Exception {
        final BuildVersionFilter filter = new BuildVersionFilter();
        final FilterConfig config = mockConfigWithVersion( "  1712345678901\n" );
        filter.init( config );

        final HttpServletRequest request = mock( HttpServletRequest.class );
        final HttpServletResponse response = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "X-Build-Version", "1712345678901" );
    }

    /**
     * Creates a FilterConfig whose ServletContext returns an InputStream for
     * /build-version.txt containing the given version string (or null if version is null).
     */
    private FilterConfig mockConfigWithVersion( final String version ) {
        final ServletContext context = mock( ServletContext.class );
        if ( version != null ) {
            final InputStream stream = new ByteArrayInputStream( version.getBytes( StandardCharsets.UTF_8 ) );
            when( context.getResourceAsStream( "/build-version.txt" ) ).thenReturn( stream );
        } else {
            when( context.getResourceAsStream( "/build-version.txt" ) ).thenReturn( null );
        }
        final FilterConfig config = mock( FilterConfig.class );
        when( config.getServletContext() ).thenReturn( context );
        return config;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-rest -Dtest=BuildVersionFilterTest -q`
Expected: Compilation failure — `BuildVersionFilter` class does not exist yet.

- [ ] **Step 3: Write the BuildVersionFilter implementation**

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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Adds an {@code X-Build-Version} response header to every request, allowing
 * the SPA to detect when the server has been redeployed with a new build.
 *
 * <p>The version is read once at init from {@code /build-version.txt} in the
 * WAR root (generated by the Vite build plugin). If the file is missing,
 * no header is set.
 */
public class BuildVersionFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( BuildVersionFilter.class );

    private String buildVersion;

    @Override
    public void init( final FilterConfig config ) throws ServletException {
        try ( final InputStream in = config.getServletContext().getResourceAsStream( "/build-version.txt" ) ) {
            if ( in != null ) {
                buildVersion = new String( in.readAllBytes(), StandardCharsets.UTF_8 ).trim();
                LOG.info( "Build version: {}", buildVersion );
            } else {
                LOG.warn( "build-version.txt not found — X-Build-Version header will not be set" );
            }
        } catch ( final IOException e ) {
            LOG.warn( "Failed to read build-version.txt", e );
        }
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                           final FilterChain chain ) throws IOException, ServletException {
        if ( buildVersion != null ) {
            ( ( HttpServletResponse ) response ).setHeader( "X-Build-Version", buildVersion );
        }
        chain.doFilter( request, response );
    }

    @Override
    public void destroy() {
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-rest -Dtest=BuildVersionFilterTest -q`
Expected: All 3 tests pass.

- [ ] **Step 5: Register BuildVersionFilter in web.xml**

In `wikantik-war/src/main/webapp/WEB-INF/web.xml`, add after the `CacheHeaderFilter` mapping and before the `SpaRoutingFilter` block:

```xml
   <!-- Build version header: lets the SPA detect redeployments -->
   <filter>
     <filter-name>BuildVersionFilter</filter-name>
     <filter-class>com.wikantik.rest.BuildVersionFilter</filter-class>
   </filter>
   <filter-mapping>
     <filter-name>BuildVersionFilter</filter-name>
     <url-pattern>/api/*</url-pattern>
     <url-pattern>/admin/*</url-pattern>
   </filter-mapping>

```

- [ ] **Step 6: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/BuildVersionFilter.java \
       wikantik-rest/src/test/java/com/wikantik/rest/BuildVersionFilterTest.java \
       wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat: add BuildVersionFilter to set X-Build-Version header on API responses"
```

---

### Task 4: Vite Build Version Plugin

**Files:**
- Modify: `wikantik-frontend/vite.config.js`

- [ ] **Step 1: Add the buildVersionPlugin to vite.config.js**

Add the plugin function before the `export default` and include it in the `plugins` array:

```js
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

function buildVersionPlugin() {
  const version = Date.now().toString();
  return {
    name: 'build-version',
    config() {
      return { define: { __BUILD_VERSION__: JSON.stringify(version) } };
    },
    generateBundle() {
      this.emitFile({
        type: 'asset',
        fileName: 'build-version.txt',
        source: version,
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), buildVersionPlugin()],
  test: {
    environment: 'node',
  },
  base: '/',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/attach': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/admin': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
```

- [ ] **Step 2: Test the plugin produces build-version.txt**

Run: `cd wikantik-frontend && npx vite build && cat dist/build-version.txt && cd ..`
Expected: A numeric timestamp is printed (e.g., `1712345678901`) and `dist/build-version.txt` exists.

- [ ] **Step 3: Commit**

```bash
git add wikantik-frontend/vite.config.js
git commit -m "feat: add Vite build-version plugin — emits build-version.txt and __BUILD_VERSION__ constant"
```

---

### Task 5: Auto-Recovery Script in index.html (Layer 1)

**Files:**
- Modify: `wikantik-frontend/index.html`
- Modify: `wikantik-frontend/src/main.jsx`

- [ ] **Step 1: Add the inline recovery script to index.html**

In `wikantik-frontend/index.html`, add a `<script>` block inside `<body>` before the `<div id="root">`:

```html
<!DOCTYPE html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Wikantik</title>
    <link rel="preconnect" href="https://fonts.googleapis.com" />
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
    <link href="https://fonts.googleapis.com/css2?family=Playfair+Display:ital,wght@0,400..900;1,400..900&family=Source+Serif+4:ital,opsz,wght@0,8..60,200..900;1,8..60,200..900&family=DM+Sans:ital,opsz,wght@0,9..40,100..1000;1,9..40,100..1000&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet" />
    <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
  </head>
  <body>
    <script>
      // Auto-recovery: if the JS or CSS bundle 404s after a deploy, reload once.
      // Uses sessionStorage to prevent infinite reload loops.
      window.addEventListener('error', function(e) {
        var tag = e.target && e.target.tagName;
        if ((tag === 'SCRIPT' || tag === 'LINK') && !sessionStorage.getItem('__wikantik_reload')) {
          sessionStorage.setItem('__wikantik_reload', '1');
          window.location.reload();
        }
      }, true);
    </script>
    <div id="root"></div>
    <script type="module" src="/src/main.jsx"></script>
  </body>
</html>
```

- [ ] **Step 2: Clear the reload flag in main.jsx after successful render**

In `wikantik-frontend/src/main.jsx`, add the `sessionStorage.removeItem` call after the `render()` call:

```js
ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <AuthProvider>
      <BrowserRouter basename="/">
        <Routes>
          <Route element={<App />}>
            <Route path="/" element={<Navigate to="/wiki/Main" replace />} />
            <Route path="/wiki/:name" element={<PageView />} />
            <Route path="/wiki" element={<Navigate to="/wiki/Main" replace />} />
            <Route path="/edit/blog/:username/:pageName" element={<BlogEditor />} />
            <Route path="/edit/:name" element={<PageEditor />} />
            <Route path="/diff/:name" element={<DiffViewer />} />
            <Route path="/search" element={<SearchResultsPage />} />
            <Route path="/preferences" element={<UserPreferencesPage />} />
            <Route path="/reset-password" element={<ResetPasswordPage />} />
            <Route path="/admin" element={<AdminLayout />}>
              <Route index element={<Navigate to="users" replace />} />
              <Route path="users" element={<AdminUsersPage />} />
              <Route path="content" element={<AdminContentPage />} />
              <Route path="security" element={<AdminSecurityPage />} />
              <Route path="knowledge" element={<AdminKnowledgePage />} />
            </Route>
            <Route path="/blog" element={<BlogDiscovery />} />
            <Route path="/blog/create" element={<CreateBlog />} />
            <Route path="/blog/:username/new" element={<NewBlogEntry />} />
            <Route path="/blog/:username/Blog" element={<BlogHome />} />
            <Route path="/blog/:username/:entryName" element={<BlogEntry />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  </React.StrictMode>
);

// App rendered successfully — clear the stale-asset reload flag so that
// the next deploy can trigger a fresh reload if needed.
sessionStorage.removeItem('__wikantik_reload');
```

- [ ] **Step 3: Verify the frontend builds**

Run: `cd wikantik-frontend && npx vite build && cd ..`
Expected: Build succeeds without errors.

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/index.html wikantik-frontend/src/main.jsx
git commit -m "feat: add inline auto-recovery script for stale asset load failures"
```

---

### Task 6: SPA Version Mismatch Detection in API Client (Layer 2c — client.js)

**Files:**
- Modify: `wikantik-frontend/src/api/client.js`

- [ ] **Step 1: Add version check to the request() function**

In `wikantik-frontend/src/api/client.js`, add a module-level flag and version check logic:

```js
const BASE = '';

/* global __BUILD_VERSION__ */
let versionMismatchSignaled = false;

async function request(path, options = {}) {
  const { signal, ...rest } = options;
  const resp = await fetch(`${BASE}${path}`, {
    credentials: 'same-origin',
    headers: { 'Accept': 'application/json', 'Content-Type': 'application/json', ...rest.headers },
    signal,
    ...rest,
  });

  // Detect server redeployment via build version header
  if (!versionMismatchSignaled && typeof __BUILD_VERSION__ !== 'undefined') {
    const serverVersion = resp.headers.get('X-Build-Version');
    if (serverVersion && serverVersion !== __BUILD_VERSION__) {
      versionMismatchSignaled = true;
      window.dispatchEvent(new CustomEvent('wikantik:version-mismatch', { detail: { serverVersion } }));
    }
  }

  if (!resp.ok) {
    const body = await resp.json().catch(() => ({ message: resp.statusText }));
    throw Object.assign(new Error(body.message || resp.statusText), { status: resp.status, body });
  }
  return resp.json();
}
```

The rest of the file (the `api` export object) stays unchanged.

- [ ] **Step 2: Verify the frontend builds**

Run: `cd wikantik-frontend && npx vite build && cd ..`
Expected: Build succeeds. No warnings about `__BUILD_VERSION__`.

- [ ] **Step 3: Commit**

```bash
git add wikantik-frontend/src/api/client.js
git commit -m "feat: detect server version mismatch in API client and dispatch event"
```

---

### Task 7: Version Mismatch Toast in App.jsx and Styles (Layer 2c — UI)

**Files:**
- Modify: `wikantik-frontend/src/App.jsx`
- Modify: `wikantik-frontend/src/styles/globals.css`

- [ ] **Step 1: Add version mismatch listener and toast to App.jsx**

Replace the full content of `wikantik-frontend/src/App.jsx`:

```jsx
import { useState, useEffect, useCallback } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import Sidebar from './components/Sidebar';
import { useAuth } from './hooks/useAuth';

export default function App() {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [updateAvailable, setUpdateAvailable] = useState(false);
  const { user } = useAuth();
  const location = useLocation();
  const isEditorRoute = location.pathname.startsWith('/edit/');

  // Close mobile sidebar when user successfully authenticates
  useEffect(() => {
    if (user?.authenticated) setMobileOpen(false);
  }, [user?.authenticated]);

  // Listen for server version mismatch
  useEffect(() => {
    function onVersionMismatch(e) {
      const dismissed = sessionStorage.getItem('__wikantik_dismissed_version');
      if (dismissed !== e.detail.serverVersion) {
        setUpdateAvailable(e.detail.serverVersion);
      }
    }
    window.addEventListener('wikantik:version-mismatch', onVersionMismatch);
    return () => window.removeEventListener('wikantik:version-mismatch', onVersionMismatch);
  }, []);

  const dismissUpdate = useCallback(() => {
    sessionStorage.setItem('__wikantik_dismissed_version', updateAvailable);
    setUpdateAvailable(false);
  }, [updateAvailable]);

  return (
    <div className="app-layout">
      {updateAvailable && (
        <div className="update-toast" role="status">
          <span>A new version is available.</span>
          <button onClick={() => window.location.reload()}>Reload</button>
          <button onClick={dismissUpdate} aria-label="Dismiss">&times;</button>
        </div>
      )}
      {mobileOpen && (
        <div className="sidebar-backdrop" onClick={() => setMobileOpen(false)} />
      )}
      <Sidebar
        collapsed={sidebarCollapsed}
        onToggle={() => setSidebarCollapsed(c => !c)}
        mobileOpen={mobileOpen}
        onMobileClose={() => setMobileOpen(false)}
        onMobileOpen={() => setMobileOpen(true)}
      />
      <main className={`app-main ${sidebarCollapsed ? 'expanded' : ''}`}>
        <div className={`app-content${isEditorRoute ? ' app-content-wide' : ''}`}>
          <Outlet />
        </div>
      </main>
    </div>
  );
}
```

- [ ] **Step 2: Add toast styles to globals.css**

Append to the end of `wikantik-frontend/src/styles/globals.css`:

```css
/* ---- Update toast ---- */
.update-toast {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 10000;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  padding: 0.6rem 1rem;
  background: var(--sage);
  color: #fff;
  font-family: var(--font-ui);
  font-size: 0.875rem;
}

.update-toast button {
  background: none;
  border: 1px solid rgba(255, 255, 255, 0.6);
  color: #fff;
  padding: 0.2rem 0.6rem;
  border-radius: 4px;
  cursor: pointer;
  font-family: var(--font-ui);
  font-size: 0.8rem;
}

.update-toast button:hover {
  background: rgba(255, 255, 255, 0.15);
}

.update-toast button:last-child {
  border: none;
  font-size: 1.1rem;
  padding: 0.1rem 0.4rem;
  line-height: 1;
}
```

- [ ] **Step 3: Verify the frontend builds**

Run: `cd wikantik-frontend && npx vite build && cd ..`
Expected: Build succeeds without errors.

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/App.jsx wikantik-frontend/src/styles/globals.css
git commit -m "feat: add non-blocking toast banner for version mismatch on redeployment"
```

---

### Task 8: Full Build Verification

- [ ] **Step 1: Run all unit tests across the project**

Run: `mvn clean install -T 1C -DskipITs`
Expected: Build succeeds, all tests pass.

- [ ] **Step 2: Verify the Vite build produces build-version.txt**

Run: `ls -la wikantik-frontend/dist/build-version.txt && cat wikantik-frontend/dist/build-version.txt`
Expected: File exists with a numeric timestamp.

- [ ] **Step 3: Verify the WAR contains build-version.txt at the root**

Run: `unzip -l wikantik-war/target/Wikantik.war | grep build-version`
Expected: `build-version.txt` listed at the WAR root.

- [ ] **Step 4: Verify index.html in the WAR contains the recovery script**

Run: `unzip -p wikantik-war/target/Wikantik.war index.html | head -20`
Expected: The inline `<script>` with `__wikantik_reload` is present.
