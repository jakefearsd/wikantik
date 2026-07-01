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
package com.wikantik.page.subsystem.lifecycle;

import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pages.PageSorter;
import com.wikantik.pagegraph.subsystem.PageGraphSubsystemBridge;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.pages.PageTimeComparator;
import com.wikantik.providers.RepositoryModifiedException;
import com.wikantik.ui.CommandResolver;
import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Default implementation of {@link PageRepository}.
 *
 * <p>All logic is moved verbatim from {@code DefaultPageManager} in
 * Phase 5 Checkpoint 3 of the wikantik-main subsystem decomposition.</p>
 */
public class DefaultPageRepository implements PageRepository {

    private static final Logger LOG = LogManager.getLogger( DefaultPageRepository.class );

    private final PageProvider    provider;
    private final Engine          engine;
    private final CommandResolver commandResolver;
    private final PageSorter      pageSorter;
    /** The object on behalf of which {@link WikiEventManager} events are fired. */
    private final Object          eventSource;

    /**
     * Constructor used by {@code DefaultPageManager}.
     *
     * @param engine          Engine instance
     * @param commandResolver Phase 1 CommandResolver
     * @param provider        Pre-built PageProvider chain
     * @param pageSorter      Shared PageSorter instance (initialised by the facade)
     * @param eventSource     Object used as the event source for {@link WikiEventManager} registration
     */
    public DefaultPageRepository( final Engine engine,
                                  final CommandResolver commandResolver,
                                  final PageProvider provider,
                                  final PageSorter pageSorter,
                                  final Object eventSource ) {
        this.engine          = engine;
        this.commandResolver = commandResolver;
        this.provider        = provider;
        this.pageSorter      = pageSorter;
        this.eventSource     = eventSource;
    }

    // --- Lazy manager accessors (same ordering / comments as original facade) ---

    private AttachmentManager getAttachmentManager() {
        return PageSubsystemBridge.fromLegacyEngine( engine ).attachments();
    }

    private ReferenceManager getReferenceManager() {
        return PageGraphSubsystemBridge.fromLegacyEngine( engine ).referenceManager();
    }

    // --- Event helper ---

    private void fireEvent( final int type, final String pagename ) {
        if ( WikiEventManager.isListening( eventSource ) ) {
            com.wikantik.core.subsystem.CoreSubsystemBridge.fromLegacyEngine( engine )
                .eventBus().fireEvent( eventSource, new WikiPageEvent( engine, type, pagename ) );
        }
    }

    // -------------------------------------------------------------------------
    // PageRepository implementation
    // -------------------------------------------------------------------------

    @Override
    public PageProvider getProvider() {
        return provider;
    }

    @Override
    public Collection<Page> getAllPages() throws ProviderException {
        return provider.getAllPages();
    }

    @Override
    public String getPageText( final String pageName, final int version ) throws ProviderException {
        if ( pageName == null || pageName.isEmpty() ) {
            throw new ProviderException( "Illegal page name" );
        }
        String text;

        try {
            text = provider.getPageText( pageName, version );
        } catch ( final RepositoryModifiedException e ) {
            //  This only occurs with the latest version.
            LOG.info( "Repository has been modified externally while fetching page {}", pageName );

            //  Empty the references and yay, it shall be recalculated
            final Page reindexedPage = provider.getPageInfo( pageName, version );

            getReferenceManager().updateReferences( reindexedPage );
            fireEvent( WikiPageEvent.PAGE_REINDEX, reindexedPage.getName() );
            text = provider.getPageText( pageName, version );
        }

        return text;
    }

    @Override
    public String getPureText( final String page, final int version ) {
        String result = null;
        try {
            result = getPageText( page, version );
        } catch ( final ProviderException e ) {
            // LOG.error justified: page text retrieval failure is surfaced to callers that rely on non-null return for rendering
            LOG.error( "ProviderException getPureText for page {} [version {}]", page, version, e );
        } finally {
            if ( result == null ) {
                result = "";
            }
        }
        return result;
    }

    @Override
    public String getText( final String page, final int version ) {
        final String result = getPureText( page, version );
        return TextUtil.replaceEntities( result );
    }

    @Override
    public void putPageText( final Page page, final String content ) throws ProviderException {
        if ( page == null || page.getName() == null || page.getName().isEmpty() ) {
            throw new ProviderException( "Illegal page name" );
        }

        provider.putPageText( page, content );
    }

    @Override
    public Page getPage( final String pagereq ) {
        return getPage( pagereq, PageProvider.LATEST_VERSION );
    }

    @Override
    public Page getPage( final String pagereq, final int version ) {
        try {
            Page p = getPageInfo( pagereq, version );
            if ( p == null ) {
                p = getAttachmentManager().getAttachmentInfo( null, pagereq );
            }

            return p;
        } catch ( final ProviderException e ) {
            LOG.warn( "Unable to fetch page info for {} [version {}]: {}", pagereq, version, e.getMessage() );
            return null;
        }
    }

    @Override
    public Page getPageInfo( final String pageName, final int version ) throws ProviderException {
        if ( pageName == null || pageName.isEmpty() ) {
            throw new ProviderException( "Illegal page name '" + pageName + "'" );
        }

        Page page;

        try {
            page = provider.getPageInfo( pageName, version );
        } catch ( final RepositoryModifiedException e ) {
            //  This only occurs with the latest version.
            LOG.info( "Repository has been modified externally while fetching info for {}", pageName );
            page = provider.getPageInfo( pageName, version );
            if ( page != null ) {
                getReferenceManager().updateReferences( page );
            } else {
                getReferenceManager().pageRemoved( Wiki.contents().page( engine, pageName ) );
            }
        }

        return page;
    }

    @Override
    @SuppressWarnings( "unchecked" )
    public <T extends Page> List<T> getVersionHistory( final String pageName ) {
        List<T> c = null;

        try {
            if ( pageExists( pageName ) ) {
                c = (List<T>) provider.getVersionHistory( pageName );
            }

            if ( c == null ) {
                c = (List<T>) getAttachmentManager().getVersionHistory( pageName );
            }
        } catch ( final ProviderException e ) {
            // LOG.error justified: version history loss is a data-visibility failure that operators must see
            LOG.error( "ProviderException requesting version history for {}", pageName, e );
        }

        return c;
    }

    @Override
    public String getCurrentProvider() {
        return getProvider().getClass().getName();
    }

    @Override
    public String getProviderDescription() {
        return provider.getProviderInfo();
    }

    @Override
    public int getTotalPageCount() {
        try {
            return provider.getAllPages().size();
        } catch ( final ProviderException e ) {
            // LOG.error justified: page count failure affects admin dashboards and may indicate provider corruption
            LOG.error( "Unable to count pages: ", e );
            return -1;
        }
    }

    @Override
    public Set<Page> getRecentChanges() {
        return getRecentChanges( new Date( 0L ) );
    }

    @Override
    public Set<Page> getRecentChanges( final Date since ) {
        try {
            final var sortedPages = new TreeSet<>( new PageTimeComparator() );
            sortedPages.addAll( provider.getAllChangedSince( since ) );
            sortedPages.addAll( getAttachmentManager().getAllAttachmentsSince( since ) );

            return sortedPages;
        } catch ( final ProviderException e ) {
            // LOG.error justified: recent changes failure is user-visible and indicates storage layer problems
            LOG.error( "Unable to fetch recent changes: ", e );
            return Collections.emptySet();
        }
    }

    @Override
    public boolean pageExists( final String pageName ) throws ProviderException {
        if ( pageName == null || pageName.isEmpty() ) {
            throw new ProviderException( "Illegal page name: '" + pageName + "'" );
        }

        return provider.pageExists( pageName );
    }

    @Override
    public boolean pageExists( final String pageName, final int version ) throws ProviderException {
        if ( pageName == null || pageName.isEmpty() ) {
            throw new ProviderException( "Illegal page name: '" + pageName + "'" );
        }

        if ( version == WikiProvider.LATEST_VERSION ) {
            return pageExists( pageName );
        }

        return provider.pageExists( pageName, version );
    }

    @Override
    public boolean wikiPageExists( final String page ) {
        if ( commandResolver.getSpecialPageReference( page ) != null ) {
            return true;
        }

        Attachment att = null;
        try {
            if ( engine.getFinalPageName( page ) != null ) {
                return true;
            }

            att = getAttachmentManager().getAttachmentInfo( null, page );
        } catch ( final ProviderException e ) {
            LOG.debug( "pageExists() failed to find attachments", e );
        }

        return att != null;
    }

    @Override
    public boolean wikiPageExists( final String page, final int version ) throws ProviderException {
        if ( commandResolver.getSpecialPageReference( page ) != null ) {
            return true;
        }

        boolean isThere = false;
        final String finalName = engine.getFinalPageName( page );
        if ( finalName != null ) {
            isThere = pageExists( finalName, version );
        }

        if ( !isThere ) {
            //  Go check if such an attachment exists.
            try {
                isThere = getAttachmentManager().getAttachmentInfo( null, page, version ) != null;
            } catch ( final ProviderException e ) {
                LOG.debug( "wikiPageExists() failed to find attachments", e );
            }
        }

        return isThere;
    }

    @Override
    public void deleteVersion( final Page page ) throws ProviderException {
        if ( page instanceof Attachment att ) {
            getAttachmentManager().deleteVersion( att );
        } else {
            provider.deleteVersion( page.getName(), page.getVersion() );
            // FIXME: If this was the latest, reindex Lucene, update RefMgr
        }
    }

    @Override
    public void deletePage( final String pageName ) throws ProviderException {
        final Page pageToDelete = getPage( pageName );
        if ( pageToDelete != null ) {
            if ( pageToDelete instanceof Attachment att ) {
                getAttachmentManager().deleteAttachment( att );
            } else {
                if ( getAttachmentManager().hasAttachments( pageToDelete ) ) {
                    final List<Attachment> attachments = getAttachmentManager().listAttachments( pageToDelete );
                    for ( final Attachment attachment : attachments ) {
                        getAttachmentManager().deleteAttachment( attachment );
                    }
                }
                deletePage( pageToDelete );
                fireEvent( WikiPageEvent.PAGE_DELETED, pageName );
            }
        }
    }

    @Override
    public void deletePage( final Page page ) throws ProviderException {
        fireEvent( WikiPageEvent.PAGE_DELETE_REQUEST, page.getName() );
        provider.deletePage( page.getName() );
        fireEvent( WikiPageEvent.PAGE_DELETED, page.getName() );
    }

    @Override
    public PageSorter getPageSorter() {
        return pageSorter;
    }
}
