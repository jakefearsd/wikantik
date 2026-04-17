# Content Chunking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a per-page chunking pipeline that splits wiki pages into heading-aware, token-bounded passages stored in a new `kg_content_chunks` table; runs on every page save; backed by an async combined rebuild (Lucene + chunks) with a new admin UI tab exposing live status and a single "Rebuild Indexes" button.

**Architecture:** New `ContentChunker` (pure function) drives a save-time `ChunkProjector` (`PageFilter`) and a bulk-mode `ContentIndexRebuildService` (singleton daemon with `IDLE → STARTING → RUNNING → DRAINING_LUCENE → IDLE` state machine). Two new REST endpoints on `AdminContentResource` surface status and trigger; a new React tab on `AdminContentPage` polls the status endpoint. No embeddings, no retrieval consumer, no Ollama — those are follow-on specs.

**Tech Stack:** Java 21, JUnit 5, JDBC (PostgreSQL + H2 for tests), Flexmark (already in use); React 18 + existing `api/client.js` + existing admin tab pattern; Prometheus via `wikantik-observability`; migration via `bin/db/migrations/`.

**Spec:** `docs/superpowers/specs/2026-04-16-content-chunking-design.md`

---

## Preflight dependency

This plan **requires** the Lucene system-page filter prep task to have landed first. Task 1 verifies it. If verification fails, do not proceed — complete the prep task as described in the spec's "Preflight dependency" section, then restart this plan.

## File Map

### New backend files
| File | Responsibility |
|------|----------------|
| `bin/db/migrations/V008__content_chunks.sql` | Creates `kg_content_chunks` with `(page_name, chunk_index)` uniqueness |
| `wikantik-main/src/main/java/com/wikantik/knowledge/chunking/Chunk.java` | Immutable record: `pageName, chunkIndex, headingPath, text, charCount, tokenCountEstimate, contentHash` |
| `wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ContentChunker.java` | Pure function `chunk(ParsedPage) -> List<Chunk>`, heading-aware splitter |
| `wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ChunkDiff.java` | Static `compute(existing, produced)` returning added/changed/removed sets |
| `wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ContentChunkRepository.java` | JDBC: `findByPage`, `apply(diff)`, `deleteAll`, `stats()` |
| `wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ChunkProjector.java` | `PageFilter` running after `GraphProjector`; failure-isolated save-time chunking |
| `wikantik-main/src/main/java/com/wikantik/admin/ContentIndexRebuildService.java` | Singleton async orchestrator with state machine |
| `wikantik-main/src/main/java/com/wikantik/admin/IndexStatusSnapshot.java` | Immutable record matching the status JSON shape |

### New backend test files
| File | Responsibility |
|------|----------------|
| `wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ContentChunkerTest.java` | Unit tests: boundaries, budget, atomicity, hash |
| `wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ChunkDiffTest.java` | Diff classification unit tests |
| `wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ContentChunkRepositoryTest.java` | H2 round-trip + uniqueness enforcement |
| `wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ChunkProjectorTest.java` | Save-time flow including failure isolation |
| `wikantik-main/src/test/java/com/wikantik/admin/ContentIndexRebuildServiceTest.java` | State machine + phase behavior + system-page skip |
| `wikantik-rest/src/test/java/com/wikantik/rest/AdminContentResourceChunksTest.java` | Endpoint shape tests: status JSON, trigger 202/409/503 |

### Edited backend files
| File | Change |
|------|--------|
| `wikantik-main/src/main/java/com/wikantik/WikiEngine.java:575` | Register `ChunkProjector` via `filterManager.addPageFilter(...)` after `graphProjector` |
| `wikantik-rest/src/main/java/com/wikantik/rest/AdminContentResource.java` | Add `GET /admin/content/index-status` and `POST /admin/content/rebuild-indexes`; mark `handleReindex` deprecated (response header) |
| `wikantik-main/src/main/java/com/wikantik/search/LuceneSearchProvider.java` | Expose `documentCount()` + `lastUpdateInstant()` for status endpoint |
| `wikantik-main/src/main/resources/ini/wikantik.properties` | Defaults for `wikantik.chunker.*` and `wikantik.rebuild.*` |

### New frontend files
| File | Responsibility |
|------|----------------|
| `wikantik-frontend/src/components/admin/IndexStatusTab.jsx` | Stat cards, rebuild button, confirm dialog, progress, errors panel, polling |
| `wikantik-frontend/src/components/admin/IndexStatusTab.test.jsx` | Rendering, polling cadence, button state transitions |

### Edited frontend files
| File | Change |
|------|--------|
| `wikantik-frontend/src/api/client.js` | Add `api.admin.getIndexStatus()` and `api.admin.rebuildIndexes()` |
| `wikantik-frontend/src/components/admin/AdminContentPage.jsx` | Add "Index Status" tab; remove the standalone reindex button |

---

## Task 1: Preflight verification

**Files:** none (read-only).

- [ ] **Step 1: Verify the Lucene system-page filter is in place**

```bash
grep -n "SystemPageRegistry\|isSystemPage" \
  wikantik-main/src/main/java/com/wikantik/search/LuceneSearchProvider.java
```

Expected: at least one match inside the `reindexPage` method body and one inside `doFullLuceneReindex`. If there are zero matches, **stop** — the preflight task has not landed. Return to the spec's "Preflight dependency" section and complete it first.

- [ ] **Step 2: Verify the prep-task test exists**

```bash
grep -rn "reindexPage.*systemPage\|system page.*not index" \
  wikantik-main/src/test/java/com/wikantik/search/
```

Expected: at least one matching test. If none, the prep task's test coverage is missing — stop.

- [ ] **Step 3: Confirm clean working tree**

```bash
git status
```

Expected: clean (the prep-task commit already landed on main). If dirty, resolve before proceeding.

---

## Task 2: Database migration V008

**Files:**
- Create: `bin/db/migrations/V008__content_chunks.sql`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ContentChunkRepositoryTest.java` (created later; migration verified manually in this task)

- [ ] **Step 1: Write the migration**

Create `bin/db/migrations/V008__content_chunks.sql`:

```sql
-- V008: kg_content_chunks — passage-level storage of wiki page content.
-- Text only in this migration; embeddings live in a separate future table
-- joined on chunk_id and are NOT created here.

CREATE TABLE IF NOT EXISTS kg_content_chunks (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    page_name            TEXT        NOT NULL,
    chunk_index          INT         NOT NULL,
    heading_path         TEXT[]      NOT NULL DEFAULT '{}',
    text                 TEXT        NOT NULL,
    char_count           INT         NOT NULL,
    token_count_estimate INT         NOT NULL,
    content_hash         TEXT        NOT NULL,
    created              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT kg_content_chunks_page_index_uniq UNIQUE (page_name, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_kg_content_chunks_page_name
    ON kg_content_chunks (page_name);
CREATE INDEX IF NOT EXISTS idx_kg_content_chunks_content_hash
    ON kg_content_chunks (content_hash);

GRANT SELECT, INSERT, UPDATE, DELETE ON kg_content_chunks TO :app_user;
```

- [ ] **Step 2: Run the migration locally**

```bash
bin/db/migrate.sh
```

Expected: output includes `Applied V008__content_chunks.sql`.

- [ ] **Step 3: Verify idempotence (no-op on re-apply)**

```bash
bin/db/migrate.sh --status
bin/db/migrate.sh
```

Expected: status lists V008 as applied; second apply is a no-op (no new "Applied" line).

- [ ] **Step 4: Verify table exists in Postgres**

```bash
PGPASSWORD="$(grep '^db.password' tomcat/tomcat-11/lib/wikantik-custom.properties | cut -d= -f2)" \
  psql -h localhost -U jspwiki -d wikantik -c '\d kg_content_chunks'
```

Expected: columns `id, page_name, chunk_index, heading_path, text, char_count, token_count_estimate, content_hash, created, modified` with the uniqueness constraint and both indexes present.

- [ ] **Step 5: Commit**

```bash
git add bin/db/migrations/V008__content_chunks.sql
git commit -m "feat(db): add kg_content_chunks table (V008)

Introduces passage-level content storage for future retrieval work.
Text-only in this migration; embeddings live in a follow-on spec."
```

---

## Task 3: `Chunk` record and `ContentChunker` skeleton

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/chunking/Chunk.java`
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ContentChunker.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ContentChunkerTest.java`

- [ ] **Step 1: Write failing tests for empty and single-paragraph cases**

```java
package com.wikantik.knowledge.chunking;

import com.wikantik.api.frontmatter.ParsedPage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContentChunkerTest {

    private final ContentChunker chunker = new ContentChunker(
        new ContentChunker.Config(300, 512, 80));

    @Test
    void emptyBodyProducesZeroChunks() {
        ParsedPage page = new ParsedPage("Empty", java.util.Map.of(), "");
        List<Chunk> chunks = chunker.chunk(page);
        assertEquals(0, chunks.size());
    }

    @Test
    void singleShortParagraphProducesOneChunk() {
        String body = "This is a single short paragraph of prose "
                    + "intended to exercise the simplest happy path.";
        ParsedPage page = new ParsedPage("Short", java.util.Map.of(), body);
        List<Chunk> chunks = chunker.chunk(page);
        assertEquals(1, chunks.size());
        assertEquals(0, chunks.get(0).chunkIndex());
        assertEquals("Short", chunks.get(0).pageName());
        assertTrue(chunks.get(0).headingPath().isEmpty());
        assertTrue(chunks.get(0).text().contains("single short paragraph"));
        assertTrue(chunks.get(0).charCount() > 0);
        assertTrue(chunks.get(0).tokenCountEstimate() > 0);
        assertNotNull(chunks.get(0).contentHash());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl wikantik-main -Dtest=ContentChunkerTest -q
```

Expected: compile failure (classes don't exist yet).

- [ ] **Step 3: Create the `Chunk` record**

```java
package com.wikantik.knowledge.chunking;

import java.util.List;

public record Chunk(
    String pageName,
    int chunkIndex,
    List<String> headingPath,
    String text,
    int charCount,
    int tokenCountEstimate,
    String contentHash
) {
    public Chunk {
        if (pageName == null || pageName.isBlank()) {
            throw new IllegalArgumentException("pageName required");
        }
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must be >= 0");
        }
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        if (text == null) {
            throw new IllegalArgumentException("text required");
        }
    }
}
```

- [ ] **Step 4: Create `ContentChunker` with minimal single-paragraph support**

```java
package com.wikantik.knowledge.chunking;

import com.wikantik.api.frontmatter.ParsedPage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public class ContentChunker {

    public record Config(int targetTokens, int maxTokens, int minTokens) {}

    private final Config config;

    public ContentChunker(Config config) {
        this.config = config;
    }

    public List<Chunk> chunk(ParsedPage page) {
        String body = page.body() == null ? "" : page.body().strip();
        List<Chunk> out = new ArrayList<>();
        if (body.isEmpty()) {
            return out;
        }
        // Minimal: treat the entire body as one chunk. Later tasks refine.
        List<String> headingPath = List.of();
        out.add(buildChunk(page.pageName(), 0, headingPath, body));
        return out;
    }

    Chunk buildChunk(String pageName, int index, List<String> headingPath, String text) {
        int charCount = text.length();
        int tokenCountEstimate = (int) Math.ceil(charCount / 4.0);
        String hash = sha256Hex16(String.join("\u0000", headingPath) + "\n" + text);
        return new Chunk(pageName, index, headingPath, text,
                         charCount, tokenCountEstimate, hash);
    }

    private static String sha256Hex16(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
mvn test -pl wikantik-main -Dtest=ContentChunkerTest -q
```

Expected: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/chunking/Chunk.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ContentChunker.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ContentChunkerTest.java
git commit -m "feat(chunking): Chunk record and minimal ContentChunker

Initial skeleton passing empty-body and single-paragraph cases. Heading
walking, token budget, and atomic blocks land in subsequent tasks."
```

---

## Task 4: Heading-aware splitting

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ContentChunker.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ContentChunkerTest.java`

- [ ] **Step 1: Add failing tests for heading behavior**

Append to `ContentChunkerTest`:

```java
@Test
void threeShortSectionsProduceThreeChunksWithHeadingPaths() {
    String body = """
        # Top Title

        ## First Section

        Alpha paragraph with enough words to count as a real chunk of prose.

        ## Second Section

        Bravo paragraph, also substantive enough to be emitted on its own.

        ## Third Section

        Charlie paragraph — again, long enough to warrant emission.
        """;
    ParsedPage page = new ParsedPage("Sections", java.util.Map.of(), body);
    List<Chunk> chunks = chunker.chunk(page);
    assertEquals(3, chunks.size());
    assertEquals(List.of("Top Title", "First Section"), chunks.get(0).headingPath());
    assertEquals(List.of("Top Title", "Second Section"), chunks.get(1).headingPath());
    assertEquals(List.of("Top Title", "Third Section"), chunks.get(2).headingPath());
}

@Test
void headingStackPopsOnShallowerHeading() {
    String body = """
        # One

        ## Two

        ### Three

        Leaf text sufficient to be emitted.

        ## TwoPrime

        Sibling text also long enough to warrant emission.
        """;
    ParsedPage page = new ParsedPage("Pops", java.util.Map.of(), body);
    List<Chunk> chunks = chunker.chunk(page);
    assertEquals(List.of("One", "Two", "Three"), chunks.get(0).headingPath());
    assertEquals(List.of("One", "TwoPrime"), chunks.get(1).headingPath());
}
```

- [ ] **Step 2: Run tests to verify failure**

```bash
mvn test -pl wikantik-main -Dtest=ContentChunkerTest -q
```

Expected: the two new tests fail (existing ones pass).

- [ ] **Step 3: Implement heading-aware splitting using Flexmark**

Replace the body of `ContentChunker.chunk(...)` with:

```java
public List<Chunk> chunk(ParsedPage page) {
    String body = page.body() == null ? "" : page.body().strip();
    List<Chunk> out = new ArrayList<>();
    if (body.isEmpty()) {
        return out;
    }

    com.vladsch.flexmark.parser.Parser parser =
        com.vladsch.flexmark.parser.Parser.builder().build();
    com.vladsch.flexmark.util.ast.Node root = parser.parse(body);

    java.util.Deque<String> headingStack = new java.util.ArrayDeque<>();
    java.util.List<com.vladsch.flexmark.util.ast.Node> pendingBlocks = new ArrayList<>();
    int[] chunkIndex = {0};

    for (com.vladsch.flexmark.util.ast.Node child : root.getChildren()) {
        if (child instanceof com.vladsch.flexmark.ast.Heading heading) {
            flushChunk(page.pageName(), chunkIndex, new ArrayList<>(headingStack),
                       pendingBlocks, out);
            adjustHeadingStack(headingStack, heading.getLevel(), heading.getText().toString());
        } else {
            pendingBlocks.add(child);
        }
    }
    flushChunk(page.pageName(), chunkIndex, new ArrayList<>(headingStack),
               pendingBlocks, out);
    return out;
}

private void adjustHeadingStack(java.util.Deque<String> stack, int level, String title) {
    while (stack.size() >= level) {
        stack.removeLast();
    }
    while (stack.size() < level - 1) {
        stack.addLast(""); // gaps are rare but possible (H1 → H3)
    }
    stack.addLast(title.trim());
}

private void flushChunk(String pageName, int[] idx, List<String> headingPath,
                        List<com.vladsch.flexmark.util.ast.Node> blocks,
                        List<Chunk> out) {
    if (blocks.isEmpty()) return;
    StringBuilder sb = new StringBuilder();
    for (var block : blocks) {
        sb.append(block.getChars().toString()).append("\n\n");
    }
    String text = sb.toString().strip();
    if (!text.isEmpty()) {
        out.add(buildChunk(pageName, idx[0]++, List.copyOf(headingPath), text));
    }
    blocks.clear();
}
```

- [ ] **Step 4: Run tests to verify pass**

```bash
mvn test -pl wikantik-main -Dtest=ContentChunkerTest -q
```

Expected: all 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ContentChunker.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ContentChunkerTest.java
git commit -m "feat(chunking): heading-aware splitting with heading_path"
```

---

## Task 5: Token budget, atomic blocks, and oversize handling

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ContentChunker.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ContentChunkerTest.java`

- [ ] **Step 1: Add failing tests for budget and atomicity**

Append to `ContentChunkerTest`:

```java
@Test
void oversizedParagraphIsSplitOnSentenceBoundaries() {
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 80; i++) {
        big.append("Sentence number ").append(i).append(" filler text. ");
    }
    ParsedPage page = new ParsedPage("Big", java.util.Map.of(), big.toString());
    List<Chunk> chunks = chunker.chunk(page);
    assertTrue(chunks.size() > 1, "expected multiple chunks");
    for (Chunk c : chunks) {
        assertTrue(c.tokenCountEstimate() <= 512,
            "chunk " + c.chunkIndex() + " exceeds max: " + c.tokenCountEstimate());
    }
}

@Test
void fencedCodeBlockIsAtomicEvenIfOversized() {
    StringBuilder body = new StringBuilder("# Title\n\n```\n");
    for (int i = 0; i < 200; i++) body.append("public int x").append(i).append(" = ").append(i).append(";\n");
    body.append("```\n");
    ParsedPage page = new ParsedPage("Code", java.util.Map.of(), body.toString());
    List<Chunk> chunks = chunker.chunk(page);
    assertEquals(1, chunks.size());
    assertTrue(chunks.get(0).text().contains("public int x0 = 0;"));
    assertTrue(chunks.get(0).text().contains("public int x199 = 199;"));
}

@Test
void shortChunkBelowMinMergesForwardAcrossHeadingBoundary() {
    String body = """
        ## Stub

        Tiny.

        ## Real

        This paragraph has plenty of real content to ensure we cross the min
        threshold on merge and emit a single well-formed chunk.
        """;
    ParsedPage page = new ParsedPage("Merge", java.util.Map.of(), body);
    List<Chunk> chunks = chunker.chunk(page);
    assertEquals(1, chunks.size(), "tiny first section should merge forward");
    assertEquals(List.of("Stub"), chunks.get(0).headingPath(),
                 "merged chunk carries first section's heading path");
    assertTrue(chunks.get(0).text().contains("Tiny."));
    assertTrue(chunks.get(0).text().contains("plenty of real content"));
}

@Test
void pluginMarkupIsPreservedVerbatim() {
    String body = "[{Plugin}] then some prose of reasonable length to be emitted.";
    ParsedPage page = new ParsedPage("Plugin", java.util.Map.of(), body);
    List<Chunk> chunks = chunker.chunk(page);
    assertEquals(1, chunks.size());
    assertTrue(chunks.get(0).text().contains("[{Plugin}]"));
}
```

- [ ] **Step 2: Run tests to verify failure**

```bash
mvn test -pl wikantik-main -Dtest=ContentChunkerTest -q
```

Expected: the 4 new tests fail.

- [ ] **Step 3: Extend `flushChunk` with budget and atomicity logic**

Replace `flushChunk` in `ContentChunker.java` with the budget-aware version:

```java
private void flushChunk(String pageName, int[] idx, List<String> headingPath,
                        List<com.vladsch.flexmark.util.ast.Node> blocks,
                        List<Chunk> out) {
    if (blocks.isEmpty()) return;
    StringBuilder current = new StringBuilder();
    for (var block : blocks) {
        String blockText = block.getChars().toString();
        int blockTokens = estimateTokens(blockText);

        boolean isAtomic = block instanceof com.vladsch.flexmark.ast.FencedCodeBlock
                         || block instanceof com.vladsch.flexmark.ext.tables.TableBlock;

        if (isAtomic) {
            emitIfNonEmpty(pageName, idx, headingPath, current, out);
            out.add(buildChunk(pageName, idx[0]++, List.copyOf(headingPath),
                               blockText.strip()));
            continue;
        }

        if (blockTokens > config.maxTokens()) {
            emitIfNonEmpty(pageName, idx, headingPath, current, out);
            for (String sentenceChunk : splitOnSentences(blockText, config.maxTokens())) {
                out.add(buildChunk(pageName, idx[0]++, List.copyOf(headingPath),
                                   sentenceChunk.strip()));
            }
            continue;
        }

        int currentTokens = estimateTokens(current.toString());
        if (currentTokens + blockTokens > config.maxTokens()) {
            emitIfNonEmpty(pageName, idx, headingPath, current, out);
        }
        current.append(blockText).append("\n\n");
    }
    emitIfNonEmpty(pageName, idx, headingPath, current, out);
    blocks.clear();
}

private void emitIfNonEmpty(String pageName, int[] idx, List<String> headingPath,
                            StringBuilder buf, List<Chunk> out) {
    String text = buf.toString().strip();
    buf.setLength(0);
    if (text.isEmpty()) return;
    if (!out.isEmpty() && estimateTokens(text) < config.minTokens()) {
        // merge-forward: append to pending text and do NOT emit yet
        buf.append(text).append("\n\n");
        return;
    }
    out.add(buildChunk(pageName, idx[0]++, List.copyOf(headingPath), text));
}

private int estimateTokens(String s) {
    return (int) Math.ceil(s.length() / 4.0);
}

private List<String> splitOnSentences(String text, int maxTokens) {
    List<String> chunks = new ArrayList<>();
    String[] sentences = text.split("(?<=[.!?])\\s+(?=[A-Z]|\\n)");
    StringBuilder cur = new StringBuilder();
    for (String s : sentences) {
        if (estimateTokens(cur.toString()) + estimateTokens(s) > maxTokens && cur.length() > 0) {
            chunks.add(cur.toString());
            cur.setLength(0);
        }
        if (estimateTokens(s) > maxTokens) {
            // single giant sentence — fall back to whitespace split
            for (String token : s.split("\\s+")) {
                if (estimateTokens(cur.toString()) + estimateTokens(token) > maxTokens
                    && cur.length() > 0) {
                    chunks.add(cur.toString());
                    cur.setLength(0);
                }
                cur.append(token).append(' ');
            }
        } else {
            cur.append(s).append(' ');
        }
    }
    if (cur.length() > 0) chunks.add(cur.toString());
    return chunks;
}
```

Note: the merge-forward semantic in `emitIfNonEmpty` — when the accumulated text is below `minTokens`, we push it back into the buffer so the next block's content concatenates with it. The first section's heading_path is preserved because `headingPath` is captured at flush time, not at final-emit time.

Look at the current imports at the top of the file and add:

```java
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ext.tables.TableBlock;
```

- [ ] **Step 4: Run tests to verify pass**

```bash
mvn test -pl wikantik-main -Dtest=ContentChunkerTest -q
```

Expected: all 8 tests pass. If the `TableBlock` class resolution fails, check that the Flexmark tables extension is on the classpath (`wikantik-main/pom.xml`); if not, drop `TableBlock` from the atomic check — the chunker will still split around tables correctly via the token budget path.

- [ ] **Step 5: Add content_hash determinism test**

```java
@Test
void contentHashIsDeterministicAndSensitiveToHeadingPath() {
    String body = "## A\n\nSame body text repeated across two sections.\n\n"
                + "## B\n\nSame body text repeated across two sections.\n";
    ParsedPage page = new ParsedPage("Hash", java.util.Map.of(), body);
    List<Chunk> chunks = chunker.chunk(page);
    assertEquals(2, chunks.size());
    assertNotEquals(chunks.get(0).contentHash(), chunks.get(1).contentHash(),
        "identical body text under different headings must hash differently");

    List<Chunk> again = chunker.chunk(page);
    assertEquals(chunks.get(0).contentHash(), again.get(0).contentHash());
    assertEquals(chunks.get(1).contentHash(), again.get(1).contentHash());
}
```

- [ ] **Step 6: Run tests**

```bash
mvn test -pl wikantik-main -Dtest=ContentChunkerTest -q
```

Expected: 9 tests pass.

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ContentChunker.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ContentChunkerTest.java
git commit -m "feat(chunking): token budget, atomic blocks, merge-forward

Atomic fenced-code and table handling; sentence-level splitting for
oversized paragraphs; min-token merge-forward preserving first section's
heading_path; hash sensitivity to heading context."
```

---

## Task 6: `ChunkDiff` classification

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ChunkDiff.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ChunkDiffTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.wikantik.knowledge.chunking;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChunkDiffTest {

    private static ChunkDiff.Stored stored(int idx, String hash) {
        return new ChunkDiff.Stored(UUID.randomUUID(), idx, hash);
    }

    private static Chunk produced(int idx, String hash) {
        return new Chunk("P", idx, List.of(), "text-" + idx, 6, 2, hash);
    }

    @Test
    void unchangedChunksAreClassifiedAsNoop() {
        var existing = List.of(stored(0, "aaa"), stored(1, "bbb"));
        var produced = List.of(produced(0, "aaa"), produced(1, "bbb"));
        var diff = ChunkDiff.compute(existing, produced);
        assertEquals(0, diff.inserts().size());
        assertEquals(0, diff.updates().size());
        assertEquals(0, diff.deletes().size());
    }

    @Test
    void hashMismatchProducesUpdateKeepingId() {
        var existing = List.of(stored(0, "aaa"));
        var old = existing.get(0);
        var produced = List.of(produced(0, "zzz"));
        var diff = ChunkDiff.compute(existing, produced);
        assertEquals(1, diff.updates().size());
        assertEquals(old.id(), diff.updates().get(0).existingId());
    }

    @Test
    void newIndexBecomesInsert() {
        var existing = List.of(stored(0, "aaa"));
        var produced = List.of(produced(0, "aaa"), produced(1, "bbb"));
        var diff = ChunkDiff.compute(existing, produced);
        assertEquals(1, diff.inserts().size());
        assertEquals(1, diff.inserts().get(0).chunkIndex());
    }

    @Test
    void missingIndexBecomesDelete() {
        var existing = List.of(stored(0, "aaa"), stored(1, "bbb"));
        var produced = List.of(produced(0, "aaa"));
        var diff = ChunkDiff.compute(existing, produced);
        assertEquals(1, diff.deletes().size());
        assertEquals(existing.get(1).id(), diff.deletes().get(0));
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

```bash
mvn test -pl wikantik-main -Dtest=ChunkDiffTest -q
```

Expected: compile failure.

- [ ] **Step 3: Implement `ChunkDiff`**

```java
package com.wikantik.knowledge.chunking;

import java.util.*;

public final class ChunkDiff {

    public record Stored(UUID id, int chunkIndex, String contentHash) {}
    public record Update(UUID existingId, Chunk replacement) {}
    public record Diff(List<Chunk> inserts, List<Update> updates, List<UUID> deletes) {}

    private ChunkDiff() {}

    public static Diff compute(List<Stored> existing, List<Chunk> produced) {
        Map<Integer, Stored> byIndex = new HashMap<>();
        for (Stored s : existing) byIndex.put(s.chunkIndex(), s);

        List<Chunk> inserts = new ArrayList<>();
        List<Update> updates = new ArrayList<>();
        Set<Integer> producedIndexes = new HashSet<>();

        for (Chunk p : produced) {
            producedIndexes.add(p.chunkIndex());
            Stored prior = byIndex.get(p.chunkIndex());
            if (prior == null) {
                inserts.add(p);
            } else if (!prior.contentHash().equals(p.contentHash())) {
                updates.add(new Update(prior.id(), p));
            }
        }

        List<UUID> deletes = new ArrayList<>();
        for (Stored s : existing) {
            if (!producedIndexes.contains(s.chunkIndex())) deletes.add(s.id());
        }
        return new Diff(inserts, updates, deletes);
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

```bash
mvn test -pl wikantik-main -Dtest=ChunkDiffTest -q
```

Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ChunkDiff.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ChunkDiffTest.java
git commit -m "feat(chunking): ChunkDiff classifies inserts, updates, deletes"
```

---

## Task 7: `ContentChunkRepository` (JDBC)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ContentChunkRepository.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ContentChunkRepositoryTest.java`

- [ ] **Step 1: Write failing H2-backed tests**

Pattern-match on `wikantik-main/src/test/java/com/wikantik/knowledge/` existing JDBC test helpers (for example the H2 test setup used by `JdbcKnowledgeRepositoryTest` — read that file to confirm the helper class and DataSource plumbing before copy-pasting below). Outline:

```java
package com.wikantik.knowledge.chunking;

import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ContentChunkRepositoryTest {

    private DataSource ds;
    private ContentChunkRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        ds = com.wikantik.knowledge.test.H2TestDataSources.newWithMigrations();
        repo = new ContentChunkRepository(ds);
    }

    @Test
    void insertAndFindRoundTrip() {
        var c = new Chunk("P", 0, List.of("H1"), "body", 4, 1, "hash0");
        var diff = new ChunkDiff.Diff(List.of(c), List.of(), List.of());
        repo.apply("P", diff);
        var stored = repo.findByPage("P");
        assertEquals(1, stored.size());
        assertEquals(0, stored.get(0).chunkIndex());
        assertEquals("hash0", stored.get(0).contentHash());
    }

    @Test
    void updateKeepsIdAndUpdatesHash() {
        var c0 = new Chunk("P", 0, List.of(), "v1", 2, 1, "h1");
        repo.apply("P", new ChunkDiff.Diff(List.of(c0), List.of(), List.of()));
        UUID id = repo.findByPage("P").get(0).id();

        var c0v2 = new Chunk("P", 0, List.of(), "v2", 2, 1, "h2");
        repo.apply("P", new ChunkDiff.Diff(List.of(),
            List.of(new ChunkDiff.Update(id, c0v2)), List.of()));

        var after = repo.findByPage("P");
        assertEquals(1, after.size());
        assertEquals(id, after.get(0).id(), "id must be preserved on update");
        assertEquals("h2", after.get(0).contentHash());
    }

    @Test
    void deleteRemovesByIdOnly() {
        var c0 = new Chunk("P", 0, List.of(), "v1", 2, 1, "h1");
        var c1 = new Chunk("P", 1, List.of(), "v2", 2, 1, "h2");
        repo.apply("P", new ChunkDiff.Diff(List.of(c0, c1), List.of(), List.of()));
        UUID id0 = repo.findByPage("P").get(0).id();
        repo.apply("P", new ChunkDiff.Diff(List.of(), List.of(), List.of(id0)));
        var after = repo.findByPage("P");
        assertEquals(1, after.size());
        assertEquals(1, after.get(0).chunkIndex());
    }

    @Test
    void uniqueIndexConstraintEnforced() {
        var c0 = new Chunk("P", 0, List.of(), "a", 1, 1, "ha");
        var c0dup = new Chunk("P", 0, List.of(), "b", 1, 1, "hb");
        repo.apply("P", new ChunkDiff.Diff(List.of(c0), List.of(), List.of()));
        assertThrows(Exception.class, () ->
            repo.apply("P", new ChunkDiff.Diff(List.of(c0dup), List.of(), List.of())));
    }

    @Test
    void deleteAllEmptiesTable() {
        var c = new Chunk("P", 0, List.of(), "x", 1, 1, "hx");
        repo.apply("P", new ChunkDiff.Diff(List.of(c), List.of(), List.of()));
        repo.deleteAll();
        assertTrue(repo.findByPage("P").isEmpty());
    }
}
```

If a shared H2-with-migrations helper does not exist, create one at `wikantik-main/src/test/java/com/wikantik/knowledge/test/H2TestDataSources.java` following the pattern in `JdbcKnowledgeRepositoryTest`. It should:
1. Create an H2 DataSource with PostgreSQL compatibility mode (`;MODE=PostgreSQL`).
2. Run every `bin/db/migrations/V*.sql` file in order, substituting `:app_user` with `sa`.
3. Return the DataSource.

- [ ] **Step 2: Run tests to verify failure**

```bash
mvn test -pl wikantik-main -Dtest=ContentChunkRepositoryTest -q
```

Expected: compile failure.

- [ ] **Step 3: Implement `ContentChunkRepository`**

```java
package com.wikantik.knowledge.chunking;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

public class ContentChunkRepository {

    public record AggregateStats(
        int pagesWithChunks, int pagesMissingChunks,
        int totalChunks, int avgTokens, int minTokens, int maxTokens) {}

    private final DataSource ds;

    public ContentChunkRepository(DataSource ds) {
        this.ds = ds;
    }

    public List<ChunkDiff.Stored> findByPage(String pageName) {
        String sql = "SELECT id, chunk_index, content_hash FROM kg_content_chunks "
                   + "WHERE page_name = ? ORDER BY chunk_index";
        try (var c = ds.getConnection(); var ps = c.prepareStatement(sql)) {
            ps.setString(1, pageName);
            try (var rs = ps.executeQuery()) {
                List<ChunkDiff.Stored> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new ChunkDiff.Stored(
                        UUID.fromString(rs.getString(1)),
                        rs.getInt(2),
                        rs.getString(3)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("findByPage failed for " + pageName, e);
        }
    }

    public void apply(String pageName, ChunkDiff.Diff diff) {
        try (var c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (UUID id : diff.deletes()) deleteById(c, id);
                for (var u : diff.updates()) update(c, u);
                for (Chunk ins : diff.inserts()) insert(c, ins);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw new RuntimeException("apply failed for " + pageName, e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("apply failed for " + pageName, e);
        }
    }

    public void deleteAll() {
        try (var c = ds.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("DELETE FROM kg_content_chunks");
        } catch (SQLException e) {
            throw new RuntimeException("deleteAll failed", e);
        }
    }

    public AggregateStats stats() {
        String sql = """
            SELECT
                COUNT(DISTINCT page_name)                      AS pages_with_chunks,
                COUNT(*)                                        AS total_chunks,
                COALESCE(AVG(token_count_estimate), 0)::INT    AS avg_tokens,
                COALESCE(MIN(token_count_estimate), 0)         AS min_tokens,
                COALESCE(MAX(token_count_estimate), 0)         AS max_tokens
            FROM kg_content_chunks
            """;
        try (var c = ds.getConnection(); var s = c.createStatement();
             var rs = s.executeQuery(sql)) {
            rs.next();
            return new AggregateStats(rs.getInt(1), 0, rs.getInt(2),
                                       rs.getInt(3), rs.getInt(4), rs.getInt(5));
        } catch (SQLException e) {
            throw new RuntimeException("stats failed", e);
        }
    }

    private void insert(Connection c, Chunk ch) throws SQLException {
        String sql = """
            INSERT INTO kg_content_chunks
              (page_name, chunk_index, heading_path, text,
               char_count, token_count_estimate, content_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        try (var ps = c.prepareStatement(sql)) {
            ps.setString(1, ch.pageName());
            ps.setInt(2, ch.chunkIndex());
            ps.setArray(3, c.createArrayOf("text", ch.headingPath().toArray()));
            ps.setString(4, ch.text());
            ps.setInt(5, ch.charCount());
            ps.setInt(6, ch.tokenCountEstimate());
            ps.setString(7, ch.contentHash());
            ps.executeUpdate();
        }
    }

    private void update(Connection c, ChunkDiff.Update u) throws SQLException {
        String sql = """
            UPDATE kg_content_chunks
               SET heading_path = ?, text = ?, char_count = ?,
                   token_count_estimate = ?, content_hash = ?, modified = NOW()
             WHERE id = ?
            """;
        try (var ps = c.prepareStatement(sql)) {
            var r = u.replacement();
            ps.setArray(1, c.createArrayOf("text", r.headingPath().toArray()));
            ps.setString(2, r.text());
            ps.setInt(3, r.charCount());
            ps.setInt(4, r.tokenCountEstimate());
            ps.setString(5, r.contentHash());
            ps.setObject(6, u.existingId());
            ps.executeUpdate();
        }
    }

    private void deleteById(Connection c, UUID id) throws SQLException {
        try (var ps = c.prepareStatement("DELETE FROM kg_content_chunks WHERE id = ?")) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

```bash
mvn test -pl wikantik-main -Dtest=ContentChunkRepositoryTest -q
```

Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ContentChunkRepository.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ContentChunkRepositoryTest.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/test/H2TestDataSources.java
git commit -m "feat(chunking): ContentChunkRepository with diff apply and stats"
```

---

## Task 8: `ChunkProjector` (save-time `PageFilter`)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ChunkProjector.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ChunkProjectorTest.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java:575`
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties`

- [ ] **Step 1: Write failing tests**

```java
package com.wikantik.knowledge.chunking;

import com.wikantik.api.core.Context;
import com.wikantik.api.frontmatter.ParsedPage;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChunkProjectorTest {

    private DataSource ds;
    private ContentChunkRepository repo;
    private ContentChunker chunker;
    private ChunkProjector projector;

    @BeforeEach
    void setUp() throws Exception {
        ds = com.wikantik.knowledge.test.H2TestDataSources.newWithMigrations();
        repo = new ContentChunkRepository(ds);
        chunker = new ContentChunker(new ContentChunker.Config(300, 512, 80));
        projector = new ChunkProjector(chunker, repo, () -> true); // enabled
    }

    @Test
    void saveNewPageWritesChunks() {
        projector.projectPage("P", java.util.Map.of(),
            "Body with enough prose content to produce a single chunk of reasonable size.");
        assertEquals(1, repo.findByPage("P").size());
    }

    @Test
    void resaveUnchangedPageIsNoop() {
        String body = "Stable body content used twice in a row to test no-op.";
        projector.projectPage("P", java.util.Map.of(), body);
        String hashBefore = repo.findByPage("P").get(0).contentHash();
        projector.projectPage("P", java.util.Map.of(), body);
        assertEquals(hashBefore, repo.findByPage("P").get(0).contentHash());
    }

    @Test
    void chunkerExceptionIsCaughtAndLogged() {
        ContentChunker throwing = mock(ContentChunker.class);
        when(throwing.chunk(any())).thenThrow(new RuntimeException("boom"));
        ChunkProjector p2 = new ChunkProjector(throwing, repo, () -> true);

        assertDoesNotThrow(() -> p2.projectPage("P", java.util.Map.of(), "body"));
        assertTrue(repo.findByPage("P").isEmpty());
    }

    @Test
    void disabledFlagSkipsChunking() {
        ChunkProjector off = new ChunkProjector(chunker, repo, () -> false);
        off.projectPage("P", java.util.Map.of(), "Body content sufficient to emit a chunk.");
        assertTrue(repo.findByPage("P").isEmpty());
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

```bash
mvn test -pl wikantik-main -Dtest=ChunkProjectorTest -q
```

Expected: compile failure.

- [ ] **Step 3: Implement `ChunkProjector`**

```java
package com.wikantik.knowledge.chunking;

import com.wikantik.api.core.Context;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

public class ChunkProjector implements PageFilter {

    private static final Logger LOG = LogManager.getLogger(ChunkProjector.class);

    private final ContentChunker chunker;
    private final ContentChunkRepository repository;
    private final BooleanSupplier enabled;

    public ChunkProjector(ContentChunker chunker,
                          ContentChunkRepository repository,
                          BooleanSupplier enabled) {
        this.chunker = chunker;
        this.repository = repository;
        this.enabled = enabled;
    }

    /** Pattern-match this against GraphProjector.postSave — same hook semantics. */
    @Override
    public String postSave(Context context, String content) {
        if (!enabled.getAsBoolean()) return content;
        String pageName = context.getPage().getName();
        ParsedPage parsed = FrontmatterParser.parse(content);
        projectPage(pageName, parsed.frontmatter(), parsed.body());
        return content;
    }

    /** Public for testability. */
    public void projectPage(String pageName, Map<String, Object> frontmatter, String body) {
        if (!enabled.getAsBoolean()) return;
        try {
            ParsedPage pp = new ParsedPage(pageName, frontmatter, body);
            List<Chunk> produced = chunker.chunk(pp);
            List<ChunkDiff.Stored> existing = repository.findByPage(pageName);
            ChunkDiff.Diff diff = ChunkDiff.compute(existing, produced);
            repository.apply(pageName, diff);
            LOG.info("Chunked {} into {} chunks (+{} ~{} -{})",
                     pageName, produced.size(),
                     diff.inserts().size(), diff.updates().size(), diff.deletes().size());
        } catch (Exception e) {
            LOG.warn("Chunking failed for page {}: {}", pageName, e.getMessage(), e);
        }
    }
}
```

Note: the exact `PageFilter` method to override (`postSave` vs. `preSave` vs. `postTranslate`) must match what `GraphProjector` overrides — read `GraphProjector.java` and mirror it. Do not invent a new lifecycle hook.

- [ ] **Step 4: Add configuration defaults**

Append to `wikantik-main/src/main/resources/ini/wikantik.properties`:

```
# Content chunking (save-time)
wikantik.chunker.target_tokens = 300
wikantik.chunker.max_tokens    = 512
wikantik.chunker.min_tokens    = 80
wikantik.chunker.enabled       = true

# Async combined rebuild (Lucene + chunks)
wikantik.rebuild.enabled              = true
wikantik.rebuild.lucene_drain_poll_ms = 2000
```

- [ ] **Step 5: Wire `ChunkProjector` in `WikiEngine`**

In `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` around line 575, alongside the existing `graphProjector` registration, add the chunk projector. Priority must be **after** `graphProjector` in the filter chain — mirror the pattern and pick a priority value greater than graphProjector's `-1003`:

```java
filterManager.addPageFilter( svcs.graphProjector(), -1003 );
filterManager.addPageFilter( svcs.chunkProjector(), -1005 );  // NEW — runs after graphProjector
filterManager.addPageFilter( svcs.frontmatterDefaultsFilter(), -1004 );
filterManager.addPageFilter( svcs.hubSyncFilter(), -999 );
```

Confirm the priority ordering semantics by reading `DefaultFilterManager.addPageFilter` before committing — if higher number means earlier, flip accordingly. The intent is "ChunkProjector runs *after* GraphProjector."

Add a `chunkProjector()` factory method to whatever services class `svcs` resolves to (grep for `graphProjector()` to find it — the service factory pattern is already established).

- [ ] **Step 6: Run the full projector test suite**

```bash
mvn test -pl wikantik-main -Dtest=ChunkProjectorTest -q
```

Expected: 4 tests pass.

- [ ] **Step 7: Compile-check the whole module**

```bash
mvn compile -pl wikantik-main -q
```

Expected: no errors.

- [ ] **Step 8: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ChunkProjector.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ChunkProjectorTest.java \
        wikantik-main/src/main/java/com/wikantik/WikiEngine.java \
        wikantik-main/src/main/resources/ini/wikantik.properties
# Also add the edited services/factory file that now returns chunkProjector().
git commit -m "feat(chunking): save-time ChunkProjector PageFilter

Runs after GraphProjector, failure-isolated, gated by
wikantik.chunker.enabled kill-switch."
```

---

## Task 9: `ContentIndexRebuildService` state machine scaffold

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/admin/IndexStatusSnapshot.java`
- Create: `wikantik-main/src/main/java/com/wikantik/admin/ContentIndexRebuildService.java`
- Test: `wikantik-main/src/test/java/com/wikantik/admin/ContentIndexRebuildServiceTest.java`

- [ ] **Step 1: Write failing state-machine tests**

```java
package com.wikantik.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContentIndexRebuildServiceStateTest {

    @Test
    void initialStateIsIdle() {
        ContentIndexRebuildService svc = newServiceWithNoPages();
        assertEquals("IDLE", svc.snapshot().rebuild().state());
    }

    @Test
    void triggerWhileIdleReturnsStartingSnapshot() {
        ContentIndexRebuildService svc = newServiceWithNoPages();
        IndexStatusSnapshot snap = svc.triggerRebuild();
        assertEquals("STARTING", snap.rebuild().state());
        assertNotNull(snap.rebuild().startedAt());
    }

    @Test
    void triggerWhileRunningThrowsConflict() throws Exception {
        ContentIndexRebuildService svc = newServiceWithBlockingPage();
        svc.triggerRebuild();
        ContentIndexRebuildService.ConflictException ex =
            assertThrows(ContentIndexRebuildService.ConflictException.class,
                         svc::triggerRebuild);
        assertTrue(ex.current().rebuild().state().equals("STARTING")
                || ex.current().rebuild().state().equals("RUNNING"));
    }

    @Test
    void rebuildDisabledFlagRejectsTrigger() {
        ContentIndexRebuildService svc = newServiceWithRebuildDisabled();
        assertThrows(ContentIndexRebuildService.DisabledException.class,
                     svc::triggerRebuild);
    }

    // Test builders — see file below for the stub PageManager / Lucene / Repo
    // fakes. Keep dependencies hand-rolled rather than mocked, to exercise the
    // real state transitions.
    private ContentIndexRebuildService newServiceWithNoPages() { /* ... */ }
    private ContentIndexRebuildService newServiceWithBlockingPage() { /* ... */ }
    private ContentIndexRebuildService newServiceWithRebuildDisabled() { /* ... */ }
}
```

Replace the stubbed builder methods with real factories. Use in-memory fakes for:
- `PageManager` (return a fixed list of page names).
- `LuceneReindexQueue` (a minimal interface over `LuceneSearchProvider.reindexPage` + `getReindexQueueDepth`).
- `ContentChunkRepository` (H2, same helper as Task 7).
- `SystemPageRegistry` (return false for all pages in the simplest test).

Define `LuceneReindexQueue` as a small interface for testability:

```java
public interface LuceneReindexQueue {
    void reindexPage(com.wikantik.api.pages.Page page);
    int queueDepth();
    int documentCount();
    java.time.Instant lastUpdateInstant();
    void clearIndex();
}
```

Wire a production adapter around `LuceneSearchProvider` in Task 11.

- [ ] **Step 2: Run tests to verify failure**

```bash
mvn test -pl wikantik-main -Dtest=ContentIndexRebuildServiceStateTest -q
```

Expected: compile failure.

- [ ] **Step 3: Define `IndexStatusSnapshot`**

```java
package com.wikantik.admin;

import java.time.Instant;
import java.util.List;

public record IndexStatusSnapshot(
    Pages pages, Lucene lucene, Chunks chunks, Rebuild rebuild) {

    public record Pages(int total, int system, int indexable) {}

    public record Lucene(int documentsIndexed, int queueDepth, Instant lastUpdate) {}

    public record Chunks(int pagesWithChunks, int pagesMissingChunks,
                         int totalChunks, int avgTokens, int minTokens, int maxTokens) {}

    public record Rebuild(
        String state, Instant startedAt,
        int pagesTotal, int pagesIterated, int pagesChunked,
        int systemPagesSkipped, int luceneQueued, int chunksWritten,
        List<RebuildError> errors) {}

    public record RebuildError(String page, String error, Instant at) {}
}
```

- [ ] **Step 4: Implement scaffold of `ContentIndexRebuildService`**

```java
package com.wikantik.admin;

import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.knowledge.chunking.ContentChunkRepository;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public class ContentIndexRebuildService {

    public enum State { IDLE, STARTING, RUNNING, DRAINING_LUCENE }

    public static class ConflictException extends RuntimeException {
        private final IndexStatusSnapshot current;
        public ConflictException(IndexStatusSnapshot s) { this.current = s; }
        public IndexStatusSnapshot current() { return current; }
    }

    public static class DisabledException extends RuntimeException {}

    private final PageManager pages;
    private final SystemPageRegistry systemPages;
    private final LuceneReindexQueue lucene;
    private final ContentChunkRepository chunkRepo;
    private final com.wikantik.knowledge.chunking.ContentChunker chunker;
    private final BooleanSupplier rebuildEnabled;
    private final long luceneDrainPollMs;

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private volatile Instant startedAt;
    private volatile int pagesTotal;
    private volatile int pagesIterated;
    private volatile int pagesChunked;
    private volatile int systemPagesSkipped;
    private volatile int luceneQueued;
    private volatile int chunksWritten;
    private final List<IndexStatusSnapshot.RebuildError> errors = new CopyOnWriteArrayList<>();

    public ContentIndexRebuildService(PageManager pages, SystemPageRegistry systemPages,
                                      LuceneReindexQueue lucene,
                                      ContentChunkRepository chunkRepo,
                                      com.wikantik.knowledge.chunking.ContentChunker chunker,
                                      BooleanSupplier rebuildEnabled,
                                      long luceneDrainPollMs) {
        this.pages = pages;
        this.systemPages = systemPages;
        this.lucene = lucene;
        this.chunkRepo = chunkRepo;
        this.chunker = chunker;
        this.rebuildEnabled = rebuildEnabled;
        this.luceneDrainPollMs = luceneDrainPollMs;
    }

    public synchronized IndexStatusSnapshot triggerRebuild() {
        if (!rebuildEnabled.getAsBoolean()) throw new DisabledException();
        if (state.get() != State.IDLE) throw new ConflictException(snapshot());
        state.set(State.STARTING);
        startedAt = Instant.now();
        resetCounters();
        Thread t = new Thread(this::runRebuild, "wikantik-rebuild");
        t.setDaemon(true);
        t.start();
        return snapshot();
    }

    public IndexStatusSnapshot snapshot() {
        var aggStats = chunkRepo.stats();
        int totalPages = pages.getAllPages().size();
        int systemPageCount = countSystemPages();
        int indexable = totalPages - systemPageCount;
        return new IndexStatusSnapshot(
            new IndexStatusSnapshot.Pages(totalPages, systemPageCount, indexable),
            new IndexStatusSnapshot.Lucene(
                lucene.documentCount(), lucene.queueDepth(), lucene.lastUpdateInstant()),
            new IndexStatusSnapshot.Chunks(
                aggStats.pagesWithChunks(),
                Math.max(0, indexable - aggStats.pagesWithChunks()),
                aggStats.totalChunks(), aggStats.avgTokens(),
                aggStats.minTokens(), aggStats.maxTokens()),
            new IndexStatusSnapshot.Rebuild(
                state.get().name(), startedAt,
                pagesTotal, pagesIterated, pagesChunked,
                systemPagesSkipped, luceneQueued, chunksWritten,
                List.copyOf(errors)));
    }

    private void runRebuild() {
        // Implemented in Task 10.
    }

    private void resetCounters() {
        pagesTotal = pagesIterated = pagesChunked =
            systemPagesSkipped = luceneQueued = chunksWritten = 0;
        errors.clear();
    }

    private int countSystemPages() {
        int n = 0;
        for (var p : pages.getAllPages()) {
            if (systemPages.isSystemPage(p.getName())) n++;
        }
        return n;
    }
}
```

- [ ] **Step 5: Run the state-machine tests to verify they pass**

```bash
mvn test -pl wikantik-main -Dtest=ContentIndexRebuildServiceStateTest -q
```

Expected: 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/admin/IndexStatusSnapshot.java \
        wikantik-main/src/main/java/com/wikantik/admin/ContentIndexRebuildService.java \
        wikantik-main/src/main/java/com/wikantik/admin/LuceneReindexQueue.java \
        wikantik-main/src/test/java/com/wikantik/admin/ContentIndexRebuildServiceStateTest.java
git commit -m "feat(admin): ContentIndexRebuildService state machine scaffold

IDLE / STARTING / RUNNING / DRAINING_LUCENE with trigger concurrency
guard and kill-switch support. Run loop implemented in the next commit."
```

---

## Task 10: Rebuild run loop (RUNNING phase) with system-page handling

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/admin/ContentIndexRebuildService.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/admin/ContentIndexRebuildServiceTest.java`

- [ ] **Step 1: Write failing tests covering the superset guarantee**

Create `ContentIndexRebuildServiceRunTest.java`:

```java
package com.wikantik.admin;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class ContentIndexRebuildServiceRunTest {

    @Test
    void runsChunkerForNonSystemPagesAndEnqueuesLuceneForAll() {
        // 3 regular + 2 system pages
        var svc = RebuildTestFactory.build(5, 2);
        svc.triggerRebuild();
        await().atMost(Duration.ofSeconds(5)).until(() ->
            svc.snapshot().rebuild().state().equals("IDLE"));

        var snap = svc.snapshot();
        assertEquals(5, snap.rebuild().pagesIterated());
        assertEquals(3, snap.rebuild().pagesChunked());
        assertEquals(2, snap.rebuild().systemPagesSkipped());
        assertEquals(5, snap.rebuild().luceneQueued());
        assertEquals(3, snap.rebuild().chunksWritten());
    }

    @Test
    void chunkerExceptionOnOnePageDoesNotStopRun() {
        var svc = RebuildTestFactory.buildWithThrowingChunker("Page2");
        svc.triggerRebuild();
        await().atMost(Duration.ofSeconds(5)).until(() ->
            svc.snapshot().rebuild().state().equals("IDLE"));

        var snap = svc.snapshot();
        assertEquals(1, snap.rebuild().errors().size());
        assertEquals("Page2", snap.rebuild().errors().get(0).page());
        assertTrue(snap.rebuild().pagesIterated() >= 3,
                   "all pages visited even after one error");
    }

    @Test
    void initialPhaseClearsLuceneAndChunksTable() {
        var svc = RebuildTestFactory.buildWithPreloadedChunks();
        svc.triggerRebuild();
        await().atMost(Duration.ofSeconds(5)).until(() ->
            svc.snapshot().rebuild().state().equals("IDLE"));

        // Preloaded chunks for pages that no longer exist should be gone.
        assertTrue(RebuildTestFactory.luceneClearedOnce());
        assertTrue(RebuildTestFactory.chunksClearedOnce());
    }
}
```

Create `RebuildTestFactory` in the same test package: constructs in-memory pages (`InMemoryPageManager`), a fake `SystemPageRegistry` (system by name prefix `"Sys"`), an in-memory `LuceneReindexQueue` fake with counters, and a real `ContentChunkRepository` over H2. Expose static builder methods that the tests call.

- [ ] **Step 2: Run tests to verify failure**

```bash
mvn test -pl wikantik-main -Dtest=ContentIndexRebuildServiceRunTest -q
```

Expected: they fail — `runRebuild` is empty.

- [ ] **Step 3: Implement the run loop**

Replace the empty `runRebuild()` body in `ContentIndexRebuildService`:

```java
private void runRebuild() {
    try {
        // STARTING: clear both indexes.
        lucene.clearIndex();
        chunkRepo.deleteAll();

        var all = pages.getAllPages();
        pagesTotal = all.size();
        state.set(State.RUNNING);

        for (var page : all) {
            pagesIterated++;
            boolean isSystem = systemPages.isSystemPage(page.getName());
            if (!isSystem) {
                try {
                    var body = page.getContent();
                    var pp = com.wikantik.api.frontmatter.FrontmatterParser.parse(body);
                    var produced = chunker.chunk(new com.wikantik.api.frontmatter.ParsedPage(
                        page.getName(), pp.frontmatter(), pp.body()));
                    var existing = chunkRepo.findByPage(page.getName());
                    var diff = ChunkDiff.compute(existing, produced);
                    chunkRepo.apply(page.getName(), diff);
                    pagesChunked++;
                    chunksWritten += produced.size();
                } catch (Exception e) {
                    recordError(page.getName(), e);
                }
            } else {
                systemPagesSkipped++;
            }
            try {
                lucene.reindexPage(page);
                luceneQueued++;
            } catch (Exception e) {
                recordError(page.getName(), e);
            }
        }

        state.set(State.DRAINING_LUCENE);
        drainLucene();
    } catch (Exception fatal) {
        LOG.error("Rebuild fatal error", fatal);
        recordError("<rebuild>", fatal);
    } finally {
        state.set(State.IDLE);
    }
}

private void recordError(String page, Exception e) {
    errors.add(new IndexStatusSnapshot.RebuildError(
        page, e.getClass().getSimpleName() + ": " + e.getMessage(), Instant.now()));
}

private void drainLucene() {
    int zeroStreak = 0;
    while (zeroStreak < 2) {
        try { Thread.sleep(luceneDrainPollMs); }
        catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }
        if (lucene.queueDepth() == 0) zeroStreak++;
        else zeroStreak = 0;
    }
}
```

The `chunker` field was added to the constructor in Task 9. The test builders in `RebuildTestFactory` need to pass a `ContentChunker` instance with the standard `new ContentChunker.Config(300, 512, 80)`.

Add a logger at the top of the class:

```java
private static final Logger LOG = LogManager.getLogger(ContentIndexRebuildService.class);
```

- [ ] **Step 4: Run tests to verify pass**

```bash
mvn test -pl wikantik-main -Dtest=ContentIndexRebuildServiceRunTest -q
```

Expected: 3 tests pass.

- [ ] **Step 5: Add Awaitility to the test classpath if missing**

Check `wikantik-main/pom.xml` for `org.awaitility:awaitility`. If absent, add:

```xml
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <version>4.2.0</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 6: Run both test classes together**

```bash
mvn test -pl wikantik-main \
    -Dtest=ContentIndexRebuildServiceStateTest,ContentIndexRebuildServiceRunTest -q
```

Expected: 7 tests pass.

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/admin/ContentIndexRebuildService.java \
        wikantik-main/src/test/java/com/wikantik/admin/ContentIndexRebuildServiceRunTest.java \
        wikantik-main/src/test/java/com/wikantik/admin/RebuildTestFactory.java \
        wikantik-main/pom.xml
git commit -m "feat(admin): rebuild run loop with system-page handling

RUNNING phase iterates all pages, chunker gated on SystemPageRegistry,
Lucene enqueue unconditional. DRAINING_LUCENE waits for queue depth to
hit zero on two consecutive polls."
```

---

## Task 11: `LuceneReindexQueue` production adapter and wiring

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/admin/LuceneSearchProviderAdapter.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/search/LuceneSearchProvider.java` (add `documentCount()` and `lastUpdateInstant()` if absent)
- Modify: service factory / `WikiEngine.java` to instantiate `ContentIndexRebuildService` as a singleton

- [ ] **Step 1: Inspect `LuceneSearchProvider` for already-exposed metrics**

```bash
grep -n "documentCount\|numDocs\|lastUpdate" \
    wikantik-main/src/main/java/com/wikantik/search/LuceneSearchProvider.java
```

Expected output informs whether those methods already exist. If they do, use them; otherwise add minimal public methods backed by the existing Lucene `IndexReader.numDocs()` and the last-update-timestamp tracked alongside `LuceneUpdater`.

- [ ] **Step 2: Write the adapter**

```java
package com.wikantik.admin;

import com.wikantik.api.pages.Page;
import com.wikantik.search.LuceneSearchProvider;

import java.time.Instant;

public class LuceneSearchProviderAdapter implements LuceneReindexQueue {

    private final LuceneSearchProvider provider;

    public LuceneSearchProviderAdapter(LuceneSearchProvider provider) {
        this.provider = provider;
    }

    @Override
    public void reindexPage(Page page) { provider.reindexPage(page); }

    @Override
    public int queueDepth() { return provider.getReindexQueueDepth(); }

    @Override
    public int documentCount() { return provider.documentCount(); }

    @Override
    public Instant lastUpdateInstant() { return provider.lastUpdateInstant(); }

    @Override
    public void clearIndex() { provider.clearIndex(); }
}
```

- [ ] **Step 3: Add missing methods on `LuceneSearchProvider` if needed**

For each of `documentCount`, `lastUpdateInstant`, `clearIndex` that doesn't already exist, add it. `clearIndex` should close the `IndexReader`, delete files under the Lucene directory, and reopen — mirror the startup path already in `doFullLuceneReindex`.

- [ ] **Step 4: Instantiate the service as a singleton in the service factory**

Find the factory / wiring class used for `graphProjector()` and `chunkProjector()` and add:

```java
public ContentIndexRebuildService contentIndexRebuildService() {
    if (cachedRebuildService == null) {
        cachedRebuildService = new ContentIndexRebuildService(
            engine.getManager(PageManager.class),
            engine.getManager(SystemPageRegistry.class),
            new LuceneSearchProviderAdapter(
                (LuceneSearchProvider) engine.getManager(SearchManager.class)),
            contentChunkRepository(),
            contentChunker(),  // same instance used by ChunkProjector
            () -> props.getBoolean("wikantik.rebuild.enabled", true),
            props.getLong("wikantik.rebuild.lucene_drain_poll_ms", 2000));
    }
    return cachedRebuildService;
}
```

- [ ] **Step 5: Compile-check**

```bash
mvn compile -pl wikantik-main -q
```

Expected: no errors.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/admin/LuceneSearchProviderAdapter.java \
        wikantik-main/src/main/java/com/wikantik/search/LuceneSearchProvider.java \
        wikantik-main/src/main/java/com/wikantik/WikiEngine.java
# Plus the edited services factory file.
git commit -m "feat(admin): wire ContentIndexRebuildService into engine

Adapter over LuceneSearchProvider exposes queue depth, document count,
last update, and clearIndex for the rebuild orchestrator."
```

---

## Task 12: REST endpoints on `AdminContentResource`

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminContentResource.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/AdminContentResourceChunksTest.java`

- [ ] **Step 1: Write failing endpoint shape tests**

Pattern-match on an existing test in `wikantik-rest/src/test/java/com/wikantik/rest/` (e.g. `AdminKnowledgeResourceTest`) for mock HTTP plumbing. Then:

```java
package com.wikantik.rest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdminContentResourceChunksTest {

    @Test
    void getIndexStatusReturnsExpectedShape() throws Exception {
        var r = AdminContentResourceTestHarness.get("/admin/content/index-status");
        assertEquals(200, r.status());
        assertTrue(r.json().containsKey("pages"));
        assertTrue(r.json().containsKey("lucene"));
        assertTrue(r.json().containsKey("chunks"));
        assertTrue(r.json().containsKey("rebuild"));
        var rebuild = (java.util.Map<String, Object>) r.json().get("rebuild");
        for (String key : java.util.List.of(
                "state", "pages_total", "pages_iterated", "pages_chunked",
                "system_pages_skipped", "lucene_queued", "chunks_written", "errors")) {
            assertTrue(rebuild.containsKey(key), "missing key: " + key);
        }
    }

    @Test
    void triggerRebuildReturns202ThenConflictWhileInFlight() throws Exception {
        var first = AdminContentResourceTestHarness.post("/admin/content/rebuild-indexes");
        assertEquals(202, first.status());

        var second = AdminContentResourceTestHarness.post("/admin/content/rebuild-indexes");
        assertEquals(409, second.status());
    }

    @Test
    void triggerRebuild503WhenDisabled() throws Exception {
        AdminContentResourceTestHarness.setRebuildEnabled(false);
        var r = AdminContentResourceTestHarness.post("/admin/content/rebuild-indexes");
        assertEquals(503, r.status());
    }
}
```

Build `AdminContentResourceTestHarness` — a thin wrapper that sets up the resource with an in-memory `ContentIndexRebuildService` (reuse `RebuildTestFactory` from Task 10) and exposes `get()` / `post()` helpers. Do not spin up a full Tomcat — use the same in-process pattern existing tests use.

- [ ] **Step 2: Run tests to verify failure**

```bash
mvn test -pl wikantik-rest -Dtest=AdminContentResourceChunksTest -q
```

Expected: compile failure or 404 responses.

- [ ] **Step 3: Add the two endpoints and deprecate the old one**

In `AdminContentResource.java`, alongside the existing `handleReindex` dispatch (around line 114), add dispatch clauses and handlers:

```java
} else if ( "index-status".equals( action ) && "GET".equalsIgnoreCase(request.getMethod()) ) {
    handleIndexStatus( response );
} else if ( "rebuild-indexes".equals( action ) && "POST".equalsIgnoreCase(request.getMethod()) ) {
    handleRebuildIndexes( response );
}
```

Handlers:

```java
private void handleIndexStatus(HttpServletResponse response) throws IOException {
    ContentIndexRebuildService svc = getEngine().getManager(
        /* however the factory is accessed */).contentIndexRebuildService();
    IndexStatusSnapshot snap = svc.snapshot();
    sendJson(response, 200, toJson(snap));
}

private void handleRebuildIndexes(HttpServletResponse response) throws IOException {
    ContentIndexRebuildService svc = ...;
    try {
        IndexStatusSnapshot snap = svc.triggerRebuild();
        sendJson(response, 202, toJson(snap));
    } catch (ContentIndexRebuildService.ConflictException e) {
        sendJson(response, 409, toJson(e.current()));
    } catch (ContentIndexRebuildService.DisabledException e) {
        sendJson(response, 503, java.util.Map.of(
            "error", "rebuild disabled",
            "flag", "wikantik.rebuild.enabled"));
    }
}
```

JSON serialization: reuse whatever pattern `AdminKnowledgeResource` uses for its Jackson/Gson conversion. Map record field names to snake_case per the spec's JSON shape.

Also, mark the existing `handleReindex` response with a deprecation header:

```java
response.setHeader("Deprecation", "true");
response.setHeader("Link",
    "</admin/content/rebuild-indexes>; rel=\"successor-version\"");
```

- [ ] **Step 4: Run tests to verify pass**

```bash
mvn test -pl wikantik-rest -Dtest=AdminContentResourceChunksTest -q
```

Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminContentResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AdminContentResourceChunksTest.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AdminContentResourceTestHarness.java
git commit -m "feat(rest): GET /admin/content/index-status + POST rebuild-indexes

Deprecates POST /admin/content/reindex via Deprecation/Link headers,
keeps it callable for legacy scripts."
```

---

## Task 13: Prometheus metrics

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ChunkProjector.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/admin/ContentIndexRebuildService.java`

- [ ] **Step 1: Identify the existing metrics registration pattern**

```bash
grep -rn "Counter.build\|Histogram.build\|Gauge.build" \
    wikantik-observability/src/main/java/ \
    wikantik-main/src/main/java/ | head -20
```

Use the same library and initialization pattern (likely `io.prometheus.client`). Store the `Counter` / `Histogram` / `Gauge` as `private static final` fields.

- [ ] **Step 2: Add chunker metrics**

In `ChunkProjector`:

```java
private static final io.prometheus.client.Counter CHUNKS_PRODUCED =
    io.prometheus.client.Counter.build()
        .name("wikantik_chunker_chunks_produced")
        .help("Chunks produced per page save")
        .register();

private static final io.prometheus.client.Histogram DURATION =
    io.prometheus.client.Histogram.build()
        .name("wikantik_chunker_duration_seconds")
        .help("Save-time chunker duration")
        .register();

private static final io.prometheus.client.Counter FAILURES =
    io.prometheus.client.Counter.build()
        .name("wikantik_chunker_failures_total")
        .help("Save-time chunker failures").labelNames("reason").register();

private static final io.prometheus.client.Histogram CHUNK_SIZE_TOKENS =
    io.prometheus.client.Histogram.build()
        .name("wikantik_chunker_chunk_size_tokens")
        .help("Token-count-estimate distribution for emitted chunks").register();
```

Wrap the body of `projectPage` with `try (var t = DURATION.startTimer())`, record `CHUNKS_PRODUCED.inc(produced.size())`, record `CHUNK_SIZE_TOKENS.observe(c.tokenCountEstimate())` per chunk, and in the catch block `FAILURES.labels(e.getClass().getSimpleName()).inc()`.

- [ ] **Step 3: Add rebuild metrics**

In `ContentIndexRebuildService`, mirror the pattern: `wikantik_rebuild_state` (gauge, set from state transitions), `wikantik_rebuild_runs_total` (counter with `outcome` label), `wikantik_rebuild_duration_seconds` (histogram, observe on state → IDLE), `wikantik_rebuild_pages_iterated`, `wikantik_rebuild_pages_chunked`, `wikantik_rebuild_system_pages_skipped` (all gauges, set in the loop).

- [ ] **Step 4: Compile-check**

```bash
mvn compile -pl wikantik-main -q
```

Expected: no errors.

- [ ] **Step 5: Spot-check metric registration via admin endpoint**

After deploying locally:

```bash
curl -u testbot:$PASSWORD http://localhost:8080/observability/metrics \
    | grep -E 'wikantik_chunker|wikantik_rebuild'
```

Expected: all listed metric families appear (even at zero values).

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ChunkProjector.java \
        wikantik-main/src/main/java/com/wikantik/admin/ContentIndexRebuildService.java
git commit -m "feat(chunking): Prometheus metrics for chunker and rebuild"
```

---

## Task 14: Frontend API client methods

**Files:**
- Modify: `wikantik-frontend/src/api/client.js`

- [ ] **Step 1: Locate the existing admin namespace**

```bash
grep -n "admin\.\|api.admin" wikantik-frontend/src/api/client.js
```

Find the `api.admin` object and the pattern used by existing admin methods (e.g. `api.admin.reindex`, `api.admin.backfillFrontmatter`).

- [ ] **Step 2: Add two methods pattern-matching the existing style**

```js
api.admin.getIndexStatus = async () => {
    const r = await fetch('/admin/content/index-status', {
        headers: { 'Accept': 'application/json' }
    });
    if (!r.ok) throw new ApiError(r.status, await r.text());
    return r.json();
};

api.admin.rebuildIndexes = async () => {
    const r = await fetch('/admin/content/rebuild-indexes', {
        method: 'POST',
        headers: { 'Accept': 'application/json' }
    });
    if (r.status === 409) throw new ApiConflict(await r.json());
    if (r.status === 503) throw new ApiError(503, 'Rebuild disabled');
    if (!r.ok) throw new ApiError(r.status, await r.text());
    return r.json();
};
```

Match the actual shape — `ApiError` and `ApiConflict` may already exist; if not, reuse whatever error conventions `api.admin.backfillFrontmatter` uses.

- [ ] **Step 3: Commit**

```bash
git add wikantik-frontend/src/api/client.js
git commit -m "feat(frontend): api.admin.getIndexStatus and rebuildIndexes"
```

---

## Task 15: `IndexStatusTab` React component

**Files:**
- Create: `wikantik-frontend/src/components/admin/IndexStatusTab.jsx`
- Test: `wikantik-frontend/src/components/admin/IndexStatusTab.test.jsx`

- [ ] **Step 1: Read the existing async-progress component for pattern reference**

```bash
cat wikantik-frontend/src/components/admin/ContentEmbeddingsTab.jsx | head -120
```

Note the polling idiom (`setInterval` + cleanup in `useEffect`) and how a confirm dialog is structured.

- [ ] **Step 2: Write failing component tests**

```jsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import IndexStatusTab from './IndexStatusTab';
import * as client from '../../api/client';

const idleStatus = {
    pages: { total: 100, system: 5, indexable: 95 },
    lucene: { documents_indexed: 95, queue_depth: 0, last_update: null },
    chunks: { pages_with_chunks: 95, pages_missing_chunks: 0,
              total_chunks: 800, avg_tokens: 287, min_tokens: 42, max_tokens: 512 },
    rebuild: { state: "IDLE", started_at: null,
               pages_total: 0, pages_iterated: 0, pages_chunked: 0,
               system_pages_skipped: 0, lucene_queued: 0, chunks_written: 0,
               errors: [] }
};

describe('IndexStatusTab', () => {
    it('renders stat cards from the status response', async () => {
        vi.spyOn(client.api.admin, 'getIndexStatus').mockResolvedValue(idleStatus);
        render(<IndexStatusTab />);
        await waitFor(() => expect(screen.getByText('95')).toBeInTheDocument());
        expect(screen.getByText(/avg 287 tokens/)).toBeInTheDocument();
    });

    it('rebuild button opens confirm dialog and calls API on confirm', async () => {
        vi.spyOn(client.api.admin, 'getIndexStatus').mockResolvedValue(idleStatus);
        const rebuild = vi.spyOn(client.api.admin, 'rebuildIndexes')
            .mockResolvedValue({ ...idleStatus,
                rebuild: { ...idleStatus.rebuild, state: 'STARTING' } });
        render(<IndexStatusTab />);

        await waitFor(() => screen.getByText(/Rebuild Indexes/i));
        fireEvent.click(screen.getByText(/Rebuild Indexes/i));
        expect(screen.getByText(/clear the Lucene index/i)).toBeInTheDocument();
        fireEvent.click(screen.getByText(/Continue/i));
        await waitFor(() => expect(rebuild).toHaveBeenCalled());
    });

    it('disables rebuild button while not IDLE', async () => {
        vi.spyOn(client.api.admin, 'getIndexStatus').mockResolvedValue({
            ...idleStatus,
            rebuild: { ...idleStatus.rebuild, state: 'RUNNING', pages_total: 95, pages_iterated: 10 }
        });
        render(<IndexStatusTab />);
        await waitFor(() => expect(screen.getByRole('button', { name: /Rebuild/i }))
            .toBeDisabled());
    });
});
```

- [ ] **Step 3: Run tests to verify failure**

```bash
cd wikantik-frontend && npx vitest run src/components/admin/IndexStatusTab.test.jsx
```

Expected: failing — component doesn't exist.

- [ ] **Step 4: Implement the component**

```jsx
import { useEffect, useState, useRef } from 'react';
import { api } from '../../api/client';

const FAST_POLL_MS = 2000;
const SLOW_POLL_MS = 10000;

export default function IndexStatusTab() {
    const [status, setStatus] = useState(null);
    const [error, setError] = useState(null);
    const [confirming, setConfirming] = useState(false);
    const intervalRef = useRef(null);

    const fetchStatus = async () => {
        try {
            const s = await api.admin.getIndexStatus();
            setStatus(s);
            setError(null);
        } catch (e) {
            setError(e.message || 'Failed to fetch status');
        }
    };

    useEffect(() => {
        fetchStatus();
        const schedule = (ms) => {
            if (intervalRef.current) clearInterval(intervalRef.current);
            intervalRef.current = setInterval(fetchStatus, ms);
        };
        schedule(FAST_POLL_MS);
        return () => intervalRef.current && clearInterval(intervalRef.current);
    }, []);

    useEffect(() => {
        if (!status) return;
        const active = status.rebuild.state !== 'IDLE';
        if (intervalRef.current) clearInterval(intervalRef.current);
        intervalRef.current = setInterval(fetchStatus,
            active ? FAST_POLL_MS : SLOW_POLL_MS);
    }, [status?.rebuild.state]);

    const doRebuild = async () => {
        setConfirming(false);
        try {
            const next = await api.admin.rebuildIndexes();
            setStatus(next);
        } catch (e) {
            setError(e.message || 'Rebuild failed');
        }
    };

    if (!status) return <div>Loading…</div>;

    const isIdle = status.rebuild.state === 'IDLE';
    return (
        <div className="index-status-tab">
            <section className="stat-cards">
                <StatCard label="Pages Indexable" value={status.pages.indexable}
                          subtitle={`of ${status.pages.total} total`} />
                <StatCard label="Lucene Documents" value={status.lucene.documents_indexed}
                          subtitle={`queue: ${status.lucene.queue_depth}`} />
                <StatCard label="Total Chunks" value={status.chunks.total_chunks}
                          subtitle={`avg ${status.chunks.avg_tokens} tokens/chunk`} />
                <StatCard label="Lucene Queue Depth" value={status.lucene.queue_depth} />
            </section>

            <section className="rebuild">
                <button disabled={!isIdle} onClick={() => setConfirming(true)}>
                    Rebuild Indexes
                </button>
                {!isIdle && (
                    <RebuildProgress rebuild={status.rebuild}
                                     luceneQueueDepth={status.lucene.queue_depth} />
                )}
            </section>

            {status.rebuild.errors.length > 0 && (
                <details className="errors-panel">
                    <summary>{status.rebuild.errors.length} errors</summary>
                    <ul>
                        {status.rebuild.errors.slice(-20).map((e, i) => (
                            <li key={i}>
                                <strong>{e.page}</strong>: {e.error} <em>({e.at})</em>
                            </li>
                        ))}
                    </ul>
                </details>
            )}

            {confirming && (
                <ConfirmDialog
                    message={`This will clear the Lucene index and chunk table and rebuild from all ${status.pages.indexable} pages. Search will be degraded until it completes. Continue?`}
                    onConfirm={doRebuild}
                    onCancel={() => setConfirming(false)} />
            )}

            {error && <div className="error-banner">{error}</div>}
        </div>
    );
}

function StatCard({ label, value, subtitle }) {
    return (
        <div className="stat-card">
            <div className="label">{label}</div>
            <div className="value">{value}</div>
            {subtitle && <div className="subtitle">{subtitle}</div>}
        </div>
    );
}

function RebuildProgress({ rebuild, luceneQueueDepth }) {
    const pct = rebuild.pages_total === 0
        ? 0 : Math.round(100 * rebuild.pages_iterated / rebuild.pages_total);
    return (
        <div className="rebuild-progress">
            <div>State: {rebuild.state}</div>
            <progress value={rebuild.pages_iterated} max={rebuild.pages_total} />
            <div className="subtitle">
                {rebuild.pages_iterated}/{rebuild.pages_total} iterated —
                chunked {rebuild.pages_chunked}, system skipped {rebuild.system_pages_skipped}
            </div>
            {rebuild.state === 'DRAINING_LUCENE' && (
                <>
                    <div>Lucene queue draining…</div>
                    <progress value={Math.max(0, rebuild.lucene_queued - luceneQueueDepth)}
                              max={rebuild.lucene_queued} />
                </>
            )}
        </div>
    );
}

function ConfirmDialog({ message, onConfirm, onCancel }) {
    return (
        <div className="confirm-dialog">
            <p>{message}</p>
            <button onClick={onConfirm}>Continue</button>
            <button onClick={onCancel}>Cancel</button>
        </div>
    );
}
```

- [ ] **Step 5: Run tests to verify pass**

```bash
cd wikantik-frontend && npx vitest run src/components/admin/IndexStatusTab.test.jsx
```

Expected: 3 tests pass.

- [ ] **Step 6: Commit**

```bash
git add wikantik-frontend/src/components/admin/IndexStatusTab.jsx \
        wikantik-frontend/src/components/admin/IndexStatusTab.test.jsx
git commit -m "feat(frontend): IndexStatusTab with polling, stat cards, rebuild"
```

---

## Task 16: Wire `IndexStatusTab` into `AdminContentPage`

**Files:**
- Modify: `wikantik-frontend/src/components/admin/AdminContentPage.jsx`

- [ ] **Step 1: Read current `AdminContentPage.jsx`**

```bash
cat wikantik-frontend/src/components/admin/AdminContentPage.jsx
```

Note how tabs are structured (look for an existing tab-switching state or component).

- [ ] **Step 2: Add the new tab and remove the legacy reindex button**

Add an import:

```js
import IndexStatusTab from './IndexStatusTab';
```

Add a tab entry where the other tabs are defined (example shape depends on the existing pattern — either a `tabs` array or inline JSX):

```jsx
{ key: 'index-status', label: 'Index Status', render: () => <IndexStatusTab /> }
```

Remove the existing standalone "Reindex" button and its handler (the old `api.admin.reindex()` call and surrounding UI — typically a single `<button>` + success state). Leave `api.admin.reindex` in the client module (it still targets the deprecated endpoint for scripts).

- [ ] **Step 3: Deploy locally and smoke-test**

```bash
mvn clean install -Dmaven.test.skip -T 1C
tomcat/tomcat-11/bin/shutdown.sh 2>/dev/null || true
rm -rf tomcat/tomcat-11/webapps/ROOT
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
sleep 10
```

Open `http://localhost:8080/admin/content`, log in as `testbot`, click the **Index Status** tab. Verify:
- Four stat cards render with real values.
- "Rebuild Indexes" button is enabled.
- Old standalone "Reindex" button is gone.

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/components/admin/AdminContentPage.jsx
git commit -m "feat(frontend): wire Index Status tab; remove legacy reindex button"
```

---

## Task 17: End-to-end integration smoke test

**Files:** none new; uses the running local deployment.

- [ ] **Step 1: Trigger a rebuild via curl**

```bash
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')
curl -u "${login}:${password}" -X POST \
     http://localhost:8080/admin/content/rebuild-indexes
```

Expected: 202 JSON snapshot with `rebuild.state = "STARTING"` or `"RUNNING"`.

- [ ] **Step 2: Poll status until IDLE**

```bash
for i in $(seq 1 60); do
    curl -s -u "${login}:${password}" http://localhost:8080/admin/content/index-status \
        | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["rebuild"]["state"], d["rebuild"]["pages_iterated"], "/", d["rebuild"]["pages_total"])'
    sleep 5
done
```

Expected: transitions to `IDLE` within a few minutes. Final `pages_iterated == pages_total`.

- [ ] **Step 3: Verify row counts**

```bash
PGPASSWORD=... psql -h localhost -U jspwiki -d wikantik -c \
    'SELECT COUNT(DISTINCT page_name) AS pages_with_chunks, COUNT(*) AS total_chunks FROM kg_content_chunks;'
```

Expected: `pages_with_chunks ≈ indexable` from the status response.

- [ ] **Step 4: Verify deprecated endpoint still works**

```bash
curl -i -u "${login}:${password}" -X POST http://localhost:8080/admin/content/reindex \
    | head -20
```

Expected: 200 response with `Deprecation: true` header. Response body is the legacy `{started: true, pagesQueued: N}`.

- [ ] **Step 5: Verify metrics endpoint**

```bash
curl -s -u "${login}:${password}" http://localhost:8080/observability/metrics \
    | grep -E 'wikantik_chunker|wikantik_rebuild' | head
```

Expected: all the metric families declared in Task 13 appear with non-zero values.

- [ ] **Step 6: Edit and save a page, confirm save-time chunker runs**

Edit any page via the UI (trivial markdown change). Then:

```bash
PGPASSWORD=... psql -h localhost -U jspwiki -d wikantik -c \
    "SELECT chunk_index, modified FROM kg_content_chunks WHERE page_name = '<PageYouEdited>' ORDER BY chunk_index;"
```

Expected: chunks for that page exist, `modified` timestamps updated on changed chunks only.

- [ ] **Step 7: Full build with all tests**

```bash
mvn clean install -T 1C -DskipITs -fae
```

Expected: green.

- [ ] **Step 8: Commit (no-op if all prior commits succeeded)**

No new code changes in this task — integration-verified only. If smoke tests revealed defects, fix and commit in bite-sized follow-ups.

---

## Done

At this point:
- `kg_content_chunks` is populated for all non-system pages via the background rebuild.
- Every page save updates chunks incrementally via `ChunkProjector`.
- Admins can inspect status and trigger combined Lucene + chunks rebuild via the new tab.
- Old `/reindex` endpoint is deprecated but callable.
- Metrics flow to Prometheus.
- The system is ready for the follow-on embeddings spec, which will add `kg_chunk_embeddings` and a background embedding worker without touching `kg_content_chunks`.
