# Testability: MockEngineBuilder + Constructor Injection

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make 7 high-value wikantik-main classes testable by introducing a lightweight mock engine helper and refactoring each class to accept dependencies through its constructor rather than fetching them at runtime via `engine.getManager()`.

**Architecture:** A `MockEngineBuilder` test utility creates a mock `Engine` pre-wired with stub managers, unblocking test writing immediately. Then each target class gets a new constructor that accepts its dependencies directly, while keeping the existing engine-based constructor as a one-line delegating convenience. Tests use the new constructor with mocks. Each class is its own commit.

**Tech Stack:** Java 21, JUnit 5, Mockito, existing wikantik manager interfaces

---

## Background

The `Engine.getManager(Class)` pattern is used 261 times across 59 files. Every class that needs a `PageManager` or `AuthorizationManager` fetches it at runtime from the engine's `ConcurrentHashMap<Class, Object>`. This means tests must boot an entire `WikiEngine` — filesystem, providers, auth, plugins — just to test one class.

The fix is constructor injection: each class declares what it needs as constructor parameters. The engine wires them at startup. Tests pass mocks directly.

### The Pattern

For each target class, the refactoring follows this template:

**Before (service locator):**
```java
public class DefaultFoo implements Foo {
    private final Engine engine;

    public DefaultFoo(Engine engine, Properties props) {
        this.engine = engine;
        // later in methods:
        engine.getManager(BarManager.class).doThing();
    }
}
```

**After (constructor injection + delegating convenience):**
```java
public class DefaultFoo implements Foo {
    private final Engine engine;
    private final BarManager barManager;

    // New: direct injection constructor for tests and future DI
    public DefaultFoo(Engine engine, Properties props, BarManager barManager) {
        this.engine = engine;
        this.barManager = barManager;
        // ...
    }

    // Existing: convenience constructor delegates
    public DefaultFoo(Engine engine, Properties props) {
        this(engine, props, engine.getManager(BarManager.class));
    }

    // methods use this.barManager instead of engine.getManager(BarManager.class)
}
```

### Key Rules

1. **No behavior change.** The delegating constructor produces identical runtime behavior.
2. **All `getManager()` calls in the class move to constructor parameters.** No partial migration.
3. **Fields become `final` where possible.** If a field was assigned in the constructor via `getManager()`, it becomes a final constructor parameter.
4. **`WikiEngine.initialize()` is NOT changed.** It continues to use the existing convenience constructors. The new constructors exist for tests and future use.

## File Map

### New Files
- `wikantik-main/src/test/java/com/wikantik/MockEngineBuilder.java` — test utility
- `wikantik-main/src/test/java/com/wikantik/attachment/DefaultAttachmentManagerTest.java`
- `wikantik-main/src/test/java/com/wikantik/attachment/AttachmentServletTest.java`
- `wikantik-main/src/test/java/com/wikantik/auth/SecurityVerifierTest.java`
- `wikantik-main/src/test/java/com/wikantik/filters/SpamFilterTest.java`
- `wikantik-main/src/test/java/com/wikantik/pages/DefaultPageManagerCITest.java` (CI = constructor injection tests, avoids conflict with existing DefaultPageManagerTest)
- `wikantik-main/src/test/java/com/wikantik/references/DefaultReferenceManagerCITest.java`
- `wikantik-main/src/test/java/com/wikantik/content/DefaultRecentArticlesManagerCITest.java`
- `wikantik-main/src/test/java/com/wikantik/render/DefaultRenderingManagerCITest.java`

### Modified Files (constructor injection refactoring)
- `wikantik-main/src/main/java/com/wikantik/filters/SpamFilter.java`
- `wikantik-main/src/main/java/com/wikantik/auth/SecurityVerifier.java`
- `wikantik-main/src/main/java/com/wikantik/attachment/AttachmentServlet.java`
- `wikantik-main/src/main/java/com/wikantik/pages/DefaultPageManager.java`
- `wikantik-main/src/main/java/com/wikantik/references/DefaultReferenceManager.java`
- `wikantik-main/src/main/java/com/wikantik/content/DefaultRecentArticlesManager.java`
- `wikantik-main/src/main/java/com/wikantik/render/DefaultRenderingManager.java`

---

## Task 1: Create MockEngineBuilder

**Files:**
- Create: `wikantik-main/src/test/java/com/wikantik/MockEngineBuilder.java`
- Test: verified by use in subsequent tasks

- [ ] **Step 1: Write MockEngineBuilder**

```java
package com.wikantik;

import com.wikantik.api.core.Engine;
import java.util.Properties;
import static org.mockito.Mockito.*;

/**
 * Lightweight builder that creates a mock {@link Engine} pre-wired with
 * stub manager instances. Each {@code with()} call registers a manager
 * that will be returned by {@code engine.getManager(type)}.
 *
 * <p>Usage:
 * <pre>{@code
 * Engine engine = MockEngineBuilder.engine()
 *     .with(PageManager.class, mockPageManager)
 *     .with(SearchManager.class, mockSearchManager)
 *     .properties(props)
 *     .build();
 * }</pre>
 */
public final class MockEngineBuilder {

    private final Engine engine;
    private Properties properties;

    private MockEngineBuilder() {
        engine = mock( Engine.class );
        properties = new Properties();
        when( engine.getWikiProperties() ).thenReturn( properties );
        when( engine.getApplicationName() ).thenReturn( "test" );
        when( engine.isConfigured() ).thenReturn( true );
    }

    public static MockEngineBuilder engine() {
        return new MockEngineBuilder();
    }

    public < T > MockEngineBuilder with( final Class< T > managerType, final T instance ) {
        when( engine.getManager( managerType ) ).thenReturn( instance );
        return this;
    }

    public MockEngineBuilder properties( final Properties props ) {
        this.properties = props;
        when( engine.getWikiProperties() ).thenReturn( props );
        return this;
    }

    public MockEngineBuilder property( final String key, final String value ) {
        properties.setProperty( key, value );
        return this;
    }

    public Engine build() {
        return engine;
    }

}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn compile -pl wikantik-main -Dmaven.test.skip -q && mvn test-compile -pl wikantik-main -q`

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/MockEngineBuilder.java
git commit -m "Add MockEngineBuilder test utility for mock Engine creation"
```

---

## Task 2: Refactor SpamFilter (358 uncovered lines, 5 getManager calls)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/filters/SpamFilter.java`
- Create: `wikantik-main/src/test/java/com/wikantik/filters/SpamFilterTest.java`

Before refactoring, read SpamFilter.java completely to identify:
1. All `getManager()` calls and which manager types they resolve
2. Which are in the constructor vs. in methods
3. The constructor signature

Then:

- [ ] **Step 1: Read SpamFilter.java and catalog all getManager calls**

Read the full file. Note every `engine.getManager(X.class)` call, what method it's in, and whether the result can be captured as a constructor-injected field.

- [ ] **Step 2: Add constructor-injection constructor**

Add a new constructor that takes all manager dependencies as parameters. Make each a `final` field. The existing constructor delegates to the new one. Replace all `engine.getManager()` calls in methods with field references.

- [ ] **Step 3: Verify existing tests still pass**

Run: `mvn test -pl wikantik-main -q`
Expected: all 1380 tests pass, 0 failures

- [ ] **Step 4: Write SpamFilterTest using MockEngineBuilder and the new constructor**

Test the core spam-detection logic:
- URL pattern matching (banned URLs)
- Content change detection
- Hostname resolution behavior
- Akismet integration points (mock the Akismet client)
- Bot trap detection
- User-agent filtering

Use `MockEngineBuilder.engine().with(...)` to provide mock managers. Instantiate SpamFilter via the new constructor. Verify that assertions test BEHAVIOR, not just coverage.

- [ ] **Step 5: Run tests**

Run: `mvn test -pl wikantik-main -q`
Expected: all tests pass

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/filters/SpamFilter.java \
       wikantik-main/src/test/java/com/wikantik/filters/SpamFilterTest.java
git commit -m "Refactor SpamFilter to constructor injection, add unit tests"
```

---

## Task 3: Refactor SecurityVerifier (323 uncovered lines, 7 getManager calls)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/SecurityVerifier.java`
- Create: `wikantik-main/src/test/java/com/wikantik/auth/SecurityVerifierTest.java`

Same pattern as Task 2:

- [ ] **Step 1: Read SecurityVerifier.java, catalog all getManager calls**
- [ ] **Step 2: Add constructor-injection constructor, replace getManager calls with fields**
- [ ] **Step 3: Verify existing tests still pass**
- [ ] **Step 4: Write SecurityVerifierTest**

SecurityVerifier checks wiki security configuration. Test:
- Policy file verification (mocked)
- User database verification (mocked)
- Group database verification (mocked)
- Container security verification (JSPS array checking)
- Various `verifyXxx()` methods return correct status

- [ ] **Step 5: Run tests, verify all pass**
- [ ] **Step 6: Commit**

---

## Task 4: Refactor AttachmentServlet (220 uncovered lines, 8 getManager calls)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/attachment/AttachmentServlet.java`
- Create: `wikantik-main/src/test/java/com/wikantik/attachment/AttachmentServletTest.java`

AttachmentServlet is a `HttpServlet`. The getManager calls happen after `init()` fetches the engine from the `ServletContext`. The refactoring here is slightly different: add a package-private constructor that accepts the managers, used by tests. The servlet lifecycle (`init()`) continues to work as before for production.

- [ ] **Step 1: Read AttachmentServlet.java, catalog all getManager calls**
- [ ] **Step 2: Add test-accessible constructor with manager parameters**
- [ ] **Step 3: Verify existing tests still pass**
- [ ] **Step 4: Write AttachmentServletTest**

Test with mock request/response (use `HttpMockFactory` or Mockito):
- GET for existing attachment returns correct content-type and data
- GET for non-existent attachment returns 404
- GET respects If-Modified-Since (304 response)
- POST upload with valid file
- POST upload with forbidden file extension
- Permission checks (unauthorized user gets 403)
- Content-Disposition header for forced-download types

- [ ] **Step 5: Run tests, verify all pass**
- [ ] **Step 6: Commit**

---

## Task 5: Refactor DefaultPageManager (118 uncovered lines, 21 getManager calls)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/pages/DefaultPageManager.java`
- Create: `wikantik-main/src/test/java/com/wikantik/pages/DefaultPageManagerCITest.java`

This class has the most getManager calls (21). Many are in methods, not the constructor. Catalog all of them and determine which should become fields vs. which need lazy resolution.

- [ ] **Step 1: Read DefaultPageManager.java, catalog all 21 getManager calls**
- [ ] **Step 2: Add constructor-injection constructor**

Some managers may not be available at DefaultPageManager construction time (circular dependency with ReferenceManager, SearchManager). For those, keep the `engine.getManager()` call but document why. All others become constructor parameters.

- [ ] **Step 3: Verify existing tests still pass**
- [ ] **Step 4: Write DefaultPageManagerCITest**

Test behaviors not covered by the existing DefaultPageManagerTest:
- Page existence checks
- Page locking/unlocking mechanics
- Version history retrieval
- Page deletion cascades (attachment cleanup, reference updates)
- Event firing on page operations

- [ ] **Step 5: Run tests, verify all pass**
- [ ] **Step 6: Commit**

---

## Task 6: Refactor DefaultReferenceManager (113 uncovered lines, 8 getManager calls)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/references/DefaultReferenceManager.java`
- Create: `wikantik-main/src/test/java/com/wikantik/references/DefaultReferenceManagerCITest.java`

- [ ] **Step 1: Read DefaultReferenceManager.java, catalog getManager calls**
- [ ] **Step 2: Add constructor-injection constructor**
- [ ] **Step 3: Verify existing tests still pass**
- [ ] **Step 4: Write DefaultReferenceManagerCITest**

Test:
- Reference tracking (page A links to page B)
- Undefined page detection
- Unreferenced page detection
- Reference updates on page save
- Reference cleanup on page delete

- [ ] **Step 5: Run tests, verify all pass**
- [ ] **Step 6: Commit**

---

## Task 7: Refactor DefaultRecentArticlesManager (102 uncovered lines, 7 getManager calls)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/content/DefaultRecentArticlesManager.java`
- Create: `wikantik-main/src/test/java/com/wikantik/content/DefaultRecentArticlesManagerCITest.java`

- [ ] **Step 1: Read DefaultRecentArticlesManager.java, catalog getManager calls**
- [ ] **Step 2: Add constructor-injection constructor**
- [ ] **Step 3: Verify existing tests still pass**
- [ ] **Step 4: Write DefaultRecentArticlesManagerCITest**

Test:
- Article list population from page provider
- Filtering by frontmatter attributes
- Sort order (most recent first)
- Cache invalidation on page save/delete events
- Pagination behavior

- [ ] **Step 5: Run tests, verify all pass**
- [ ] **Step 6: Commit**

---

## Task 8: Refactor DefaultRenderingManager (87 uncovered lines, 14 getManager calls)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/render/DefaultRenderingManager.java`
- Create: `wikantik-main/src/test/java/com/wikantik/render/DefaultRenderingManagerCITest.java`

- [ ] **Step 1: Read DefaultRenderingManager.java, catalog all 14 getManager calls**
- [ ] **Step 2: Add constructor-injection constructor**

Like DefaultPageManager, some managers may have circular initialization dependencies. Document any that must remain lazy.

- [ ] **Step 3: Verify existing tests still pass**
- [ ] **Step 4: Write DefaultRenderingManagerCITest**

Test:
- Markdown rendering produces expected HTML for basic markup
- Plugin placeholder expansion
- Variable substitution
- Cache key generation and cache hit/miss behavior
- Render context isolation (two concurrent renders don't interfere)

- [ ] **Step 5: Run tests, verify all pass**
- [ ] **Step 6: Commit**

---

## Verification

After all 8 tasks:

- [ ] Run the full wikantik-main test suite: `mvn test -pl wikantik-main`
- [ ] Regenerate JaCoCo report: `mvn jacoco:report -pl wikantik-main -q`
- [ ] Verify overall line coverage exceeds 75%
- [ ] Run the full project build: `mvn clean install -T 1C -DskipITs -Dmaven.test.skip=false`
