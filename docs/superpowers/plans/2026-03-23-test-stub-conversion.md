# Test Stub Conversion: Decouple MCP Tests from TestEngine

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert MCP tool tests from requiring a full WikiEngine (25+ manager initialization, filesystem, reference scanning) to lightweight in-memory stubs, proving the test isolation architecture works.

**Architecture:** StubPageManager and StubSystemPageRegistry (already created) provide in-memory implementations of manager interfaces. Tests that only need PageManager + SystemPageRegistry are converted first (11 EASY tests). Then StubReferenceManager is created to unlock 7 MEDIUM tests. HARD tests (15, where tool constructors take WikiEngine directly) are left for a future phase.

**Tech Stack:** JUnit 5, existing stub classes in `com.wikantik.test`, MCP tool test classes in `com.wikantik.mcp.tools`

---

## File Map

### Existing stubs (already created, no changes needed)
- `wikantik-main/src/test/java/com/wikantik/test/StubPageManager.java` — in-memory PageManager
- `wikantik-main/src/test/java/com/wikantik/test/StubSystemPageRegistry.java` — no-op SystemPageRegistry
- `wikantik-main/src/test/java/com/wikantik/test/StubPageManagerTest.java` — 9 tests for the stub itself

### New stubs to create
- `wikantik-main/src/test/java/com/wikantik/test/StubReferenceManager.java` — in-memory reference tracking

### Tests to convert (EASY — PageManager + SystemPageRegistry only)
- `wikantik-mcp/src/test/java/com/wikantik/mcp/tools/ReadPageToolTest.java`
- `wikantik-mcp/src/test/java/com/wikantik/mcp/tools/ListPagesToolTest.java`
- `wikantik-mcp/src/test/java/com/wikantik/mcp/tools/DeletePageToolTest.java`
- `wikantik-mcp/src/test/java/com/wikantik/mcp/tools/GetPageHistoryToolTest.java`
- `wikantik-mcp/src/test/java/com/wikantik/mcp/tools/LockPageToolTest.java`
- `wikantik-mcp/src/test/java/com/wikantik/mcp/tools/UnlockPageToolTest.java`
- `wikantik-mcp/src/test/java/com/wikantik/mcp/tools/ScanMarkdownLinksToolTest.java`
- `wikantik-mcp/src/test/java/com/wikantik/mcp/tools/QueryMetadataToolTest.java`
- `wikantik-mcp/src/test/java/com/wikantik/mcp/tools/ListMetadataValuesToolTest.java`
- `wikantik-mcp/src/test/java/com/wikantik/mcp/tools/GetClusterMapToolTest.java`
- `wikantik-mcp/src/test/java/com/wikantik/mcp/tools/ListPagesSystemPageFilterTest.java`

### Tests to convert (MEDIUM — need StubReferenceManager)
- `wikantik-mcp/src/test/java/com/wikantik/mcp/tools/GetBrokenLinksToolTest.java`
- `wikantik-mcp/src/test/java/com/wikantik/mcp/tools/GetOrphanedPagesToolTest.java`
- `wikantik-mcp/src/test/java/com/wikantik/mcp/tools/GetOutboundLinksToolTest.java`
- `wikantik-mcp/src/test/java/com/wikantik/mcp/tools/GetWikiStatsToolTest.java`
- `wikantik-mcp/src/test/java/com/wikantik/mcp/tools/AuditClusterToolTest.java`
- `wikantik-mcp/src/test/java/com/wikantik/mcp/tools/AuditCrossClusterToolTest.java`
- `wikantik-mcp/src/test/java/com/wikantik/mcp/tools/VerifyPagesToolTest.java`

### Not converted (HARD — tool constructors take WikiEngine, future phase)
- WritePageToolTest, PatchPageToolTest, BatchWritePagesToolTest, BatchPatchPagesToolTest, UpdateMetadataToolTest, BatchUpdateMetadataToolTest, RenamePageToolTest, DiffPageToolTest, SearchPagesToolTest, UploadAttachmentToolTest, PublishClusterToolTest, ExtendClusterToolTest, ApplyAuditFixesToolTest, FrontmatterRoundTripTest, PreviewStructuredDataToolTest

---

## Phase 1: Convert EASY Tests (PageManager + SystemPageRegistry)

### Task 1: Convert ReadPageToolTest

**Files:**
- Modify: `wikantik-mcp/src/test/java/com/wikantik/mcp/tools/ReadPageToolTest.java`

The conversion pattern for every EASY test is identical:

- [ ] **Step 1: Replace setUp() to use stubs instead of TestEngine**

Replace:
```java
import com.wikantik.TestEngine;
import com.wikantik.content.SystemPageRegistry;
import com.wikantik.pages.PageManager;

private TestEngine engine;

@BeforeEach
void setUp() {
    engine = TestEngine.build();
    tool = new ReadPageTool( engine.getManager( PageManager.class ), engine.getManager( SystemPageRegistry.class ) );
}

@AfterEach
void tearDown() {
    engine.stop();
}
```

With:
```java
import com.wikantik.test.StubPageManager;
import com.wikantik.test.StubSystemPageRegistry;

private StubPageManager pm;

@BeforeEach
void setUp() {
    pm = new StubPageManager();
    tool = new ReadPageTool( pm, new StubSystemPageRegistry() );
}
```

- [ ] **Step 2: Replace all `engine.saveText(name, text)` with `pm.savePage(name, text)`**

Search/replace throughout the file. These are functionally equivalent — both store page content accessible via `getPage()` and `getPureText()`.

- [ ] **Step 3: Remove `engine.stop()` and `@AfterEach` if it only did `engine.stop()`**

StubPageManager is garbage-collected — no shutdown needed.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -pl wikantik-mcp -Dtest="com.wikantik.mcp.tools.ReadPageToolTest" -am -Dsurefire.failIfNoSpecifiedTests=false -B`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add wikantik-mcp/src/test/java/com/wikantik/mcp/tools/ReadPageToolTest.java
git commit -m "test: convert ReadPageToolTest to StubPageManager (no TestEngine)"
```

### Task 2-11: Convert remaining EASY tests

Apply the same pattern from Task 1 to each of these test files, one at a time. After EACH conversion, run the test and commit:

- [ ] **Task 2: ListPagesToolTest** — same pattern, tool takes `(pm, spr)`
- [ ] **Task 3: DeletePageToolTest** — same pattern, tool takes `(pm, spr)`
- [ ] **Task 4: GetPageHistoryToolTest** — tool takes `(pm)` only, no SystemPageRegistry
- [ ] **Task 5: LockPageToolTest** — tool takes `(pm)` only
- [ ] **Task 6: UnlockPageToolTest** — tool takes `(pm)` only
- [ ] **Task 7: ScanMarkdownLinksToolTest** — tool takes `(pm)` only
- [ ] **Task 8: QueryMetadataToolTest** — tool takes `(pm)` only
- [ ] **Task 9: ListMetadataValuesToolTest** — tool takes `(pm)` only
- [ ] **Task 10: GetClusterMapToolTest** — tool takes `(pm, spr)`
- [ ] **Task 11: ListPagesSystemPageFilterTest** — tool takes `(pm, spr)`

After each: run the specific test, verify PASS, commit.

- [ ] **Task 12: Run full MCP test suite**

Run: `mvn test -pl wikantik-mcp -am -Dsurefire.failIfNoSpecifiedTests=false -B`
Expected: ALL 350 MCP tests pass. No regressions.

- [ ] **Task 13: Commit batch**

```bash
git commit -m "test: convert 11 MCP tool tests to StubPageManager"
```

---

## Phase 2: Create StubReferenceManager

### Task 14: Write StubReferenceManager tests

**Files:**
- Create: `wikantik-main/src/test/java/com/wikantik/test/StubReferenceManagerTest.java`

- [ ] **Step 1: Write tests for the stub contract**

The stub needs to support:
- `findReferrers(pageName)` → returns pages that link TO this page
- `findRefersTo(pageName)` → returns pages this page links TO
- `findUnreferenced()` → pages with no inbound links
- `findUncreated()` → pages referenced but never created
- `findCreated()` → all known page names
- `scanWikiLinks(page, content)` → extract links from content
- `updateReferences(page, links)` → store reference data
- `isInitialized()` → return true

```java
class StubReferenceManagerTest {
    @Test void testFindReferrers() { ... }
    @Test void testFindRefersTo() { ... }
    @Test void testFindUnreferenced() { ... }
    @Test void testFindUncreated() { ... }
    @Test void testIsInitialized() { ... }
}
```

- [ ] **Step 2: Run tests — should fail (StubReferenceManager doesn't exist)**

### Task 15: Implement StubReferenceManager

**Files:**
- Create: `wikantik-main/src/test/java/com/wikantik/test/StubReferenceManager.java`

- [ ] **Step 1: Implement the stub**

Must implement `com.wikantik.references.ReferenceManager` (the shim in wikantik-main, which extends the API interface). This also requires implementing super-interfaces: `PageFilter` (no-op), `InternalModule` (marker), `WikiEventListener` (no-op).

In-memory implementation using two ConcurrentHashMaps:
- `refersTo: Map<String, Collection<String>>` — what each page links to
- `referredBy: Map<String, Set<String>>` — what links to each page

Key methods:
- `addReferences(pageName, targets)` — convenience for tests to set up reference data
- `findReferrers(pageName)` / `findRefersTo(pageName)` — look up from maps
- `findReferredBy(pageName)` — same as findReferrers (returns Set)
- `findUnreferenced()` — pages in refersTo keys that have no inbound refs
- `findUncreated()` — pages in referredBy that aren't in refersTo keys
- `findCreated()` — all keys from refersTo
- `updateReferences(String page, Collection<String> references)` — update both maps
- `updateReferences(Page page)` — single-arg overload, delegates to string version
- `scanWikiLinks(page, content)` — delegate to `MarkdownLinkScanner.findLocalLinks()` (reuse!)
- `isInitialized()` — return true
- `initialize(Collection<Page>)` — no-op
- All PageFilter methods (`preTranslate`, `postTranslate`, `preSave`, `postSave`) — no-op
- `actionPerformed(WikiEvent)` — no-op

- [ ] **Step 2: Run StubReferenceManagerTest — should pass**
- [ ] **Step 3: Commit**

### MEDIUM Test Conversion Pattern

MEDIUM tests differ from EASY tests because `engine.saveText()` previously triggered the reference manager as a side effect — links found during save were automatically added to the reference graph. With stubs, you must populate references explicitly.

**Before (with TestEngine):**
```java
private TestEngine engine;

@BeforeEach
void setUp() {
    engine = TestEngine.build();
    tool = new GetBrokenLinksTool( engine.getManager( ReferenceManager.class ) );
}

@Test
void testFindsDeadLink() throws Exception {
    engine.saveText( "SourcePage", "[DeadLink]()" );  // ref manager auto-populates
    // tool.execute() finds DeadLink as broken because it doesn't exist
}
```

**After (with stubs):**
```java
private StubPageManager pm;
private StubReferenceManager refMgr;

@BeforeEach
void setUp() {
    pm = new StubPageManager();
    refMgr = new StubReferenceManager();
    tool = new GetBrokenLinksTool( refMgr );
}

@Test
void testFindsDeadLink() throws Exception {
    pm.savePage( "SourcePage", "[DeadLink]()" );
    refMgr.addReferences( "SourcePage", Set.of( "DeadLink" ) );  // MUST populate manually
    // tool.execute() finds DeadLink as broken because it doesn't exist in pm
}
```

The key difference: after `pm.savePage()`, you must call `refMgr.addReferences()` with the links that the page contains. Without this, the reference manager has no data and tests pass vacuously.

### Task 16-22: Convert MEDIUM tests

Apply the MEDIUM pattern above, using StubReferenceManager in addition to StubPageManager:

- [ ] **Task 16: GetBrokenLinksToolTest** — tool takes `(referenceManager)`
- [ ] **Task 17: GetOrphanedPagesToolTest** — tool takes `(referenceManager, spr)`
- [ ] **Task 18: GetOutboundLinksToolTest** — tool takes `(referenceManager)`
- [ ] **Task 19: GetWikiStatsToolTest** — tool takes `(pm, referenceManager)`
- [ ] **Task 20: AuditClusterToolTest** — tool takes `(pm, referenceManager)`. This test creates pages with frontmatter and checks cluster audit results. The stub needs `pm.savePage()` to store content that the tool reads back.
- [ ] **Task 21: AuditCrossClusterToolTest** — tool takes `(pm, referenceManager, spr)`
- [ ] **Task 22: VerifyPagesToolTest** — tool takes `(pm, referenceManager)`

After each: run the specific test, verify PASS, commit.

### Task 23: Full regression test

- [ ] **Step 1: Run full MCP test suite**

Run: `mvn test -pl wikantik-mcp -am -Dsurefire.failIfNoSpecifiedTests=false -B`
Expected: ALL MCP tests pass

- [ ] **Step 2: Run full main test suite**

Run: `mvn clean test -T 1C -DskipITs -B`
Expected: BUILD SUCCESS

- [ ] **Step 3: Final commit**

```bash
git commit -m "test: convert 7 MCP tool tests to stubs with StubReferenceManager"
```

---

## Phase 3: Measure Impact

### Task 24: Benchmark before/after

- [ ] **Step 1: Time the full MCP test suite**

```bash
time mvn test -pl wikantik-mcp -am -Dsurefire.failIfNoSpecifiedTests=false -B -q
```

Compare against the baseline (~14 seconds from before conversion). The converted tests should be faster since they skip TestEngine initialization.

- [ ] **Step 2: Document results in a comment on the commit**

---

## Out of Scope (Future Phase)

15 HARD tests where tool constructors take `WikiEngine` directly. Converting these requires refactoring the tool classes themselves to accept managers instead of the engine. This is a separate effort documented in ADR-001.

---

## Verification Checklist

After all tasks:
1. `mvn clean test -T 1C -DskipITs -B` — full unit test suite passes
2. No test references `TestEngine` that was supposed to be converted
3. StubPageManager, StubSystemPageRegistry, StubReferenceManager all have their own test suites
4. No regressions — same test count, same pass rate
