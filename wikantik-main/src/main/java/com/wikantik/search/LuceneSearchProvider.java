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
package com.wikantik.search;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.classic.ClassicAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.store.Directory;
import com.wikantik.InternalWikiException;
import com.wikantik.WatchDog;
import com.wikantik.WikiBackgroundThread;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.search.SearchResult;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.core.subsystem.CoreSubsystemBridge;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import com.wikantik.search.subsystem.lucene.DefaultLuceneIndexer;
import com.wikantik.search.subsystem.lucene.DefaultLuceneIndexLifecycle;
import com.wikantik.search.subsystem.lucene.DefaultLuceneSearcher;
import com.wikantik.search.subsystem.lucene.LuceneIndexer;
import com.wikantik.search.subsystem.lucene.LuceneIndexLifecycle;
import com.wikantik.search.subsystem.lucene.LuceneSearcher;
import com.wikantik.util.ClassUtil;
import com.wikantik.util.TextUtil;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Facade over the three-way Lucene decomposition:
 * {@link DefaultLuceneIndexLifecycle} (shared analyzer + stats),
 * {@link DefaultLuceneIndexer} (write side), and
 * {@link DefaultLuceneSearcher} (read side).
 *
 * <p>All public constants and the {@link #initialize(Engine, Properties)}
 * lifecycle method live here. Public methods one-line-delegate to the helpers.
 * The three helpers are accessible via {@link #getIndexer()},
 * {@link #getSearcher()}, {@link #getIndexLifecycle()} for callers that need
 * typed access (e.g. {@code SearchSubsystem.Services} in Ckpt 4).</p>
 *
 * <p><strong>Test-path note:</strong> the package-private constructors bypass
 * {@link #initialize(Engine, Properties)}. Helpers are therefore created lazily
 * on first use — reading the facade's fields via {@code Supplier<>} lambdas —
 * so that reflection-based field injection in test {@code setUp()} methods is
 * visible to the helpers when they are eventually constructed.</p>
 *
 * @since 2.2.21.
 */
public class LuceneSearchProvider implements SearchProvider {

    protected static final Logger LOG = LogManager.getLogger( LuceneSearchProvider.class );

    // -------------------------------------------------------------------------
    // Lucene property names (public constants — used by external callers)
    // -------------------------------------------------------------------------

    /** Which analyzer to use. Default is {@link ClassicAnalyzer}. */
    public static final String PROP_LUCENE_ANALYZER      = "wikantik.lucene.analyzer";
    private static final String PROP_LUCENE_INDEXDELAY   = "wikantik.lucene.indexdelay";
    private static final String PROP_LUCENE_INITIALDELAY = "wikantik.lucene.initialdelay";

    /**
     * How often (in seconds) to check for pages that exist on disk but are not in the Lucene index.
     * Default is 300 seconds (5 minutes). Set to 0 to disable periodic checks.
     */
    private static final String PROP_LUCENE_MISSINGPAGECHECK_INTERVAL = "wikantik.lucene.missingPageCheckInterval";
    private static final int DEFAULT_MISSING_PAGE_CHECK_INTERVAL = 300;

    private int missingPageCheckInterval = DEFAULT_MISSING_PAGE_CHECK_INTERVAL;

    private String analyzerClass = ClassicAnalyzer.class.getName();

    private static final String LUCENE_DIR = "lucene";

    /** These attachment file suffixes will be indexed. */
    public static final java.util.List<String> SEARCHABLE_FILE_SUFFIXES = DefaultLuceneIndexer.SEARCHABLE_FILE_SUFFIXES;

    // -------------------------------------------------------------------------
    // Lucene field names (public so tests and external tools can reference them)
    // -------------------------------------------------------------------------

    protected static final String LUCENE_ID            = DefaultLuceneIndexer.LUCENE_ID;
    protected static final String LUCENE_PAGE_CONTENTS = DefaultLuceneIndexer.LUCENE_PAGE_CONTENTS;
    protected static final String LUCENE_AUTHOR        = DefaultLuceneIndexer.LUCENE_AUTHOR;
    protected static final String LUCENE_ATTACHMENTS   = DefaultLuceneIndexer.LUCENE_ATTACHMENTS;
    protected static final String LUCENE_PAGE_NAME     = DefaultLuceneIndexer.LUCENE_PAGE_NAME;
    protected static final String LUCENE_PAGE_KEYWORDS = DefaultLuceneIndexer.LUCENE_PAGE_KEYWORDS;
    protected static final String LUCENE_PAGE_TAGS     = DefaultLuceneIndexer.LUCENE_PAGE_TAGS;
    protected static final String LUCENE_PAGE_CLUSTER  = DefaultLuceneIndexer.LUCENE_PAGE_CLUSTER;
    protected static final String LUCENE_PAGE_SUMMARY  = DefaultLuceneIndexer.LUCENE_PAGE_SUMMARY;

    /** The maximum number of hits to return from searches. */
    public static final int MAX_SEARCH_HITS = DefaultLuceneSearcher.MAX_SEARCH_HITS;

    /** Create contexts also. Generating contexts can be expensive, so they're not on by default. */
    public static final int FLAG_CONTEXTS = LuceneSearcher.FLAG_CONTEXTS;

    // -------------------------------------------------------------------------
    // Facade-level state (also accessed by test fixtures via reflection)
    // -------------------------------------------------------------------------

    private Engine engine;
    private PageManager pageManager;
    private AttachmentManager attachmentManager;

    /**
     * Resolved once from {@code wikantik.search.lucene.directory.kind} during
     * {@link #initialize(Engine, Properties)} and passed down to both
     * {@link DefaultLuceneSearcher} and {@link DefaultLuceneIndexer}. {@code true}
     * selects {@link org.apache.lucene.store.MMapDirectory}; {@code false}
     * (default) keeps {@link org.apache.lucene.store.NIOFSDirectory}.
     */
    private boolean useMMap;
    @SuppressWarnings("PMD.UnusedPrivateField") // Kept for constructor signature compatibility
    private AuthorizationManager authorizationManager;
    @SuppressWarnings("PMD.UnusedPrivateField") // Kept for constructor signature compatibility
    private AclManager aclManager;
    private SystemPageRegistry systemPageRegistry;
    private Executor searchExecutor;

    /** Cached Lucene Analyzer instance — expensive to create via reflection, so we cache it. */
    private Analyzer analyzer;

    /** Absolute path to the Lucene index directory. */
    private String luceneDirectory;

    /**
     * Pending reindex queue exposed on this facade so that reflection-based
     * test fixtures (e.g. {@code LuceneSearchProviderSystemPageFilterTest})
     * can inject items directly. The actual queue lives in {@link DefaultLuceneIndexer};
     * this field holds the same {@link List} reference.
     */
    protected final List<Object[]> updates = Collections.synchronizedList( new ArrayList<>() );

    // -------------------------------------------------------------------------
    // Lazily-constructed helpers
    // -------------------------------------------------------------------------

    private volatile DefaultLuceneIndexLifecycle lifecycle;
    private volatile DefaultLuceneIndexer indexer;
    private volatile DefaultLuceneSearcher searcher;

    /**
     * Stats returned from a single invocation of {@link #drainUpdateQueue()}.
     *
     * @param totalQueued  total items dequeued (indexed + skipped + failed)
     * @param indexed      items successfully written to the Lucene index
     * @param skipped      items skipped because they are system pages (not a failure)
     * @param failed       items where indexing returned false for non-skip reasons
     */
    public record DrainStats( int totalQueued, int indexed, int skipped, int failed ) {}

    /** Lucene MoreLikeThis hit returned by {@link #moreLikeThis(String, int, Set)}. */
    public record MoreLikeThisHit( String name, float score ) {}

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** No-arg constructor required for reflection-based instantiation. */
    public LuceneSearchProvider() {
    }

    /**
     * Package-private constructor for testing — accepts collaborators directly,
     * bypassing the {@link #initialize(Engine, Properties)} lifecycle.
     */
    LuceneSearchProvider( final PageManager pageManager,
                          final AttachmentManager attachmentManager,
                          final AuthorizationManager authorizationManager,
                          final AclManager aclManager ) {
        this( pageManager, attachmentManager, authorizationManager, aclManager, null );
    }

    /**
     * Package-private constructor for testing that also accepts a
     * {@link SystemPageRegistry} so tests can assert that system pages
     * are filtered out of the index.
     */
    LuceneSearchProvider( final PageManager pageManager,
                          final AttachmentManager attachmentManager,
                          final AuthorizationManager authorizationManager,
                          final AclManager aclManager,
                          final SystemPageRegistry systemPageRegistry ) {
        this.pageManager = pageManager;
        this.attachmentManager = attachmentManager;
        this.authorizationManager = authorizationManager;
        this.aclManager = aclManager;
        this.systemPageRegistry = systemPageRegistry;
        // NOTE: helpers are NOT built here — they are constructed lazily so that
        // test fixtures can inject luceneDirectory / analyzer / searchExecutor /
        // engine via reflection before the first method call.
    }

    // -------------------------------------------------------------------------
    // Lazy helper initialization
    // -------------------------------------------------------------------------

    /**
     * Constructs the lifecycle if not already present.
     * Called before any operation that needs the lifecycle.
     * Uses the facade's {@code analyzer} field, which may have been set via
     * reflection by a test fixture.
     */
    private DefaultLuceneIndexLifecycle lifecycle() {
        if ( lifecycle == null ) {
            synchronized ( this ) {
                if ( lifecycle == null ) {
                    @SuppressWarnings( "PMD.CloseResource" ) // ownership transferred to DefaultLuceneIndexLifecycle
                    final Analyzer a = this.analyzer != null ? this.analyzer : new ClassicAnalyzer();
                    lifecycle = new DefaultLuceneIndexLifecycle( a );
                }
            }
        }
        return lifecycle;
    }

    /**
     * Constructs the indexer if not already present.
     * Uses the same {@link List} reference as {@link #updates} so that
     * reflection-based queue injection in tests targets the correct list.
     */
    private DefaultLuceneIndexer indexer() {
        if ( indexer == null ) {
            synchronized ( this ) {
                if ( indexer == null ) {
                    indexer = new DefaultLuceneIndexer(
                            () -> luceneDirectory,
                            lifecycle(),
                            pageManager,
                            attachmentManager,
                            systemPageRegistry,
                            updates, // share the facade's updates list
                            useMMap );
                }
            }
        }
        return indexer;
    }

    private DefaultLuceneSearcher searcher() {
        if ( searcher == null ) {
            synchronized ( this ) {
                if ( searcher == null ) {
                    final Executor exec = searchExecutor != null
                            ? searchExecutor : Executors.newCachedThreadPool();
                    searcher = new DefaultLuceneSearcher(
                            () -> luceneDirectory,
                            lifecycle(),
                            indexer(),
                            pageManager,
                            engine,
                            exec,
                            useMMap );
                }
            }
        }
        return searcher;
    }

    // -------------------------------------------------------------------------
    // Public accessors for Ckpt 4 (SearchSubsystem.Services wiring)
    // -------------------------------------------------------------------------

    /** @return the write-side helper */
    public LuceneIndexer getIndexer() {
        return indexer();
    }

    /** @return the read-side helper */
    public LuceneSearcher getSearcher() {
        return searcher();
    }

    /** @return the lifecycle helper */
    public LuceneIndexLifecycle getIndexLifecycle() {
        return lifecycle();
    }

    // -------------------------------------------------------------------------
    // SearchProvider / WikiProvider contract
    // -------------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    public void initialize( final Engine engine, final Properties props ) throws NoRequiredPropertyException, IOException {
        this.engine = engine;
        this.pageManager = PageSubsystemBridge.fromLegacyEngine( engine ).pages();
        this.attachmentManager = PageSubsystemBridge.fromLegacyEngine( engine ).attachments();
        this.systemPageRegistry = CoreSubsystemBridge.fromLegacyEngine( engine ).systemPageRegistry();
        // AuthorizationManager and AclManager are initialized after SearchManager
        // in the engine startup sequence, so we resolve them lazily on first use.
        searchExecutor = Executors.newCachedThreadPool();

        luceneDirectory = engine.getWorkDir() + File.separator + LUCENE_DIR;

        final int initialDelay = TextUtil.getIntegerProperty( props, PROP_LUCENE_INITIALDELAY, LuceneUpdater.INITIAL_DELAY );
        final int indexDelay   = TextUtil.getIntegerProperty( props, PROP_LUCENE_INDEXDELAY, LuceneUpdater.INDEX_DELAY );
        missingPageCheckInterval = TextUtil.getIntegerProperty( props, PROP_LUCENE_MISSINGPAGECHECK_INTERVAL, DEFAULT_MISSING_PAGE_CHECK_INTERVAL );

        analyzerClass = TextUtil.getStringProperty( props, PROP_LUCENE_ANALYZER, analyzerClass );

        // Resolve the Directory backend once at init time so both the searcher
        // and indexer agree. Default 'nio' (current behaviour); 'mmap' selects
        // MMapDirectory (Lucene's recommended default on 64-bit Linux).
        final String dirKind = TextUtil.getStringProperty( props,
            com.wikantik.search.subsystem.lucene.LuceneDirectoryFactory.PROP_KIND,
            com.wikantik.search.subsystem.lucene.LuceneDirectoryFactory.DEFAULT_KIND );
        this.useMMap = com.wikantik.search.subsystem.lucene.LuceneDirectoryFactory.parseKind( dirKind );
        LOG.info( "Lucene directory backend: {} ({})",
            this.useMMap ? "MMapDirectory" : "NIOFSDirectory",
            com.wikantik.search.subsystem.lucene.LuceneDirectoryFactory.PROP_KIND + "=" + dirKind );

        try {
            analyzer = ClassUtil.buildInstance( analyzerClass );
            LOG.info( "Lucene analyzer initialized: {}", analyzerClass );
        } catch ( final Exception e ) {
            LOG.error( "Could not initialize LuceneAnalyzer class {}, using default ClassicAnalyzer", analyzerClass, e );
            analyzer = new ClassicAnalyzer();
        }

        final File dir = new File( luceneDirectory );
        LOG.info( "Lucene enabled, cache will be in: {}", dir.getAbsolutePath() );
        try {
            if ( !dir.exists() && !dir.mkdirs() ) {
                LOG.warn( "Failed to create Lucene directory: {}", dir.getAbsolutePath() );
            }
            if ( !dir.exists() || !dir.canWrite() || !dir.canRead() ) {
                LOG.error( "Cannot write to Lucene directory, disabling Lucene: {}", dir.getAbsolutePath() );
                throw new IOException( "Invalid Lucene directory." );
            }
            final String[] filelist = dir.list();
            if ( filelist == null ) {
                throw new IOException( "Invalid Lucene directory: cannot produce listing: " + dir.getAbsolutePath() );
            }
        } catch ( final IOException e ) {
            LOG.error( "Problem while creating Lucene index - not using Lucene.", e );
        }

        // Force eager construction of helpers so the background thread can use them.
        lifecycle();
        indexer();
        searcher();

        final LuceneUpdater updater = new LuceneUpdater( this.engine, this, initialDelay, indexDelay, missingPageCheckInterval );
        updater.start();
    }

    /**
     * Returns the handling engine.
     *
     * @return Current Engine
     */
    protected Engine getEngine() {
        return engine;
    }

    /** {@inheritDoc} */
    @Override
    public String getProviderInfo() {
        return "LuceneSearchProvider";
    }

    // -------------------------------------------------------------------------
    // SearchProvider write-side delegation
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    @Override
    public void pageRemoved( final Page page ) {
        indexer().pageRemoved( page );
    }

    /**
     * Adds a page-text pair to the lucene update queue. Safe to call always.
     *
     * @param page WikiPage to add to the update queue.
     */
    @Override
    public void reindexPage( final Page page ) {
        indexer().reindexPage( page );
    }

    // -------------------------------------------------------------------------
    // SearchProvider read-side delegation
    // -------------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    public Collection<SearchResult> findPages( final String query, final Context wikiContext ) throws ProviderException {
        return searcher().findPages( query, wikiContext );
    }

    /**
     * Searches pages using a particular combination of flags.
     *
     * @param query the query to perform in Lucene query language
     * @param flags a set of flags
     * @return a Collection of SearchResult instances
     * @throws ProviderException if there is a problem with the backend
     */
    public Collection<SearchResult> findPages( final String query, final int flags, final Context wikiContext )
            throws ProviderException {
        return searcher().findPages( query, flags, wikiContext );
    }

    /**
     * Returns up to {@code maxResults} documents similar to {@code seedDocName} based on the
     * {@code contents} field, excluding any document whose {@code id} is in {@code excludeNames}.
     *
     * @param seedDocName  the {@link #LUCENE_ID} value of the seed document
     * @param maxResults   upper bound on returned hits (after exclusions)
     * @param excludeNames document ids to filter out of the result list
     * @return list of similar-document hits, ordered by Lucene relevance score (best first)
     * @throws IOException if the Lucene index cannot be opened or queried
     */
    public List<MoreLikeThisHit> moreLikeThis( final String seedDocName,
                                                final int maxResults,
                                                final Set<String> excludeNames )
            throws IOException {
        final List<LuceneSearcher.MoreLikeThisHit> raw =
                searcher().moreLikeThis( seedDocName, maxResults, excludeNames );
        final List<MoreLikeThisHit> out = new ArrayList<>( raw.size() );
        for ( final LuceneSearcher.MoreLikeThisHit h : raw ) {
            out.add( new MoreLikeThisHit( h.name(), h.score() ) );
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Index stats + ops — delegated to lifecycle / indexer
    // -------------------------------------------------------------------------

    /** @return total number of non-blank queries processed since startup. */
    public long getTotalSearchCount() {
        return lifecycle().getTotalSearchCount();
    }

    /** @return number of queries that produced zero results since startup. */
    public long getZeroResultSearchCount() {
        return lifecycle().getZeroResultSearchCount();
    }

    /** @return elapsed wall-clock millis of the most recent non-blank query, or 0 if none yet. */
    public long getLastQueryElapsedMillis() {
        return lifecycle().getLastQueryElapsedMillis();
    }

    /**
     * @return the timestamp of the most recent successful index update;
     *         {@link Instant#EPOCH} if no update has been recorded yet
     */
    public Instant lastUpdateInstant() {
        return lifecycle().lastUpdateInstant();
    }

    /**
     * Returns the current number of live (non-deleted) documents in the
     * Lucene index, or {@code 0} if the index directory is empty or cannot
     * be opened.
     *
     * @return live document count, or {@code 0} on error/empty index
     */
    public int documentCount() {
        return indexer().documentCount();
    }

    /** @return number of pages currently queued for background reindexing. */
    public int getReindexQueueDepth() {
        return indexer().getReindexQueueDepth();
    }

    /**
     * Removes every document from the Lucene index via
     * {@link IndexWriter#deleteAll()} + {@link IndexWriter#commit()}.
     */
    public void clearIndex() {
        indexer().clearIndex();
    }

    // -------------------------------------------------------------------------
    // Methods that tests call directly (forwarded to indexer)
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the given page name refers to a system page
     * and should therefore be excluded from the Lucene index.
     *
     * @param pageName the wiki page name to check
     * @return true if the page is a system page and should not be indexed
     */
    boolean isSystemPageExcluded( final String pageName ) {
        return indexer().isSystemPageExcluded( pageName );
    }

    /**
     * Updates the lucene index for a single page.
     *
     * @param page The WikiPage to check
     * @param text The page text to index.
     * @return true if the page was successfully indexed
     */
    protected synchronized boolean updateLuceneIndex( final Page page, final String text ) {
        return indexer().updateLuceneIndex( page, text );
    }

    /**
     * Indexes page using the given IndexWriter.
     *
     * @param page   WikiPage
     * @param text   Page text to index
     * @param writer The Lucene IndexWriter to use for indexing
     * @return the created index Document
     * @throws IOException If there's an indexing problem
     */
    protected Document luceneIndexPage( final Page page, final String text, final IndexWriter writer )
            throws IOException {
        return indexer().luceneIndexPage( page, text, writer );
    }

    /**
     * Fetches the attachment content from the repository.
     *
     * @param attachmentName Name of the attachment.
     * @param version        The version of the attachment.
     * @return the content of the Attachment as a String.
     */
    protected String getAttachmentContent( final String attachmentName, final int version ) {
        return indexer().getAttachmentContent( attachmentName, version );
    }

    /**
     * Returns the content of an attachment.
     *
     * @param att Attachment to get content for.
     * @return String representing the content of the file.
     */
    protected String getAttachmentContent( final com.wikantik.api.core.Attachment att ) {
        return indexer().getAttachmentContent( att );
    }

    /**
     * Returns a Set of all page names currently in the Lucene index.
     *
     * @return Set of page names in the index, or empty set if index cannot be read
     */
    protected Set<String> getIndexedPageNames() {
        return indexer().getIndexedPageNames();
    }

    /**
     * Indexes pages that exist on disk but are missing from the Lucene index.
     *
     * @return the number of pages that were indexed
     */
    protected int indexMissingPages() {
        return indexer().indexMissingPages();
    }

    /**
     * Performs a full Lucene reindex, if necessary.
     *
     * @throws IOException If there's a problem during indexing
     */
    protected void doFullLuceneReindex() throws IOException {
        indexer().doFullLuceneReindex();
    }

    /**
     * Creates a new {@link IndexWriter} for the given directory.
     *
     * @param luceneDir the Lucene directory
     * @return a new writer
     * @throws IOException if the writer cannot be opened
     */
    IndexWriter getIndexWriter( final Directory luceneDir ) throws IOException {
        return lifecycle().getIndexWriter( luceneDir );
    }

    // -------------------------------------------------------------------------
    // Drain queue — facade keeps its own DrainStats record type
    // -------------------------------------------------------------------------

    /**
     * Drains the pending reindex queue, writing each page to the Lucene index.
     * The queue drained is the facade's own {@link #updates} list; this
     * preserves compatibility with test fixtures that inject into
     * {@code LuceneSearchProvider.updates} via reflection.
     *
     * @return stats describing what the drain did
     */
    DrainStats drainUpdateQueue() {
        synchronized ( updates ) {
            final int totalQueued = updates.size();
            if ( totalQueued >= DefaultLuceneIndexer.QUEUE_DEPTH_WARN_THRESHOLD ) {
                LOG.warn( "Lucene reindex queue depth {} has reached threshold {} — sustained backpressure; search results may lag",
                          totalQueued, DefaultLuceneIndexer.QUEUE_DEPTH_WARN_THRESHOLD );
            }
            if ( totalQueued == 0 ) {
                return new DrainStats( 0, 0, 0, 0 );
            }

            int processed = 0;
            int failed = 0;
            int skipped = 0;
            while ( !updates.isEmpty() ) {
                final Object[] pair = updates.remove( 0 );
                final Page page = ( Page ) pair[ 0 ];
                final String text = ( String ) pair[ 1 ];
                if ( indexer().isSystemPageExcluded( page.getName() ) ) {
                    LOG.debug( "Drain loop skipping system page '{}'", page.getName() );
                    skipped++;
                } else if ( !indexer().updateLuceneIndex( page, text ) ) {
                    failed++;
                }
                processed++;
                if ( processed % 100 == 0 ) {
                    LOG.info( "Reindex progress: {}/{} pages indexed ({} failed, {} skipped so far)",
                              processed, totalQueued, failed, skipped );
                }
            }
            final int indexed = processed - failed - skipped;
            LOG.info( "Reindex complete: {} pages indexed, {} failed, {} skipped out of {} total",
                      indexed, failed, skipped, totalQueued );
            if ( indexed > 0 ) {
                lifecycle().touchLastUpdateInstant();
            }
            return new DrainStats( totalQueued, indexed, skipped, failed );
        }
    }

    /**
     * Recency multiplier for scoring (delegated to DefaultLuceneSearcher for reference,
     * but kept here for backwards compatibility with any direct callers).
     *
     * @param lastModifiedMs epoch millis of the page's last modification
     * @param nowMs          epoch millis of "now"
     * @return recency multiplier in {@code [RECENCY_FLOOR, 1.0]}
     */
    static double recencyFactor( final long lastModifiedMs, final long nowMs ) {
        return DefaultLuceneSearcher.recencyFactor( lastModifiedMs, nowMs );
    }

    // -------------------------------------------------------------------------
    // Lucene updater background thread
    // -------------------------------------------------------------------------

    /**
     * Updater thread that updates Lucene indexes.
     */
    private static final class LuceneUpdater extends WikiBackgroundThread {
        static final int INDEX_DELAY    = 5;
        static final int INITIAL_DELAY = 60;
        private final LuceneSearchProvider provider;

        private final int initialDelay;
        private final int missingPageCheckInterval;
        private volatile long lastMissingPageCheck;

        private WatchDog watchdog;

        private LuceneUpdater( final Engine engine, final LuceneSearchProvider provider,
                               final int initialDelay, final int indexDelay,
                               final int missingPageCheckInterval ) {
            super( engine, indexDelay );
            this.provider = provider;
            this.initialDelay = initialDelay;
            this.missingPageCheckInterval = missingPageCheckInterval;
            lastMissingPageCheck = System.currentTimeMillis();
            setName( "JSPWiki Lucene Indexer" );
        }

        @Override
        public void startupTask() throws Exception {
            watchdog = WatchDog.getCurrentWatchDog( getEngine() );

            // Sleep in one-second slices, checking for engine shutdown between them.
            // A monolithic sleep here leaked this (non-daemon) thread for the full
            // initial delay after engine.stop(), after which it ran a full reindex
            // against the dead engine.
            try {
                for ( int i = 0; i < initialDelay; i++ ) {
                    if ( isShuttingDown() ) {
                        LOG.info( "Engine shut down during Lucene indexer initial delay — skipping full reindex." );
                        return;
                    }
                    Thread.sleep( 1000L );
                }
            } catch ( final InterruptedException e ) {
                throw new InternalWikiException( "Interrupted while waiting to start.", e );
            }
            if ( isShuttingDown() ) {
                LOG.info( "Engine shut down during Lucene indexer initial delay — skipping full reindex." );
                return;
            }

            watchdog.enterState( "Full reindex" );
            provider.doFullLuceneReindex();
            lastMissingPageCheck = System.currentTimeMillis();
            watchdog.exitState();
        }

        @Override
        public void backgroundTask() {
            watchdog.enterState( "Emptying index queue", 60 );

            provider.drainUpdateQueue();

            watchdog.exitState();

            if ( missingPageCheckInterval > 0 ) {
                final long now = System.currentTimeMillis();
                final long elapsedSeconds = ( now - lastMissingPageCheck ) / 1000L;

                if ( elapsedSeconds >= missingPageCheckInterval ) {
                    watchdog.enterState( "Checking for missing pages", 120 );
                    LOG.debug( "Running periodic check for pages missing from Lucene index" );
                    provider.indexMissingPages();
                    lastMissingPageCheck = now;
                    watchdog.exitState();
                }
            }
        }
    }
}
