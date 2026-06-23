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
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
// NIOFSDirectory and MMapDirectory are selected at runtime via LuceneDirectoryFactory.
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.util.FileUtil;
import com.wikantik.util.TextUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link LuceneIndexer}.
 *
 * <p>Write side of the LuceneSearchProvider decomposition. Owns the reindex
 * queue ({@link #updates}), per-page document construction, attachment content
 * extraction, and index clearing. All index directory I/O reads the path from
 * a {@link Supplier}{@code <String>} so the enclosing
 * {@code LuceneSearchProvider} facade's {@code luceneDirectory} field can be
 * mutated (e.g. by test fixtures via reflection) without invalidating this
 * helper's view of the path.</p>
 *
 * <p>Synchronisation for all mutating operations is on {@code this} (the
 * indexer's own monitor), preserving the semantics that were previously on
 * {@code LuceneSearchProvider.this}. The reentrant call from
 * {@link #updateLuceneIndex} into {@link #pageRemoved} is safe because both
 * methods are {@code synchronized} on the same instance.</p>
 */
public class DefaultLuceneIndexer implements LuceneIndexer {

    private static final Logger LOG = LogManager.getLogger( DefaultLuceneIndexer.class );

    private static final String PUNCTUATION_TO_SPACES =
            StringUtils.repeat( " ", TextUtil.PUNCTUATION_CHARS_ALLOWED.length() );

    public static final int QUEUE_DEPTH_WARN_THRESHOLD = 1000;

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

    private final Supplier<String> directorySupplier;
    private final LuceneIndexLifecycle lifecycle;
    private final PageManager pageManager;
    private final AttachmentManager attachmentManager;
    private final SystemPageRegistry systemPageRegistry;

    /**
     * When {@code true}, Lucene {@link org.apache.lucene.store.Directory} instances
     * are opened as {@link org.apache.lucene.store.MMapDirectory}; when {@code false}
     * (default), as {@link org.apache.lucene.store.NIOFSDirectory}. Threaded down
     * from {@link com.wikantik.search.LuceneSearchProvider} where the property
     * {@code wikantik.search.lucene.directory.kind} is read once.
     */
    private final boolean useMMap;

    /**
     * Pending reindex queue — (Page, String) pairs awaiting drain.
     * Injected at construction time so the facade and indexer share the same list
     * instance, allowing test fixtures that access {@code LuceneSearchProvider.updates}
     * via reflection to see items enqueued by {@link #reindexPage}.
     */
    final List<Object[]> updates;

    /**
     * Constructs a {@code DefaultLuceneIndexer} with a caller-supplied queue.
     *
     * @param directorySupplier  lambda that returns the current Lucene index directory path;
     *                           called on every I/O operation so reflective mutation of the
     *                           facade's {@code luceneDirectory} field is automatically visible
     * @param lifecycle          the shared lifecycle (owns analyzer + stats)
     * @param pageManager        used to read page text and enumerate all pages
     * @param attachmentManager  used to enumerate and stream attachments
     * @param systemPageRegistry used to detect system pages; may be {@code null}
     * @param updates            the shared reindex queue — must be thread-safe
     */
    public DefaultLuceneIndexer( final Supplier<String> directorySupplier,
                                  final LuceneIndexLifecycle lifecycle,
                                  final PageManager pageManager,
                                  final AttachmentManager attachmentManager,
                                  final SystemPageRegistry systemPageRegistry,
                                  final List<Object[]> updates,
                                  final boolean useMMap ) {
        this.directorySupplier = directorySupplier;
        this.lifecycle = lifecycle;
        this.pageManager = pageManager;
        this.attachmentManager = attachmentManager;
        this.systemPageRegistry = systemPageRegistry;
        this.updates = updates;
        this.useMMap = useMMap;
    }

    /**
     * Six-argument convenience constructor for callers that don't yet thread the
     * MMap flag. Defaults to {@code useMMap=false} (NIO directory) — i.e. the
     * pre-flag behaviour, preserved for tests and any external wiring.
     */
    public DefaultLuceneIndexer( final Supplier<String> directorySupplier,
                                  final LuceneIndexLifecycle lifecycle,
                                  final PageManager pageManager,
                                  final AttachmentManager attachmentManager,
                                  final SystemPageRegistry systemPageRegistry,
                                  final List<Object[]> updates ) {
        this( directorySupplier, lifecycle, pageManager, attachmentManager,
            systemPageRegistry, updates, /*useMMap*/ false );
    }

    private String dir() {
        return directorySupplier.get();
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
        try ( Directory luceneDir = LuceneDirectoryFactory.open( new File( dir() ).toPath(), useMMap );
              IndexWriter writer = lifecycle.getIndexWriter( luceneDir ) ) {
            final org.apache.lucene.search.Query query =
                    new TermQuery( new Term( LUCENE_ID, page.getName() ) );
            writer.deleteDocuments( query );
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
        try ( Directory luceneDir = LuceneDirectoryFactory.open( dirFile.toPath(), useMMap );
              IndexWriter writer = lifecycle.getIndexWriter( luceneDir ) ) {
            writer.deleteAll();
            writer.commit();
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
        final File dirFile = new File( dir() == null ? "" : dir() );
        final String[] dirFiles = dirFile.list();
        if ( !dirFile.exists() || dirFiles == null || dirFiles.length == 0 ) {
            return 0;
        }
        try ( Directory luceneDir = LuceneDirectoryFactory.open( dirFile.toPath(), useMMap );
              IndexReader reader = DirectoryReader.open( luceneDir ) ) {
            return reader.numDocs();
        } catch ( final IOException e ) {
            LOG.warn( "Could not read Lucene index for documentCount: {}", e.getMessage(), e );
            return 0;
        }
    }

    @Override
    public int getReindexQueueDepth() {
        return updates.size();
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

                final Directory luceneDir = LuceneDirectoryFactory.open( dirFile.toPath(), useMMap );
                try ( IndexWriter writer = lifecycle.getIndexWriter( luceneDir ) ) {
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

    @Override
    public int indexMissingPages() {
        final File dirFile = new File( dir() );
        final String[] dirFiles = dirFile.list();
        if ( !dirFile.exists() || dirFiles == null || dirFiles.length == 0 ) {
            LOG.debug( "No Lucene index exists, skipping missing page check" );
            return 0;
        }

        int pagesIndexed = 0;
        try {
            final Set<String> indexedPages = getIndexedPageNames();
            final Collection<Page> allPages = pageManager.getAllPages();

            final List<Page> missingPages = allPages.stream()
                    .filter( page -> !indexedPages.contains( page.getName() ) )
                    .filter( page -> {
                        if ( isSystemPageExcluded( page.getName() ) ) {
                            LOG.debug( "Skipping system page '{}' during missing-page sweep", page.getName() );
                            return false;
                        }
                        return true;
                    } )
                    .toList();

            if ( !missingPages.isEmpty() ) {
                LOG.info( "Found {} pages missing from Lucene index, indexing...", missingPages.size() );
                try ( Directory luceneDir = LuceneDirectoryFactory.open( dirFile.toPath(), useMMap );
                      IndexWriter writer = lifecycle.getIndexWriter( luceneDir ) ) {
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
                }
                LOG.info( "Indexed {} missing pages", pagesIndexed );
            }

            final Collection<Attachment> allAttachments = attachmentManager.getAllAttachments();
            final List<Attachment> missingAttachments = allAttachments.stream()
                    .filter( att -> !indexedPages.contains( att.getName() ) )
                    .toList();

            if ( !missingAttachments.isEmpty() ) {
                LOG.info( "Found {} attachments missing from Lucene index, indexing...",
                          missingAttachments.size() );
                try ( Directory luceneDir = LuceneDirectoryFactory.open( dirFile.toPath(), useMMap );
                      IndexWriter writer = lifecycle.getIndexWriter( luceneDir ) ) {
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
                }
            }
        } catch ( final ProviderException e ) {
            // LOG.error justified: page provider failure during missing-page sweep; index may be incomplete.
            LOG.error( "Error reading pages while checking for missing Lucene entries", e );
        } catch ( final IOException e ) {
            // LOG.error justified: Lucene write failure during missing-page sweep; index may be incomplete.
            LOG.error( "Error writing to Lucene index while indexing missing pages", e );
        }

        return pagesIndexed;
    }

    @Override
    public Set<String> getIndexedPageNames() {
        final Set<String> indexedPages = new HashSet<>();
        final File dirFile = new File( dir() );
        final String[] dirFiles = dirFile.list();
        if ( !dirFile.exists() || dirFiles == null || dirFiles.length == 0 ) {
            return indexedPages;
        }
        try ( Directory luceneDir = LuceneDirectoryFactory.open( dirFile.toPath(), useMMap );
              IndexReader reader = DirectoryReader.open( luceneDir ) ) {
            final StoredFields storedFields = reader.storedFields();
            for ( int i = 0; i < reader.maxDoc(); i++ ) {
                final Document doc = storedFields.document( i );
                final String pageName = doc.get( LUCENE_ID );
                if ( pageName != null ) {
                    indexedPages.add( pageName );
                }
            }
            LOG.debug( "Found {} pages in Lucene index", indexedPages.size() );
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
        pageRemoved( page );
        try ( Directory luceneDir = LuceneDirectoryFactory.open( new File( dir() ).toPath(), useMMap );
              IndexWriter writer = lifecycle.getIndexWriter( luceneDir ) ) {
            luceneIndexPage( page, text, writer );
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

        final String indexedText = text.replace( "__", " " ); // be nice to Language Analyzers - cfr. JSPWIKI-893

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

        if ( page.getAuthor() != null ) {
            field = new Field( LUCENE_AUTHOR, page.getAuthor(), TextField.TYPE_STORED );
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

        synchronized ( writer ) {
            writer.addDocument( doc );
        }
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

    @Override
    public DrainStats drainUpdateQueue() {
        synchronized ( updates ) {
            final int totalQueued = updates.size();
            if ( totalQueued >= QUEUE_DEPTH_WARN_THRESHOLD ) {
                LOG.warn( "Lucene reindex queue depth {} has reached threshold {} — sustained backpressure; search results may lag",
                          totalQueued, QUEUE_DEPTH_WARN_THRESHOLD );
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
                if ( isSystemPageExcluded( page.getName() ) ) {
                    LOG.debug( "Drain loop skipping system page '{}'", page.getName() );
                    skipped++;
                } else if ( !updateLuceneIndex( page, text ) ) {
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
                lifecycle.touchLastUpdateInstant();
            }
            return new DrainStats( totalQueued, indexed, skipped, failed );
        }
    }
}
