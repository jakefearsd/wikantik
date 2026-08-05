# Blog Feature Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Completely eliminate the blog feature from Wikantik — code, routes, plugins, storage special-casing, and all existing blog content — leaving no compatibility shims behind.

**Architecture:** Removal proceeds **outside-in** so the compiler and test suite act as the safety net at every step: content → frontend → REST/routes → plugins → engine wiring → page-storage providers. Each layer is unreferenced by the time it is deleted. The final layer (`AbstractFileProvider` / `VersioningFileProvider`) is the only one that touches code shared with non-blog pages, so it is isolated into its own task with an explicit regression gate.

**Tech Stack:** Java 25 / Maven multi-module, JUnit 5, React 19 + Vite 8 + Vitest 4, Tomcat 11, PostgreSQL.

## Global Constraints

- Work directly on `main`. No feature branches, no PRs.
- TDD: for a removal, the failing test asserts the surface is **gone**. Write it, watch it fail against the still-present feature, then delete the code and watch it pass.
- Never swallow exceptions with empty catch blocks — always log at least `LOG.warn()` with context.
- Canonical gate before any prod-code commit: `bin/run-tests.sh --parallel 4` (requires Docker).
- Long Maven runs go through `bin/agent-build.sh` — never a bare foreground `mvn`, never `nohup mvn -q … &`.
- Every SPA route change edits **both** `web.xml` and `SPA_EXACT`/`SPA_PREFIXES` in `SpaRoutingFilter.java`. Either alone 404s.
- Stage specific files by name. Never `git add -A`.
- Commit messages 1–3 lines.
- Frontend checks: `npm run lint` (0 errors required; warnings are pre-existing noise) and `npm test` from `wikantik-frontend/`.
- No `News.md` updates — that convention is retired.
- **No cruft.** The end state must contain no vestige of the feature — no dead constants, no
  "formerly blog" comments, no compatibility shims, no orphaned i18n keys, no tests that exist only
  to assert the feature is gone.
- **Transient TDD scaffolding.** The reflection-based tests in Task 5 (`coreSubsystemHasNoBlogComponent`)
  and Task 6 (`providerHasNoBlogSpecialCasing`) are removal anchors, not permanent regression guards:
  they assert internal structure and would be cruft if left behind. They must exist while their task
  runs — the task review verifies them — and are **deleted in Task 7, Step 2a**. The Task 2 and Task 3
  tests are different: they assert user-visible render and routing behaviour, and they stay.

## Known False Positives — Do NOT Touch

A raw `grep -ri blog` over this repo over-reports badly. These matches are **not** the blog feature and must be left alone:

| File | Why it matches |
|---|---|
| `GetBriefingTool.java`, `GetBriefingToolTest.java` | local variable `final BriefingLogService blog` — "briefing log" |
| `BriefingResource.java`, `BriefingResourceTest.java` | same `blog` variable |
| `SelfApiKeysResourceTest.java`, `CrawlScopeTest.java` | same `blog` variable |
| `connectorGuides.js` | unrelated prose |
| `DefaultRecentArticlesManagerTest.java`, `DefaultRecentArticlesManagerCITest.java`, `RecentArticlesQueryTest.java` | string fixtures `"BlogPost1"`, `"Blog.*"` used to exercise pattern matching. Ordinary page names that happen to start with "Blog". |
| `SystemPageRegistryTest.java:77` | asserts `"BlogPost2026"` is *not* a system page — a string fixture |
| `HubOverviewServiceTest.java` | string fixtures |
| `plugin/RecentArticles.java` | javadoc example only — `RecentArticles` is **not** a blog plugin, it stays |
| `AttachmentResource.java:59` | javadoc example of a hierarchical page name |
| `RenderingManager.java:235` | javadoc example |
| `wikantik-wikipages/*/CSSThemeDark.md`, `InstallationTips.md` | CSS class names / unrelated prose |

Conversely, two plugins have generic names but **are** blog-only and must be deleted: `ArticleListing` and `LatestArticle` (both call `BlogManager`, both used only on the two `Blog.md` pages).

## File Structure

**Deleted outright (34 source files):**

```
wikantik-main/src/main/java/com/wikantik/blog/          BlogManager, DefaultBlogManager,
                                                         BlogInfo, BlogAlreadyExistsException
wikantik-main/src/main/java/com/wikantik/plugin/        BlogListing, ArticleListing, LatestArticle
wikantik-main/src/main/resources/com/wikantik/blog/     BlogTemplate.md
wikantik-rest/src/main/java/com/wikantik/rest/          BlogResource
wikantik-frontend/src/components/                       BlogDiscovery, BlogHome, BlogEntry,
                                                         CreateBlog, NewBlogEntry, BlogEditor (+6 tests)
wikantik-frontend/src/hooks/                            useMyBlog.js (+test)
tests                                                   DefaultBlogManagerTest, BlogListingTest,
                                                         ArticleListingTest, LatestArticleTest,
                                                         BlogResourceTest, BlogIT,
                                                         AbstractFileProviderBlogTest,
                                                         VersioningFileProviderBlogTest
docs                                                    docs/Blog.md, BlogFeature.md,
                                                         BlogEditorSplitView.md
```

**Modified (16 files):** `WikiEngine.java`, `CoreSubsystem.java`, `CoreSubsystemBridge.java`, `CoreSubsystemFactory.java`, `AbstractFileProvider.java`, `VersioningFileProvider.java`, `SpaRoutingFilter.java`, `web.xml`, `wikantik_module.xml`, `main.jsx`, `api/client.js`, `PersonalZone.jsx` (+test), `RenderCacheInvalidationTest.java`, `CoreSubsystemFactoryTest.java`, `CoreSubsystemBridgeTest.java`, `AuthSubsystemFactoryTest.java`, `KnowledgeSubsystemFactoryTest.java`, `RobotsTxtTest.java`, `PluginResources_ru.properties`, `default.properties`.

**Content deleted:** 71 files under `docs/wikantik-pages/blog/`, plus inbound links from 3 corpus pages.

---

### Task 1: Delete blog content from the version-controlled corpus

Content deletion is independent of all code changes — no test fixture reads these files (provider tests build their own temp dirs). Doing it first means no later task has to reason about live blog pages.

**Files:**
- Delete: `docs/wikantik-pages/blog/` (71 files, includes `admin/`, `jakefear/`, and nested `OLD/` version history)
- Delete: `docs/Blog.md`, `docs/wikantik-pages/BlogFeature.md`, `docs/wikantik-pages/BlogEditorSplitView.md`
- Modify: `docs/wikantik-pages/WikiToMarkdownConverter.md`, `docs/wikantik-pages/AttachmentManagement.md`, `docs/wikantik-pages/JspToReactMigration.md` — strip inbound links to the deleted pages

**Interfaces:**
- Consumes: nothing
- Produces: a corpus with zero `blog/` pages and zero links to them. Later tasks may assume no wiki page references a blog route.

- [ ] **Step 1: Record the inbound links that will break**

```bash
cd /home/jakefear/source/jspwiki
grep -rn "BlogFeature\|BlogEditorSplitView\|](/blog\|blog/jakefear\|blog/admin" \
  docs/wikantik-pages --include=*.md | grep -v "^docs/wikantik-pages/blog/"
```

Expected: hits in `WikiToMarkdownConverter.md`, `AttachmentManagement.md`, `JspToReactMigration.md`. Note the exact line numbers — you will edit each one in Step 3.

- [ ] **Step 2: Delete the content**

```bash
git rm -r -q docs/wikantik-pages/blog
git rm -q docs/Blog.md docs/wikantik-pages/BlogFeature.md docs/wikantik-pages/BlogEditorSplitView.md
```

- [ ] **Step 3: Strip the inbound links**

For each hit from Step 1, remove the link. If the link is inline prose, remove the whole sentence when it no longer reads sensibly; if it is a bullet in a "See also" list, remove the bullet. Do not leave a bare link label with no target.

- [ ] **Step 4: Verify no dangling references remain**

```bash
grep -rn "BlogFeature\|BlogEditorSplitView\|](/blog\|blog/jakefear\|blog/admin" \
  docs/wikantik-pages docs/*.md 2>/dev/null
```

Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add -u docs/
git commit -m "docs: delete blog corpus content and inbound links"
```

---

### Task 2: Remove the blog frontend

**Files:**
- Delete: `wikantik-frontend/src/components/{BlogDiscovery,BlogHome,BlogEntry,CreateBlog,NewBlogEntry,BlogEditor}.jsx` and their six `.test.jsx` siblings
- Delete: `wikantik-frontend/src/hooks/useMyBlog.js`, `wikantik-frontend/src/hooks/useMyBlog.test.js`
- Modify: `wikantik-frontend/src/main.jsx` — 6 `React.lazy` imports (lines 20–25) and 6 `<Route>` entries (lines 62, 100–104)
- Modify: `wikantik-frontend/src/api/client.js` — the `blog: { … }` object (lines ~304–340)
- Modify: `wikantik-frontend/src/components/PersonalZone.jsx` — `useMyBlog` import (line 6), hook call (line 37), and the `my-blog` `CollapsibleSection` (lines 107–118)
- Test: `wikantik-frontend/src/components/PersonalZone.test.jsx`

**Interfaces:**
- Consumes: nothing
- Produces: no frontend route or API client method references `/api/blog` or `/blog/*`. Task 3 may then delete the server side without leaving a caller.

- [ ] **Step 1: Write the failing test**

Add to `wikantik-frontend/src/components/PersonalZone.test.jsx`:

```jsx
it('does not render a blog section', () => {
  render(
    <MemoryRouter>
      <PersonalZone login="testuser" authed={true} onMobileClose={() => {}} />
    </MemoryRouter>
  );
  expect(screen.queryByText(/my blog/i)).not.toBeInTheDocument();
  expect(screen.queryByText(/blog home/i)).not.toBeInTheDocument();
});
```

Match the existing render/wrapper idiom already used in that file — if the other tests wrap in a provider or pass different props, mirror them exactly rather than copying the above verbatim.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd wikantik-frontend && npx vitest run src/components/PersonalZone.test.jsx
```

Expected: FAIL — "My blog" is currently rendered.

- [ ] **Step 3: Delete the blog components and hook**

```bash
cd /home/jakefear/source/jspwiki
git rm -q wikantik-frontend/src/components/BlogDiscovery.jsx \
          wikantik-frontend/src/components/BlogDiscovery.test.jsx \
          wikantik-frontend/src/components/BlogHome.jsx \
          wikantik-frontend/src/components/BlogHome.test.jsx \
          wikantik-frontend/src/components/BlogEntry.jsx \
          wikantik-frontend/src/components/BlogEntry.test.jsx \
          wikantik-frontend/src/components/CreateBlog.jsx \
          wikantik-frontend/src/components/CreateBlog.test.jsx \
          wikantik-frontend/src/components/NewBlogEntry.jsx \
          wikantik-frontend/src/components/NewBlogEntry.test.jsx \
          wikantik-frontend/src/components/BlogEditor.jsx \
          wikantik-frontend/src/components/BlogEditor.test.jsx \
          wikantik-frontend/src/hooks/useMyBlog.js \
          wikantik-frontend/src/hooks/useMyBlog.test.js
```

- [ ] **Step 4: Remove the routes from `main.jsx`**

Delete these six `const … = React.lazy(…)` lines:

```jsx
const BlogDiscovery = React.lazy(() => import('./components/BlogDiscovery'));
const BlogHome = React.lazy(() => import('./components/BlogHome'));
const BlogEntry = React.lazy(() => import('./components/BlogEntry'));
const CreateBlog = React.lazy(() => import('./components/CreateBlog'));
const NewBlogEntry = React.lazy(() => import('./components/NewBlogEntry'));
const BlogEditor = React.lazy(() => import('./components/BlogEditor'));
```

and these six routes:

```jsx
<Route path="/edit/blog/:username/:pageName" element={<BlogEditor />} />
<Route path="/blog" element={<BlogDiscovery />} />
<Route path="/blog/create" element={<CreateBlog />} />
<Route path="/blog/:username/new" element={<NewBlogEntry />} />
<Route path="/blog/:username/Blog" element={<BlogHome />} />
<Route path="/blog/:username/:entryName" element={<BlogEntry />} />
```

Also update the comment on line 12 which lists "admin pages, editors, blog, graph viewers" — drop the word "blog".

- [ ] **Step 5: Remove the `blog` API client object**

In `wikantik-frontend/src/api/client.js`, delete the `// Blog` comment and the entire `blog: { … },` object (`list`, `get`, `create`, `update`, `remove`, `listEntries`, `getEntry`, `createEntry`, `updateEntry`, `deleteEntry`). Leave the surrounding object's comma structure valid.

- [ ] **Step 6: Remove the PersonalZone blog section**

Delete the `import { useMyBlog } from '../hooks/useMyBlog';` line, the `const { entries } = useMyBlog({ login, enabled: authed });` line, and the whole `<CollapsibleSection id="my-blog" …>` block including its children.

- [ ] **Step 7: Run the frontend suite**

```bash
cd wikantik-frontend && npm run lint && npm test
```

Expected: lint 0 errors; all tests pass including the new PersonalZone assertion. Any test failing with "Cannot find module './components/Blog…'" means a leftover import — fix it.

- [ ] **Step 8: Commit**

```bash
cd /home/jakefear/source/jspwiki
git add -u wikantik-frontend/
git commit -m "feat: remove blog UI (components, routes, api client, sidebar)"
```

---

### Task 3: Remove the blog REST resource and routes

**Files:**
- Delete: `wikantik-rest/src/main/java/com/wikantik/rest/BlogResource.java`
- Delete: `wikantik-rest/src/test/java/com/wikantik/rest/BlogResourceTest.java`
- Delete: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/BlogIT.java`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java:97,98,194`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` — lines 380–381 (SPA filter patterns), 650–654 (servlet decl), 957–964 (two servlet-mappings)
- Modify: `wikantik-war/src/test/java/com/wikantik/war/RobotsTxtTest.java:107-108`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/SpaRoutingFilterTest.java`

**Interfaces:**
- Consumes: Task 2's guarantee that no frontend code calls `/api/blog`
- Produces: no HTTP surface at `/blog`, `/blog/*`, `/api/blog`, or `/api/blog/*`. Task 4 may then delete the plugins with no REST caller.

- [ ] **Step 1: Write the failing test**

`SpaRoutingFilter` exposes no static route predicate — the only entry point is `doFilter`. The existing tests drive it through the filter and assert on the chain: an SPA route is swallowed (`verify( chain, never() ).doFilter(…)`), so a non-SPA path is the inverse — it passes through to the chain. Add to `SpaRoutingFilterTest.java`, using the file's own `mockRequest( String )` helper:

```java
// ---- blog removal: /blog is no longer an SPA route ----

@Test
void blogRootPassesThroughToTheChain() throws Exception {
    final HttpServletRequest request = mockRequest( "/blog" );

    filter.doFilter( request, response, chain );

    verify( chain ).doFilter( any(), any() );
}

@Test
void blogSubPathPassesThroughToTheChain() throws Exception {
    final HttpServletRequest request = mockRequest( "/blog/jakefear/Blog" );

    filter.doFilter( request, response, chain );

    verify( chain ).doFilter( any(), any() );
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
bin/agent-build.sh start t3 -- mvn test -pl wikantik-rest -Dtest=SpaRoutingFilterTest
bin/agent-build.sh wait t3 300
```

Expected: FAIL — `/blog` is currently an SPA route.

- [ ] **Step 3: Remove the SPA route entries**

In `SpaRoutingFilter.java`, drop `"/blog/"` from `SPA_PREFIXES` (line 97) and `"/blog"` from `SPA_EXACT` (line 98):

```java
private static final String[] SPA_PREFIXES = { "/wiki/", "/edit/", "/diff/", "/admin/" };
private static final String[] SPA_EXACT = { "/admin", "/search", "/page-graph", "/knowledge-graph", "/preferences", "/reset-password", "/login", "/me/mentions", "/change-password" };
```

Update the comment on line 194 to drop `/blog/` from its list.

- [ ] **Step 4: Remove the web.xml entries**

Delete these two lines from the SPA filter-mapping (around line 380):

```xml
<url-pattern>/blog/*</url-pattern>
<url-pattern>/blog</url-pattern>
```

Delete the servlet declaration (around line 650):

```xml
<!-- Blog API -->
<servlet>
    <servlet-name>BlogResource</servlet-name>
    <servlet-class>com.wikantik.rest.BlogResource</servlet-class>
</servlet>
```

Delete both servlet-mappings (around line 957):

```xml
<servlet-mapping>
    <servlet-name>BlogResource</servlet-name>
    <url-pattern>/api/blog/*</url-pattern>
</servlet-mapping>
<servlet-mapping>
    <servlet-name>BlogResource</servlet-name>
    <url-pattern>/api/blog</url-pattern>
</servlet-mapping>
```

- [ ] **Step 5: Delete the resource and its tests**

```bash
git rm -q wikantik-rest/src/main/java/com/wikantik/rest/BlogResource.java \
          wikantik-rest/src/test/java/com/wikantik/rest/BlogResourceTest.java \
          wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/BlogIT.java
```

- [ ] **Step 6: Fix `RobotsTxtTest`**

Lines 107–108 currently assert robots.txt must *not* disallow `/blog`, on the grounds that blog is public content. That rationale is gone. Delete the assertion entirely:

```java
assertFalse( content.contains( "Disallow: /blog" ),
        "robots.txt must not disallow /blog (public content); was: " + content );
```

Do not replace it with an assertion that `/blog` *is* disallowed — the path simply no longer exists, and asserting on it would re-encode the dead concept.

- [ ] **Step 7: Run the tests**

```bash
bin/agent-build.sh start t3b -- bash -c "mvn test -pl wikantik-rest -Dtest=SpaRoutingFilterTest && mvn test -pl wikantik-war -Dtest=RobotsTxtTest"
bin/agent-build.sh wait t3b 300
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -u wikantik-rest/ wikantik-war/ wikantik-it-tests/
git commit -m "feat: remove blog REST resource and HTTP routes"
```

---

### Task 4: Remove the blog plugins

`BlogListing`, `ArticleListing`, and `LatestArticle` are all blog-only. `RecentArticles` is **not** — leave it.

**Files:**
- Delete: `wikantik-main/src/main/java/com/wikantik/plugin/{BlogListing,ArticleListing,LatestArticle}.java`
- Delete: `wikantik-main/src/test/java/com/wikantik/plugin/{BlogListingTest,ArticleListingTest,LatestArticleTest}.java`
- Modify: `wikantik-main/src/main/resources/ini/wikantik_module.xml` — three `<plugin>` blocks at lines 55–71

**Interfaces:**
- Consumes: Task 1's guarantee that no corpus page invokes `[{ArticleListing}]` or `[{LatestArticle}]`
- Produces: `BlogManager` has no remaining callers inside `wikantik-main` except the engine wiring, which Task 5 removes.

- [ ] **Step 1: Confirm no corpus page still invokes the plugins**

```bash
grep -rn "ArticleListing\|LatestArticle\|BlogListing" docs/wikantik-pages wikantik-wikipages --include=*.md 2>/dev/null | grep -v "/target/"
```

Expected: no output. (Task 1 deleted the only two pages that used them.) If anything appears, remove the plugin invocation from that page before continuing.

- [ ] **Step 2: Delete the plugin registrations**

In `wikantik-main/src/main/resources/ini/wikantik_module.xml`, delete all three `<plugin>` blocks — the ones whose `class` attributes are `com.wikantik.plugin.BlogListing`, `com.wikantik.plugin.LatestArticle`, and `com.wikantik.plugin.ArticleListing`, each including its nested `<alias>` element and closing `</plugin>` tag.

- [ ] **Step 3: Delete the plugin classes and tests**

```bash
git rm -q wikantik-main/src/main/java/com/wikantik/plugin/BlogListing.java \
          wikantik-main/src/main/java/com/wikantik/plugin/ArticleListing.java \
          wikantik-main/src/main/java/com/wikantik/plugin/LatestArticle.java \
          wikantik-main/src/test/java/com/wikantik/plugin/BlogListingTest.java \
          wikantik-main/src/test/java/com/wikantik/plugin/ArticleListingTest.java \
          wikantik-main/src/test/java/com/wikantik/plugin/LatestArticleTest.java
```

- [ ] **Step 4: Verify the module descriptor is still well-formed and plugins still load**

```bash
bin/agent-build.sh start t4 -- mvn test -pl wikantik-main -Dtest='DefaultPluginManagerTest,PluginCoverageTest,RecentArticlesTest'
bin/agent-build.sh wait t4 420
```

Expected: PASS. This proves `wikantik_module.xml` still parses and the surviving plugins still register. (Verified during planning: none of these three classes references `BlogListing`, `ArticleListing`, or `LatestArticle`, so they need no edits — they are here purely as the regression signal.)

- [ ] **Step 5: Commit**

```bash
git add -u wikantik-main/
git commit -m "feat: remove blog plugins (BlogListing, ArticleListing, LatestArticle)"
```

---

### Task 5: Remove BlogManager and its engine wiring

**Files:**
- Delete: `wikantik-main/src/main/java/com/wikantik/blog/{BlogManager,DefaultBlogManager,BlogInfo,BlogAlreadyExistsException}.java`
- Delete: `wikantik-main/src/main/resources/com/wikantik/blog/BlogTemplate.md`
- Delete: `wikantik-main/src/test/java/com/wikantik/blog/DefaultBlogManagerTest.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` — lines 39, 212, 220, 486, 643–644, 698, 708, 840, 850–851, 1709
- Modify: `wikantik-main/src/main/java/com/wikantik/core/subsystem/CoreSubsystem.java` — lines 23, 45, 69, 86
- Modify: `wikantik-main/src/main/java/com/wikantik/core/subsystem/CoreSubsystemBridge.java` — lines 23, 92
- Modify: `wikantik-main/src/main/java/com/wikantik/core/subsystem/CoreSubsystemFactory.java` — lines 51, 72
- Modify tests: `RenderCacheInvalidationTest.java`, `CoreSubsystemFactoryTest.java`, `CoreSubsystemBridgeTest.java`, `AuthSubsystemFactoryTest.java`, `KnowledgeSubsystemFactoryTest.java`

**Interfaces:**
- Consumes: Tasks 2–4's guarantee that no UI, REST resource, or plugin calls `BlogManager`
- Produces: the `CoreSubsystem` record no longer has a `blogManager` component; `WikiEngine.getManager()` no longer special-cases it. Task 6 may then remove provider logic with no manager depending on the storage layout.

- [ ] **Step 1: Write the failing test**

Add to `wikantik-main/src/test/java/com/wikantik/core/subsystem/CoreSubsystemFactoryTest.java`:

```java
@Test
void coreSubsystemHasNoBlogComponent() {
    final boolean hasBlogComponent = java.util.Arrays.stream(
            CoreSubsystem.class.getRecordComponents() )
        .anyMatch( c -> c.getName().toLowerCase( java.util.Locale.ROOT ).contains( "blog" ) );
    assertFalse( hasBlogComponent,
        "CoreSubsystem must not carry a blogManager component after blog removal" );
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
bin/agent-build.sh start t5 -- mvn test -pl wikantik-main -Dtest=CoreSubsystemFactoryTest
bin/agent-build.sh wait t5 420
```

Expected: FAIL — `blogManager` is still a record component.

- [ ] **Step 3: Remove the blog component from the CoreSubsystem trio**

In `CoreSubsystem.java`: delete the `import com.wikantik.blog.BlogManager;`, drop `BlogManager blogManager,` from **both** the record header (line 69) and the secondary constructor/factory signature (line 86), and remove `{@link BlogManager}` from the class javadoc on line 45.

In `CoreSubsystemBridge.java`: delete the import (line 23) and the `engine.getManager( BlogManager.class ),` argument (line 92).

In `CoreSubsystemFactory.java`: delete the `deps.blogManager(),` argument (line 72) and drop `blogManager` from the "optional" comment on line 51.

- [ ] **Step 4: Remove the WikiEngine wiring**

Work through all ten sites:

- line 39 — delete `import com.wikantik.blog.BlogManager;`
- lines 212 and 840 — comments listing the managers excluded from `SNAPSHOT_REBUILDERS`; drop the "and BlogManager" phrasing so the sentence still reads correctly
- line 220 — delete the `|| t == com.wikantik.blog.BlogManager.class` clause from the predicate
- line 486 — delete the `getManager( BlogManager.class )` argument
- lines 643–644 — delete the `// Phase 7b: BlogManager …` comment and the `initComponent( BlogManager.class );` call
- line 698 — delete the `final BlogManager blogManager` parameter
- line 708 — delete the `blogManager,` argument
- lines 850–851 — delete the `if ( manager.isInstance( coreSubsystem.blogManager() ) ) { return ( T ) coreSubsystem.blogManager(); }` branch
- line 1709 — drop `BlogManager` from the javadoc list

- [ ] **Step 5: Delete the blog package**

```bash
git rm -r -q wikantik-main/src/main/java/com/wikantik/blog \
             wikantik-main/src/main/resources/com/wikantik/blog \
             wikantik-main/src/test/java/com/wikantik/blog
```

- [ ] **Step 6: Fix the tests that referenced BlogManager**

`RenderCacheInvalidationTest.java` — delete the `import com.wikantik.blog.BlogManager;` (line 27), the entire `testBlogHomeHtmlUpdatesAfterNewEntry` test method (starts line 86), and the blog cleanup block in setup (lines 54–58, the `bm.blogExists(…)` / `bm.deleteBlog(…)` guard). The remaining non-blog cache-invalidation tests in that class stay.

`CoreSubsystemFactoryTest`, `CoreSubsystemBridgeTest`, `AuthSubsystemFactoryTest`, `KnowledgeSubsystemFactoryTest` — these construct `CoreSubsystem` instances or stub `getManager`. Remove the `blogManager` argument / stub from each construction site. The arity change makes the compiler point at every one.

- [ ] **Step 7: Compile tests, then run them**

`mvn compile` does **not** compile test sources — the signature change above will only surface with an explicit test-compile:

```bash
bin/agent-build.sh start t5b -- bash -c "mvn test-compile -pl wikantik-main && mvn test -pl wikantik-main -Dtest='CoreSubsystem*Test,RenderCacheInvalidationTest,AuthSubsystemFactoryTest,KnowledgeSubsystemFactoryTest'"
bin/agent-build.sh wait t5b 480
```

Expected: PASS, including the new `coreSubsystemHasNoBlogComponent`.

- [ ] **Step 8: Commit**

```bash
git add -u wikantik-main/
git commit -m "feat: remove BlogManager and its engine/subsystem wiring"
```

---

### Task 6: Remove blog special-casing from the page-storage providers

**This is the only task that edits code shared with every non-blog page.** A mistake here corrupts page storage generally, not just blog. Treat the existing non-blog provider tests as the regression gate.

The removal is mostly *simplification*: `normaliseBlogName` exists solely to case-fold the blog username segment, so all eight `isBlogPage( … ) ? normaliseBlogName( … ) : …` ternaries collapse to their else-branch.

**Files:**
- Delete: `wikantik-main/src/test/java/com/wikantik/providers/AbstractFileProviderBlogTest.java`
- Delete: `wikantik-main/src/test/java/com/wikantik/providers/VersioningFileProviderBlogTest.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/providers/AbstractFileProvider.java` — lines 192–207 (constant + predicate), 270–278 (mangleName branch), 320–322, 348–359 (normaliseBlogName), 372, 473–481 (parent-dir guard), 497, 513, 559–581 (getAllPages recursion), 770, 790, 890
- Modify: `wikantik-main/src/main/java/com/wikantik/providers/VersioningFileProvider.java` — lines 184–187, 198–208, 215–219

**Interfaces:**
- Consumes: Task 5's guarantee that no manager depends on `blog/<user>/<slug>` storage
- Produces: `AbstractFileProvider.isBlogPage` and `normaliseBlogName` no longer exist; page names containing `/` receive no special treatment.

- [ ] **Step 1: Write the failing test**

Add to `wikantik-main/src/test/java/com/wikantik/providers/AbstractFileProviderTest.java`:

```java
@Test
void providerHasNoBlogSpecialCasing() {
    final boolean hasBlogMethod = java.util.Arrays.stream(
            AbstractFileProvider.class.getDeclaredMethods() )
        .anyMatch( m -> m.getName().toLowerCase( java.util.Locale.ROOT ).contains( "blog" ) );
    assertFalse( hasBlogMethod,
        "AbstractFileProvider must not retain blog-specific methods after blog removal" );
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
bin/agent-build.sh start t6 -- mvn test -pl wikantik-main -Dtest=AbstractFileProviderTest
bin/agent-build.sh wait t6 420
```

Expected: FAIL — `isBlogPage` and `normaliseBlogName` are still declared.

- [ ] **Step 3: Capture the pre-change baseline**

Before touching provider code, record that the non-blog provider suite is green, so any later red is unambiguously yours:

```bash
bin/agent-build.sh start t6base -- mvn test -pl wikantik-main -Dtest='AbstractFileProviderTest,AbstractFileProviderChangedSinceTest,VersioningFileProviderTest,VersioningFileProviderCITest,VersioningFileProviderConcurrencyTest,CachingProviderTest,CachingProviderConcurrencyTest,FileSystemProviderTest,PageProviderDecoratorTest'
bin/agent-build.sh wait t6base 480
```

Expected: only the new `providerHasNoBlogSpecialCasing` fails. Everything else passes.

- [ ] **Step 4: Collapse the cache-key ternaries**

At lines 322, 372, 497, 513, 770, 790, and 890 the pattern is:

```java
final String cacheKey = isBlogPage( page ) ? normaliseBlogName( page ) : page;
```

Replace each with the else-branch, and inline it where the local no longer earns its name:

```java
final String cacheKey = page;
```

The variable names differ per site (`page`, `pageName`, `page.getName()`, `migCacheKey`) — read each line and keep the correct operand. Also delete the now-stale comments at lines 320–321 explaining the case-folding.

- [ ] **Step 5: Remove the mangleName branch, the predicate, and the helper**

Delete the blog branch in `mangleName` (lines 270–278) so the method falls through to its ordinary encoding path. Delete `normaliseBlogName` entirely (lines 348–359). Delete `isBlogPage` and `BLOG_PREFIX` (lines 192–207).

- [ ] **Step 6: Remove the save-time parent-directory guard**

Delete the block at lines 473–481 that throws `ProviderException( "Blog directory does not exist: " … )`. This guard existed only because `BlogManager` owned directory creation; with no blog pages there is no such precondition.

- [ ] **Step 7: Remove the getAllPages recursion**

Delete lines 559–581 — the whole `final File blogRoot = new File( pageDirectory, "blog" ); if( blogRoot.isDirectory() ) { … }` block that walks `blog/<user>/` subdirectories. This is purely additive discovery; removing it cannot affect top-level page enumeration.

- [ ] **Step 8: Remove the nested-OLD logic from VersioningFileProvider**

Delete the `if( AbstractFileProvider.isBlogPage( page ) ) { … }` branch at lines 203–208 so `findOldPageDir` always returns `new File( getPageDirectory(), PAGEDIR )`, and the matching branch at lines 219+ so the versioned filename is always the full mangled name. Delete the two javadoc paragraphs at lines 184–187 and 198–199 describing the blog nesting.

- [ ] **Step 9: Delete the blog provider tests**

```bash
git rm -q wikantik-main/src/test/java/com/wikantik/providers/AbstractFileProviderBlogTest.java \
          wikantik-main/src/test/java/com/wikantik/providers/VersioningFileProviderBlogTest.java
```

- [ ] **Step 10: Re-run the full provider suite**

```bash
bin/agent-build.sh start t6b -- mvn test -pl wikantik-main -Dtest='AbstractFileProviderTest,AbstractFileProviderChangedSinceTest,VersioningFileProviderTest,VersioningFileProviderCITest,VersioningFileProviderConcurrencyTest,CachingProviderTest,CachingProviderConcurrencyTest,FileSystemProviderTest,PageProviderDecoratorTest'
bin/agent-build.sh wait t6b 480
```

Expected: ALL PASS, including `providerHasNoBlogSpecialCasing`. Compare against the Step 3 baseline — any newly-failing test is a real regression in shared storage code, not a blog artefact. Stop and use `superpowers:systematic-debugging` if so.

- [ ] **Step 11: Commit**

```bash
git add -u wikantik-main/
git commit -m "feat: remove blog special-casing from page-storage providers"
```

---

### Task 7: Sweep the leftovers and run the canonical gate

**Files:**
- Modify: `wikantik-main/src/main/resources/plugin/PluginResources_ru.properties` — `weblogentryplugin.*` keys (lines 46–79)
- Modify: `wikantik-util/src/test/resources/templates/default.properties` — `blog.*` keys (lines 607–611)
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: everything from Tasks 1–6
- Produces: a repo where the only remaining "blog" matches are the documented false positives.

- [ ] **Step 1: Remove the dead i18n keys**

In `PluginResources_ru.properties`, delete the `# WeblogEntryPlugin` comment and the `weblogentryplugin.*` keys — these are legacy JSPWiki leftovers for a plugin that no longer exists. Check the sibling locale files for the same keys:

```bash
grep -rn "weblogentryplugin\|blog\." wikantik-main/src/main/resources/plugin/ wikantik-util/src/test/resources/templates/
```

Delete every match. In `default.properties` that is the `#blog texts in various JSPs` comment plus `blog.commenttitle`, `blog.backtomain`, `blog.addcomments`, `blog.permalink`.

- [ ] **Step 2a: Delete the transient TDD scaffolding**

These two tests were removal anchors, not regression guards. They assert internal structure via
reflection, and leaving them behind is exactly the cruft this removal is meant to avoid:

- `wikantik-main/src/test/java/com/wikantik/core/subsystem/CoreSubsystemFactoryTest.java` — delete the
  `coreSubsystemHasNoBlogComponent` test method (and its now-unused `java.util.Arrays` import if
  nothing else in the file uses it).
- `wikantik-main/src/test/java/com/wikantik/providers/AbstractFileProviderTest.java` — delete the
  `providerHasNoBlogSpecialCasing` test method (same import check).

Keep the Task 2 `PersonalZone` assertion and the Task 3 `SpaRoutingFilter` pass-through tests — those
assert real user-visible behaviour and earn their place.

- [ ] **Step 2: Full-repo sweep**

```bash
grep -rin "blog" --include=*.java --include=*.jsx --include=*.js --include=*.xml \
     --include=*.properties --include=*.md --include=*.sql --include=*.yml . 2>/dev/null \
  | grep -v "/target/" | grep -v node_modules | grep -v "/dist/" | grep -v "^./tomcat/"
```

Every remaining hit must be on the false-positive list in this plan's "Known False Positives" section. Anything else is a leftover — remove it.

- [ ] **Step 3: Add the CHANGELOG entry**

Under `## [Unreleased]` → `### Removed` (create the subsection if absent):

```markdown
### Removed
- **The blog feature is gone in its entirety.** `BlogManager` and its engine wiring, the
  `/api/blog` REST surface, the `/blog/*` SPA routes, the `BlogListing`/`ArticleListing`/
  `LatestArticle` plugins, the six React blog screens, and all blog content. Blogging moved to a
  separate application, so this removes a subsystem rather than deprecating it.
  The notable part is the page-storage layer: blog pages had their own on-disk layout
  (`blog/<username>/<slug>` as real subdirectories, with `OLD/` nested *inside* the user directory
  rather than at the top level) plus username case-folding on every cache key. That special-casing
  reached ~12 sites across `AbstractFileProvider` and `VersioningFileProvider` — shared code on the
  hot path for every page, not just blog ones — and is now gone, collapsing eight
  `isBlogPage(…) ? normaliseBlogName(…) : …` ternaries to their else-branch.
```

- [ ] **Step 4: Run the canonical gate**

```bash
bin/agent-build.sh start gate-blog -- bin/run-tests.sh --parallel 4
bin/agent-build.sh wait gate-blog 540
```

Expected: `RESULT: ALL PASSED`.

- [ ] **Step 5: Verify the gate result against fresh logs only**

`.test-suite-logs/` retains logs from older runs under different filenames — a stale failure will read as current if you aggregate the whole directory. A current `--parallel` run produces exactly `phase1-unit.log` and `it-parallel.log`:

```bash
ls -la --time-style=+%H:%M:%S .test-suite-logs/phase1-unit.log .test-suite-logs/it-parallel.log
grep -E "RESULT" .build-logs/gate-blog.log
```

Confirm both mtimes fall inside this run's window, then trust `RESULT: ALL PASSED`. Expect the unit count to drop by roughly the number of deleted test methods versus the ~8,932 baseline — a *large* unexplained drop means tests silently stopped being discovered.

- [ ] **Step 6: Commit**

```bash
git add -u wikantik-main/ wikantik-util/ CHANGELOG.md
git commit -m "feat: remove blog i18n keys; changelog for blog removal"
```

---

### Task 8: Delete blog content from production

Code deletion does not remove pages: the prod page store survives deploys, and prod content is managed over MCP rather than by the deploy. Without this task, prod keeps orphaned `blog/*` pages that the provider can no longer resolve correctly.

**Files:** none in the repo — this is an operational step against the live wiki.

**Interfaces:**
- Consumes: Task 7's verified-green build
- Produces: a prod page store with no `blog/` pages.

- [ ] **Step 1: Enumerate the blog pages that exist on prod**

Use the admin MCP tools (not curl/REST — the MCP surface is the sanctioned path for wiki content). List pages and filter for names beginning `blog/`. Record the exact list before deleting anything.

- [ ] **Step 2: Confirm the list with the user**

Show the enumerated page names and get explicit confirmation before deleting. Page deletion is irreversible and outward-facing.

- [ ] **Step 3: Delete them**

Use `mcp__wikantik-admin__delete_pages` on the confirmed list. Pace calls to roughly 10 requests/second.

- [ ] **Step 4: Verify**

Re-run the enumeration from Step 1. Expected: no pages with a `blog/` prefix. Also confirm `/blog` now 404s rather than serving the SPA shell.

- [ ] **Step 5: Rebuild search and Knowledge Graph indexes**

Deleted pages leave stale entries in the BM25 and dense indexes. Trigger a rebuild so retrieval stops surfacing them:

```bash
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')
curl -u "${login}:${password}" -X POST https://wiki.wikantik.com/admin/content/rebuild-indexes
```

Expected: the endpoint accepts the rebuild. Afterwards, a search for a former blog title returns no results.

---

## Self-Review

**Spec coverage.** Every surface found during investigation maps to a task: content → T1; frontend components/routes/api/sidebar → T2; REST resource, web.xml, SPA routing, robots test → T3; plugins + module descriptor → T4; BlogManager package, engine wiring, subsystem trio, dependent tests → T5; provider special-casing → T6; i18n keys, sweep, changelog, gate → T7; prod content + index rebuild → T8.

**Ordering.** Strictly outside-in, so each task deletes code that the previous task already orphaned. T6 (shared storage code) runs last among the code tasks and carries its own before/after regression baseline.

**Known risks.** T6 is the only task touching code shared with non-blog pages; it has an explicit baseline step (T6/Step 3) and a named regression suite. T5's record-arity change is compiler-visible but only under `test-compile`, which Step 7 calls out explicitly.

**Naming consistency.** `isBlogPage`, `normaliseBlogName`, `BLOG_PREFIX`, `BLOG_DIR`, `BLOG_HOME_PAGE`, `blogPagePath`, `blogManager` are used consistently throughout and all originate from files verified during investigation.
