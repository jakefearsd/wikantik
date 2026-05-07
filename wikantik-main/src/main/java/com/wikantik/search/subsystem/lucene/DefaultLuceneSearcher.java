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
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.mlt.MoreLikeThis;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleHTMLEncoder;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import com.wikantik.api.core.Acl;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.search.SearchResult;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;

import java.io.File;
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
 * MoreLikeThis. Reads the Lucene index via a per-call {@link DirectoryReader}
 * (no persistent searcher state). Directory path is obtained from a
 * {@link Supplier}{@code <String>} so that reflective mutations of the facade's
 * {@code luceneDirectory} field are automatically visible.</p>
 */
public class DefaultLuceneSearcher implements LuceneSearcher {

    private static final Logger LOG = LogManager.getLogger( DefaultLuceneSearcher.class );

    /** Maximum number of fragments from search matches. */
    private static final int MAX_FRAGMENTS = 3;

    /** The maximum number of hits to return from searches. */
    public static final int MAX_SEARCH_HITS = 99_999;

    /** Half-life for recency decay: a page's recency multiplier falls from 1.0 toward 0.5 as it ages. */
    private static final long RECENCY_HALF_LIFE_MS = 365L * 24 * 60 * 60 * 1000;

    /** Floor for the recency multiplier so that very old pages still rank (just behind fresh ones). */
    private static final double RECENCY_FLOOR = 0.5;

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

    private final Supplier<String> directorySupplier;
    private final LuceneIndexLifecycle lifecycle;
    private final LuceneIndexer indexer;
    private final PageManager pageManager;
    private final Engine engine;
    private final Executor searchExecutor;

    // Lazily resolved (initialised after SearchManager in engine startup sequence)
    private AuthorizationManager authorizationManager;
    private AclManager aclManager;

    /**
     * Constructs a {@code DefaultLuceneSearcher}.
     *
     * @param directorySupplier lambda that returns the current Lucene index directory path
     * @param lifecycle          shared lifecycle (analyzer + metrics)
     * @param indexer            used to remove stale index entries found during search
     * @param pageManager        used to load page objects for ACL checks
     * @param engine             used for lazy auth-manager resolution
     * @param searchExecutor     executor for Lucene searcher thread pool
     */
    public DefaultLuceneSearcher( final Supplier<String> directorySupplier,
                                   final LuceneIndexLifecycle lifecycle,
                                   final LuceneIndexer indexer,
                                   final PageManager pageManager,
                                   final Engine engine,
                                   final Executor searchExecutor ) {
        this.directorySupplier = directorySupplier;
        this.lifecycle = lifecycle;
        this.indexer = indexer;
        this.pageManager = pageManager;
        this.engine = engine;
        this.searchExecutor = searchExecutor;
    }

    /**
     * Package-private constructor for tests that supply auth managers directly,
     * bypassing the lazy-resolution path.
     */
    DefaultLuceneSearcher( final Supplier<String> directorySupplier,
                            final LuceneIndexLifecycle lifecycle,
                            final LuceneIndexer indexer,
                            final PageManager pageManager,
                            final Engine engine,
                            final Executor searchExecutor,
                            final AuthorizationManager authorizationManager,
                            final AclManager aclManager ) {
        this( directorySupplier, lifecycle, indexer, pageManager, engine, searchExecutor );
        this.authorizationManager = authorizationManager;
        this.aclManager = aclManager;
    }

    private String dir() {
        return directorySupplier.get();
    }

    // -------------------------------------------------------------------------
    // LuceneSearcher interface
    // -------------------------------------------------------------------------

    @Override
    public Collection<SearchResult> findPages( final String query, final Context wikiContext )
            throws ProviderException {
        return findPages( query, FLAG_CONTEXTS, wikiContext );
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

        try ( Directory luceneDir = new NIOFSDirectory( new File( dir() ).toPath() );
              IndexReader reader = DirectoryReader.open( luceneDir ) ) {

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
            final QueryParser qp = new MultiFieldQueryParser(
                    queryfields, lifecycle.getAnalyzer(), boosts );
            final Query luceneQuery = qp.parse( query );
            final IndexSearcher searcher = new IndexSearcher( reader, searchExecutor );

            if ( ( flags & FLAG_CONTEXTS ) != 0 ) {
                highlighter = new Highlighter(
                        new SimpleHTMLFormatter( "<span class=\"searchmatch\">", "</span>" ),
                        new SimpleHTMLEncoder(),
                        new QueryScorer( luceneQuery ) );
            }

            // AuthorizationManager and AclManager are initialized after SearchManager in the engine
            // startup sequence, so resolve them lazily from the engine on first use.
            if ( authorizationManager == null ) {
                authorizationManager = AuthSubsystemBridge.fromLegacyEngine( engine ).authorization();
            }
            if ( aclManager == null ) {
                aclManager = com.wikantik.auth.subsystem.AuthSubsystemBridge.fromLegacyEngine( engine ).aclManager();
            }
            final AuthorizationManager mgr = authorizationManager;
            final AclManager aclMgr = aclManager;
            final PageManager pm = pageManager;
            final Session session = wikiContext.getWikiSession();

            final PagePermission globalViewPerm = new PagePermission(
                    engine.getApplicationName() + ":*", PagePermission.VIEW_ACTION );
            final boolean policyAllowsView = mgr.checkStaticPermission( session, globalViewPerm );

            final TopDocs hits = searcher.search( luceneQuery, MAX_SEARCH_HITS );
            final StoredFields storedFields = reader.storedFields();

            list = new ArrayList<>( hits.scoreDocs.length );
            for ( final ScoreDoc hit : hits.scoreDocs ) {
                final Document doc = storedFields.document( hit.doc );
                final String pageName = doc.get( DefaultLuceneIndexer.LUCENE_ID );
                final Page page = pm.getPage( pageName, PageProvider.LATEST_VERSION );

                if ( page != null ) {
                    final Acl acl = aclMgr.getPermissions( page );
                    final boolean allowed;
                    if ( policyAllowsView && ( acl == null || acl.isEmpty() ) ) {
                        allowed = true;
                    } else {
                        final PagePermission pp = new PagePermission( page, PagePermission.VIEW_ACTION );
                        allowed = mgr.checkPermission( session, pp );
                    }

                    if ( allowed ) {
                        final Date lastModified = page.getLastModified();
                        final double recency = lastModified == null ? 1.0
                                : recencyFactor( lastModified.getTime(), System.currentTimeMillis() );
                        final int score = ( int ) ( hit.score * recency * 100 );

                        final String text = doc.get( DefaultLuceneIndexer.LUCENE_PAGE_CONTENTS );
                        String[] fragments = new String[ 0 ];
                        if ( text != null && highlighter != null ) {
                            final TokenStream tokenStream = lifecycle.getAnalyzer().tokenStream(
                                    DefaultLuceneIndexer.LUCENE_PAGE_CONTENTS, new StringReader( text ) );
                            fragments = highlighter.getBestFragments( tokenStream, text, MAX_FRAGMENTS );
                        }

                        list.add( new SearchResultImpl( page, score, fragments ) );
                    }
                } else {
                    // LOG.error justified: stale index entry; Lucene references a page that no longer exists in the page store.
                    LOG.error( "Lucene found a result page '{}' that could not be loaded, removing from Lucene cache",
                               pageName );
                    indexer.pageRemoved( Wiki.contents().page( engine, pageName ) );
                }
            }
        } catch ( final IOException e ) {
            // LOG.error justified: Lucene index I/O failure during search; results may be empty or incomplete.
            LOG.error( "Failed during lucene search", e );
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

    @Override
    public List<MoreLikeThisHit> moreLikeThis( final String seedDocName, final int maxResults,
                                                final Set<String> excludeNames )
            throws IOException {
        if ( seedDocName == null || seedDocName.isEmpty() || maxResults <= 0 ) {
            return Collections.emptyList();
        }
        final Set<String> excludes = excludeNames == null ? Collections.emptySet() : excludeNames;

        try ( Directory luceneDir = new NIOFSDirectory( new File( dir() ).toPath() );
              IndexReader reader = DirectoryReader.open( luceneDir ) ) {

            final IndexSearcher searcher = new IndexSearcher( reader, searchExecutor );
            final TopDocs seedHits = searcher.search(
                    new TermQuery( new Term( DefaultLuceneIndexer.LUCENE_ID, seedDocName ) ), 1 );
            if ( seedHits.scoreDocs.length == 0 ) {
                return Collections.emptyList();
            }
            final int seedDocId = seedHits.scoreDocs[ 0 ].doc;

            final MoreLikeThis mlt = new MoreLikeThis( reader );
            mlt.setAnalyzer( lifecycle.getAnalyzer() );
            mlt.setFieldNames( new String[] { DefaultLuceneIndexer.LUCENE_PAGE_CONTENTS } );
            mlt.setMinTermFreq( 1 );
            mlt.setMinDocFreq( 1 );
            final Query mltQuery = mlt.like( seedDocId );

            final int fetch = Math.min( 1024, maxResults + excludes.size() + 10 );
            final TopDocs hits = searcher.search( mltQuery, fetch );
            final StoredFields storedFields = reader.storedFields();

            final List<MoreLikeThisHit> out = new ArrayList<>();
            for ( final ScoreDoc sd : hits.scoreDocs ) {
                if ( out.size() >= maxResults ) break;
                final Document doc = storedFields.document( sd.doc );
                final String name = doc.get( DefaultLuceneIndexer.LUCENE_ID );
                if ( name == null || name.equals( seedDocName ) ) continue;
                if ( excludes.contains( name ) ) continue;
                out.add( new MoreLikeThisHit( name, sd.score ) );
            }
            return out;
        }
    }

    // -------------------------------------------------------------------------
    // Recency scoring (package-private for testability)
    // -------------------------------------------------------------------------

    /**
     * Returns a recency multiplier in the range {@code [RECENCY_FLOOR, 1.0]}.
     * Freshly modified pages get {@code 1.0}; the multiplier decays toward
     * {@link #RECENCY_FLOOR} on a {@link #RECENCY_HALF_LIFE_MS} half-life.
     *
     * @param lastModifiedMs epoch millis of the page's last modification
     * @param nowMs          epoch millis of "now" (injected for testability)
     * @return recency multiplier in {@code [RECENCY_FLOOR, 1.0]}
     */
    public static double recencyFactor( final long lastModifiedMs, final long nowMs ) {
        final long ageMs = Math.max( 0L, nowMs - lastModifiedMs );
        final double decay = Math.pow( 0.5, ( double ) ageMs / RECENCY_HALF_LIFE_MS );
        return RECENCY_FLOOR + ( 1.0 - RECENCY_FLOOR ) * decay;
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
            return contexts;
        }
    }
}
