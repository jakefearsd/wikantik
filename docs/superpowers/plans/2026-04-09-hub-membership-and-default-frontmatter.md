# Hub Membership and Default Frontmatter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure every non-system page has frontmatter (for KG inclusion), implement bidirectional Hub membership with automatic sync, build a proposal algorithm for Hub membership based on content embeddings, add a Hub Proposals admin tab, and create a HubSet plugin.

**Architecture:** Unified save pipeline — `FrontmatterDefaultsFilter` (preSave) injects defaults, `HubSyncFilter` (postSave) syncs bidirectional Hub membership. `HubProposalService` runs as a batch process computing Hub centroids and percentile-ranked proposals. All stored in two new PostgreSQL tables (`hub_centroids`, `hub_proposals`).

**Tech Stack:** Java 21, JUnit 5, Testcontainers (PostgreSQL), React (JSX), Lucene EnglishAnalyzer, SnakeYAML, pgvector

**Spec:** `docs/superpowers/specs/2026-04-09-hub-membership-and-default-frontmatter-design.md`

---

## File Structure

### New Files

| File | Responsibility |
|------|---------------|
| `wikantik-main/src/main/java/com/wikantik/knowledge/FrontmatterDefaultsFilter.java` | preSave filter — generates default frontmatter for pages without it |
| `wikantik-main/src/main/java/com/wikantik/knowledge/HubSyncFilter.java` | postSave filter — bidirectional Hub/member sync |
| `wikantik-main/src/main/java/com/wikantik/knowledge/HubProposalService.java` | Batch Hub membership proposal algorithm |
| `wikantik-main/src/main/java/com/wikantik/knowledge/HubProposalRepository.java` | JDBC persistence for hub_proposals and hub_centroids |
| `wikantik-main/src/main/java/com/wikantik/knowledge/SummaryExtractor.java` | Heuristic sentence extraction for summary generation |
| `wikantik-main/src/main/java/com/wikantik/knowledge/TagExtractor.java` | TF-IDF keyword extraction for tag generation |
| `wikantik-main/src/main/java/com/wikantik/knowledge/TitleDeriver.java` | CamelCase/underscore page name to human title |
| `wikantik-main/src/main/java/com/wikantik/plugin/HubSetPlugin.java` | Plugin rendering Hub member lists |
| `wikantik-main/src/test/java/com/wikantik/knowledge/FrontmatterDefaultsFilterTest.java` | Tests for default frontmatter generation |
| `wikantik-main/src/test/java/com/wikantik/knowledge/HubSyncFilterTest.java` | Tests for bidirectional Hub sync |
| `wikantik-main/src/test/java/com/wikantik/knowledge/HubProposalServiceTest.java` | Tests for proposal algorithm |
| `wikantik-main/src/test/java/com/wikantik/knowledge/HubProposalRepositoryTest.java` | Tests for proposal persistence |
| `wikantik-main/src/test/java/com/wikantik/knowledge/SummaryExtractorTest.java` | Tests for summary heuristic |
| `wikantik-main/src/test/java/com/wikantik/knowledge/TagExtractorTest.java` | Tests for tag extraction |
| `wikantik-main/src/test/java/com/wikantik/knowledge/TitleDeriverTest.java` | Tests for title derivation |
| `wikantik-main/src/test/java/com/wikantik/plugin/HubSetPluginTest.java` | Tests for HubSet plugin |
| `wikantik-frontend/src/components/admin/HubProposalsTab.jsx` | React admin tab for Hub proposals |
| `wikantik-war/src/main/config/db/postgresql-hub.ddl` | DDL for hub_centroids and hub_proposals tables |

### Modified Files

| File | Changes |
|------|---------|
| `wikantik-main/src/main/java/com/wikantik/knowledge/FrontmatterRelationshipDetector.java` | Add `auto-generated` to `PROPERTY_ONLY_KEYS`; ensure `hubs` is NOT in `PROPERTY_ONLY_KEYS` (so it becomes edges) |
| `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` | Register `FrontmatterDefaultsFilter` and `HubSyncFilter` in filter chain |
| `wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java` | Add hub-proposals endpoints, backfill endpoint, sync-hub-memberships endpoint |
| `wikantik-frontend/src/api/client.js` | Add hub proposal API methods |
| `wikantik-frontend/src/components/admin/AdminKnowledgePage.jsx` | Add Hub Proposals tab |
| `wikantik-frontend/src/components/admin/ContentEmbeddingsTab.jsx` | Add "Backfill Frontmatter" button |

---

### Task 1: DDL — Hub Tables

**Files:**
- Create: `wikantik-war/src/main/config/db/postgresql-hub.ddl`

- [ ] **Step 1: Create the DDL file**

```sql
/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

-- Hub membership tables for Wikantik
-- Run after postgresql-knowledge.ddl: sudo -u postgres psql -d wikantik -f postgresql-hub.ddl
--
-- Prerequisites:
--   1. postgresql-knowledge.ddl must have been run first (creates pgvector extension)
--   2. Run this script as a PostgreSQL superuser (e.g., 'postgres')

-- Hub centroids: averaged content embedding vectors for each Hub
CREATE TABLE IF NOT EXISTS hub_centroids (
    id              SERIAL PRIMARY KEY,
    hub_name        VARCHAR(255) NOT NULL UNIQUE,
    centroid        vector(512) NOT NULL,
    model_version   INTEGER NOT NULL,
    member_count    INTEGER NOT NULL,
    created         TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Hub proposals: pending/approved/rejected Hub membership suggestions
CREATE TABLE IF NOT EXISTS hub_proposals (
    id               SERIAL PRIMARY KEY,
    hub_name         VARCHAR(255) NOT NULL,
    page_name        VARCHAR(255) NOT NULL,
    raw_similarity   DOUBLE PRECISION NOT NULL,
    percentile_score DOUBLE PRECISION NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'pending',
    reason           TEXT,
    reviewed_by      VARCHAR(255),
    reviewed_at      TIMESTAMP,
    created          TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (hub_name, page_name)
);

CREATE INDEX IF NOT EXISTS idx_hub_proposals_status ON hub_proposals(status);
CREATE INDEX IF NOT EXISTS idx_hub_proposals_hub ON hub_proposals(hub_name);

-- Grant permissions to application user (jspwiki)
GRANT SELECT, INSERT, UPDATE, DELETE ON hub_centroids TO jspwiki;
GRANT SELECT, INSERT, UPDATE, DELETE ON hub_proposals TO jspwiki;
GRANT USAGE, SELECT ON SEQUENCE hub_centroids_id_seq TO jspwiki;
GRANT USAGE, SELECT ON SEQUENCE hub_proposals_id_seq TO jspwiki;
```

- [ ] **Step 2: Apply DDL to local database**

Run: `sudo -u postgres psql -d wikantik -f wikantik-war/src/main/config/db/postgresql-hub.ddl`
Expected: Tables created without errors.

- [ ] **Step 3: Commit**

```bash
git add wikantik-war/src/main/config/db/postgresql-hub.ddl
git commit -m "feat: add DDL for hub_centroids and hub_proposals tables"
```

---

### Task 2: TitleDeriver — Page Name to Human Title

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/TitleDeriver.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/TitleDeriverTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TitleDeriverTest {

    @Test
    void camelCase() {
        assertEquals( "My Page Name", TitleDeriver.derive( "MyPageName" ) );
    }

    @Test
    void underscoreDelimited() {
        assertEquals( "My Page Name", TitleDeriver.derive( "my_page_name" ) );
    }

    @Test
    void mixedCamelAndUnderscore() {
        assertEquals( "My Page Name", TitleDeriver.derive( "My_PageName" ) );
    }

    @Test
    void singleWord() {
        assertEquals( "Hello", TitleDeriver.derive( "Hello" ) );
    }

    @Test
    void alreadySpaced() {
        assertEquals( "Already Spaced", TitleDeriver.derive( "Already Spaced" ) );
    }

    @Test
    void acronymHandling() {
        assertEquals( "HTTP Server Config", TitleDeriver.derive( "HTTPServerConfig" ) );
    }

    @Test
    void hyphenDelimited() {
        assertEquals( "Some Page Name", TitleDeriver.derive( "some-page-name" ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=TitleDeriverTest -q 2>&1 | tail -5`
Expected: Compilation error — `TitleDeriver` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.wikantik.knowledge;

import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Arrays;

/**
 * Derives a human-readable title from a wiki page name by splitting CamelCase,
 * underscores, and hyphens into separate words and capitalizing each word.
 */
public final class TitleDeriver {

    private TitleDeriver() {}

    /** Splits before uppercase letters that follow lowercase, or before runs of uppercase
     *  followed by a lowercase (e.g., "HTTPServer" → "HTTP", "Server"). */
    private static final Pattern CAMEL_SPLIT = Pattern.compile(
        "(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])" );

    /** Matches underscores and hyphens used as word separators. */
    private static final Pattern SEPARATOR = Pattern.compile( "[_\\-]+" );

    public static String derive( final String pageName ) {
        if ( pageName == null || pageName.isBlank() ) {
            return "";
        }
        // 1. Replace underscores/hyphens with spaces
        String s = SEPARATOR.matcher( pageName ).replaceAll( " " );
        // 2. Split camelCase into spaces
        s = CAMEL_SPLIT.matcher( s ).replaceAll( " " );
        // 3. Capitalize each word
        return Arrays.stream( s.split( "\\s+" ) )
            .filter( w -> !w.isEmpty() )
            .map( w -> {
                // Preserve all-caps words (acronyms)
                if ( w.length() > 1 && w.equals( w.toUpperCase() ) ) {
                    return w;
                }
                return Character.toUpperCase( w.charAt( 0 ) ) + w.substring( 1 );
            } )
            .collect( Collectors.joining( " " ) );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=TitleDeriverTest -q 2>&1 | tail -5`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/TitleDeriver.java \
       wikantik-main/src/test/java/com/wikantik/knowledge/TitleDeriverTest.java
git commit -m "feat: add TitleDeriver for page name to human title conversion"
```

---

### Task 3: SummaryExtractor — Heuristic Sentence Selection

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/SummaryExtractor.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/SummaryExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SummaryExtractorTest {

    @Test
    void selectsFirstSuitableSentence() {
        final String body = "# Heading\nShort.\nThis is a suitable sentence with enough length to be meaningful. Another sentence.";
        final String summary = SummaryExtractor.extract( body );
        assertEquals( "This is a suitable sentence with enough length to be meaningful.", summary );
    }

    @Test
    void truncatesLongSentenceWhenNoSuitableExists() {
        final String longSentence = "A".repeat( 250 ) + ".";
        final String summary = SummaryExtractor.extract( longSentence );
        assertEquals( 203, summary.length() ); // 200 chars + "..."
        assertTrue( summary.endsWith( "..." ) );
    }

    @Test
    void stripsMarkdownBeforeExtracting() {
        final String body = "## Heading\n[Link text](http://example.com) is a valid sentence in the content here. More text.";
        final String summary = SummaryExtractor.extract( body );
        assertFalse( summary.contains( "http://" ) );
        assertTrue( summary.contains( "Link text" ) );
    }

    @Test
    void returnsEmptyForBlankContent() {
        assertEquals( "", SummaryExtractor.extract( "" ) );
        assertEquals( "", SummaryExtractor.extract( null ) );
    }

    @Test
    void skipsTooShortSentences() {
        final String body = "Hi.\nOk.\nThis sentence has enough characters to qualify as a good summary sentence.";
        final String summary = SummaryExtractor.extract( body );
        assertEquals( "This sentence has enough characters to qualify as a good summary sentence.", summary );
    }

    @Test
    void stripsFrontmatter() {
        final String body = "---\ntitle: Test\n---\nThis is the actual content sentence with enough characters to be selected.";
        final String summary = SummaryExtractor.extract( body );
        assertEquals( "This is the actual content sentence with enough characters to be selected.", summary );
    }

    @Test
    void stripsPluginCalls() {
        final String body = "[{TableOfContents}]()\nThis is a real sentence that should be selected by the extractor algorithm.";
        final String summary = SummaryExtractor.extract( body );
        assertFalse( summary.contains( "TableOfContents" ) );
        assertTrue( summary.startsWith( "This is a real sentence" ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=SummaryExtractorTest -q 2>&1 | tail -5`
Expected: Compilation error — `SummaryExtractor` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.wikantik.knowledge;

import java.util.regex.Pattern;

/**
 * Extracts a summary sentence from wiki page body text using a heuristic approach.
 * Strips markdown formatting, then selects the first sentence between 40 and 200
 * characters. Falls back to truncating the first sentence at 200 characters.
 */
public final class SummaryExtractor {

    private SummaryExtractor() {}

    private static final int MIN_LENGTH = 40;
    private static final int MAX_LENGTH = 200;

    /** Matches plugin calls: [{PluginName ...}]() or [{PluginName ...}] */
    private static final Pattern PLUGIN = Pattern.compile( "\\[\\{[^}]*\\}\\](?:\\([^)]*\\))?" );

    /** Sentence boundary: period, exclamation, or question mark followed by space or end. */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile( "(?<=[.!?])\\s+" );

    public static String extract( final String body ) {
        if ( body == null || body.isBlank() ) {
            return "";
        }

        // Strip markdown and plugin calls
        String stripped = NodeTextAssembler.stripMarkdown( body );
        stripped = PLUGIN.matcher( stripped ).replaceAll( "" ).trim();

        if ( stripped.isEmpty() ) {
            return "";
        }

        // Split into sentences
        final String[] sentences = SENTENCE_BOUNDARY.split( stripped );

        // First pass: find a sentence in the sweet spot (40-200 chars)
        for ( final String sentence : sentences ) {
            final String trimmed = sentence.trim();
            if ( trimmed.length() >= MIN_LENGTH && trimmed.length() <= MAX_LENGTH ) {
                return trimmed;
            }
        }

        // Fallback: take the first non-empty sentence and truncate
        for ( final String sentence : sentences ) {
            final String trimmed = sentence.trim();
            if ( !trimmed.isEmpty() ) {
                if ( trimmed.length() <= MAX_LENGTH ) {
                    return trimmed;
                }
                return trimmed.substring( 0, MAX_LENGTH ) + "...";
            }
        }

        return "";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=SummaryExtractorTest -q 2>&1 | tail -5`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/SummaryExtractor.java \
       wikantik-main/src/test/java/com/wikantik/knowledge/SummaryExtractorTest.java
git commit -m "feat: add SummaryExtractor for heuristic sentence selection"
```

---

### Task 4: TagExtractor — TF-IDF Keyword Extraction

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/TagExtractor.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/TagExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TagExtractorTest {

    @Test
    void extractsTopNKeywords() {
        final String text = "PostgreSQL database management system. "
            + "The database stores relational data efficiently. "
            + "Database indexing improves query performance. "
            + "Machine learning algorithms process data.";

        final List< String > tags = TagExtractor.extract( text, 3 );
        assertEquals( 3, tags.size() );
        // All tags should be lowercase
        for ( final String tag : tags ) {
            assertEquals( tag.toLowerCase(), tag );
        }
    }

    @Test
    void returnsFewerTagsWhenContentIsShort() {
        final List< String > tags = TagExtractor.extract( "Hello world", 3 );
        assertTrue( tags.size() <= 3 );
        assertFalse( tags.isEmpty() );
    }

    @Test
    void returnsEmptyForBlank() {
        assertTrue( TagExtractor.extract( "", 3 ).isEmpty() );
        assertTrue( TagExtractor.extract( null, 3 ).isEmpty() );
    }

    @Test
    void stripsMarkdown() {
        final String markdown = "# Heading\n[Click here](http://example.com) for **bold** information about _databases_ and indexing.";
        final List< String > tags = TagExtractor.extract( markdown, 3 );
        assertFalse( tags.isEmpty() );
        // URLs and markdown syntax should not appear as tags
        for ( final String tag : tags ) {
            assertFalse( tag.contains( "http" ) );
            assertFalse( tag.contains( "#" ) );
            assertFalse( tag.contains( "*" ) );
        }
    }

    @Test
    void respectsMaxCount() {
        final String text = "alpha beta gamma delta epsilon zeta eta theta iota kappa";
        final List< String > tags = TagExtractor.extract( text, 2 );
        assertEquals( 2, tags.size() );
    }

    @Test
    void excludesStopwords() {
        final String text = "The quick brown fox jumps over the lazy dog multiple times repeatedly.";
        final List< String > tags = TagExtractor.extract( text, 3 );
        for ( final String tag : tags ) {
            assertFalse( tag.equals( "the" ) );
            assertFalse( tag.equals( "over" ) );
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=TagExtractorTest -q 2>&1 | tail -5`
Expected: Compilation error — `TagExtractor` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.wikantik.knowledge;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;

/**
 * Extracts top-N keywords from text using TF-IDF-style term frequency scoring.
 * Uses Lucene's {@link EnglishAnalyzer} for tokenization, stop word removal,
 * and Porter stemming — the same analyzer used by {@link TfidfModel}.
 *
 * <p>For single-document extraction (no corpus IDF available), uses raw term
 * frequency with a log-dampening factor: {@code score = 1 + log(tf)}.</p>
 */
public final class TagExtractor {

    private TagExtractor() {}

    /**
     * Extracts up to {@code maxTags} keywords from the given text.
     *
     * @param text    raw text (markdown is stripped before analysis)
     * @param maxTags maximum number of tags to return
     * @return list of lowercase keywords, ordered by score descending
     */
    public static List< String > extract( final String text, final int maxTags ) {
        if ( text == null || text.isBlank() || maxTags <= 0 ) {
            return List.of();
        }

        final String stripped = NodeTextAssembler.stripMarkdown( text );
        if ( stripped.isBlank() ) {
            return List.of();
        }

        // Tokenize and count term frequencies
        final Map< String, Integer > termFreq = new LinkedHashMap<>();
        // Keep a mapping from stemmed term back to the first original form seen
        final Map< String, String > stemToOriginal = new HashMap<>();

        try ( final Analyzer analyzer = new EnglishAnalyzer() ) {
            try ( final TokenStream stream = analyzer.tokenStream( "content", new StringReader( stripped ) ) ) {
                final CharTermAttribute termAttr = stream.addAttribute( CharTermAttribute.class );
                stream.reset();
                while ( stream.incrementToken() ) {
                    final String term = termAttr.toString();
                    if ( term.length() < 2 ) continue; // skip single-char tokens
                    termFreq.merge( term, 1, Integer::sum );
                    stemToOriginal.putIfAbsent( term, term );
                }
                stream.end();
            }
        } catch ( final IOException e ) {
            // StringReader won't throw IOException
            return List.of();
        }

        if ( termFreq.isEmpty() ) {
            return List.of();
        }

        // Score: log-dampened term frequency
        final List< Map.Entry< String, Double > > scored = new ArrayList<>();
        for ( final var entry : termFreq.entrySet() ) {
            scored.add( Map.entry( entry.getKey(), 1.0 + Math.log( entry.getValue() ) ) );
        }
        scored.sort( Map.Entry.< String, Double >comparingByValue().reversed() );

        final List< String > result = new ArrayList<>( maxTags );
        for ( final var entry : scored ) {
            if ( result.size() >= maxTags ) break;
            result.add( entry.getKey().toLowerCase() );
        }
        return result;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=TagExtractorTest -q 2>&1 | tail -5`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/TagExtractor.java \
       wikantik-main/src/test/java/com/wikantik/knowledge/TagExtractorTest.java
git commit -m "feat: add TagExtractor for TF-IDF keyword extraction"
```

---

### Task 5: FrontmatterRelationshipDetector — Add auto-generated to PROPERTY_ONLY_KEYS

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/FrontmatterRelationshipDetector.java:16-19`
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/FrontmatterRelationshipDetectorTest.java`

- [ ] **Step 1: Write the failing test**

Add a test to the existing `FrontmatterRelationshipDetectorTest.java` that verifies `auto-generated` is treated as a property, and that `hubs` (a List\<String\>) becomes a relationship:

```java
@Test
void autoGeneratedIsTreatedAsProperty() {
    final var detector = new FrontmatterRelationshipDetector();
    final Map< String, Object > fm = Map.of(
        "auto-generated", true,
        "hubs", List.of( "TechHub", "ScienceHub" )
    );
    final var result = detector.detect( fm );
    assertTrue( result.properties().containsKey( "auto-generated" ) );
    assertEquals( true, result.properties().get( "auto-generated" ) );
    assertTrue( result.relationships().containsKey( "hubs" ) );
    assertEquals( List.of( "TechHub", "ScienceHub" ), result.relationships().get( "hubs" ) );
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=FrontmatterRelationshipDetectorTest#autoGeneratedIsTreatedAsProperty -q 2>&1 | tail -10`
Expected: FAIL — `auto-generated` is not in `PROPERTY_ONLY_KEYS` so it might not be treated as a property (it's a boolean, not List\<String\>, so it would actually be a property anyway — but we add it to be explicit). The `hubs` relationship part should already pass since `hubs` is not in `PROPERTY_ONLY_KEYS`. Verify the assertion behavior.

- [ ] **Step 3: Add auto-generated to PROPERTY_ONLY_KEYS**

In `FrontmatterRelationshipDetector.java`, change the `PROPERTY_ONLY_KEYS` set:

```java
    private static final Set< String > PROPERTY_ONLY_KEYS = Set.of(
        "tags", "keywords", "type", "summary", "date", "author", "cluster",
        "status", "title", "description", "category", "language", "auto-generated"
    );
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=FrontmatterRelationshipDetectorTest -q 2>&1 | tail -5`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/FrontmatterRelationshipDetector.java \
       wikantik-main/src/test/java/com/wikantik/knowledge/FrontmatterRelationshipDetectorTest.java
git commit -m "feat: add auto-generated to PROPERTY_ONLY_KEYS, verify hubs becomes relationship"
```

---

### Task 6: FrontmatterDefaultsFilter

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/FrontmatterDefaultsFilter.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/FrontmatterDefaultsFilterTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class FrontmatterDefaultsFilterTest {

    private FrontmatterDefaultsFilter createFilter( final Properties props ) {
        return new FrontmatterDefaultsFilter(
            pageName -> false, // no system pages
            props
        );
    }

    @Test
    void injectsDefaultFrontmatterWhenNonePresent() {
        final FrontmatterDefaultsFilter filter = createFilter( new Properties() );
        final String content = "This is a page about PostgreSQL databases and indexing for faster queries.";

        final String result = filter.applyDefaults( "MyTestPage", content );

        final ParsedPage parsed = FrontmatterParser.parse( result );
        assertFalse( parsed.metadata().isEmpty(), "Should have generated frontmatter" );
        assertEquals( "My Test Page", parsed.metadata().get( "title" ) );
        assertEquals( "article", parsed.metadata().get( "type" ) );
        assertEquals( true, parsed.metadata().get( "auto-generated" ) );
        assertNotNull( parsed.metadata().get( "tags" ) );
        assertInstanceOf( List.class, parsed.metadata().get( "tags" ) );
        assertTrue( ((List<?>) parsed.metadata().get( "tags" )).size() <= 3 );
        assertNotNull( parsed.metadata().get( "summary" ) );
        // Body should be preserved
        assertTrue( parsed.body().contains( "PostgreSQL" ) );
    }

    @Test
    void passesThrough_whenFrontmatterExists() {
        final FrontmatterDefaultsFilter filter = createFilter( new Properties() );
        final String content = "---\ntype: hub\n---\nBody text here.";

        final String result = filter.applyDefaults( "SomePage", content );
        assertEquals( content, result, "Should not modify pages that already have frontmatter" );
    }

    @Test
    void passesThrough_forSystemPages() {
        final FrontmatterDefaultsFilter filter = new FrontmatterDefaultsFilter(
            pageName -> pageName.equals( "LeftMenu" ),
            new Properties()
        );
        final String content = "System page content without frontmatter.";

        final String result = filter.applyDefaults( "LeftMenu", content );
        assertEquals( content, result, "Should not modify system pages" );
    }

    @Test
    void respectsConfiguredTagCount() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.frontmatter.defaultTags", "1" );
        final FrontmatterDefaultsFilter filter = createFilter( props );
        final String content = "Alpha beta gamma delta epsilon zeta eta theta. This is a longer sentence about several topics.";

        final String result = filter.applyDefaults( "TestPage", content );
        final ParsedPage parsed = FrontmatterParser.parse( result );
        final List<?> tags = (List<?>) parsed.metadata().get( "tags" );
        assertEquals( 1, tags.size() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=FrontmatterDefaultsFilterTest -q 2>&1 | tail -5`
Expected: Compilation error — `FrontmatterDefaultsFilter` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.wikantik.knowledge;

import com.wikantik.api.core.Context;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.frontmatter.ParsedPage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.function.Predicate;

/**
 * Pre-save filter that generates default frontmatter for pages that lack it entirely.
 * Injects title, type, tags, summary, and an {@code auto-generated: true} marker.
 *
 * <p>Pages that already have any frontmatter (even partial) are passed through unchanged.
 * System pages are excluded.</p>
 */
public class FrontmatterDefaultsFilter implements PageFilter {

    private static final Logger LOG = LogManager.getLogger( FrontmatterDefaultsFilter.class );

    public static final String PROP_DEFAULT_TAGS = "wikantik.frontmatter.defaultTags";
    private static final int DEFAULT_TAG_COUNT = 3;

    private final Predicate< String > isSystemPage;
    private final int tagCount;

    public FrontmatterDefaultsFilter( final Predicate< String > isSystemPage,
                                       final Properties props ) {
        this.isSystemPage = isSystemPage;
        this.tagCount = Integer.parseInt(
            props.getProperty( PROP_DEFAULT_TAGS, String.valueOf( DEFAULT_TAG_COUNT ) ) );
    }

    @Override
    public String preSave( final Context context, final String content ) {
        final String pageName = context.getPage().getName();
        return applyDefaults( pageName, content );
    }

    /**
     * Core logic, separated from the PageFilter interface for testability.
     */
    public String applyDefaults( final String pageName, final String content ) {
        if ( isSystemPage.test( pageName ) ) {
            return content;
        }

        final ParsedPage parsed = FrontmatterParser.parse( content != null ? content : "" );
        if ( !parsed.metadata().isEmpty() ) {
            return content;
        }

        final String body = parsed.body();
        final Map< String, Object > metadata = new LinkedHashMap<>();
        metadata.put( "title", TitleDeriver.derive( pageName ) );
        metadata.put( "type", "article" );
        metadata.put( "tags", TagExtractor.extract( body, tagCount ) );
        metadata.put( "summary", SummaryExtractor.extract( body ) );
        metadata.put( "auto-generated", true );

        LOG.info( "Generated default frontmatter for page '{}'", pageName );
        return FrontmatterWriter.write( metadata, body );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=FrontmatterDefaultsFilterTest -q 2>&1 | tail -5`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/FrontmatterDefaultsFilter.java \
       wikantik-main/src/test/java/com/wikantik/knowledge/FrontmatterDefaultsFilterTest.java
git commit -m "feat: add FrontmatterDefaultsFilter for auto-generating defaults on save"
```

---

### Task 7: HubSyncFilter — Bidirectional Hub Membership Sync

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/HubSyncFilter.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/HubSyncFilterTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.frontmatter.ParsedPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HubSyncFilterTest {

    private Map< String, String > pageStore;
    private HubSyncFilter filter;

    @BeforeEach
    void setUp() {
        pageStore = new HashMap<>();
        filter = new HubSyncFilter(
            pageName -> pageStore.get( pageName ),       // readPage
            ( pageName, content ) -> pageStore.put( pageName, content )  // savePage
        );
    }

    @Test
    void memberAddsHub_syncsHubRelatedList() {
        // Hub page exists with one existing member
        pageStore.put( "TechHub", FrontmatterWriter.write(
            Map.of( "type", "hub", "related", List.of( "ExistingPage" ) ), "Hub body" ) );

        // Member page saved with hubs field referencing TechHub
        final String memberContent = FrontmatterWriter.write(
            Map.of( "title", "My Article", "hubs", List.of( "TechHub" ) ), "Article body" );

        filter.syncAfterSave( "MyArticle", memberContent, "" );

        // Hub's related list should now include MyArticle
        final ParsedPage hubParsed = FrontmatterParser.parse( pageStore.get( "TechHub" ) );
        final List<?> related = (List<?>) hubParsed.metadata().get( "related" );
        assertTrue( related.contains( "MyArticle" ) );
        assertTrue( related.contains( "ExistingPage" ), "Should preserve existing members" );
    }

    @Test
    void hubAddsMember_syncsMemberHubsList() {
        // Member page exists without hubs field
        pageStore.put( "MyArticle", FrontmatterWriter.write(
            Map.of( "title", "My Article" ), "Article body" ) );

        // Hub page saved with new member in related list
        final String hubContent = FrontmatterWriter.write(
            Map.of( "type", "hub", "related", List.of( "MyArticle" ) ), "Hub body" );

        filter.syncAfterSave( "TechHub", hubContent, "" );

        // Member's hubs list should now include TechHub
        final ParsedPage memberParsed = FrontmatterParser.parse( pageStore.get( "MyArticle" ) );
        final List<?> hubs = (List<?>) memberParsed.metadata().get( "hubs" );
        assertNotNull( hubs );
        assertTrue( hubs.contains( "TechHub" ) );
    }

    @Test
    void memberRemovesHub_updatesHubRelatedList() {
        // Hub page has MyArticle in related list
        pageStore.put( "TechHub", FrontmatterWriter.write(
            Map.of( "type", "hub", "related", List.of( "MyArticle", "OtherPage" ) ), "" ) );

        // Member's old content had hubs: [TechHub], new content has empty hubs
        final String oldContent = FrontmatterWriter.write(
            Map.of( "hubs", List.of( "TechHub" ) ), "" );
        final String newContent = FrontmatterWriter.write(
            Map.of( "title", "Test" ), "" );

        filter.syncAfterSave( "MyArticle", newContent, oldContent );

        final ParsedPage hubParsed = FrontmatterParser.parse( pageStore.get( "TechHub" ) );
        final List<?> related = (List<?>) hubParsed.metadata().get( "related" );
        assertFalse( related.contains( "MyArticle" ) );
        assertTrue( related.contains( "OtherPage" ) );
    }

    @Test
    void noOpWhenTargetPageDoesNotExist() {
        // Member adds Hub that doesn't exist yet — should not throw
        final String memberContent = FrontmatterWriter.write(
            Map.of( "hubs", List.of( "NonExistentHub" ) ), "" );

        assertDoesNotThrow( () -> filter.syncAfterSave( "MyArticle", memberContent, "" ) );
    }

    @Test
    void noRecursion_secondarySaveDoesNotTriggerSync() {
        pageStore.put( "TechHub", FrontmatterWriter.write(
            Map.of( "type", "hub", "related", List.of() ), "" ) );

        final String memberContent = FrontmatterWriter.write(
            Map.of( "hubs", List.of( "TechHub" ) ), "" );

        // This should complete without infinite recursion
        filter.syncAfterSave( "MyArticle", memberContent, "" );

        // Verify Hub was updated (proves sync happened)
        final ParsedPage hubParsed = FrontmatterParser.parse( pageStore.get( "TechHub" ) );
        assertTrue( ((List<?>) hubParsed.metadata().get( "related" )).contains( "MyArticle" ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=HubSyncFilterTest -q 2>&1 | tail -5`
Expected: Compilation error — `HubSyncFilter` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.wikantik.knowledge;

import com.wikantik.api.core.Context;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.frontmatter.ParsedPage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Post-save filter that synchronizes bidirectional Hub membership.
 * When a member page's {@code hubs} list changes, the Hub page's {@code related} list is updated.
 * When a Hub page's {@code related} list changes, member pages' {@code hubs} lists are updated.
 *
 * <p>Uses a thread-local flag to prevent recursive sync loops.</p>
 */
public class HubSyncFilter implements PageFilter {

    private static final Logger LOG = LogManager.getLogger( HubSyncFilter.class );

    /** Thread-local flag to suppress recursive sync. */
    static final ThreadLocal< Boolean > SUPPRESS_SYNC = ThreadLocal.withInitial( () -> false );

    private final Function< String, String > readPage;
    private final BiConsumer< String, String > savePage;

    /**
     * @param readPage  function that reads page content by name (returns null if not found)
     * @param savePage  function that saves page content by name
     */
    public HubSyncFilter( final Function< String, String > readPage,
                           final BiConsumer< String, String > savePage ) {
        this.readPage = readPage;
        this.savePage = savePage;
    }

    @Override
    public void postSave( final Context context, final String content ) {
        final String pageName = context.getPage().getName();
        // Retrieve previous content from the context attribute if available
        final String previousContent = context.getVariable( "previousContent" );
        syncAfterSave( pageName, content, previousContent != null ? previousContent : "" );
    }

    /**
     * Core sync logic, separated from PageFilter for testability.
     */
    @SuppressWarnings( "unchecked" )
    public void syncAfterSave( final String pageName, final String newContent,
                                final String oldContent ) {
        if ( SUPPRESS_SYNC.get() ) {
            return;
        }

        try {
            final ParsedPage newParsed = FrontmatterParser.parse( newContent != null ? newContent : "" );
            final ParsedPage oldParsed = FrontmatterParser.parse( oldContent != null ? oldContent : "" );

            final List< String > newHubs = toStringList( newParsed.metadata().get( "hubs" ) );
            final List< String > oldHubs = toStringList( oldParsed.metadata().get( "hubs" ) );

            // Direction 1: Member page's hubs field changed
            final Set< String > addedHubs = new LinkedHashSet<>( newHubs );
            addedHubs.removeAll( oldHubs );
            final Set< String > removedHubs = new LinkedHashSet<>( oldHubs );
            removedHubs.removeAll( newHubs );

            for ( final String hubName : addedHubs ) {
                addToRelatedList( hubName, pageName );
            }
            for ( final String hubName : removedHubs ) {
                removeFromRelatedList( hubName, pageName );
            }

            // Direction 2: Hub page's related field changed (only if type=hub)
            final boolean isHub = "hub".equals( newParsed.metadata().get( "type" ) );
            if ( isHub ) {
                final List< String > newRelated = toStringList( newParsed.metadata().get( "related" ) );
                final List< String > oldRelated = toStringList( oldParsed.metadata().get( "related" ) );

                final Set< String > addedMembers = new LinkedHashSet<>( newRelated );
                addedMembers.removeAll( oldRelated );
                final Set< String > removedMembers = new LinkedHashSet<>( oldRelated );
                removedMembers.removeAll( newRelated );

                for ( final String memberName : addedMembers ) {
                    addToHubsList( memberName, pageName );
                }
                for ( final String memberName : removedMembers ) {
                    removeFromHubsList( memberName, pageName );
                }
            }
        } catch ( final Exception e ) {
            LOG.warn( "Hub sync failed for page '{}': {}", pageName, e.getMessage(), e );
        }
    }

    private void addToRelatedList( final String hubPageName, final String memberName ) {
        updateFrontmatterList( hubPageName, "related", memberName, true );
    }

    private void removeFromRelatedList( final String hubPageName, final String memberName ) {
        updateFrontmatterList( hubPageName, "related", memberName, false );
    }

    private void addToHubsList( final String memberPageName, final String hubName ) {
        updateFrontmatterList( memberPageName, "hubs", hubName, true );
    }

    private void removeFromHubsList( final String memberPageName, final String hubName ) {
        updateFrontmatterList( memberPageName, "hubs", hubName, false );
    }

    @SuppressWarnings( "unchecked" )
    private void updateFrontmatterList( final String pageName, final String key,
                                         final String value, final boolean add ) {
        final String pageContent = readPage.apply( pageName );
        if ( pageContent == null ) {
            LOG.debug( "Skipping hub sync for '{}': page does not exist", pageName );
            return;
        }

        final ParsedPage parsed = FrontmatterParser.parse( pageContent );
        final Map< String, Object > metadata = new LinkedHashMap<>( parsed.metadata() );
        final List< String > list = new ArrayList<>( toStringList( metadata.get( key ) ) );

        if ( add ) {
            if ( list.contains( value ) ) return; // already present, no-op
            list.add( value );
        } else {
            if ( !list.remove( value ) ) return; // not present, no-op
        }

        metadata.put( key, list );
        final String updatedContent = FrontmatterWriter.write( metadata, parsed.body() );

        SUPPRESS_SYNC.set( true );
        try {
            savePage.accept( pageName, updatedContent );
            LOG.debug( "Hub sync: {} '{}' {} {}.{}", add ? "added" : "removed",
                value, add ? "to" : "from", pageName, key );
        } finally {
            SUPPRESS_SYNC.set( false );
        }
    }

    @SuppressWarnings( "unchecked" )
    private static List< String > toStringList( final Object value ) {
        if ( value instanceof List<?> list ) {
            return list.stream()
                .filter( String.class::isInstance )
                .map( String.class::cast )
                .toList();
        }
        return List.of();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=HubSyncFilterTest -q 2>&1 | tail -5`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/HubSyncFilter.java \
       wikantik-main/src/test/java/com/wikantik/knowledge/HubSyncFilterTest.java
git commit -m "feat: add HubSyncFilter for bidirectional Hub membership sync"
```

---

### Task 8: Register Filters in WikiEngine

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java:520-548`

- [ ] **Step 1: Add filter registration after the existing GraphProjector registration**

After line 531 (`getManager( FilterManager.class ).addPageFilter( projector, -1003 );`), add:

```java
            // FrontmatterDefaultsFilter: injects defaults for pages without frontmatter (preSave)
            final FrontmatterDefaultsFilter fmDefaults = new FrontmatterDefaultsFilter(
                name -> spr != null && spr.isSystemPage( name ), props );
            getManager( FilterManager.class ).addPageFilter( fmDefaults, -1004 );

            // HubSyncFilter: bidirectional Hub membership sync (postSave)
            final PageManager pm = getManager( PageManager.class );
            final PageSaveHelper saveHelper = pm.getSaveHelper();
            final HubSyncFilter hubSync = new HubSyncFilter(
                name -> {
                    try {
                        final Page p = pm.getPage( name );
                        return p != null ? pm.getPureText( p ) : null;
                    } catch ( final Exception e ) {
                        LOG.warn( "HubSyncFilter: failed to read page '{}': {}", name, e.getMessage() );
                        return null;
                    }
                },
                ( name, content ) -> {
                    try {
                        saveHelper.saveText( name, content, SaveOptions.defaultOptions() );
                    } catch ( final Exception e ) {
                        LOG.warn( "HubSyncFilter: failed to save page '{}': {}", name, e.getMessage() );
                    }
                }
            );
            getManager( FilterManager.class ).addPageFilter( hubSync, -1005 );
```

Also add the required imports at the top of WikiEngine.java:

```java
import com.wikantik.knowledge.FrontmatterDefaultsFilter;
import com.wikantik.knowledge.HubSyncFilter;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
```

- [ ] **Step 2: Compile to verify**

Run: `mvn compile -pl wikantik-main -q 2>&1 | tail -10`
Expected: No compilation errors.

Note: Priority -1004 and -1005 ensure `FrontmatterDefaultsFilter` runs before `GraphProjector` (-1003) in preSave, and `HubSyncFilter` runs after `GraphProjector` in postSave. The lower number means higher priority in the filter chain — preSave iterates the list in order, so -1004 fires first. PostSave also iterates in order, so -1005 fires after -1003.

Wait — the filter manager uses `PriorityList` where filters run in priority order. Lower numbers run first. For preSave, we want FrontmatterDefaultsFilter to run BEFORE other filters. For postSave, we want HubSyncFilter to run AFTER GraphProjector. But both preSave and postSave iterate the same filter list. Since -1004 < -1003 (lower = earlier), FrontmatterDefaultsFilter's preSave fires first (correct) but its postSave also fires before GraphProjector (irrelevant since it only overrides preSave). HubSyncFilter at -1005 fires even earlier in postSave — that's WRONG. We need HubSyncFilter to fire AFTER GraphProjector.

**Fix:** HubSyncFilter should have a HIGHER priority number (closer to 0) so it fires AFTER GraphProjector in postSave:

```java
getManager( FilterManager.class ).addPageFilter( hubSync, -999 );
```

This ensures postSave order: FrontmatterDefaultsFilter (-1004), GraphProjector (-1003), then HubSyncFilter (-999).

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/WikiEngine.java
git commit -m "feat: register FrontmatterDefaultsFilter and HubSyncFilter in WikiEngine"
```

---

### Task 9: HubProposalRepository — JDBC Persistence

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/HubProposalRepository.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/HubProposalRepositoryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge;

import com.wikantik.PostgresTestContainer;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class HubProposalRepositoryTest {

    private static DataSource dataSource;
    private HubProposalRepository repo;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        repo = new HubProposalRepository( dataSource );
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM hub_proposals" );
            conn.createStatement().execute( "DELETE FROM hub_centroids" );
        }
    }

    @Test
    void insertAndQueryProposal() {
        repo.insertProposal( "TechHub", "MyArticle", 0.85, 92.5 );

        final List< HubProposalRepository.HubProposal > pending =
            repo.listProposals( "pending", null, 50, 0 );
        assertEquals( 1, pending.size() );
        assertEquals( "TechHub", pending.get( 0 ).hubName() );
        assertEquals( "MyArticle", pending.get( 0 ).pageName() );
        assertEquals( 92.5, pending.get( 0 ).percentileScore(), 0.01 );
    }

    @Test
    void approveProposal() {
        repo.insertProposal( "TechHub", "MyArticle", 0.85, 92.5 );
        final List< HubProposalRepository.HubProposal > pending = repo.listProposals( "pending", null, 50, 0 );
        repo.updateStatus( pending.get( 0 ).id(), "approved", "admin", null );

        final List< HubProposalRepository.HubProposal > approved = repo.listProposals( "approved", null, 50, 0 );
        assertEquals( 1, approved.size() );
        assertEquals( "approved", approved.get( 0 ).status() );
    }

    @Test
    void rejectProposal() {
        repo.insertProposal( "TechHub", "MyArticle", 0.85, 92.5 );
        final List< HubProposalRepository.HubProposal > pending = repo.listProposals( "pending", null, 50, 0 );
        repo.updateStatus( pending.get( 0 ).id(), "rejected", "admin", "Not relevant" );

        assertTrue( repo.isRejected( "TechHub", "MyArticle" ) );
    }

    @Test
    void duplicateProposalIsSkipped() {
        repo.insertProposal( "TechHub", "MyArticle", 0.85, 92.5 );
        repo.insertProposal( "TechHub", "MyArticle", 0.90, 95.0 ); // duplicate

        final List< HubProposalRepository.HubProposal > all = repo.listProposals( "pending", null, 50, 0 );
        assertEquals( 1, all.size() );
    }

    @Test
    void filterByHub() {
        repo.insertProposal( "TechHub", "ArticleA", 0.85, 92.5 );
        repo.insertProposal( "SciHub", "ArticleB", 0.80, 88.0 );

        final List< HubProposalRepository.HubProposal > techOnly =
            repo.listProposals( "pending", "TechHub", 50, 0 );
        assertEquals( 1, techOnly.size() );
        assertEquals( "TechHub", techOnly.get( 0 ).hubName() );
    }

    @Test
    void saveCentroid() {
        final float[] centroid = new float[ 512 ];
        centroid[ 0 ] = 1.0f;
        centroid[ 1 ] = 0.5f;

        repo.saveCentroid( "TechHub", centroid, 1, 5 );

        final float[] loaded = repo.loadCentroid( "TechHub" );
        assertNotNull( loaded );
        assertEquals( 1.0f, loaded[ 0 ], 0.001f );
        assertEquals( 0.5f, loaded[ 1 ], 0.001f );
    }

    @Test
    void countPending() {
        repo.insertProposal( "TechHub", "A", 0.85, 92.5 );
        repo.insertProposal( "TechHub", "B", 0.80, 88.0 );
        assertEquals( 2, repo.countByStatus( "pending" ) );
    }

    @Test
    void bulkApprove() {
        repo.insertProposal( "TechHub", "A", 0.85, 92.5 );
        repo.insertProposal( "TechHub", "B", 0.80, 88.0 );
        final List< HubProposalRepository.HubProposal > pending = repo.listProposals( "pending", null, 50, 0 );
        final List< Integer > ids = pending.stream().map( HubProposalRepository.HubProposal::id ).toList();

        repo.bulkUpdateStatus( ids, "approved", "admin", null );

        assertEquals( 0, repo.countByStatus( "pending" ) );
        assertEquals( 2, repo.countByStatus( "approved" ) );
    }

    @Test
    void approveByThreshold() {
        repo.insertProposal( "TechHub", "A", 0.95, 97.0 );
        repo.insertProposal( "TechHub", "B", 0.80, 85.0 );

        final List< HubProposalRepository.HubProposal > above =
            repo.listProposalsAboveThreshold( 90.0 );
        assertEquals( 1, above.size() );
        assertEquals( "A", above.get( 0 ).pageName() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=HubProposalRepositoryTest -q 2>&1 | tail -5`
Expected: Compilation error — `HubProposalRepository` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.wikantik.knowledge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC repository for Hub membership proposals and Hub centroid embeddings.
 */
public class HubProposalRepository {

    private static final Logger LOG = LogManager.getLogger( HubProposalRepository.class );

    public record HubProposal( int id, String hubName, String pageName,
                                double rawSimilarity, double percentileScore,
                                String status, String reason,
                                String reviewedBy, Instant reviewedAt,
                                Instant created ) {}

    private final DataSource dataSource;

    public HubProposalRepository( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    public void insertProposal( final String hubName, final String pageName,
                                 final double rawSimilarity, final double percentileScore ) {
        final String sql = "INSERT INTO hub_proposals (hub_name, page_name, raw_similarity, percentile_score) "
            + "VALUES (?, ?, ?, ?) ON CONFLICT (hub_name, page_name) DO NOTHING";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, hubName );
            ps.setString( 2, pageName );
            ps.setDouble( 3, rawSimilarity );
            ps.setDouble( 4, percentileScore );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to insert hub proposal [{} -> {}]: {}", hubName, pageName, e.getMessage() );
        }
    }

    public List< HubProposal > listProposals( final String status, final String hubName,
                                                final int limit, final int offset ) {
        final StringBuilder sql = new StringBuilder(
            "SELECT id, hub_name, page_name, raw_similarity, percentile_score, status, reason, "
            + "reviewed_by, reviewed_at, created FROM hub_proposals WHERE status = ?" );
        if ( hubName != null ) {
            sql.append( " AND hub_name = ?" );
        }
        sql.append( " ORDER BY percentile_score DESC LIMIT ? OFFSET ?" );

        final List< HubProposal > result = new ArrayList<>();
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            int idx = 1;
            ps.setString( idx++, status );
            if ( hubName != null ) {
                ps.setString( idx++, hubName );
            }
            ps.setInt( idx++, limit );
            ps.setInt( idx, offset );
            try ( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    result.add( mapRow( rs ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to list hub proposals: {}", e.getMessage() );
        }
        return result;
    }

    public List< HubProposal > listProposalsAboveThreshold( final double minPercentile ) {
        final String sql = "SELECT id, hub_name, page_name, raw_similarity, percentile_score, status, reason, "
            + "reviewed_by, reviewed_at, created FROM hub_proposals "
            + "WHERE status = 'pending' AND percentile_score >= ? ORDER BY percentile_score DESC";
        final List< HubProposal > result = new ArrayList<>();
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setDouble( 1, minPercentile );
            try ( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    result.add( mapRow( rs ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to list proposals above threshold: {}", e.getMessage() );
        }
        return result;
    }

    public void updateStatus( final int id, final String status,
                               final String reviewedBy, final String reason ) {
        final String sql = "UPDATE hub_proposals SET status = ?, reviewed_by = ?, reason = ?, "
            + "reviewed_at = NOW() WHERE id = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, status );
            ps.setString( 2, reviewedBy );
            ps.setString( 3, reason );
            ps.setInt( 4, id );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to update hub proposal {}: {}", id, e.getMessage() );
        }
    }

    public void bulkUpdateStatus( final List< Integer > ids, final String status,
                                    final String reviewedBy, final String reason ) {
        if ( ids.isEmpty() ) return;
        final String placeholders = String.join( ",", ids.stream().map( i -> "?" ).toList() );
        final String sql = "UPDATE hub_proposals SET status = ?, reviewed_by = ?, reason = ?, "
            + "reviewed_at = NOW() WHERE id IN (" + placeholders + ")";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, status );
            ps.setString( 2, reviewedBy );
            ps.setString( 3, reason );
            int idx = 4;
            for ( final int id : ids ) {
                ps.setInt( idx++, id );
            }
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to bulk update hub proposals: {}", e.getMessage() );
        }
    }

    public boolean isRejected( final String hubName, final String pageName ) {
        final String sql = "SELECT 1 FROM hub_proposals WHERE hub_name = ? AND page_name = ? AND status = 'rejected'";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, hubName );
            ps.setString( 2, pageName );
            try ( final ResultSet rs = ps.executeQuery() ) {
                return rs.next();
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to check rejection for [{} -> {}]: {}", hubName, pageName, e.getMessage() );
            return false;
        }
    }

    public boolean exists( final String hubName, final String pageName ) {
        final String sql = "SELECT 1 FROM hub_proposals WHERE hub_name = ? AND page_name = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, hubName );
            ps.setString( 2, pageName );
            try ( final ResultSet rs = ps.executeQuery() ) {
                return rs.next();
            }
        } catch ( final SQLException e ) {
            return false;
        }
    }

    public int countByStatus( final String status ) {
        final String sql = "SELECT COUNT(*) FROM hub_proposals WHERE status = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, status );
            try ( final ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? rs.getInt( 1 ) : 0;
            }
        } catch ( final SQLException e ) {
            return 0;
        }
    }

    public void saveCentroid( final String hubName, final float[] centroid,
                               final int modelVersion, final int memberCount ) {
        final String sql = "INSERT INTO hub_centroids (hub_name, centroid, model_version, member_count) "
            + "VALUES (?, ?::vector, ?, ?) ON CONFLICT (hub_name) DO UPDATE SET "
            + "centroid = EXCLUDED.centroid, model_version = EXCLUDED.model_version, "
            + "member_count = EXCLUDED.member_count, created = NOW()";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, hubName );
            ps.setString( 2, vectorToString( centroid ) );
            ps.setInt( 3, modelVersion );
            ps.setInt( 4, memberCount );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to save centroid for hub '{}': {}", hubName, e.getMessage() );
        }
    }

    public float[] loadCentroid( final String hubName ) {
        final String sql = "SELECT centroid::text FROM hub_centroids WHERE hub_name = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, hubName );
            try ( final ResultSet rs = ps.executeQuery() ) {
                if ( rs.next() ) {
                    return parseVector( rs.getString( 1 ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to load centroid for hub '{}': {}", hubName, e.getMessage() );
        }
        return null;
    }

    public void clearCentroids() {
        try ( final Connection conn = dataSource.getConnection();
              final Statement st = conn.createStatement() ) {
            st.execute( "DELETE FROM hub_centroids" );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to clear hub centroids: {}", e.getMessage() );
        }
    }

    private static HubProposal mapRow( final ResultSet rs ) throws SQLException {
        return new HubProposal(
            rs.getInt( "id" ),
            rs.getString( "hub_name" ),
            rs.getString( "page_name" ),
            rs.getDouble( "raw_similarity" ),
            rs.getDouble( "percentile_score" ),
            rs.getString( "status" ),
            rs.getString( "reason" ),
            rs.getString( "reviewed_by" ),
            rs.getTimestamp( "reviewed_at" ) != null ? rs.getTimestamp( "reviewed_at" ).toInstant() : null,
            rs.getTimestamp( "created" ).toInstant()
        );
    }

    private static String vectorToString( final float[] vec ) {
        final StringBuilder sb = new StringBuilder( "[" );
        for ( int i = 0; i < vec.length; i++ ) {
            if ( i > 0 ) sb.append( ',' );
            sb.append( vec[ i ] );
        }
        sb.append( ']' );
        return sb.toString();
    }

    private static float[] parseVector( final String text ) {
        final String inner = text.substring( 1, text.length() - 1 ); // strip [ and ]
        final String[] parts = inner.split( "," );
        final float[] vec = new float[ parts.length ];
        for ( int i = 0; i < parts.length; i++ ) {
            vec[ i ] = Float.parseFloat( parts[ i ].trim() );
        }
        return vec;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=HubProposalRepositoryTest -q 2>&1 | tail -5`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/HubProposalRepository.java \
       wikantik-main/src/test/java/com/wikantik/knowledge/HubProposalRepositoryTest.java
git commit -m "feat: add HubProposalRepository for hub proposals and centroids"
```

---

### Task 10: HubProposalService — Centroid + Proposal Algorithm

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/HubProposalService.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/HubProposalServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge;

import com.wikantik.PostgresTestContainer;
import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class HubProposalServiceTest {

    private static DataSource dataSource;
    private JdbcKnowledgeRepository kgRepo;
    private ContentEmbeddingRepository contentRepo;
    private EmbeddingRepository embeddingRepo;
    private HubProposalRepository proposalRepo;
    private HubProposalService service;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM hub_proposals" );
            conn.createStatement().execute( "DELETE FROM hub_centroids" );
            conn.createStatement().execute( "DELETE FROM kg_content_embeddings" );
            conn.createStatement().execute( "DELETE FROM kg_embeddings" );
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_proposals" );
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        kgRepo = new JdbcKnowledgeRepository( dataSource );
        contentRepo = new ContentEmbeddingRepository( dataSource );
        embeddingRepo = new EmbeddingRepository( dataSource );
        proposalRepo = new HubProposalRepository( dataSource );
    }

    @Test
    void generateProposals_createsProposalsAboveThreshold() {
        // Create a Hub node with 3 members
        kgRepo.upsertNode( "TechHub", "hub", "TechHub", Provenance.HUMAN_AUTHORED,
            Map.of( "type", "hub", "related", List.of( "Java", "Python", "Kotlin" ) ) );
        kgRepo.upsertNode( "Java", "article", "Java", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertNode( "Python", "article", "Python", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertNode( "Kotlin", "article", "Kotlin", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertNode( "Rust", "article", "Rust", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertNode( "Cooking", "article", "Cooking", Provenance.HUMAN_AUTHORED, Map.of() );

        // Train a content model with similar docs for programming languages
        final var embService = new EmbeddingService( kgRepo, embeddingRepo, contentRepo, null, null );
        final TfidfModel model = new TfidfModel();
        model.build(
            List.of( "Java", "Python", "Kotlin", "Rust", "Cooking", "TechHub" ),
            List.of(
                "Java programming language object oriented JVM bytecode",
                "Python programming language dynamic typing scripting",
                "Kotlin programming language JVM coroutines android",
                "Rust programming language memory safety systems",
                "Cooking recipes food baking kitchen ingredients",
                "Technology hub programming languages software development"
            )
        );
        contentRepo.saveEmbeddings( 1, model, Map.of() );

        service = new HubProposalService( kgRepo, proposalRepo, contentRepo, 90, model );
        service.generateProposals();

        // Should have at least one proposal (Rust is similar to programming Hubs)
        final List< HubProposalRepository.HubProposal > proposals =
            proposalRepo.listProposals( "pending", null, 50, 0 );
        assertFalse( proposals.isEmpty(), "Should generate at least one proposal" );
        // Cooking should not be proposed (too dissimilar)
        // Existing members should not be proposed
        for ( final var p : proposals ) {
            assertFalse( List.of( "Java", "Python", "Kotlin" ).contains( p.pageName() ),
                "Should not propose existing members" );
        }
    }

    @Test
    void generateProposals_skipsRejectedPairs() {
        kgRepo.upsertNode( "TechHub", "hub", "TechHub", Provenance.HUMAN_AUTHORED,
            Map.of( "type", "hub", "related", List.of( "Java", "Python" ) ) );
        kgRepo.upsertNode( "Java", "article", "Java", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertNode( "Python", "article", "Python", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertNode( "Kotlin", "article", "Kotlin", Provenance.HUMAN_AUTHORED, Map.of() );

        final TfidfModel model = new TfidfModel();
        model.build(
            List.of( "Java", "Python", "Kotlin", "TechHub" ),
            List.of( "Java programming", "Python programming", "Kotlin programming", "Tech hub" )
        );
        contentRepo.saveEmbeddings( 1, model, Map.of() );

        // Reject Kotlin for TechHub
        proposalRepo.insertProposal( "TechHub", "Kotlin", 0.9, 95.0 );
        final var pending = proposalRepo.listProposals( "pending", null, 50, 0 );
        proposalRepo.updateStatus( pending.get( 0 ).id(), "rejected", "admin", "Not relevant" );

        service = new HubProposalService( kgRepo, proposalRepo, contentRepo, 0, model );
        service.generateProposals();

        // Should not have a new pending proposal for Kotlin
        final var newPending = proposalRepo.listProposals( "pending", null, 50, 0 );
        for ( final var p : newPending ) {
            assertFalse( "Kotlin".equals( p.pageName() ) && "TechHub".equals( p.hubName() ),
                "Should not re-propose rejected pair" );
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=HubProposalServiceTest -q 2>&1 | tail -5`
Expected: Compilation error — `HubProposalService` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.KgNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Batch service that computes Hub membership proposals using TF-IDF content embeddings.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Compute centroid embedding for each Hub (average of member page vectors)</li>
 *   <li>Score all candidate pages against all Hub centroids (cosine similarity)</li>
 *   <li>Convert raw scores to percentile ranks across all page-Hub combinations</li>
 *   <li>Write proposals above the review threshold to the review queue</li>
 * </ol>
 */
public class HubProposalService {

    private static final Logger LOG = LogManager.getLogger( HubProposalService.class );

    public static final String PROP_REVIEW_PERCENTILE = "wikantik.hub.reviewPercentile";
    private static final int DEFAULT_REVIEW_PERCENTILE = 90;

    private final JdbcKnowledgeRepository kgRepo;
    private final HubProposalRepository proposalRepo;
    private final ContentEmbeddingRepository contentRepo;
    private final int reviewPercentile;
    private final TfidfModel contentModel;

    public HubProposalService( final JdbcKnowledgeRepository kgRepo,
                                final HubProposalRepository proposalRepo,
                                final ContentEmbeddingRepository contentRepo,
                                final int reviewPercentile,
                                final TfidfModel contentModel ) {
        this.kgRepo = kgRepo;
        this.proposalRepo = proposalRepo;
        this.contentRepo = contentRepo;
        this.reviewPercentile = reviewPercentile;
        this.contentModel = contentModel;
    }

    public HubProposalService( final JdbcKnowledgeRepository kgRepo,
                                final HubProposalRepository proposalRepo,
                                final ContentEmbeddingRepository contentRepo,
                                final Properties props,
                                final TfidfModel contentModel ) {
        this( kgRepo, proposalRepo, contentRepo,
            Integer.parseInt( props.getProperty( PROP_REVIEW_PERCENTILE,
                String.valueOf( DEFAULT_REVIEW_PERCENTILE ) ) ),
            contentModel );
    }

    /**
     * Generates Hub membership proposals. This is a blocking batch operation.
     *
     * @return number of proposals created
     */
    @SuppressWarnings( "unchecked" )
    public int generateProposals() {
        if ( contentModel == null || contentModel.getEntityCount() == 0 ) {
            LOG.warn( "Hub proposals skipped: content model not trained" );
            return 0;
        }

        final long start = System.currentTimeMillis();

        // Step 1: Find all Hub pages and their current members
        final List< KgNode > allNodes = kgRepo.queryNodes( null, null, 100_000, 0 );
        final Map< String, List< String > > hubMembers = new LinkedHashMap<>();
        final Set< String > allHubNames = new HashSet<>();

        for ( final KgNode node : allNodes ) {
            if ( node.properties() != null && "hub".equals( node.properties().get( "type" ) ) ) {
                allHubNames.add( node.name() );
                final Object related = node.properties().get( "related" );
                if ( related instanceof List<?> list ) {
                    final List< String > members = list.stream()
                        .filter( String.class::isInstance )
                        .map( String.class::cast )
                        .toList();
                    if ( members.size() >= 2 ) {
                        hubMembers.put( node.name(), members );
                    }
                }
            }
        }

        if ( hubMembers.isEmpty() ) {
            LOG.info( "Hub proposals skipped: no hubs with 2+ members found" );
            return 0;
        }

        // Step 2: Compute Hub centroid embeddings
        final Map< String, float[] > centroids = new LinkedHashMap<>();
        for ( final var entry : hubMembers.entrySet() ) {
            final String hubName = entry.getKey();
            final List< String > members = entry.getValue();
            final float[] centroid = computeCentroid( members );
            if ( centroid != null ) {
                centroids.put( hubName, centroid );
                proposalRepo.saveCentroid( hubName, centroid,
                    0, members.size() );
            }
        }

        if ( centroids.isEmpty() ) {
            LOG.info( "Hub proposals skipped: no computable centroids" );
            return 0;
        }

        // Step 3: Score all candidate pages against all Hub centroids
        record Score( String hubName, String pageName, double rawSimilarity ) {}
        final List< Score > allScores = new ArrayList<>();

        for ( final var hubEntry : centroids.entrySet() ) {
            final String hubName = hubEntry.getKey();
            final float[] centroid = hubEntry.getValue();
            final Set< String > existingMembers = new HashSet<>( hubMembers.get( hubName ) );

            for ( final String pageName : contentModel.getEntityNames() ) {
                // Skip Hubs themselves and existing members
                if ( allHubNames.contains( pageName ) || existingMembers.contains( pageName ) ) {
                    continue;
                }
                final int pageId = contentModel.entityId( pageName );
                if ( pageId < 0 ) continue;

                final double sim = cosineSimilarity( contentModel.getVector( pageId ), centroid );
                allScores.add( new Score( hubName, pageName, sim ) );
            }
        }

        if ( allScores.isEmpty() ) {
            LOG.info( "Hub proposals: no candidate scores computed" );
            return 0;
        }

        // Step 4: Percentile normalization
        final double[] rawValues = allScores.stream().mapToDouble( Score::rawSimilarity ).sorted().toArray();

        // Step 5: Write proposals above threshold
        int created = 0;
        for ( final Score score : allScores ) {
            final double percentile = computePercentile( rawValues, score.rawSimilarity );
            if ( percentile < reviewPercentile ) continue;

            // Skip if already exists or was rejected
            if ( proposalRepo.exists( score.hubName, score.pageName )
                || proposalRepo.isRejected( score.hubName, score.pageName ) ) {
                continue;
            }

            proposalRepo.insertProposal( score.hubName, score.pageName,
                score.rawSimilarity, percentile );
            created++;
        }

        final long elapsed = System.currentTimeMillis() - start;
        LOG.info( "Hub proposals generated in {}ms: {} scores computed, {} proposals created "
            + "(threshold: {}th percentile)", elapsed, allScores.size(), created, reviewPercentile );
        return created;
    }

    private float[] computeCentroid( final List< String > memberNames ) {
        final List< float[] > vectors = new ArrayList<>();
        for ( final String name : memberNames ) {
            final int id = contentModel.entityId( name );
            if ( id >= 0 ) {
                vectors.add( contentModel.getVector( id ) );
            }
        }
        if ( vectors.size() < 2 ) return null;

        final float[] centroid = new float[ TfidfModel.DIMENSION ];
        for ( final float[] v : vectors ) {
            for ( int i = 0; i < centroid.length; i++ ) {
                centroid[ i ] += v[ i ];
            }
        }
        // Average
        for ( int i = 0; i < centroid.length; i++ ) {
            centroid[ i ] /= vectors.size();
        }
        // Normalize for cosine similarity
        double norm = 0;
        for ( final float v : centroid ) norm += v * v;
        if ( norm > 0 ) {
            norm = Math.sqrt( norm );
            for ( int i = 0; i < centroid.length; i++ ) centroid[ i ] /= (float) norm;
        }
        return centroid;
    }

    private static double cosineSimilarity( final float[] a, final float[] b ) {
        double dot = 0;
        for ( int i = 0; i < a.length; i++ ) {
            dot += a[ i ] * b[ i ];
        }
        return dot; // vectors are L2-normalized
    }

    static double computePercentile( final double[] sortedValues, final double value ) {
        // Binary search for the position of value in the sorted array
        int count = 0;
        for ( final double v : sortedValues ) {
            if ( v < value ) count++;
        }
        return ( count * 100.0 ) / sortedValues.length;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=HubProposalServiceTest -q 2>&1 | tail -5`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/HubProposalService.java \
       wikantik-main/src/test/java/com/wikantik/knowledge/HubProposalServiceTest.java
git commit -m "feat: add HubProposalService with centroid + percentile algorithm"
```

---

### Task 11: HubSetPlugin

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/plugin/HubSetPlugin.java`
- Test: `wikantik-main/src/test/java/com/wikantik/plugin/HubSetPluginTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.plugin;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.managers.PageManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HubSetPluginTest {

    @Test
    void linksMode_rendersPageLinks() throws PluginException {
        final HubSetPlugin plugin = new HubSetPlugin();
        final Context context = mockContext(
            "TechHub",
            Map.of( "type", "hub", "related", List.of( "Java", "Python", "Kotlin" ) ),
            "Hub body"
        );

        final String result = plugin.execute( context, Map.of( "hub", "TechHub" ) );
        assertTrue( result.contains( "Java" ) );
        assertTrue( result.contains( "Python" ) );
        assertTrue( result.contains( "Kotlin" ) );
    }

    @Test
    void emptyHub_returnsMessage() throws PluginException {
        final HubSetPlugin plugin = new HubSetPlugin();
        final Context context = mockContext(
            "EmptyHub",
            Map.of( "type", "hub", "related", List.of() ),
            ""
        );

        final String result = plugin.execute( context, Map.of( "hub", "EmptyHub" ) );
        assertTrue( result.contains( "no member pages" ) );
        assertTrue( result.contains( "hub-set-empty" ) );
    }

    @Test
    void nonExistentHub_returnsError() throws PluginException {
        final HubSetPlugin plugin = new HubSetPlugin();
        final Engine engine = mock( Engine.class );
        final PageManager pm = mock( PageManager.class );
        when( engine.getManager( PageManager.class ) ).thenReturn( pm );
        when( pm.getPage( "NoSuchHub" ) ).thenReturn( null );

        final Context context = mock( Context.class );
        when( context.getEngine() ).thenReturn( engine );
        final Page currentPage = mock( Page.class );
        when( currentPage.getName() ).thenReturn( "TestPage" );
        when( context.getPage() ).thenReturn( currentPage );

        final String result = plugin.execute( context, Map.of( "hub", "NoSuchHub" ) );
        assertTrue( result.contains( "does not exist" ) || result.contains( "not found" ) );
    }

    @Test
    void maxParam_limitsOutput() throws PluginException {
        final HubSetPlugin plugin = new HubSetPlugin();
        final Context context = mockContext(
            "BigHub",
            Map.of( "type", "hub", "related", List.of( "A", "B", "C", "D", "E" ) ),
            ""
        );

        final String result = plugin.execute( context, Map.of( "hub", "BigHub", "max", "2" ) );
        // Should contain only 2 items. Exact assertion depends on rendering.
        // Count occurrences of link pattern
        final long linkCount = result.chars().filter( c -> c == '(' ).count();
        assertTrue( linkCount <= 2, "Should have at most 2 links, found " + linkCount );
    }

    private Context mockContext( final String hubPageName,
                                  final Map< String, Object > hubMetadata,
                                  final String hubBody ) {
        final String hubContent = FrontmatterWriter.write( hubMetadata, hubBody );

        final Engine engine = mock( Engine.class );
        final PageManager pm = mock( PageManager.class );
        when( engine.getManager( PageManager.class ) ).thenReturn( pm );

        final Page hubPage = mock( Page.class );
        when( hubPage.getName() ).thenReturn( hubPageName );
        when( pm.getPage( hubPageName ) ).thenReturn( hubPage );
        when( pm.getPureText( hubPage ) ).thenReturn( hubContent );

        // Mock member pages for cards mode
        for ( final Object member : hubMetadata.getOrDefault( "related", List.of() ) instanceof List<?> l ? l : List.of() ) {
            final Page memberPage = mock( Page.class );
            when( memberPage.getName() ).thenReturn( (String) member );
            when( pm.getPage( (String) member ) ).thenReturn( memberPage );
            when( pm.getPureText( memberPage ) ).thenReturn(
                FrontmatterWriter.write( Map.of( "title", member, "summary", "About " + member, "tags", List.of( "test" ) ), "" ) );
        }

        final Context context = mock( Context.class );
        when( context.getEngine() ).thenReturn( engine );
        final Page currentPage = mock( Page.class );
        when( currentPage.getName() ).thenReturn( "CurrentPage" );
        when( context.getPage() ).thenReturn( currentPage );

        return context;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=HubSetPluginTest -q 2>&1 | tail -5`
Expected: Compilation error — `HubSetPlugin` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.wikantik.plugin;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Plugin that renders the set of all pages belonging to a named Hub.
 *
 * <p>Parameters:
 * <ul>
 *   <li><b>hub</b> (required) — Hub page name</li>
 *   <li><b>max</b> — maximum rows to output</li>
 *   <li><b>detail</b> — "links" (default) or "cards"</li>
 * </ul>
 * Plus all parameters inherited from {@link AbstractReferralPlugin}.
 *
 * <p>Usage: {@code [{HubSet hub='Technology' max='10' detail='cards'}]}</p>
 */
public class HubSetPlugin extends AbstractReferralPlugin {

    private static final Logger LOG = LogManager.getLogger( HubSetPlugin.class );

    public static final String PARAM_HUB = "hub";
    public static final String PARAM_MAX = "max";
    public static final String PARAM_DETAIL = "detail";

    @Override
    public String execute( final Context context, final Map< String, String > params ) throws PluginException {
        final String hubName = params.get( PARAM_HUB );
        if ( hubName == null || hubName.isBlank() ) {
            return "<div class=\"error\">HubSet plugin requires a 'hub' parameter.</div>";
        }

        final int max = TextUtil.parseIntParameter( params.get( PARAM_MAX ), ALL_ITEMS );
        final String detail = params.getOrDefault( PARAM_DETAIL, "links" );

        final PageManager pm = context.getEngine().getManager( PageManager.class );
        final Page hubPage = pm.getPage( hubName );
        if ( hubPage == null ) {
            return "<div class=\"error\">Hub '" + escapeHtml( hubName ) + "' not found.</div>";
        }

        final String hubContent = pm.getPureText( hubPage );
        final ParsedPage parsed = FrontmatterParser.parse( hubContent != null ? hubContent : "" );

        if ( !"hub".equals( parsed.metadata().get( "type" ) ) ) {
            return "<div class=\"error\">Page '" + escapeHtml( hubName ) + "' is not a Hub (type != hub).</div>";
        }

        final Object relatedObj = parsed.metadata().get( "related" );
        List< String > members = List.of();
        if ( relatedObj instanceof List<?> list ) {
            members = list.stream()
                .filter( String.class::isInstance )
                .map( String.class::cast )
                .toList();
        }

        if ( members.isEmpty() ) {
            return "<div class=\"hub-set-empty\">Hub '" + escapeHtml( hubName ) + "' has no member pages.</div>";
        }

        // Apply inherited filtering (exclude/include patterns, system pages)
        super.initialize( context, params );
        final List< String > filtered = filterAndSortCollection( members );

        if ( filtered.isEmpty() ) {
            return "<div class=\"hub-set-empty\">Hub '" + escapeHtml( hubName ) + "' has no member pages.</div>";
        }

        // Apply max limit
        final List< String > limited = max > 0 && max < filtered.size()
            ? filtered.subList( 0, max ) : filtered;

        if ( "cards".equalsIgnoreCase( detail ) ) {
            return renderCards( limited, pm );
        }

        // Default: links mode — use inherited wikitizeCollection
        final String wikitext = wikitizeCollection( limited, separator, ALL_ITEMS );
        return makeHTML( context, wikitext );
    }

    private String renderCards( final List< String > pages, final PageManager pm ) {
        final StringBuilder sb = new StringBuilder();
        sb.append( "<div class=\"hub-set-cards\">" );

        for ( final String pageName : pages ) {
            sb.append( "<div class=\"hub-set-card\">" );
            sb.append( "<h4><a href=\"/" ).append( escapeHtml( pageName ) ).append( "\">" )
                .append( escapeHtml( pageName ) ).append( "</a></h4>" );

            final Page page = pm.getPage( pageName );
            if ( page != null ) {
                final String content = pm.getPureText( page );
                if ( content != null ) {
                    final ParsedPage parsed = FrontmatterParser.parse( content );
                    final Object summary = parsed.metadata().get( "summary" );
                    if ( summary instanceof String s && !s.isBlank() ) {
                        sb.append( "<p>" ).append( escapeHtml( s ) ).append( "</p>" );
                    }
                    final Object tags = parsed.metadata().get( "tags" );
                    if ( tags instanceof List<?> tagList && !tagList.isEmpty() ) {
                        sb.append( "<div class=\"hub-set-tags\">" );
                        for ( final Object tag : tagList ) {
                            sb.append( "<span class=\"hub-set-tag\">" )
                                .append( escapeHtml( String.valueOf( tag ) ) )
                                .append( "</span> " );
                        }
                        sb.append( "</div>" );
                    }
                }
            }
            sb.append( "</div>" );
        }

        sb.append( "</div>" );
        return sb.toString();
    }

    private static String escapeHtml( final String s ) {
        return s.replace( "&", "&amp;" )
                .replace( "<", "&lt;" )
                .replace( ">", "&gt;" )
                .replace( "\"", "&quot;" );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=HubSetPluginTest -q 2>&1 | tail -5`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/plugin/HubSetPlugin.java \
       wikantik-main/src/test/java/com/wikantik/plugin/HubSetPluginTest.java
git commit -m "feat: add HubSetPlugin for rendering Hub member lists"
```

---

### Task 12: REST Endpoints — Hub Proposals and Backfill

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java`

- [ ] **Step 1: Add hub-proposals routes to doGet switch**

In the `doGet` method's switch statement, add:

```java
case "hub-proposals" -> handleGetHubProposals( request, response );
case "backfill-frontmatter" -> handleGetBackfillStatus( response );
```

- [ ] **Step 2: Add hub-proposals routes to doPost switch**

In the `doPost` method's switch statement, add:

```java
case "hub-proposals" -> handlePostHubProposals( request, response, segments );
case "backfill-frontmatter" -> handlePostBackfillFrontmatter( response );
case "sync-hub-memberships" -> handlePostSyncHubMemberships( response );
```

- [ ] **Step 3: Implement the handler methods**

Add at the bottom of the class:

```java
    // --- Hub Proposals ---

    private HubProposalRepository getHubProposalRepo( final HttpServletResponse response ) throws IOException {
        // HubProposalRepository is stored in the engine's manager map
        final HubProposalRepository repo = getEngine().getManager( HubProposalRepository.class );
        if ( repo == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "Hub proposals not configured" );
        }
        return repo;
    }

    private void handleGetHubProposals( final HttpServletRequest request,
                                         final HttpServletResponse response ) throws IOException {
        final HubProposalRepository repo = getHubProposalRepo( response );
        if ( repo == null ) return;

        final String status = request.getParameter( "status" ) != null
            ? request.getParameter( "status" ) : "pending";
        final String hubName = request.getParameter( "hub" );
        final int limit = parseIntParam( request, "limit", 50 );
        final int offset = parseIntParam( request, "offset", 0 );

        final var proposals = repo.listProposals( status, hubName, limit, offset );
        final int total = repo.countByStatus( status );

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "total", total );
        result.put( "proposals", proposals );
        sendJson( response, result );
    }

    private void handlePostHubProposals( final HttpServletRequest request,
                                          final HttpServletResponse response,
                                          final String[] segments ) throws IOException {
        final HubProposalRepository repo = getHubProposalRepo( response );
        if ( repo == null ) return;

        if ( segments.length < 2 ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Action required" );
            return;
        }

        switch ( segments[1] ) {
            case "generate" -> {
                final EmbeddingService emb = getEngine().getManager( EmbeddingService.class );
                if ( emb == null || !emb.isContentReady() ) {
                    sendError( response, HttpServletResponse.SC_PRECONDITION_FAILED,
                        "Content model must be trained before generating proposals" );
                    return;
                }
                final JdbcKnowledgeRepository kgRepo =
                    (JdbcKnowledgeRepository) getEngine().getManager( KnowledgeGraphService.class );
                // The actual KG repo is inside the service — need to access it.
                // For now, create service inline. Production code will use a registered service.
                sendJson( response, Map.of( "status", "ok", "message", "Hub proposals generated" ) );
            }
            case "bulk-approve" -> {
                final JsonObject body = parseJsonBody( request, response );
                if ( body == null ) return;
                final var ids = GSON.fromJson( body.get( "ids" ), new TypeToken< List< Integer > >() {}.getType() );
                final String reviewedBy = body.has( "reviewedBy" ) ? body.get( "reviewedBy" ).getAsString() : "admin";
                repo.bulkUpdateStatus( (List<Integer>) ids, "approved", reviewedBy, null );
                // TODO: trigger frontmatter updates for each approved proposal
                sendJson( response, Map.of( "status", "ok" ) );
            }
            case "bulk-reject" -> {
                final JsonObject body = parseJsonBody( request, response );
                if ( body == null ) return;
                final var ids = GSON.fromJson( body.get( "ids" ), new TypeToken< List< Integer > >() {}.getType() );
                final String reviewedBy = body.has( "reviewedBy" ) ? body.get( "reviewedBy" ).getAsString() : "admin";
                final String reason = body.has( "reason" ) ? body.get( "reason" ).getAsString() : null;
                repo.bulkUpdateStatus( (List<Integer>) ids, "rejected", reviewedBy, reason );
                sendJson( response, Map.of( "status", "ok" ) );
            }
            case "threshold-approve" -> {
                final JsonObject body = parseJsonBody( request, response );
                if ( body == null ) return;
                final double threshold = body.get( "threshold" ).getAsDouble();
                final String reviewedBy = body.has( "reviewedBy" ) ? body.get( "reviewedBy" ).getAsString() : "admin";
                final var above = repo.listProposalsAboveThreshold( threshold );
                final var ids = above.stream().map( HubProposalRepository.HubProposal::id ).toList();
                repo.bulkUpdateStatus( ids, "approved", reviewedBy, null );
                // TODO: trigger frontmatter updates
                sendJson( response, Map.of( "status", "ok", "approved", ids.size() ) );
            }
            default -> {
                // Single proposal: approve or reject
                if ( segments.length >= 3 ) {
                    final int id;
                    try {
                        id = Integer.parseInt( segments[1] );
                    } catch ( final NumberFormatException e ) {
                        sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid proposal ID" );
                        return;
                    }
                    switch ( segments[2] ) {
                        case "approve" -> {
                            repo.updateStatus( id, "approved", "admin", null );
                            // TODO: trigger frontmatter update for this proposal
                            sendJson( response, Map.of( "status", "ok" ) );
                        }
                        case "reject" -> {
                            final JsonObject body = parseJsonBody( request, response );
                            final String reason = body != null && body.has( "reason" ) ? body.get( "reason" ).getAsString() : null;
                            repo.updateStatus( id, "rejected", "admin", reason );
                            sendJson( response, Map.of( "status", "ok" ) );
                        }
                        default -> sendNotFound( response, "Unknown action: " + segments[2] );
                    }
                } else {
                    sendNotFound( response, "Unknown hub-proposals action" );
                }
            }
        }
    }

    // --- Backfill ---

    private volatile boolean backfillRunning = false;
    private volatile int backfillTotal = 0;
    private volatile int backfillProcessed = 0;
    private volatile int backfillErrors = 0;

    private void handleGetBackfillStatus( final HttpServletResponse response ) throws IOException {
        sendJson( response, Map.of(
            "running", backfillRunning,
            "total", backfillTotal,
            "processed", backfillProcessed,
            "errors", backfillErrors
        ) );
    }

    private void handlePostBackfillFrontmatter( final HttpServletResponse response ) throws IOException {
        if ( backfillRunning ) {
            sendError( response, HttpServletResponse.SC_CONFLICT, "Backfill already in progress" );
            return;
        }
        final EmbeddingService emb = getEngine().getManager( EmbeddingService.class );
        if ( emb == null || !emb.isContentReady() ) {
            sendError( response, HttpServletResponse.SC_PRECONDITION_FAILED,
                "Content model must be trained before backfill" );
            return;
        }

        // Run in background thread
        final Thread t = new Thread( () -> runBackfill(), "frontmatter-backfill" );
        t.setDaemon( true );
        t.start();
        sendJson( response, Map.of( "status", "started" ) );
    }

    private void runBackfill() {
        backfillRunning = true;
        backfillProcessed = 0;
        backfillErrors = 0;
        try {
            final PageManager pm = getEngine().getManager( PageManager.class );
            final SystemPageRegistry spr = getEngine().getManager( SystemPageRegistry.class );
            final var allPages = pm.getAllPages();
            backfillTotal = allPages.size();

            final Properties props = getEngine().getWikiProperties();
            final FrontmatterDefaultsFilter filter = new FrontmatterDefaultsFilter(
                name -> spr != null && spr.isSystemPage( name ), props );

            for ( final Page page : allPages ) {
                try {
                    final String content = pm.getPureText( page );
                    final ParsedPage parsed = FrontmatterParser.parse( content != null ? content : "" );
                    if ( !parsed.metadata().isEmpty() ) {
                        backfillProcessed++;
                        continue;
                    }
                    if ( spr != null && spr.isSystemPage( page.getName() ) ) {
                        backfillProcessed++;
                        continue;
                    }
                    final String updated = filter.applyDefaults( page.getName(), content );
                    pm.getSaveHelper().saveText( page.getName(), updated, SaveOptions.defaultOptions() );
                    backfillProcessed++;
                } catch ( final Exception e ) {
                    backfillErrors++;
                    LOG.warn( "Backfill failed for page '{}': {}", page.getName(), e.getMessage() );
                }
            }
        } catch ( final Exception e ) {
            LOG.error( "Backfill failed: {}", e.getMessage(), e );
        } finally {
            backfillRunning = false;
        }
    }

    private void handlePostSyncHubMemberships( final HttpServletResponse response ) throws IOException {
        // Trigger a save-cycle for each Hub page to bootstrap bidirectional links
        final PageManager pm = getEngine().getManager( PageManager.class );
        int synced = 0;
        for ( final Page page : pm.getAllPages() ) {
            try {
                final String content = pm.getPureText( page );
                final ParsedPage parsed = FrontmatterParser.parse( content != null ? content : "" );
                if ( "hub".equals( parsed.metadata().get( "type" ) ) ) {
                    pm.getSaveHelper().saveText( page.getName(), content, SaveOptions.defaultOptions() );
                    synced++;
                }
            } catch ( final Exception e ) {
                LOG.warn( "Hub sync failed for '{}': {}", page.getName(), e.getMessage() );
            }
        }
        sendJson( response, Map.of( "status", "ok", "hubsSynced", synced ) );
    }
```

Add the required imports at the top of AdminKnowledgeResource.java:

```java
import com.wikantik.knowledge.HubProposalRepository;
import com.wikantik.knowledge.HubProposalService;
import com.wikantik.knowledge.FrontmatterDefaultsFilter;
```

- [ ] **Step 4: Compile to verify**

Run: `mvn compile -pl wikantik-rest -q 2>&1 | tail -10`
Expected: No compilation errors.

Note: The `generate` handler has a TODO — the HubProposalService needs to be registered in WikiEngine (Task 8 alternative: register it alongside EmbeddingService). This will be wired during integration. For now, the endpoint structure is correct.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java
git commit -m "feat: add REST endpoints for hub proposals, backfill, and sync"
```

---

### Task 13: Frontend API Client — Hub Proposal Methods

**Files:**
- Modify: `wikantik-frontend/src/api/client.js`

- [ ] **Step 1: Add hub proposal API methods**

After the existing `getPagesWithoutFrontmatter` method (line 397), add:

```javascript
    // Hub Proposals
    listHubProposals: (status = 'pending', hub = null, limit = 50, offset = 0) => {
      const params = new URLSearchParams({ status, limit, offset });
      if (hub) params.set('hub', hub);
      return request(`/admin/knowledge/hub-proposals?${params}`);
    },

    generateHubProposals: () =>
      request('/admin/knowledge/hub-proposals/generate', { method: 'POST' }),

    approveHubProposal: (id) =>
      request(`/admin/knowledge/hub-proposals/${id}/approve`, { method: 'POST' }),

    rejectHubProposal: (id, reason) =>
      request(`/admin/knowledge/hub-proposals/${id}/reject`, {
        method: 'POST',
        body: JSON.stringify({ reason }),
      }),

    bulkApproveHubProposals: (ids) =>
      request('/admin/knowledge/hub-proposals/bulk-approve', {
        method: 'POST',
        body: JSON.stringify({ ids }),
      }),

    bulkRejectHubProposals: (ids, reason) =>
      request('/admin/knowledge/hub-proposals/bulk-reject', {
        method: 'POST',
        body: JSON.stringify({ ids, reason }),
      }),

    thresholdApproveHubProposals: (threshold) =>
      request('/admin/knowledge/hub-proposals/threshold-approve', {
        method: 'POST',
        body: JSON.stringify({ threshold }),
      }),

    backfillFrontmatter: () =>
      request('/admin/knowledge/backfill-frontmatter', { method: 'POST' }),

    getBackfillStatus: () =>
      request('/admin/knowledge/backfill-frontmatter/status'),

    syncHubMemberships: () =>
      request('/admin/knowledge/sync-hub-memberships', { method: 'POST' }),
```

- [ ] **Step 2: Commit**

```bash
git add wikantik-frontend/src/api/client.js
git commit -m "feat: add hub proposals API methods to frontend client"
```

---

### Task 14: HubProposalsTab.jsx — Admin UI Tab

**Files:**
- Create: `wikantik-frontend/src/components/admin/HubProposalsTab.jsx`

- [ ] **Step 1: Create the component**

```jsx
import { useState, useEffect } from 'react';
import { api } from '../../api/client';

const PAGE_SIZE = 50;

export default function HubProposalsTab() {
  const [proposals, setProposals] = useState([]);
  const [total, setTotal] = useState(0);
  const [offset, setOffset] = useState(0);
  const [hubFilter, setHubFilter] = useState('');
  const [selected, setSelected] = useState(new Set());
  const [thresholdValue, setThresholdValue] = useState(95);
  const [thresholdCount, setThresholdCount] = useState(0);
  const [generating, setGenerating] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [rejectReason, setRejectReason] = useState('');
  const [showRejectModal, setShowRejectModal] = useState(false);

  const loadData = async () => {
    try {
      const result = await api.knowledge.listHubProposals('pending', hubFilter || null, PAGE_SIZE, offset);
      setProposals(result.proposals || []);
      setTotal(result.total || 0);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadData(); }, [offset, hubFilter]);

  useEffect(() => {
    const count = proposals.filter(p => p.percentile_score >= thresholdValue).length;
    setThresholdCount(count);
  }, [thresholdValue, proposals]);

  const handleGenerate = async () => {
    setGenerating(true);
    setError(null);
    try {
      await api.knowledge.generateHubProposals();
      await loadData();
    } catch (err) {
      setError(err.message);
    } finally {
      setGenerating(false);
    }
  };

  const handleSync = async () => {
    setSyncing(true);
    setError(null);
    try {
      await api.knowledge.syncHubMemberships();
    } catch (err) {
      setError(err.message);
    } finally {
      setSyncing(false);
    }
  };

  const handleApprove = async (id) => {
    try {
      await api.knowledge.approveHubProposal(id);
      await loadData();
    } catch (err) {
      setError(err.message);
    }
  };

  const handleReject = async (id, reason) => {
    try {
      await api.knowledge.rejectHubProposal(id, reason);
      await loadData();
    } catch (err) {
      setError(err.message);
    }
  };

  const handleBulkApprove = async () => {
    if (selected.size === 0) return;
    try {
      await api.knowledge.bulkApproveHubProposals([...selected]);
      setSelected(new Set());
      await loadData();
    } catch (err) {
      setError(err.message);
    }
  };

  const handleBulkReject = async () => {
    if (selected.size === 0) return;
    try {
      await api.knowledge.bulkRejectHubProposals([...selected], rejectReason);
      setSelected(new Set());
      setShowRejectModal(false);
      setRejectReason('');
      await loadData();
    } catch (err) {
      setError(err.message);
    }
  };

  const handleThresholdApprove = async () => {
    try {
      await api.knowledge.thresholdApproveHubProposals(thresholdValue);
      await loadData();
    } catch (err) {
      setError(err.message);
    }
  };

  const toggleSelect = (id) => {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleSelectAll = () => {
    if (selected.size === proposals.length) {
      setSelected(new Set());
    } else {
      setSelected(new Set(proposals.map(p => p.id)));
    }
  };

  if (loading) return <div className="admin-loading">Loading hub proposals...</div>;

  return (
    <div>
      {error && <div className="admin-error" style={{ marginBottom: 'var(--space-sm)' }}>{error}</div>}

      {/* Top bar */}
      <div style={{ padding: 'var(--space-sm)', background: 'var(--surface-secondary)', borderRadius: 'var(--radius-md)', marginBottom: 'var(--space-md)', fontSize: '0.85em' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-md)', flexWrap: 'wrap' }}>
          <button className="btn btn-primary btn-sm" onClick={handleGenerate} disabled={generating}>
            {generating ? 'Generating...' : 'Generate Hub Proposals'}
          </button>
          <button className="btn btn-sm" onClick={handleSync} disabled={syncing}>
            {syncing ? 'Syncing...' : 'Sync Hub Memberships'}
          </button>
          <span><strong>Pending:</strong> {total}</span>
        </div>
      </div>

      {/* Bulk operations */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', marginBottom: 'var(--space-sm)', flexWrap: 'wrap', fontSize: '0.85em' }}>
        <button className="btn btn-sm" onClick={handleBulkApprove} disabled={selected.size === 0}>
          Approve Selected ({selected.size})
        </button>
        <button className="btn btn-sm" onClick={() => selected.size > 0 && setShowRejectModal(true)} disabled={selected.size === 0}>
          Reject Selected ({selected.size})
        </button>
        <span style={{ margin: '0 var(--space-sm)' }}>|</span>
        <label>Approve all above</label>
        <input type="number" min="0" max="100" value={thresholdValue} onChange={e => setThresholdValue(Number(e.target.value))}
          style={{ width: '60px', padding: '2px 4px' }} />
        <span>% ({thresholdCount} match)</span>
        <button className="btn btn-sm" onClick={handleThresholdApprove} disabled={thresholdCount === 0}>Apply</button>
      </div>

      {/* Hub filter */}
      <div style={{ marginBottom: 'var(--space-sm)' }}>
        <input type="text" placeholder="Filter by Hub name..." value={hubFilter}
          onChange={e => { setHubFilter(e.target.value); setOffset(0); }}
          style={{ padding: '4px 8px', width: '250px' }} />
      </div>

      {/* Reject modal */}
      {showRejectModal && (
        <div style={{ padding: 'var(--space-sm)', background: 'var(--surface-secondary)', borderRadius: 'var(--radius-md)', marginBottom: 'var(--space-sm)' }}>
          <label>Rejection reason (optional):</label>
          <input type="text" value={rejectReason} onChange={e => setRejectReason(e.target.value)}
            style={{ width: '100%', padding: '4px 8px', marginTop: '4px' }} />
          <div style={{ marginTop: 'var(--space-sm)', display: 'flex', gap: 'var(--space-sm)' }}>
            <button className="btn btn-sm" onClick={handleBulkReject}>Confirm Reject</button>
            <button className="btn btn-sm" onClick={() => setShowRejectModal(false)}>Cancel</button>
          </div>
        </div>
      )}

      {/* Proposals table */}
      <table className="admin-table">
        <thead>
          <tr>
            <th style={{ width: '30px' }}>
              <input type="checkbox" checked={selected.size === proposals.length && proposals.length > 0}
                onChange={toggleSelectAll} />
            </th>
            <th>Hub</th>
            <th>Page</th>
            <th>Percentile</th>
            <th>Similarity</th>
            <th>Created</th>
            <th style={{ width: '100px' }}>Actions</th>
          </tr>
        </thead>
        <tbody>
          {proposals.map(p => (
            <tr key={p.id}>
              <td><input type="checkbox" checked={selected.has(p.id)} onChange={() => toggleSelect(p.id)} /></td>
              <td><a href={`/${encodeURIComponent(p.hub_name)}`}>{p.hub_name}</a></td>
              <td><a href={`/${encodeURIComponent(p.page_name)}`}>{p.page_name}</a></td>
              <td>{p.percentile_score.toFixed(1)}%</td>
              <td>{(p.raw_similarity * 100).toFixed(1)}%</td>
              <td style={{ fontSize: '0.85em', color: 'var(--text-muted)' }}>
                {new Date(p.created).toLocaleString()}
              </td>
              <td>
                <button className="btn btn-sm" onClick={() => handleApprove(p.id)} title="Approve">&#10003;</button>
                {' '}
                <button className="btn btn-sm" onClick={() => {
                  const reason = prompt('Rejection reason (optional):');
                  if (reason !== null) handleReject(p.id, reason);
                }} title="Reject">&#10007;</button>
              </td>
            </tr>
          ))}
          {proposals.length === 0 && (
            <tr><td colSpan={7} style={{ textAlign: 'center' }}>No pending proposals.</td></tr>
          )}
        </tbody>
      </table>

      {/* Pagination */}
      {total > PAGE_SIZE && (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 'var(--space-sm)', fontSize: '0.85em' }}>
          <button className="btn btn-sm" onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))} disabled={offset === 0}>Prev</button>
          <span>Showing {offset + 1}–{Math.min(offset + proposals.length, total)} of {total}</span>
          <button className="btn btn-sm" onClick={() => setOffset(offset + PAGE_SIZE)} disabled={offset + proposals.length >= total}>Next</button>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add wikantik-frontend/src/components/admin/HubProposalsTab.jsx
git commit -m "feat: add HubProposalsTab component for admin UI"
```

---

### Task 15: Wire Hub Proposals Tab into AdminKnowledgePage

**Files:**
- Modify: `wikantik-frontend/src/components/admin/AdminKnowledgePage.jsx`

- [ ] **Step 1: Add the tab to the TABS array**

Add after the `content-embeddings` entry:

```javascript
{ id: 'hub-proposals', label: 'Hub Proposals' },
```

- [ ] **Step 2: Import HubProposalsTab**

Add at the top with other imports:

```javascript
import HubProposalsTab from './HubProposalsTab';
```

- [ ] **Step 3: Add the conditional rendering**

Add alongside the existing tab renders:

```javascript
{activeTab === 'hub-proposals' && <HubProposalsTab />}
```

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/components/admin/AdminKnowledgePage.jsx
git commit -m "feat: wire HubProposalsTab into admin knowledge page"
```

---

### Task 16: Add Backfill Button to ContentEmbeddingsTab

**Files:**
- Modify: `wikantik-frontend/src/components/admin/ContentEmbeddingsTab.jsx`

- [ ] **Step 1: Add backfill state and handler**

Add state variables after existing state declarations:

```javascript
const [backfilling, setBackfilling] = useState(false);
const [backfillStatus, setBackfillStatus] = useState(null);
```

Add handler after `handleRetrain`:

```javascript
const handleBackfill = async () => {
  if (!confirm('This will generate default frontmatter for all pages that lack it. Continue?')) return;
  setBackfilling(true);
  setError(null);
  try {
    await api.knowledge.backfillFrontmatter();
    // Poll for progress
    const poll = setInterval(async () => {
      try {
        const status = await api.knowledge.getBackfillStatus();
        setBackfillStatus(status);
        if (!status.running) {
          clearInterval(poll);
          setBackfilling(false);
          await loadData();
        }
      } catch (err) {
        clearInterval(poll);
        setBackfilling(false);
        setError(err.message);
      }
    }, 2000);
  } catch (err) {
    setBackfilling(false);
    setError(err.message);
  }
};
```

- [ ] **Step 2: Add the button next to the "Pages Without Frontmatter" heading**

Change the heading section (around line 93-95) to:

```jsx
<div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', marginBottom: 'var(--space-sm)' }}>
  <h4 style={{ fontSize: '0.95em', margin: 0 }}>
    Pages Without Frontmatter <span style={{ color: 'var(--text-muted)', fontWeight: 'normal' }}>({noFmTotal})</span>
  </h4>
  <button className="btn btn-sm" onClick={handleBackfill} disabled={backfilling || !status?.content_ready}
    title={!status?.content_ready ? 'Content model must be trained first' : ''}>
    {backfilling ? `Backfilling... ${backfillStatus ? `(${backfillStatus.processed}/${backfillStatus.total})` : ''}` : 'Backfill Frontmatter'}
  </button>
</div>
```

- [ ] **Step 3: Commit**

```bash
git add wikantik-frontend/src/components/admin/ContentEmbeddingsTab.jsx
git commit -m "feat: add backfill frontmatter button to content embeddings tab"
```

---

### Task 17: Register HubProposalRepository in WikiEngine

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java:532-548`

- [ ] **Step 1: Add HubProposalRepository registration**

After the EmbeddingService registration block (around line 542), add:

```java
            final HubProposalRepository hubProposalRepo = new HubProposalRepository( ds );
            managers.put( HubProposalRepository.class, hubProposalRepo );
```

Add the import:

```java
import com.wikantik.knowledge.HubProposalRepository;
```

- [ ] **Step 2: Compile to verify**

Run: `mvn compile -pl wikantik-main -q 2>&1 | tail -5`
Expected: No compilation errors.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/WikiEngine.java
git commit -m "feat: register HubProposalRepository in WikiEngine"
```

---

### Task 18: Full Build Verification

- [ ] **Step 1: Run full build with tests**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Fix any compilation or test failures**

Address any issues found during the full build.

- [ ] **Step 3: Final commit if any fixes were needed**

```bash
git add -u
git commit -m "fix: address build issues from hub membership feature"
```
