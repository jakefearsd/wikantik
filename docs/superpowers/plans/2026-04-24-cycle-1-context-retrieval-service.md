# Cycle 1: ContextRetrievalService Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract a shared `ContextRetrievalService` as the single home for agent-facing retrieval logic. All three existing retrieval callers (`SearchResource` REST endpoint, `SearchWikiTool` OpenAPI tool) route through it. No MCP wire-level changes in this cycle — cycle 2 adds the new tools on top.

**Architecture:** Interface + records in `wikantik-api/knowledge`; `DefaultContextRetrievalService` in `wikantik-knowledge` composing existing singletons (`SearchManager`, `HybridSearchService`, `ChunkVectorIndex`, `PageAggregator`, `HybridFuser`, `GraphRerankStep`, `ContentChunkRepository`, `NodeMentionSimilarity`, `PageManager`, `FrontmatterMetadataCache`). Service is registered as an engine manager and called from the REST and OpenAPI callers.

**Tech Stack:** Java 21, JUnit 5, Testcontainers (PostgreSQL), existing Wikantik manager registry, Gson for JSON shaping on callers.

**Reference spec:** `docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md`

---

## File Structure

**Creating (in `wikantik-api/src/main/java/com/wikantik/api/knowledge/`):**
- `ContextRetrievalService.java` — interface, 4 methods
- `ContextQuery.java` — record (query input with filter)
- `PageListFilter.java` — record (browse filter, also embedded in ContextQuery)
- `RetrievalResult.java` — record (retrieve output wrapper)
- `RetrievedPage.java` — record (page with chunks + related)
- `RetrievedChunk.java` — record (heading path + text + score + matched terms)
- `RelatedPage.java` — record (name + reason)
- `PageList.java` — record (list output wrapper)
- `MetadataValue.java` — record (value + count)

**Creating (in `wikantik-knowledge/src/main/java/com/wikantik/knowledge/`):**
- `DefaultContextRetrievalService.java` — implementation
- `ContextRetrievalServiceInitializer.java` — servlet context listener that registers the manager
- `knowledge/ContextRetrievalModule.java` — (only if needed for wiring; see task 10)

**Creating (in `wikantik-knowledge/src/test/java/com/wikantik/knowledge/`):**
- `ContextRetrievalRecordsTest.java` — record invariants
- `DefaultContextRetrievalServiceTest.java` — unit tests with fakes
- `ContextRetrievalServiceIT.java` — integration test on Testcontainer with seeded data

**Modifying:**
- `wikantik-rest/src/main/java/com/wikantik/rest/SearchResource.java` — call the service
- `wikantik-tools/src/main/java/com/wikantik/tools/SearchWikiTool.java` — call the service
- `wikantik-tools/src/main/java/com/wikantik/tools/ResultShaper.java` — may shrink or become obsolete
- `wikantik-war/src/main/webapp/WEB-INF/web.xml` — register `ContextRetrievalServiceInitializer`

---

## Task 1: Simple value records

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/RetrievedChunk.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/RelatedPage.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/MetadataValue.java`
- Test: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/ContextRetrievalRecordsTest.java`

- [ ] **Step 1: Write failing record invariants test**

Create `wikantik-knowledge/src/test/java/com/wikantik/knowledge/ContextRetrievalRecordsTest.java`:

```java
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextRetrievalRecordsTest {

    @Test
    void retrievedChunk_rejectsNullHeadingPath() {
        assertThrows( IllegalArgumentException.class,
            () -> new RetrievedChunk( null, "text", 0.5, List.of() ) );
    }

    @Test
    void retrievedChunk_rejectsNullText() {
        assertThrows( IllegalArgumentException.class,
            () -> new RetrievedChunk( List.of( "H1" ), null, 0.5, List.of() ) );
    }

    @Test
    void retrievedChunk_defaultsMatchedTermsWhenNull() {
        final RetrievedChunk chunk = new RetrievedChunk( List.of( "H1" ), "body", 0.5, null );
        assertEquals( List.of(), chunk.matchedTerms() );
    }

    @Test
    void retrievedChunk_copiesListsDefensively() {
        final var mutable = new java.util.ArrayList< String >();
        mutable.add( "a" );
        final RetrievedChunk chunk = new RetrievedChunk( mutable, "body", 0.5, List.of() );
        mutable.add( "b" );
        assertEquals( 1, chunk.headingPath().size() );
    }

    @Test
    void relatedPage_rejectsBlankName() {
        assertThrows( IllegalArgumentException.class,
            () -> new RelatedPage( "", "reason" ) );
    }

    @Test
    void relatedPage_acceptsEmptyReason() {
        final RelatedPage rp = new RelatedPage( "Other", "" );
        assertEquals( "", rp.reason() );
    }

    @Test
    void metadataValue_rejectsNegativeCount() {
        assertThrows( IllegalArgumentException.class,
            () -> new MetadataValue( "search", -1 ) );
    }

    @Test
    void metadataValue_rejectsNullValue() {
        assertThrows( IllegalArgumentException.class,
            () -> new MetadataValue( null, 1 ) );
    }
}
```

- [ ] **Step 2: Run test to verify compile failure**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=ContextRetrievalRecordsTest`
Expected: compile error — `RetrievedChunk`, `RelatedPage`, `MetadataValue` cannot be resolved.

- [ ] **Step 3: Create RetrievedChunk record**

Create `wikantik-api/src/main/java/com/wikantik/api/knowledge/RetrievedChunk.java`:

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
package com.wikantik.api.knowledge;

import java.util.List;

/**
 * One chunk of wiki content surfaced as context for a page in a retrieval
 * result. {@code headingPath} is the section breadcrumb (e.g. {@code
 * ["Retrieval Experiment Harness", "7. Model selection", "Decision rationale"]}).
 * {@code chunkScore} is the underlying similarity / BM25 score; its absolute
 * magnitude is retriever-dependent so callers should treat it as ordinal.
 * {@code matchedTerms} is a best-effort list of query terms that lit up on
 * this chunk — may be empty.
 */
public record RetrievedChunk(
    List< String > headingPath,
    String text,
    double chunkScore,
    List< String > matchedTerms
) {
    public RetrievedChunk {
        if ( headingPath == null ) {
            throw new IllegalArgumentException( "headingPath must not be null (use empty list)" );
        }
        if ( text == null ) {
            throw new IllegalArgumentException( "text must not be null" );
        }
        headingPath = List.copyOf( headingPath );
        matchedTerms = matchedTerms == null ? List.of() : List.copyOf( matchedTerms );
    }
}
```

- [ ] **Step 4: Create RelatedPage record**

Create `wikantik-api/src/main/java/com/wikantik/api/knowledge/RelatedPage.java`:

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
package com.wikantik.api.knowledge;

/**
 * Hint that another wiki page is closely related to a retrieved page via
 * Knowledge Graph mention co-occurrence. {@code reason} is a human-readable
 * summary of why (e.g. {@code "shared entities: qwen3, bm25"}) — may be empty
 * when the service cannot populate it, never {@code null}.
 */
public record RelatedPage( String name, String reason ) {
    public RelatedPage {
        if ( name == null || name.isBlank() ) {
            throw new IllegalArgumentException( "name must not be blank" );
        }
        reason = reason == null ? "" : reason;
    }
}
```

- [ ] **Step 5: Create MetadataValue record**

Create `wikantik-api/src/main/java/com/wikantik/api/knowledge/MetadataValue.java`:

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
package com.wikantik.api.knowledge;

/**
 * A distinct value of a frontmatter metadata field (e.g. {@code cluster})
 * with the count of pages whose frontmatter contains that value. Used by
 * {@code list_metadata_values} to surface "what clusters exist?" style
 * discovery queries.
 */
public record MetadataValue( String value, int count ) {
    public MetadataValue {
        if ( value == null ) {
            throw new IllegalArgumentException( "value must not be null" );
        }
        if ( count < 0 ) {
            throw new IllegalArgumentException( "count must not be negative" );
        }
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=ContextRetrievalRecordsTest`
Expected: 8 tests pass.

- [ ] **Step 7: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/RetrievedChunk.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/RelatedPage.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/MetadataValue.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/ContextRetrievalRecordsTest.java
git commit -m "feat(api): add RetrievedChunk/RelatedPage/MetadataValue records"
```

---

## Task 2: Filter records

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/PageListFilter.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/ContextQuery.java`
- Modify: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/ContextRetrievalRecordsTest.java`

- [ ] **Step 1: Add failing filter-record tests**

Append to `ContextRetrievalRecordsTest.java` (inside the class, before the closing brace):

```java
    @Test
    void pageListFilter_allFieldsOptional() {
        final PageListFilter f = new PageListFilter( null, null, null, null, null, null, 50, 0 );
        assertNull( f.cluster() );
        assertEquals( List.of(), f.tags() );
        assertNull( f.type() );
        assertEquals( 50, f.limit() );
    }

    @Test
    void pageListFilter_rejectsNegativeLimit() {
        assertThrows( IllegalArgumentException.class,
            () -> new PageListFilter( null, null, null, null, null, null, -1, 0 ) );
    }

    @Test
    void pageListFilter_rejectsLimitOverMax() {
        assertThrows( IllegalArgumentException.class,
            () -> new PageListFilter( null, null, null, null, null, null, 201, 0 ) );
    }

    @Test
    void pageListFilter_rejectsNegativeOffset() {
        assertThrows( IllegalArgumentException.class,
            () -> new PageListFilter( null, null, null, null, null, null, 50, -1 ) );
    }

    @Test
    void contextQuery_rejectsBlankQuery() {
        assertThrows( IllegalArgumentException.class,
            () -> new ContextQuery( "", 5, 3, null ) );
    }

    @Test
    void contextQuery_rejectsNullQuery() {
        assertThrows( IllegalArgumentException.class,
            () -> new ContextQuery( null, 5, 3, null ) );
    }

    @Test
    void contextQuery_clampsMaxPages() {
        assertThrows( IllegalArgumentException.class,
            () -> new ContextQuery( "q", 21, 3, null ) );
    }

    @Test
    void contextQuery_rejectsZeroChunksPerPage() {
        assertThrows( IllegalArgumentException.class,
            () -> new ContextQuery( "q", 5, 0, null ) );
    }

    @Test
    void contextQuery_filterDefaultsToEmpty() {
        final ContextQuery q = new ContextQuery( "q", 5, 3, null );
        assertNotNull( q.filter() );
        assertNull( q.filter().cluster() );
    }
```

- [ ] **Step 2: Run test to confirm compile failure**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=ContextRetrievalRecordsTest`
Expected: compile error — `PageListFilter`, `ContextQuery` unresolved.

- [ ] **Step 3: Create PageListFilter record**

Create `wikantik-api/src/main/java/com/wikantik/api/knowledge/PageListFilter.java`:

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
package com.wikantik.api.knowledge;

import java.time.Instant;
import java.util.List;

/**
 * Optional filters for {@link ContextRetrievalService#listPages(PageListFilter)}
 * and as an embedded filter in {@link ContextQuery}. All string/list fields are
 * null-tolerant; the two bounded numerics are validated.
 *
 * <p>Limit is bounded at 200 to avoid runaway JSON responses. Callers that
 * genuinely need more should paginate via {@code offset}.</p>
 */
public record PageListFilter(
    String cluster,
    List< String > tags,
    String type,
    String author,
    Instant modifiedAfter,
    Instant modifiedBefore,
    int limit,
    int offset
) {
    public static final int MAX_LIMIT = 200;

    public PageListFilter {
        if ( limit < 0 || limit > MAX_LIMIT ) {
            throw new IllegalArgumentException(
                "limit must be in [0, " + MAX_LIMIT + "], got " + limit );
        }
        if ( offset < 0 ) {
            throw new IllegalArgumentException( "offset must be >= 0, got " + offset );
        }
        tags = tags == null ? List.of() : List.copyOf( tags );
    }

    /** A filter with default pagination (50/0) and no field constraints. */
    public static PageListFilter unfiltered() {
        return new PageListFilter( null, null, null, null, null, null, 50, 0 );
    }
}
```

- [ ] **Step 4: Create ContextQuery record**

Create `wikantik-api/src/main/java/com/wikantik/api/knowledge/ContextQuery.java`:

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
package com.wikantik.api.knowledge;

/**
 * Input for {@link ContextRetrievalService#retrieve(ContextQuery)}. {@code query}
 * is the natural-language query; {@code maxPages} caps the returned page list
 * at 20; {@code chunksPerPage} caps per-page contributing chunks at 5. The
 * optional {@code filter} pre-filters the candidate page set before ranking.
 */
public record ContextQuery(
    String query,
    int maxPages,
    int chunksPerPage,
    PageListFilter filter
) {
    public static final int MAX_PAGES_CAP = 20;
    public static final int MAX_CHUNKS_PER_PAGE_CAP = 5;

    public ContextQuery {
        if ( query == null || query.isBlank() ) {
            throw new IllegalArgumentException( "query must not be blank" );
        }
        if ( maxPages <= 0 || maxPages > MAX_PAGES_CAP ) {
            throw new IllegalArgumentException(
                "maxPages must be in (0, " + MAX_PAGES_CAP + "], got " + maxPages );
        }
        if ( chunksPerPage <= 0 || chunksPerPage > MAX_CHUNKS_PER_PAGE_CAP ) {
            throw new IllegalArgumentException(
                "chunksPerPage must be in (0, " + MAX_CHUNKS_PER_PAGE_CAP + "], got " + chunksPerPage );
        }
        filter = filter == null ? PageListFilter.unfiltered() : filter;
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=ContextRetrievalRecordsTest`
Expected: all 17 tests pass.

- [ ] **Step 6: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/PageListFilter.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/ContextQuery.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/ContextRetrievalRecordsTest.java
git commit -m "feat(api): add PageListFilter/ContextQuery records"
```

---

## Task 3: Result wrapper records

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/RetrievedPage.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/RetrievalResult.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/PageList.java`
- Modify: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/ContextRetrievalRecordsTest.java`

- [ ] **Step 1: Add failing wrapper-record tests**

Append to `ContextRetrievalRecordsTest.java`:

```java
    @Test
    void retrievedPage_rejectsBlankName() {
        assertThrows( IllegalArgumentException.class,
            () -> new RetrievedPage( "", "url", 0.0, "", null, List.of(),
                List.of(), List.of(), null, null ) );
    }

    @Test
    void retrievedPage_defaultsCollections() {
        final RetrievedPage p = new RetrievedPage(
            "P", "url", 0.5, "summary", null, null, null, null, null, null );
        assertEquals( List.of(), p.tags() );
        assertEquals( List.of(), p.contributingChunks() );
        assertEquals( List.of(), p.relatedPages() );
        assertNull( p.author() );
        assertNull( p.lastModified() );
    }

    @Test
    void retrievalResult_rejectsNullPages() {
        assertThrows( IllegalArgumentException.class,
            () -> new RetrievalResult( "q", null, 0 ) );
    }

    @Test
    void retrievalResult_rejectsNegativeTotal() {
        assertThrows( IllegalArgumentException.class,
            () -> new RetrievalResult( "q", List.of(), -1 ) );
    }

    @Test
    void pageList_defaultsPagesToEmpty() {
        final PageList pl = new PageList( null, 0, 50, 0 );
        assertEquals( List.of(), pl.pages() );
    }
```

- [ ] **Step 2: Run test to verify compile failure**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=ContextRetrievalRecordsTest`
Expected: compile error — `RetrievedPage`, `RetrievalResult`, `PageList` unresolved.

- [ ] **Step 3: Create RetrievedPage record**

Create `wikantik-api/src/main/java/com/wikantik/api/knowledge/RetrievedPage.java`:

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
package com.wikantik.api.knowledge;

import java.util.List;

/**
 * A wiki page surfaced as a retrieval hit. Carries its own top contributing
 * chunks (from the chunk-level score pass) and a small list of
 * mention-co-occurrence neighbors as {@link RelatedPage} hints. All list
 * fields default to empty when {@code null} is passed; name must be
 * non-blank.
 */
public record RetrievedPage(
    String name,
    String url,
    double score,
    String summary,
    String cluster,
    List< String > tags,
    List< RetrievedChunk > contributingChunks,
    List< RelatedPage > relatedPages,
    String author,
    java.util.Date lastModified
) {
    public RetrievedPage {
        if ( name == null || name.isBlank() ) {
            throw new IllegalArgumentException( "name must not be blank" );
        }
        summary = summary == null ? "" : summary;
        tags = tags == null ? List.of() : List.copyOf( tags );
        contributingChunks = contributingChunks == null ? List.of() : List.copyOf( contributingChunks );
        relatedPages = relatedPages == null ? List.of() : List.copyOf( relatedPages );
        // Defensive copy of mutable Date so internal state stays immutable.
        lastModified = lastModified == null ? null : new java.util.Date( lastModified.getTime() );
    }

    /** Defensive copy on read — Date is mutable. */
    public java.util.Date lastModified() {
        return lastModified == null ? null : new java.util.Date( lastModified.getTime() );
    }
}
```

- [ ] **Step 4: Create RetrievalResult record**

Create `wikantik-api/src/main/java/com/wikantik/api/knowledge/RetrievalResult.java`:

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
package com.wikantik.api.knowledge;

import java.util.List;

/**
 * Output of {@link ContextRetrievalService#retrieve(ContextQuery)}. Echoes
 * the query back for client convenience. {@code totalMatched} is the count
 * of pages considered before truncation to {@code maxPages}.
 */
public record RetrievalResult( String query, List< RetrievedPage > pages, int totalMatched ) {
    public RetrievalResult {
        if ( query == null ) {
            throw new IllegalArgumentException( "query must not be null" );
        }
        if ( pages == null ) {
            throw new IllegalArgumentException( "pages must not be null" );
        }
        if ( totalMatched < 0 ) {
            throw new IllegalArgumentException( "totalMatched must not be negative" );
        }
        pages = List.copyOf( pages );
    }
}
```

- [ ] **Step 5: Create PageList record**

Create `wikantik-api/src/main/java/com/wikantik/api/knowledge/PageList.java`:

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
package com.wikantik.api.knowledge;

import java.util.List;

/**
 * Output of {@link ContextRetrievalService#listPages(PageListFilter)}. The
 * {@code pages} list never contains chunks or relatedPages — this is browse
 * output, not RAG output. {@code totalMatched} is the full match count,
 * useful for UI pagination; {@code limit} and {@code offset} echo the input.
 */
public record PageList(
    List< RetrievedPage > pages,
    int totalMatched,
    int limit,
    int offset
) {
    public PageList {
        pages = pages == null ? List.of() : List.copyOf( pages );
        if ( totalMatched < 0 ) {
            throw new IllegalArgumentException( "totalMatched must not be negative" );
        }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=ContextRetrievalRecordsTest`
Expected: all 22 tests pass.

- [ ] **Step 7: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/RetrievedPage.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/RetrievalResult.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/PageList.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/ContextRetrievalRecordsTest.java
git commit -m "feat(api): add RetrievedPage/RetrievalResult/PageList records"
```

---

## Task 4: ContextRetrievalService interface

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/ContextRetrievalService.java`

No test for the interface itself — the implementation tests (tasks 6-10) exercise the contract.

- [ ] **Step 1: Create the interface**

Create `wikantik-api/src/main/java/com/wikantik/api/knowledge/ContextRetrievalService.java`:

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
package com.wikantik.api.knowledge;

import java.util.List;

/**
 * The single entry point for agent-facing retrieval. Owns the composition of
 * BM25 + dense retrieval + graph rerank + chunk/relatedPages shaping so every
 * caller — REST {@code /api/search}, OpenAPI tool-server {@code search_wiki},
 * and the forthcoming MCP {@code retrieve_context} — produces consistent
 * results from the same pipeline.
 *
 * <p>Implementations are thread-safe and stateless; obtain one from the
 * engine's manager registry.</p>
 */
public interface ContextRetrievalService {

    /** Run the full retrieval pipeline for a natural-language query. */
    RetrievalResult retrieve( ContextQuery query );

    /**
     * Fetch a single page by name. Returns {@code null} if the page does
     * not exist. Does not consult the search stack — this is a direct
     * page lookup for pinned-context flows.
     */
    RetrievedPage getPage( String pageName );

    /** Browse pages by metadata filters. No ranking, no chunks, no related. */
    PageList listPages( PageListFilter filter );

    /**
     * Distinct values of a frontmatter field across all pages, with page
     * counts. {@code field} is the frontmatter key (e.g. {@code "cluster"}).
     */
    List< MetadataValue > listMetadataValues( String field );
}
```

- [ ] **Step 2: Compile-check**

Run: `mvn -pl wikantik-api -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/ContextRetrievalService.java
git commit -m "feat(api): add ContextRetrievalService interface"
```

---

## Task 5: DefaultContextRetrievalService scaffold

Create the skeleton with constructor + unimplemented methods so downstream tasks can fill each method in isolation.

**Files:**
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java`

- [ ] **Step 1: Create the scaffold**

Create `wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java`:

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
package com.wikantik.knowledge;

import com.wikantik.api.core.Engine;
import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.MetadataValue;
import com.wikantik.api.knowledge.PageList;
import com.wikantik.api.knowledge.PageListFilter;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RetrievedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.rest.FrontmatterMetadataCache;
import com.wikantik.search.SearchManager;
import com.wikantik.search.hybrid.ChunkVectorIndex;
import com.wikantik.search.hybrid.GraphRerankStep;
import com.wikantik.search.hybrid.HybridSearchService;

import java.util.List;

/**
 * Composes the retrieval singletons into the agent-facing contract defined
 * by {@link ContextRetrievalService}. Thread-safe and stateless; constructed
 * once at engine boot.
 *
 * <p>Dependencies are pulled from the engine in
 * {@link #fromEngine(Engine)} so the constructor stays trivially testable
 * with fakes.</p>
 */
public final class DefaultContextRetrievalService implements ContextRetrievalService {

    private final SearchManager searchManager;
    private final HybridSearchService hybridSearch;
    private final GraphRerankStep graphRerank;
    private final ChunkVectorIndex chunkIndex;
    private final ContentChunkRepository chunkRepo;
    private final NodeMentionSimilarity similarity;
    private final PageManager pageManager;
    private final FrontmatterMetadataCache fmCache;
    private final String publicBaseUrl;

    public DefaultContextRetrievalService(
            final SearchManager searchManager,
            final HybridSearchService hybridSearch,
            final GraphRerankStep graphRerank,
            final ChunkVectorIndex chunkIndex,
            final ContentChunkRepository chunkRepo,
            final NodeMentionSimilarity similarity,
            final PageManager pageManager,
            final FrontmatterMetadataCache fmCache,
            final String publicBaseUrl ) {
        if ( searchManager == null ) throw new IllegalArgumentException( "searchManager required" );
        if ( pageManager == null ) throw new IllegalArgumentException( "pageManager required" );
        // hybridSearch, graphRerank, chunkIndex, chunkRepo, similarity, fmCache all nullable:
        // the service degrades rather than failing when optional deps are absent.
        this.searchManager = searchManager;
        this.hybridSearch = hybridSearch;
        this.graphRerank = graphRerank;
        this.chunkIndex = chunkIndex;
        this.chunkRepo = chunkRepo;
        this.similarity = similarity;
        this.pageManager = pageManager;
        this.fmCache = fmCache;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl;
    }

    /**
     * Convenience factory pulling every dependency from the engine. Returns
     * {@code null} if the engine has no {@link PageManager}, since without
     * pages there is nothing to retrieve.
     */
    public static DefaultContextRetrievalService fromEngine( final Engine engine ) {
        final PageManager pm = engine.getManager( PageManager.class );
        if ( pm == null ) return null;
        final SearchManager sm = engine.getManager( SearchManager.class );
        if ( sm == null ) return null;
        return new DefaultContextRetrievalService(
            sm,
            engine.getManager( HybridSearchService.class ),
            engine.getManager( GraphRerankStep.class ),
            engine.getManager( ChunkVectorIndex.class ),
            engine.getManager( ContentChunkRepository.class ),
            engine.getManager( NodeMentionSimilarity.class ),
            pm,
            engine.getManager( FrontmatterMetadataCache.class ),
            engine.getBaseURL() );
    }

    @Override
    public RetrievalResult retrieve( final ContextQuery query ) {
        throw new UnsupportedOperationException( "implemented in task 8" );
    }

    @Override
    public RetrievedPage getPage( final String pageName ) {
        throw new UnsupportedOperationException( "implemented in task 6" );
    }

    @Override
    public PageList listPages( final PageListFilter filter ) {
        throw new UnsupportedOperationException( "implemented in task 7" );
    }

    @Override
    public List< MetadataValue > listMetadataValues( final String field ) {
        throw new UnsupportedOperationException( "implemented in task 7b" );
    }
}
```

- [ ] **Step 2: Compile-check**

Run: `mvn -pl wikantik-knowledge -am -q compile`
Expected: BUILD SUCCESS. If `FrontmatterMetadataCache` cannot be resolved, confirm it's in `wikantik-rest` (it is — look at `SearchResource.java:130`) and add the `wikantik-rest` dependency to `wikantik-knowledge/pom.xml` with `<scope>compile</scope>`. If the import cycle is awkward, move `FrontmatterMetadataCache` to `wikantik-main` as a follow-up.

Verify dependency is present:

```bash
grep -A2 "wikantik-rest\|wikantik-main" wikantik-knowledge/pom.xml | head -20
```

If `wikantik-rest` is not already a dependency and `FrontmatterMetadataCache` cannot move trivially, replace the field with direct `PageManager` + `FrontmatterParser` lookups in task 7 instead.

- [ ] **Step 3: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java
git add -- wikantik-knowledge/pom.xml  # if dependency was added
git commit -m "feat(knowledge): scaffold DefaultContextRetrievalService"
```

---

## Task 6: Implement `getPage()`

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java`
- Create: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/DefaultContextRetrievalServiceTest.java`

- [ ] **Step 1: Write failing test for getPage**

Create `wikantik-knowledge/src/test/java/com/wikantik/knowledge/DefaultContextRetrievalServiceTest.java`:

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
package com.wikantik.knowledge;

import com.wikantik.api.core.Page;
import com.wikantik.api.knowledge.RetrievedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.knowledge.testfakes.FakeDeps;
import com.wikantik.knowledge.testfakes.FakePageManager;
import com.wikantik.knowledge.testfakes.FakeSearchManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class DefaultContextRetrievalServiceTest {

    @Test
    void getPage_returnsNullWhenMissing() {
        final DefaultContextRetrievalService svc = FakeDeps.minimal().build();
        assertNull( svc.getPage( "Nonexistent" ) );
    }

    @Test
    void getPage_returnsShapedRecord() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "Hub", "---\nsummary: the hub\ncluster: search\n"
                + "tags: [retrieval, search]\n---\n\nBody", "alice", Date.from( Instant.parse( "2026-04-23T00:00:00Z" ) ) );
        final DefaultContextRetrievalService svc = FakeDeps.minimal()
            .pageManager( pm ).baseUrl( "https://wiki.example" ).build();

        final RetrievedPage p = svc.getPage( "Hub" );

        assertNotNull( p );
        assertEquals( "Hub", p.name() );
        assertEquals( "https://wiki.example/Hub", p.url() );
        assertEquals( "the hub", p.summary() );
        assertEquals( "search", p.cluster() );
        assertEquals( java.util.List.of( "retrieval", "search" ), p.tags() );
        assertEquals( 0.0, p.score() );
        assertTrue( p.contributingChunks().isEmpty() );
        assertTrue( p.relatedPages().isEmpty() );
        assertEquals( "alice", p.author() );
    }
}
```

(The `FakeDeps` / `FakePageManager` / `FakeSearchManager` test helpers live in a test-only package. Adding them in the next step.)

- [ ] **Step 2: Create the test fake helpers**

Create `wikantik-knowledge/src/test/java/com/wikantik/knowledge/testfakes/FakeDeps.java`:

```java
package com.wikantik.knowledge.testfakes;

import com.wikantik.knowledge.DefaultContextRetrievalService;

/** Test-only builder that wires null-tolerant deps into the service. */
public final class FakeDeps {

    private FakeSearchManager search = new FakeSearchManager();
    private FakePageManager pageManager = new FakePageManager();
    private String baseUrl = "";

    public static FakeDeps minimal() { return new FakeDeps(); }

    public FakeDeps search( FakeSearchManager s ) { this.search = s; return this; }
    public FakeDeps pageManager( FakePageManager pm ) { this.pageManager = pm; return this; }
    public FakeDeps baseUrl( String u ) { this.baseUrl = u; return this; }

    public DefaultContextRetrievalService build() {
        return new DefaultContextRetrievalService(
            search,
            null,  // hybridSearch
            null,  // graphRerank
            null,  // chunkIndex
            null,  // chunkRepo
            null,  // similarity
            pageManager,
            null,  // fmCache
            baseUrl );
    }
}
```

Create `wikantik-knowledge/src/test/java/com/wikantik/knowledge/testfakes/FakePageManager.java`:

```java
package com.wikantik.knowledge.testfakes;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;

import java.util.*;

/**
 * Minimal PageManager fake — only the methods the service touches. Other
 * methods throw UnsupportedOperationException so unexpected calls surface.
 */
public class FakePageManager implements PageManager {

    private final Map< String, StoredPage > pages = new LinkedHashMap<>();

    public void addPage( String name, String text, String author, Date lastModified ) {
        pages.put( name, new StoredPage( name, text, author, lastModified ) );
    }

    @Override public Page getPage( String name ) {
        final StoredPage sp = pages.get( name );
        return sp == null ? null : sp.toPage( PageProvider.LATEST_VERSION );
    }

    @Override public Page getPage( String name, int version ) {
        return getPage( name );
    }

    @Override public String getPureText( String name, int version ) {
        final StoredPage sp = pages.get( name );
        return sp == null ? null : sp.text;
    }

    @Override public Collection< String > getAllPages() {
        return List.copyOf( pages.keySet() );
    }

    // remaining PageManager methods throw — force failure if anything else is used.
    @Override public Page getPage( String name, int version, String author ) {
        throw new UnsupportedOperationException();
    }
    // ...add stubs for every remaining interface method so compile succeeds.
    // For the exact list, run `javap com.wikantik.api.managers.PageManager`
    // or open the interface file and stub each method with
    // `throw new UnsupportedOperationException();`.

    private static final class StoredPage {
        final String name;
        final String text;
        final String author;
        final Date lastModified;
        StoredPage( String n, String t, String a, Date d ) {
            name = n; text = t; author = a; lastModified = d;
        }
        Page toPage( int version ) {
            // Build a minimal Page via the API's simple page factory.
            // Use com.wikantik.api.spi.Wiki.contents().page(...) if needed,
            // or create an anonymous Page implementation that returns the
            // stored fields. Concrete approach: see an existing
            // PageManager test fake in wikantik-main/src/test/java for a
            // working pattern to copy.
            throw new UnsupportedOperationException( "Fill in using an existing test fake pattern" );
        }
    }
}
```

Create `wikantik-knowledge/src/test/java/com/wikantik/knowledge/testfakes/FakeSearchManager.java`:

```java
package com.wikantik.knowledge.testfakes;

import com.wikantik.api.core.Context;
import com.wikantik.api.search.SearchResult;
import com.wikantik.search.SearchManager;

import java.util.*;

/** Minimal SearchManager fake that returns a preconfigured BM25 result list. */
public class FakeSearchManager implements SearchManager {

    private List< SearchResult > nextResults = List.of();

    public void setResults( List< SearchResult > results ) {
        this.nextResults = List.copyOf( results );
    }

    @Override
    public Collection< SearchResult > findPages( String query, Context ctx ) {
        return nextResults;
    }

    // stub the rest with UnsupportedOperationException — same pattern as FakePageManager.
}
```

**Important:** `FakePageManager.StoredPage.toPage()` and the remaining stubs
are a pattern-copy from an existing wiki test fake. Before filling
these in, search for a working pattern:

```bash
find wikantik-main/src/test -name "*PageManager*" -o -name "*FakePage*" | head
grep -l "implements PageManager" wikantik-main/src/test/java/**/*.java 2>/dev/null | head
```

If no suitable fake exists, use the `TestEngine` from `com.wikantik.TestEngine`
which is already a test-time engine with a working `PageManager`. In that
case, replace `FakePageManager` with a thin wrapper around `TestEngine`.

- [ ] **Step 3: Run test to confirm compile failure**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=DefaultContextRetrievalServiceTest`
Expected: fails because `getPage()` throws `UnsupportedOperationException` and/or because the fakes don't compile.

- [ ] **Step 4: Implement `getPage()` in the service**

Edit `DefaultContextRetrievalService.java` — replace the body of `getPage(String)`:

```java
    @Override
    public RetrievedPage getPage( final String pageName ) {
        if ( pageName == null || pageName.isBlank() ) return null;
        final Page page = pageManager.getPage( pageName );
        if ( page == null ) return null;
        final String rawText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
        final ParsedPage parsed = FrontmatterParser.parse( rawText == null ? "" : rawText );
        return new RetrievedPage(
            page.getName(),
            buildUrl( page.getName() ),
            0.0,
            stringOrEmpty( parsed.metadata().get( "summary" ) ),
            stringOrNull( parsed.metadata().get( "cluster" ) ),
            stringList( parsed.metadata().get( "tags" ) ),
            List.of(),
            List.of(),
            page.getAuthor(),
            page.getLastModified() );
    }

    private String buildUrl( final String pageName ) {
        if ( publicBaseUrl == null || publicBaseUrl.isBlank() ) return pageName;
        final String base = publicBaseUrl.endsWith( "/" ) ? publicBaseUrl : publicBaseUrl + "/";
        return base + pageName;
    }

    private static String stringOrEmpty( final Object o ) {
        return o == null ? "" : o.toString();
    }

    private static String stringOrNull( final Object o ) {
        return o == null ? null : o.toString();
    }

    @SuppressWarnings( "unchecked" )
    private static List< String > stringList( final Object o ) {
        if ( !( o instanceof List< ? > raw ) ) return List.of();
        final List< String > out = new ArrayList<>( raw.size() );
        for ( final Object item : raw ) if ( item != null ) out.add( item.toString() );
        return List.copyOf( out );
    }
```

Add required imports:

```java
import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.providers.PageProvider;
import java.util.ArrayList;
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=DefaultContextRetrievalServiceTest`
Expected: both `getPage_*` tests pass.

- [ ] **Step 6: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/DefaultContextRetrievalServiceTest.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/testfakes/
git commit -m "feat(knowledge): implement ContextRetrievalService.getPage()"
```

---

## Task 7: Implement `listMetadataValues()`

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java`
- Modify: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/DefaultContextRetrievalServiceTest.java`

- [ ] **Step 1: Write failing test**

Append to `DefaultContextRetrievalServiceTest.java`:

```java
    @Test
    void listMetadataValues_countsDistinctClusters() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "A", "---\ncluster: search\n---\n\n", "bob", new java.util.Date() );
        pm.addPage( "B", "---\ncluster: search\n---\n\n", "bob", new java.util.Date() );
        pm.addPage( "C", "---\ncluster: kg\n---\n\n", "bob", new java.util.Date() );
        pm.addPage( "D", "---\n---\n\n", "bob", new java.util.Date() );

        final DefaultContextRetrievalService svc = FakeDeps.minimal().pageManager( pm ).build();
        final var values = svc.listMetadataValues( "cluster" );

        assertEquals( 2, values.size() );
        // Sorted by count descending:
        assertEquals( "search", values.get( 0 ).value() );
        assertEquals( 2, values.get( 0 ).count() );
        assertEquals( "kg", values.get( 1 ).value() );
        assertEquals( 1, values.get( 1 ).count() );
    }

    @Test
    void listMetadataValues_expandsListFields() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "A", "---\ntags: [retrieval, search]\n---\n\n", "b", new java.util.Date() );
        pm.addPage( "B", "---\ntags: [search, kg]\n---\n\n", "b", new java.util.Date() );

        final DefaultContextRetrievalService svc = FakeDeps.minimal().pageManager( pm ).build();
        final var values = svc.listMetadataValues( "tags" );

        // Each tag counts once per page; "search" appears on 2 pages, others on 1.
        assertEquals( 3, values.size() );
        assertEquals( "search", values.get( 0 ).value() );
        assertEquals( 2, values.get( 0 ).count() );
    }
```

- [ ] **Step 2: Run test to verify failure**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=DefaultContextRetrievalServiceTest`
Expected: the two new tests fail with `UnsupportedOperationException`.

- [ ] **Step 3: Implement `listMetadataValues()`**

Replace the method body in `DefaultContextRetrievalService.java`:

```java
    @Override
    public List< MetadataValue > listMetadataValues( final String field ) {
        if ( field == null || field.isBlank() ) return List.of();
        final Map< String, Integer > counts = new LinkedHashMap<>();
        for ( final String pageName : pageManager.getAllPages() ) {
            final String text = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
            if ( text == null ) continue;
            final ParsedPage parsed = FrontmatterParser.parse( text );
            final Object raw = parsed.metadata().get( field );
            if ( raw == null ) continue;
            if ( raw instanceof List< ? > listVal ) {
                for ( final Object v : listVal ) {
                    if ( v != null ) counts.merge( v.toString(), 1, Integer::sum );
                }
            } else {
                counts.merge( raw.toString(), 1, Integer::sum );
            }
        }
        return counts.entrySet().stream()
            .map( e -> new MetadataValue( e.getKey(), e.getValue() ) )
            .sorted( ( a, b ) -> Integer.compare( b.count(), a.count() ) )
            .toList();
    }
```

Add import:

```java
import java.util.LinkedHashMap;
```

- [ ] **Step 4: Run test to verify pass**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=DefaultContextRetrievalServiceTest`
Expected: all tests pass (4 now).

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/DefaultContextRetrievalServiceTest.java
git commit -m "feat(knowledge): implement ContextRetrievalService.listMetadataValues()"
```

---

## Task 8: Implement `listPages()` with filters

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java`
- Modify: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/DefaultContextRetrievalServiceTest.java`

- [ ] **Step 1: Write failing tests**

Append to `DefaultContextRetrievalServiceTest.java`:

```java
    @Test
    void listPages_filtersByCluster() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "S1", "---\ncluster: search\n---\n\n", "a", new java.util.Date() );
        pm.addPage( "K1", "---\ncluster: kg\n---\n\n", "a", new java.util.Date() );
        pm.addPage( "S2", "---\ncluster: search\n---\n\n", "a", new java.util.Date() );

        final DefaultContextRetrievalService svc = FakeDeps.minimal().pageManager( pm ).build();
        final var result = svc.listPages( new com.wikantik.api.knowledge.PageListFilter(
            "search", null, null, null, null, null, 50, 0 ) );

        assertEquals( 2, result.totalMatched() );
        assertEquals( 2, result.pages().size() );
        assertTrue( result.pages().stream().allMatch( p -> "search".equals( p.cluster() ) ) );
    }

    @Test
    void listPages_filtersByTag() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "A", "---\ntags: [search, retrieval]\n---\n\n", "a", new java.util.Date() );
        pm.addPage( "B", "---\ntags: [kg]\n---\n\n", "a", new java.util.Date() );

        final DefaultContextRetrievalService svc = FakeDeps.minimal().pageManager( pm ).build();
        final var result = svc.listPages( new com.wikantik.api.knowledge.PageListFilter(
            null, java.util.List.of( "search" ), null, null, null, null, 50, 0 ) );

        assertEquals( 1, result.pages().size() );
        assertEquals( "A", result.pages().get( 0 ).name() );
    }

    @Test
    void listPages_respectsLimitAndOffset() {
        final FakePageManager pm = new FakePageManager();
        for ( int i = 0; i < 10; i++ ) {
            pm.addPage( "P" + i, "---\n---\n\n", "a", new java.util.Date() );
        }
        final DefaultContextRetrievalService svc = FakeDeps.minimal().pageManager( pm ).build();
        final var result = svc.listPages( new com.wikantik.api.knowledge.PageListFilter(
            null, null, null, null, null, null, 3, 4 ) );

        assertEquals( 10, result.totalMatched() );
        assertEquals( 3, result.pages().size() );
        assertEquals( "P4", result.pages().get( 0 ).name() );
        assertEquals( "P6", result.pages().get( 2 ).name() );
    }
```

- [ ] **Step 2: Run test to verify failure**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=DefaultContextRetrievalServiceTest`
Expected: new tests fail with `UnsupportedOperationException`.

- [ ] **Step 3: Implement `listPages()`**

Replace the method body in `DefaultContextRetrievalService.java`:

```java
    @Override
    public PageList listPages( final PageListFilter filter ) {
        final PageListFilter f = filter == null ? PageListFilter.unfiltered() : filter;

        final List< RetrievedPage > matched = new ArrayList<>();
        for ( final String pageName : pageManager.getAllPages() ) {
            final Page page = pageManager.getPage( pageName );
            if ( page == null ) continue;
            final String text = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( text == null ? "" : text );
            if ( !matchesFilter( page, parsed, f ) ) continue;
            matched.add( new RetrievedPage(
                page.getName(),
                buildUrl( page.getName() ),
                0.0,
                stringOrEmpty( parsed.metadata().get( "summary" ) ),
                stringOrNull( parsed.metadata().get( "cluster" ) ),
                stringList( parsed.metadata().get( "tags" ) ),
                List.of(),
                List.of(),
                page.getAuthor(),
                page.getLastModified() ) );
        }
        final int total = matched.size();
        final int from = Math.min( f.offset(), total );
        final int to = Math.min( from + Math.max( f.limit(), 1 ), total );
        return new PageList( matched.subList( from, to ), total, f.limit(), f.offset() );
    }

    private boolean matchesFilter( final Page page, final ParsedPage parsed, final PageListFilter f ) {
        if ( f.cluster() != null
                && !f.cluster().equals( parsed.metadata().get( "cluster" ) ) ) {
            return false;
        }
        if ( f.type() != null
                && !f.type().equals( parsed.metadata().get( "type" ) ) ) {
            return false;
        }
        if ( f.author() != null && !f.author().equals( page.getAuthor() ) ) {
            return false;
        }
        final List< String > tags = stringList( parsed.metadata().get( "tags" ) );
        for ( final String required : f.tags() ) {
            if ( !tags.contains( required ) ) return false;
        }
        if ( f.modifiedAfter() != null ) {
            final Date d = page.getLastModified();
            if ( d == null || d.toInstant().isBefore( f.modifiedAfter() ) ) return false;
        }
        if ( f.modifiedBefore() != null ) {
            final Date d = page.getLastModified();
            if ( d == null || d.toInstant().isAfter( f.modifiedBefore() ) ) return false;
        }
        return true;
    }
```

Add imports:

```java
import java.util.Date;
```

- [ ] **Step 4: Run tests**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=DefaultContextRetrievalServiceTest`
Expected: all tests pass (7 now).

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/DefaultContextRetrievalServiceTest.java
git commit -m "feat(knowledge): implement ContextRetrievalService.listPages()"
```

---

## Task 9: Implement `retrieve()` — ordered pages (no chunks, no relatedPages)

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java`
- Modify: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/DefaultContextRetrievalServiceTest.java`

- [ ] **Step 1: Write failing test**

Append to `DefaultContextRetrievalServiceTest.java`:

```java
    @Test
    void retrieve_returnsPagesInBm25Order_whenHybridDisabled() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "Alpha", "---\nsummary: alpha summary\n---\n\nbody", "a", new java.util.Date() );
        pm.addPage( "Beta",  "---\nsummary: beta summary\n---\n\nbody", "a", new java.util.Date() );
        pm.addPage( "Gamma", "---\nsummary: gamma summary\n---\n\nbody", "a", new java.util.Date() );

        final FakeSearchManager sm = new FakeSearchManager();
        sm.setResults( java.util.List.of(
            com.wikantik.knowledge.testfakes.FakeSearchResult.of( "Beta", 5 ),
            com.wikantik.knowledge.testfakes.FakeSearchResult.of( "Alpha", 3 ) ) );

        final DefaultContextRetrievalService svc = FakeDeps.minimal()
            .search( sm ).pageManager( pm ).build();

        final var result = svc.retrieve( new com.wikantik.api.knowledge.ContextQuery(
            "bm25 hit terms", 5, 3, null ) );

        assertEquals( "bm25 hit terms", result.query() );
        assertEquals( 2, result.totalMatched() );
        assertEquals( 2, result.pages().size() );
        assertEquals( "Beta", result.pages().get( 0 ).name() );
        assertEquals( "Alpha", result.pages().get( 1 ).name() );
        assertEquals( "beta summary", result.pages().get( 0 ).summary() );
        assertTrue( result.pages().get( 0 ).contributingChunks().isEmpty(),
            "chunks populated in later task" );
        assertTrue( result.pages().get( 0 ).relatedPages().isEmpty(),
            "relatedPages populated in later task" );
    }

    @Test
    void retrieve_respectsMaxPages() {
        final FakePageManager pm = new FakePageManager();
        for ( int i = 0; i < 8; i++ ) {
            pm.addPage( "P" + i, "---\n---\n\n", "a", new java.util.Date() );
        }
        final FakeSearchManager sm = new FakeSearchManager();
        final var srs = new java.util.ArrayList< com.wikantik.api.search.SearchResult >();
        for ( int i = 0; i < 8; i++ ) {
            srs.add( com.wikantik.knowledge.testfakes.FakeSearchResult.of( "P" + i, 8 - i ) );
        }
        sm.setResults( srs );

        final DefaultContextRetrievalService svc = FakeDeps.minimal()
            .search( sm ).pageManager( pm ).build();

        final var result = svc.retrieve( new com.wikantik.api.knowledge.ContextQuery( "q", 3, 3, null ) );

        assertEquals( 8, result.totalMatched() );
        assertEquals( 3, result.pages().size() );
    }
```

Create `wikantik-knowledge/src/test/java/com/wikantik/knowledge/testfakes/FakeSearchResult.java`:

```java
package com.wikantik.knowledge.testfakes;

import com.wikantik.api.core.Page;
import com.wikantik.api.search.SearchResult;
import com.wikantik.api.spi.Wiki;

public final class FakeSearchResult implements SearchResult {

    public static FakeSearchResult of( String name, int score ) {
        return new FakeSearchResult( name, score );
    }

    private final String name;
    private final int score;

    private FakeSearchResult( String name, int score ) {
        this.name = name;
        this.score = score;
    }

    @Override public Page getPage() {
        // Minimal Page — only getName() is actually used by the service.
        // Follow the same pattern as FakePageManager.toPage(); simplest
        // is an anonymous Page that returns the stored name and nulls
        // elsewhere. Check wikantik-api for the interface shape.
        throw new UnsupportedOperationException( "Implement matching FakePageManager.toPage()" );
    }
    @Override public int getScore() { return score; }
    @Override public String[] getContexts() { return new String[ 0 ]; }

    public String name() { return name; }
}
```

**Note to implementer:** `FakeSearchResult.getPage()` and
`FakePageManager.StoredPage.toPage()` both need a tiny anonymous `Page`
implementation. The simplest working version returns `name`,
`lastModified`, `author`, and null for everything else. The service code
in the retrieve path only calls `getName()`, so the other method stubs
can return null/empty.

- [ ] **Step 2: Run test to verify failure**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=DefaultContextRetrievalServiceTest`
Expected: new tests fail with `UnsupportedOperationException` from `retrieve()`.

- [ ] **Step 3: Implement `retrieve()` — pages only**

Replace the `retrieve()` method in `DefaultContextRetrievalService.java`:

```java
    @Override
    public RetrievalResult retrieve( final ContextQuery query ) {
        if ( query == null ) throw new IllegalArgumentException( "query required" );

        // Phase 1: BM25 pass. Uses an anonymous anchor Page for context just
        // like SearchResource does.
        final Page anchor = pageManager.getPage( "Main" );
        final Context ctx = anchor != null
            ? com.wikantik.api.spi.Wiki.context().create( enginePlaceholder(), anchor )
            : null;
        final Collection< SearchResult > bm25;
        try {
            bm25 = searchManager.findPages( query.query(), ctx );
        } catch ( final Exception e ) {
            LOG.warn( "BM25 search failed: {}", e.getMessage(), e );
            return new RetrievalResult( query.query(), List.of(), 0 );
        }
        final List< SearchResult > ordered = applyHybridAndGraphRerank( query.query(), bm25 );

        // Phase 2: filter by ContextQuery.filter (if any).
        final PageListFilter f = query.filter();
        final List< RetrievedPage > pages = new ArrayList<>();
        for ( final SearchResult sr : ordered ) {
            final Page page = sr.getPage();
            if ( page == null ) continue;
            final String text = pageManager.getPureText( page.getName(), PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( text == null ? "" : text );
            if ( f != null && !matchesFilter( page, parsed, f ) ) continue;
            pages.add( new RetrievedPage(
                page.getName(),
                buildUrl( page.getName() ),
                sr.getScore(),
                stringOrEmpty( parsed.metadata().get( "summary" ) ),
                stringOrNull( parsed.metadata().get( "cluster" ) ),
                stringList( parsed.metadata().get( "tags" ) ),
                List.of(),  // chunks filled in task 10
                List.of(),  // relatedPages filled in task 11
                page.getAuthor(),
                page.getLastModified()
            ) );
            if ( pages.size() >= query.maxPages() ) break;
        }
        return new RetrievalResult( query.query(), pages, ordered.size() );
    }

    private List< SearchResult > applyHybridAndGraphRerank( final String query,
                                                            final Collection< SearchResult > bm25 ) {
        final List< SearchResult > asList = new ArrayList<>( bm25 == null ? List.of() : bm25 );
        if ( hybridSearch == null || !hybridSearch.isEnabled() ) {
            return asList;
        }
        final List< String > names = new ArrayList<>();
        final Map< String, SearchResult > byName = new LinkedHashMap<>();
        for ( final SearchResult sr : asList ) {
            if ( sr.getPage() == null ) continue;
            names.add( sr.getPage().getName() );
            byName.putIfAbsent( sr.getPage().getName(), sr );
        }
        List< String > fused = hybridSearch.rerank( query, names );
        if ( graphRerank != null ) {
            try {
                fused = graphRerank.rerank( query, fused );
            } catch ( final RuntimeException e ) {
                LOG.warn( "Graph rerank failed; using hybrid fused order: {}", e.getMessage(), e );
            }
        }
        if ( fused.equals( names ) ) return asList;
        final List< SearchResult > out = new ArrayList<>( fused.size() );
        for ( final String name : fused ) {
            final SearchResult existing = byName.get( name );
            if ( existing != null ) { out.add( existing ); continue; }
            final Page p = pageManager.getPage( name );
            if ( p == null ) continue;
            out.add( new DenseOnlySearchResult( p ) );
        }
        return out;
    }

    /** Mirrors the DenseOnlySearchResult in SearchResource. */
    private static final class DenseOnlySearchResult implements SearchResult {
        private final Page page;
        DenseOnlySearchResult( final Page p ) { this.page = p; }
        @Override public Page getPage() { return page; }
        @Override public int getScore() { return 0; }
        @Override public String[] getContexts() { return new String[ 0 ]; }
    }

    /** Placeholder: replaced with real engine handle once manager wiring lands (task 11). */
    private Engine enginePlaceholder() { return null; }
```

Add imports:

```java
import com.wikantik.api.core.Context;
import com.wikantik.api.search.SearchResult;
import java.util.Collection;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

private static final Logger LOG = LogManager.getLogger( DefaultContextRetrievalService.class );
```

**Note:** The `enginePlaceholder()` shim is a pragmatic compromise. `SearchManager.findPages` needs a `Context` which needs an `Engine`. The test fakes pass `null` anchor page so the context is `null` and the fake ignores it. Real production wiring in task 11 supplies a real engine via the service constructor — add an `Engine` field to the constructor in task 11 and use it to build the context properly. Mark the placeholder with a `// TODO(task 11)` comment so it's obvious.

- [ ] **Step 4: Run tests — verify pass**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=DefaultContextRetrievalServiceTest`
Expected: all tests pass (9 now). If `FakeSearchResult.getPage()` wasn't finished, fill it in using the minimal Page pattern referenced.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/DefaultContextRetrievalServiceTest.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/testfakes/FakeSearchResult.java
git commit -m "feat(knowledge): implement retrieve() — pages only (chunks/related pending)"
```

---

## Task 10: Populate `contributingChunks` on retrieved pages

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java`
- Modify: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/DefaultContextRetrievalServiceTest.java`

- [ ] **Step 1: Write failing test with fake chunk index + chunk repo**

Append to `DefaultContextRetrievalServiceTest.java`:

```java
    @Test
    void retrieve_populatesContributingChunksFromDenseIndex() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "Alpha", "---\n---\nbody", "a", new java.util.Date() );
        pm.addPage( "Beta", "---\n---\nbody", "a", new java.util.Date() );

        final FakeSearchManager sm = new FakeSearchManager();
        sm.setResults( java.util.List.of(
            com.wikantik.knowledge.testfakes.FakeSearchResult.of( "Alpha", 5 ),
            com.wikantik.knowledge.testfakes.FakeSearchResult.of( "Beta", 3 ) ) );

        final java.util.UUID alphaC1 = java.util.UUID.randomUUID();
        final java.util.UUID alphaC2 = java.util.UUID.randomUUID();
        final java.util.UUID betaC1  = java.util.UUID.randomUUID();

        final var chunkIndex = new com.wikantik.knowledge.testfakes.FakeChunkVectorIndex();
        chunkIndex.setEnabled( true );
        chunkIndex.setDim( 8 );
        chunkIndex.setTopK( java.util.List.of(
            new com.wikantik.search.hybrid.ScoredChunk( alphaC1, "Alpha", 0.9 ),
            new com.wikantik.search.hybrid.ScoredChunk( alphaC2, "Alpha", 0.8 ),
            new com.wikantik.search.hybrid.ScoredChunk( betaC1,  "Beta",  0.7 ) ) );

        final var chunkRepo = new com.wikantik.knowledge.testfakes.FakeChunkRepository();
        chunkRepo.addChunk( alphaC1, "Alpha", 0, java.util.List.of( "Alpha", "Intro" ), "first chunk of Alpha" );
        chunkRepo.addChunk( alphaC2, "Alpha", 1, java.util.List.of( "Alpha", "Details" ), "second chunk of Alpha" );
        chunkRepo.addChunk( betaC1,  "Beta",  0, java.util.List.of( "Beta"  ), "beta chunk one" );

        final var hybrid = com.wikantik.knowledge.testfakes.FakeHybridSearch.enabledReturning(
            java.util.List.of( "Alpha", "Beta" ) );

        final DefaultContextRetrievalService svc = new DefaultContextRetrievalService(
            sm, hybrid, null, chunkIndex, chunkRepo, null, pm, null, "https://wiki.example" );

        final var result = svc.retrieve( new com.wikantik.api.knowledge.ContextQuery(
            "alpha query", 5, 2, null ) );

        final var alphaPage = result.pages().stream()
            .filter( p -> "Alpha".equals( p.name() ) ).findFirst().orElseThrow();
        assertEquals( 2, alphaPage.contributingChunks().size() );
        assertEquals( java.util.List.of( "Alpha", "Intro" ),
            alphaPage.contributingChunks().get( 0 ).headingPath() );
        assertEquals( "first chunk of Alpha",
            alphaPage.contributingChunks().get( 0 ).text() );
        assertEquals( 0.9, alphaPage.contributingChunks().get( 0 ).chunkScore(), 0.0001 );
    }
```

Create `FakeChunkVectorIndex.java` in the same `testfakes` package:

```java
package com.wikantik.knowledge.testfakes;

import com.wikantik.search.hybrid.ChunkVectorIndex;
import com.wikantik.search.hybrid.ScoredChunk;

import java.util.List;

public class FakeChunkVectorIndex implements ChunkVectorIndex {
    private boolean enabled;
    private int dim;
    private List< ScoredChunk > topK = List.of();

    public void setEnabled( boolean b ) { this.enabled = b; }
    public void setDim( int d ) { this.dim = d; }
    public void setTopK( List< ScoredChunk > chunks ) { this.topK = List.copyOf( chunks ); }

    @Override public List< ScoredChunk > topKChunks( float[] vec, int k ) {
        return topK.size() > k ? topK.subList( 0, k ) : topK;
    }
    @Override public boolean isReady() { return enabled; }
    @Override public int dimension() { return dim; }
}
```

Create `FakeChunkRepository.java`:

```java
package com.wikantik.knowledge.testfakes;

import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository.MentionableChunk;

import java.util.*;

/** Stubs ContentChunkRepository for findByIds — other methods throw. */
public class FakeChunkRepository extends ContentChunkRepository {

    private final Map< UUID, MentionableChunk > byId = new LinkedHashMap<>();

    public FakeChunkRepository() {
        super( null );  // DataSource not used by our overridden methods
    }

    public void addChunk( UUID id, String page, int idx, List< String > headingPath, String text ) {
        byId.put( id, new MentionableChunk( id, page, idx, headingPath, text ) );
    }

    @Override public List< MentionableChunk > findByIds( List< UUID > ids ) {
        if ( ids == null || ids.isEmpty() ) return List.of();
        final List< MentionableChunk > out = new ArrayList<>();
        for ( final UUID id : ids ) {
            final MentionableChunk mc = byId.get( id );
            if ( mc != null ) out.add( mc );
        }
        return out;
    }
}
```

**HybridSearchService is `public final`** — cannot be subclassed. Use Mockito instead (project-wide mockito-core with dynamic agent loading is already configured; it can mock final classes). Add a helper:

Create `FakeHybridSearch.java`:

```java
package com.wikantik.knowledge.testfakes;

import com.wikantik.search.hybrid.HybridSearchService;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Mockito-backed stub for the final HybridSearchService class. */
public final class FakeHybridSearch {

    public static HybridSearchService enabledReturning( final List< String > rerankOrder ) {
        final HybridSearchService mock = mock( HybridSearchService.class );
        when( mock.isEnabled() ).thenReturn( true );
        when( mock.rerank( anyString(), anyList() ) ).thenReturn( rerankOrder );
        when( mock.rerankWith( anyString(), anyList(), any() ) ).thenReturn( rerankOrder );
        when( mock.prefetchQueryEmbedding( anyString() ) )
            .thenReturn( CompletableFuture.completedFuture( Optional.of( new float[]{ 1f } ) ) );
        return mock;
    }

    public static HybridSearchService disabled() {
        final HybridSearchService mock = mock( HybridSearchService.class );
        when( mock.isEnabled() ).thenReturn( false );
        return mock;
    }

    private FakeHybridSearch() {}
}
```

Ensure `wikantik-knowledge/pom.xml` has the mockito-core test dependency (follow `wikantik-mcp/pom.xml:138` for the pattern).

Test sites then use `FakeHybridSearch.enabledReturning(List.of("Alpha", "Beta"))` instead of constructing a subclass.

- [ ] **Step 2: Run test to verify failure**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=DefaultContextRetrievalServiceTest#retrieve_populatesContributingChunksFromDenseIndex`
Expected: `contributingChunks` is empty (the existing task-9 implementation doesn't populate them yet).

- [ ] **Step 3: Implement chunk population**

Extract chunk lookup into a helper and weave it into `retrieve()`. Replace the `retrieve()` body (in the part that builds `RetrievedPage`):

```java
    @Override
    public RetrievalResult retrieve( final ContextQuery query ) {
        if ( query == null ) throw new IllegalArgumentException( "query required" );

        final Context ctx = buildContext();
        final Collection< SearchResult > bm25;
        try {
            bm25 = searchManager.findPages( query.query(), ctx );
        } catch ( final Exception e ) {
            LOG.warn( "BM25 search failed: {}", e.getMessage(), e );
            return new RetrievalResult( query.query(), List.of(), 0 );
        }
        final List< SearchResult > ordered = applyHybridAndGraphRerank( query.query(), bm25 );

        // Pre-fetch contributing chunks for the top candidates in one pass
        // so we don't round-trip the DB per page.
        final Map< String, List< RetrievedChunk > > chunksByPage =
            fetchContributingChunks( query.query(), ordered, query.chunksPerPage() );

        final PageListFilter f = query.filter();
        final List< RetrievedPage > pages = new ArrayList<>();
        for ( final SearchResult sr : ordered ) {
            final Page page = sr.getPage();
            if ( page == null ) continue;
            final String text = pageManager.getPureText( page.getName(), PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( text == null ? "" : text );
            if ( f != null && !matchesFilter( page, parsed, f ) ) continue;
            final List< RetrievedChunk > chunks = chunksByPage.getOrDefault(
                page.getName(), List.of() );
            pages.add( new RetrievedPage(
                page.getName(),
                buildUrl( page.getName() ),
                sr.getScore(),
                stringOrEmpty( parsed.metadata().get( "summary" ) ),
                stringOrNull( parsed.metadata().get( "cluster" ) ),
                stringList( parsed.metadata().get( "tags" ) ),
                chunks,
                List.of(),  // relatedPages: task 11
                page.getAuthor(),
                page.getLastModified()
            ) );
            if ( pages.size() >= query.maxPages() ) break;
        }
        return new RetrievalResult( query.query(), pages, ordered.size() );
    }

    private Map< String, List< RetrievedChunk > > fetchContributingChunks(
            final String query,
            final List< SearchResult > ordered,
            final int chunksPerPage ) {
        if ( chunkIndex == null || !chunkIndex.isReady()
                || chunkRepo == null || hybridSearch == null ) {
            return Map.of();
        }
        final Optional< float[] > embedding;
        try {
            embedding = hybridSearch.prefetchQueryEmbedding( query ).get( 2500, TimeUnit.MILLISECONDS );
        } catch ( final Exception e ) {
            LOG.debug( "Embedding fetch for chunks failed: {}", e.getMessage() );
            return Map.of();
        }
        if ( embedding.isEmpty() ) return Map.of();
        final List< ScoredChunk > topChunks = chunkIndex.topKChunks( embedding.get(), 200 );
        if ( topChunks.isEmpty() ) return Map.of();

        final Set< String > interestingPages = new HashSet<>();
        for ( final SearchResult sr : ordered ) {
            if ( sr.getPage() != null ) interestingPages.add( sr.getPage().getName() );
        }
        final Map< String, List< ScoredChunk > > grouped = new LinkedHashMap<>();
        for ( final ScoredChunk sc : topChunks ) {
            if ( !interestingPages.contains( sc.pageName() ) ) continue;
            grouped.computeIfAbsent( sc.pageName(), k -> new ArrayList<>() ).add( sc );
        }
        final List< UUID > allIds = new ArrayList<>();
        for ( final var entry : grouped.entrySet() ) {
            final List< ScoredChunk > list = entry.getValue();
            final int take = Math.min( chunksPerPage, list.size() );
            for ( int i = 0; i < take; i++ ) allIds.add( list.get( i ).chunkId() );
        }
        final List< ContentChunkRepository.MentionableChunk > loaded = chunkRepo.findByIds( allIds );
        final Map< UUID, ContentChunkRepository.MentionableChunk > byId = new HashMap<>();
        for ( final var mc : loaded ) byId.put( mc.id(), mc );

        final Map< String, List< RetrievedChunk > > out = new LinkedHashMap<>();
        for ( final var entry : grouped.entrySet() ) {
            final List< ScoredChunk > list = entry.getValue();
            final int take = Math.min( chunksPerPage, list.size() );
            final List< RetrievedChunk > pageChunks = new ArrayList<>( take );
            for ( int i = 0; i < take; i++ ) {
                final ScoredChunk sc = list.get( i );
                final ContentChunkRepository.MentionableChunk mc = byId.get( sc.chunkId() );
                if ( mc == null ) continue;
                pageChunks.add( new RetrievedChunk(
                    mc.headingPath(), mc.text(), sc.score(), List.of() ) );
            }
            out.put( entry.getKey(), pageChunks );
        }
        return out;
    }

    private Context buildContext() {
        final Page anchor = pageManager.getPage( "Main" );
        if ( anchor == null ) return null;
        // See task 11 note: engine-aware Context is wired in when the
        // service is registered as a manager.
        try {
            return com.wikantik.api.spi.Wiki.context().create( null, anchor );
        } catch ( final Exception e ) {
            return null;
        }
    }
```

Add imports:

```java
import com.wikantik.api.knowledge.RetrievedChunk;
import com.wikantik.search.hybrid.ScoredChunk;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
```

- [ ] **Step 4: Run test to verify pass**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=DefaultContextRetrievalServiceTest`
Expected: all tests pass (10 now).

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/DefaultContextRetrievalServiceTest.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/testfakes/FakeChunkVectorIndex.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/testfakes/FakeChunkRepository.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/testfakes/FakeHybridSearch.java
git commit -m "feat(knowledge): populate contributingChunks on retrieved pages"
```

---

## Task 11: Populate `relatedPages` via NodeMentionSimilarity

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java`
- Modify: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/DefaultContextRetrievalServiceTest.java`

- [ ] **Step 1: Write failing test with fake similarity**

Append to `DefaultContextRetrievalServiceTest.java`:

```java
    @Test
    void retrieve_populatesRelatedPages() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "Alpha", "---\n---\n\n", "a", new java.util.Date() );
        pm.addPage( "Beta",  "---\n---\n\n", "a", new java.util.Date() );
        pm.addPage( "Gamma", "---\n---\n\n", "a", new java.util.Date() );

        final FakeSearchManager sm = new FakeSearchManager();
        sm.setResults( java.util.List.of(
            com.wikantik.knowledge.testfakes.FakeSearchResult.of( "Alpha", 5 ) ) );

        final var similarity = new com.wikantik.knowledge.testfakes.FakeNodeMentionSimilarity();
        similarity.setRelated( "Alpha",
            java.util.List.of(
                new com.wikantik.knowledge.embedding.NodeMentionSimilarity.ScoredName( "Beta", 0.9 ),
                new com.wikantik.knowledge.embedding.NodeMentionSimilarity.ScoredName( "Gamma", 0.7 ) ) );

        final DefaultContextRetrievalService svc = new DefaultContextRetrievalService(
            sm, null, null, null, null, similarity, pm, null, "" );

        final var result = svc.retrieve( new com.wikantik.api.knowledge.ContextQuery(
            "q", 5, 3, null ) );

        assertEquals( 1, result.pages().size() );
        final var relatedPages = result.pages().get( 0 ).relatedPages();
        assertEquals( 2, relatedPages.size() );
        assertEquals( "Beta", relatedPages.get( 0 ).name() );
        assertNotNull( relatedPages.get( 0 ).reason() );
    }
```

Create `FakeNodeMentionSimilarity.java`:

```java
package com.wikantik.knowledge.testfakes;

import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity.ScoredName;

import java.util.*;

/** Scripts similarTo(name, limit) for a given page name. */
public class FakeNodeMentionSimilarity extends NodeMentionSimilarity {

    private final Map< String, List< ScoredName > > scripted = new HashMap<>();

    public FakeNodeMentionSimilarity() {
        super( null, "fake-model" );
    }

    public void setRelated( String page, List< ScoredName > neighbors ) {
        scripted.put( page, List.copyOf( neighbors ) );
    }

    @Override public boolean isReady() { return true; }
    @Override public List< ScoredName > similarTo( String name, int limit ) {
        final List< ScoredName > full = scripted.getOrDefault( name, List.of() );
        return full.size() <= limit ? full : full.subList( 0, limit );
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=DefaultContextRetrievalServiceTest#retrieve_populatesRelatedPages`
Expected: `relatedPages` is empty.

- [ ] **Step 3: Implement `relatedPages` population**

In `DefaultContextRetrievalService.retrieve()`, replace the per-page builder loop to also look up relatedPages:

```java
            final List< RetrievedChunk > chunks = chunksByPage.getOrDefault(
                page.getName(), List.of() );
            final List< RelatedPage > related = fetchRelatedPages( page.getName() );
            pages.add( new RetrievedPage(
                page.getName(),
                buildUrl( page.getName() ),
                sr.getScore(),
                stringOrEmpty( parsed.metadata().get( "summary" ) ),
                stringOrNull( parsed.metadata().get( "cluster" ) ),
                stringList( parsed.metadata().get( "tags" ) ),
                chunks,
                related,
                page.getAuthor(),
                page.getLastModified()
            ) );
```

Add the helper method:

```java
    private static final int RELATED_PAGES_LIMIT = 5;

    private List< RelatedPage > fetchRelatedPages( final String pageName ) {
        if ( similarity == null || !similarity.isReady() ) return List.of();
        final List< NodeMentionSimilarity.ScoredName > neighbors;
        try {
            neighbors = similarity.similarTo( pageName, RELATED_PAGES_LIMIT );
        } catch ( final RuntimeException e ) {
            LOG.debug( "NodeMentionSimilarity failed for '{}': {}", pageName, e.getMessage() );
            return List.of();
        }
        if ( neighbors.isEmpty() ) return List.of();
        final List< RelatedPage > out = new ArrayList<>( neighbors.size() );
        for ( final var n : neighbors ) {
            // Skip the page itself (shouldn't happen given similarTo excludes it,
            // but defensive since this runs on user input).
            if ( pageName.equals( n.name() ) ) continue;
            out.add( new RelatedPage( n.name(), describeReason( n ) ) );
        }
        return out;
    }

    private String describeReason( final NodeMentionSimilarity.ScoredName n ) {
        // Phase 1 reason: just similarity score. Cycle 2 can upgrade to
        // "shared entities: X, Y" once we resolve the contributing entities.
        return String.format( "similarity %.2f", n.score() );
    }
```

Add imports:

```java
import com.wikantik.api.knowledge.RelatedPage;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
```

- [ ] **Step 4: Run tests**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=DefaultContextRetrievalServiceTest`
Expected: all tests pass (11 now).

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/DefaultContextRetrievalServiceTest.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/testfakes/FakeNodeMentionSimilarity.java
git commit -m "feat(knowledge): populate relatedPages via NodeMentionSimilarity"
```

---

## Task 12: Register service as engine manager

**Files:**
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/ContextRetrievalServiceInitializer.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java` (remove the engine placeholder)

- [ ] **Step 1: Update the service to accept an Engine**

Replace the constructor and the `buildContext` stub in `DefaultContextRetrievalService.java`:

```java
    private final Engine engine;

    public DefaultContextRetrievalService(
            final Engine engine,
            final SearchManager searchManager,
            final HybridSearchService hybridSearch,
            final GraphRerankStep graphRerank,
            final ChunkVectorIndex chunkIndex,
            final ContentChunkRepository chunkRepo,
            final NodeMentionSimilarity similarity,
            final PageManager pageManager,
            final FrontmatterMetadataCache fmCache,
            final String publicBaseUrl ) {
        if ( engine == null ) throw new IllegalArgumentException( "engine required" );
        if ( searchManager == null ) throw new IllegalArgumentException( "searchManager required" );
        if ( pageManager == null ) throw new IllegalArgumentException( "pageManager required" );
        this.engine = engine;
        this.searchManager = searchManager;
        // ... rest unchanged
    }

    private Context buildContext() {
        final Page anchor = pageManager.getPage( "Main" );
        if ( anchor == null ) return null;
        try {
            return com.wikantik.api.spi.Wiki.context().create( engine, anchor );
        } catch ( final Exception e ) {
            LOG.debug( "Context build failed: {}", e.getMessage() );
            return null;
        }
    }
```

Update `fromEngine` to pass the engine:

```java
    public static DefaultContextRetrievalService fromEngine( final Engine engine ) {
        final PageManager pm = engine.getManager( PageManager.class );
        if ( pm == null ) return null;
        final SearchManager sm = engine.getManager( SearchManager.class );
        if ( sm == null ) return null;
        return new DefaultContextRetrievalService(
            engine, sm,
            engine.getManager( HybridSearchService.class ),
            engine.getManager( GraphRerankStep.class ),
            engine.getManager( ChunkVectorIndex.class ),
            engine.getManager( ContentChunkRepository.class ),
            engine.getManager( NodeMentionSimilarity.class ),
            pm,
            engine.getManager( FrontmatterMetadataCache.class ),
            engine.getBaseURL() );
    }
```

Update all test fake constructions (`FakeDeps.build()` and the direct `new DefaultContextRetrievalService(...)` calls in the tests) to include a non-null first `Engine` arg. The easiest fix: extract an interface-minimal `FakeEngine` test fake that implements only `getApplicationName`, `getBaseURL`, `getWikiProperties`, `getManager` — returning fixtures or null.

Add to `FakeDeps.java`:

```java
    private com.wikantik.knowledge.testfakes.FakeEngine engine = new com.wikantik.knowledge.testfakes.FakeEngine();

    public FakeDeps engine( com.wikantik.knowledge.testfakes.FakeEngine e ) {
        this.engine = e; return this;
    }
```

And pass `engine` as the first constructor arg in `FakeDeps.build()`.

Create `FakeEngine.java`:

```java
package com.wikantik.knowledge.testfakes;

import com.wikantik.api.core.Engine;
// ... plus minimal stubs throwing UnsupportedOperationException for methods
// the service never touches. Inspect the Engine interface in wikantik-api
// to get the signatures right.
```

- [ ] **Step 2: Run existing tests to ensure parity**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=DefaultContextRetrievalServiceTest,ContextRetrievalRecordsTest`
Expected: all 22+11=33 tests pass (same count, same tests).

- [ ] **Step 3: Create ContextRetrievalServiceInitializer**

Create `wikantik-knowledge/src/main/java/com/wikantik/knowledge/ContextRetrievalServiceInitializer.java`:

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
package com.wikantik.knowledge;

import com.wikantik.api.core.Engine;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.spi.Wiki;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Constructs the {@link DefaultContextRetrievalService} once the engine is
 * initialized, and registers it on the engine as the {@link ContextRetrievalService}
 * manager. Load-on-startup order is between the WikiEngine bootstrap and the
 * MCP initializers that depend on this service.
 */
public class ContextRetrievalServiceInitializer implements ServletContextListener {

    private static final Logger LOG = LogManager.getLogger( ContextRetrievalServiceInitializer.class );

    @Override
    public void contextInitialized( final ServletContextEvent sce ) {
        final ServletContext ctx = sce.getServletContext();
        final Engine engine;
        try {
            engine = Wiki.engine().find( ctx, null );
        } catch ( final Exception e ) {
            LOG.warn( "WikiEngine unavailable — ContextRetrievalService not registered: {}",
                e.getMessage() );
            return;
        }
        final DefaultContextRetrievalService svc = DefaultContextRetrievalService.fromEngine( engine );
        if ( svc == null ) {
            LOG.info( "Required managers missing — ContextRetrievalService not registered" );
            return;
        }
        if ( engine instanceof com.wikantik.WikiEngine wikiEngine ) {
            wikiEngine.setManager( ContextRetrievalService.class, svc );
            LOG.info( "ContextRetrievalService registered" );
        } else {
            LOG.warn( "Engine is not a WikiEngine; cannot setManager. ContextRetrievalService unregistered." );
        }
    }
}
```

`WikiEngine.setManager(Class<T>, T)` is at `wikantik-main/src/main/java/com/wikantik/WikiEngine.java:467`. The `Engine` API interface does not expose `setManager`, which is why we downcast to `WikiEngine`. If a cleaner registration path is preferred (e.g. promoting `setManager` to `Engine`), that's a follow-up — for this cycle the cast works.

- [ ] **Step 4: Register the listener in web.xml**

Edit `wikantik-war/src/main/webapp/WEB-INF/web.xml` — add a `<listener>` entry between the existing WikiEngine bootstrap listener and the MCP initializers:

```xml
    <listener>
        <listener-class>com.wikantik.knowledge.ContextRetrievalServiceInitializer</listener-class>
    </listener>
```

Verify ordering by grepping the file for `WikiBootstrap`, `KnowledgeMcpInitializer`, and adding between them. Listener ordering in `web.xml` matches file order.

- [ ] **Step 5: Integration smoke test (compile + local deploy)**

```bash
mvn clean install -pl wikantik-knowledge,wikantik-war -am -DskipITs -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/ContextRetrievalServiceInitializer.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java \
        wikantik-war/src/main/webapp/WEB-INF/web.xml \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/testfakes/
git commit -m "feat(knowledge): register ContextRetrievalService as engine manager"
```

---

## Task 13: End-to-end integration test

**Files:**
- Create: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/ContextRetrievalServiceIT.java`

- [ ] **Step 1: Write the integration test**

Create the test:

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
package com.wikantik.knowledge;

import com.wikantik.PostgresTestContainer;
import com.wikantik.api.knowledge.*;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test against a PostgresTestContainer with seeded chunks and
 * entity mentions. Asserts the service composes the real retrieval stack
 * correctly — BM25, dense chunks, graph similarity — on a small but
 * realistic dataset.
 *
 * <p>The BM25 side of hybrid is stubbed because SearchManager needs a
 * running Lucene index this test doesn't spin up. The dense + similarity
 * sides use real DB-backed implementations.</p>
 */
@Testcontainers( disabledWithoutDocker = true )
class ContextRetrievalServiceIT {

    private static DataSource dataSource;

    @BeforeAll
    static void init() { dataSource = PostgresTestContainer.createDataSource(); }

    @AfterEach
    void cleanUp() throws Exception {
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute( "DELETE FROM chunk_entity_mentions" );
            c.createStatement().execute( "DELETE FROM content_chunk_embeddings" );
            c.createStatement().execute( "DELETE FROM kg_content_chunks" );
            c.createStatement().execute( "DELETE FROM kg_edges" );
            c.createStatement().execute( "DELETE FROM kg_nodes" );
        }
    }

    @Test
    void retrieve_endToEnd_seededCorpus() throws Exception {
        // Seed 3 pages, 3 chunks, 2 node mentions, matching vectors.
        final UUID alphaC1 = UUID.randomUUID();
        final UUID alphaC2 = UUID.randomUUID();
        final UUID betaC1  = UUID.randomUUID();
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO kg_content_chunks (id, page_name, chunk_index, heading_path, text, "
              + "  char_count, token_count_estimate, content_hash) VALUES "
              + "('" + alphaC1 + "', 'Alpha', 0, ARRAY['Alpha','Intro'], 'alpha body one', 14, 4, 'h1'), "
              + "('" + alphaC2 + "', 'Alpha', 1, ARRAY['Alpha','Details'], 'alpha body two', 14, 4, 'h2'), "
              + "('" + betaC1  + "', 'Beta',  0, ARRAY['Beta'], 'beta body',           9, 3, 'h3')" );
            // Populate embeddings table with sample 4-dim vectors. Skip this
            // block if the schema requires specific floats — swap in a helper.
        }

        // Use production service with real chunk repo + similarity backed by the DB,
        // but stub the search path (no Lucene here).
        final var chunkRepo = new ContentChunkRepository( dataSource );
        final var similarity = new NodeMentionSimilarity( dataSource, "qwen3-embedding-0.6b" );

        // ... assemble the service using FakeEngine + FakeSearchManager
        // preloaded to return "Alpha" and "Beta" in BM25 order, plus
        // a real DB-backed ChunkVectorIndex (if available) or a fake that
        // mirrors what the DB contains.

        // Assert: retrieve("alpha") returns Alpha first with at least one
        // contributing chunk pulled from the real chunks table.
        fail( "TODO: assemble service and assert — see notes in step 2" );
    }
}
```

**Note:** Full end-to-end IT with live Lucene + dense index is out of
scope for cycle 1. This test validates the *service* against real
Postgres data via the chunk repo + node mention similarity path. A more
thorough IT can land in cycle 2 when there's a running MCP tool to
exercise the full stack via HTTP.

- [ ] **Step 2: Flesh out the TODO**

Fill in the missing service assembly in the test:
- Use the real `ContentChunkRepository( dataSource )` and real
  `NodeMentionSimilarity( dataSource, "qwen3-embedding-0.6b" )`
- Use a `FakeSearchManager` returning an in-memory BM25 list of the 3
  seeded pages.
- Use a `FakeChunkVectorIndex` that returns the seeded chunk IDs as
  top-K results — this bridges the gap since the Postgres-backed
  `InMemoryChunkVectorIndex` isn't trivial to bootstrap from test data.
- Assert that `retrieve(ContextQuery("alpha body", 5, 3, null))` returns
  Alpha first, and its `contributingChunks` list is non-empty with
  heading paths pulled from the real DB.

Concretely, at the bottom of the test method:

```java
        final var chunkIndex = new com.wikantik.knowledge.testfakes.FakeChunkVectorIndex();
        chunkIndex.setEnabled( true );
        chunkIndex.setDim( 1024 );
        chunkIndex.setTopK( java.util.List.of(
            new com.wikantik.search.hybrid.ScoredChunk( alphaC1, "Alpha", 0.9 ),
            new com.wikantik.search.hybrid.ScoredChunk( betaC1,  "Beta",  0.7 ) ) );

        final var searchManager = new com.wikantik.knowledge.testfakes.FakeSearchManager();
        searchManager.setResults( java.util.List.of(
            com.wikantik.knowledge.testfakes.FakeSearchResult.of( "Alpha", 5 ),
            com.wikantik.knowledge.testfakes.FakeSearchResult.of( "Beta", 3 ) ) );

        final var pageManager = new com.wikantik.knowledge.testfakes.FakePageManager();
        pageManager.addPage( "Alpha", "---\nsummary: a\n---\nbody",
            "seed", new java.util.Date() );
        pageManager.addPage( "Beta", "---\nsummary: b\n---\nbody",
            "seed", new java.util.Date() );

        final var hybrid = com.wikantik.knowledge.testfakes.FakeHybridSearch.enabledReturning(
            java.util.List.of( "Alpha", "Beta" ) );

        final DefaultContextRetrievalService svc = new DefaultContextRetrievalService(
            new com.wikantik.knowledge.testfakes.FakeEngine(),
            searchManager, hybrid, null, chunkIndex, chunkRepo, similarity,
            pageManager, null, "https://wiki.example" );

        final var result = svc.retrieve( new ContextQuery( "alpha body", 5, 3, null ) );
        assertEquals( 2, result.pages().size() );
        assertEquals( "Alpha", result.pages().get( 0 ).name() );
        assertFalse( result.pages().get( 0 ).contributingChunks().isEmpty() );
        assertEquals( java.util.List.of( "Alpha", "Intro" ),
            result.pages().get( 0 ).contributingChunks().get( 0 ).headingPath() );
```

Remove the trailing `fail(...)`.

- [ ] **Step 3: Run the IT**

Run: `mvn -pl wikantik-knowledge -am -q test -Dtest=ContextRetrievalServiceIT`
Expected: passes (requires Docker for Postgres testcontainer).

- [ ] **Step 4: Commit**

```bash
git add wikantik-knowledge/src/test/java/com/wikantik/knowledge/ContextRetrievalServiceIT.java
git commit -m "test(knowledge): e2e integration test over seeded postgres corpus"
```

---

## Task 14: Refactor `SearchResource` to use the service

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/SearchResource.java`

- [ ] **Step 1: Baseline existing behavior**

Read the existing `/api/search` tests to understand the response shape contract:

```bash
find wikantik-rest/src/test wikantik-it-tests -name "*SearchResource*" -o -name "*Search*IT*" 2>/dev/null | head
```

Run any existing test(s) to confirm baseline:

```bash
mvn -pl wikantik-rest -am -q test -Dtest=SearchResource*
```

Expected: passes (take note of the test names to ensure they still pass after refactor).

- [ ] **Step 2: Replace `applyHybridRerank` logic with service call**

In `SearchResource.java`, replace the body of `doGet` from the comment `"// Hybrid path: when HybridSearchService is present..."` through the end of the method. Use the `ContextRetrievalService` for retrieval and keep the JSON envelope shape identical:

```java
        // Delegate retrieval to ContextRetrievalService. The service owns
        // BM25 → hybrid rerank → graph rerank → page shaping.
        final ContextRetrievalService ctxService = engine.getManager( ContextRetrievalService.class );
        if ( ctxService == null ) {
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "ContextRetrievalService not configured" );
            return;
        }
        final RetrievalResult retrieval;
        try {
            retrieval = ctxService.retrieve( new ContextQuery( query, Math.min( limit, 20 ), 3, null ) );
        } catch ( final RuntimeException e ) {
            LOG.error( "Retrieval failed for '{}': {}", query, e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "retrieval failed: " + e.getMessage() );
            return;
        }

        final List< Map< String, Object > > resultList = new ArrayList<>();
        for ( final RetrievedPage p : retrieval.pages() ) {
            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "name", p.name() );
            entry.put( "score", p.score() );
            if ( !p.summary().isEmpty() ) entry.put( "summary", p.summary() );
            if ( !p.tags().isEmpty() ) entry.put( "tags", p.tags() );
            if ( p.cluster() != null ) entry.put( "cluster", p.cluster() );
            if ( resultList.size() < limit ) {
                resultList.add( entry );
            }
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "query", query );
        result.put( "results", resultList );
        result.put( "total", resultList.size() );

        sendJson( response, result );
```

Delete the now-unused private `applyHybridRerank`, `awaitEmbedding`, and
`DenseOnlySearchResult` members. Remove related imports.

**Map the remaining fields in `SearchResource`** — `RetrievedPage` already carries `author` and `lastModified` (from Task 3); wire them into the JSON envelope to preserve the existing REST contract:

```java
            if ( p.author() != null ) entry.put( "author", p.author() );
            if ( p.lastModified() != null ) entry.put( "lastModified", p.lastModified() );
            // contexts: previously Lucene highlight fragments; now chunk text.
            // Intentional change — chunks are higher-signal than Lucene snippets.
            if ( !p.contributingChunks().isEmpty() ) {
                entry.put( "contexts", p.contributingChunks().stream()
                    .map( com.wikantik.api.knowledge.RetrievedChunk::text ).toList() );
            }
```

- [ ] **Step 3: Run the REST tests**

Run: `mvn -pl wikantik-rest -am -q test -Dtest=SearchResource*`
Expected: all existing tests pass.

- [ ] **Step 4: Re-run the full wikantik-knowledge suite to catch record-change regressions**

Run: `mvn -pl wikantik-knowledge -am -q test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/SearchResource.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/RetrievedPage.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/
git commit -m "refactor(rest): route /api/search through ContextRetrievalService"
```

---

## Task 15: Refactor `SearchWikiTool` to use the service

**Files:**
- Modify: `wikantik-tools/src/main/java/com/wikantik/tools/SearchWikiTool.java`

- [ ] **Step 1: Check existing test for SearchWikiTool**

```bash
cat wikantik-tools/src/test/java/com/wikantik/tools/SearchWikiToolTest.java | head -60
mvn -pl wikantik-tools -am -q test -Dtest=SearchWikiToolTest
```

Expected: passes. Note the current assertions around shape (name, url, score, snippet).

- [ ] **Step 2: Replace `applyHybridRerank` logic**

In `SearchWikiTool.execute()`, delete the `sm.findPages(...)` / `applyHybridRerank(...)` / `ResultShaper.snippet(...)` path and replace with:

```java
    Map< String, Object > execute( final String query, final int maxResults, final HttpServletRequest request ) {
        final int clamped = clampLimit( maxResults );
        final ContextRetrievalService ctxService = engine.getManager( ContextRetrievalService.class );
        if ( ctxService == null ) {
            final Map< String, Object > error = ResultShaper.orderedMap();
            error.put( "query", query );
            error.put( "results", List.of() );
            error.put( "total", 0 );
            error.put( "error", "ContextRetrievalService not configured" );
            return error;
        }
        final RetrievalResult retrieval;
        try {
            retrieval = ctxService.retrieve( new ContextQuery( query, clamped, 3, null ) );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Tool-server search failed for query '{}': {}", query, e.getMessage() );
            final Map< String, Object > error = ResultShaper.orderedMap();
            error.put( "query", query );
            error.put( "results", List.of() );
            error.put( "total", 0 );
            error.put( "error", "search failed: " + e.getMessage() );
            return error;
        }

        final String publicBaseUrl = config.publicBaseUrl();
        final List< Map< String, Object > > shaped = new ArrayList<>();
        for ( final RetrievedPage p : retrieval.pages() ) {
            if ( shaped.size() >= clamped ) break;
            final Map< String, Object > entry = ResultShaper.orderedMap();
            entry.put( "name", p.name() );
            entry.put( "url", ResultShaper.citationUrl( p.name(), request, publicBaseUrl ) );
            entry.put( "score", p.score() );
            if ( !p.summary().isEmpty() ) entry.put( "summary", p.summary() );
            if ( !p.tags().isEmpty() ) entry.put( "tags", p.tags() );
            if ( p.cluster() != null ) entry.put( "cluster", p.cluster() );
            // Snippet from top contributing chunk (replaces ResultShaper.snippet).
            if ( !p.contributingChunks().isEmpty() ) {
                final RetrievedChunk c0 = p.contributingChunks().get( 0 );
                final String snippet = c0.text().length() > 320
                    ? c0.text().substring( 0, 320 ) + "…"
                    : c0.text();
                entry.put( "snippet", snippet );
            }
            if ( p.lastModified() != null ) {
                entry.put( "lastModified", p.lastModified().toInstant().toString() );
            }
            if ( p.author() != null ) entry.put( "author", p.author() );
            shaped.add( entry );
        }
        final Map< String, Object > out = ResultShaper.orderedMap();
        out.put( "query", query );
        out.put( "results", shaped );
        out.put( "total", shaped.size() );
        return out;
    }
```

Delete `applyHybridRerank`, `safePureText`, `DenseOnlySearchResult`,
`createContext` (if only used locally). Keep `clampLimit`.

- [ ] **Step 3: Update `SearchWikiToolTest`**

Existing tests probably mocked `SearchManager.findPages` and/or
`HybridSearchService`. Rewrite them to mock `ContextRetrievalService`
instead:

```java
// In SearchWikiToolTest setup:
    ContextRetrievalService ctxService = mock( ContextRetrievalService.class );
    when( engine.getManager( ContextRetrievalService.class ) ).thenReturn( ctxService );
    when( ctxService.retrieve( any( ContextQuery.class ) ) ).thenReturn(
        new RetrievalResult( "q", List.of( new RetrievedPage(
            "Alpha", "", 5.0, "alpha summary", null, List.of(),
            List.of( new RetrievedChunk( List.of( "Alpha" ), "alpha body", 0.9, List.of() ) ),
            List.of(), null, null ) ), 1 ) );
```

Adjust existing assertions: snippet content comes from the injected chunk, not from pureText anymore.

- [ ] **Step 4: Run SearchWikiToolTest**

Run: `mvn -pl wikantik-tools -am -q test -Dtest=SearchWikiToolTest`
Expected: passes.

- [ ] **Step 5: Commit**

```bash
git add wikantik-tools/src/main/java/com/wikantik/tools/SearchWikiTool.java \
        wikantik-tools/src/test/java/com/wikantik/tools/SearchWikiToolTest.java
git commit -m "refactor(tools): route SearchWikiTool through ContextRetrievalService"
```

---

## Task 16: Full build + manual smoke

**Files:** none created

- [ ] **Step 1: Full multi-module build, unit tests only**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS across all modules.

- [ ] **Step 2: Integration test pass (sequential, per CLAUDE.md)**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: BUILD SUCCESS. Note any new failures — these are pre-existing ITs that may touch search; fix any parity regressions before proceeding.

- [ ] **Step 3: Deploy locally and smoke test /api/search**

```bash
bin/deploy-local.sh
tomcat/tomcat-11/bin/startup.sh
sleep 10
curl -s "http://localhost:8080/api/search?q=hybrid%20retrieval&limit=5" | jq '.results | map({name, score, summary})'
```

Expected: same top results as before the refactor, same score ordering.

Compare against a baseline captured before the refactor. If the results shift materially, investigate — the service should be behavior-identical to the old inline path. Particularly check the `contexts` field: it was Lucene fragments before, now it's chunk text — note the shift is intentional, but confirm the shape doesn't break existing UI.

- [ ] **Step 4: Smoke test /tools/search_wiki**

```bash
curl -s -H "Authorization: Bearer <dev-key>" \
    "http://localhost:8080/tools/v1/search_wiki?q=hybrid%20retrieval&limit=3" | jq
```

Expected: same top results, `snippet` populated from chunk text.

- [ ] **Step 5: Close the cycle — update the spec's "Rollout order"**

Mark cycle 1 complete in the spec's rollout list. Edit `docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md`, change the cycle 1 heading to `**Cycle 1 — ContextRetrievalService extraction. ✓**`.

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md
git commit -m "chore(spec): mark cycle 1 complete — ContextRetrievalService"
```

---

## Summary

At cycle 1 complete:

- `ContextRetrievalService` interface + 8 records in `wikantik-api`.
- `DefaultContextRetrievalService` in `wikantik-knowledge` wraps the retrieval stack.
- Service is registered as an engine manager via a servlet context listener.
- `SearchResource` (REST `/api/search`) routes through the service.
- `SearchWikiTool` (OpenAPI `/tools/search_wiki`) routes through the service.
- 33+ unit tests + 1 integration test exercise the full contract.
- No MCP wire changes — cycle 2 picks up from here to add the agent-facing tools.

Next cycle: add `retrieve_context`, `get_page`, `list_pages`, `list_metadata_values` as MCP tools on `/knowledge-mcp` alongside the existing 6 KG tools.
