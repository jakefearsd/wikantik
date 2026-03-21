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
package com.wikantik.pages;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.WikiBackgroundThread;
import com.wikantik.api.core.Acl;
import com.wikantik.api.core.AclEntry;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.attachment.AttachmentManager;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.cache.CachingManager;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiSecurityEvent;
import com.wikantik.providers.RepositoryModifiedException;
import com.wikantik.references.ReferenceManager;
import com.wikantik.filters.FilterManager;
import com.wikantik.search.SearchManager;
import com.wikantik.ui.CommandResolver;
import com.wikantik.util.ClassUtil;
import com.wikantik.util.TextUtil;

import java.io.IOException;
import java.security.Permission;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Manages the WikiPages. This class functions as an unified interface towards the page providers. It handles initialization
 * and management of the providers, and provides utility methods for accessing the contents.
 *
 * @since 2.0
 */
public class DefaultPageManager implements PageManager {

    private static final Logger LOG = LogManager.getLogger( DefaultPageManager.class );
    private final PageProvider provider;
    private final Engine engine;
    private final int expiryTime;
    protected final ConcurrentHashMap< String, PageLock > pageLocks = new ConcurrentHashMap<>();
    private final PageSorter pageSorter = new PageSorter();
    private LockReaper reaper;

    /**
     * Creates a new PageManager.
     *
     * @param newEngine Engine instance
     * @param props  Properties to use for initialization
     * @throws NoSuchElementException {@value #PROP_PAGEPROVIDER} property not found on Engine properties
     * @throws WikiException If anything goes wrong, you get this.
     */
    public DefaultPageManager(final Engine newEngine, final Properties props) throws NoSuchElementException, WikiException {
        this.engine = newEngine;
        final String classname;
        final boolean useCache = engine.getManager( CachingManager.class ).enabled( CachingManager.CACHE_PAGES );
        expiryTime = TextUtil.parseIntParameter( props.getProperty( PROP_LOCKEXPIRY ), 60 );

        //  If user wants to use a cache, then we'll use the CachingProvider.
        if( useCache ) {
            classname = "com.wikantik.providers.CachingProvider";
        } else {
            classname = TextUtil.getRequiredProperty( props, PROP_PAGEPROVIDER );
        }

        pageSorter.initialize( props );

        try {
            LOG.debug( "Page provider class: '{}'", classname );
            provider = ClassUtil.buildInstance( "com.wikantik.providers", classname );
            LOG.debug( "Initializing page provider class {}", provider );
            provider.initialize( engine, props );
        } catch( final ReflectiveOperationException e ) {
            LOG.error( "Unable to instantiate provider class '{}' ({})", classname, e.getMessage(), e );
            throw new WikiException( "Illegal provider class. (" + e.getMessage() + ")", e );
        } catch( final NoRequiredPropertyException e ) {
            LOG.error("Provider did not found a property it was looking for: {}", e.getMessage(), e);
            throw e;  // Same exception works.
        } catch( final IOException e ) {
            LOG.error("An I/O exception occurred while trying to create a new page provider: {}", classname, e);
            throw new WikiException("Unable to start page provider: " + e.getMessage(), e);
        }

    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#getProvider()
     */
    @Override
    public PageProvider getProvider() {
        return provider;
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#getAllPages()
     */
    @Override
    public Collection< Page > getAllPages() throws ProviderException {
        return provider.getAllPages();
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#getPageText(java.lang.String, int)
     */
    @Override
    public String getPageText( final String pageName, final int version ) throws ProviderException {
        if (pageName == null || pageName.isEmpty()) {
            throw new ProviderException( "Illegal page name" );
        }
        String text;

        try {
            text = provider.getPageText( pageName, version );
        } catch ( final RepositoryModifiedException e ) {
            //  This only occurs with the latest version.
            LOG.info( "Repository has been modified externally while fetching page " + pageName );

            //  Empty the references and yay, it shall be recalculated
            final Page p = provider.getPageInfo( pageName, version );

            engine.getManager( ReferenceManager.class ).updateReferences( p );
            fireEvent( WikiPageEvent.PAGE_REINDEX, p.getName() );
            text = provider.getPageText( pageName, version );
        }

        return text;
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#getPureText(String, int)
     */
    @Override
    public String getPureText( final String page, final int version ) {
        String result = null;
        try {
            result = getPageText( page, version );
        } catch( final ProviderException e ) {
            LOG.error( "ProviderException getPureText for page " + page + " [version " + version + "]", e );
        } finally {
            if( result == null ) {
                result = "";
            }
        }
        return result;
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#getText(String, int)
     */
    @Override
    public String getText( final String page, final int version ) {
        final String result = getPureText( page, version );
        return TextUtil.replaceEntities( result );
    }

    @Override
    public void saveText( final Context context, final String text ) throws WikiException {
        // Check if page data actually changed; bail if not
        final Page page = context.getPage();
        final String oldText = getPureText( page );
        final String proposedText = TextUtil.normalizePostData( text );
        if ( oldText != null && oldText.equals( proposedText ) ) {
            return;
        }

        // Check if creation of empty pages is allowed; bail if not
        final boolean allowEmpty = TextUtil.getBooleanProperty( engine.getWikiProperties(),
                                                                Engine.PROP_ALLOW_CREATION_OF_EMPTY_PAGES,
                                                         false );
        if ( !allowEmpty && !wikiPageExists( page ) && text.trim().equals( "" ) ) {
            return;
        }

        // Set the page author
        final Page page2 = context.getPage();
        if ( page2.getAuthor() == null && context.getCurrentUser() != null ) {
            page2.setAuthor( context.getCurrentUser().getName() );
        }

        // Run pre-save filters
        final String saveText = engine.getManager( FilterManager.class ).doPreSaveFiltering( context, proposedText );

        // Save the page text
        putPageText( page, saveText );

        // Refresh the context for post-save filtering
        getPage( page.getName() );
        engine.getManager( FilterManager.class ).doPostSaveFiltering( context, saveText );

        // Reindex the saved page
        page.setVersion( com.wikantik.api.providers.PageProvider.LATEST_VERSION );
        engine.getManager( SearchManager.class ).reindexPage( page );
    }

    /**
     * Returns the Engine to which this PageManager belongs to.
     *
     * @return The Engine object.
     */
    protected Engine getEngine() {
        return engine;
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#putPageText(com.wikantik.api.core.Page, java.lang.String)
     */
    @Override
    public void putPageText( final Page page, final String content ) throws ProviderException {
        if (page == null || page.getName() == null || page.getName().isEmpty()) {
            throw new ProviderException("Illegal page name");
        }

        provider.putPageText(page, content);
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#lockPage(com.wikantik.api.core.Page, java.lang.String)
     */
    @Override
    public PageLock lockPage( final Page page, final String user ) {
        if( reaper == null ) {
            //  Start the lock reaper lazily.  We don't want to start it in the constructor, because starting threads in constructors
            //  is a bad idea when it comes to inheritance.  Besides, laziness is a virtue.
            reaper = new LockReaper( engine );
            reaper.start();
        }

        fireEvent( WikiPageEvent.PAGE_LOCK, page.getName() ); // prior to or after actual lock?
        PageLock lock = pageLocks.get( page.getName() );

        if( lock == null ) {
            //
            //  Lock is available, so make a lock.
            //
            final Date d = new Date();
            lock = new PageLock( page, user, d, new Date( d.getTime() + expiryTime * 60 * 1000L ) );
            pageLocks.put( page.getName(), lock );
            LOG.debug( "Locked page " + page.getName() + " for " + user );
        } else {
            LOG.debug( "Page " + page.getName() + " already locked by " + lock.getLocker() );
            lock = null; // Nothing to return
        }

        return lock;
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#unlockPage(com.wikantik.pages.PageLock)
     */
    @Override
    public void unlockPage( final PageLock lock ) {
        if (lock == null) {
            return;
        }

        pageLocks.remove( lock.getPage() );
        LOG.debug( "Unlocked page " + lock.getPage() );

        fireEvent( WikiPageEvent.PAGE_UNLOCK, lock.getPage() );
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#getCurrentLock(com.wikantik.api.core.Page)
     */
    @Override
    public PageLock getCurrentLock( final Page page ) {
        return pageLocks.get( page.getName() );
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#getActiveLocks()
     */
    @Override
    public List< PageLock > getActiveLocks() {
        return  new ArrayList<>( pageLocks.values() );
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#getPage(java.lang.String)
     */
    @Override
    public Page getPage( final String pagereq ) {
        return getPage( pagereq, PageProvider.LATEST_VERSION );
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#getPage(java.lang.String, int)
     */
    @Override
    public Page getPage( final String pagereq, final int version ) {
        try {
            Page p = getPageInfo( pagereq, version );
            if( p == null ) {
                p = engine.getManager( AttachmentManager.class ).getAttachmentInfo( null, pagereq );
            }

            return p;
        } catch( final ProviderException e ) {
            LOG.warn( "Unable to fetch page info for {} [version {}]: {}", pagereq, version, e.getMessage() );
            return null;
        }
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#getPageInfo(java.lang.String, int)
     */
    @Override
    public Page getPageInfo( final String pageName, final int version) throws ProviderException {
        if( pageName == null || pageName.isEmpty() ) {
            throw new ProviderException( "Illegal page name '" + pageName + "'" );
        }

        Page page;

        try {
            page = provider.getPageInfo( pageName, version );
        } catch( final RepositoryModifiedException e ) {
            //  This only occurs with the latest version.
            LOG.info( "Repository has been modified externally while fetching info for " + pageName );
            page = provider.getPageInfo( pageName, version );
            if( page != null ) {
                engine.getManager( ReferenceManager.class ).updateReferences( page );
            } else {
                engine.getManager( ReferenceManager.class ).pageRemoved( Wiki.contents().page( engine, pageName ) );
            }
        }

        return page;
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#getVersionHistory(java.lang.String)
     */
    @Override @SuppressWarnings( "unchecked" )
    public < T extends Page > List< T > getVersionHistory( final String pageName ) {
        List< T > c = null;

        try {
            if( pageExists( pageName ) ) {
                c = ( List< T > )provider.getVersionHistory( pageName );
            }

            if( c == null ) {
                c = ( List< T > )engine.getManager( AttachmentManager.class ).getVersionHistory( pageName );
            }
        } catch( final ProviderException e ) {
            LOG.error( "ProviderException requesting version history for " + pageName, e );
        }

        return c;
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#getCurrentProvider()
     */
    @Override 
    public String getCurrentProvider() {
        return getProvider().getClass().getName();
    }

    /**
     * {@inheritDoc}
     *
     * @see com.wikantik.pages.PageManager#getProviderDescription()
     */
    @Override 
    public String getProviderDescription() {
        return provider.getProviderInfo();
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#getTotalPageCount()
     */
    @Override
    public int getTotalPageCount() {
        try {
            return provider.getAllPages().size();
        } catch( final ProviderException e ) {
            LOG.error( "Unable to count pages: ", e );
            return -1;
        }
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#getRecentChanges()
     */
    @Override
    public Set< Page > getRecentChanges() {
        return getRecentChanges( new Date( 0L ) );
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#getRecentChanges(Date)
     */
    @Override
    public Set< Page > getRecentChanges( final Date since ) {
        try {
            final var sortedPages = new TreeSet<>( new PageTimeComparator() );
            sortedPages.addAll( provider.getAllChangedSince( since ) );
            sortedPages.addAll( engine.getManager( AttachmentManager.class ).getAllAttachmentsSince( since ) );

            return sortedPages;
        } catch( final ProviderException e ) {
            LOG.error( "Unable to fetch recent changes: ", e );
            return Collections.emptySet();
        }
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#pageExists(java.lang.String)
     */
    @Override
    public boolean pageExists( final String pageName ) throws ProviderException {
        if (pageName == null || pageName.isEmpty()) {
            throw new ProviderException("Illegal page name");
        }

        return provider.pageExists(pageName);
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#pageExists(java.lang.String, int)
     */
    @Override
    public boolean pageExists( final String pageName, final int version ) throws ProviderException {
        if( pageName == null || pageName.isEmpty() ) {
            throw new ProviderException( "Illegal page name" );
        }

        if( version == WikiProvider.LATEST_VERSION ) {
            return pageExists( pageName );
        }

        return provider.pageExists( pageName, version );
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#wikiPageExists(java.lang.String)
     */
    @Override
    public boolean wikiPageExists( final String page ) {
        if( engine.getManager( CommandResolver.class ).getSpecialPageReference( page ) != null ) {
            return true;
        }

        Attachment att = null;
        try {
            if( engine.getFinalPageName( page ) != null ) {
                return true;
            }

            att = engine.getManager( AttachmentManager.class ).getAttachmentInfo( null, page );
        } catch( final ProviderException e ) {
            LOG.debug( "pageExists() failed to find attachments", e );
        }

        return att != null;
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#wikiPageExists(java.lang.String, int)
     */
    @Override
    public boolean wikiPageExists( final String page, final int version ) throws ProviderException {
        if( engine.getManager( CommandResolver.class ).getSpecialPageReference( page ) != null ) {
            return true;
        }

        boolean isThere = false;
        final String finalName = engine.getFinalPageName( page );
        if( finalName != null ) {
            isThere = pageExists( finalName, version );
        }

        if( !isThere ) {
            //  Go check if such an attachment exists.
            try {
                isThere = engine.getManager( AttachmentManager.class ).getAttachmentInfo( null, page, version ) != null;
            } catch( final ProviderException e ) {
                LOG.debug( "wikiPageExists() failed to find attachments", e );
            }
        }

        return isThere;
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#deleteVersion(com.wikantik.api.core.Page)
     */
    @Override
    public void deleteVersion( final Page page ) throws ProviderException {
        if( page instanceof Attachment att ) {
            engine.getManager( AttachmentManager.class ).deleteVersion( att );
        } else {
            provider.deleteVersion( page.getName(), page.getVersion() );
            // FIXME: If this was the latest, reindex Lucene, update RefMgr
        }
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#deletePage(java.lang.String)
     */
    @Override
    public void deletePage( final String pageName ) throws ProviderException {
        final Page p = getPage( pageName );
        if( p != null ) {
            if( p instanceof Attachment att ) {
                engine.getManager( AttachmentManager.class ).deleteAttachment( att );
            } else {
                final Collection< String > refTo = new ArrayList<>( engine.getManager( ReferenceManager.class ).findRefersTo( pageName ) );

                if( engine.getManager( AttachmentManager.class ).hasAttachments( p ) ) {
                    final List< Attachment > attachments = engine.getManager( AttachmentManager.class ).listAttachments( p );
                    for( final Attachment attachment : attachments ) {
                        refTo.remove( attachment.getName() );

                        engine.getManager( AttachmentManager.class ).deleteAttachment( attachment );
                    }
                }
                deletePage( p );
                fireEvent( WikiPageEvent.PAGE_DELETED, pageName );
            }
        }
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#deletePage(com.wikantik.api.core.Page)
     */
    @Override
    public void deletePage( final Page page ) throws ProviderException {
        fireEvent( WikiPageEvent.PAGE_DELETE_REQUEST, page.getName() );
        provider.deletePage( page.getName() );
        fireEvent( WikiPageEvent.PAGE_DELETED, page.getName() );
    }

    /**
     * This is a simple reaper thread that runs roughly every minute
     * or so (it's not really that important, as long as it runs),
     * and removes all locks that have expired.
     */
    private class LockReaper extends WikiBackgroundThread {
        /**
         * Create a LockReaper for a given engine.
         *
         * @param newEngine Engine to own this thread.
         */
        public LockReaper( final Engine newEngine) {
            super( engine, 60 );
            setName( "JSPWiki Lock Reaper" );
        }

        @Override
        public void backgroundTask() {
            final Collection< PageLock > entries = pageLocks.values();
            for( final Iterator<PageLock> i = entries.iterator(); i.hasNext(); ) {
                final PageLock p = i.next();

                if ( p.isExpired() ) {
                    i.remove();

                    LOG.debug( "Reaped lock: " + p.getPage() +
                               " by " + p.getLocker() +
                               ", acquired " + p.getAcquisitionTime() +
                               ", and expired " + p.getExpiryTime() );
                }
            }
        }
    }

    // events processing .......................................................

    /**
     * Fires a WikiPageEvent of the provided type and page name
     * to all registered listeners.
     *
     * @param type     the event type to be fired
     * @param pagename the wiki page name as a String
     * @see com.wikantik.event.WikiPageEvent
     */
    protected final void fireEvent( final int type, final String pagename ) {
        if( WikiEventManager.isListening( this ) ) {
            WikiEventManager.fireEvent( this, new WikiPageEvent( engine, type, pagename ) );
        }
    }

    /**
     * Listens for {@link com.wikantik.event.WikiSecurityEvent#PROFILE_NAME_CHANGED}
     * events. If a user profile's name changes, each page ACL is inspected. If an entry contains
     * a name that has changed, it is replaced with the new one. No events are emitted
     * as a consequence of this method, because the page contents are still the same; it is
     * only the representations of the names within the ACL that are changing.
     *
     * @param event The event
     */
    @Override
    public void actionPerformed( final WikiEvent event ) {
        if( !( event instanceof WikiSecurityEvent se ) ) {
            return;
        }

        if( se.getType() == WikiSecurityEvent.PROFILE_NAME_CHANGED ) {
            final UserProfile[] profiles = (UserProfile[]) se.getTarget();
            final Principal[] oldPrincipals = new Principal[] { new WikiPrincipal( profiles[ 0 ].getLoginName() ),
                                                                new WikiPrincipal( profiles[ 0 ].getFullname()),
                                                                new WikiPrincipal( profiles[ 0 ].getWikiName() ) };
            final Principal newPrincipal = new WikiPrincipal( profiles[ 1 ].getFullname() );

            // Examine each page ACL
            try {
                int pagesChanged = 0;
                final Collection< Page > pages = getAllPages();
                for( final Page page : pages ) {
                    final boolean aclChanged = changeAcl( page, oldPrincipals, newPrincipal );
                    if( aclChanged ) {
                        // If the Acl needed changing, change it now
                        try {
                            engine.getManager( AclManager.class ).setPermissions( page, page.getAcl() );
                        } catch( final WikiSecurityException e ) {
                            LOG.error("Could not change page ACL for page " + page.getName() + ": " + e.getMessage(), e);
                        }
                        pagesChanged++;
                    }
                }
                LOG.info( "Profile name change for '" + newPrincipal + "' caused " + pagesChanged + " page ACLs to change also." );
            } catch( final ProviderException e ) {
                // Oooo! This is really bad...
                LOG.error( "Could not change user name in Page ACLs because of Provider error:" + e.getMessage(), e );
            }
        }
    }

    /**
     * For a single wiki page, replaces all Acl entries matching a supplied array of Principals with a new Principal.
     *
     * @param page the wiki page whose Acl is to be modified
     * @param oldPrincipals an array of Principals to replace; all AclEntry objects whose {@link AclEntry#getPrincipal()} method returns
     *                      one of these Principals will be replaced
     * @param newPrincipal the Principal that should receive the old Principals' permissions
     * @return <code>true</code> if the Acl was actually changed; <code>false</code> otherwise
     */
    protected boolean changeAcl( final Page page, final Principal[] oldPrincipals, final Principal newPrincipal ) {
        final Acl acl = page.getAcl();
        boolean pageChanged = false;
        if( acl != null ) {
            final Enumeration< AclEntry > entries = acl.aclEntries();
            final Collection< AclEntry > entriesToAdd = new ArrayList<>();
            final Collection< AclEntry > entriesToRemove = new ArrayList<>();
            while( entries.hasMoreElements() ) {
                final AclEntry entry = entries.nextElement();
                if( ArrayUtils.contains( oldPrincipals, entry.getPrincipal() ) ) {
                    // Create new entry
                    final AclEntry newEntry = Wiki.acls().entry();
                    newEntry.setPrincipal( newPrincipal );
                    final Enumeration< Permission > permissions = entry.permissions();
                    while( permissions.hasMoreElements() ) {
                        final Permission permission = permissions.nextElement();
                        newEntry.addPermission( permission );
                    }
                    pageChanged = true;
                    entriesToRemove.add( entry );
                    entriesToAdd.add( newEntry );
                }
            }
            for( final AclEntry entry : entriesToRemove ) {
                acl.removeEntry( entry );
            }
            for( final AclEntry entry : entriesToAdd ) {
                acl.addEntry( entry );
            }
        }
        return pageChanged;
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.pages.PageManager#getPageSorter()
     */
    @Override
    public PageSorter getPageSorter() {
        return pageSorter;
    }

}
