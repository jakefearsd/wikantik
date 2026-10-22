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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.auth.acl.AclDirectiveText;
import com.wikantik.util.FileUtil;
import com.wikantik.util.TextUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link LuceneIndexer}.
 *
 * <p>Write side of the LuceneSearchProvider decomposition. Owns the reindex
 * queue ({@link #updates}), per-page document construction, attachment content
 * extraction, and index clearing. All index I/O goes through a shared
 * {@link LuceneIndexStore}: one long-lived writer, each page written with
 * {@code updateDocument} (an atomic replace), one commit per drained batch, and
 * the searcher's reader refreshed by every commit.</p>
 *
 * <p>Synchronisation for all mutating operations is on {@code this} (the
 * indexer's own monitor), preserving the semantics that were previously on
 * {@code LuceneSearchProvider.this}.</p>
 */
public class DefaultLuceneIndexer implements LuceneIndexer {

    private static final Logger LOG = LogManager.getLogger( DefaultLuceneIndexer.class );

    private static final String PUNCTUATION_TO_SPACES =
            StringUtils.repeat( " ", TextUtil.PUNCTUATION_CHARS_ALLOWED.length() );

    public static final int QUEUE_DEPTH_WARN_THRESHOLD = 1000;

    /** Drains a page may fail in before it is dropped from the queue (a failure of the index itself never counts). */
    static final int MAX_INDEX_ATTEMPTS = 3;

    // Lucene field-name constants (public so the facade in a parent package can reference them)
    public static final String LUCENE_ID            = "id";
    public static final String LUCENE_PAGE_CONTENTS = "contents";
    public static final String LUCENE_AUTHOR        = "author";
    public static final String LUCENE_ATTACHMENTS   = "attachment";
    public static final String LUCENE_PAGE_NAME     = "name";
    public static final String LUCENE_PAGE_KEYWORDS = "keywords";
    public static final String LUCENE_PAGE_TAGS     = "tags";
    public static final String LUCENE_PAGE_CLUSTER  = "cluster";
    public static final String LUCENE_PAGE_SUMMARY  = "summary";

    /** Attachment file suffixes eligible for full-text indexing. */
    public static final List<String> SEARCHABLE_FILE_SUFFIXES = List.of(
            ".txt", ".ini", ".xml", ".html", "htm", ".mm", ".htm",
            ".xhtml", ".java", ".c", ".cpp", ".php", ".asm", ".sh",
            ".properties", ".kml", ".gpx", ".loc", ".md", ".xml" );

    private final LuceneIndexStore store;
    private final LuceneIndexLifecycle lifecycle;
    private final PageManager pageManager;
    private final AttachmentManager attachmentManager;
    private final SystemPageRegistry systemPageRegistry;

    /**
     * Where the identity service comes from. A supplier because the Auth subsystem is built after the search
     * manager initialises; {@code () -> null} until {@link #setPublicIdentitySource} is called.
     */
    private volatile Supplier< PublicIdentity > publicIdentitySource = () -> null;

    /**
     * Pending reindex queue — (Page, String) pairs awaiting drain.
     * Injected at construction time so the facade and indexer share the same list
     * instance, allowing test fixtures that access {@code LuceneSearchProvider.updates}
     * via reflection to see items enqueued by {@link #reindexPage}.
     */
    final List<Object[]> updates;

    /** Pages taken off {@link #updates} by the running drain and not yet committed. Guarded by {@link #updates}. */
    private int inFlight;

    /** Failed drains per page name, for the {@link #MAX_INDEX_ATTEMPTS} bound. Guarded by {@code this}. */
    private final Map< String, Integer > failedAttempts = new HashMap<>();

    /**
     * Constructs a {@code DefaultLuceneIndexer} over a shared index store.
     *
     * @param store              the index store, shared with the searcher so commits refresh its reader
     * @param lifecycle          the shared lifecycle (owns analyzer + stats)
     * @param pageManager        used to read page text and enumerate all pages
     * @param attachmentManager  used to enumerate and stream attachments
     * @param systemPageRegistry used to detect system pages; may be {@code null}
     * @param updates            the shared reindex queue — must be thread-safe
     */
    public DefaultLuceneIndexer( final LuceneIndexStore store,
                                  final LuceneIndexLifecycle lifecycle,
                                  final PageManager pageManager,
                                  final AttachmentManager attachmentManager,
                                  final SystemPageRegistry systemPageRegistry,
                                  final List<Object[]> updates ) {
        this.store = store;
        this.lifecycle = lifecycle;
        this.pageManager = pageManager;
        this.attachmentManager = attachmentManager;
        this.systemPageRegistry = systemPageRegistry;
        this.updates = updates;
    }

    /**
     * Convenience constructor for a stand-alone indexer with its own NIO-backed {@link LuceneIndexStore}.
     * Nothing else sees its commits, so a searcher must not be paired with it.
     */
    public DefaultLuceneIndexer( final Supplier<String> directorySupplier,
                                  final LuceneIndexLifecycle lifecycle,
                                  final PageManager pageManager,
                                  final AttachmentManager attachmentManager,
                                  final SystemPageRegistry systemPageRegistry,
                                  final List<Object[]> updates ) {
        this( new LuceneIndexStore( directorySupplier, lifecycle, false ), lifecycle, pageManager,
            attachmentManager, systemPageRegistry, updates );
    }

    /**
     * Supplies the identity service used to index each author's <em>display name</em>. Set once by
     * {@code LuceneSearchProvider}; never needed by the read path.
     */
    public void setPublicIdentitySource( final Supplier< PublicIdentity > source ) {
        this.publicIdentitySource = source == null ? () -> null : source;
    }

    /**
     * The author value to index: the display name for a stored login (legacy names unchanged), or {@code null}
     * — leaving the field out — when the identity service is not available yet, so a login is never written to
     * the index (searching for an email would otherwise reveal who edited what).
     */
    private String indexedAuthor( final Page page ) {
        final String stored = page.getAuthor();
        if ( stored == null ) {
            return null;
        }
        final PublicIdentity identity = publicIdentitySource.get();
        if ( identity == null ) {
            LOG.debug( "No identity service yet; author of '{}' is left out of the search index", page.getName() );
            return null;
        }
        return identity.displayName( stored );
    }

    private String dir() {
        return store.path();
    }

    /** {@code true} when the index directory is absent or empty, i.e. there is nothing to read yet. */
    private boolean indexDirectoryEmpty() {
        final String path = dir();
        final String[] dirFiles = path == null ? null : new File( path ).list();
        return dirFiles == null || dirFiles.length == 0;
    }

    // -------------------------------------------------------------------------
    // LuceneIndexer interface
    // -------------------------------------------------------------------------

    @Override
    public void reindexPage( final Page page ) {
        if ( page != null ) {
            if ( isSystemPageExcluded( page.getName() ) ) {
                LOG.debug( "Skipping system page '{}' — system pages are not indexed", page.getName() );
                return;
            }
            final String text;
            if ( page instanceof Attachment att ) {
                text = getAttachmentContent( att );
            } else {
                text = pageManager.getPureText( page );
            }
            if ( text != null ) {
                final Object[] pair = new Object[ 2 ];
                pair[ 0 ] = page;
                pair[ 1 ] = text;
                updates.add( pair );
                LOG.debug( "Scheduling page {} for index update", page.getName() );
            }
        }
    }

    @Override
    public synchronized void pageRemoved( final Page page ) {
        try {
            store.writer().deleteDocuments( new Term( LUCENE_ID, page.getName() ) );
            store.commit();
        } catch ( final Exception e ) {
            // LOG.error justified: Lucene I/O failure removing a page from the index; search results may be stale until corrected.
            LOG.error( "Unable to remove page '{}' from Lucene index", page.getName(), e );
        }
    }

    @Override
    public synchronized void clearIndex() {
        final String luceneDirectory = dir();
        if ( luceneDirectory == null ) {
            LOG.warn( "clearIndex called before Lucene directory was initialized — nothing to clear" );
            return;
        }
        final File dirFile = new File( luceneDirectory );
        if ( !dirFile.exists() ) {
            return;
        }
        try {
            store.writer().deleteAll();
            store.commit();
            lifecycle.touchLastUpdateInstant();
            LOG.info( "Cleared Lucene index at {}", dirFile.getAbsolutePath() );
        } catch ( final IOException e ) {
            // LOG.error justified: Lucene I/O failure on clearIndex; index may be in inconsistent state; operator action required.
            LOG.error( "Unable to clear Lucene index at {}: {}", dirFile.getAbsolutePath(), e.getMessage(), e );
            throw new IllegalStateException( "unable to clear Lucene index", e );
        }
    }

    @Override
    public int documentCount() {
        if ( indexDirectoryEmpty() ) {
            return 0;
        }
        try ( LuceneIndexStore.ReaderLease lease = store.acquire() ) {
            return lease.reader().numDocs();
        } catch ( final IOException e ) {
            LOG.warn( "Could not read Lucene index for documentCount: {}", e.getMessage(), e );
            return 0;
        }
    }

    /** Counts queued pages plus those the running drain has taken but not yet committed. */
    @Override
    public int getReindexQueueDepth() {
        synchronized ( updates ) {
            return updates.size() + inFlight;
        }
    }

    @Override
    public void doFullLuceneReindex() throws IOException {
        final File dirFile = new File( dir() );
        if ( !dirFile.exists() && !dirFile.mkdirs() ) {
            LOG.warn( "Failed to create Lucene directory: {}", dirFile.getAbsolutePath() );
        }
        final String[] filelist = dirFile.list();
        if ( filelist == null ) {
            throw new IOException( "Invalid Lucene directory: cannot produce listing: "
                                   + dirFile.getAbsolutePath() );
        }

        try {
            if ( filelist.length == 0 ) {
                final Date start = new Date();
                LOG.info( "Starting Lucene reindexing, this can take a couple of minutes..." );

                indexAllPagesAndAttachments( store.writer() );
                store.commit();

                final Date end = new Date();
                LOG.info( "Full Lucene index finished in {} milliseconds.", end.getTime() - start.getTime() );
                lifecycle.touchLastUpdateInstant();
            } else {
                LOG.info( "Lucene index exists, checking for missing pages..." );
                indexMissingPages();
            }
        } catch ( final IOException e ) {
            // LOG.error justified: Lucene index directory I/O failure at startup; search will be unavailable until resolved.
            LOG.error( "Problem while creating Lucene index - not using Lucene.", e );
        } catch ( final ProviderException e ) {
            // LOG.error justified: page provider unavailable during initial reindex; wiki cannot start cleanly.
            LOG.error( "Problem reading pages while creating Lucene index (JSPWiki won't start.)", e );
            throw new IllegalArgumentException( "unable to create Lucene index", e );
        } catch ( final Exception e ) {
            // LOG.error justified: unexpected failure starting Lucene; search will be disabled.
            LOG.error( "Unable to start lucene", e );
        }
    }

    /**
     *  Indexes every page and attachment from scratch into {@code writer} (the empty-directory
     *  path of a full reindex). Split out of {@link #doFullLuceneReindex()} to keep that
     *  method's complexity in check.
     */
    private void indexAllPagesAndAttachments( final IndexWriter writer ) throws ProviderException {
        long pagesIndexed = 0L;
        long systemPagesSkipped = 0L;
        final Collection<Page> allPages = pageManager.getAllPages();
        for ( final Page page : allPages ) {
            if ( isSystemPageExcluded( page.getName() ) ) {
                systemPagesSkipped++;
                continue;
            }
            try {
                final String text = pageManager.getPageText(
                        page.getName(), WikiProvider.LATEST_VERSION );
                luceneIndexPage( page, text, writer );
                pagesIndexed++;
            } catch ( final IOException e ) {
                LOG.warn( "Unable to index page {}, continuing to next ", page.getName(), e );
            }
        }
        LOG.info( "Indexed {} pages ({} system pages skipped)", pagesIndexed, systemPagesSkipped );

        long attachmentsIndexed = 0L;
        final Collection<Attachment> allAttachments = attachmentManager.getAllAttachments();
        for ( final Attachment att : allAttachments ) {
            try {
                final String text = getAttachmentContent( att.getName(), WikiProvider.LATEST_VERSION );
                luceneIndexPage( att, text, writer );
                attachmentsIndexed++;
            } catch ( final IOException e ) {
                LOG.warn( "Unable to index attachment {}, continuing to next", att.getName(), e );
            }
        }
        LOG.info( "Indexed {} attachments", attachmentsIndexed );
    }

    @Override
    public int indexMissingPages() {
        if ( indexDirectoryEmpty() ) {
            LOG.debug( "No Lucene index exists, skipping missing page check" );
            return 0;
        }

        int pagesIndexed = 0;
        try {
            final Set<String> indexedPages = getIndexedPageNames();
            final Collection<Page> allPages = pageManager.getAllPages();
            final List<Page> missingPages = computeMissingPages( allPages, indexedPages );

            logMissingPageSweepResult( allPages, indexedPages, missingPages );

            int missingPagesWritten = 0;
            if ( !missingPages.isEmpty() ) {
                LOG.info( "Found {} pages missing from Lucene index, indexing...", missingPages.size() );
                missingPagesWritten = indexMissingPagesToDisk( missingPages );
            }

            final Collection<Attachment> allAttachments = attachmentManager.getAllAttachments();
            final List<Attachment> missingAttachments = allAttachments.stream()
                    .filter( att -> !indexedPages.contains( att.getName() ) )
                    .toList();

            int attachmentsWritten = 0;
            if ( !missingAttachments.isEmpty() ) {
                LOG.info( "Found {} attachments missing from Lucene index, indexing...",
                          missingAttachments.size() );
                attachmentsWritten = indexMissingAttachmentsToDisk( missingAttachments );
            }
            // One commit for the whole sweep — pages and attachments alike.
            if ( missingPagesWritten + attachmentsWritten > 0 ) {
                store.commit();
            }
            pagesIndexed = missingPagesWritten;
        } catch ( final ProviderException e ) {
            // LOG.error justified: page provider failure during missing-page sweep; index may be incomplete.
            LOG.error( "Error reading pages while checking for missing Lucene entries", e );
        } catch ( final IOException e ) {
            // LOG.error justified: Lucene write failure during missing-page sweep; index may be incomplete.
            LOG.error( "Error writing to Lucene index while indexing missing pages", e );
        }

        return pagesIndexed;
    }

    /** Filters {@code allPages} down to those absent from {@code indexedPages}, excluding system pages. */
    private List<Page> computeMissingPages( final Collection<Page> allPages, final Set<String> indexedPages ) {
        return allPages.stream()
                .filter( page -> !indexedPages.contains( page.getName() ) )
                .filter( page -> {
                    if ( isSystemPageExcluded( page.getName() ) ) {
                        LOG.debug( "Skipping system page '{}' during missing-page sweep", page.getName() );
                        return false;
                    }
                    return true;
                } )
                .toList();
    }

    /**
     *  Logs the outcome of a missing-page sweep. Always reports the comparison, even
     *  (especially) when it found nothing. Logging only in the "found something" case made
     *  "swept and everything was present" and "compared an empty page list and therefore
     *  could not find anything" produce identical output: silence. A page that is missing
     *  from the index AND absent from the enumeration the sweep compares against is invisible
     *  forever — it is never re-indexed, and the boot log gives an operator nothing to go on.
     *  Observed in production as a 33 KB page that served fine at /wiki/ while a term unique
     *  to it returned zero BM25 matches.
     */
    private void logMissingPageSweepResult( final Collection<Page> allPages, final Set<String> indexedPages, final List<Page> missingPages ) {
        if ( allPages.isEmpty() && !indexedPages.isEmpty() ) {
            LOG.warn( "Lucene missing-page sweep compared an EMPTY page list against {} indexed "
                    + "documents — the sweep proved nothing and no page can have been found "
                    + "missing. The page provider likely had not enumerated yet.",
                indexedPages.size() );
        } else {
            // The document count covers attachments as well as pages, so it is legitimately
            // larger than the page count — it is here to be compared against ITSELF across
            // restarts, not against the page count.
            LOG.info( "Lucene missing-page sweep: {} pages enumerated, {} documents in index "
                    + "(pages + attachments), {} pages missing",
                allPages.size(), indexedPages.size(), missingPages.size() );
        }
    }

    /** Writes {@code missingPages} through the shared writer (uncommitted), returning the count written. */
    private int indexMissingPagesToDisk( final List<Page> missingPages ) throws IOException, ProviderException {
        final IndexWriter writer = store.writer();
        int pagesIndexed = 0;
        for ( final Page page : missingPages ) {
            try {
                final String text = pageManager.getPageText(
                        page.getName(), WikiProvider.LATEST_VERSION );
                luceneIndexPage( page, text, writer );
                pagesIndexed++;
                LOG.debug( "Indexed missing page: {}", page.getName() );
            } catch ( final IOException e ) {
                LOG.warn( "Unable to index missing page {}", page.getName(), e );
            }
        }
        LOG.info( "Indexed {} missing pages", pagesIndexed );
        return pagesIndexed;
    }

    /** Writes {@code missingAttachments} through the shared writer (uncommitted), returning the count written. */
    private int indexMissingAttachmentsToDisk( final List<Attachment> missingAttachments ) throws IOException {
        final IndexWriter writer = store.writer();
        int attachmentsIndexed = 0;
        for ( final Attachment att : missingAttachments ) {
            try {
                final String text = getAttachmentContent(
                        att.getName(), WikiProvider.LATEST_VERSION );
                luceneIndexPage( att, text, writer );
                attachmentsIndexed++;
                LOG.debug( "Indexed missing attachment: {}", att.getName() );
            } catch ( final IOException e ) {
                LOG.warn( "Unable to index missing attachment {}", att.getName(), e );
            }
        }
        LOG.info( "Indexed {} missing attachments", attachmentsIndexed );
        return attachmentsIndexed;
    }

    @Override
    public Set<String> getIndexedPageNames() {
        final Set<String> indexedPages = new HashSet<>();
        if ( indexDirectoryEmpty() ) {
            return indexedPages;
        }
        try ( LuceneIndexStore.ReaderLease lease = store.acquire() ) {
            // Walk each leaf and honour liveDocs. A deleted document keeps its stored
            // fields on disk until a merge reclaims the segment, so a reader.maxDoc()
            // scan still reads back the deleted page's id and reports it as indexed.
            // The missing-page sweep would then never re-index a page the searcher
            // (which honours liveDocs) can no longer match — it stays invisible to
            // search indefinitely while the sweep logs "0 pages missing".
            for ( final LeafReaderContext leafContext : lease.reader().leaves() ) {
                final LeafReader leaf = leafContext.reader();
                final Bits liveDocs = leaf.getLiveDocs();
                final StoredFields storedFields = leaf.storedFields();
                for ( int i = 0; i < leaf.maxDoc(); i++ ) {
                    if ( liveDocs != null && !liveDocs.get( i ) ) {
                        continue;
                    }
                    final String pageName = storedFields.document( i ).get( LUCENE_ID );
                    if ( pageName != null ) {
                        indexedPages.add( pageName );
                    }
                }
            }
            LOG.debug( "Found {} live pages in Lucene index", indexedPages.size() );
        } catch ( final IOException e ) {
            LOG.warn( "Could not read Lucene index to get indexed page names", e );
        }
        return indexedPages;
    }

    @Override
    public synchronized boolean updateLuceneIndex( final Page page, final String text ) {
        if ( isSystemPageExcluded( page.getName() ) ) {
            LOG.debug( "Skipping Lucene index update for system page '{}'", page.getName() );
            return false;
        }
        LOG.debug( "Updating Lucene index for page '{}'...", page.getName() );
        try {
            luceneIndexPage( page, text, store.writer() );
            store.commit();
        } catch ( final IOException e ) {
            // LOG.error justified: Lucene I/O failure indexing a page; search results may be stale.
            LOG.error( "Unable to update page '{}' from Lucene index", page.getName(), e );
            return false;
        } catch ( final Exception e ) {
            // LOG.error justified: unexpected Lucene failure; indicates misconfiguration or corruption.
            LOG.error( "Unexpected Lucene exception - please check configuration!", e );
            return false;
        }
        LOG.debug( "Done updating Lucene index for page '{}'.", page.getName() );
        lifecycle.touchLastUpdateInstant();
        return true;
    }

    @Override
    public Document luceneIndexPage( final Page page, final String text, final IndexWriter writer )
            throws IOException {
        LOG.debug( "Indexing {}...", page.getName() );
        final Document doc = new Document();
        if ( text == null ) {
            return doc;
        }

        // Access-rule directives name people by login and are not searchable content; indexing the text
        // without them keeps them out of hits and highlighted snippets. The frontmatter parse below still
        // reads the original text.
        final String indexedText = AclDirectiveText.strip( text )
                .replace( "__", " " ); // be nice to Language Analyzers - cfr. JSPWIKI-893

        Field field = new Field( LUCENE_ID, page.getName(), StringField.TYPE_STORED );
        doc.add( field );
        // Columnar copy of the id for the search read path: DefaultLuceneSearcher
        // reads it back via DocValues, which needs no stored-fields block
        // decompression — the per-hit cost that dominated search CPU + allocation
        // when highlighting is off (the default). The stored field above is kept
        // for the highlighting path and as a fallback for pre-DocValues segments.
        doc.add( new BinaryDocValuesField( LUCENE_ID, new BytesRef( page.getName() ) ) );

        field = new Field( LUCENE_PAGE_CONTENTS, indexedText, TextField.TYPE_STORED );
        doc.add( field );

        final String unTokenizedTitle = StringUtils.replaceChars(
                page.getName(), TextUtil.PUNCTUATION_CHARS_ALLOWED, PUNCTUATION_TO_SPACES );
        field = new Field( LUCENE_PAGE_NAME,
                TextUtil.beautifyString( page.getName() ) + " " + unTokenizedTitle,
                TextField.TYPE_STORED );
        doc.add( field );

        final String author = indexedAuthor( page );
        if ( author != null ) {
            field = new Field( LUCENE_AUTHOR, author, TextField.TYPE_STORED );
            doc.add( field );
        }

        try {
            final List<Attachment> attachments = attachmentManager.listAttachments( page );
            final String attachmentNames = attachments.stream()
                    .map( att -> att.getName() + ";" )
                    .collect( Collectors.joining() );
            field = new Field( LUCENE_ATTACHMENTS, attachmentNames, TextField.TYPE_STORED );
            doc.add( field );
        } catch ( final ProviderException e ) {
            // LOG.error justified: attachment list failure during indexing; document may be missing attachment field.
            LOG.error( "Failed to get attachments for page", e );
        }

        if ( page.getAttribute( "keywords" ) != null ) {
            field = new Field( LUCENE_PAGE_KEYWORDS,
                    page.getAttribute( "keywords" ).toString(), TextField.TYPE_STORED );
            doc.add( field );
        }

        try {
            final com.wikantik.api.frontmatter.ParsedPage parsed =
                    com.wikantik.api.frontmatter.FrontmatterParser.parse( text );
            final java.util.Map<String, Object> metadata = parsed.metadata();

            final Object tags = metadata.get( "tags" );
            if ( tags instanceof java.util.List<?> tagList && !tagList.isEmpty() ) {
                final String tagString = tagList.stream()
                        .map( Object::toString )
                        .collect( Collectors.joining( " " ) );
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

        // Replaces any existing document for the page in one atomic operation, so a failure can never
        // leave the page deleted but not re-added. No synchronized( writer ): IndexWriter is thread-safe,
        // and Lucene warns that locking on it can deadlock against its own merge threads.
        writer.updateDocument( new Term( LUCENE_ID, page.getName() ), doc );
        return doc;
    }

    @Override
    public String getAttachmentContent( final String attachmentName, final int version ) {
        try {
            final Attachment att = attachmentManager.getAttachmentInfo( attachmentName, version );
            if ( att != null ) {
                return getAttachmentContent( att );
            }
        } catch ( final ProviderException e ) {
            // LOG.error justified: attachment info unavailable; content will be excluded from search index.
            LOG.error( "Attachment cannot be loaded", e );
        }
        return null;
    }

    @Override
    public String getAttachmentContent( final Attachment att ) {
        final String filename = att.getFileName();
        final boolean searchSuffix = SEARCHABLE_FILE_SUFFIXES.stream().anyMatch( filename::endsWith );
        if ( searchSuffix ) {
            try ( InputStream attStream = attachmentManager.getAttachmentStream( att );
                  StringWriter sout = new StringWriter() ) {
                FileUtil.copyContents( new InputStreamReader( attStream, StandardCharsets.UTF_8 ), sout );
                return filename + " " + sout;
            } catch ( final ProviderException | IOException e ) {
                // LOG.error justified: attachment stream failure; only filename will be indexed for this attachment.
                LOG.error( "Attachment cannot be loaded", e );
            }
        }
        return filename;
    }

    @Override
    public boolean isSystemPageExcluded( final String pageName ) {
        return systemPageRegistry != null
                && pageName != null
                && systemPageRegistry.isSystemPage( pageName );
    }

    /**
     * Takes the whole queue as one batch, writes each page through the shared writer and commits once.
     * Pages that failed stay queued for the next drain: a page that failed on its own for up to
     * {@link #MAX_INDEX_ATTEMPTS} drains, and every uncommitted page when the index itself failed.
     */
    @Override
    public DrainStats drainUpdateQueue() {
        final List<Object[]> batch = takeQueuedBatch();
        if ( batch.size() >= QUEUE_DEPTH_WARN_THRESHOLD ) {
            LOG.warn( "Lucene reindex queue depth {} has reached threshold {} — sustained backpressure; search results may lag",
                      batch.size(), QUEUE_DEPTH_WARN_THRESHOLD );
        }
        if ( batch.isEmpty() ) {
            return new DrainStats( 0, 0, 0, 0 );
        }
        final List<Object[]> retry = new ArrayList<>();
        try {
            return indexBatch( batch, retry );
        } finally {
            requeue( retry, batch.size() );
        }
    }

    /** Moves the whole queue into a local batch at once; the pages count as in flight until {@link #requeue}. */
    private List<Object[]> takeQueuedBatch() {
        synchronized ( updates ) {
            final List<Object[]> batch = new ArrayList<>( updates );
            updates.clear();
            inFlight += batch.size();
            return batch;
        }
    }

    private synchronized DrainStats indexBatch( final List<Object[]> batch, final List<Object[]> retry ) {
        final List<Object[]> toWrite = new ArrayList<>( batch.size() );
        for ( final Object[] item : batch ) {
            if ( isSystemPageExcluded( pageOf( item ).getName() ) ) {
                LOG.debug( "Drain loop skipping system page '{}'", pageOf( item ).getName() );
            } else {
                toWrite.add( item );
            }
        }
        final int skipped = batch.size() - toWrite.size();
        final int indexed = toWrite.isEmpty() ? 0 : writeAndCommit( toWrite, batch.size(), retry );
        final int failed = toWrite.size() - indexed;
        LOG.info( "Reindex complete: {} pages indexed, {} failed, {} skipped out of {} total",
                  indexed, failed, skipped, batch.size() );
        return new DrainStats( batch.size(), indexed, skipped, failed );
    }

    /** Writes {@code toWrite} and commits once; returns the number committed, adding what to try again to {@code retry}. */
    private int writeAndCommit( final List<Object[]> toWrite, final int totalQueued, final List<Object[]> retry ) {
        final IndexWriter writer;
        try {
            writer = store.writer();
        } catch ( final IOException | RuntimeException e ) {
            LOG.warn( "Cannot open the Lucene index writer; {} pages stay queued: {}", toWrite.size(), e.getMessage(), e );
            retry.addAll( toWrite );
            return 0;
        }
        final List<Object[]> written = new ArrayList<>( toWrite.size() );
        for ( int i = 0; i < toWrite.size(); i++ ) {
            final Object[] item = toWrite.get( i );
            final Exception failure = writeQueuedPage( item, writer );
            if ( failure == null ) {
                written.add( item );
            } else if ( writer.isOpen() ) {
                retryOrDrop( item, failure, retry );
            } else {
                // Lucene closed the writer after a tragic failure; nothing written in this batch will commit.
                LOG.warn( "Lucene index writer closed while indexing '{}'; {} pages stay queued: {}",
                          pageOf( item ).getName(), toWrite.size() - i, failure.getMessage(), failure );
                retry.addAll( toWrite.subList( i, toWrite.size() ) );
                break;
            }
            if ( ( i + 1 ) % 100 == 0 ) {
                LOG.info( "Reindex progress: {}/{} pages written ({} failed so far)", i + 1, totalQueued, i + 1 - written.size() );
            }
        }
        return commitWritten( written, retry );
    }

    /** @return {@code null} when the page was written, else the failure */
    private Exception writeQueuedPage( final Object[] item, final IndexWriter writer ) {
        try {
            luceneIndexPage( pageOf( item ), ( String ) item[ 1 ], writer );
            return null;
        } catch ( final IOException | RuntimeException e ) {
            return e;
        }
    }

    private int commitWritten( final List<Object[]> written, final List<Object[]> retry ) {
        if ( written.isEmpty() ) {
            return 0;
        }
        try {
            store.commit();
        } catch ( final IOException | RuntimeException e ) {
            LOG.warn( "Lucene commit of {} pages failed; they stay queued: {}", written.size(), e.getMessage(), e );
            retry.addAll( written );
            return 0;
        }
        if ( !failedAttempts.isEmpty() ) {
            written.forEach( item -> failedAttempts.remove( pageOf( item ).getName() ) );
        }
        lifecycle.touchLastUpdateInstant();
        return written.size();
    }

    /** Re-queues a page that failed on its own until it has failed {@link #MAX_INDEX_ATTEMPTS} drains. */
    private void retryOrDrop( final Object[] item, final Exception failure, final List<Object[]> retry ) {
        final String name = pageOf( item ).getName();
        final int attempts = failedAttempts.merge( name, 1, Integer::sum );
        if ( attempts < MAX_INDEX_ATTEMPTS ) {
            LOG.warn( "Unable to index page '{}' (attempt {} of {}); it stays queued: {}",
                      name, attempts, MAX_INDEX_ATTEMPTS, failure.getMessage(), failure );
            retry.add( item );
        } else {
            failedAttempts.remove( name );
            // LOG.error justified: the update is abandoned; search keeps the page's previously indexed version until it is saved again.
            LOG.error( "Unable to index page '{}' after {} attempts; dropping the update — search keeps its previously "
                       + "indexed version until the page is saved again", name, attempts, failure );
        }
    }

    /** Puts {@code retry} back on the queue, except pages a newer save has queued again meanwhile, and ends the batch's in-flight count. */
    private void requeue( final List<Object[]> retry, final int batchSize ) {
        synchronized ( updates ) {
            for ( final Object[] item : retry ) {
                final String name = pageOf( item ).getName();
                if ( updates.stream().noneMatch( queued -> name.equals( pageOf( queued ).getName() ) ) ) {
                    updates.add( item );
                }
            }
            inFlight -= batchSize;
        }
    }

    private static Page pageOf( final Object[] item ) {
        return ( Page ) item[ 0 ];
    }
}
