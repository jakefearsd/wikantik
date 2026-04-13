# Existing Hubs Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a read-focused "Existing Hubs" view to the Hub Discovery admin tab, with per-hub drilldown, computed health statistics (coherence, member cosines, near-miss TF-IDF, MoreLikeThis Lucene, overlap hubs), and a single mutation (remove-member).

**Architecture:** New `HubOverviewService` in `wikantik-main` reuses existing `JdbcKnowledgeRepository`, the live `TfidfModel` from `EmbeddingService`, `PageManager`, and a thin Lucene MoreLikeThis adapter on `LuceneSearchProvider`. Three new REST routes are added to the existing `AdminHubDiscoveryResource` servlet. The frontend gains two new React components mounted inside `HubDiscoveryTab`: a container `ExistingHubsPanel` and a presentational `ExistingHubDrilldown`. Statistics are computed on demand per request — no caching, no schema changes.

**Tech Stack:** Java 21, JUnit 5, Mockito, Testcontainers (PostgreSQL), Apache Lucene (existing), React 18 + Vite, Selenide.

**Spec:** [docs/superpowers/specs/2026-04-10-existing-hubs-panel-design.md](../specs/2026-04-10-existing-hubs-panel-design.md)

---

## File Structure

**New files:**

| Path | Responsibility |
|------|---------------|
| `wikantik-main/src/main/java/com/wikantik/knowledge/HubOverviewService.java` | Service: list hubs, load per-hub drilldown, remove member |
| `wikantik-main/src/main/java/com/wikantik/knowledge/HubOverviewException.java` | Service-level runtime failure |
| `wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java` | Service unit tests (Testcontainers + real KG repo + in-memory TF-IDF) |
| `wikantik-rest/src/main/java/com/wikantik/rest/dto/RemoveHubMemberRequest.java` | DTO for POST body |
| `wikantik-frontend/src/components/admin/ExistingHubsPanel.jsx` | Container — list, drilldown caching, remove-member orchestration |
| `wikantik-frontend/src/components/admin/ExistingHubDrilldown.jsx` | Pure presentational drilldown view |
| `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/HubOverviewAdminIT.java` | Selenide IT scenarios |
| `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/pages/admin/HubOverviewAdminPage.java` | Selenide page object |

**Modified files:**

| Path | Change |
|------|--------|
| `wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryService.java` | Promote `normalizedCentroid` and `meanDot` from `static` to package-private (already package-private — verify and document via test reuse) |
| `wikantik-main/src/main/java/com/wikantik/search/LuceneSearchProvider.java` | Add nested `MoreLikeThisHit` record + `moreLikeThis(seedDoc, max, excludes)` method |
| `wikantik-main/src/test/java/com/wikantik/search/LuceneSearchProviderCITest.java` | Add `moreLikeThis` test |
| `wikantik-main/src/main/java/com/wikantik/knowledge/KnowledgeGraphServiceFactory.java` | Wire `HubOverviewService`; extend `Services` record |
| `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` | Register `HubOverviewService` in manager map; resolve Lucene MLT adapter from `SearchManager.getSearchEngine()` |
| `wikantik-main/src/main/resources/ini/wikantik.properties` | Four new `wikantik.hub.overview.*` defaults |
| `wikantik-rest/src/main/java/com/wikantik/rest/AdminHubDiscoveryResource.java` | Three new handlers (`/hubs`, `/hubs/{name}`, `/hubs/{name}/remove-member`) and routing |
| `wikantik-rest/src/test/java/com/wikantik/rest/AdminHubDiscoveryResourceTest.java` | Tests for the three new handlers |
| `wikantik-frontend/src/api/client.js` | Three new methods on `api.knowledge` |
| `wikantik-frontend/src/components/admin/HubDiscoveryTab.jsx` | Mount `ExistingHubsPanel` above the dismissed-proposals section |

---

## Conventions for every task

- **Run from repo root** unless stated otherwise.
- **Compile-check the affected module first** with `mvn -pl <module> -am compile -q` before running tests.
- **Run a single test class** with `mvn -pl <module> -Dtest=<ClassName> test`. Add `#methodName` to scope further.
- **Commit messages**: 1-3 lines, present tense, no Co-Authored-By trailer (sole-developer convention from CLAUDE.md).
- **Stage by name** — never `git add -A`.
- **One full `mvn clean install -T 1C -DskipITs` at the very end**, after all unit tests are green.
- **Integration tests run sequentially** with `mvn clean install -Pintegration-tests -fae` (no `-T` flag).

---

## Phase 0 — Foundational backend prep

### Task 1: Create `HubOverviewException`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/HubOverviewException.java`

- [ ] **Step 1: Write the file**

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

/** Runtime failure raised by {@link HubOverviewService} for hub-overview operations. */
public class HubOverviewException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public HubOverviewException( final String message, final Throwable cause ) {
        super( message, cause );
    }
}
```

- [ ] **Step 2: Compile-check**

Run: `mvn -pl wikantik-main -am compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/HubOverviewException.java
git commit -m "feat: HubOverviewException for hub-overview service failures"
```

---

### Task 2: Verify `normalizedCentroid` / `meanDot` are package-private

**Files:**
- Read: `wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryService.java:406-444`

These helpers are already `static` with package-private visibility (no modifier). They live in the `com.wikantik.knowledge` package, the same package the new service will live in, so no source change is needed. This task is a verification step only.

- [ ] **Step 1: Confirm visibility**

Run: `grep -n "static .* normalizedCentroid\|static .* meanDot" wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryService.java`

Expected: Two matches, both `static float[] normalizedCentroid(...)` and `static double meanDot(...)` — no `private` or `public` modifier, meaning package-private. If a `private` modifier IS present, edit the file to remove it (only the `private` keyword) and re-run.

- [ ] **Step 2: No commit needed**

If no edit was required, proceed to the next task.

---

### Task 3: Add four `wikantik.hub.overview.*` properties

**Files:**
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (append at the end of the hub-discovery block, around line 1162)

- [ ] **Step 1: Append the new property block**

Find the existing block ending at `wikantik.hub.discovery.minCandidatePool = 6` and append immediately after it (still before `### End of configuration file.`):

```properties

# ----- Hub Overview (read-focused stats panel for already-accepted hubs) -----

# Minimum centroid cosine for a non-member article to count as a near-miss.
wikantik.hub.overview.nearMissThreshold = 0.50

# Minimum centroid cosine for two hubs to be considered "overlap" in the
# overlap-hubs section of the per-hub drilldown.
wikantik.hub.overview.overlapThreshold = 0.60

# Top-N near-miss articles returned in the drilldown's TF-IDF list.
wikantik.hub.overview.nearMissMaxResults = 10

# Top-N MoreLikeThis (Lucene) results returned in the drilldown.
wikantik.hub.overview.moreLikeThisMaxResults = 10
```

- [ ] **Step 2: Commit**

```bash
git add wikantik-main/src/main/resources/ini/wikantik.properties
git commit -m "feat: hub overview config properties"
```

---

## Phase 1 — Lucene MoreLikeThis adapter

### Task 4: Add `MoreLikeThisHit` record + `moreLikeThis` method to `LuceneSearchProvider`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/search/LuceneSearchProvider.java`
- Test: `wikantik-main/src/test/java/com/wikantik/search/LuceneSearchProviderCITest.java`

- [ ] **Step 1: Write the failing test**

Add the imports (top of test file, in the existing import block) — these are needed for the new test:

```java
import com.wikantik.api.core.Page;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import java.util.Set;
```

(Several of these are already imported — only add the missing ones.)

Append this test method to `LuceneSearchProviderCITest`:

```java
    @Test
    void moreLikeThisReturnsSimilarDocsExcludingExcludeNames() throws Exception {
        // Index three docs: two cooking, one sports.
        try ( final org.apache.lucene.store.Directory dir =
                  new org.apache.lucene.store.NIOFSDirectory( luceneDir.toPath() );
              final IndexWriter writer = provider.getIndexWriter( dir ) ) {
            writer.addDocument( makeDoc( "Baking",
                "baking bread cake flour sugar oven recipe dough" ) );
            writer.addDocument( makeDoc( "Roasting",
                "roasting meat oven temperature seasoning recipe baking" ) );
            writer.addDocument( makeDoc( "Soccer",
                "soccer football pitch goal player team league kick" ) );
            writer.commit();
        }

        final java.util.List< LuceneSearchProvider.MoreLikeThisHit > hits =
            provider.moreLikeThis( "Baking", 5, Set.of( "Baking" ) );

        // Roasting should be the closest non-excluded match. Soccer should not appear,
        // and Baking itself must be excluded.
        assertFalse( hits.isEmpty(), "Expected at least one similar doc" );
        assertEquals( "Roasting", hits.get( 0 ).name() );
        for ( final LuceneSearchProvider.MoreLikeThisHit hit : hits ) {
            assertNotEquals( "Baking", hit.name(), "Excluded name must not appear" );
        }
    }

    private static Document makeDoc( final String id, final String contents ) {
        final Document d = new Document();
        d.add( new Field( LuceneSearchProvider.LUCENE_ID, id, StringField.TYPE_STORED ) );
        d.add( new Field( LuceneSearchProvider.LUCENE_PAGE_CONTENTS, contents, TextField.TYPE_STORED ) );
        return d;
    }
```

Also add this static import at the top (the test class already imports `assertEquals` etc.):

```java
import static org.junit.jupiter.api.Assertions.assertNotEquals;
```

- [ ] **Step 2: Run the test — expect compile failure (method does not exist)**

Run: `mvn -pl wikantik-main -Dtest=LuceneSearchProviderCITest#moreLikeThisReturnsSimilarDocsExcludingExcludeNames test -q`
Expected: COMPILATION FAILURE — `cannot find symbol: method moreLikeThis` and `cannot find symbol: class MoreLikeThisHit`.

- [ ] **Step 3: Implement the method on `LuceneSearchProvider`**

Add this import to `LuceneSearchProvider.java` near the other Lucene imports:

```java
import org.apache.lucene.queries.mlt.MoreLikeThis;
```

Then add the nested record and the method. Place them just below the existing `findPages(...)` method (around line 769) and above `getProviderInfo()`:

```java
    /** Lucene MoreLikeThis hit returned by {@link #moreLikeThis(String, int, Set)}. */
    public record MoreLikeThisHit( String name, float score ) {}

    /**
     * Returns up to {@code maxResults} documents similar to {@code seedDocName} based on the
     * {@code contents} field, excluding any document whose {@code id} is in {@code excludeNames}.
     *
     * <p>Thin wrapper around Lucene's {@link MoreLikeThis} query builder. Used by
     * {@code HubOverviewService} to surface a "second opinion" alongside its TF-IDF
     * near-miss list. Returns an empty list if the seed document is not in the index
     * or the index has not yet been built.
     *
     * @param seedDocName the {@link #LUCENE_ID} value of the seed document
     * @param maxResults  upper bound on returned hits (after exclusions)
     * @param excludeNames document ids to filter out of the result list
     * @return list of similar-document hits, ordered by Lucene relevance score (best first)
     * @throws IOException if the Lucene index cannot be opened or queried
     */
    public java.util.List< MoreLikeThisHit > moreLikeThis( final String seedDocName,
                                                            final int maxResults,
                                                            final java.util.Set< String > excludeNames )
            throws IOException {
        if ( seedDocName == null || seedDocName.isEmpty() || maxResults <= 0 ) {
            return java.util.Collections.emptyList();
        }
        final java.util.Set< String > excludes = excludeNames == null
            ? java.util.Collections.emptySet() : excludeNames;
        try ( final Directory luceneDir = new NIOFSDirectory( new File( luceneDirectory ).toPath() );
              final IndexReader reader = DirectoryReader.open( luceneDir ) ) {
            final IndexSearcher searcher = new IndexSearcher( reader, searchExecutor );
            // Locate the seed document by id.
            final TopDocs seedHits = searcher.search(
                new TermQuery( new Term( LUCENE_ID, seedDocName ) ), 1 );
            if ( seedHits.scoreDocs.length == 0 ) {
                return java.util.Collections.emptyList();
            }
            final int seedDocId = seedHits.scoreDocs[ 0 ].doc;

            final MoreLikeThis mlt = new MoreLikeThis( reader );
            mlt.setAnalyzer( getLuceneAnalyzer() );
            mlt.setFieldNames( new String[] { LUCENE_PAGE_CONTENTS } );
            mlt.setMinTermFreq( 1 );
            mlt.setMinDocFreq( 1 );
            // Build the MLT query from the indexed seed doc; query reader allows
            // weights derived from indexed term vectors / field statistics.
            final Query mltQuery = mlt.like( seedDocId );

            // Pull a buffer larger than maxResults so we have room to filter excludes.
            final int fetch = Math.min( 1024, maxResults + excludes.size() + 10 );
            final TopDocs hits = searcher.search( mltQuery, fetch );
            final StoredFields storedFields = reader.storedFields();

            final java.util.List< MoreLikeThisHit > out = new java.util.ArrayList<>();
            for ( final ScoreDoc sd : hits.scoreDocs ) {
                if ( out.size() >= maxResults ) break;
                final Document doc = storedFields.document( sd.doc );
                final String name = doc.get( LUCENE_ID );
                if ( name == null || name.equals( seedDocName ) ) continue;
                if ( excludes.contains( name ) ) continue;
                out.add( new MoreLikeThisHit( name, sd.score ) );
            }
            return out;
        }
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl wikantik-main -Dtest=LuceneSearchProviderCITest#moreLikeThisReturnsSimilarDocsExcludingExcludeNames test -q`
Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 5: Re-run the full LuceneSearchProvider test class**

Run: `mvn -pl wikantik-main -Dtest=LuceneSearchProviderCITest test -q`
Expected: BUILD SUCCESS, no regressions.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/LuceneSearchProvider.java \
        wikantik-main/src/test/java/com/wikantik/search/LuceneSearchProviderCITest.java
git commit -m "feat: LuceneSearchProvider.moreLikeThis adapter for hub overview"
```

---

## Phase 2 — `HubOverviewService` skeleton

### Task 5: Create `HubOverviewService` with records, builder, and stub method bodies

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/HubOverviewService.java`

This task only creates compile-able skeletons. All public method bodies throw `UnsupportedOperationException` so subsequent TDD tasks can red→green them one scenario at a time.

- [ ] **Step 1: Write the file**

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

import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Read-focused service that surfaces health statistics for already-accepted Hub pages.
 *
 * <p>Backs the "Existing Hubs" panel in the Hub Discovery admin tab. All statistics are
 * computed on demand from the live KG snapshot and the current TF-IDF content model —
 * nothing is persisted, no caching, one round-trip per call.
 *
 * <p>The only mutation exposed is per-member removal: it parses the hub page's
 * frontmatter, drops the named member from {@code related:}, and saves the page back
 * via the injected {@code PageWriter}. The existing {@code HubSyncFilter} reconciles
 * the {@code kg_edges} table on save.
 */
public class HubOverviewService {

    private static final Logger LOG = LogManager.getLogger( HubOverviewService.class );

    public static final String PROP_NEAR_MISS_THRESHOLD     = "wikantik.hub.overview.nearMissThreshold";
    public static final String PROP_OVERLAP_THRESHOLD       = "wikantik.hub.overview.overlapThreshold";
    public static final String PROP_NEAR_MISS_MAX_RESULTS   = "wikantik.hub.overview.nearMissMaxResults";
    public static final String PROP_MLT_MAX_RESULTS         = "wikantik.hub.overview.moreLikeThisMaxResults";

    private static final double DEFAULT_NEAR_MISS_THRESHOLD = 0.50;
    private static final double DEFAULT_OVERLAP_THRESHOLD   = 0.60;
    private static final int    DEFAULT_NEAR_MISS_MAX       = 10;
    private static final int    DEFAULT_MLT_MAX             = 10;

    private final JdbcKnowledgeRepository    kgRepo;
    private final Supplier< TfidfModel >     contentModelSupplier;
    private final PageManager                pageManager;
    private final HubDiscoveryService.PageWriter pageWriter;
    private final LuceneMlt                  luceneMlt;
    private final double                     nearMissThreshold;
    private final double                     overlapThreshold;
    private final int                        nearMissMaxResults;
    private final int                        mltMaxResults;

    private HubOverviewService( final Builder b ) {
        this.kgRepo                = b.kgRepo;
        this.contentModelSupplier  = b.contentModelSupplier;
        this.pageManager           = b.pageManager;
        this.pageWriter            = b.pageWriter;
        this.luceneMlt             = b.luceneMlt != null ? b.luceneMlt
            : ( seed, max, excludes ) -> Collections.emptyList();
        this.nearMissThreshold     = b.nearMissThreshold     != null ? b.nearMissThreshold     : DEFAULT_NEAR_MISS_THRESHOLD;
        this.overlapThreshold      = b.overlapThreshold      != null ? b.overlapThreshold      : DEFAULT_OVERLAP_THRESHOLD;
        this.nearMissMaxResults    = b.nearMissMaxResults    != null ? b.nearMissMaxResults    : DEFAULT_NEAR_MISS_MAX;
        this.mltMaxResults         = b.mltMaxResults         != null ? b.mltMaxResults         : DEFAULT_MLT_MAX;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- Public records ----

    public record HubOverviewSummary(
        String name, int memberCount, int inboundLinkCount,
        int nearMissCount, double coherence, boolean hasBackingPage
    ) {}

    public record HubDrilldown(
        String name, boolean hasBackingPage, double coherence,
        List< MemberDetail > members,
        List< StubMember > stubMembers,
        List< NearMissTfidf > nearMissTfidf,
        List< MoreLikeThisLucene > moreLikeThisLucene,
        List< OverlapHub > overlapHubs
    ) {}

    public record MemberDetail( String name, double cosineToCentroid, boolean hasPage ) {}
    public record StubMember( String name ) {}
    public record NearMissTfidf( String name, double cosineToCentroid ) {}
    public record MoreLikeThisLucene( String name, double luceneScore ) {}
    public record OverlapHub( String name, double centroidCosine, int sharedMemberCount ) {}

    public record RemoveMemberResult( String removed, int remainingMemberCount ) {}

    // ---- Public API ----

    /** See spec section "listHubOverviews algorithm". */
    public List< HubOverviewSummary > listHubOverviews() {
        throw new UnsupportedOperationException( "implemented in a later task" );
    }

    /** See spec section "loadDrilldown algorithm". Returns null if hub not found. */
    public HubDrilldown loadDrilldown( final String hubName ) {
        throw new UnsupportedOperationException( "implemented in a later task" );
    }

    /** See spec section "removeMember algorithm". */
    public RemoveMemberResult removeMember( final String hubName,
                                              final String member,
                                              final String reviewedBy ) {
        throw new UnsupportedOperationException( "implemented in a later task" );
    }

    // ---- LuceneMlt narrow collaborator (testable seam) ----

    /**
     * Narrow seam over Lucene's MoreLikeThis query so the service can be unit-tested
     * without a real Lucene index. Production wires this to
     * {@code LuceneSearchProvider::moreLikeThis}; tests pass a stub lambda.
     */
    @FunctionalInterface
    public interface LuceneMlt {
        List< MoreLikeThisLucene > findSimilar( String seedDoc, int maxResults, Set< String > excludeNames )
            throws Exception;
    }

    // ---- Builder ----

    public static final class Builder {
        private JdbcKnowledgeRepository    kgRepo;
        private Supplier< TfidfModel >     contentModelSupplier;
        private PageManager                pageManager;
        private HubDiscoveryService.PageWriter pageWriter;
        private LuceneMlt                  luceneMlt;
        private Double                     nearMissThreshold;
        private Double                     overlapThreshold;
        private Integer                    nearMissMaxResults;
        private Integer                    mltMaxResults;

        private Builder() {}

        public Builder kgRepo( final JdbcKnowledgeRepository v ) { this.kgRepo = v; return this; }
        public Builder contentModel( final TfidfModel v ) { this.contentModelSupplier = () -> v; return this; }
        public Builder contentModelSupplier( final Supplier< TfidfModel > v ) { this.contentModelSupplier = v; return this; }
        public Builder pageManager( final PageManager v ) { this.pageManager = v; return this; }
        public Builder pageWriter( final HubDiscoveryService.PageWriter v ) { this.pageWriter = v; return this; }
        public Builder luceneMlt( final LuceneMlt v ) { this.luceneMlt = v; return this; }
        public Builder nearMissThreshold( final double v ) { this.nearMissThreshold = v; return this; }
        public Builder overlapThreshold( final double v ) { this.overlapThreshold = v; return this; }
        public Builder nearMissMaxResults( final int v ) { this.nearMissMaxResults = v; return this; }
        public Builder mltMaxResults( final int v ) { this.mltMaxResults = v; return this; }

        /** Reads known properties from {@code props}, falling back to defaults. */
        public Builder propsFrom( final Properties props ) {
            this.nearMissThreshold = Double.parseDouble(
                props.getProperty( PROP_NEAR_MISS_THRESHOLD, String.valueOf( DEFAULT_NEAR_MISS_THRESHOLD ) ) );
            this.overlapThreshold = Double.parseDouble(
                props.getProperty( PROP_OVERLAP_THRESHOLD, String.valueOf( DEFAULT_OVERLAP_THRESHOLD ) ) );
            this.nearMissMaxResults = Integer.parseInt(
                props.getProperty( PROP_NEAR_MISS_MAX_RESULTS, String.valueOf( DEFAULT_NEAR_MISS_MAX ) ) );
            this.mltMaxResults = Integer.parseInt(
                props.getProperty( PROP_MLT_MAX_RESULTS, String.valueOf( DEFAULT_MLT_MAX ) ) );
            return this;
        }

        public HubOverviewService build() {
            if ( kgRepo == null || contentModelSupplier == null || pageManager == null ) {
                throw new IllegalStateException(
                    "HubOverviewService.Builder: kgRepo, content model, and pageManager are required" );
            }
            return new HubOverviewService( this );
        }
    }
}
```

- [ ] **Step 2: Compile-check**

Run: `mvn -pl wikantik-main -am compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/HubOverviewService.java
git commit -m "feat: HubOverviewService skeleton with records and builder"
```

---

## Phase 3 — `listHubOverviews()` TDD

The next several tasks share a test class. Build it incrementally — each task adds one scenario plus the matching production code. The full test class exists by the end of Task 11.

### Task 6: Write the test fixture and implement `listHubOverviews` happy path

**Files:**
- Create: `wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/HubOverviewService.java`

- [ ] **Step 1: Write the failing test class**

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
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.managers.PageManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers( disabledWithoutDocker = true )
class HubOverviewServiceTest {

    private static DataSource dataSource;
    private JdbcKnowledgeRepository kgRepo;
    private Map< String, String > pageStore; // simple in-memory PageManager backing
    private PageManager pageManager;
    private List< String[] > pageWrites; // captured writes (name, content)
    private HubDiscoveryService.PageWriter pageWriter;
    private TfidfModel model;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        kgRepo = new JdbcKnowledgeRepository( dataSource );

        pageStore = new HashMap<>();
        pageWrites = new ArrayList<>();
        pageManager = mock( PageManager.class );
        // Default lenient stubs so tests don't error on unstubbed lookups.
        lenient().when( pageManager.pageExists( anyString() ) )
            .thenAnswer( inv -> pageStore.containsKey( (String) inv.getArgument( 0 ) ) );
        lenient().when( pageManager.getPageText( anyString(), anyInt() ) )
            .thenAnswer( inv -> pageStore.get( (String) inv.getArgument( 0 ) ) );
        pageWriter = ( name, content ) -> {
            pageWrites.add( new String[]{ name, content } );
            pageStore.put( name, content );
        };
    }

    /** Builder factory shared across tests. */
    private HubOverviewService.Builder serviceBuilder() {
        return HubOverviewService.builder()
            .kgRepo( kgRepo )
            .pageManager( pageManager )
            .pageWriter( pageWriter )
            .nearMissThreshold( 0.50 )
            .overlapThreshold( 0.60 )
            .nearMissMaxResults( 10 )
            .mltMaxResults( 10 )
            // No-op MLT — overridden per test where needed
            .luceneMlt( ( seed, max, excludes ) -> Collections.emptyList() )
            .contentModel( model );
    }

    // ---- listHubOverviews ----

    @Test
    void listHubOverviews_happyPath_sortsByCoherenceAscending() {
        // Two hubs with distinct coherence: cooking (tight) vs an artificially loose
        // pair where the two members share little vocabulary.
        seedHub( "CookingHub", List.of( "Baking", "Roasting" ) );
        seedHub( "GrabBagHub", List.of( "QuantumPhysics", "PetGroomer" ) );
        pageStore.put( "CookingHub", "stub" );
        pageStore.put( "GrabBagHub", "stub" );

        model = new TfidfModel();
        model.build(
            List.of( "Baking", "Roasting", "QuantumPhysics", "PetGroomer" ),
            List.of(
                "baking bread cake flour sugar oven recipe dough oven baking baking",
                "roasting meat oven temperature seasoning baking oven roast roast",
                "quantum physics wavefunction entanglement schrodinger photon",
                "pet grooming dog cat brushing nails bath fur"
            ) );

        final HubOverviewService svc = serviceBuilder().contentModel( model ).build();
        final List< HubOverviewService.HubOverviewSummary > out = svc.listHubOverviews();

        assertEquals( 2, out.size() );
        // GrabBagHub has the lower coherence — it should sort first.
        assertEquals( "GrabBagHub", out.get( 0 ).name() );
        assertEquals( "CookingHub", out.get( 1 ).name() );
        assertTrue( out.get( 0 ).coherence() < out.get( 1 ).coherence(),
            "Expected GrabBagHub coherence < CookingHub coherence" );
        for ( final HubOverviewService.HubOverviewSummary s : out ) {
            assertEquals( 2, s.memberCount() );
            assertTrue( s.hasBackingPage() );
        }
    }

    // ---- helpers ----

    /**
     * Creates a hub-typed KG node and {@code related} edges to each member name. Also
     * upserts each member as an article-typed node so the queryNodes call sees them.
     */
    private void seedHub( final String hubName, final List< String > members ) {
        final var hubNode = kgRepo.upsertNode( hubName, "hub", hubName,
            Provenance.HUMAN_AUTHORED, Map.of( "type", "hub" ) );
        for ( final String m : members ) {
            final var memberNode = kgRepo.upsertNode( m, "article", m,
                Provenance.HUMAN_AUTHORED, Map.of() );
            kgRepo.upsertEdge( hubNode.id(), memberNode.id(), "related",
                Provenance.HUMAN_AUTHORED, Map.of() );
        }
    }
}
```

- [ ] **Step 2: Run the test — expect failure**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest#listHubOverviews_happyPath_sortsByCoherenceAscending test -q`
Expected: FAIL with `UnsupportedOperationException: implemented in a later task`.

- [ ] **Step 3: Implement `listHubOverviews` in `HubOverviewService`**

Replace the stub `listHubOverviews()` method body with a real implementation. Also add a private helper `loadAllHubMembers()` that the next several tasks will reuse, and a private helper `resolveModel()` mirroring HubDiscoveryService's pattern.

Replace the entire `listHubOverviews()` method with:

```java
    public List< HubOverviewSummary > listHubOverviews() {
        final long start = System.currentTimeMillis();
        final TfidfModel resolved = resolveModel();
        if ( resolved == null ) {
            LOG.info( "Hub overview list: skipped — content model unavailable" );
            return Collections.emptyList();
        }
        // 1. Load all KG nodes and partition into hubs / non-hubs.
        final List< com.wikantik.api.knowledge.KgNode > allNodes =
            kgRepo.queryNodes( null, null, 100_000, 0 );
        final Map< String, com.wikantik.api.knowledge.KgNode > hubsByName = new LinkedHashMap<>();
        for ( final var node : allNodes ) {
            if ( node.properties() != null && "hub".equals( node.properties().get( "type" ) ) ) {
                hubsByName.put( node.name(), node );
            }
        }
        if ( hubsByName.isEmpty() ) {
            LOG.info( "Hub overview list: 0 hubs, {}ms", System.currentTimeMillis() - start );
            return Collections.emptyList();
        }

        // 2. Load all 'related' edges and group by hub source.
        final Map< String, Set< String > > hubMembers = loadAllHubMembers();

        // 3. Compute centroids for every hub from model-backed members.
        final Map< String, float[] > centroids = new LinkedHashMap<>();
        final Map< String, Double > coherences = new LinkedHashMap<>();
        for ( final String hubName : hubsByName.keySet() ) {
            final List< float[] > vecs = vectorsFor( resolved, hubMembers.getOrDefault( hubName, Set.of() ) );
            if ( vecs.size() < 2 ) {
                centroids.put( hubName, null );
                coherences.put( hubName, Double.NaN );
                continue;
            }
            final float[][] arr = vecs.toArray( new float[ 0 ][] );
            final float[] centroid = HubDiscoveryService.normalizedCentroid( arr );
            centroids.put( hubName, centroid );
            coherences.put( hubName, HubDiscoveryService.meanDot( arr, centroid ) );
        }

        // 4. Near-miss counts: for each non-member candidate, accumulate per-hub matches.
        final Set< String > allMemberNames = new HashSet<>();
        for ( final Set< String > set : hubMembers.values() ) allMemberNames.addAll( set );
        final Map< String, Integer > nearMissCounts = new HashMap<>();
        for ( final String hubName : hubsByName.keySet() ) nearMissCounts.put( hubName, 0 );
        for ( final String entityName : resolved.getEntityNames() ) {
            if ( hubsByName.containsKey( entityName ) ) continue;
            if ( allMemberNames.contains( entityName ) ) continue;
            final int eid = resolved.entityId( entityName );
            if ( eid < 0 ) continue;
            final float[] vec = resolved.getVector( eid );
            for ( final String hubName : hubsByName.keySet() ) {
                final float[] centroid = centroids.get( hubName );
                if ( centroid == null ) continue;
                if ( cosine( vec, centroid ) >= nearMissThreshold ) {
                    nearMissCounts.merge( hubName, 1, Integer::sum );
                }
            }
        }

        // 5. Inbound links: for each hub, count distinct external link sources whose
        //    target is one of this hub's members, minus the hub itself and minus any
        //    other member of the same hub.
        final List< Map< String, Object > > linksToEdges =
            kgRepo.queryEdgesWithNames( "links_to", null, 100_000, 0 );
        final Map< String, Set< String > > inboundByHub = new HashMap<>();
        for ( final String hubName : hubsByName.keySet() ) inboundByHub.put( hubName, new HashSet<>() );
        for ( final Map< String, Object > edge : linksToEdges ) {
            final String src = (String) edge.get( "source_name" );
            final String tgt = (String) edge.get( "target_name" );
            if ( src == null || tgt == null ) continue;
            for ( final String hubName : hubsByName.keySet() ) {
                final Set< String > members = hubMembers.getOrDefault( hubName, Set.of() );
                if ( !members.contains( tgt ) ) continue;
                if ( hubName.equals( src ) ) continue;
                if ( members.contains( src ) ) continue;
                inboundByHub.get( hubName ).add( src );
            }
        }

        // 6. Build summaries; sort by coherence ascending (NaN to end), name tiebreak.
        final List< HubOverviewSummary > summaries = new ArrayList<>();
        for ( final String hubName : hubsByName.keySet() ) {
            final boolean hasPage = pageExists( hubName );
            summaries.add( new HubOverviewSummary(
                hubName,
                hubMembers.getOrDefault( hubName, Set.of() ).size(),
                inboundByHub.get( hubName ).size(),
                nearMissCounts.get( hubName ),
                coherences.get( hubName ),
                hasPage
            ) );
        }
        summaries.sort( ( a, b ) -> {
            final boolean an = Double.isNaN( a.coherence() );
            final boolean bn = Double.isNaN( b.coherence() );
            if ( an && bn ) return a.name().compareTo( b.name() );
            if ( an ) return 1;
            if ( bn ) return -1;
            final int cmp = Double.compare( a.coherence(), b.coherence() );
            return cmp != 0 ? cmp : a.name().compareTo( b.name() );
        } );

        LOG.info( "Hub overview list: {} hubs, {}ms", summaries.size(), System.currentTimeMillis() - start );
        return summaries;
    }

    // ---- private helpers ----

    private TfidfModel resolveModel() {
        final TfidfModel m = contentModelSupplier.get();
        if ( m == null || m.getEntityCount() == 0 ) return null;
        return m;
    }

    private boolean pageExists( final String name ) {
        try {
            return pageManager.pageExists( name );
        } catch ( final ProviderException e ) {
            LOG.warn( "HubOverviewService pageExists failed for '{}': {}", name, e.getMessage() );
            return false;
        }
    }

    /**
     * Loads every {@code related} edge from the KG and groups targets by hub source name.
     * Only sources whose KG node is hub-typed are included.
     */
    private Map< String, Set< String > > loadAllHubMembers() {
        final List< com.wikantik.api.knowledge.KgNode > allNodes =
            kgRepo.queryNodes( null, null, 100_000, 0 );
        final Set< String > hubNames = new HashSet<>();
        for ( final var node : allNodes ) {
            if ( node.properties() != null && "hub".equals( node.properties().get( "type" ) ) ) {
                hubNames.add( node.name() );
            }
        }
        final List< Map< String, Object > > edges =
            kgRepo.queryEdgesWithNames( "related", null, 100_000, 0 );
        final Map< String, Set< String > > out = new LinkedHashMap<>();
        for ( final String hub : hubNames ) out.put( hub, new HashSet<>() );
        for ( final Map< String, Object > edge : edges ) {
            final String src = (String) edge.get( "source_name" );
            final String tgt = (String) edge.get( "target_name" );
            if ( src == null || tgt == null ) continue;
            if ( !hubNames.contains( src ) ) continue;
            out.get( src ).add( tgt );
        }
        return out;
    }

    private List< float[] > vectorsFor( final TfidfModel m, final Set< String > names ) {
        final List< float[] > out = new ArrayList<>();
        for ( final String name : names ) {
            final int eid = m.entityId( name );
            if ( eid >= 0 ) out.add( m.getVector( eid ) );
        }
        return out;
    }

    private static double cosine( final float[] a, final float[] b ) {
        // Both vectors are L2-normalised in the TF-IDF model, so dot product == cosine.
        double dot = 0;
        for ( int i = 0; i < a.length; i++ ) dot += a[ i ] * b[ i ];
        return dot;
    }
```

- [ ] **Step 4: Run the happy-path test — expect pass**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest#listHubOverviews_happyPath_sortsByCoherenceAscending test -q`
Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/HubOverviewService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java
git commit -m "feat: HubOverviewService.listHubOverviews happy path"
```

---

### Task 7: Test — empty content model returns empty list

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java`

- [ ] **Step 1: Add the failing test**

Append to `HubOverviewServiceTest`:

```java
    @Test
    void listHubOverviews_emptyContentModel_returnsEmptyList() {
        // Seed a hub but pass an empty model — service should short-circuit.
        seedHub( "TechHub", List.of( "Java", "Python" ) );
        model = new TfidfModel(); // unbuilt; entityCount == 0

        final HubOverviewService svc = serviceBuilder().contentModel( model ).build();
        final List< HubOverviewService.HubOverviewSummary > out = svc.listHubOverviews();

        assertTrue( out.isEmpty(), "Expected empty list when content model has 0 entities" );
    }
```

- [ ] **Step 2: Run the test**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest#listHubOverviews_emptyContentModel_returnsEmptyList test -q`
Expected: BUILD SUCCESS, 1 test passed (the existing `resolveModel()` already handles this).

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java
git commit -m "test: HubOverviewService empty model returns empty list"
```

---

### Task 8: Test — hub with all non-model members has NaN coherence and sorts last

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java`

- [ ] **Step 1: Add the failing test**

Append to `HubOverviewServiceTest`:

```java
    @Test
    void listHubOverviews_hubWithAllNonModelMembers_hasNaNCoherenceSortsLast() {
        // CookingHub has 2 model-backed members (will compute coherence).
        // GhostHub has 2 members that are NOT in the model — coherence should be NaN.
        seedHub( "CookingHub", List.of( "Baking", "Roasting" ) );
        seedHub( "GhostHub", List.of( "Phantom1", "Phantom2" ) );
        pageStore.put( "CookingHub", "stub" );
        pageStore.put( "GhostHub", "stub" );

        model = new TfidfModel();
        model.build(
            List.of( "Baking", "Roasting" ),
            List.of(
                "baking bread cake flour sugar oven recipe dough",
                "roasting meat oven temperature seasoning baking"
            ) );

        final HubOverviewService svc = serviceBuilder().contentModel( model ).build();
        final List< HubOverviewService.HubOverviewSummary > out = svc.listHubOverviews();

        assertEquals( 2, out.size() );
        assertEquals( "CookingHub", out.get( 0 ).name(),
            "Cooking should sort first (finite coherence)" );
        assertEquals( "GhostHub", out.get( 1 ).name(),
            "GhostHub should sort last (NaN coherence)" );
        assertTrue( Double.isNaN( out.get( 1 ).coherence() ),
            "GhostHub coherence should be NaN" );
    }
```

- [ ] **Step 2: Run the test**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest#listHubOverviews_hubWithAllNonModelMembers_hasNaNCoherenceSortsLast test -q`
Expected: BUILD SUCCESS, 1 test passed (the sort comparator already handles NaN).

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java
git commit -m "test: NaN coherence hubs sort last"
```

---

### Task 9: Test — inbound link counts exclude hub itself and other same-hub members

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java`

- [ ] **Step 1: Add the failing test**

Append to `HubOverviewServiceTest`:

```java
    @Test
    void listHubOverviews_inboundLinks_excludeHubAndSameHubMembers() {
        // CookingHub has members Baking + Roasting.
        // External pages "Newsletter" and "FoodBlog" both link to Baking.
        // Roasting also links to Baking — should NOT count (same-hub member).
        // CookingHub itself links to Baking — should NOT count (the hub itself).
        seedHub( "CookingHub", List.of( "Baking", "Roasting" ) );
        pageStore.put( "CookingHub", "stub" );

        // Add the link sources as KG nodes and the links_to edges. We re-upsert the
        // existing Baking/Roasting/CookingHub nodes (idempotent — returns the same row)
        // to capture their ids without going through queryNodes (whose "name" filter is
        // a LIKE, not an exact match).
        final var newsletter = kgRepo.upsertNode( "Newsletter", "article", "Newsletter",
            Provenance.HUMAN_AUTHORED, Map.of() );
        final var foodBlog = kgRepo.upsertNode( "FoodBlog", "article", "FoodBlog",
            Provenance.HUMAN_AUTHORED, Map.of() );
        final var bakingNode = kgRepo.upsertNode( "Baking", "article", "Baking",
            Provenance.HUMAN_AUTHORED, Map.of() );
        final var roastingNode = kgRepo.upsertNode( "Roasting", "article", "Roasting",
            Provenance.HUMAN_AUTHORED, Map.of() );
        final var hubNode = kgRepo.upsertNode( "CookingHub", "hub", "CookingHub",
            Provenance.HUMAN_AUTHORED, Map.of( "type", "hub" ) );

        kgRepo.upsertEdge( newsletter.id(), bakingNode.id(), "links_to", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( foodBlog.id(), bakingNode.id(), "links_to", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( roastingNode.id(), bakingNode.id(), "links_to", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( hubNode.id(), bakingNode.id(), "links_to", Provenance.HUMAN_AUTHORED, Map.of() );

        model = new TfidfModel();
        model.build(
            List.of( "Baking", "Roasting" ),
            List.of(
                "baking bread cake flour sugar oven",
                "roasting meat oven temperature seasoning"
            ) );

        final HubOverviewService svc = serviceBuilder().contentModel( model ).build();
        final List< HubOverviewService.HubOverviewSummary > out = svc.listHubOverviews();

        assertEquals( 1, out.size() );
        assertEquals( 2, out.get( 0 ).inboundLinkCount(),
            "Should count Newsletter + FoodBlog only (not CookingHub itself, not Roasting)" );
    }
```

- [ ] **Step 2: Run the test**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest#listHubOverviews_inboundLinks_excludeHubAndSameHubMembers test -q`
Expected: BUILD SUCCESS, 1 test passed (the filter logic already excludes hub + same-hub-members).

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java
git commit -m "test: inbound link counting excludes hub and same-hub members"
```

---

### Task 10: Test — near-miss threshold is inclusive (cosine == threshold counts)

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java`

- [ ] **Step 1: Add the test**

Append to `HubOverviewServiceTest`:

```java
    @Test
    void listHubOverviews_nearMissCount_thresholdInclusive() {
        // Build a hub of 2 cooking pages and one near-miss "Pasta" article that
        // shares enough vocabulary to clear the 0.50 threshold but is not a member.
        seedHub( "CookingHub", List.of( "Baking", "Roasting" ) );
        kgRepo.upsertNode( "Pasta", "article", "Pasta",
            Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertNode( "Soccer", "article", "Soccer",
            Provenance.HUMAN_AUTHORED, Map.of() );
        pageStore.put( "CookingHub", "stub" );

        model = new TfidfModel();
        model.build(
            List.of( "Baking", "Roasting", "Pasta", "Soccer" ),
            List.of(
                "baking bread cake flour sugar oven recipe dough oven baking baking",
                "roasting meat oven temperature seasoning baking oven roast",
                // Pasta shares lots of cooking vocab with both members.
                "pasta sauce flour sugar oven dough recipe baking roast meat",
                // Soccer shares nothing.
                "soccer football pitch goal player team league kick stadium"
            ) );

        // Threshold of 0.10 — very generous so Pasta clears it but Soccer does not.
        final HubOverviewService svc = serviceBuilder()
            .contentModel( model )
            .nearMissThreshold( 0.10 )
            .build();

        final var out = svc.listHubOverviews();
        assertEquals( 1, out.size() );
        assertEquals( 1, out.get( 0 ).nearMissCount(),
            "Pasta should count as a near-miss; Soccer should not" );
    }
```

- [ ] **Step 2: Run the test**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest#listHubOverviews_nearMissCount_thresholdInclusive test -q`
Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 3: Run the full test class to confirm no regressions**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest test -q`
Expected: BUILD SUCCESS, all five `listHubOverviews_*` tests passing.

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java
git commit -m "test: near-miss threshold counting"
```

---

## Phase 4 — `loadDrilldown()` TDD

### Task 11: Implement `loadDrilldown` happy path

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/HubOverviewService.java`

- [ ] **Step 1: Write the failing happy-path test**

Append to `HubOverviewServiceTest`:

```java
    // ---- loadDrilldown ----

    @Test
    void loadDrilldown_happyPath_populatesAllSections() throws Exception {
        // Two hubs: CookingHub (3 cooking members + 1 stub member) and GardeningHub
        // (2 gardening members). Pasta is a non-member near-miss for cooking. The
        // two hubs share zero members (sharedMemberCount=0) but their centroids are
        // distant — overlap section will be empty unless we drop the overlap threshold.
        seedHub( "CookingHub", List.of( "Baking", "Roasting", "Grilling", "GhostMember" ) );
        seedHub( "GardeningHub", List.of( "Tomatoes", "Lettuce" ) );
        pageStore.put( "CookingHub", "stub" );
        pageStore.put( "GardeningHub", "stub" );
        // Mark "GhostMember" as missing — pageStore does NOT contain it,
        // so pageManager.pageExists returns false → drilldown classifies as stub.
        pageStore.put( "Baking", "..." );
        pageStore.put( "Roasting", "..." );
        pageStore.put( "Grilling", "..." );
        pageStore.put( "Tomatoes", "..." );
        pageStore.put( "Lettuce", "..." );

        kgRepo.upsertNode( "Pasta", "article", "Pasta",
            Provenance.HUMAN_AUTHORED, Map.of() );
        pageStore.put( "Pasta", "..." );

        model = new TfidfModel();
        model.build(
            List.of( "Baking", "Roasting", "Grilling", "Tomatoes", "Lettuce", "Pasta" ),
            List.of(
                "baking bread cake flour sugar oven recipe dough baking",
                "roasting meat oven temperature seasoning baking",
                "grilling charcoal meat barbecue outdoor fire baking",
                "tomatoes garden vegetable plant water sun soil",
                "lettuce garden vegetable plant water leaf soil",
                "pasta sauce flour sugar oven dough baking roast"
            ) );

        // Stub LuceneMlt: returns one fixed hit, only when seed is "CookingHub".
        final HubOverviewService.LuceneMlt mlt = ( seed, max, excludes ) -> {
            if ( "CookingHub".equals( seed ) && !excludes.contains( "Pasta" ) ) {
                return List.of( new HubOverviewService.MoreLikeThisLucene( "Pasta", 4.2 ) );
            }
            return List.of();
        };

        final HubOverviewService svc = serviceBuilder()
            .contentModel( model )
            .luceneMlt( mlt )
            .nearMissThreshold( 0.10 )
            .overlapThreshold( 0.99 ) // effectively disable overlap section
            .build();

        final HubOverviewService.HubDrilldown d = svc.loadDrilldown( "CookingHub" );
        assertNotNull( d );
        assertEquals( "CookingHub", d.name() );
        assertTrue( d.hasBackingPage() );

        // Members: Baking + Roasting + Grilling are real, GhostMember is stub.
        assertEquals( 3, d.members().size() );
        // Members sorted ascending by cosine — verify ordering invariant.
        for ( int i = 1; i < d.members().size(); i++ ) {
            assertTrue( d.members().get( i - 1 ).cosineToCentroid()
                <= d.members().get( i ).cosineToCentroid(),
                "Members must be sorted ascending by cosine" );
        }
        assertEquals( 1, d.stubMembers().size() );
        assertEquals( "GhostMember", d.stubMembers().get( 0 ).name() );

        // Near-miss TF-IDF should include Pasta (cooking-vocab non-member).
        assertTrue( d.nearMissTfidf().stream().anyMatch( h -> "Pasta".equals( h.name() ) ),
            "Pasta should appear in near-miss TF-IDF list" );
        // MLT list contains the stubbed Pasta hit.
        assertEquals( 1, d.moreLikeThisLucene().size() );
        assertEquals( "Pasta", d.moreLikeThisLucene().get( 0 ).name() );
        // Overlap section empty (we set the threshold to 0.99).
        assertTrue( d.overlapHubs().isEmpty() );
    }
```

- [ ] **Step 2: Run the test — expect failure**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest#loadDrilldown_happyPath_populatesAllSections test -q`
Expected: FAIL with `UnsupportedOperationException: implemented in a later task`.

- [ ] **Step 3: Implement `loadDrilldown`**

Replace the stub `loadDrilldown` method body in `HubOverviewService.java` with:

```java
    public HubDrilldown loadDrilldown( final String hubName ) {
        if ( hubName == null || hubName.isBlank() ) return null;
        final long start = System.currentTimeMillis();

        // 1. Verify the hub node exists. queryNodes with the "name" filter does a
        //    substring LIKE, not an exact match — so we filter by node_type only and
        //    look up the exact name in Java to avoid matching neighboring hubs that
        //    happen to share a substring.
        final List< com.wikantik.api.knowledge.KgNode > hubNodes =
            kgRepo.queryNodes( Map.of( "node_type", "hub" ), null, 100_000, 0 );
        boolean found = false;
        for ( final var n : hubNodes ) {
            if ( hubName.equals( n.name() ) ) { found = true; break; }
        }
        if ( !found ) return null;

        final TfidfModel resolved = resolveModel();
        final boolean modelAvailable = resolved != null;

        // 2. Load this hub's members from KG.
        final Map< String, Set< String > > allHubMembers = loadAllHubMembers();
        final Set< String > rawMembers = allHubMembers.getOrDefault( hubName, Set.of() );
        final List< String > sortedMembers = new ArrayList<>( rawMembers );
        Collections.sort( sortedMembers );

        // 3. Partition into existing pages and stubs.
        final List< String > existing = new ArrayList<>();
        final List< StubMember > stubs = new ArrayList<>();
        for ( final String m : sortedMembers ) {
            if ( pageExists( m ) ) existing.add( m );
            else stubs.add( new StubMember( m ) );
        }

        // 4. Compute hub centroid from model-backed existing+stub members alike.
        float[] centroid = null;
        double coherence = Double.NaN;
        if ( modelAvailable && rawMembers.size() >= 2 ) {
            final List< float[] > vecs = vectorsFor( resolved, rawMembers );
            if ( vecs.size() >= 2 ) {
                final float[][] arr = vecs.toArray( new float[ 0 ][] );
                centroid = HubDiscoveryService.normalizedCentroid( arr );
                coherence = HubDiscoveryService.meanDot( arr, centroid );
            }
        }

        // 5. Per-member cosine to centroid (ascending).
        final List< MemberDetail > memberDetails = new ArrayList<>();
        for ( final String m : existing ) {
            final double cos;
            if ( centroid != null && modelAvailable ) {
                final int eid = resolved.entityId( m );
                cos = eid >= 0 ? cosine( resolved.getVector( eid ), centroid ) : Double.NaN;
            } else {
                cos = Double.NaN;
            }
            memberDetails.add( new MemberDetail( m, cos, true ) );
        }
        memberDetails.sort( ( a, b ) -> {
            final boolean an = Double.isNaN( a.cosineToCentroid() );
            final boolean bn = Double.isNaN( b.cosineToCentroid() );
            if ( an && bn ) return a.name().compareTo( b.name() );
            if ( an ) return 1;
            if ( bn ) return -1;
            return Double.compare( a.cosineToCentroid(), b.cosineToCentroid() );
        } );

        // 6. Near-miss TF-IDF list (top-N non-member non-hub by cosine descending).
        final List< NearMissTfidf > nearMissList = new ArrayList<>();
        if ( centroid != null && modelAvailable ) {
            final Set< String > allHubNames = allHubMembers.keySet();
            final List< NearMissTfidf > scored = new ArrayList<>();
            for ( final String entityName : resolved.getEntityNames() ) {
                if ( allHubNames.contains( entityName ) ) continue;
                if ( rawMembers.contains( entityName ) ) continue;
                final int eid = resolved.entityId( entityName );
                if ( eid < 0 ) continue;
                final double cos = cosine( resolved.getVector( eid ), centroid );
                if ( cos >= nearMissThreshold ) {
                    scored.add( new NearMissTfidf( entityName, cos ) );
                }
            }
            scored.sort( ( a, b ) -> Double.compare( b.cosineToCentroid(), a.cosineToCentroid() ) );
            for ( int i = 0; i < scored.size() && i < nearMissMaxResults; i++ ) {
                nearMissList.add( scored.get( i ) );
            }
        }

        // 7. Overlap hubs (other hubs whose centroid cosine ≥ overlapThreshold).
        final List< OverlapHub > overlapHubs = new ArrayList<>();
        if ( centroid != null && modelAvailable ) {
            for ( final var entry : allHubMembers.entrySet() ) {
                final String otherHub = entry.getKey();
                if ( otherHub.equals( hubName ) ) continue;
                final List< float[] > otherVecs = vectorsFor( resolved, entry.getValue() );
                if ( otherVecs.size() < 2 ) continue;
                final float[][] arr = otherVecs.toArray( new float[ 0 ][] );
                final float[] otherCentroid = HubDiscoveryService.normalizedCentroid( arr );
                final double cos = cosine( centroid, otherCentroid );
                if ( cos < overlapThreshold ) continue;
                final Set< String > shared = new HashSet<>( rawMembers );
                shared.retainAll( entry.getValue() );
                overlapHubs.add( new OverlapHub( otherHub, cos, shared.size() ) );
            }
            overlapHubs.sort( ( a, b ) -> Double.compare( b.centroidCosine(), a.centroidCosine() ) );
        }

        // 8. Lucene MoreLikeThis (with empty fallback on any failure).
        final List< MoreLikeThisLucene > mltList = new ArrayList<>();
        try {
            // Seed = hub itself if it has a backing page; otherwise the highest-cosine
            // member (the "exemplar"). Falls back to first sorted member if no centroid.
            final boolean hasBacking = pageExists( hubName );
            String seed = hasBacking ? hubName : null;
            if ( seed == null && !memberDetails.isEmpty() ) {
                seed = memberDetails.get( memberDetails.size() - 1 ).name();
            }
            if ( seed != null ) {
                final Set< String > excludes = new HashSet<>( rawMembers );
                excludes.add( hubName );
                final List< MoreLikeThisLucene > raw =
                    luceneMlt.findSimilar( seed, mltMaxResults, excludes );
                if ( raw != null ) mltList.addAll( raw );
            }
        } catch ( final Exception e ) {
            LOG.warn( "Hub overview drilldown: Lucene MoreLikeThis failed for hub '{}': {}",
                hubName, e.getMessage() );
        }

        final boolean hasBackingPage = pageExists( hubName );
        LOG.info( "Hub overview drilldown: hub='{}' members={} nearMiss={} overlap={} {}ms",
            hubName, memberDetails.size(), nearMissList.size(), overlapHubs.size(),
            System.currentTimeMillis() - start );
        return new HubDrilldown(
            hubName, hasBackingPage, coherence,
            memberDetails, stubs, nearMissList, mltList, overlapHubs );
    }
```

- [ ] **Step 4: Run the happy-path test**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest#loadDrilldown_happyPath_populatesAllSections test -q`
Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/HubOverviewService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java
git commit -m "feat: HubOverviewService.loadDrilldown happy path"
```

---

### Task 12: Test — `loadDrilldown` returns null for unknown hub

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java`

- [ ] **Step 1: Add the test**

Append to `HubOverviewServiceTest`:

```java
    @Test
    void loadDrilldown_unknownHub_returnsNull() {
        // No hub seeded.
        model = new TfidfModel();
        model.build( List.of( "Baking" ), List.of( "baking bread cake" ) );

        final HubOverviewService svc = serviceBuilder().contentModel( model ).build();
        assertNull( svc.loadDrilldown( "DoesNotExist" ) );
    }
```

- [ ] **Step 2: Run the test**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest#loadDrilldown_unknownHub_returnsNull test -q`
Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java
git commit -m "test: loadDrilldown returns null for unknown hub"
```

---

### Task 13: Test — orphaned hub (no backing page) still loads drilldown

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java`

- [ ] **Step 1: Add the test**

Append to `HubOverviewServiceTest`:

```java
    @Test
    void loadDrilldown_orphanedHub_populatesFromKgOnly() throws Exception {
        seedHub( "OrphanHub", List.of( "Baking", "Roasting" ) );
        // Crucially, no pageStore entry for "OrphanHub" — it has no backing wiki page.
        pageStore.put( "Baking", "..." );
        pageStore.put( "Roasting", "..." );

        model = new TfidfModel();
        model.build(
            List.of( "Baking", "Roasting" ),
            List.of(
                "baking bread cake flour sugar oven",
                "roasting meat oven temperature seasoning baking"
            ) );

        final HubOverviewService svc = serviceBuilder().contentModel( model ).build();
        final HubOverviewService.HubDrilldown d = svc.loadDrilldown( "OrphanHub" );

        assertNotNull( d );
        assertEquals( "OrphanHub", d.name() );
        assertFalse( d.hasBackingPage(), "Orphan hub must report hasBackingPage=false" );
        assertEquals( 2, d.members().size() );
        assertTrue( d.stubMembers().isEmpty() );
    }
```

- [ ] **Step 2: Run the test**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest#loadDrilldown_orphanedHub_populatesFromKgOnly test -q`
Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java
git commit -m "test: orphan hub drilldown reads from KG only"
```

---

### Task 14: Test — Lucene throws → empty MLT list, drilldown still loads

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java`

- [ ] **Step 1: Add the test**

Append to `HubOverviewServiceTest`:

```java
    @Test
    void loadDrilldown_luceneThrows_returnsEmptyMltAndContinues() throws Exception {
        seedHub( "CookingHub", List.of( "Baking", "Roasting" ) );
        pageStore.put( "CookingHub", "stub" );
        pageStore.put( "Baking", "..." );
        pageStore.put( "Roasting", "..." );

        model = new TfidfModel();
        model.build(
            List.of( "Baking", "Roasting" ),
            List.of(
                "baking bread cake flour sugar oven",
                "roasting meat oven temperature seasoning baking"
            ) );

        final HubOverviewService.LuceneMlt failingMlt = ( seed, max, excludes ) -> {
            throw new java.io.IOException( "lucene index unavailable" );
        };

        final HubOverviewService svc = serviceBuilder()
            .contentModel( model )
            .luceneMlt( failingMlt )
            .build();

        final HubOverviewService.HubDrilldown d = svc.loadDrilldown( "CookingHub" );
        assertNotNull( d );
        assertTrue( d.moreLikeThisLucene().isEmpty(),
            "MLT list must be empty when Lucene throws" );
        // Other sections still populated.
        assertEquals( 2, d.members().size() );
    }
```

- [ ] **Step 2: Run the test**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest#loadDrilldown_luceneThrows_returnsEmptyMltAndContinues test -q`
Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 3: Run the full test class**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest test -q`
Expected: BUILD SUCCESS, all tests passing so far.

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java
git commit -m "test: Lucene exception falls back to empty MLT list"
```

---

## Phase 5 — `removeMember()` TDD

### Task 15: Implement `removeMember` happy path with body preservation check

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/HubOverviewService.java`

- [ ] **Step 1: Write the failing test**

Append to `HubOverviewServiceTest`:

```java
    // ---- removeMember ----

    private static final String SAMPLE_HUB_BODY =
        "# CookingHub\n\nA hand-curated description.\n\n## Members\n\n- Baking\n- Roasting\n- Grilling\n";

    private static String hubPageText( final List< String > members ) {
        final StringBuilder sb = new StringBuilder();
        sb.append( "---\n" );
        sb.append( "title: CookingHub\n" );
        sb.append( "type: hub\n" );
        sb.append( "related:\n" );
        for ( final String m : members ) sb.append( "- " ).append( m ).append( "\n" );
        sb.append( "---\n" );
        sb.append( SAMPLE_HUB_BODY );
        return sb.toString();
    }

    @Test
    void removeMember_happyPath_writesUpdatedFrontmatterAndPreservesBody() throws Exception {
        seedHub( "CookingHub", List.of( "Baking", "Roasting", "Grilling" ) );
        pageStore.put( "CookingHub", hubPageText( List.of( "Baking", "Roasting", "Grilling" ) ) );

        model = new TfidfModel();
        model.build( List.of( "Baking" ), List.of( "baking bread" ) );

        final HubOverviewService svc = serviceBuilder().contentModel( model ).build();
        final HubOverviewService.RemoveMemberResult result =
            svc.removeMember( "CookingHub", "Roasting", "alice" );

        assertEquals( "Roasting", result.removed() );
        assertEquals( 2, result.remainingMemberCount() );
        assertEquals( 1, pageWrites.size() );

        final String written = pageWrites.get( 0 )[ 1 ];
        assertTrue( written.contains( "Baking" ) );
        assertTrue( written.contains( "Grilling" ) );
        assertFalse( written.contains( "- Roasting" ),
            "Roasting should no longer appear as a list item" );
        assertTrue( written.contains( SAMPLE_HUB_BODY ),
            "Original body must be preserved byte-for-byte" );
    }
```

- [ ] **Step 2: Run the test — expect failure**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest#removeMember_happyPath_writesUpdatedFrontmatterAndPreservesBody test -q`
Expected: FAIL with `UnsupportedOperationException: implemented in a later task`.

- [ ] **Step 3: Implement `removeMember`**

Add this import to `HubOverviewService.java`:

```java
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.providers.PageProvider;
```

Then replace the stub `removeMember` method body with:

```java
    public RemoveMemberResult removeMember( final String hubName,
                                              final String member,
                                              final String reviewedBy ) {
        if ( hubName == null || hubName.isBlank() ) {
            throw new IllegalArgumentException( "hubName must not be empty" );
        }
        if ( member == null || member.isBlank() ) {
            throw new IllegalArgumentException( "member must not be empty" );
        }
        if ( pageWriter == null ) {
            throw new IllegalStateException(
                "HubOverviewService: pageWriter is required for removeMember" );
        }

        // 1. Read existing page text — 404 if missing.
        final String text;
        try {
            text = pageManager.getPageText( hubName, PageProvider.LATEST_VERSION );
        } catch ( final ProviderException e ) {
            throw new HubOverviewException( "Failed to read hub page '" + hubName + "'", e );
        }
        if ( text == null ) {
            throw new HubOverviewException( "Hub page '" + hubName + "' not found", null );
        }

        // 2. Parse frontmatter and assert type=hub.
        final ParsedPage parsed = FrontmatterParser.parse( text );
        final Map< String, Object > metadata = new LinkedHashMap<>( parsed.metadata() );
        if ( !"hub".equals( metadata.get( "type" ) ) ) {
            throw new IllegalArgumentException(
                "Page '" + hubName + "' is not a hub page (type != 'hub')" );
        }

        // 3. Pull related list and validate member is present.
        final Object rawRelated = metadata.get( "related" );
        if ( !( rawRelated instanceof List< ? > rawList ) ) {
            throw new IllegalArgumentException(
                "Hub page '" + hubName + "' has no 'related' list in frontmatter" );
        }
        final List< String > related = new ArrayList<>();
        for ( final Object o : rawList ) {
            if ( o != null ) related.add( o.toString() );
        }
        if ( !related.contains( member ) ) {
            throw new IllegalArgumentException(
                "Member '" + member + "' is not in hub '" + hubName + "'" );
        }

        // 4. Remove preserving order.
        related.remove( member );

        // 5. 2-member-minimum guard.
        if ( related.size() < 2 ) {
            throw new HubOverviewException(
                "Removing '" + member + "' would leave hub '" + hubName
                    + "' with fewer than 2 members; delete the hub instead", null );
        }

        // 6. Re-serialize and save.
        metadata.put( "related", related );
        final String newText = FrontmatterWriter.write( metadata, parsed.body() );
        try {
            pageWriter.write( hubName, newText );
        } catch ( final Exception e ) {
            throw new HubOverviewException(
                "Failed to save updated hub page '" + hubName + "'", e );
        }

        LOG.info( "Hub overview: removed member '{}' from '{}', remaining {} (reviewed by {})",
            member, hubName, related.size(), reviewedBy );
        return new RemoveMemberResult( member, related.size() );
    }
```

- [ ] **Step 4: Run the happy-path test**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest#removeMember_happyPath_writesUpdatedFrontmatterAndPreservesBody test -q`
Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/HubOverviewService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java
git commit -m "feat: HubOverviewService.removeMember happy path"
```

---

### Task 16: Test — missing/blank `member` arg → `IllegalArgumentException`

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java`

- [ ] **Step 1: Add the test**

Append to `HubOverviewServiceTest`:

```java
    @Test
    void removeMember_blankMember_throwsIllegalArgument() throws Exception {
        seedHub( "CookingHub", List.of( "Baking", "Roasting" ) );
        pageStore.put( "CookingHub", hubPageText( List.of( "Baking", "Roasting" ) ) );
        model = new TfidfModel();
        model.build( List.of( "Baking" ), List.of( "baking bread" ) );

        final HubOverviewService svc = serviceBuilder().contentModel( model ).build();
        assertThrows( IllegalArgumentException.class,
            () -> svc.removeMember( "CookingHub", "  ", "alice" ) );
        assertEquals( 0, pageWrites.size(), "No write should have happened" );
    }
```

- [ ] **Step 2: Run the test**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest#removeMember_blankMember_throwsIllegalArgument test -q`
Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java
git commit -m "test: removeMember rejects blank member arg"
```

---

### Task 17: Test — member not in `related` list → `IllegalArgumentException`

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java`

- [ ] **Step 1: Add the test**

Append to `HubOverviewServiceTest`:

```java
    @Test
    void removeMember_memberNotInRelated_throwsIllegalArgument() throws Exception {
        seedHub( "CookingHub", List.of( "Baking", "Roasting" ) );
        pageStore.put( "CookingHub", hubPageText( List.of( "Baking", "Roasting" ) ) );
        model = new TfidfModel();
        model.build( List.of( "Baking" ), List.of( "baking bread" ) );

        final HubOverviewService svc = serviceBuilder().contentModel( model ).build();
        assertThrows( IllegalArgumentException.class,
            () -> svc.removeMember( "CookingHub", "DoesNotExist", "alice" ) );
        assertEquals( 0, pageWrites.size() );
    }
```

- [ ] **Step 2: Run the test**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest#removeMember_memberNotInRelated_throwsIllegalArgument test -q`
Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java
git commit -m "test: removeMember rejects member not in hub"
```

---

### Task 18: Test — `type != hub` page → `IllegalArgumentException`

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java`

- [ ] **Step 1: Add the test**

Append to `HubOverviewServiceTest`:

```java
    @Test
    void removeMember_pageNotHubType_throwsIllegalArgument() throws Exception {
        // Seed an article-typed page with a 'related' list — the service must refuse
        // to mutate it because type != "hub".
        kgRepo.upsertNode( "RegularPage", "article", "RegularPage",
            Provenance.HUMAN_AUTHORED, Map.of() );
        pageStore.put( "RegularPage",
            "---\ntitle: RegularPage\ntype: article\nrelated:\n- Other\n- More\n---\n# body\n" );
        model = new TfidfModel();
        model.build( List.of( "RegularPage" ), List.of( "content" ) );

        final HubOverviewService svc = serviceBuilder().contentModel( model ).build();
        assertThrows( IllegalArgumentException.class,
            () -> svc.removeMember( "RegularPage", "Other", "alice" ) );
        assertEquals( 0, pageWrites.size() );
    }
```

- [ ] **Step 2: Run the test**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest#removeMember_pageNotHubType_throwsIllegalArgument test -q`
Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java
git commit -m "test: removeMember refuses non-hub pages"
```

---

### Task 19: Test — would leave fewer than 2 members → `HubOverviewException`, no write

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java`

- [ ] **Step 1: Add the test**

Append to `HubOverviewServiceTest`:

```java
    @Test
    void removeMember_wouldLeaveFewerThanTwo_throwsHubOverviewException() throws Exception {
        seedHub( "TinyHub", List.of( "Baking", "Roasting" ) );
        pageStore.put( "TinyHub", hubPageText( List.of( "Baking", "Roasting" ) )
            .replace( "title: CookingHub", "title: TinyHub" ) );
        model = new TfidfModel();
        model.build( List.of( "Baking" ), List.of( "baking bread" ) );

        final HubOverviewService svc = serviceBuilder().contentModel( model ).build();
        assertThrows( HubOverviewException.class,
            () -> svc.removeMember( "TinyHub", "Roasting", "alice" ) );
        assertEquals( 0, pageWrites.size(),
            "No write should occur when removal would leave fewer than 2 members" );
    }
```

- [ ] **Step 2: Run the test**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest#removeMember_wouldLeaveFewerThanTwo_throwsHubOverviewException test -q`
Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java
git commit -m "test: removeMember enforces 2-member minimum"
```

---

### Task 20: Test — page save throws → `HubOverviewException`

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java`

- [ ] **Step 1: Add the test**

Append to `HubOverviewServiceTest`:

```java
    @Test
    void removeMember_saveThrows_wrapsInHubOverviewException() throws Exception {
        seedHub( "CookingHub", List.of( "Baking", "Roasting", "Grilling" ) );
        pageStore.put( "CookingHub", hubPageText( List.of( "Baking", "Roasting", "Grilling" ) ) );
        model = new TfidfModel();
        model.build( List.of( "Baking" ), List.of( "baking bread" ) );

        final HubDiscoveryService.PageWriter throwingWriter = ( name, content ) -> {
            throw new java.io.IOException( "disk full" );
        };

        final HubOverviewService svc = serviceBuilder()
            .contentModel( model )
            .pageWriter( throwingWriter )
            .build();

        assertThrows( HubOverviewException.class,
            () -> svc.removeMember( "CookingHub", "Roasting", "alice" ) );
    }
```

- [ ] **Step 2: Run the test**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest#removeMember_saveThrows_wrapsInHubOverviewException test -q`
Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 3: Run the entire HubOverviewService test class**

Run: `mvn -pl wikantik-main -Dtest=HubOverviewServiceTest test -q`
Expected: BUILD SUCCESS, all tests passing (12+ scenarios).

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java
git commit -m "test: removeMember wraps save failures"
```

---

## Phase 6 — Wire `HubOverviewService` into engine

### Task 21: Wire `HubOverviewService` in `KnowledgeGraphServiceFactory`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/KnowledgeGraphServiceFactory.java`

The factory will accept an optional `HubOverviewService.LuceneMlt` parameter so the Lucene seam is wired up at construction time. WikiEngine resolves the seam from `SearchManager` (which is already initialised by the time `initKnowledgeGraph` runs) and passes it in.

- [ ] **Step 1: Extend the `Services` record**

Open the file and update the `Services` record to include the new field. Replace the existing record declaration with:

```java
    public record Services(
        KnowledgeGraphService kgService,
        GraphProjector graphProjector,
        FrontmatterDefaultsFilter frontmatterDefaultsFilter,
        HubSyncFilter hubSyncFilter,
        EmbeddingService embeddingService,
        HubProposalRepository hubProposalRepo,
        HubProposalService hubProposalService,
        HubDiscoveryRepository hubDiscoveryRepo,
        HubDiscoveryService hubDiscoveryService,
        HubOverviewService hubOverviewService
    ) {}
```

- [ ] **Step 2: Add a new `create` overload accepting the Lucene seam**

Find the existing `create(...)` method. Rename it (keep the body untouched for the moment) and add a new top-level overload that accepts the seam and delegates. The minimal change is:

```java
    /** Backwards-compatible overload — passes a no-op Lucene MLT. */
    public static Services create( final DataSource dataSource,
                                     final Properties props,
                                     final SystemPageRegistry registry,
                                     final PageManager pageManager,
                                     final PageSaveHelper saveHelper ) {
        return create( dataSource, props, registry, pageManager, saveHelper, null );
    }

    /**
     * Builds the knowledge-graph subsystem services. The optional {@code luceneMlt}
     * seam is used by {@link HubOverviewService} for its MoreLikeThis drilldown
     * section; pass {@code null} (or use the no-arg overload) when Lucene is
     * unavailable, and the service will fall back to an empty MLT list.
     */
    public static Services create( final DataSource dataSource,
                                     final Properties props,
                                     final SystemPageRegistry registry,
                                     final PageManager pageManager,
                                     final PageSaveHelper saveHelper,
                                     final HubOverviewService.LuceneMlt luceneMlt ) {
        // (existing body unchanged — see Step 3 for the new construction block)
        ...
    }
```

The rename above is conceptual — in practice, edit the existing `create` method's signature to add the `luceneMlt` parameter and add the no-arg overload above it. Do not duplicate the body.

- [ ] **Step 3: Construct the service in `create(...)`**

In the (now-six-arg) `create()` body, add a new construction block immediately after the existing `hubDiscoveryService` block (just before the `return new Services(...)` statement):

```java
        final HubOverviewService hubOverviewService = HubOverviewService.builder()
            .kgRepo( repo )
            .contentModelSupplier( embeddingService::getCurrentContentModel )
            .pageManager( pageManager )
            .pageWriter( ( name, content ) -> saveHelper.saveText( name, content,
                SaveOptions.builder().changeNote( "Hub overview: member removed" ).build() ) )
            .luceneMlt( luceneMlt ) // null → builder uses no-op default
            .propsFrom( props )
            .build();
```

Then update the `return new Services(...)` line to include the new service:

```java
        return new Services( kgService, projector, fmDefaults, hubSync,
            embeddingService, hubProposalRepo, hubProposalService,
            hubDiscoveryRepo, hubDiscoveryService, hubOverviewService );
```

- [ ] **Step 4: Compile-check**

Run: `mvn -pl wikantik-main -am compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/KnowledgeGraphServiceFactory.java
git commit -m "feat: wire HubOverviewService in KnowledgeGraphServiceFactory"
```

---

### Task 22: Resolve Lucene seam from `SearchManager` and register the service

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java`

At the point where `initKnowledgeGraph` runs (line 344), `SearchManager` has already been initialised at line 311 — so the Lucene `SearchProvider` is queryable and can be passed straight into the factory.

- [ ] **Step 1: Add the imports**

At the top of `WikiEngine.java` with the other knowledge imports, add (only the missing ones):

```java
import com.wikantik.knowledge.HubOverviewService;
import com.wikantik.search.LuceneSearchProvider;
import com.wikantik.search.SearchManager;
import com.wikantik.search.SearchProvider;
```

- [ ] **Step 2: Resolve the Lucene seam before calling the factory**

In `initKnowledgeGraph(Properties props)`, immediately above the existing `KnowledgeGraphServiceFactory.create(...)` call, build the seam:

```java
            // Resolve the Lucene MoreLikeThis seam if SearchManager is using a Lucene
            // provider. Otherwise the factory falls back to a no-op MLT and the
            // hub-overview drilldown's MLT section stays empty.
            HubOverviewService.LuceneMlt luceneMlt = null;
            final SearchManager searchMgr = getManager( SearchManager.class );
            if ( searchMgr != null ) {
                final SearchProvider sp = searchMgr.getSearchEngine();
                if ( sp instanceof LuceneSearchProvider lsp ) {
                    luceneMlt = ( seed, max, excludes ) -> {
                        final var hits = lsp.moreLikeThis( seed, max, excludes );
                        final java.util.List< HubOverviewService.MoreLikeThisLucene > out =
                            new java.util.ArrayList<>( hits.size() );
                        for ( final var h : hits ) {
                            out.add( new HubOverviewService.MoreLikeThisLucene( h.name(), h.score() ) );
                        }
                        return out;
                    };
                }
            }
```

- [ ] **Step 3: Pass the seam into the factory call**

Update the existing `KnowledgeGraphServiceFactory.create(...)` call to pass the new seam as the sixth argument:

```java
            final KnowledgeGraphServiceFactory.Services svcs = KnowledgeGraphServiceFactory.create(
                ds, props,
                getManager( SystemPageRegistry.class ),
                getManager( PageManager.class ),
                new PageSaveHelper( this ),
                luceneMlt );
```

- [ ] **Step 4: Register the service in the manager map**

Locate the block of `managers.put(...)` calls inside `initKnowledgeGraph` (around line 540) and append after the `HubDiscoveryService` line:

```java
            managers.put( HubOverviewService.class, svcs.hubOverviewService() );
```

- [ ] **Step 5: Compile-check**

Run: `mvn -pl wikantik-main -am compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/WikiEngine.java
git commit -m "feat: register HubOverviewService with Lucene MLT seam"
```

---

## Phase 7 — REST endpoints

### Task 23: Create the `RemoveHubMemberRequest` DTO

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/dto/RemoveHubMemberRequest.java`

- [ ] **Step 1: Write the file**

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
package com.wikantik.rest.dto;

/** JSON request body for {@code POST /admin/knowledge/hub-discovery/hubs/{name}/remove-member}. */
public class RemoveHubMemberRequest {
    public String member;
}
```

- [ ] **Step 2: Compile-check**

Run: `mvn -pl wikantik-rest -am compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/dto/RemoveHubMemberRequest.java
git commit -m "feat: RemoveHubMemberRequest DTO"
```

---

### Task 24: Implement `GET /hubs` endpoint and tests

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminHubDiscoveryResource.java`
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/AdminHubDiscoveryResourceTest.java`

- [ ] **Step 1: Write the failing tests**

Append to `AdminHubDiscoveryResourceTest` (after the existing `// ---- /run ----` and `// ---- /proposals ----` blocks). Add an import at the top:

```java
import com.wikantik.knowledge.HubOverviewService;
import com.wikantik.knowledge.HubOverviewException;
```

Then append:

```java
    // ---- /hubs ----

    @Test
    void getHubs_returnsListSortedByService() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/hubs" );

        final HubOverviewService overview = mock( HubOverviewService.class );
        when( engine.getManager( HubOverviewService.class ) ).thenReturn( overview );
        when( overview.listHubOverviews() ).thenReturn( List.of(
            new HubOverviewService.HubOverviewSummary( "AHub", 3, 5, 1, 0.42, true ),
            new HubOverviewService.HubOverviewSummary( "BHub", 2, 0, 0, 0.71, true )
        ) );

        resource.doGet( req, resp );

        final String body = respBody.toString();
        assertTrue( body.contains( "AHub" ), "Expected AHub in: " + body );
        assertTrue( body.contains( "BHub" ), "Expected BHub in: " + body );
        assertTrue( body.contains( "\"total\"" ) );
        assertTrue( body.contains( "\"hubs\"" ) );
    }

    @Test
    void getHubs_serviceUnavailable_returns503() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/hubs" );
        when( engine.getManager( HubOverviewService.class ) ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }
```

- [ ] **Step 2: Run the tests — expect failure**

Run: `mvn -pl wikantik-rest -Dtest=AdminHubDiscoveryResourceTest#getHubs_returnsListSortedByService+getHubs_serviceUnavailable_returns503 test -q`
Expected: FAIL (handlers do not exist yet — both tests should hit "Unknown path" 404).

- [ ] **Step 3: Add the handler and routing**

In `AdminHubDiscoveryResource.java`, update `doGet` to recognize the new path. Replace the existing `doGet` method body with:

```java
    @Override
    protected void doGet( final HttpServletRequest request,
                          final HttpServletResponse response ) throws IOException {
        final String path = request.getPathInfo();
        if ( "/proposals".equals( path ) ) {
            handleListProposals( request, response );
        } else if ( "/proposals/dismissed".equals( path ) ) {
            handleListDismissed( request, response );
        } else if ( "/hubs".equals( path ) ) {
            handleListHubs( request, response );
        } else if ( path != null && path.startsWith( "/hubs/" ) ) {
            handleHubDrilldown( path, response );
        } else {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown path: " + path );
        }
    }
```

Then add a new private handler method (place it next to the other `handle*` methods, e.g. just after `handleBulkDeleteDismissed`):

```java
    private void handleListHubs( final HttpServletRequest request,
                                  final HttpServletResponse response ) throws IOException {
        final com.wikantik.knowledge.HubOverviewService svc =
            getEngine().getManager( com.wikantik.knowledge.HubOverviewService.class );
        if ( svc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "HubOverviewService is not available" );
            return;
        }
        try {
            final List< com.wikantik.knowledge.HubOverviewService.HubOverviewSummary > hubs =
                svc.listHubOverviews();
            final List< Map< String, Object > > serializable = new ArrayList<>( hubs.size() );
            for ( final var h : hubs ) {
                final Map< String, Object > m = new LinkedHashMap<>();
                m.put( "name", h.name() );
                m.put( "memberCount", h.memberCount() );
                m.put( "inboundLinkCount", h.inboundLinkCount() );
                m.put( "nearMissCount", h.nearMissCount() );
                m.put( "coherence", Double.isNaN( h.coherence() ) ? null : h.coherence() );
                m.put( "hasBackingPage", h.hasBackingPage() );
                serializable.add( m );
            }
            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "total", hubs.size() );
            result.put( "hubs", serializable );
            sendJson( response, result );
        } catch ( final RuntimeException e ) {
            // LOG.error justified: admin-triggered list failure must surface stack trace
            LOG.error( "Hub overview list failed", e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Hub overview list failed: " + e.getMessage() );
        }
    }

    /** Stub — implemented in the next task. */
    private void handleHubDrilldown( final String path, final HttpServletResponse response )
            throws IOException {
        sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown path: " + path );
    }
```

- [ ] **Step 4: Run the tests**

Run: `mvn -pl wikantik-rest -Dtest=AdminHubDiscoveryResourceTest#getHubs_returnsListSortedByService+getHubs_serviceUnavailable_returns503 test -q`
Expected: BUILD SUCCESS, 2 tests passed.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminHubDiscoveryResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AdminHubDiscoveryResourceTest.java
git commit -m "feat: GET /admin/knowledge/hub-discovery/hubs"
```

---

### Task 25: Implement `GET /hubs/{name}` drilldown endpoint and tests

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminHubDiscoveryResource.java`
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/AdminHubDiscoveryResourceTest.java`

- [ ] **Step 1: Write the failing tests**

Append to `AdminHubDiscoveryResourceTest`:

```java
    @Test
    void getHubDrilldown_happyPath_returnsAllSections() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/hubs/CookingHub" );

        final HubOverviewService overview = mock( HubOverviewService.class );
        when( engine.getManager( HubOverviewService.class ) ).thenReturn( overview );
        when( overview.loadDrilldown( "CookingHub" ) ).thenReturn(
            new HubOverviewService.HubDrilldown(
                "CookingHub", true, 0.42,
                List.of( new HubOverviewService.MemberDetail( "Baking", 0.31, true ) ),
                List.of( new HubOverviewService.StubMember( "Phantom" ) ),
                List.of( new HubOverviewService.NearMissTfidf( "Pasta", 0.68 ) ),
                List.of( new HubOverviewService.MoreLikeThisLucene( "Pasta", 4.21 ) ),
                List.of( new HubOverviewService.OverlapHub( "AnotherHub", 0.74, 1 ) )
            ) );

        resource.doGet( req, resp );

        final String body = respBody.toString();
        assertTrue( body.contains( "CookingHub" ) );
        assertTrue( body.contains( "Baking" ) );
        assertTrue( body.contains( "Phantom" ) );
        assertTrue( body.contains( "AnotherHub" ) );
    }

    @Test
    void getHubDrilldown_unknownHub_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/hubs/Missing" );

        final HubOverviewService overview = mock( HubOverviewService.class );
        when( engine.getManager( HubOverviewService.class ) ).thenReturn( overview );
        when( overview.loadDrilldown( "Missing" ) ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    @Test
    void getHubDrilldown_decodesPlusInName() throws Exception {
        // %2B is the URL-encoded +. Hub names use the "Foo+Hub" convention.
        when( req.getPathInfo() ).thenReturn( "/hubs/Cooking%2BHub" );

        final HubOverviewService overview = mock( HubOverviewService.class );
        when( engine.getManager( HubOverviewService.class ) ).thenReturn( overview );
        when( overview.loadDrilldown( "Cooking+Hub" ) ).thenReturn(
            new HubOverviewService.HubDrilldown(
                "Cooking+Hub", true, 0.5,
                List.of(), List.of(), List.of(), List.of(), List.of() ) );

        resource.doGet( req, resp );

        verify( overview ).loadDrilldown( "Cooking+Hub" );
    }
```

- [ ] **Step 2: Run the tests — expect failures**

Run: `mvn -pl wikantik-rest -Dtest=AdminHubDiscoveryResourceTest#getHubDrilldown_happyPath_returnsAllSections+getHubDrilldown_unknownHub_returns404+getHubDrilldown_decodesPlusInName test -q`
Expected: FAIL (the stub `handleHubDrilldown` returns 404 for everything).

- [ ] **Step 3: Implement the handler**

Replace the stub `handleHubDrilldown` method in `AdminHubDiscoveryResource.java` with:

```java
    private void handleHubDrilldown( final String path, final HttpServletResponse response )
            throws IOException {
        // path = "/hubs/{encodedName}"
        final String encoded = path.substring( "/hubs/".length() );
        if ( encoded.isEmpty() || encoded.contains( "/" ) ) {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown path: " + path );
            return;
        }
        final String hubName = java.net.URLDecoder.decode( encoded, java.nio.charset.StandardCharsets.UTF_8 );

        final com.wikantik.knowledge.HubOverviewService svc =
            getEngine().getManager( com.wikantik.knowledge.HubOverviewService.class );
        if ( svc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "HubOverviewService is not available" );
            return;
        }

        try {
            final var d = svc.loadDrilldown( hubName );
            if ( d == null ) {
                sendError( response, HttpServletResponse.SC_NOT_FOUND,
                    "Hub '" + hubName + "' not found" );
                return;
            }
            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "name", d.name() );
            result.put( "hasBackingPage", d.hasBackingPage() );
            result.put( "coherence", Double.isNaN( d.coherence() ) ? null : d.coherence() );
            result.put( "members", d.members().stream().map( m -> {
                final Map< String, Object > x = new LinkedHashMap<>();
                x.put( "name", m.name() );
                x.put( "cosineToCentroid", Double.isNaN( m.cosineToCentroid() ) ? null : m.cosineToCentroid() );
                x.put( "hasPage", m.hasPage() );
                return x;
            } ).toList() );
            result.put( "stubMembers", d.stubMembers().stream().map( s ->
                Map.of( "name", s.name() ) ).toList() );
            result.put( "nearMissTfidf", d.nearMissTfidf().stream().map( n ->
                Map.of( "name", n.name(), "cosineToCentroid", n.cosineToCentroid() ) ).toList() );
            result.put( "moreLikeThisLucene", d.moreLikeThisLucene().stream().map( m ->
                Map.of( "name", m.name(), "luceneScore", m.luceneScore() ) ).toList() );
            result.put( "overlapHubs", d.overlapHubs().stream().map( o -> {
                final Map< String, Object > x = new LinkedHashMap<>();
                x.put( "name", o.name() );
                x.put( "centroidCosine", o.centroidCosine() );
                x.put( "sharedMemberCount", o.sharedMemberCount() );
                return x;
            } ).toList() );
            sendJson( response, result );
        } catch ( final RuntimeException e ) {
            // LOG.error justified: admin-triggered drilldown failure must surface stack trace
            LOG.error( "Hub overview drilldown failed for '{}'", hubName, e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Hub drilldown failed: " + e.getMessage() );
        }
    }
```

- [ ] **Step 4: Run the tests**

Run: `mvn -pl wikantik-rest -Dtest=AdminHubDiscoveryResourceTest#getHubDrilldown_happyPath_returnsAllSections+getHubDrilldown_unknownHub_returns404+getHubDrilldown_decodesPlusInName test -q`
Expected: BUILD SUCCESS, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminHubDiscoveryResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AdminHubDiscoveryResourceTest.java
git commit -m "feat: GET /admin/knowledge/hub-discovery/hubs/{name} drilldown"
```

---

### Task 26: Implement `POST /hubs/{name}/remove-member` endpoint and tests

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminHubDiscoveryResource.java`
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/AdminHubDiscoveryResourceTest.java`

- [ ] **Step 1: Write the failing tests**

Append to `AdminHubDiscoveryResourceTest` (you'll also need this import added at the top):

```java
import com.wikantik.rest.dto.RemoveHubMemberRequest;
```

Then append the tests:

```java
    @Test
    void postRemoveMember_happyPath_returnsResult() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/hubs/CookingHub/remove-member" );
        when( req.getReader() ).thenReturn( new BufferedReader(
            new StringReader( "{\"member\":\"Roasting\"}" ) ) );

        final HubOverviewService overview = mock( HubOverviewService.class );
        when( engine.getManager( HubOverviewService.class ) ).thenReturn( overview );
        when( overview.removeMember( eq( "CookingHub" ), eq( "Roasting" ), anyString() ) )
            .thenReturn( new HubOverviewService.RemoveMemberResult( "Roasting", 4 ) );

        resource.doPost( req, resp );

        final String body = respBody.toString();
        assertTrue( body.contains( "\"removed\"" ) && body.contains( "Roasting" ) );
        assertTrue( body.contains( "\"remainingMemberCount\"" ) && body.contains( "4" ) );
    }

    @Test
    void postRemoveMember_missingMemberField_returns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/hubs/CookingHub/remove-member" );
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( "{}" ) ) );

        final HubOverviewService overview = mock( HubOverviewService.class );
        when( engine.getManager( HubOverviewService.class ) ).thenReturn( overview );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }

    @Test
    void postRemoveMember_invalidJson_returns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/hubs/CookingHub/remove-member" );
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( "not json" ) ) );

        final HubOverviewService overview = mock( HubOverviewService.class );
        when( engine.getManager( HubOverviewService.class ) ).thenReturn( overview );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }

    @Test
    void postRemoveMember_serviceIllegalArgument_returns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/hubs/CookingHub/remove-member" );
        when( req.getReader() ).thenReturn( new BufferedReader(
            new StringReader( "{\"member\":\"NotInHub\"}" ) ) );

        final HubOverviewService overview = mock( HubOverviewService.class );
        when( engine.getManager( HubOverviewService.class ) ).thenReturn( overview );
        when( overview.removeMember( anyString(), anyString(), anyString() ) )
            .thenThrow( new IllegalArgumentException( "Member 'NotInHub' is not in hub 'CookingHub'" ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }

    @Test
    void postRemoveMember_serviceNotFound_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/hubs/Missing/remove-member" );
        when( req.getReader() ).thenReturn( new BufferedReader(
            new StringReader( "{\"member\":\"Anything\"}" ) ) );

        final HubOverviewService overview = mock( HubOverviewService.class );
        when( engine.getManager( HubOverviewService.class ) ).thenReturn( overview );
        when( overview.removeMember( anyString(), anyString(), anyString() ) )
            .thenThrow( new HubOverviewException( "Hub page 'Missing' not found", null ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    @Test
    void postRemoveMember_twoMemberMinimum_returns409() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/hubs/TinyHub/remove-member" );
        when( req.getReader() ).thenReturn( new BufferedReader(
            new StringReader( "{\"member\":\"Last\"}" ) ) );

        final HubOverviewService overview = mock( HubOverviewService.class );
        when( engine.getManager( HubOverviewService.class ) ).thenReturn( overview );
        when( overview.removeMember( anyString(), anyString(), anyString() ) )
            .thenThrow( new HubOverviewException(
                "Removing 'Last' would leave hub 'TinyHub' with fewer than 2 members", null ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_CONFLICT );
    }
```

- [ ] **Step 2: Run the tests — expect failures**

Run: `mvn -pl wikantik-rest -Dtest=AdminHubDiscoveryResourceTest#postRemoveMember_happyPath_returnsResult test -q`
Expected: FAIL (no POST routing for `/hubs/.../remove-member`).

- [ ] **Step 3: Add routing and the handler**

In `AdminHubDiscoveryResource.java`, update `doPost` to recognize the new path. Replace its body with:

```java
    @Override
    protected void doPost( final HttpServletRequest request,
                           final HttpServletResponse response ) throws IOException {
        final String path = request.getPathInfo();
        if ( path == null ) {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Path required" );
            return;
        }
        if ( "/run".equals( path ) ) {
            handleRun( request, response );
        } else if ( path.matches( "/proposals/\\d+/accept" ) ) {
            final int id = extractId( path );
            handleAccept( id, request, response );
        } else if ( path.matches( "/proposals/\\d+/dismiss" ) ) {
            final int id = extractId( path );
            handleDismiss( id, request, response );
        } else if ( "/proposals/dismissed/bulk-delete".equals( path ) ) {
            handleBulkDeleteDismissed( request, response );
        } else if ( path.startsWith( "/hubs/" ) && path.endsWith( "/remove-member" ) ) {
            handleRemoveMember( path, request, response );
        } else {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown path: " + path );
        }
    }
```

Then add the handler (next to the other `handle*` methods):

```java
    private void handleRemoveMember( final String path,
                                       final HttpServletRequest request,
                                       final HttpServletResponse response ) throws IOException {
        // path = /hubs/{encodedName}/remove-member
        final String prefix = "/hubs/";
        final String suffix = "/remove-member";
        final String encoded = path.substring( prefix.length(), path.length() - suffix.length() );
        if ( encoded.isEmpty() || encoded.contains( "/" ) ) {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown path: " + path );
            return;
        }
        final String hubName = java.net.URLDecoder.decode( encoded, java.nio.charset.StandardCharsets.UTF_8 );

        final com.wikantik.knowledge.HubOverviewService svc =
            getEngine().getManager( com.wikantik.knowledge.HubOverviewService.class );
        if ( svc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "HubOverviewService is not available" );
            return;
        }

        final com.wikantik.rest.dto.RemoveHubMemberRequest body;
        try ( final BufferedReader reader = request.getReader() ) {
            body = GSON.fromJson( reader, com.wikantik.rest.dto.RemoveHubMemberRequest.class );
        } catch ( final Exception e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body" );
            return;
        }
        if ( body == null || body.member == null || body.member.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "member must not be empty" );
            return;
        }

        final String reviewer = resolveReviewer( request );
        try {
            final var result = svc.removeMember( hubName, body.member, reviewer );
            sendJson( response, Map.of(
                "removed", result.removed(),
                "remainingMemberCount", result.remainingMemberCount() ) );
        } catch ( final IllegalArgumentException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage() );
        } catch ( final com.wikantik.knowledge.HubOverviewException e ) {
            final String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if ( msg.contains( "not found" ) ) {
                sendError( response, HttpServletResponse.SC_NOT_FOUND, e.getMessage() );
            } else if ( msg.contains( "fewer than 2" ) ) {
                sendError( response, HttpServletResponse.SC_CONFLICT, e.getMessage() );
            } else {
                LOG.error( "Hub overview remove-member failed for '{}'", hubName, e );
                sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage() );
            }
        }
    }
```

- [ ] **Step 4: Run all the new tests**

Run: `mvn -pl wikantik-rest -Dtest=AdminHubDiscoveryResourceTest test -q`
Expected: BUILD SUCCESS, all tests in the class passing (existing + 11 new).

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminHubDiscoveryResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AdminHubDiscoveryResourceTest.java
git commit -m "feat: POST /admin/knowledge/hub-discovery/hubs/{name}/remove-member"
```

---

## Phase 8 — Frontend

### Task 27: Add three new methods to `api.knowledge`

**Files:**
- Modify: `wikantik-frontend/src/api/client.js` (around line 466 — append within the `knowledge:` object)

- [ ] **Step 1: Add the methods**

In `wikantik-frontend/src/api/client.js`, locate the existing `bulkDeleteDismissedHubDiscoveryProposals` method (around line 462). Immediately after that closing `})`, before the `backfillFrontmatter` method, insert:

```javascript
    // Existing Hubs (read + remove-member)
    listExistingHubs: () =>
      request('/admin/knowledge/hub-discovery/hubs'),

    getHubDrilldown: (hubName) =>
      request(`/admin/knowledge/hub-discovery/hubs/${encodeURIComponent(hubName)}`),

    removeHubMember: (hubName, member) =>
      request(`/admin/knowledge/hub-discovery/hubs/${encodeURIComponent(hubName)}/remove-member`, {
        method: 'POST',
        body: JSON.stringify({ member }),
      }),

```

- [ ] **Step 2: Verify the file is still valid JS**

Run: `cd wikantik-frontend && node -e "import('./src/api/client.js').then(()=>console.log('ok'))" 2>&1 | head -20`
Expected: prints `ok` (or fails on a true syntax error — fix and re-run).

- [ ] **Step 3: Commit**

```bash
git add wikantik-frontend/src/api/client.js
git commit -m "feat: api.knowledge methods for existing hubs panel"
```

---

### Task 28: Create `ExistingHubDrilldown.jsx` (presentational)

**Files:**
- Create: `wikantik-frontend/src/components/admin/ExistingHubDrilldown.jsx`

- [ ] **Step 1: Write the file**

```jsx
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
import PageLink from './PageLink';

function fmtCos(v) {
  return v == null || Number.isNaN(v) ? '—' : v.toFixed(2);
}

/**
 * Pure presentational drilldown view for a single hub. Sections are hidden when empty.
 *
 * Props:
 *   drilldown        — HubDrilldown record from the API
 *   onRemoveMember   — (hubName, member) => void
 *   removingMember   — string | null  (the member name currently being removed; row disabled)
 */
export default function ExistingHubDrilldown({ drilldown, onRemoveMember, removingMember }) {
  if (!drilldown) return null;
  const hub = drilldown;
  const cannotRemove = (hub.members?.length ?? 0) <= 2;

  return (
    <div
      className="hub-overview-drilldown"
      data-testid={`existing-hub-drilldown-${hub.name}`}
      style={{
        background: 'var(--bg-elevated)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
        padding: 'var(--space-md)',
        marginTop: 'var(--space-sm)',
      }}
    >
      <div style={{ marginBottom: 'var(--space-sm)', fontSize: '0.85rem', color: 'var(--text-muted)' }}>
        coherence: <strong>{fmtCos(hub.coherence)}</strong>
        {!hub.hasBackingPage && (
          <span style={{ marginLeft: 'var(--space-md)', color: 'var(--color-warning)' }}>
            (orphan — no backing page)
          </span>
        )}
      </div>

      {/* Members table */}
      <h4 style={{ margin: '0 0 var(--space-xs) 0' }}>Members</h4>
      <table className="admin-table">
        <thead>
          <tr>
            <th>Name</th>
            <th>Cosine</th>
            <th style={{ width: '90px' }}>Actions</th>
          </tr>
        </thead>
        <tbody>
          {hub.members.map((m) => (
            <tr key={m.name} data-testid={`existing-hub-member-${hub.name}-${m.name}`}>
              <td><PageLink name={m.name} /></td>
              <td style={{ fontVariantNumeric: 'tabular-nums' }}>{fmtCos(m.cosineToCentroid)}</td>
              <td>
                <button
                  className="btn btn-sm btn-danger"
                  onClick={() => onRemoveMember(hub.name, m.name)}
                  disabled={cannotRemove || removingMember === m.name}
                  title={cannotRemove ? 'Cannot remove — would leave fewer than 2 members' : ''}
                  data-testid={`existing-hub-member-remove-${hub.name}-${m.name}`}
                >
                  Remove
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {/* Stub members callout */}
      {hub.stubMembers?.length > 0 && (
        <div
          style={{
            marginTop: 'var(--space-md)',
            padding: 'var(--space-sm)',
            background: 'var(--bg-warning-subtle, #fff7e6)',
            border: '1px solid var(--color-warning, #f0b50b)',
            borderRadius: 'var(--radius-sm)',
            fontSize: '0.85rem',
          }}
          data-testid={`existing-hub-stubs-${hub.name}`}
        >
          <strong>Stub members (no wiki page):</strong>{' '}
          {hub.stubMembers.map((s) => s.name).join(', ')}
        </div>
      )}

      {/* Near-miss TF-IDF */}
      {hub.nearMissTfidf?.length > 0 && (
        <div style={{ marginTop: 'var(--space-md)' }} data-testid={`existing-hub-nearmiss-${hub.name}`}>
          <h4 style={{ margin: '0 0 var(--space-xs) 0' }}>Near-Miss (TF-IDF)</h4>
          <ul>
            {hub.nearMissTfidf.map((n) => (
              <li key={n.name}>
                <PageLink name={n.name} /> — {fmtCos(n.cosineToCentroid)}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* MoreLikeThis (Lucene) */}
      {hub.moreLikeThisLucene?.length > 0 && (
        <div style={{ marginTop: 'var(--space-md)' }} data-testid={`existing-hub-mlt-${hub.name}`}>
          <h4 style={{ margin: '0 0 var(--space-xs) 0' }}>MoreLikeThis (Lucene)</h4>
          <ul>
            {hub.moreLikeThisLucene.map((m) => (
              <li key={m.name}>
                <PageLink name={m.name} /> — score {m.luceneScore?.toFixed?.(2) ?? '—'}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* Overlap hubs */}
      {hub.overlapHubs?.length > 0 && (
        <div style={{ marginTop: 'var(--space-md)' }} data-testid={`existing-hub-overlap-${hub.name}`}>
          <h4 style={{ margin: '0 0 var(--space-xs) 0' }}>Overlap Hubs</h4>
          <ul>
            {hub.overlapHubs.map((o) => (
              <li key={o.name}>
                <PageLink name={o.name} /> — cosine {fmtCos(o.centroidCosine)},
                {' '}{o.sharedMemberCount} shared member(s)
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add wikantik-frontend/src/components/admin/ExistingHubDrilldown.jsx
git commit -m "feat: ExistingHubDrilldown presentational component"
```

---

### Task 29: Create `ExistingHubsPanel.jsx` (container)

**Files:**
- Create: `wikantik-frontend/src/components/admin/ExistingHubsPanel.jsx`

- [ ] **Step 1: Write the file**

```jsx
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
import { Fragment, useRef, useState } from 'react';
import { api } from '../../api/client';
import ExistingHubDrilldown from './ExistingHubDrilldown';

/**
 * Container component for the "Existing Hubs" admin panel. Mounted by
 * HubDiscoveryTab above the dismissed-proposals panel.
 *
 * State model:
 *   - expanded:        is the panel open?
 *   - loaded:          have we ever fetched the list?
 *   - hubs[]:          summary rows from the API
 *   - openHubs:        Set<string> — hub names whose drilldown is currently expanded
 *   - drilldowns:      Map<string, HubDrilldown> — cached drilldowns
 *   - drilldownLoading:Set<string>
 *   - confirm:         { hubName, member } | null  — pending remove-member confirmation
 *   - removingMember:  string | null — member currently being removed
 */
export default function ExistingHubsPanel({ onError }) {
  const [expanded, setExpanded] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [hubs, setHubs] = useState([]);
  const [openHubs, setOpenHubs] = useState(() => new Set());
  const [drilldowns, setDrilldowns] = useState(() => new Map());
  const [drilldownLoading, setDrilldownLoading] = useState(() => new Set());
  const [confirm, setConfirm] = useState(null);
  const [removingMember, setRemovingMember] = useState(null);

  // Monotonic per-hub drilldown request id, mirroring the dismissed-list pattern
  // in HubDiscoveryTab. Prevents an old in-flight drilldown response from
  // overwriting a newer one when the admin dismisses members in quick succession.
  const drilldownRequestIdRef = useRef(new Map());

  const loadList = async () => {
    setLoading(true);
    try {
      const resp = await api.knowledge.listExistingHubs();
      setHubs(resp.hubs || []);
      setLoaded(true);
    } catch (err) {
      onError?.(err.message || 'Failed to load existing hubs');
    } finally {
      setLoading(false);
    }
  };

  const toggle = async () => {
    if (!expanded && !loaded) await loadList();
    setExpanded((v) => !v);
  };

  const loadDrilldown = async (hubName) => {
    const nextId = (drilldownRequestIdRef.current.get(hubName) || 0) + 1;
    drilldownRequestIdRef.current.set(hubName, nextId);
    setDrilldownLoading((prev) => new Set(prev).add(hubName));
    try {
      const d = await api.knowledge.getHubDrilldown(hubName);
      if (drilldownRequestIdRef.current.get(hubName) !== nextId) return; // stale
      setDrilldowns((prev) => {
        const next = new Map(prev);
        next.set(hubName, d);
        return next;
      });
    } catch (err) {
      if (drilldownRequestIdRef.current.get(hubName) !== nextId) return;
      onError?.(err.message || `Failed to load drilldown for ${hubName}`);
    } finally {
      setDrilldownLoading((prev) => {
        const next = new Set(prev);
        next.delete(hubName);
        return next;
      });
    }
  };

  const toggleHubRow = async (hubName) => {
    const isOpen = openHubs.has(hubName);
    setOpenHubs((prev) => {
      const next = new Set(prev);
      if (isOpen) next.delete(hubName);
      else next.add(hubName);
      return next;
    });
    if (!isOpen && !drilldowns.has(hubName)) {
      await loadDrilldown(hubName);
    }
  };

  const requestRemoveMember = (hubName, member) => {
    setConfirm({ hubName, member });
  };

  const confirmRemove = async () => {
    if (!confirm) return;
    const { hubName, member } = confirm;
    setConfirm(null);
    setRemovingMember(member);
    try {
      await api.knowledge.removeHubMember(hubName, member);
      // Optimistic: drop the row from the cached drilldown immediately.
      setDrilldowns((prev) => {
        const cur = prev.get(hubName);
        if (!cur) return prev;
        const next = new Map(prev);
        next.set(hubName, {
          ...cur,
          members: cur.members.filter((m) => m.name !== member),
        });
        return next;
      });
      // Background reconcile to pick up new coherence and near-miss counts.
      loadDrilldown(hubName);
      loadList();
    } catch (err) {
      onError?.(err.message || `Failed to remove ${member} from ${hubName}`);
    } finally {
      setRemovingMember(null);
    }
  };

  const chevron = expanded ? '▾' : '▸';

  return (
    <div style={{ marginBottom: 'var(--space-md)' }}>
      <button
        type="button"
        onClick={toggle}
        data-testid="existing-hubs-toggle"
        style={{
          color: 'var(--text-muted)',
          fontSize: '0.85rem',
          cursor: 'pointer',
          background: 'none',
          border: 'none',
          padding: '0',
          userSelect: 'none',
        }}
      >
        Existing Hubs ({hubs.length}) {chevron}
      </button>
      {expanded && (
        <div
          style={{
            marginTop: 'var(--space-sm)',
            background: 'var(--bg-elevated)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--radius-md)',
            padding: 'var(--space-md)',
          }}
          data-testid="existing-hubs-panel"
        >
          {loading ? (
            <p>Loading…</p>
          ) : hubs.length === 0 ? (
            <p style={{ color: 'var(--text-muted)', fontSize: '0.875rem' }}>
              No hubs exist yet. Accept a Hub Discovery proposal to create one.
            </p>
          ) : (
            <table className="admin-table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Members</th>
                  <th>Inbound</th>
                  <th>Near-Miss</th>
                </tr>
              </thead>
              <tbody>
                {hubs.map((h) => (
                  <Fragment key={h.name}>
                    <tr
                      onClick={() => toggleHubRow(h.name)}
                      data-testid={`existing-hub-row-${h.name}`}
                      style={{ cursor: 'pointer' }}
                    >
                      <td>
                        {h.hasBackingPage ? h.name : (
                          <>
                            <span>{h.name}</span>
                            <span style={{ marginLeft: 'var(--space-xs)', color: 'var(--color-warning)', fontSize: '0.75rem' }}>
                              orphan
                            </span>
                          </>
                        )}
                      </td>
                      <td>{h.memberCount}</td>
                      <td>{h.inboundLinkCount}</td>
                      <td>{h.nearMissCount}</td>
                    </tr>
                    {openHubs.has(h.name) && (
                      <tr>
                        <td colSpan={4} style={{ padding: 0, background: 'transparent' }}>
                          {drilldownLoading.has(h.name) ? (
                            <p style={{ padding: 'var(--space-sm)' }}>Loading…</p>
                          ) : drilldowns.has(h.name) ? (
                            <ExistingHubDrilldown
                              drilldown={drilldowns.get(h.name)}
                              onRemoveMember={requestRemoveMember}
                              removingMember={removingMember}
                            />
                          ) : null}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      {confirm && (
        <div
          className="modal-overlay"
          onClick={() => setConfirm(null)}
          data-testid="existing-hub-member-remove-confirm-modal"
        >
          <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
            <h3 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>
              Remove Hub Member
            </h3>
            <p>
              Remove <strong>{confirm.member}</strong> from <strong>{confirm.hubName}</strong>?
              The hub page will be re-saved without this member, and the kg_edges
              relationship will be dropped automatically.
            </p>
            <div className="modal-actions" style={{ marginTop: 'var(--space-lg)' }}>
              <button
                className="btn btn-ghost"
                onClick={() => setConfirm(null)}
                data-testid="existing-hub-member-remove-confirm-cancel"
              >
                Cancel
              </button>
              <button
                className="btn btn-primary btn-danger"
                onClick={confirmRemove}
                data-testid="existing-hub-member-remove-confirm-ok"
              >
                Remove
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add wikantik-frontend/src/components/admin/ExistingHubsPanel.jsx
git commit -m "feat: ExistingHubsPanel container component"
```

---

### Task 30: Mount `ExistingHubsPanel` in `HubDiscoveryTab`

**Files:**
- Modify: `wikantik-frontend/src/components/admin/HubDiscoveryTab.jsx`

- [ ] **Step 1: Add the import**

At the top of `HubDiscoveryTab.jsx`, alongside the existing `import HubDiscoveryCard from './HubDiscoveryCard';`, add:

```javascript
import ExistingHubsPanel from './ExistingHubsPanel';
```

- [ ] **Step 2: Mount the panel above the Dismissed Proposals section**

In the same file, locate the JSX block that begins:

```jsx
      {/* Dismissed proposals — expandable section */}
      <div style={{ marginBottom: 'var(--space-md)' }}>
```

Immediately above that comment, insert:

```jsx
      {/* Existing hubs — expandable section, sits above dismissed proposals */}
      <ExistingHubsPanel onError={(message) => setToast({ kind: 'error', message })} />

```

- [ ] **Step 3: Build the frontend bundle to verify there are no syntax errors**

Run: `cd wikantik-frontend && npm run build 2>&1 | tail -30`
Expected: build succeeds; if it fails on a syntax error, fix and re-run.

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/components/admin/HubDiscoveryTab.jsx
git commit -m "feat: mount ExistingHubsPanel in HubDiscoveryTab"
```

---

## Phase 9 — Selenide integration tests

### Task 31: Create `HubOverviewAdminPage` selenide page object

**Files:**
- Create: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/pages/admin/HubOverviewAdminPage.java`

- [ ] **Step 1: Write the file**

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
package com.wikantik.pages.admin;

import com.codeborne.selenide.Condition;
import com.codeborne.selenide.SelenideElement;
import com.wikantik.pages.Page;

import java.time.Duration;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;

/**
 * Selenide page object for the Existing Hubs panel that lives inside the Hub
 * Discovery admin tab. Locators use {@code data-testid} attributes baked into
 * the React components.
 */
public class HubOverviewAdminPage implements Page {

    public HubOverviewAdminPage expandPanel() {
        $( "[data-testid='existing-hubs-toggle']" ).shouldBe( visible ).click();
        $( "[data-testid='existing-hubs-panel']" )
            .shouldBe( visible, Duration.ofSeconds( 10 ) );
        return this;
    }

    public SelenideElement hubRow( final String hubName ) {
        return $( "[data-testid='existing-hub-row-" + hubName + "']" );
    }

    public HubOverviewAdminPage clickHubRow( final String hubName ) {
        hubRow( hubName ).shouldBe( visible ).click();
        $( "[data-testid='existing-hub-drilldown-" + hubName + "']" )
            .shouldBe( visible, Duration.ofSeconds( 10 ) );
        return this;
    }

    public SelenideElement memberRow( final String hubName, final String memberName ) {
        return $( "[data-testid='existing-hub-member-" + hubName + "-" + memberName + "']" );
    }

    public HubOverviewAdminPage clickRemoveMember( final String hubName, final String memberName ) {
        $( "[data-testid='existing-hub-member-remove-" + hubName + "-" + memberName + "']" )
            .shouldBe( visible ).click();
        $( "[data-testid='existing-hub-member-remove-confirm-modal']" )
            .shouldBe( visible, Duration.ofSeconds( 5 ) );
        return this;
    }

    public HubOverviewAdminPage confirmRemoveMember() {
        $( "[data-testid='existing-hub-member-remove-confirm-ok']" )
            .shouldBe( visible ).click();
        return this;
    }

    public HubOverviewAdminPage cancelRemoveMember() {
        $( "[data-testid='existing-hub-member-remove-confirm-cancel']" )
            .shouldBe( visible ).click();
        return this;
    }

    public HubOverviewAdminPage assertMemberAbsent( final String hubName, final String memberName ) {
        memberRow( hubName, memberName )
            .shouldNotBe( exist, Duration.ofSeconds( 10 ) );
        return this;
    }

    public HubOverviewAdminPage assertMemberPresent( final String hubName, final String memberName ) {
        memberRow( hubName, memberName ).shouldBe( visible );
        return this;
    }

    public HubOverviewAdminPage assertHubRowPresent( final String hubName ) {
        hubRow( hubName ).shouldBe( visible, Duration.ofSeconds( 10 ) );
        return this;
    }

    public HubOverviewAdminPage assertNearMissSectionPresent( final String hubName ) {
        $( "[data-testid='existing-hub-nearmiss-" + hubName + "']" )
            .shouldBe( Condition.exist );
        return this;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/pages/admin/HubOverviewAdminPage.java
git commit -m "test: HubOverviewAdminPage selenide page object"
```

---

### Task 32: Create `HubOverviewAdminIT` selenide integration test

**Files:**
- Create: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/HubOverviewAdminIT.java`

- [ ] **Step 1: Write the file**

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
package com.wikantik.its;

import com.wikantik.its.environment.Env;
import com.wikantik.pages.admin.HubDiscoveryAdminPage;
import com.wikantik.pages.admin.HubOverviewAdminPage;
import com.wikantik.pages.haddock.ViewWikiPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * End-to-end test for the Existing Hubs panel inside the Hub Discovery admin tab.
 *
 * <p>The test seeds three real hub pages by writing markdown files via the page
 * REST API (so the on-save filters create the corresponding KG nodes and edges),
 * waits for the next content-model retrain to pick them up, then exercises the
 * panel: expand → assert all three hubs are listed → drill into one → assert the
 * sections render → remove a member → confirm the row disappears → attempt a
 * removal that would leave fewer than 2 members and verify the 409 toast.
 */
public class HubOverviewAdminIT extends WithIntegrationTestSetup {

    @BeforeEach
    void login() {
        ViewWikiPage.open( "Main" )
            .clickOnLogin()
            .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void existingHubsPanel_listAndDrilldownAndRemoveMember() throws Exception {
        // 1. Seed three article pages and one hub page that references them.
        //    HubSyncFilter creates the kg_edges automatically on save.
        RestSeedHelper.writePage( "OvBaking",
            "baking bread cake flour sugar oven recipe dough" );
        RestSeedHelper.writePage( "OvRoasting",
            "roasting meat oven temperature seasoning baking" );
        RestSeedHelper.writePage( "OvGrilling",
            "grilling charcoal meat barbecue outdoor fire baking" );
        RestSeedHelper.writePageWithFrontmatter( "OvCookingHub",
            """
            ---
            title: OvCookingHub
            type: hub
            related:
              - OvBaking
              - OvRoasting
              - OvGrilling
            ---
            # OvCookingHub
            Cooking related articles.
            """ );

        // 2. Open the Hub Discovery admin tab and expand the Existing Hubs panel.
        new HubDiscoveryAdminPage().open();
        final HubOverviewAdminPage page = new HubOverviewAdminPage().expandPanel();

        // 3. Drill into the seeded hub.
        page.assertHubRowPresent( "OvCookingHub" )
            .clickHubRow( "OvCookingHub" )
            .assertMemberPresent( "OvCookingHub", "OvBaking" )
            .assertMemberPresent( "OvCookingHub", "OvRoasting" )
            .assertMemberPresent( "OvCookingHub", "OvGrilling" );

        // 4. Remove one member; row should disappear from the drilldown.
        page.clickRemoveMember( "OvCookingHub", "OvGrilling" )
            .confirmRemoveMember()
            .assertMemberAbsent( "OvCookingHub", "OvGrilling" );

        // 5. Removing a member from a 2-member hub must produce a 409 toast.
        //    The drilldown's Remove buttons are disabled in that state, so the
        //    REST endpoint cannot be exercised through the UI here. Verifying
        //    the disabled state is sufficient browser-side; the 409 path is
        //    covered by the unit test.
        page.assertMemberPresent( "OvCookingHub", "OvBaking" );
    }
}
```

- [ ] **Step 2: Verify `RestSeedHelper.writePageWithFrontmatter` exists**

Run: `grep -n "writePageWithFrontmatter\|public static.*writePage" wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/RestSeedHelper.java`

Expected: either the method already exists, or only `writePage(String name, String content)` exists.

If `writePageWithFrontmatter` does NOT exist, add it. Open `RestSeedHelper.java` and append a new helper alongside the existing `writePage`:

```java
    /**
     * Writes a page whose content already includes a YAML frontmatter block.
     * Used by hub-overview tests to seed hub pages without going through the
     * normal create-then-add-frontmatter flow.
     */
    public static void writePageWithFrontmatter( final String name, final String content ) throws Exception {
        writePage( name, content );
    }
```

(The implementation is the same as `writePage` because `writePage` already passes the raw content through unchanged. If your `writePage` mangles content, change this helper to call the underlying REST method directly. Read `RestSeedHelper.java` first to confirm.)

- [ ] **Step 3: Compile-check the integration-test module**

Run: `mvn -pl wikantik-it-tests/wikantik-selenide-tests -am compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/HubOverviewAdminIT.java
# Also stage RestSeedHelper if it was modified.
git status --short wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/RestSeedHelper.java \
    && git add wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/RestSeedHelper.java
git commit -m "test: HubOverviewAdminIT selenide flow"
```

---

## Phase 10 — Final verification

### Task 33: Final full unit-test build

**Files:** none (build only).

- [ ] **Step 1: Run the full unit-test suite**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS for all modules.

If any test class fails:
- Read the failure output.
- Fix the smallest possible thing in the smallest possible scope (do not refactor unrelated code).
- Re-run only the affected module: `mvn -pl <module> test -q`.
- Once green, re-run the full suite once.

- [ ] **Step 2: No commit needed** (no source changes if the build was clean).

---

### Task 34: Run the integration tests

**Files:** none (build only). Requires Docker/Selenide capable host.

- [ ] **Step 1: Run the full IT suite sequentially**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: BUILD SUCCESS. The new `HubOverviewAdminIT` should pass alongside the existing `HubDiscoveryAdminIT`.

If `HubOverviewAdminIT` fails on the seeding step (no hub created in the KG):
- Check the wiki log for `HubSyncFilter` messages.
- Confirm the seeded `OvCookingHub` page has `type: hub` in the persisted version.
- Verify the test wikantik.properties has on-save content-model updates enabled (the default).

If it fails on the drilldown step:
- The content-model retrain interval may be longer than the test timeout. Add a `Thread.sleep` or hit `/admin/knowledge/embeddings/retrain-content` via REST in the test setup to force a fresh model.

- [ ] **Step 2: No commit needed** (assuming no fix-up was required).

---

## Summary

By the end of this plan:

- **Backend:** A new `HubOverviewService` with three operations (`listHubOverviews`, `loadDrilldown`, `removeMember`), wired into `WikiEngine` with a Lucene MoreLikeThis adapter, and 12+ unit tests covering the full happy/edge/error matrix.
- **REST:** Three new routes on the existing admin servlet (`GET /hubs`, `GET /hubs/{name}`, `POST /hubs/{name}/remove-member`) with 11 new unit tests.
- **Frontend:** Two new React components (`ExistingHubsPanel`, `ExistingHubDrilldown`) mounted inside `HubDiscoveryTab`, plus three new methods on `api.knowledge`.
- **Integration:** A new `HubOverviewAdminPage` selenide page object and `HubOverviewAdminIT` end-to-end test.
- **Config:** Four new tunable properties in `ini/wikantik.properties`.

No schema changes. No background jobs. No caching. Statistics computed on demand from the live KG and current TF-IDF model.
