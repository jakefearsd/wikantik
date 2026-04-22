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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.classic.ClassicAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.queries.mlt.MoreLikeThis;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleHTMLEncoder;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import com.wikantik.InternalWikiException;
import com.wikantik.WatchDog;
import com.wikantik.WikiBackgroundThread;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.api.search.SearchResult;
import com.wikantik.api.spi.Wiki;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.core.Acl;
import com.wikantik.api.core.Session;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.api.managers.PageManager;
import com.wikantik.util.ClassUtil;
import com.wikantik.util.FileUtil;
import com.wikantik.util.TextUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;


/**
 * Interface for the search providers that handle searching the Wiki
 *
 * @since 2.2.21.
 */
public class LuceneSearchProvider implements SearchProvider {

    protected static final Logger LOG = LogManager.getLogger( LuceneSearchProvider.class );

    private Engine engine;
    private PageManager pageManager;
    private AttachmentManager attachmentManager;
    private AuthorizationManager authorizationManager;
    private AclManager aclManager;
    private SystemPageRegistry systemPageRegistry;
    private Executor searchExecutor;

    // Lucene properties.

    /** Which analyzer to use.  Default is StandardAnalyzer. */
    public static final String PROP_LUCENE_ANALYZER      = "wikantik.lucene.analyzer";
    private static final String PROP_LUCENE_INDEXDELAY   = "wikantik.lucene.indexdelay";
    private static final String PROP_LUCENE_INITIALDELAY = "wikantik.lucene.initialdelay";

    /**
     * How often (in seconds) to check for pages that exist on disk but are not in the Lucene index.
     * Default is 300 seconds (5 minutes). Set to 0 to disable periodic checks.
     */
    private static final String PROP_LUCENE_MISSINGPAGECHECK_INTERVAL = "wikantik.lucene.missingPageCheckInterval";
    private static final int DEFAULT_MISSING_PAGE_CHECK_INTERVAL = 300;

    /** How often (in seconds) to check for missing pages. Loaded from properties. */
    private int missingPageCheckInterval = DEFAULT_MISSING_PAGE_CHECK_INTERVAL;

    private String analyzerClass = ClassicAnalyzer.class.getName();

    /** Cached Lucene Analyzer instance - expensive to create via reflection, so we cache it. */
    private Analyzer analyzer;

    private static final String LUCENE_DIR = "lucene";

    /** These attachment file suffixes will be indexed. */
    public static final String[] SEARCHABLE_FILE_SUFFIXES = new String[] { ".txt", ".ini", ".xml", ".html", "htm", ".mm", ".htm",
                                                                           ".xhtml", ".java", ".c", ".cpp", ".php", ".asm", ".sh",
                                                                           ".properties", ".kml", ".gpx", ".loc", ".md", ".xml" };

    protected static final String LUCENE_ID            = "id";
    protected static final String LUCENE_PAGE_CONTENTS = "contents";
    protected static final String LUCENE_AUTHOR        = "author";
    protected static final String LUCENE_ATTACHMENTS   = "attachment";
    protected static final String LUCENE_PAGE_NAME     = "name";
    protected static final String LUCENE_PAGE_KEYWORDS = "keywords";
    protected static final String LUCENE_PAGE_TAGS     = "tags";
    protected static final String LUCENE_PAGE_CLUSTER  = "cluster";
    protected static final String LUCENE_PAGE_SUMMARY  = "summary";

    private String luceneDirectory;
    protected final List< Object[] > updates = Collections.synchronizedList( new ArrayList<>() );

    /** Total number of non-blank queries processed since startup. */
    private final AtomicLong totalSearchCount = new AtomicLong();
    /** Subset of {@link #totalSearchCount} whose result set was empty. */
    private final AtomicLong zeroResultSearchCount = new AtomicLong();
    /** Wall-clock duration of the most recent query, in milliseconds. */
    private final AtomicLong lastQueryElapsedMillis = new AtomicLong();

    /**
     * Timestamp of the most recent successful Lucene index update (per-page
     * update, drain cycle, or full reindex). Coarse-grained — intended for
     * the admin rebuild-status UI, not for correctness. Starts at
     * {@link Instant#EPOCH} until the first update lands.
     */
    private volatile Instant lastUpdateInstant = Instant.EPOCH;

    /** Maximum number of fragments from search matches. */
    private static final int MAX_FRAGMENTS = 3;

    /** The maximum number of hits to return from searches. */
    public static final int MAX_SEARCH_HITS = 99_999;

    /** Half-life for recency decay: a page's recency multiplier falls from 1.0 toward 0.5 as it ages. */
    private static final long RECENCY_HALF_LIFE_MS = 365L * 24 * 60 * 60 * 1000;

    /** Floor for the recency multiplier so that very old pages still rank (just behind fresh ones). */
    private static final double RECENCY_FLOOR = 0.5;

    /**
     * Returns a recency multiplier in the range {@code [RECENCY_FLOOR, 1.0]}.
     * Freshly modified pages get {@code 1.0}; the multiplier decays toward
     * {@link #RECENCY_FLOOR} on a {@link #RECENCY_HALF_LIFE_MS} half-life.
     * Callers multiply this against the Lucene relevance score so recent
     * pages edge out older ones of equal textual relevance.
     *
     * @param lastModifiedMs epoch millis of the page's last modification
     * @param nowMs          epoch millis of "now" (injected for testability)
     * @return recency multiplier in {@code [RECENCY_FLOOR, 1.0]}
     */
    static double recencyFactor( final long lastModifiedMs, final long nowMs ) {
        final long ageMs = Math.max( 0L, nowMs - lastModifiedMs );
        final double decay = Math.pow( 0.5, ( double ) ageMs / RECENCY_HALF_LIFE_MS );
        return RECENCY_FLOOR + ( 1.0 - RECENCY_FLOOR ) * decay;
    }

    /**
     * Per-field weights applied to {@link MultiFieldQueryParser}. Order mirrors the
     * {@code queryfields} array in {@link #findPages(String, int, Context)}:
     * contents, name, author, attachment, keywords, tags, cluster, summary.
     * Title/name matches and curated metadata (keywords, tags, summary, cluster)
     * are boosted above raw body text so that a page whose name matches the
     * query is ranked above pages that only mention it in the body.
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

    private static final String PUNCTUATION_TO_SPACES = StringUtils.repeat( " ", TextUtil.PUNCTUATION_CHARS_ALLOWED.length() );

    /** No-arg constructor required for reflection-based instantiation. */
    public LuceneSearchProvider() {
    }

    /**
     * Package-private constructor for testing — accepts collaborators directly,
     * bypassing the {@link #initialize(Engine, Properties)} lifecycle.
     *
     * @param pageManager          the PageManager to use
     * @param attachmentManager    the AttachmentManager to use
     * @param authorizationManager the AuthorizationManager to use
     * @param aclManager           the AclManager to use
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
    }

    /** {@inheritDoc} */
    @Override
    public void initialize( final Engine engine, final Properties props ) throws NoRequiredPropertyException, IOException {
        this.engine = engine;
        this.pageManager = engine.getManager( PageManager.class );
        this.attachmentManager = engine.getManager( AttachmentManager.class );
        this.systemPageRegistry = engine.getManager( SystemPageRegistry.class );
        // AuthorizationManager and AclManager are initialized after SearchManager
        // in the engine startup sequence, so we resolve them lazily on first use.
        searchExecutor = Executors.newCachedThreadPool();

        luceneDirectory = engine.getWorkDir() + File.separator + LUCENE_DIR;

        final int initialDelay = TextUtil.getIntegerProperty( props, PROP_LUCENE_INITIALDELAY, LuceneUpdater.INITIAL_DELAY );
        final int indexDelay   = TextUtil.getIntegerProperty( props, PROP_LUCENE_INDEXDELAY, LuceneUpdater.INDEX_DELAY );
        missingPageCheckInterval = TextUtil.getIntegerProperty( props, PROP_LUCENE_MISSINGPAGECHECK_INTERVAL, DEFAULT_MISSING_PAGE_CHECK_INTERVAL );

        analyzerClass = TextUtil.getStringProperty( props, PROP_LUCENE_ANALYZER, analyzerClass );

        // Initialize the cached analyzer instance
        try {
            analyzer = ClassUtil.buildInstance( analyzerClass );
            LOG.info( "Lucene analyzer initialized: {}", analyzerClass );
        } catch( final Exception e ) {
            LOG.error( "Could not initialize LuceneAnalyzer class {}, using default ClassicAnalyzer", analyzerClass, e );
            analyzer = new ClassicAnalyzer();
        }

        // FIXME: Just to be simple for now, we will do full reindex only if no files are in lucene directory.

        final File dir = new File( luceneDirectory );
        LOG.info( "Lucene enabled, cache will be in: {}", dir.getAbsolutePath() );
        try {
            if( !dir.exists() && !dir.mkdirs() ) {
                LOG.warn( "Failed to create Lucene directory: {}", dir.getAbsolutePath() );
            }

            if( !dir.exists() || !dir.canWrite() || !dir.canRead() ) {
                LOG.error( "Cannot write to Lucene directory, disabling Lucene: {}", dir.getAbsolutePath() );
                throw new IOException( "Invalid Lucene directory." );
            }

            final String[] filelist = dir.list();
            if( filelist == null ) {
                throw new IOException( "Invalid Lucene directory: cannot produce listing: " + dir.getAbsolutePath() );
            }
        } catch( final IOException e ) {
            LOG.error( "Problem while creating Lucene index - not using Lucene.", e );
        }

        // Start the Lucene update thread, which waits first for a little while before starting to go through
        // the Lucene "pages that need updating".
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

    /**
     * Returns {@code true} if the given page name refers to a system page
     * (CSS theme, navigation fragment, layout template, etc.) and should
     * therefore be excluded from the Lucene index. System pages pollute
     * search results and downstream RAG retrieval.
     *
     * <p>If the registry is not wired (e.g. an old test constructor path),
     * this returns {@code false} so behavior degrades to indexing everything —
     * matching the pre-filter behavior rather than silently dropping pages.
     *
     * @param pageName the wiki page name to check
     * @return true if the page is a system page and should not be indexed
     */
    boolean isSystemPageExcluded( final String pageName ) {
        return systemPageRegistry != null
                && pageName != null
                && systemPageRegistry.isSystemPage( pageName );
    }

    /**
     * Performs a full Lucene reindex, if necessary.
     *
     * @throws IOException If there's a problem during indexing
     */
    protected void doFullLuceneReindex() throws IOException {
        final File dir = new File( luceneDirectory );
        if( !dir.exists() && !dir.mkdirs() ) {
            LOG.warn( "Failed to create Lucene directory: {}", dir.getAbsolutePath() );
        }
        final String[] filelist = dir.list();
        if( filelist == null ) {
            throw new IOException( "Invalid Lucene directory: cannot produce listing: " + dir.getAbsolutePath() );
        }

        try {
            if( filelist.length == 0 ) {
                //
                //  No files? Reindex!
                //
                final Date start = new Date();

                LOG.info( "Starting Lucene reindexing, this can take a couple of minutes..." );

                final Directory luceneDir = new NIOFSDirectory( dir.toPath() );
                try( final IndexWriter writer = getIndexWriter( luceneDir ) ) {
                    long pagesIndexed = 0L;
                    long systemPagesSkipped = 0L;
                    final Collection< Page > allPages = pageManager.getAllPages();
                    for( final Page page : allPages ) {
                        if( isSystemPageExcluded( page.getName() ) ) {
                            systemPagesSkipped++;
                            continue;
                        }
                        try {
                            final String text = pageManager.getPageText( page.getName(), WikiProvider.LATEST_VERSION );
                            luceneIndexPage( page, text, writer );
                            pagesIndexed++;
                        } catch( final IOException e ) {
                            LOG.warn( "Unable to index page {}, continuing to next ", page.getName(), e );
                        }
                    }
                    LOG.info( "Indexed {} pages ({} system pages skipped)", pagesIndexed, systemPagesSkipped );

                    long attachmentsIndexed = 0L;
                    final Collection< Attachment > allAttachments = attachmentManager.getAllAttachments();
                    for( final Attachment att : allAttachments ) {
                        try {
                            final String text = getAttachmentContent( att.getName(), WikiProvider.LATEST_VERSION );
                            luceneIndexPage( att, text, writer );
                            attachmentsIndexed++;
                        } catch( final IOException e ) {
                            LOG.warn( "Unable to index attachment {}, continuing to next", att.getName(), e );
                        }
                    }
                    LOG.info( "Indexed {} attachments", attachmentsIndexed );
                }

                final Date end = new Date();
                LOG.info( "Full Lucene index finished in {} milliseconds.", end.getTime() - start.getTime() );
                lastUpdateInstant = Instant.now();
            } else {
                // Index exists - check for pages added outside the wiki UI (e.g., directly to filesystem)
                LOG.info( "Lucene index exists, checking for missing pages..." );
                indexMissingPages();
            }
        } catch( final IOException e ) {
            LOG.error( "Problem while creating Lucene index - not using Lucene.", e );
        } catch( final ProviderException e ) {
            LOG.error( "Problem reading pages while creating Lucene index (JSPWiki won't start.)", e );
            throw new IllegalArgumentException( "unable to create Lucene index", e );
        } catch( final Exception e ) {
            LOG.error( "Unable to start lucene", e );
        }

    }

    /**
     * Fetches the attachment content from the repository.
     * Content is flat text that can be used for indexing/searching or display
     *
     * @param attachmentName Name of the attachment.
     * @param version        The version of the attachment.
     * @return the content of the Attachment as a String.
     */
    protected String getAttachmentContent( final String attachmentName, final int version ) {
        try {
            final Attachment att = attachmentManager.getAttachmentInfo( attachmentName, version );
            //FIXME: Find out why sometimes att is null
            if( att != null ) {
                return getAttachmentContent( att );
            }
        } catch( final ProviderException e ) {
            LOG.error( "Attachment cannot be loaded", e );
        }
        return null;
    }

    /**
     * @param att Attachment to get content for. Filename extension is used to determine the type of the attachment.
     * @return String representing the content of the file.
     * FIXME This is a very simple implementation of some text-based attachment, mainly used for testing.
     * This should be replaced /moved to Attachment search providers or some other 'pluggable' way to search attachments
     */
    protected String getAttachmentContent( final Attachment att ) {
        //FIXME: Add attachment plugin structure

        final String filename = att.getFileName();

        boolean searchSuffix = Arrays.stream(SEARCHABLE_FILE_SUFFIXES).anyMatch(filename::endsWith);

        if( searchSuffix ) {
            try( final InputStream attStream = attachmentManager.getAttachmentStream( att ); final StringWriter sout = new StringWriter() ) {
                FileUtil.copyContents( new InputStreamReader( attStream, StandardCharsets.UTF_8 ), sout );
                return filename + " " + sout;
            } catch( final ProviderException | IOException e ) {
                LOG.error( "Attachment cannot be loaded", e );
            }
        }

        return filename;
    }

    /**
     * Updates the lucene index for a single page.
     *
     * @param page The WikiPage to check
     * @param text The page text to index.
     */
    protected synchronized boolean updateLuceneIndex( final Page page, final String text ) {
        if( isSystemPageExcluded( page.getName() ) ) {
            LOG.debug( "Skipping Lucene index update for system page '{}'", page.getName() );
            return false;
        }
        LOG.debug( "Updating Lucene index for page '{}'...", page.getName() );
        pageRemoved( page );

        // Now add back the new version.
        try( final Directory luceneDir = new NIOFSDirectory( new File( luceneDirectory ).toPath() );
             final IndexWriter writer = getIndexWriter( luceneDir ) ) {
            luceneIndexPage( page, text, writer );
        } catch( final IOException e ) {
            LOG.error( "Unable to update page '{}' from Lucene index", page.getName(), e );
            return false;
        } catch( final Exception e ) {
            LOG.error( "Unexpected Lucene exception - please check configuration!", e );
            return false;
        }

        LOG.debug( "Done updating Lucene index for page '{}'.", page.getName() );
        lastUpdateInstant = Instant.now();
        return true;
    }

    /**
     * Returns the cached Lucene Analyzer instance.
     * The analyzer is initialized once during {@link #initialize(Engine, Properties)} and reused
     * for all subsequent operations, avoiding expensive reflection-based instantiation per request.
     *
     * @return The cached Analyzer instance
     */
    private Analyzer getLuceneAnalyzer() {
        return analyzer;
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
    protected Document luceneIndexPage( final Page page, final String text, final IndexWriter writer ) throws IOException {
        LOG.debug( "Indexing {}...", page.getName() );

        // make a new, empty document
        final Document doc = new Document();
        if( text == null ) {
            return doc;
        }

        final String indexedText = text.replace( "__", " " ); // be nice to Language Analyzers - cfr. JSPWIKI-893

        // Raw name is the keyword we'll use to refer to this document for updates.
        Field field = new Field( LUCENE_ID, page.getName(), StringField.TYPE_STORED );
        doc.add( field );

        // Body text.  It is stored in the doc for search contexts.
        field = new Field( LUCENE_PAGE_CONTENTS, indexedText, TextField.TYPE_STORED );
        doc.add( field );

        // Allow searching by page name. Both beautified and raw
        final String unTokenizedTitle = StringUtils.replaceChars( page.getName(), TextUtil.PUNCTUATION_CHARS_ALLOWED, PUNCTUATION_TO_SPACES );
        field = new Field( LUCENE_PAGE_NAME, TextUtil.beautifyString( page.getName() ) + " " + unTokenizedTitle, TextField.TYPE_STORED );
        doc.add( field );

        // Allow searching by authorname
        if( page.getAuthor() != null ) {
            field = new Field( LUCENE_AUTHOR, page.getAuthor(), TextField.TYPE_STORED );
            doc.add( field );
        }

        // Now add the names of the attachments of this page
        try {
            final List< Attachment > attachments = attachmentManager.listAttachments( page );
            final String attachmentNames = attachments.stream().map(att -> att.getName() + ";").collect(Collectors.joining());

            field = new Field( LUCENE_ATTACHMENTS, attachmentNames, TextField.TYPE_STORED );
            doc.add( field );

        } catch( final ProviderException e ) {
            // Unable to read attachments
            LOG.error( "Failed to get attachments for page", e );
        }

        // also index page keywords, if available
        if( page.getAttribute( "keywords" ) != null ) {
            field = new Field( LUCENE_PAGE_KEYWORDS, page.getAttribute( "keywords" ).toString(), TextField.TYPE_STORED );
            doc.add( field );
        }

        // Index frontmatter metadata (tags, cluster, summary) for semantic search
        try {
            final com.wikantik.api.frontmatter.ParsedPage parsed = com.wikantik.api.frontmatter.FrontmatterParser.parse( text );
            final java.util.Map< String, Object > metadata = parsed.metadata();

            final Object tags = metadata.get( "tags" );
            if ( tags instanceof java.util.List< ? > tagList && !tagList.isEmpty() ) {
                final String tagString = tagList.stream().map( Object::toString ).collect( Collectors.joining( " " ) );
                doc.add( new Field( LUCENE_PAGE_TAGS, tagString, TextField.TYPE_STORED ) );
            }

            final Object cluster = metadata.get( "cluster" );
            if ( cluster != null ) {
                doc.add( new Field( LUCENE_PAGE_CLUSTER, cluster.toString(), TextField.TYPE_STORED ) );
            }

            final Object summary = metadata.get( "summary" );
            if ( summary != null ) {
                doc.add( new Field( LUCENE_PAGE_SUMMARY, summary.toString(), TextField.TYPE_STORED ) );
            }
        } catch ( final Exception e ) {
            LOG.debug( "Could not parse frontmatter for indexing {}: {}", page.getName(), e.getMessage() );
        }

        synchronized( writer ) {
            writer.addDocument( doc );
        }

        return doc;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void pageRemoved( final Page page ) {
        try( final Directory luceneDir = new NIOFSDirectory( new File( luceneDirectory ).toPath() );
             final IndexWriter writer = getIndexWriter( luceneDir ) ) {
            final Query query = new TermQuery( new Term( LUCENE_ID, page.getName() ) );
            writer.deleteDocuments( query );
        } catch( final Exception e ) {
            LOG.error( "Unable to remove page '{}' from Lucene index", page.getName(), e );
        }
    }

    IndexWriter getIndexWriter( final Directory luceneDir ) throws IOException {
        final IndexWriterConfig writerConfig = new IndexWriterConfig( getLuceneAnalyzer() );
        writerConfig.setOpenMode( OpenMode.CREATE_OR_APPEND );
        return new IndexWriter( luceneDir, writerConfig );
    }

    /**
     * Returns a Set of all page names currently in the Lucene index.
     * This is used to efficiently identify pages that exist on disk but are not indexed.
     *
     * @return Set of page names in the index, or empty set if index cannot be read
     */
    protected Set< String > getIndexedPageNames() {
        final Set< String > indexedPages = new HashSet<>();
        final File dir = new File( luceneDirectory );

        final String[] dirFiles = dir.list();
        if( !dir.exists() || dirFiles == null || dirFiles.length == 0 ) {
            return indexedPages;
        }

        try( final Directory luceneDir = new NIOFSDirectory( dir.toPath() );
             final IndexReader reader = DirectoryReader.open( luceneDir ) ) {
            final StoredFields storedFields = reader.storedFields();
            for( int i = 0; i < reader.maxDoc(); i++ ) {
                final Document doc = storedFields.document( i );
                final String pageName = doc.get( LUCENE_ID );
                if( pageName != null ) {
                    indexedPages.add( pageName );
                }
            }
            LOG.debug( "Found {} pages in Lucene index", indexedPages.size() );
        } catch( final IOException e ) {
            LOG.warn( "Could not read Lucene index to get indexed page names", e );
        }

        return indexedPages;
    }

    /**
     * Indexes pages that exist on disk but are missing from the Lucene index.
     * This method is optimized for large wikis - it retrieves page names from the page provider
     * and compares against indexed names without loading full page content until necessary.
     *
     * @return the number of pages that were indexed
     */
    protected int indexMissingPages() {
        final File dir = new File( luceneDirectory );
        final String[] dirFiles = dir.list();
        if( !dir.exists() || dirFiles == null || dirFiles.length == 0 ) {
            // No index exists yet - full reindex will happen
            LOG.debug( "No Lucene index exists, skipping missing page check" );
            return 0;
        }

        int pagesIndexed = 0;
        try {
            // Get set of indexed page names (efficient - only reads LUCENE_ID field)
            final Set< String > indexedPages = getIndexedPageNames();

            // Get all pages from disk
            final Collection< Page > allPages = pageManager.getAllPages();

            // Find pages that exist on disk but not in index. System pages are
            // skipped here too — otherwise, because doFullLuceneReindex() excludes
            // them, every system page would appear "missing" on every sweep and
            // get re-indexed, defeating the filter.
            final List< Page > missingPages = allPages.stream()
                    .filter( page -> !indexedPages.contains( page.getName() ) )
                    .filter( page -> {
                        if( isSystemPageExcluded( page.getName() ) ) {
                            LOG.debug( "Skipping system page '{}' during missing-page sweep", page.getName() );
                            return false;
                        }
                        return true;
                    } )
                    .toList();

            if( !missingPages.isEmpty() ) {
                LOG.info( "Found {} pages missing from Lucene index, indexing...", missingPages.size() );

                try( final Directory luceneDir = new NIOFSDirectory( dir.toPath() );
                     final IndexWriter writer = getIndexWriter( luceneDir ) ) {
                    for( final Page page : missingPages ) {
                        try {
                            final String text = pageManager
                                    .getPageText( page.getName(), WikiProvider.LATEST_VERSION );
                            luceneIndexPage( page, text, writer );
                            pagesIndexed++;
                            LOG.debug( "Indexed missing page: {}", page.getName() );
                        } catch( final IOException e ) {
                            LOG.warn( "Unable to index missing page {}", page.getName(), e );
                        }
                    }
                }
                LOG.info( "Indexed {} missing pages", pagesIndexed );
            }

            // Also check for missing attachments
            final Collection< Attachment > allAttachments = attachmentManager.getAllAttachments();
            final List< Attachment > missingAttachments = allAttachments.stream()
                    .filter( att -> !indexedPages.contains( att.getName() ) )
                    .toList();

            if( !missingAttachments.isEmpty() ) {
                LOG.info( "Found {} attachments missing from Lucene index, indexing...", missingAttachments.size() );

                try( final Directory luceneDir = new NIOFSDirectory( dir.toPath() );
                     final IndexWriter writer = getIndexWriter( luceneDir ) ) {
                    int attachmentsIndexed = 0;
                    for( final Attachment att : missingAttachments ) {
                        try {
                            final String text = getAttachmentContent( att.getName(), WikiProvider.LATEST_VERSION );
                            luceneIndexPage( att, text, writer );
                            attachmentsIndexed++;
                            LOG.debug( "Indexed missing attachment: {}", att.getName() );
                        } catch( final IOException e ) {
                            LOG.warn( "Unable to index missing attachment {}", att.getName(), e );
                        }
                    }
                    LOG.info( "Indexed {} missing attachments", attachmentsIndexed );
                }
            }

        } catch( final ProviderException e ) {
            LOG.error( "Error reading pages while checking for missing Lucene entries", e );
        } catch( final IOException e ) {
            LOG.error( "Error writing to Lucene index while indexing missing pages", e );
        }

        return pagesIndexed;
    }

    /**
     * Adds a page-text pair to the lucene update queue.  Safe to call always
     *
     * @param page WikiPage to add to the update queue.
     */
    @Override
    public void reindexPage( final Page page ) {
        if( page != null ) {
            if( isSystemPageExcluded( page.getName() ) ) {
                LOG.debug( "Skipping system page '{}' — system pages are not indexed", page.getName() );
                return;
            }
            final String text;

            // TODO: Think if this was better done in the thread itself?
            if( page instanceof Attachment att ) {
                text = getAttachmentContent( att );
            } else {
                text = pageManager.getPureText( page );
            }

            if( text != null ) {
                // Add work item to updates queue.
                final Object[] pair = new Object[ 2 ];
                pair[ 0 ] = page;
                pair[ 1 ] = text;
                updates.add( pair );
                LOG.debug( "Scheduling page {} for index update", page.getName() );
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public Collection< SearchResult > findPages( final String query, final Context wikiContext ) throws ProviderException {
        return findPages( query, FLAG_CONTEXTS, wikiContext );
    }

    /** Create contexts also. Generating contexts can be expensive, so they're not on by default. */
    public static final int FLAG_CONTEXTS = 0x01;

    /**
     * Searches pages using a particular combination of flags.
     *
     * @param query The query to perform in Lucene query language
     * @param flags A set of flags
     * @return A Collection of SearchResult instances
     * @throws ProviderException if there is a problem with the backend
     */
    public Collection< SearchResult > findPages( final String query, final int flags, final Context wikiContext ) throws ProviderException {
        // Return empty results for blank queries - Lucene cannot parse empty strings
        if( StringUtils.isBlank( query ) ) {
            return Collections.emptyList();
        }

        final long startMs = System.currentTimeMillis();
        ArrayList< SearchResult > list = new ArrayList<>();
        Highlighter highlighter = null;

        try( final Directory luceneDir = new NIOFSDirectory( new File( luceneDirectory ).toPath() );
             final IndexReader reader = DirectoryReader.open( luceneDir ) ) {
            final String[] queryfields = { LUCENE_PAGE_CONTENTS, LUCENE_PAGE_NAME, LUCENE_AUTHOR, LUCENE_ATTACHMENTS,
                    LUCENE_PAGE_KEYWORDS, LUCENE_PAGE_TAGS, LUCENE_PAGE_CLUSTER, LUCENE_PAGE_SUMMARY };
            final java.util.Map< String, Float > boosts = new java.util.HashMap<>();
            for ( int i = 0; i < queryfields.length; i++ ) {
                boosts.put( queryfields[ i ], FIELD_BOOSTS[ i ] );
            }
            final QueryParser qp = new MultiFieldQueryParser( queryfields, getLuceneAnalyzer(), boosts );
            final Query luceneQuery = qp.parse( query );
            final IndexSearcher searcher = new IndexSearcher( reader, searchExecutor );

            if( ( flags & FLAG_CONTEXTS ) != 0 ) {
                highlighter = new Highlighter( new SimpleHTMLFormatter( "<span class=\"searchmatch\">", "</span>" ),
                                               new SimpleHTMLEncoder(),
                                               new QueryScorer( luceneQuery ) );
            }

            // AuthorizationManager and AclManager are initialized after SearchManager in the engine
            // startup sequence, so resolve them lazily from the engine on first use.
            if( authorizationManager == null ) {
                authorizationManager = engine.getManager( AuthorizationManager.class );
            }
            if( aclManager == null ) {
                aclManager = engine.getManager( AclManager.class );
            }
            final AuthorizationManager mgr = authorizationManager;
            final AclManager aclMgr = aclManager;
            final PageManager pm = pageManager;
            final Session session = wikiContext.getWikiSession();

            // Pre-check: does the user's session pass the static policy for "view"?
            // This is an in-memory check against DatabasePolicy/LocalPolicy — no I/O.
            // If the policy denies view, no search results are visible at all.
            final PagePermission globalViewPerm = new PagePermission( engine.getApplicationName() + ":*", PagePermission.VIEW_ACTION );
            final boolean policyAllowsView = mgr.checkStaticPermission( session, globalViewPerm );

            final TopDocs hits = searcher.search( luceneQuery, MAX_SEARCH_HITS );
            final StoredFields storedFields = reader.storedFields();

            list = new ArrayList<>( hits.scoreDocs.length );
            for( final ScoreDoc hit : hits.scoreDocs ) {
                final Document doc = storedFields.document( hit.doc );
                final String pageName = doc.get( LUCENE_ID );
                final Page page = pm.getPage( pageName, PageProvider.LATEST_VERSION );

                if( page != null ) {
                    // Fast path: if the policy allows view and the page has no ACL,
                    // skip the full checkPermission() call (avoids loading page text
                    // to parse [{ALLOW}] markers for pages that have none).
                    final Acl acl = aclMgr.getPermissions( page );
                    final boolean allowed;
                    if( policyAllowsView && ( acl == null || acl.isEmpty() ) ) {
                        allowed = true;
                    } else {
                        final PagePermission pp = new PagePermission( page, PagePermission.VIEW_ACTION );
                        allowed = mgr.checkPermission( session, pp );
                    }

                    if( allowed ) {
                        final Date lastModified = page.getLastModified();
                        final double recency = lastModified == null ? 1.0
                                : recencyFactor( lastModified.getTime(), System.currentTimeMillis() );
                        final int score = ( int ) ( hit.score * recency * 100 );

                        // Get highlighted search contexts
                        final String text = doc.get( LUCENE_PAGE_CONTENTS );

                        String[] fragments = new String[ 0 ];
                        if( text != null && highlighter != null ) {
                            final TokenStream tokenStream = getLuceneAnalyzer().tokenStream( LUCENE_PAGE_CONTENTS, new StringReader( text ) );
                            fragments = highlighter.getBestFragments( tokenStream, text, MAX_FRAGMENTS );
                        }

                        final SearchResult result = new SearchResultImpl( page, score, fragments );
                        list.add( result );
                    }
                } else {
                    LOG.error( "Lucene found a result page '{}' that could not be loaded, removing from Lucene cache",  pageName );
                    pageRemoved( Wiki.contents().page( engine, pageName ) );
                }
            }
        } catch( final IOException e ) {
            LOG.error( "Failed during lucene search", e );
        } catch( final ParseException e ) {
            LOG.error( "Broken query; cannot parse query: {}", query, e );
            throw new ProviderException( "You have entered a query Lucene cannot process [" + query + "]: " + e.getMessage() );
        } catch( final InvalidTokenOffsetsException e ) {
            LOG.error( "Tokens are incompatible with provided text ", e );
        }

        final long elapsedMs = System.currentTimeMillis() - startMs;
        lastQueryElapsedMillis.set( elapsedMs );
        totalSearchCount.incrementAndGet();
        if( list.isEmpty() ) {
            zeroResultSearchCount.incrementAndGet();
            LOG.warn( "Zero-result search: query='{}' elapsedMs={}", query, elapsedMs );
        } else {
            LOG.info( "Search: query='{}' results={} elapsedMs={}", query, list.size(), elapsedMs );
        }

        return list;
    }

    /** @return total number of non-blank queries processed since startup. */
    public long getTotalSearchCount() {
        return totalSearchCount.get();
    }

    /** @return number of queries that produced zero results since startup. */
    public long getZeroResultSearchCount() {
        return zeroResultSearchCount.get();
    }

    /** @return elapsed wall-clock millis of the most recent non-blank query, or 0 if none yet. */
    public long getLastQueryElapsedMillis() {
        return lastQueryElapsedMillis.get();
    }

    /** @return number of pages currently queued for background reindexing. */
    public int getReindexQueueDepth() {
        return updates.size();
    }

    /**
     * Returns the current number of live (non-deleted) documents in the
     * Lucene index, or {@code 0} if the index directory is empty or cannot
     * be opened. Matches the error-handling style of
     * {@link #getIndexedPageNames()} — IO failures are logged at WARN and
     * a sentinel is returned rather than propagating up the stack.
     *
     * @return live document count, or {@code 0} on error/empty index
     */
    public int documentCount() {
        final File dir = new File( luceneDirectory == null ? "" : luceneDirectory );
        final String[] dirFiles = dir.list();
        if ( !dir.exists() || dirFiles == null || dirFiles.length == 0 ) {
            return 0;
        }
        try ( final Directory luceneDir = new NIOFSDirectory( dir.toPath() );
              final IndexReader reader = DirectoryReader.open( luceneDir ) ) {
            return reader.numDocs();
        } catch ( final IOException e ) {
            LOG.warn( "Could not read Lucene index for documentCount: {}", e.getMessage(), e );
            return 0;
        }
    }

    /**
     * @return the timestamp of the most recent successful index update;
     *         {@link Instant#EPOCH} if no update has been recorded yet
     */
    public Instant lastUpdateInstant() {
        return lastUpdateInstant;
    }

    /**
     * Removes every document from the Lucene index via
     * {@link IndexWriter#deleteAll()} + {@link IndexWriter#commit()}. Does
     * NOT delete the underlying directory — the index stays present and
     * writable, just empty. After a successful clear, {@link #documentCount()}
     * returns {@code 0} and the last-update timestamp is advanced.
     *
     * <p>IO failures are logged at ERROR and rethrown as a runtime exception
     * — matching {@link #doFullLuceneReindex()}'s "unable to create Lucene
     * index" pattern — so callers (the rebuild orchestrator) can record the
     * failure and abort the run.
     */
    public synchronized void clearIndex() {
        if ( luceneDirectory == null ) {
            LOG.warn( "clearIndex called before Lucene directory was initialized — nothing to clear" );
            return;
        }
        final File dir = new File( luceneDirectory );
        if ( !dir.exists() ) {
            return;
        }
        try ( final Directory luceneDir = new NIOFSDirectory( dir.toPath() );
              final IndexWriter writer = getIndexWriter( luceneDir ) ) {
            writer.deleteAll();
            writer.commit();
            lastUpdateInstant = Instant.now();
            LOG.info( "Cleared Lucene index at {}", dir.getAbsolutePath() );
        } catch ( final IOException e ) {
            LOG.error( "Unable to clear Lucene index at {}: {}", dir.getAbsolutePath(), e.getMessage(), e );
            throw new IllegalStateException( "unable to clear Lucene index", e );
        }
    }

    /**
     * Stats returned from a single invocation of {@link #drainUpdateQueue()}.
     *
     * @param totalQueued  total items dequeued (indexed + skipped + failed)
     * @param indexed      items successfully written to the Lucene index
     * @param skipped      items skipped because they are system pages (not a failure)
     * @param failed       items where {@link #updateLuceneIndex(Page, String)} returned
     *                     false for non-skip reasons (IO, unexpected exception)
     */
    record DrainStats( int totalQueued, int indexed, int skipped, int failed ) {}

    /**
     * Drains the pending reindex queue, writing each page to the Lucene index.
     * Extracted from {@link LuceneUpdater#backgroundTask()} so the counter logic
     * can be exercised in isolation — in particular the invariant that
     * system-page skips must NOT be counted as failures.
     *
     * <p>System pages are detected via {@link #isSystemPageExcluded(String)} and
     * skipped before {@link #updateLuceneIndex(Page, String)} is called, so they
     * never contribute to either {@code indexed} or {@code failed}. The
     * defense-in-depth check in {@code updateLuceneIndex} still returns
     * {@code false} for system pages if reached via a direct caller, but on the
     * normal drain path it is unreachable.
     *
     * @return stats describing what the drain did; {@code totalQueued == 0}
     *         means the queue was empty.
     */
    DrainStats drainUpdateQueue() {
        synchronized( updates ) {
            final int totalQueued = updates.size();
            if( totalQueued >= QUEUE_DEPTH_WARN_THRESHOLD ) {
                LOG.warn( "Lucene reindex queue depth {} has reached threshold {} — sustained backpressure; search results may lag",
                          totalQueued, QUEUE_DEPTH_WARN_THRESHOLD );
            }
            if( totalQueued == 0 ) {
                return new DrainStats( 0, 0, 0, 0 );
            }

            int processed = 0;
            int failed = 0;
            int skipped = 0;
            while( !updates.isEmpty() ) {
                final Object[] pair = updates.remove( 0 );
                final Page page = ( Page ) pair[ 0 ];
                final String text = ( String ) pair[ 1 ];
                if( isSystemPageExcluded( page.getName() ) ) {
                    // Defense-in-depth: reindexPage already filters system pages
                    // before enqueueing, but anything that bypassed that (e.g. a
                    // direct updates.add() in a test or future caller) must not
                    // be counted as a failure.
                    LOG.debug( "Drain loop skipping system page '{}'", page.getName() );
                    skipped++;
                } else if( !updateLuceneIndex( page, text ) ) {
                    failed++;
                }
                processed++;
                if( processed % 100 == 0 ) {
                    LOG.info( "Reindex progress: {}/{} pages indexed ({} failed, {} skipped so far)",
                              processed, totalQueued, failed, skipped );
                }
            }
            final int indexed = processed - failed - skipped;
            LOG.info( "Reindex complete: {} pages indexed, {} failed, {} skipped out of {} total",
                      indexed, failed, skipped, totalQueued );
            if ( indexed > 0 ) {
                lastUpdateInstant = Instant.now();
            }
            return new DrainStats( totalQueued, indexed, skipped, failed );
        }
    }

    /**
     * When the reindex queue is at or above this depth, {@link LuceneUpdater#backgroundTask()}
     * logs a WARN so operators notice sustained backpressure (bulk import, storm of edits, etc.).
     */
    static final int QUEUE_DEPTH_WARN_THRESHOLD = 1000;

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
    public List< MoreLikeThisHit > moreLikeThis( final String seedDocName,
                                                   final int maxResults,
                                                   final Set< String > excludeNames )
            throws IOException {
        if ( seedDocName == null || seedDocName.isEmpty() || maxResults <= 0 ) {
            return Collections.emptyList();
        }
        final Set< String > excludes = excludeNames == null
            ? Collections.emptySet() : excludeNames;
        try ( final Directory luceneDir = new NIOFSDirectory( new File( luceneDirectory ).toPath() );
              final IndexReader reader = DirectoryReader.open( luceneDir ) ) {
            final IndexSearcher searcher = new IndexSearcher( reader, searchExecutor );
            // Locate the seed document by id.
            final TopDocs seedHits = searcher.search(
                new TermQuery( new Term( LUCENE_ID, seedDocName ) ), 1 );
            if ( seedHits.scoreDocs.length == 0 ) {
                return Collections.emptyList();
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

            final List< MoreLikeThisHit > out = new ArrayList<>();
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

    /** {@inheritDoc} */
    @Override
    public String getProviderInfo() {
        return "LuceneSearchProvider";
    }

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
                               final int initialDelay, final int indexDelay, final int missingPageCheckInterval ) {
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

            // Sleep initially...
            try {
                Thread.sleep( initialDelay * 1000L );
            } catch( final InterruptedException e ) {
                throw new InternalWikiException( "Interrupted while waiting to start.", e );
            }

            watchdog.enterState( "Full reindex" );
            // Reindex everything (or check for missing pages if index exists)
            provider.doFullLuceneReindex();
            lastMissingPageCheck = System.currentTimeMillis();
            watchdog.exitState();
        }

        @Override
        public void backgroundTask() {
            watchdog.enterState( "Emptying index queue", 60 );

            provider.drainUpdateQueue();

            watchdog.exitState();

            // Periodically check for pages added outside the wiki UI
            if( missingPageCheckInterval > 0 ) {
                final long now = System.currentTimeMillis();
                final long elapsedSeconds = ( now - lastMissingPageCheck ) / 1000L;

                if( elapsedSeconds >= missingPageCheckInterval ) {
                    watchdog.enterState( "Checking for missing pages", 120 );
                    LOG.debug( "Running periodic check for pages missing from Lucene index" );
                    provider.indexMissingPages();
                    lastMissingPageCheck = now;
                    watchdog.exitState();
                }
            }
        }

    }

    // FIXME: This class is dumb; needs to have a better implementation
    private static class SearchResultImpl implements SearchResult {

        private final Page page;
        private final int score;
        private final String[] contexts;

        public SearchResultImpl( final Page page, final int score, final String[] contexts ) {
            this.page = page;
            this.score = score;
            this.contexts = contexts != null ? contexts.clone() : null;
        }

        @Override
        public Page getPage() {
            return this.page;
        }

        /* (non-Javadoc)
         * @see com.wikantik.SearchResult#getScore()
         */
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
