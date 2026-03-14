# Fix MCP Integration Tests & Failsafe Reporting

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make all ~60 MCP integration tests pass and ensure Maven reports BUILD FAILURE when any IT test fails.

**Architecture:** Two independent fixes — (1) the MCP server doesn't start because `McpServerInitializer` looks up the WikiEngine as a servlet attribute before it's created; fix by eagerly creating the engine. (2) `testFailureIgnore=true` in failsafe plugin silently swallows all IT failures; remove it.

**Tech Stack:** Java 21, JUnit 5, Maven Failsafe, MCP SDK 1.0.0, Cargo/Tomcat 11

---

## Root Cause Analysis

### Bug 1: MCP server never starts in Cargo-deployed Tomcat

**Sequence of events during Tomcat startup:**
1. `WikiBootstrapServletContextListener.contextInitialized()` — sets up SPIs + logging
2. `McpServerInitializer.contextInitialized()` — looks for WikiEngine via `servletContext.getAttribute("org.apache.wiki.WikiEngine")` → **returns null** → logs WARN → returns without starting MCP
3. `WikiServletFilter.init()` → calls `Wiki.engine().find(context, null)` → **creates** WikiEngine, stores it in the attribute
4. Other servlets init

The WikiEngine attribute is only set when `WikiEngine.getInstance()` is first called (lazy creation). Since `McpServerInitializer` is a listener (phase 1), it runs before filters (phase 3). The engine doesn't exist yet.

**Evidence:** Tomcat access log shows `POST /mcp HTTP/1.1" 404` — the MCP servlet is never registered.

### Bug 2: BUILD SUCCESS masks IT test failures

`jspwiki-it-tests/pom.xml` line 111:
```xml
<testFailureIgnore>true</testFailureIgnore>
```

The failsafe plugin's `verify` goal checks for test failures, but `testFailureIgnore=true` tells it to ignore them. Maven exits with 0 regardless of test results.

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `jspwiki-mcp/src/main/java/org/apache/wiki/mcp/McpServerInitializer.java` | Modify | Fix WikiEngine lookup to use `Wiki.engine().find()` instead of `getAttribute()` |
| `jspwiki-it-tests/pom.xml` | Modify | Remove `testFailureIgnore=true` |

---

## Chunk 1: Fix MCP server startup

### Task 1: Fix McpServerInitializer engine lookup

**Files:**
- Modify: `jspwiki-mcp/src/main/java/org/apache/wiki/mcp/McpServerInitializer.java:51-60`

- [ ] **Step 1: Add import for Wiki SPI**

In `McpServerInitializer.java`, add this import:

```java
import org.apache.wiki.api.spi.Wiki;
```

- [ ] **Step 2: Replace getAttribute with Wiki.engine().find()**

Replace lines 54-60:

```java
// OLD:
final WikiEngine engine = ( WikiEngine ) servletContext.getAttribute( "org.apache.wiki.WikiEngine" );
if ( engine == null ) {
    LOG.warn( "WikiEngine not found in ServletContext — MCP server not started. "
            + "Ensure McpServerInitializer runs after WikiBootstrapServletContextListener." );
    return;
}
```

With:

```java
// NEW: eagerly create the WikiEngine if it doesn't exist yet.
// WikiBootstrapServletContextListener has already initialized SPIs by the time
// this listener runs, so getInstance() is safe to call here.
WikiEngine engine;
try {
    engine = ( WikiEngine ) Wiki.engine().find( servletContext, null );
} catch ( final Exception e ) {
    LOG.warn( "WikiEngine could not be created — MCP server not started: {}", e.getMessage() );
    return;
}
```

- [ ] **Step 3: Remove unused WikiEngine import if needed**

The `WikiEngine` import is still needed for the cast. Keep it. But the `import org.apache.wiki.WikiEngine;` can be replaced with the API import if `Wiki.engine().find()` returns `Engine`. Check the return type — it returns `Engine`, so cast to `WikiEngine`:

Actually, `Wiki.engine().find()` returns `Engine` (the API interface). The tool constructors need specific manager types obtained via `engine.getManager()` which is on the `Engine` interface. So we can use `Engine` instead of `WikiEngine`:

```java
import org.apache.wiki.api.core.Engine;
```

Replace:
```java
WikiEngine engine;
```
With:
```java
Engine engine;
```

And remove `import org.apache.wiki.WikiEngine;` since it's no longer needed.

Also update the manager lookups — they already use interface types (`PageManager`, `ReferenceManager`, `AttachmentManager`), and `getManager()` is on `Engine`, so no changes needed there.

- [ ] **Step 4: Verify the MCP module compiles**

Run: `mvn compile -pl jspwiki-mcp -am`
Expected: BUILD SUCCESS

- [ ] **Step 5: Run unit tests for MCP module**

Run: `mvn test -pl jspwiki-mcp`
Expected: All tests pass

- [ ] **Step 6: Commit**

```bash
git add jspwiki-mcp/src/main/java/org/apache/wiki/mcp/McpServerInitializer.java
git commit -m "fix: eagerly create WikiEngine in McpServerInitializer

The MCP server was never starting in Cargo-deployed Tomcat because
contextInitialized() looked up the WikiEngine via getAttribute(),
but the engine isn't created until filter initialization (later).

Use Wiki.engine().find() to eagerly create the engine. The SPIs are
already initialized by WikiBootstrapServletContextListener which runs
before this listener.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Chunk 2: Fix failsafe reporting

### Task 2: Remove testFailureIgnore from failsafe configuration

**Files:**
- Modify: `jspwiki-it-tests/pom.xml:111`

- [ ] **Step 1: Remove testFailureIgnore line**

In `jspwiki-it-tests/pom.xml`, remove line 111:

```xml
<testFailureIgnore>true</testFailureIgnore>
```

This causes Maven's `verify` goal to properly fail when any integration test fails.

- [ ] **Step 2: Commit**

```bash
git add jspwiki-it-tests/pom.xml
git commit -m "fix: remove testFailureIgnore so IT failures break the build

testFailureIgnore=true caused BUILD SUCCESS even when integration tests
had errors, making failures invisible.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Chunk 3: Verify end-to-end

### Task 3: Run integration tests and verify MCP tests pass

- [ ] **Step 1: Build the full project without tests**

Run: `mvn clean install -Dmaven.test.skip`
Expected: BUILD SUCCESS

- [ ] **Step 2: Run integration tests for the custom module**

Run: `mvn verify -Pintegration-tests` (from the root)
Expected: All MCP IT tests pass, BUILD SUCCESS (genuine this time)

If MCP tests still fail, check:
1. Tomcat logs for "MCP server started successfully with 8 tools at /mcp"
2. Access log for `POST /<context>/mcp` returning 200 (not 404)
3. If the WARN "WikiEngine could not be created" appears, check that `WikiBootstrapServletContextListener` runs before `McpServerInitializer` in web.xml

- [ ] **Step 3: Verify failures are now reported correctly**

Temporarily break a test (e.g., change an assertion), run `mvn verify -Pintegration-tests`, confirm BUILD FAILURE is reported.

- [ ] **Step 4: Run the full unit test suite**

Run: `mvn clean test -pl jspwiki-main -T 1C -DskipITs`
Expected: All 1039 unit tests still pass

- [ ] **Step 5: Final commit if any adjustments were needed**
