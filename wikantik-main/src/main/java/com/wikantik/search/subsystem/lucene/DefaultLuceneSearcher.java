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
package com.wikantik.search.subsystem.lucene;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleHTMLEncoder;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.livevalues.LiveValues;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.search.SearchResult;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.util.TextUtil;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Default implementation of {@link LuceneSearcher}.
 *
 * <p>Read side of the LuceneSearchProvider decomposition. Owns query parsing,
 * scored-hits assembly, ACL filtering, highlighting, snippet extraction, and
 * MoreLikeThis. Reads the Lucene index through the shared {@link LuceneIndexStore}:
 * each query borrows the current reader and gives it back, so consecutive searches
 * reuse one open reader until a commit replaces it.</p>
 */
public class DefaultLuceneSearcher implements LuceneSearcher {

    private static final Logger LOG = LogManager.getLogger( DefaultLuceneSearcher.class );

    /** Maximum number of fragments from search matches. */
    private static final int MAX_FRAGMENTS = 3;

    /** The maximum number of hits to return from searches. */
    public static final int MAX_SEARCH_HITS = 99_999;



    /**
     * Per-field weights applied to {@link MultiFieldQueryParser}. Order mirrors the
     * {@code queryfields} array in {@link #findPages(String, int, Context)}:
     * contents, name, author, attachment, keywords, tags, cluster, summary.
     */
    private static final float[] FIELD_BOOSTS = {
            1.0f,  // contents (body)
            4.0f,  // name (title)
            1.0f,  // author
            1.0f,  // attachment
            3.0f,  // keywords
            2.0f,  // tags
            2.0f,  // cluster
            2.5f   // summary
    };

    private final LuceneIndexStore store;
    private final LuceneIndexLifecycle lifecycle;
    private final LuceneIndexer indexer;
    private final PageManager pageManager;
    private final Engine engine;
    private final Executor searchExecutor;
    /** Resolved per snippet: the live-values service registers after boot. */
    private final java.util.function.Supplier< LiveValues > liveValuesSource;
    /**
     * When {@code false} (the default), the per-result snippet pass is skipped:
     * {@link Highlighter} is not constructed and {@code getBestFragments} is never
     * called. The Highlighter is a known-expensive Lucene pipeline (BM25 tokenizer +
     * MemoryIndex + WeightedSpanTermExtractor + SimpleHTMLEncoder); JFR profiling
     * at N=300 attributed ~600 samples to it across the search call stack. Set
     * {@code wikantik.search.highlighter.enabled=true} in {@code wikantik.properties}
     * to opt back in if a caller actually consumes the snippet fragments.
     *
     * <p>The flag-passing API ({@link #findPages(String, int, Context)} with explicit
     * {@code FLAG_CONTEXTS}) still works regardless of this property — callers that
     * really need snippets can request them directly.</p>
     */
    private final boolean highlightingEnabled;

    /**
     * Stored-field selector for the common case where we only need the page id
     * back from a hit (no snippet highlighting). Passing this to
     * {@link StoredFields#document(int, java.util.Set)} skips materialising the
     * large {@code contents} stored field — which the search path otherwise
     * decompresses + UTF-decodes per result and then discards whenever the
     * Highlighter is off (the default). JFR at moderate load attributed ~17%
     * of CPU to that wasted stored-field read.
     */
    private static final java.util.Set< String > ID_ONLY_FIELDS =
        java.util.Set.of( DefaultLuceneIndexer.LUCENE_ID );

    // Lazily resolved (initialised after SearchManager in engine startup sequence)
    private AuthorizationManager authorizationManager;

    /**
     * Constructs a {@code DefaultLuceneSearcher}.
     *
     * @param store              the index store shared with the indexer, whose commits refresh its reader
     * @param lifecycle          shared lifecycle (analyzer + metrics)
     * @param indexer            used to remove stale index entries found during search
     * @param pageManager        used to load page objects for ACL checks
     * @param engine             used for lazy auth-manager resolution
     * @param searchExecutor     executor for Lucene searcher thread pool
     */
    public DefaultLuceneSearcher( final LuceneIndexStore store,
                                   final LuceneIndexLifecycle lifecycle,
                                   final LuceneIndexer indexer,
                                   final PageManager pageManager,
                                   final Engine engine,
                                   final Executor searchExecutor ) {
        this.store = store;
        this.lifecycle = lifecycle;
        this.indexer = indexer;
        this.pageManager = pageManager;
        this.engine = engine;
        this.searchExecutor = searchExecutor;
        this.liveValuesSource = () -> com.wikantik.livevalues.LiveValuesLookup.of( engine );
        // Defensive: engine.getWikiProperties() can be null in test fixtures that
        // mock Engine. Treat that case as "default" (highlighting off).
        final java.util.Properties wikiProps = engine == null ? null : engine.getWikiProperties();
        this.highlightingEnabled = wikiProps != null
            && TextUtil.getBooleanProperty( wikiProps, "wikantik.search.highlighter.enabled", false );
        if ( !this.highlightingEnabled ) {
            LOG.info( "DefaultLuceneSearcher: snippet highlighting DISABLED via property "
                + "wikantik.search.highlighter.enabled=false (default). "
                + "Two-arg findPages(query, ctx) will skip the Highlighter; callers needing "
                + "snippets can use the three-arg findPages(query, FLAG_CONTEXTS, ctx) directly." );
        }
    }

    /**
     * Package-private constructor for tests that supply the authorization manager directly,
     * bypassing the lazy-resolution path. Reads through its own NIO-backed store.
     */
    DefaultLuceneSearcher( final Supplier<String> directorySupplier,
                            final LuceneIndexLifecycle lifecycle,
                            final LuceneIndexer indexer,
                            final PageManager pageManager,
                            final Engine engine,
                            final Executor searchExecutor,
                            final AuthorizationManager authorizationManager ) {
        this( new LuceneIndexStore( directorySupplier, lifecycle, false ), lifecycle, indexer,
            pageManager, engine, searchExecutor );
        this.authorizationManager = authorizationManager;
    }


    // -------------------------------------------------------------------------
    // LuceneSearcher interface
    // -------------------------------------------------------------------------

    @Override
    public Collection<SearchResult> findPages( final String query, final Context wikiContext )
            throws ProviderException {
        return findPages( query, highlightingEnabled ? FLAG_CONTEXTS : 0, wikiContext );
    }

    @Override
    @SuppressWarnings( "PMD.CloseResource" ) // TokenStream close is handled implicitly via Lucene's internal lifecycle.
    public Collection<SearchResult> findPages( final String query, final int flags,
                                                final Context wikiContext )
            throws ProviderException {
        if ( org.apache.commons.lang3.StringUtils.isBlank( query ) ) {
            return Collections.emptyList();
        }

        final long startMs = System.currentTimeMillis();
        ArrayList<SearchResult> list = new ArrayList<>();
        Highlighter highlighter = null;

        try ( LuceneIndexStore.ReaderLease lease = store.acquire() ) {
            final IndexReader reader = lease.reader();
            final Query luceneQuery = buildQuery( query );
            final IndexSearcher searcher = new IndexSearcher( reader, searchExecutor );
            if ( ( flags & FLAG_CONTEXTS ) != 0 ) {
                highlighter = new Highlighter(
                        new SimpleHTMLFormatter( "<span class=\"searchmatch\">", "</span>" ),
                        new SimpleHTMLEncoder(),
                        new QueryScorer( luceneQuery ) );
            }

            final TopDocs hits = searcher.search( luceneQuery, MAX_SEARCH_HITS );
            // Pass 1 (loadHits) loads each hit's page, within this lease; pass 2 keeps the ones the
            // session may view, asked of the authorization manager in one batch.
            final List< Hit > loaded = loadHits( reader, hits, highlighter != null );
            final Set< String > viewable = filterViewable( wikiContext.getWikiSession(), loaded );
            list = new ArrayList<>( loaded.size() );
            for ( final Hit hit : loaded ) {
                if ( viewable.contains( hit.page().getName() ) ) {
                    list.add( toResult( hit, highlighter ) );
                }
            }
        } catch ( final IndexNotFoundException e ) {
            // No committed segments yet. This is NOT an error and must NOT be thrown
            // from a best-effort read: two transient/empty states raise it, and both
            // legitimately have zero results *available right now* —
            //   * nothing has ever been indexed (a fresh install), and
            //   * a writer is mid-build (Lucene writes pending_segments_N and only
            //     renames it to segments_N on commit).
            // The right contract for a caller waiting on the index (a poller, the
            // Search plugin, a warm-up probe) is "no results yet, come back" — which
            // an empty result expresses, and which a retry loop handles naturally.
            // Throwing a checked/runtime "not ready" here instead was the wrong tool:
            // it turned a transient state into a hard failure that every caller and
            // test had to special-case, and it made a genuinely-empty query counted
            // by the metrics path throw instead. So fall through to the metrics +
            // empty-result tail. (A genuine I/O fault is a DIFFERENT exception —
            // IOException below — and that one DOES propagate.)
            LOG.debug( "Search '{}' ran against an index with no committed segments yet - "
                       + "returning no results for now", query );
        } catch ( final IOException e ) {
            // Never swallow this into an empty list: a failed search that looks like a
            // successful empty one silently corrupts whatever the caller does next.
            // LOG.error justified: Lucene index I/O failure during search.
            LOG.error( "Failed during lucene search", e );
            throw new ProviderException( "search failed: " + e.getMessage(), e );
        } catch ( final ParseException e ) {
            LOG.warn( "Cannot parse search query: [{}]: {}", query, e.getMessage() );
            throw new ProviderException( "You have entered a query Lucene cannot process [" + query + "]: "
                                         + e.getMessage(), e );
        } catch ( final InvalidTokenOffsetsException e ) {
            // LOG.error justified: token offsets mismatch indicates analyzer inconsistency; highlights may be wrong.
            LOG.error( "Tokens are incompatible with provided text ", e );
        }

        final long elapsedMs = System.currentTimeMillis() - startMs;
        lifecycle.recordSearchMetrics( elapsedMs, list.isEmpty() );
        if ( list.isEmpty() ) {
            LOG.warn( "Zero-result search: query='{}' elapsedMs={}", query, elapsedMs );
        } else {
            LOG.info( "Search: query='{}' results={} elapsedMs={}", query, list.size(), elapsedMs );
        }

        return list;
    }

    /** Parses the user's query across the boosted index fields. */
    private Query buildQuery( final String query ) throws ParseException {
        final String[] queryfields = {
                DefaultLuceneIndexer.LUCENE_PAGE_CONTENTS,
                DefaultLuceneIndexer.LUCENE_PAGE_NAME,
                DefaultLuceneIndexer.LUCENE_AUTHOR,
                DefaultLuceneIndexer.LUCENE_ATTACHMENTS,
                DefaultLuceneIndexer.LUCENE_PAGE_KEYWORDS,
                DefaultLuceneIndexer.LUCENE_PAGE_TAGS,
                DefaultLuceneIndexer.LUCENE_PAGE_CLUSTER,
                DefaultLuceneIndexer.LUCENE_PAGE_SUMMARY
        };
        final Map<String, Float> boosts = new HashMap<>();
        for ( int i = 0; i < queryfields.length; i++ ) {
            boosts.put( queryfields[ i ], FIELD_BOOSTS[ i ] );
        }
        final QueryParser qp = new MultiFieldQueryParser( queryfields, lifecycle.getAnalyzer(), boosts );
        return qp.parse( query );
    }

    /**
     * Loads each hit's page. Must run while the reader lease is held: it reads stored fields.
     * A hit whose page is null is handed to the {@link StaleHitReconciler} and dropped.
     */
    private List< Hit > loadHits( final IndexReader reader, final TopDocs hits, final boolean needBody )
            throws IOException, ProviderException {
        final StoredFields storedFields = reader.storedFields();
        final StaleHitReconciler reconciler = new StaleHitReconciler( pageManager, indexer, engine );
        final List< Hit > loaded = new ArrayList<>( hits.scoreDocs.length );
        for ( final ScoreDoc hit : hits.scoreDocs ) {
            // Highlighting off (default): read only the id, and via DocValues so
            // we never decompress the stored-fields block. Highlighting on: read
            // the full stored document because we need the body for snippets.
            final Document doc = needBody ? storedFields.document( hit.doc ) : null;
            final String pageName = needBody
                ? doc.get( DefaultLuceneIndexer.LUCENE_ID )
                : LuceneHitSupport.readPageId( reader, storedFields, hit.doc, ID_ONLY_FIELDS );
            // Deliberately skips the frontmatter parse: this page is used only for
            // ACL/permission checks + lastModified below, never for page variables.
            final Page page = pageManager.getPageWithoutMetadata( pageName, PageProvider.LATEST_VERSION );
            if ( page != null ) {
                loaded.add( new Hit( page, hit.score, doc ) );
            } else {
                reconciler.reconcile( pageName );
            }
        }
        return loaded;
    }

    /**
     * The names of the loaded hits the session may view, in one batch: the authorization manager's
     * filterViewable fast path is the only no-ACL shortcut, so every rule it applies (inline ACLs,
     * private clusters) applies here too.
     */
    private Set< String > filterViewable( final Session session, final List< Hit > loaded ) {
        // AuthorizationManager is initialized after SearchManager in the engine
        // startup sequence, so resolve it lazily from the engine on first use.
        if ( authorizationManager == null ) {
            authorizationManager = AuthSubsystemBridge.fromLegacyEngine( engine ).authorization();
        }
        return loaded.isEmpty() ? Set.of()
                : authorizationManager.filterViewable( session, loaded.stream().map( h -> h.page().getName() ).toList() );
    }

    /** A Lucene hit whose page loaded: the page, the raw Lucene score and the stored document (null unless highlighting). */
    private record Hit( Page page, float score, Document doc ) {}

    /** The search result for a hit the session may view: recency-weighted score plus highlight fragments when asked for. */
    @SuppressWarnings( "PMD.CloseResource" ) // TokenStream close is handled implicitly via Lucene's internal lifecycle.
    private SearchResult toResult( final Hit hit, final Highlighter highlighter )
            throws IOException, InvalidTokenOffsetsException {
        final Date lastModified = hit.page().getLastModified();
        final double recency = lastModified == null ? 1.0
                : LuceneHitSupport.recencyFactor( lastModified.getTime(), System.currentTimeMillis() );
        final int score = ( int ) ( hit.score() * recency * 100 );

        final String text = servedContents( hit.doc() != null ? hit.doc().get( DefaultLuceneIndexer.LUCENE_PAGE_CONTENTS ) : null,
                liveValuesSource.get() );
        String[] fragments = new String[ 0 ];
        if ( text != null && highlighter != null ) {
            final TokenStream tokenStream = lifecycle.getAnalyzer().tokenStream(
                    DefaultLuceneIndexer.LUCENE_PAGE_CONTENTS, new StringReader( text ) );
            fragments = highlighter.getBestFragments( tokenStream, text, MAX_FRAGMENTS );
        }
        return new SearchResultImpl( hit.page(), score, fragments );
    }

    /** The stored page text with live-value tokens expanded (it is stored raw), so a snippet shows the number. */
    public static String servedContents( final String stored, final LiveValues liveValues ) {
        return stored == null || liveValues == null ? stored : liveValues.expandBody( stored );
    }

    // -------------------------------------------------------------------------
    // Inner class: SearchResultImpl
    // -------------------------------------------------------------------------

    private static class SearchResultImpl implements SearchResult {

        private final Page page;
        private final int score;
        private final String[] contexts;

        SearchResultImpl( final Page page, final int score, final String[] contexts ) {
            this.page = page;
            this.score = score;
            this.contexts = contexts != null ? contexts.clone() : null;
        }

        @Override
        public Page getPage() {
            return page;
        }

        @Override
        public int getScore() {
            return score;
        }

        @Override
        public String[] getContexts() {
            return contexts != null ? contexts.clone() : null;
        }
    }
}
