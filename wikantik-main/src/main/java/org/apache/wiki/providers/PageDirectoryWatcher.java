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
package org.apache.wiki.providers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.WikiBackgroundThread;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.exceptions.ProviderException;
import org.apache.wiki.api.providers.PageProvider;
import org.apache.wiki.api.spi.Wiki;
import org.apache.wiki.cache.CachingManager;
import org.apache.wiki.event.WikiEventManager;
import org.apache.wiki.event.WikiPageEvent;
import org.apache.wiki.filters.FilterManager;
import org.apache.wiki.references.ReferenceManager;
import org.apache.wiki.search.SearchManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Background thread that monitors the page directory for external filesystem changes
 * using {@link java.nio.file.WatchService}. When files are created, modified, or deleted
 * outside of JSPWiki's API (e.g., by an external publishing system), this watcher
 * triggers the necessary cache invalidation and event firing to keep JSPWiki in sync.
 *
 * <p>This thread follows the same pattern as {@code LuceneUpdater} in
 * {@link org.apache.wiki.search.LuceneSearchProvider}, extending
 * {@link WikiBackgroundThread} for lifecycle management.
 *
 * <p>The watcher handles:
 * <ul>
 *   <li>File creation: new {@code .md} or {@code .txt} files</li>
 *   <li>File modification: changed content in existing page files</li>
 *   <li>File deletion: removed page files</li>
 * </ul>
 *
 * <p>A self-modification guard prevents double-processing when pages are saved
 * through JSPWiki's own API.
 *
 * @since 2.12.3
 */
class PageDirectoryWatcher extends WikiBackgroundThread {

    private static final Logger LOG = LogManager.getLogger( PageDirectoryWatcher.class );

    /** How long (in ms) to consider a page as "recently saved internally" and skip watcher processing. */
    private static final long INTERNAL_SAVE_GUARD_MILLIS = 5_000L;

    /** How often (in ms) to clean up stale entries from the internal save guard map. */
    private static final long GUARD_CLEANUP_INTERVAL_MILLIS = 30_000L;

    private final AbstractFileProvider fileProvider;
    private final CachingManager cachingManager;
    private final Engine engine;
    private final Path pageDirectoryPath;

    private WatchService watchService;

    /** Pages recently saved through JSPWiki's API, with timestamp. Used to avoid double-processing. */
    private final ConcurrentHashMap<String, Long> recentInternalSaves = new ConcurrentHashMap<>();

    private long lastGuardCleanup = System.currentTimeMillis();

    /**
     * Creates a new PageDirectoryWatcher.
     *
     * @param engine the wiki engine
     * @param sleepInterval polling interval in seconds
     * @param fileProvider the underlying file-based page provider
     * @param cachingManager the cache manager for invalidating caches
     */
    PageDirectoryWatcher( final Engine engine, final int sleepInterval,
                          final AbstractFileProvider fileProvider, final CachingManager cachingManager ) {
        super( engine, sleepInterval );
        this.engine = engine;
        this.fileProvider = fileProvider;
        this.cachingManager = cachingManager;
        this.pageDirectoryPath = new File( fileProvider.getPageDirectory() ).toPath();
        setName( "JSPWiki Page Directory Watcher" );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Registers the WatchService on the page directory.
     */
    @Override
    public void startupTask() throws Exception {
        // Wait for the page directory to exist (it may still be initializing in a parallel test environment)
        final File dir = pageDirectoryPath.toFile();
        for( int i = 0; i < 50 && !dir.isDirectory(); i++ ) {
            Thread.sleep( 100 );
        }
        if( !dir.isDirectory() ) {
            LOG.error( "Page directory does not exist after waiting, watcher will not start: {}", pageDirectoryPath );
            return;
        }

        watchService = FileSystems.getDefault().newWatchService();
        pageDirectoryPath.register( watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE );
        LOG.info( "Page directory watcher started, monitoring: {}", pageDirectoryPath );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Polls the WatchService for pending events, collects changed filenames into
     * a deduplicated set, and processes each change by invalidating caches and firing events.
     */
    @Override
    public void backgroundTask() throws Exception {
        if( watchService == null ) {
            return;
        }

        final Set<String> createdOrModified = new HashSet<>();
        final Set<String> deleted = new HashSet<>();

        // Non-blocking poll for all pending watch keys
        WatchKey key;
        while( ( key = watchService.poll() ) != null ) {
            for( final WatchEvent<?> event : key.pollEvents() ) {
                final WatchEvent.Kind<?> kind = event.kind();

                if( kind == StandardWatchEventKinds.OVERFLOW ) {
                    LOG.warn( "WatchService overflow detected, some filesystem events may have been lost" );
                    continue;
                }

                @SuppressWarnings( "unchecked" )
                final WatchEvent<Path> pathEvent = ( WatchEvent<Path> ) event;
                final String filename = pathEvent.context().toString();

                // Only process wiki page files (.md and .txt)
                if( !filename.endsWith( AbstractFileProvider.MARKDOWN_EXT )
                        && !filename.endsWith( AbstractFileProvider.FILE_EXT ) ) {
                    continue;
                }

                final String pageName = filenameToPageName( filename );
                if( pageName == null ) {
                    continue;
                }

                // Skip pages recently saved through JSPWiki's own API
                if( isRecentInternalSave( pageName ) ) {
                    LOG.debug( "Skipping watcher processing for internally saved page: {}", pageName );
                    continue;
                }

                if( kind == StandardWatchEventKinds.ENTRY_DELETE ) {
                    deleted.add( pageName );
                } else {
                    createdOrModified.add( pageName );
                }
            }
            key.reset();
        }

        // Process collected changes
        for( final String pageName : createdOrModified ) {
            // If a page was both deleted and re-created in the same batch, treat as modified
            deleted.remove( pageName );
            processCreatedOrModified( pageName );
        }

        for( final String pageName : deleted ) {
            processDeleted( pageName );
        }

        // Periodically clean up stale guard entries
        cleanupGuardEntries();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Closes the WatchService.
     */
    @Override
    public void shutdownTask() throws Exception {
        if( watchService != null ) {
            watchService.close();
            LOG.info( "Page directory watcher stopped" );
        }
    }

    /**
     * Records that a page was just saved through JSPWiki's internal API.
     * The watcher will skip processing filesystem events for this page
     * for a short period to avoid double-processing.
     *
     * @param pageName the name of the page being saved internally
     */
    void notifyInternalSave( final String pageName ) {
        recentInternalSaves.put( pageName, System.currentTimeMillis() );
    }

    /**
     * Processes a file creation or modification event.
     * Invalidates all relevant caches and fires events to trigger re-rendering,
     * reference updates, and search re-indexing.
     */
    private void processCreatedOrModified( final String pageName ) {
        LOG.info( "External change detected for page: {}", pageName );

        try {
            // 1. Invalidate file extension cache so .md/.txt precedence is re-evaluated
            fileProvider.invalidateFileExtensionCache( pageName );

            // 2. Invalidate EhCache entries
            cachingManager.remove( CachingManager.CACHE_PAGES, pageName );
            cachingManager.remove( CachingManager.CACHE_PAGES_TEXT, pageName );
            cachingManager.remove( CachingManager.CACHE_PAGES_HISTORY, pageName );

            // 3. Fire POST_SAVE_BEGIN event on FilterManager to trigger rendering cache flush
            //    DefaultRenderingManager listens for this event and flushes CACHE_DOCUMENTS
            //    for this page and all pages that refer to it
            final FilterManager filterManager = engine.getManager( FilterManager.class );
            if( filterManager != null && WikiEventManager.isListening( filterManager ) ) {
                WikiEventManager.fireEvent( filterManager,
                        new WikiPageEvent( engine, WikiPageEvent.POST_SAVE_BEGIN, pageName ) );
            }

            // 4. Update references — rebuild the link graph for this page
            final ReferenceManager refMgr = engine.getManager( ReferenceManager.class );
            if( refMgr != null ) {
                final Page page = Wiki.contents().page( engine, pageName );
                refMgr.updateReferences( page );
            }

            // 5. Reindex for search
            final SearchManager searchMgr = engine.getManager( SearchManager.class );
            if( searchMgr != null ) {
                final Page page = Wiki.contents().page( engine, pageName );
                page.setVersion( PageProvider.LATEST_VERSION );
                searchMgr.reindexPage( page );
            }
        } catch( final Exception e ) {
            LOG.error( "Error processing external change for page: {}", pageName, e );
        }
    }

    /**
     * Processes a file deletion event.
     * Invalidates all relevant caches and fires a PAGE_DELETED event.
     */
    private void processDeleted( final String pageName ) {
        LOG.info( "External deletion detected for page: {}", pageName );

        try {
            // 1. Invalidate file extension cache
            fileProvider.invalidateFileExtensionCache( pageName );

            // 2. Invalidate EhCache entries
            cachingManager.remove( CachingManager.CACHE_PAGES, pageName );
            cachingManager.remove( CachingManager.CACHE_PAGES_TEXT, pageName );
            cachingManager.remove( CachingManager.CACHE_PAGES_HISTORY, pageName );

            // 3. Fire POST_SAVE_BEGIN to flush rendering cache
            final FilterManager filterManager = engine.getManager( FilterManager.class );
            if( filterManager != null && WikiEventManager.isListening( filterManager ) ) {
                WikiEventManager.fireEvent( filterManager,
                        new WikiPageEvent( engine, WikiPageEvent.POST_SAVE_BEGIN, pageName ) );
            }

            // 4. Notify reference manager about removal
            final ReferenceManager refMgr = engine.getManager( ReferenceManager.class );
            if( refMgr != null ) {
                final Page page = Wiki.contents().page( engine, pageName );
                refMgr.pageRemoved( page );
            }

            // 5. Remove from search index
            final SearchManager searchMgr = engine.getManager( SearchManager.class );
            if( searchMgr != null ) {
                final Page page = Wiki.contents().page( engine, pageName );
                searchMgr.pageRemoved( page );
            }
        } catch( final Exception e ) {
            LOG.error( "Error processing external deletion for page: {}", pageName, e );
        }
    }

    /**
     * Converts a filename (e.g., "MyPage.md") to a wiki page name (e.g., "MyPage").
     * Uses the file provider's unmangle logic to decode URL-encoded filenames.
     *
     * @param filename the filename including extension
     * @return the page name, or null if the filename is not a recognized page file
     */
    String filenameToPageName( final String filename ) {
        String baseName;
        if( filename.endsWith( AbstractFileProvider.MARKDOWN_EXT ) ) {
            baseName = filename.substring( 0, filename.length() - AbstractFileProvider.MARKDOWN_EXT.length() );
        } else if( filename.endsWith( AbstractFileProvider.FILE_EXT ) ) {
            baseName = filename.substring( 0, filename.length() - AbstractFileProvider.FILE_EXT.length() );
        } else {
            return null;
        }
        return fileProvider.unmangleName( baseName );
    }

    /**
     * Checks if a page was recently saved through JSPWiki's internal API.
     */
    private boolean isRecentInternalSave( final String pageName ) {
        final Long timestamp = recentInternalSaves.get( pageName );
        if( timestamp == null ) {
            return false;
        }
        return ( System.currentTimeMillis() - timestamp ) < INTERNAL_SAVE_GUARD_MILLIS;
    }

    /**
     * Periodically removes stale entries from the internal save guard map.
     */
    private void cleanupGuardEntries() {
        final long now = System.currentTimeMillis();
        if( ( now - lastGuardCleanup ) < GUARD_CLEANUP_INTERVAL_MILLIS ) {
            return;
        }
        lastGuardCleanup = now;

        final Iterator<Map.Entry<String, Long>> it = recentInternalSaves.entrySet().iterator();
        while( it.hasNext() ) {
            final Map.Entry<String, Long> entry = it.next();
            if( ( now - entry.getValue() ) > INTERNAL_SAVE_GUARD_MILLIS ) {
                it.remove();
            }
        }
    }

}
