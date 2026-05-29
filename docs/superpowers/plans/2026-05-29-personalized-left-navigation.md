# Personalized Left Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give logged-in users a clearly-separated, personalized "me" zone at the top of the left sidebar (profile, mentions, owned pages, recently viewed, blog, and resume-editing drafts), plus the editor autosave the drafts feature depends on.

**Architecture:** One new read-only REST endpoint (`GET /api/me/pages`) reusing the existing `PageOwnerService`; everything else is React. A new `PersonalZone` component is extracted from `Sidebar.jsx` and composes small hooks. Recently-viewed and drafts live in `localStorage`, namespaced by login. A new `useDraft` hook adds autosave to `PageEditor`/`BlogEditor`.

**Tech Stack:** Java 21 servlets (`wikantik-rest`), JUnit 5 + Mockito (unit) and JDK-`HttpClient` Cargo IT (`wikantik-it-tests/wikantik-it-test-rest`), React 18 + React Router, Vitest + happy-dom + Testing Library (`wikantik-frontend`), plain CSS with design-system variables.

**Reference spec:** `docs/superpowers/specs/2026-05-29-personalized-left-navigation-design.md`

**Key facts established during design (do not re-derive):**
- Current user login on the server: `Wiki.session().find( engine, request ).getLoginPrincipal().getName()` (see `MyMentionsResource.currentUser`).
- Frontend user object (`useAuth().user`): `{ authenticated, username, loginPrincipal, roles }`. `username` is the display/wiki name; `loginPrincipal` is the login used for ownership + blog paths. Anonymous shape is `{ authenticated: false, username: 'anonymous' }` (no `loginPrincipal`).
- `PageOwnerService.listByOwner(owner, limit, offset)` returns `List<PageOwnership>`; `PageOwnership(String canonicalId, String ownerLogin, String assignedBy, Instant assignedAt)` (package `com.wikantik.api.comments`). `countByOwner(owner)` returns `int`. Accessor: `getSubsystems().persistence().pageOwners()` (see `AdminPageOwnershipResource.pageOwners`).
- A brand-new page save assigns its first author as owner (`PageOwnerFilter`, postSave) — whichever principal first saves a page owns it.
- **Servlets are registered in `wikantik-war/src/main/webapp/WEB-INF/web.xml`** — there is NO `RestInitializer.java`. Each servlet needs a `<servlet>` block (name + class; the existing `MyMentionsResource` block is ~line 469) AND a matching `<servlet-mapping>` block (name + url-pattern; the existing `MyMentionsResource` mapping is ~line 687). The servlet dispatch filter is already mapped to `/api/*`, so no filter change is needed.
- No new SPA route is added (PersonalZone links only to existing routes: `/preferences`, `/me/mentions`, `/wiki/:name`, `/blog/:login/...`, `/edit/:name`). So **no `SpaRoutingFilter`/web.xml SPA change** is required.
- Slug/title resolution: `getSubsystems().pageGraph().structuralIndexService().resolveSlugFromCanonicalId( canonicalId )` returns `Optional<String>` (see `MyMentionsResource.resolveSlug`). Interface: `com.wikantik.api.pagegraph.StructuralIndexService`.
- The blog client API already exists: `api.blog.listEntries(login)` -> `GET /api/blog/{login}/entries`.
- `useAuth` exposes `{ user, loading, sso, login, logout, refresh }`. `useUnreadMentions({enabled})` returns `{count, refresh}`. The frontend `request(path, options)` helper is the low-level fetch wrapper in `client.js`.
- `PageView` route param: `const { name = 'Main' } = useParams();` (page slug is `name`).
- Vitest config: `environment: 'happy-dom'`, `setupFiles: ['./src/setupTests.js']`. Existing tests use `@testing-library/react` and `vitest`.
- **Integration-test layout (verified by reading the real files):** REST ITs live in `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/` (package `com.wikantik.its.rest`); examples: `CommentThreadIT`, `SelfDeleteAccountIT`, `ChangesFeedIT`, `RestApiIT`. They use the JDK 21 `java.net.http.HttpClient` + Gson against `System.getProperty("it-wikantik.base.url", "http://localhost:18080/wikantik-it-test-rest")` (no RestAssured), and there is no shared base class. The seed helper `com.wikantik.its.RestSeedHelper` (in `wikantik-selenide-tests`, on the test classpath) has verified static methods `void writePage(String name, String markdown)`, `void awaitAdminReady()` (no args), `String get(String path)`, `String post(String path, String jsonBody)`. Authenticated ITs log in as `janne` with a secure-cookie-over-http shim (see `CommentThreadIT`). Copy that scaffold rather than inventing one.
- Single-file test commands: backend `mvn test -pl wikantik-rest -Dtest=ClassName`; frontend `npx vitest run <path>` from `wikantik-frontend/`. Integration tests: `mvn clean install -Pintegration-tests -fae` (sequential, never `-T`).
- **No DB migration is needed** — `page_owners` already exists.

---

## File Structure

**Create:**
- `wikantik-rest/src/main/java/com/wikantik/rest/MyPagesResource.java` — `GET /api/me/pages` servlet.
- `wikantik-rest/src/test/java/com/wikantik/rest/MyPagesResourceTest.java` — Mockito unit test.
- `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/MyPagesIT.java` — wire-level IT.
- `wikantik-frontend/src/hooks/useDraft.js` — editor autosave/restore for one page.
- `wikantik-frontend/src/hooks/useDraft.test.js`
- `wikantik-frontend/src/hooks/useDrafts.js` — enumerate all local drafts for current login.
- `wikantik-frontend/src/hooks/useDrafts.test.js`
- `wikantik-frontend/src/hooks/useRecentlyViewed.js` — ring buffer of viewed pages.
- `wikantik-frontend/src/hooks/useRecentlyViewed.test.js`
- `wikantik-frontend/src/hooks/useMyPages.js`
- `wikantik-frontend/src/hooks/useMyPages.test.js`
- `wikantik-frontend/src/hooks/useMyBlog.js`
- `wikantik-frontend/src/hooks/useMyBlog.test.js`
- `wikantik-frontend/src/utils/draftKeys.js` — shared localStorage key helpers (DRY across useDraft/useDrafts).
- `wikantik-frontend/src/utils/draftKeys.test.js`
- `wikantik-frontend/src/components/CollapsibleSection.jsx`
- `wikantik-frontend/src/components/CollapsibleSection.test.jsx`
- `wikantik-frontend/src/components/PersonalZone.jsx`
- `wikantik-frontend/src/components/PersonalZone.test.jsx`

**Modify:**
- `wikantik-war/src/main/webapp/WEB-INF/web.xml` — register the new servlet (`<servlet>` + `<servlet-mapping>`).
- `wikantik-frontend/src/api/client.js` — add `getMyPages`.
- `wikantik-frontend/src/components/PageEditor.jsx` — wire `useDraft` + restore banner.
- `wikantik-frontend/src/components/BlogEditor.jsx` — wire `useDraft` + restore banner.
- `wikantik-frontend/src/components/PageView.jsx` — record recently-viewed on load.
- `wikantik-frontend/src/components/Sidebar.jsx` — render `<PersonalZone>`, remove the inline authenticated block.
- `wikantik-frontend/src/styles/globals.css` — me-zone styles.

---

## Task 1: GET /api/me/pages REST endpoint

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/MyPagesResource.java`
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/MyPagesResourceTest.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`

- [ ] **Step 1: Write the failing unit test**

Create `wikantik-rest/src/test/java/com/wikantik/rest/MyPagesResourceTest.java` (Apache license header copied verbatim from `MyMentionsResource.java` lines 1-18, then):

```java
package com.wikantik.rest;

import com.wikantik.api.comments.PageOwnership;
import com.wikantik.comments.PageOwnerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class MyPagesResourceTest {

    private StringWriter capture( final HttpServletResponse resp ) throws Exception {
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
        return sw;
    }

    @Test
    void unauthenticatedGetsUnauthorized() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final MyPagesResource res = new MyPagesResource() {
            @Override protected boolean isAuthenticated( final HttpServletRequest r ) { return false; }
        };
        res.doGet( req, resp );
        verify( resp ).sendError( eq( HttpServletResponse.SC_UNAUTHORIZED ), anyString() );
    }

    @Test
    void returnsOnlyCallersOwnedPages() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "limit" ) ).thenReturn( null );
        final PageOwnerService owners = mock( PageOwnerService.class );
        when( owners.listByOwner( "alice", 15, 0 ) ).thenReturn( List.of(
                new PageOwnership( "cid-1", "alice", "alice", Instant.parse( "2026-05-01T00:00:00Z" ) ) ) );
        final StringWriter sw = capture( resp );

        final MyPagesResource res = new MyPagesResource() {
            @Override protected boolean isAuthenticated( final HttpServletRequest r ) { return true; }
            @Override protected String currentUser( final HttpServletRequest r ) { return "alice"; }
            @Override protected PageOwnerService pageOwners() { return owners; }
            @Override protected Optional< String > resolveSlug( final String cid ) { return Optional.of( "Alice/Notes" ); }
        };
        res.doGet( req, resp );

        verify( owners ).listByOwner( "alice", 15, 0 );
        assertTrue( sw.toString().contains( "Alice/Notes" ), "response should include resolved slug" );
        assertTrue( sw.toString().contains( "cid-1" ), "response should include canonicalId" );
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -pl wikantik-rest -Dtest=MyPagesResourceTest`
Expected: FAIL — compilation error, `MyPagesResource` does not exist.

- [ ] **Step 3: Write the implementation**

Create `wikantik-rest/src/main/java/com/wikantik/rest/MyPagesResource.java` (license header verbatim from `MyMentionsResource.java` lines 1-18, then):

```java
package com.wikantik.rest;

import com.wikantik.api.comments.PageOwnership;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.comments.PageOwnerService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST servlet listing the pages owned by the current user.
 * Mapped to {@code /api/me/pages/*}.
 *
 * <ul>
 *   <li>{@code GET /api/me/pages?limit=N} — {@code {pages:[{canonicalId,slug,title,assignedAt}]}}, newest-first.</li>
 * </ul>
 *
 * Requires authentication; anonymous callers get a 401.
 */
public class MyPagesResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_LIMIT = 15;
    private static final int MAX_LIMIT = 100;

    /** Seam — page-owner service, overridable for unit tests. */
    protected PageOwnerService pageOwners() {
        return getSubsystems().persistence().pageOwners();
    }

    /** Seam — slug resolution from canonical id, overridable for unit tests. */
    protected Optional< String > resolveSlug( final String canonicalId ) {
        return getSubsystems().pageGraph().structuralIndexService().resolveSlugFromCanonicalId( canonicalId );
    }

    /** Seam — current authenticated user's login, overridable for unit tests. */
    protected String currentUser( final HttpServletRequest request ) {
        final Engine engine = getEngine();
        return Wiki.session().find( engine, request ).getLoginPrincipal().getName();
    }

    /** Seam — auth gate, overridable for unit tests. */
    protected boolean isAuthenticated( final HttpServletRequest request ) {
        final Engine engine = getEngine();
        final Session s = Wiki.session().find( engine, request );
        return s != null && s.isAuthenticated();
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        if ( !isAuthenticated( request ) ) {
            sendError( response, HttpServletResponse.SC_UNAUTHORIZED, "Login required" );
            return;
        }
        final int limit = clampLimit( request.getParameter( "limit" ) );
        final List< PageOwnership > rows = pageOwners().listByOwner( currentUser( request ), limit, 0 );
        final List< Map< String, Object > > out = new ArrayList<>();
        for ( final PageOwnership r : rows ) {
            final Optional< String > slug = resolveSlug( r.canonicalId() );
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "canonicalId", r.canonicalId() );
            m.put( "slug", slug.orElse( r.canonicalId() ) );
            m.put( "title", slug.orElse( r.canonicalId() ) );
            m.put( "assignedAt", r.assignedAt() == null ? null : r.assignedAt().toString() );
            out.add( m );
        }
        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "pages", out );
        sendJson( response, body );
    }

    private static int clampLimit( final String raw ) {
        if ( raw == null || raw.isBlank() ) return DEFAULT_LIMIT;
        try {
            return Math.max( 1, Math.min( MAX_LIMIT, Integer.parseInt( raw ) ) );
        } catch ( final NumberFormatException e ) {
            return DEFAULT_LIMIT;
        }
    }
}
```

> If a helper used here (`sendJson`, `sendError`, `getSubsystems`, `getEngine`) has a slightly different name in `RestServletBase`, mirror exactly what `MyMentionsResource.java` does — that file uses all of them and is the source of truth.

- [ ] **Step 4: Register the servlet in web.xml**

In `wikantik-war/src/main/webapp/WEB-INF/web.xml`, immediately after the existing `MyMentionsResource` `<servlet>` block (around line 469-471), add:

```xml
   <servlet>
       <servlet-name>MyPagesResource</servlet-name>
       <servlet-class>com.wikantik.rest.MyPagesResource</servlet-class>
   </servlet>
```

Then, immediately after the existing `MyMentionsResource` `<servlet-mapping>` block (around line 687-689), add:

```xml
   <servlet-mapping>
       <servlet-name>MyPagesResource</servlet-name>
       <url-pattern>/api/me/pages/*</url-pattern>
   </servlet-mapping>
```

(Match the surrounding indentation exactly — copy the whitespace style of the adjacent `MyMentionsResource` blocks.)

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -pl wikantik-rest -Dtest=MyPagesResourceTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/MyPagesResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/MyPagesResourceTest.java \
        wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(nav): GET /api/me/pages lists pages owned by current user"
```

---

## Task 2: Wire-level IT for /api/me/pages

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/MyPagesIT.java`

**Verified facts about this IT module (do not assume otherwise):**
- REST ITs in `wikantik-it-test-rest` use the JDK 21 `java.net.http.HttpClient` + Gson, against base URL `System.getProperty("it-wikantik.base.url", "http://localhost:18080/wikantik-it-test-rest")`, set up in an `@BeforeAll static void setUp()`. No RestAssured, no shared base class — each IT is standalone.
- Authenticated ITs (e.g. `CommentThreadIT`) log in as user **`janne`** (Admin group) and install a secure-cookie-over-http shim on the `HttpClient` so `JSESSIONID` is sent. Public ITs (e.g. `ChangesFeedIT`) use a plain client and no cookies.
- `com.wikantik.its.RestSeedHelper` verified static API: `void writePage(String name, String markdown)`, `void awaitAdminReady()` (no args), `String get(String path)`, `String post(String path, String jsonBody)`. There is no cookie-returning login helper — copy the login + cookie shim from `CommentThreadIT`.
- Page ownership: `PageOwnerFilter` assigns the **first author** of a new page as its owner. The authenticated user in the test must be the principal that seeded the page for it to show up in their `/api/me/pages`.

- [ ] **Step 1: Write the IT**

Create `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/MyPagesIT.java`. **Copy the full scaffold from `CommentThreadIT.java`** verbatim — license header, `package com.wikantik.its.rest;`, the JDK-HttpClient imports, the `@BeforeAll setUp()` building `baseUrl` + `client`, the `secureCookieOverHttp()` shim, and its `janne` login + page-seeding helpers. Then implement two tests:

1. `anonymousIsUnauthorized` — model on `ChangesFeedIT`'s plain-`get` style: `GET baseUrl + "/api/me/pages"` with **no** auth cookie must return **401**.

```java
    @Test
    void anonymousIsUnauthorized() throws Exception {
        final HttpResponse< String > resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + "/api/me/pages" ) )
                        .header( "Accept", "application/json" )
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString() );
        assertEquals( 401, resp.statusCode(), "anonymous must be 401: " + resp.body() );
    }
```

2. `listsPagesOwnedByCaller` — authenticate as `janne` (the same login + cookie-shim `client` that `CommentThreadIT` uses), seed a page **as that same principal**, wait for the index, then GET `/api/me/pages` and assert the page appears. Use the authenticated `client` to PUT-seed the page exactly as `CommentThreadIT` seeds its page (copy that helper). Sketch:

```java
    @Test
    void listsPagesOwnedByCaller() throws Exception {
        login( "janne" );                       // CommentThreadIT's login helper (copied)
        final String page = "MyPagesITPage";
        seedPageAsCurrentUser( page, "Owned by the caller for the my-pages IT." ); // copy CommentThreadIT's PUT-seed helper
        RestSeedHelper.awaitAdminReady();       // wait for structural index UP

        final HttpResponse< String > resp = get( "/api/me/pages?limit=50" ); // authenticated `client`
        assertEquals( 200, resp.statusCode(), "should be 200: " + resp.body() );
        final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
        final JsonArray pages = body.getAsJsonArray( "pages" );
        assertTrue( pages.size() >= 1, "caller should own at least the seeded page" );
        boolean found = false;
        for ( int i = 0; i < pages.size(); i++ ) {
            if ( page.equals( pages.get( i ).getAsJsonObject().get( "slug" ).getAsString() ) ) found = true;
        }
        assertTrue( found, "seeded page must appear in /api/me/pages: " + resp.body() );
    }
```

Imports beyond CommentThreadIT's scaffold: `com.google.gson.JsonArray`, `com.google.gson.JsonObject`, `com.google.gson.JsonParser`, `com.wikantik.its.RestSeedHelper`, and the JUnit `assertEquals`/`assertTrue` statics (CommentThreadIT already imports the latter).

> The exact `login(...)`, `get(...)`, and page-seeding helper method names come from `CommentThreadIT` — read that file and reuse its members rather than re-deriving them. If the seeded page's slug is normalized, relax the slug assertion to just `pages.size() >= 1`.

- [ ] **Step 2: Run the IT to verify it passes**

Run (from repo root): `mvn clean install -Pintegration-tests -fae`
Expected: `MyPagesIT` green. ITs must run sequentially (no `-T`), per CLAUDE.md.

- [ ] **Step 3: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/MyPagesIT.java
git commit -m "test(nav): wire-level IT for /api/me/pages ownership listing"
```

---

## Task 3: API client method getMyPages

**Files:**
- Modify: `wikantik-frontend/src/api/client.js`

- [ ] **Step 1: Add the client method**

In `wikantik-frontend/src/api/client.js`, next to `getMyMentionsUnreadCount` (around line 162), add:

```js
  getMyPages: (limit = 15) =>
    request(`/api/me/pages?limit=${limit}`),
```

- [ ] **Step 2: Verify it builds**

Run (from `wikantik-frontend/`): `npx vite build`
Expected: build succeeds (no syntax errors).

- [ ] **Step 3: Commit**

```bash
git add wikantik-frontend/src/api/client.js
git commit -m "feat(nav): api.getMyPages client method"
```

---

## Task 4: draftKeys localStorage helpers

**Files:**
- Create: `wikantik-frontend/src/utils/draftKeys.js`
- Create: `wikantik-frontend/src/utils/draftKeys.test.js`

Shared by `useDraft` and `useDrafts` (DRY) so the key scheme lives in one place.

- [ ] **Step 1: Write the failing test**

Create `wikantik-frontend/src/utils/draftKeys.test.js`:

```js
import { describe, it, expect } from 'vitest';
import { draftKey, draftPrefix, parseDraftKey } from './draftKeys';

describe('draftKeys', () => {
  it('builds a namespaced key', () => {
    expect(draftKey('alice', 'Foo/Bar')).toBe('wikantik.draft.alice.Foo/Bar');
  });
  it('builds a per-login prefix', () => {
    expect(draftPrefix('alice')).toBe('wikantik.draft.alice.');
  });
  it('round-trips the page id out of a key', () => {
    const k = draftKey('alice', 'Foo/Bar');
    expect(parseDraftKey('alice', k)).toBe('Foo/Bar');
  });
  it('returns null for a key belonging to another login', () => {
    expect(parseDraftKey('bob', 'wikantik.draft.alice.Foo')).toBeNull();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run (from `wikantik-frontend/`): `npx vitest run src/utils/draftKeys.test.js`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the implementation**

Create `wikantik-frontend/src/utils/draftKeys.js`:

```js
const BASE = 'wikantik.draft';

export function draftPrefix(login) {
  return `${BASE}.${login}.`;
}

export function draftKey(login, pageId) {
  return `${draftPrefix(login)}${pageId}`;
}

export function parseDraftKey(login, key) {
  const prefix = draftPrefix(login);
  return key.startsWith(prefix) ? key.slice(prefix.length) : null;
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `npx vitest run src/utils/draftKeys.test.js`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/utils/draftKeys.js wikantik-frontend/src/utils/draftKeys.test.js
git commit -m "feat(nav): namespaced localStorage key helpers for editor drafts"
```

---

## Task 5: useDraft autosave/restore hook

**Files:**
- Create: `wikantik-frontend/src/hooks/useDraft.js`
- Create: `wikantik-frontend/src/hooks/useDraft.test.js`

API: `useDraft({ login, pageId, enabled })` returns `{ draft, saveDraft, clearDraft }` where `draft` is the stored object (or `null`) read once on mount, `saveDraft(fields)` writes immediately (caller debounces), and `clearDraft()` removes the key.

- [ ] **Step 1: Write the failing test**

Create `wikantik-frontend/src/hooks/useDraft.test.js`:

```js
import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useDraft } from './useDraft';
import { draftKey } from '../utils/draftKeys';

beforeEach(() => localStorage.clear());

describe('useDraft', () => {
  it('reads an existing draft on mount', () => {
    localStorage.setItem(
      draftKey('alice', 'Foo'),
      JSON.stringify({ content: 'hi', title: 'Foo', savedAt: 123 }),
    );
    const { result } = renderHook(() =>
      useDraft({ login: 'alice', pageId: 'Foo', enabled: true }));
    expect(result.current.draft.content).toBe('hi');
  });

  it('saveDraft persists a namespaced entry', () => {
    const { result } = renderHook(() =>
      useDraft({ login: 'alice', pageId: 'Foo', enabled: true }));
    act(() => result.current.saveDraft({ content: 'edit', title: 'Foo' }));
    const raw = JSON.parse(localStorage.getItem(draftKey('alice', 'Foo')));
    expect(raw.content).toBe('edit');
    expect(typeof raw.savedAt).toBe('number');
  });

  it('clearDraft removes the entry', () => {
    localStorage.setItem(draftKey('alice', 'Foo'), JSON.stringify({ content: 'x', savedAt: 1 }));
    const { result } = renderHook(() =>
      useDraft({ login: 'alice', pageId: 'Foo', enabled: true }));
    act(() => result.current.clearDraft());
    expect(localStorage.getItem(draftKey('alice', 'Foo'))).toBeNull();
  });

  it('is inert when disabled (no login)', () => {
    const { result } = renderHook(() =>
      useDraft({ login: null, pageId: 'Foo', enabled: false }));
    act(() => result.current.saveDraft({ content: 'edit' }));
    expect(localStorage.length).toBe(0);
    expect(result.current.draft).toBeNull();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx vitest run src/hooks/useDraft.test.js`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the implementation**

Create `wikantik-frontend/src/hooks/useDraft.js`:

```js
import { useCallback, useRef } from 'react';
import { draftKey } from '../utils/draftKeys';

/**
 * Editor autosave for a single page, persisted to localStorage namespaced by login.
 * @param {{login:string|null, pageId:string|null, enabled:boolean}} opts
 */
export function useDraft({ login, pageId, enabled }) {
  const active = enabled && !!login && !!pageId;
  const key = active ? draftKey(login, pageId) : null;

  // Read once on first render so an open editor can offer restore.
  const initial = useRef(undefined);
  if (initial.current === undefined) {
    if (key) {
      try {
        const raw = localStorage.getItem(key);
        initial.current = raw ? JSON.parse(raw) : null;
      } catch {
        initial.current = null;
      }
    } else {
      initial.current = null;
    }
  }

  const saveDraft = useCallback((fields) => {
    if (!key) return;
    try {
      localStorage.setItem(key, JSON.stringify({ ...fields, savedAt: Date.now() }));
    } catch (e) {
      // Quota or serialization failure — drafts are best-effort; don't break editing.
      console.warn('useDraft: failed to persist draft', e);
    }
  }, [key]);

  const clearDraft = useCallback(() => {
    if (key) localStorage.removeItem(key);
  }, [key]);

  return { draft: initial.current, saveDraft, clearDraft };
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `npx vitest run src/hooks/useDraft.test.js`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/hooks/useDraft.js wikantik-frontend/src/hooks/useDraft.test.js
git commit -m "feat(nav): useDraft editor autosave/restore hook"
```

---

## Task 6: Wire autosave + restore banner into PageEditor

**Files:**
- Modify: `wikantik-frontend/src/components/PageEditor.jsx`

Goal: while editing, debounce-save the editor content to a draft; on mount, if a draft differing from the loaded content exists, show a non-destructive banner offering Restore / Discard; clear the draft on a successful save.

- [ ] **Step 1: Add imports**

At the top of `wikantik-frontend/src/components/PageEditor.jsx`, add:

```js
import { useDraft } from '../hooks/useDraft';
import { useAuth } from '../hooks/useAuth';
```

- [ ] **Step 2: Initialize the hook inside the component**

After the existing `useParams`/state declarations in `PageEditor` (the route param for the page name is `name`), add:

```js
  const { user } = useAuth();
  const login = user?.authenticated ? user.loginPrincipal : null;
  const { draft, saveDraft, clearDraft } = useDraft({
    login,
    pageId: name,
    enabled: !!login,
  });
  const [restorePrompt, setRestorePrompt] = useState(false);
```

(`useState` is already imported in this file.)

- [ ] **Step 3: Offer restore on mount**

After the effect that loads the page content into the `content` state, add:

```js
  useEffect(() => {
    if (draft && draft.content && draft.content !== content) {
      setRestorePrompt(true);
    }
    // run once after the initial content load
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft]);
```

- [ ] **Step 4: Autosave on edit (debounced)**

```js
  useEffect(() => {
    if (!login) return;
    const id = setTimeout(() => {
      saveDraft({ content, title: name });
    }, 800);
    return () => clearTimeout(id);
  }, [content, name, login, saveDraft]);
```

- [ ] **Step 5: Clear draft on successful save**

In the existing save handler, after the API save resolves successfully, add:

```js
      clearDraft();
```

- [ ] **Step 6: Render the restore banner**

Just inside the editor's returned JSX, above the textarea, add:

```jsx
      {restorePrompt && (
        <div className="draft-restore-banner" role="status">
          <span>
            You have unsaved changes from{' '}
            {new Date(draft.savedAt).toLocaleString()}.
          </span>
          <button
            type="button"
            className="btn-link"
            onClick={() => { setContent(draft.content); setRestorePrompt(false); }}
          >
            Restore
          </button>
          <button
            type="button"
            className="btn-link"
            onClick={() => { clearDraft(); setRestorePrompt(false); }}
          >
            Discard
          </button>
        </div>
      )}
```

(If the content setter in this component is not named `setContent`, use the actual setter for the editor body state.)

- [ ] **Step 7: Verify the build**

Run (from `wikantik-frontend/`): `npx vite build`
Expected: build succeeds.

- [ ] **Step 8: Commit**

```bash
git add wikantik-frontend/src/components/PageEditor.jsx
git commit -m "feat(nav): autosave + restore-on-return in PageEditor"
```

---

## Task 7: Wire autosave + restore banner into BlogEditor

**Files:**
- Modify: `wikantik-frontend/src/components/BlogEditor.jsx`

`BlogEditor` keeps content in `content` state and computes `blogPageName = blog/${username}/${pageName}` (around line 27). Use that as the draft `pageId`.

- [ ] **Step 1: Add imports**

```js
import { useDraft } from '../hooks/useDraft';
import { useAuth } from '../hooks/useAuth';
```

- [ ] **Step 2: Initialize the hook**

After `blogPageName` is computed, add:

```js
  const { user } = useAuth();
  const login = user?.authenticated ? user.loginPrincipal : null;
  const { draft, saveDraft, clearDraft } = useDraft({
    login,
    pageId: blogPageName,
    enabled: !!login,
  });
  const [restorePrompt, setRestorePrompt] = useState(false);
```

- [ ] **Step 3: Offer restore on mount**

```js
  useEffect(() => {
    if (draft && draft.content && draft.content !== content) {
      setRestorePrompt(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft]);
```

- [ ] **Step 4: Autosave on edit (debounced)**

```js
  useEffect(() => {
    if (!login) return;
    const id = setTimeout(() => {
      saveDraft({ content, title: blogPageName });
    }, 800);
    return () => clearTimeout(id);
  }, [content, blogPageName, login, saveDraft]);
```

- [ ] **Step 5: Clear draft on successful save**

In the existing blog save handler, after the save resolves successfully, add:

```js
      clearDraft();
```

- [ ] **Step 6: Render the restore banner**

Inside the returned JSX, above the textarea, add the identical banner block from Task 6 Step 6 (copy it verbatim — note it references `setContent`; use `BlogEditor`'s content setter name if different).

- [ ] **Step 7: Verify the build**

Run (from `wikantik-frontend/`): `npx vite build`
Expected: build succeeds.

- [ ] **Step 8: Commit**

```bash
git add wikantik-frontend/src/components/BlogEditor.jsx
git commit -m "feat(nav): autosave + restore-on-return in BlogEditor"
```

---

## Task 8: useDrafts enumeration hook

**Files:**
- Create: `wikantik-frontend/src/hooks/useDrafts.js`
- Create: `wikantik-frontend/src/hooks/useDrafts.test.js`

API: `useDrafts({ login, enabled })` returns `{ drafts, removeDraft(pageId) }` where `drafts` is an array of `{ pageId, title, savedAt }` sorted newest-first.

- [ ] **Step 1: Write the failing test**

Create `wikantik-frontend/src/hooks/useDrafts.test.js`:

```js
import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useDrafts } from './useDrafts';
import { draftKey } from '../utils/draftKeys';

beforeEach(() => localStorage.clear());

describe('useDrafts', () => {
  it('lists only the current login drafts, newest first', () => {
    localStorage.setItem(draftKey('alice', 'A'), JSON.stringify({ content: 'a', title: 'A', savedAt: 100 }));
    localStorage.setItem(draftKey('alice', 'B'), JSON.stringify({ content: 'b', title: 'B', savedAt: 200 }));
    localStorage.setItem(draftKey('bob', 'C'), JSON.stringify({ content: 'c', title: 'C', savedAt: 300 }));
    const { result } = renderHook(() => useDrafts({ login: 'alice', enabled: true }));
    expect(result.current.drafts.map(d => d.pageId)).toEqual(['B', 'A']);
  });

  it('removeDraft deletes the entry and updates the list', () => {
    localStorage.setItem(draftKey('alice', 'A'), JSON.stringify({ content: 'a', title: 'A', savedAt: 100 }));
    const { result } = renderHook(() => useDrafts({ login: 'alice', enabled: true }));
    act(() => result.current.removeDraft('A'));
    expect(result.current.drafts).toHaveLength(0);
    expect(localStorage.getItem(draftKey('alice', 'A'))).toBeNull();
  });

  it('returns empty when disabled', () => {
    localStorage.setItem(draftKey('alice', 'A'), JSON.stringify({ content: 'a', savedAt: 1 }));
    const { result } = renderHook(() => useDrafts({ login: null, enabled: false }));
    expect(result.current.drafts).toHaveLength(0);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx vitest run src/hooks/useDrafts.test.js`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the implementation**

Create `wikantik-frontend/src/hooks/useDrafts.js`:

```js
import { useCallback, useState } from 'react';
import { draftPrefix, parseDraftKey } from '../utils/draftKeys';

function readDrafts(login) {
  if (!login) return [];
  const prefix = draftPrefix(login);
  const out = [];
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i);
    if (!key || !key.startsWith(prefix)) continue;
    const pageId = parseDraftKey(login, key);
    try {
      const val = JSON.parse(localStorage.getItem(key));
      out.push({ pageId, title: val.title || pageId, savedAt: val.savedAt || 0 });
    } catch {
      // Skip a corrupt entry rather than failing the whole list.
    }
  }
  out.sort((a, b) => b.savedAt - a.savedAt);
  return out;
}

export function useDrafts({ login, enabled }) {
  const active = enabled && !!login;
  const [drafts, setDrafts] = useState(() => (active ? readDrafts(login) : []));

  const removeDraft = useCallback((pageId) => {
    if (!login) return;
    localStorage.removeItem(`${draftPrefix(login)}${pageId}`);
    setDrafts(readDrafts(login));
  }, [login]);

  return { drafts, removeDraft };
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `npx vitest run src/hooks/useDrafts.test.js`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/hooks/useDrafts.js wikantik-frontend/src/hooks/useDrafts.test.js
git commit -m "feat(nav): useDrafts enumeration hook for resume-editing"
```

---

## Task 9: useRecentlyViewed hook

**Files:**
- Create: `wikantik-frontend/src/hooks/useRecentlyViewed.js`
- Create: `wikantik-frontend/src/hooks/useRecentlyViewed.test.js`

API: `useRecentlyViewed({ login, enabled })` returns `{ items, record({ slug, title }) }`. Storage key `wikantik.recent.<login>`; ring buffer capped at 20; most-recent-first; recording an existing slug moves it to front (dedup).

- [ ] **Step 1: Write the failing test**

Create `wikantik-frontend/src/hooks/useRecentlyViewed.test.js`:

```js
import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useRecentlyViewed } from './useRecentlyViewed';

beforeEach(() => localStorage.clear());

describe('useRecentlyViewed', () => {
  it('records newest-first and dedups by slug', () => {
    const { result } = renderHook(() => useRecentlyViewed({ login: 'alice', enabled: true }));
    act(() => result.current.record({ slug: 'A', title: 'A' }));
    act(() => result.current.record({ slug: 'B', title: 'B' }));
    act(() => result.current.record({ slug: 'A', title: 'A' }));
    expect(result.current.items.map(i => i.slug)).toEqual(['A', 'B']);
  });

  it('caps the buffer at 20', () => {
    const { result } = renderHook(() => useRecentlyViewed({ login: 'alice', enabled: true }));
    act(() => {
      for (let i = 0; i < 25; i++) result.current.record({ slug: `P${i}`, title: `P${i}` });
    });
    expect(result.current.items).toHaveLength(20);
    expect(result.current.items[0].slug).toBe('P24');
  });

  it('namespaces by login', () => {
    const { result: alice } = renderHook(() => useRecentlyViewed({ login: 'alice', enabled: true }));
    act(() => alice.current.record({ slug: 'A', title: 'A' }));
    const { result: bob } = renderHook(() => useRecentlyViewed({ login: 'bob', enabled: true }));
    expect(bob.current.items).toHaveLength(0);
  });

  it('is inert when disabled', () => {
    const { result } = renderHook(() => useRecentlyViewed({ login: null, enabled: false }));
    act(() => result.current.record({ slug: 'A', title: 'A' }));
    expect(result.current.items).toHaveLength(0);
    expect(localStorage.length).toBe(0);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx vitest run src/hooks/useRecentlyViewed.test.js`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the implementation**

Create `wikantik-frontend/src/hooks/useRecentlyViewed.js`:

```js
import { useCallback, useState } from 'react';

const CAP = 20;
const keyFor = (login) => `wikantik.recent.${login}`;

function read(login) {
  if (!login) return [];
  try {
    return JSON.parse(localStorage.getItem(keyFor(login))) || [];
  } catch {
    return [];
  }
}

export function useRecentlyViewed({ login, enabled }) {
  const active = enabled && !!login;
  const [items, setItems] = useState(() => (active ? read(login) : []));

  const record = useCallback(({ slug, title }) => {
    if (!active || !slug) return;
    setItems((prev) => {
      const next = [{ slug, title: title || slug }, ...prev.filter((i) => i.slug !== slug)].slice(0, CAP);
      try {
        localStorage.setItem(keyFor(login), JSON.stringify(next));
      } catch (e) {
        console.warn('useRecentlyViewed: failed to persist', e);
      }
      return next;
    });
  }, [active, login]);

  return { items, record };
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `npx vitest run src/hooks/useRecentlyViewed.test.js`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/hooks/useRecentlyViewed.js wikantik-frontend/src/hooks/useRecentlyViewed.test.js
git commit -m "feat(nav): useRecentlyViewed ring-buffer hook"
```

---

## Task 10: Record recently-viewed in PageView

**Files:**
- Modify: `wikantik-frontend/src/components/PageView.jsx`

- [ ] **Step 1: Add imports**

```js
import { useRecentlyViewed } from '../hooks/useRecentlyViewed';
import { useAuth } from '../hooks/useAuth';
```

- [ ] **Step 2: Initialize the hook in the component**

After the existing `const { name = 'Main' } = useParams();` line in `PageView`, add:

```js
  const { user } = useAuth();
  const recent = useRecentlyViewed({
    login: user?.authenticated ? user.loginPrincipal : null,
    enabled: !!user?.authenticated,
  });
```

- [ ] **Step 3: Record after the page loads**

After the page content/title is loaded into state (after the existing load effect), add:

```js
  useEffect(() => {
    if (user?.authenticated && name) {
      recent.record({ slug: name, title: name });
    }
    // record on page change only
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [name, user?.authenticated]);
```

(If the component exposes a nicer human title in state, pass it as `title`; `name` is an acceptable fallback. `useEffect` is already imported.)

- [ ] **Step 4: Verify the build**

Run (from `wikantik-frontend/`): `npx vite build`
Expected: build succeeds.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/PageView.jsx
git commit -m "feat(nav): record recently-viewed pages on PageView load"
```

---

## Task 11: useMyPages hook

**Files:**
- Create: `wikantik-frontend/src/hooks/useMyPages.js`
- Create: `wikantik-frontend/src/hooks/useMyPages.test.js`

API: `useMyPages({ enabled })` returns `{ pages, loading }`. Fetches `api.getMyPages()` when enabled; empty + not-loading when disabled. Fails closed (empty list) on error.

- [ ] **Step 1: Write the failing test**

Create `wikantik-frontend/src/hooks/useMyPages.test.js`:

```js
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useMyPages } from './useMyPages';
import { api } from '../api/client';

vi.mock('../api/client', () => ({ api: { getMyPages: vi.fn() } }));

beforeEach(() => vi.clearAllMocks());

describe('useMyPages', () => {
  it('loads pages when enabled', async () => {
    api.getMyPages.mockResolvedValue({ pages: [{ slug: 'Foo', title: 'Foo' }] });
    const { result } = renderHook(() => useMyPages({ enabled: true }));
    await waitFor(() => expect(result.current.pages).toHaveLength(1));
    expect(result.current.pages[0].slug).toBe('Foo');
  });

  it('does not fetch when disabled', () => {
    renderHook(() => useMyPages({ enabled: false }));
    expect(api.getMyPages).not.toHaveBeenCalled();
  });

  it('fails closed to an empty list on error', async () => {
    api.getMyPages.mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useMyPages({ enabled: true }));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.pages).toEqual([]);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx vitest run src/hooks/useMyPages.test.js`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the implementation**

Create `wikantik-frontend/src/hooks/useMyPages.js`:

```js
import { useEffect, useState } from 'react';
import { api } from '../api/client';

export function useMyPages({ enabled }) {
  const [pages, setPages] = useState([]);
  const [loading, setLoading] = useState(enabled);

  useEffect(() => {
    if (!enabled) { setPages([]); setLoading(false); return; }
    let cancelled = false;
    setLoading(true);
    api.getMyPages()
      .then((d) => { if (!cancelled) setPages(d.pages || []); })
      .catch(() => { if (!cancelled) setPages([]); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [enabled]);

  return { pages, loading };
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `npx vitest run src/hooks/useMyPages.test.js`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/hooks/useMyPages.js wikantik-frontend/src/hooks/useMyPages.test.js
git commit -m "feat(nav): useMyPages hook"
```

---

## Task 12: useMyBlog hook

**Files:**
- Create: `wikantik-frontend/src/hooks/useMyBlog.js`
- Create: `wikantik-frontend/src/hooks/useMyBlog.test.js`

API: `useMyBlog({ login, enabled })` returns `{ entries, loading }`. Fetches `api.blog.listEntries(login)` when enabled; fails closed to empty.

- [ ] **Step 1: Write the failing test**

Create `wikantik-frontend/src/hooks/useMyBlog.test.js`:

```js
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useMyBlog } from './useMyBlog';
import { api } from '../api/client';

vi.mock('../api/client', () => ({ api: { blog: { listEntries: vi.fn() } } }));

beforeEach(() => vi.clearAllMocks());

describe('useMyBlog', () => {
  it('loads entries for the login when enabled', async () => {
    api.blog.listEntries.mockResolvedValue({ entries: [{ name: 'E1', title: 'Entry 1' }] });
    const { result } = renderHook(() => useMyBlog({ login: 'alice', enabled: true }));
    await waitFor(() => expect(result.current.entries).toHaveLength(1));
    expect(api.blog.listEntries).toHaveBeenCalledWith('alice');
  });

  it('does not fetch when disabled', () => {
    renderHook(() => useMyBlog({ login: 'alice', enabled: false }));
    expect(api.blog.listEntries).not.toHaveBeenCalled();
  });

  it('fails closed on error', async () => {
    api.blog.listEntries.mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useMyBlog({ login: 'alice', enabled: true }));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.entries).toEqual([]);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx vitest run src/hooks/useMyBlog.test.js`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the implementation**

Create `wikantik-frontend/src/hooks/useMyBlog.js`:

```js
import { useEffect, useState } from 'react';
import { api } from '../api/client';

export function useMyBlog({ login, enabled }) {
  const active = enabled && !!login;
  const [entries, setEntries] = useState([]);
  const [loading, setLoading] = useState(active);

  useEffect(() => {
    if (!active) { setEntries([]); setLoading(false); return; }
    let cancelled = false;
    setLoading(true);
    api.blog.listEntries(login)
      .then((d) => { if (!cancelled) setEntries(d.entries || []); })
      .catch(() => { if (!cancelled) setEntries([]); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [active, login]);

  return { entries, loading };
}
```

> Verify the entries field name: `GET /api/blog/{login}/entries` returns its list under a key — confirm whether it is `entries` (used above) or `pages`/`items` by checking `BlogResource` or an existing caller, and align both the hook and its test to the real key.

- [ ] **Step 4: Run the test to verify it passes**

Run: `npx vitest run src/hooks/useMyBlog.test.js`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/hooks/useMyBlog.js wikantik-frontend/src/hooks/useMyBlog.test.js
git commit -m "feat(nav): useMyBlog hook"
```

---

## Task 13: CollapsibleSection component

**Files:**
- Create: `wikantik-frontend/src/components/CollapsibleSection.jsx`
- Create: `wikantik-frontend/src/components/CollapsibleSection.test.jsx`

API: `<CollapsibleSection id="my-pages" icon="..." title="My pages" count={5} defaultOpen>{children}</CollapsibleSection>`. Header is a real `<button>` with `aria-expanded`; open/closed persisted under `wikantik.section.<id>`.

- [ ] **Step 1: Write the failing test**

Create `wikantik-frontend/src/components/CollapsibleSection.test.jsx`:

```jsx
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import CollapsibleSection from './CollapsibleSection';

beforeEach(() => localStorage.clear());

describe('CollapsibleSection', () => {
  it('renders the title and toggles aria-expanded', () => {
    render(
      <CollapsibleSection id="t" title="My pages" defaultOpen>
        <a href="/x">child</a>
      </CollapsibleSection>,
    );
    const btn = screen.getByRole('button', { name: /my pages/i });
    expect(btn).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('child')).toBeInTheDocument();
    fireEvent.click(btn);
    expect(btn).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('child')).not.toBeInTheDocument();
  });

  it('persists collapsed state to localStorage', () => {
    const { unmount } = render(
      <CollapsibleSection id="persist" title="Sec" defaultOpen>
        <span>body</span>
      </CollapsibleSection>,
    );
    fireEvent.click(screen.getByRole('button', { name: /sec/i }));
    unmount();
    render(
      <CollapsibleSection id="persist" title="Sec" defaultOpen>
        <span>body</span>
      </CollapsibleSection>,
    );
    expect(screen.getByRole('button', { name: /sec/i })).toHaveAttribute('aria-expanded', 'false');
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx vitest run src/components/CollapsibleSection.test.jsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the implementation**

Create `wikantik-frontend/src/components/CollapsibleSection.jsx`:

```jsx
import { useState } from 'react';

const keyFor = (id) => `wikantik.section.${id}`;

export default function CollapsibleSection({ id, icon, title, count, defaultOpen = true, children }) {
  const [open, setOpen] = useState(() => {
    const saved = localStorage.getItem(keyFor(id));
    return saved === null ? defaultOpen : saved === '1';
  });

  const toggle = () => {
    setOpen((prev) => {
      const next = !prev;
      try { localStorage.setItem(keyFor(id), next ? '1' : '0'); } catch { /* best-effort */ }
      return next;
    });
  };

  return (
    <div className="sidebar-section personal-section">
      <button
        type="button"
        className="personal-section-header"
        aria-expanded={open}
        onClick={toggle}
      >
        {icon && <span className="personal-section-icon" aria-hidden="true">{icon}</span>}
        <span className="personal-section-title">{title}</span>
        {typeof count === 'number' && <span className="personal-section-count">{count}</span>}
        <span className="personal-section-chevron" aria-hidden="true">{open ? '▾' : '▸'}</span>
      </button>
      {open && <div className="personal-section-body">{children}</div>}
    </div>
  );
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `npx vitest run src/components/CollapsibleSection.test.jsx`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/CollapsibleSection.jsx wikantik-frontend/src/components/CollapsibleSection.test.jsx
git commit -m "feat(nav): accessible CollapsibleSection component"
```

---

## Task 14: PersonalZone component

**Files:**
- Create: `wikantik-frontend/src/components/PersonalZone.jsx`
- Create: `wikantik-frontend/src/components/PersonalZone.test.jsx`

Composes identity header, New Article, mentions link + badge, and four collapsible sections (My pages, Recently viewed, My blog, Drafts). Renders nothing for anonymous users. Each list shows up to 3 inline items with a "View all (N)" that expands inline up to ~15; per-section empty states. The Drafts section is hidden when empty.

- [ ] **Step 1: Write the failing test**

Create `wikantik-frontend/src/components/PersonalZone.test.jsx`:

```jsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import PersonalZone from './PersonalZone';

vi.mock('../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('../hooks/useUnreadMentions', () => ({ useUnreadMentions: () => ({ count: 2 }) }));
vi.mock('../hooks/useMyPages', () => ({ useMyPages: () => ({ pages: [{ slug: 'Foo', title: 'Foo' }], loading: false }) }));
vi.mock('../hooks/useMyBlog', () => ({ useMyBlog: () => ({ entries: [], loading: false }) }));
vi.mock('../hooks/useRecentlyViewed', () => ({ useRecentlyViewed: () => ({ items: [], record: () => {} }) }));
vi.mock('../hooks/useDrafts', () => ({ useDrafts: () => ({ drafts: [], removeDraft: () => {} }) }));

import { useAuth } from '../hooks/useAuth';

beforeEach(() => { localStorage.clear(); vi.clearAllMocks(); });

const renderZone = () =>
  render(<MemoryRouter><PersonalZone onMobileClose={() => {}} onNewArticle={() => {}} /></MemoryRouter>);

describe('PersonalZone', () => {
  it('renders nothing for anonymous users', () => {
    useAuth.mockReturnValue({ user: { authenticated: false, username: 'anonymous' } });
    const { container } = renderZone();
    expect(container.firstChild).toBeNull();
  });

  it('shows identity, mentions badge, and an owned page for authed users', () => {
    useAuth.mockReturnValue({ user: { authenticated: true, username: 'Alice', loginPrincipal: 'alice', roles: [] }, logout: () => {} });
    renderZone();
    expect(screen.getByText('Alice')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.getByText('Foo')).toBeInTheDocument();
  });

  it('hides the Drafts section when there are no drafts', () => {
    useAuth.mockReturnValue({ user: { authenticated: true, username: 'Alice', loginPrincipal: 'alice', roles: [] }, logout: () => {} });
    renderZone();
    expect(screen.queryByText(/resume editing/i)).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx vitest run src/components/PersonalZone.test.jsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the implementation**

Create `wikantik-frontend/src/components/PersonalZone.jsx`:

```jsx
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { useUnreadMentions } from '../hooks/useUnreadMentions';
import { useMyPages } from '../hooks/useMyPages';
import { useMyBlog } from '../hooks/useMyBlog';
import { useRecentlyViewed } from '../hooks/useRecentlyViewed';
import { useDrafts } from '../hooks/useDrafts';
import CollapsibleSection from './CollapsibleSection';

const INLINE = 3;
const EXPANDED = 15;

function PreviewList({ items, render, emptyLabel }) {
  const [showAll, setShowAll] = useState(false);
  if (!items.length) return <div className="personal-empty">{emptyLabel}</div>;
  const visible = showAll ? items.slice(0, EXPANDED) : items.slice(0, INLINE);
  return (
    <>
      {visible.map(render)}
      {!showAll && items.length > INLINE && (
        <button type="button" className="personal-viewall" onClick={() => setShowAll(true)}>
          View all {items.length}
        </button>
      )}
    </>
  );
}

export default function PersonalZone({ onMobileClose = () => {}, onNewArticle = () => {} }) {
  const { user, logout } = useAuth();
  const authed = !!user?.authenticated;
  const login = authed ? user.loginPrincipal : null;

  const { count: mentions } = useUnreadMentions({ enabled: authed });
  const { pages } = useMyPages({ enabled: authed });
  const { entries } = useMyBlog({ login, enabled: authed });
  const { items: recent } = useRecentlyViewed({ login, enabled: authed });
  const { drafts, removeDraft } = useDrafts({ login, enabled: authed });

  if (!authed) return null;

  const initials = (user.username || '?').slice(0, 2).toUpperCase();

  return (
    <div className="personal-zone">
      <div className="personal-identity">
        <div className="personal-avatar" aria-hidden="true">{initials}</div>
        <div className="personal-identity-text">
          <Link to="/preferences" className="personal-name" onClick={onMobileClose}>{user.username}</Link>
          <div className="personal-sub">
            {user.roles?.includes('Admin') ? 'Admin · ' : ''}
            <Link to="/preferences" onClick={onMobileClose}>Profile</Link>
            {' · '}
            <button type="button" className="btn-link" onClick={logout}>Sign out</button>
          </div>
        </div>
      </div>

      <button
        className="btn btn-primary personal-new-article"
        onClick={onNewArticle}
        style={{ width: '100%', justifyContent: 'center' }}
      >
        + New Article
      </button>

      <Link to="/me/mentions" className="sidebar-link personal-mentions" onClick={onMobileClose}>
        <span aria-hidden="true">🔔</span> My mentions
        {mentions > 0 && <span className="sidebar-mentions-badge">{mentions}</span>}
      </Link>

      <CollapsibleSection id="my-pages" icon="📄" title="My pages" count={pages.length}>
        <PreviewList
          items={pages}
          emptyLabel="No pages yet."
          render={(p) => (
            <Link key={p.slug} to={`/wiki/${p.slug}`} className="sidebar-link" onClick={onMobileClose}>
              {p.title}
            </Link>
          )}
        />
      </CollapsibleSection>

      <CollapsibleSection id="recent" icon="🕘" title="Recently viewed">
        <PreviewList
          items={recent}
          emptyLabel="Pages you view will appear here."
          render={(r) => (
            <Link key={r.slug} to={`/wiki/${r.slug}`} className="sidebar-link" onClick={onMobileClose}>
              {r.title}
            </Link>
          )}
        />
      </CollapsibleSection>

      <CollapsibleSection id="my-blog" icon="✍️" title="My blog" count={entries.length}>
        <Link to={`/blog/${login}/Blog`} className="sidebar-link" onClick={onMobileClose}>Blog home</Link>
        <PreviewList
          items={entries}
          emptyLabel="No blog entries yet."
          render={(e) => (
            <Link
              key={e.name}
              to={`/blog/${login}/${e.name}`}
              className="sidebar-link"
              onClick={onMobileClose}
            >
              {e.title || e.name}
            </Link>
          )}
        />
      </CollapsibleSection>

      {drafts.length > 0 && (
        <CollapsibleSection id="drafts" icon="📝" title="Resume editing" count={drafts.length}>
          {drafts.map((d) => (
            <div key={d.pageId} className="personal-draft-row">
              <Link to={`/edit/${d.pageId}`} className="sidebar-link" onClick={onMobileClose}>
                {d.title}
              </Link>
              <button
                type="button"
                className="btn-link personal-draft-discard"
                aria-label={`Discard draft for ${d.title}`}
                onClick={() => removeDraft(d.pageId)}
              >
                ✕
              </button>
            </div>
          ))}
        </CollapsibleSection>
      )}
    </div>
  );
}
```

> The `\uXXXX` escapes above are emoji/symbol placeholders (bell, page, clock, pen, memo, ✕, ▾/▸, ·) — when typing the file, use the actual glyphs from the design mockup. They are escaped here only to survive plan transport.

- [ ] **Step 4: Run the test to verify it passes**

Run: `npx vitest run src/components/PersonalZone.test.jsx`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/PersonalZone.jsx wikantik-frontend/src/components/PersonalZone.test.jsx
git commit -m "feat(nav): PersonalZone component composing the me-zone"
```

---

## Task 15: Integrate PersonalZone into Sidebar

**Files:**
- Modify: `wikantik-frontend/src/components/Sidebar.jsx`

- [ ] **Step 1: Add the import**

```js
import PersonalZone from './PersonalZone';
```

- [ ] **Step 2: Remove the now-duplicated inline block**

Delete the authenticated block currently at lines 88-106 (the fragment containing the `+ New Article` button and the `My mentions` link with `mentionsCount`).

- [ ] **Step 3: Render PersonalZone in its place**

Where that block was (just after the `search-trigger` button, before the Primary Navigation section), add:

```jsx
        <PersonalZone
          onMobileClose={onMobileClose}
          onNewArticle={() => setNewArticleOpen(true)}
        />
```

- [ ] **Step 4: Remove the now-unused useUnreadMentions usage**

`PersonalZone` owns the unread count now. In `Sidebar.jsx` delete the line `const { count: mentionsCount } = useUnreadMentions({ enabled: !!user?.authenticated });` and its import `import { useUnreadMentions } from '../hooks/useUnreadMentions';`. (Leave `useAuth`/`user` — still used for the Admin section.)

- [ ] **Step 5: Run the existing Sidebar tests + build**

Run (from `wikantik-frontend/`): `npx vitest run src/components/Sidebar.test.jsx` (if the file exists) then `npx vite build`
Expected: tests pass and build succeeds. If a Sidebar test asserted the old inline "My mentions" link directly in `Sidebar`, move that assertion to `PersonalZone.test.jsx`.

- [ ] **Step 6: Commit**

```bash
git add wikantik-frontend/src/components/Sidebar.jsx
git commit -m "feat(nav): render PersonalZone in Sidebar, drop inline auth block"
```

---

## Task 16: Me-zone styles

**Files:**
- Modify: `wikantik-frontend/src/styles/globals.css`

- [ ] **Step 1: Append the styles**

At the end of `wikantik-frontend/src/styles/globals.css`, add (uses existing design-system variables):

```css
/* ---- Personalized "me" zone ---- */
.personal-zone {
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--space-sm);
  padding: var(--space-sm);
  margin: var(--space-md) 0;
}
.personal-identity {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding-bottom: var(--space-sm);
  border-bottom: 1px solid var(--border);
}
.personal-avatar {
  width: 34px; height: 34px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-weight: 600; font-family: var(--font-ui);
}
.personal-name { font-weight: 600; color: var(--text); text-decoration: none; }
.personal-sub { font-size: 0.72rem; color: var(--text-muted); }
.personal-new-article { margin: var(--space-sm) 0; }
.personal-mentions { display: flex; align-items: center; gap: var(--space-xs); }

.personal-section-header {
  display: flex; align-items: center; gap: var(--space-xs);
  width: 100%;
  background: none; border: none; cursor: pointer;
  padding: var(--space-xs) var(--space-xs);
  font: inherit; color: var(--text-secondary);
  text-transform: uppercase; letter-spacing: 0.06em; font-size: 0.68rem;
}
.personal-section-header:hover { color: var(--text); }
.personal-section-header:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.personal-section-count { margin-left: var(--space-xs); color: var(--text-muted); }
.personal-section-chevron { margin-left: auto; opacity: 0.6; }
.personal-section-body { padding-left: var(--space-xs); }
.personal-empty { color: var(--text-muted); font-style: italic; font-size: 0.78rem; padding: var(--space-xs); }
.personal-viewall {
  background: none; border: none; cursor: pointer;
  color: var(--accent); font: inherit; font-size: 0.78rem;
  padding: var(--space-xs);
}
.personal-draft-row { display: flex; align-items: center; }
.personal-draft-row .sidebar-link { flex: 1; }
.personal-draft-discard { color: var(--text-muted); padding: 0 var(--space-xs); }

.draft-restore-banner {
  display: flex; align-items: center; gap: var(--space-sm);
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--space-xs);
  padding: var(--space-sm);
  margin-bottom: var(--space-sm);
  font-size: 0.85rem;
}
```

- [ ] **Step 2: Verify the build**

Run (from `wikantik-frontend/`): `npx vite build`
Expected: build succeeds.

- [ ] **Step 3: Commit**

```bash
git add wikantik-frontend/src/styles/globals.css
git commit -m "feat(nav): styles for the personalized me-zone"
```

---

## Task 17: Final verification

- [ ] **Step 1: Full frontend test suite** — `npx vitest run` (from `wikantik-frontend/`). All tests pass.
- [ ] **Step 2: Full build with unit tests (no ITs)** — `mvn clean install -T 1C -DskipITs` from repo root. BUILD SUCCESS (includes `MyPagesResourceTest` and the vite frontend build).
- [ ] **Step 3: Full integration-test reactor (sequential)** — `mvn clean install -Pintegration-tests -fae` from repo root. BUILD SUCCESS; `MyPagesIT` green; no regressions. (Per CLAUDE.md and memory `feedback_full_it_after_targeted_fix`, gate the commit on the full IT reactor.)
- [ ] **Step 4: Manual smoke (recommended)** — `mvn clean install -Dmaven.test.skip -T 1C && bin/redeploy.sh`, log in as `testbot` (creds in `test.properties`), and confirm: me-zone shows identity/profile/mentions; My pages lists owned pages; viewing a page adds it to Recently viewed; opening the editor, leaving, and returning offers Restore; the draft appears under Resume editing until saved.
- [ ] **Step 5: Final commit (if incidental fixups were needed)** — `git add -A && git commit -m "chore(nav): final verification fixups for personalized left navigation"`.

---

## Self-Review Notes (author check)

- **Spec coverage:** identity/profile (Task 14); mentions reuse (14); My pages — backend (1,2) + client (3) + hook (11) + UI (14); Recently viewed (9,10,14); My blog (12,14); Drafts + autosave (4-8,14); CollapsibleSection a11y/persistence (13); PersonalZone extraction + Sidebar slimming (14,15); localStorage login-namespacing (4,9); no DB migration; backend unit+IT and frontend vitest; out-of-scope items (server-side blog drafts, bookmarks, new /me routes) not implemented.
- **Type/name consistency:** `useDraft({login,pageId,enabled})->{draft,saveDraft,clearDraft}`, `useDrafts({login,enabled})->{drafts,removeDraft}`, `useRecentlyViewed({login,enabled})->{items,record}`, `useMyPages({enabled})->{pages,loading}`, `useMyBlog({login,enabled})->{entries,loading}` — used consistently in PersonalZone (Task 14). Draft item shape `{pageId,title,savedAt}` matches Tasks 8 and 14. Backend seam names (`pageOwners`/`resolveSlug`/`currentUser`/`isAuthenticated`) match resource (Task 1) and test. Registration is web.xml `<servlet>`+`<servlet-mapping>` (Task 1 Step 4).
- **Placeholders:** none — every code step is concrete.
- **Known soft spots for the implementer (verify against real source, do not assume):**
  1. Blog entries JSON key — confirm `entries` vs another key from `BlogResource`/an existing caller (Task 12 note).
  2. `PageEditor`/`BlogEditor` content-state setter name (`setContent` assumed) for the Restore button (Tasks 6/7).
  3. `RestServletBase` helper names — mirror `MyMentionsResource.java` exactly (Task 1 note).
  4. The IT scaffold (login, get, page-seed helpers) — copy from `CommentThreadIT`, do not invent (Task 2).
  5. Replace the `\uXXXX` emoji placeholders in `PersonalZone.jsx` with real glyphs (Task 14 note).
  6. If `Sidebar.test.jsx` asserted the inline mentions link, migrate that assertion (Task 15 Step 5).
```
