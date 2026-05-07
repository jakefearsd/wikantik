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
import com.wikantik.api.core.Acl;
import com.wikantik.api.core.AclEntry;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.api.pages.PageLock;
import com.wikantik.api.pages.PageSorter;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiSecurityEvent;
import com.wikantik.page.subsystem.lifecycle.DefaultPageLifecycle;
import com.wikantik.page.subsystem.lifecycle.DefaultPageLockService;
import com.wikantik.page.subsystem.lifecycle.DefaultPageRepository;
import com.wikantik.page.subsystem.lifecycle.PageLifecycle;
import com.wikantik.page.subsystem.lifecycle.PageLockService;
import com.wikantik.page.subsystem.lifecycle.PageRepository;
import com.wikantik.ui.CommandResolver;
import com.wikantik.util.TextUtil;

import java.security.Permission;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;


/**
 * Manages the WikiPages. This class functions as a thin façade that delegates to three
 * internal helpers: {@link PageRepository} (storage access), {@link PageLifecycle}
 * (save orchestration), and {@link PageLockService} (lock state).
 *
 * <p>Phase 5 Checkpoint 3 of the wikantik-main subsystem decomposition extracted
 * all implementation logic into those helpers; this class retains only wiring,
 * the {@code actionPerformed} ACL-update listener, and {@link #changeAcl}.</p>
 *
 * @since 2.0
 */
public class DefaultPageManager implements com.wikantik.api.managers.PageManager {

    private static final Logger LOG = LogManager.getLogger( DefaultPageManager.class );

    private final Engine         engine;
    private final PageRepository repository;
    private final PageLifecycle  lifecycle;
    private final PageLockService lockService;

    // pageSorter is initialized here so the test-seam ctor can skip props parsing
    // and the production ctor can call pageSorter.initialize(props) before building helpers.
    private final PageSorter pageSorter = new PageSorter();

    /**
     * Creates a new PageManager, resolving managers from the engine.
     *
     * @param newEngine Engine instance
     * @param props  Properties to use for initialization
     * @throws NoSuchElementException {@value #PROP_PAGEPROVIDER} property not found on Engine properties
     * @throws WikiException If anything goes wrong, you get this.
     */
    public DefaultPageManager( final Engine newEngine, final Properties props ) throws NoSuchElementException, WikiException {
        this( newEngine, props,
              com.wikantik.page.subsystem.PageSubsystemFactory.buildProvider( newEngine, props ) );
    }

    /**
     * Production constructor used by the engine boot path. Phase 5 Ckpt 2
     * lifted the {@link PageProvider} chain construction into
     * {@link com.wikantik.page.subsystem.PageSubsystemFactory#buildProvider};
     * this constructor receives the pre-built chain and just wires the
     * page-manager state around it.
     *
     * @param newEngine     Engine instance
     * @param props         Properties to use for initialization
     * @param pageProvider  Pre-built {@link PageProvider} chain (already initialized)
     */
    public DefaultPageManager( final Engine newEngine, final Properties props,
                               final PageProvider pageProvider ) throws NoSuchElementException {
        this.engine = newEngine;
        final CommandResolver commandResolver = com.wikantik.core.subsystem.CoreSubsystemBridge.fromLegacyEngine( newEngine ).commandResolver();
        final int expiryTime = TextUtil.parseIntParameter( props.getProperty( PROP_LOCKEXPIRY ), 60 );
        pageSorter.initialize( props );

        this.repository  = new DefaultPageRepository( newEngine, commandResolver, pageProvider, pageSorter, this );
        this.lifecycle   = new DefaultPageLifecycle( newEngine, repository );
        this.lockService = new DefaultPageLockService( newEngine, expiryTime, this );
    }

    /**
     * Package-private test-only constructor that bypasses provider initialization entirely.
     * Accepts a pre-built PageProvider so tests can use mocks without hitting the filesystem.
     *
     * @param newEngine         Engine instance (may be a mock)
     * @param commandResolver   Phase 1 CommandResolver (may be a mock)
     * @param pageProvider      Pre-built PageProvider (may be a mock)
     * @param lockExpiryMinutes Lock expiry time in minutes
     */
    DefaultPageManager( final Engine newEngine,
                        final CommandResolver commandResolver,
                        final PageProvider pageProvider,
                        final int lockExpiryMinutes ) {
        this.engine      = newEngine;
        this.repository  = new DefaultPageRepository( newEngine, commandResolver, pageProvider, pageSorter, this );
        this.lifecycle   = new DefaultPageLifecycle( newEngine, repository );
        this.lockService = new DefaultPageLockService( newEngine, lockExpiryMinutes, this );
    }

    // -------------------------------------------------------------------------
    // Package-private accessors for PageSubsystemFactory (Ckpt 3 wiring)
    // -------------------------------------------------------------------------

    /** Returns the internal {@link PageRepository}. */
    public PageRepository getRepository() { return repository; }

    /** Returns the internal {@link PageLifecycle}. */
    public PageLifecycle getLifecycle() { return lifecycle; }

    /** Returns the internal {@link PageLockService}. */
    public PageLockService getLockService() { return lockService; }

    // -------------------------------------------------------------------------
    // Engine accessor (kept for subclass use and internal ACL listener)
    // -------------------------------------------------------------------------

    protected Engine getEngine() {
        return engine;
    }

    // -------------------------------------------------------------------------
    // PageManager façade — one-liner delegates
    // -------------------------------------------------------------------------

    @Override public PageProvider getProvider()                                                  { return repository.getProvider(); }
    @Override public Collection<Page> getAllPages() throws ProviderException                     { return repository.getAllPages(); }
    @Override public String getPageText( final String n, final int v ) throws ProviderException  { return repository.getPageText( n, v ); }
    @Override public String getPureText( final String p, final int v )                           { return repository.getPureText( p, v ); }
    @Override public String getText( final String p, final int v )                               { return repository.getText( p, v ); }
    @Override public void putPageText( final Page p, final String c ) throws ProviderException   { repository.putPageText( p, c ); }
    @Override public Page getPage( final String p )                                              { return repository.getPage( p ); }
    @Override public Page getPage( final String p, final int v )                                 { return repository.getPage( p, v ); }
    @Override public Page getPageInfo( final String n, final int v ) throws ProviderException    { return repository.getPageInfo( n, v ); }
    @Override public <T extends Page> List<T> getVersionHistory( final String n )                { return repository.getVersionHistory( n ); }
    @Override public String getCurrentProvider()                                                 { return repository.getCurrentProvider(); }
    @Override public String getProviderDescription()                                             { return repository.getProviderDescription(); }
    @Override public int getTotalPageCount()                                                     { return repository.getTotalPageCount(); }
    @Override public Set<Page> getRecentChanges()                                               { return repository.getRecentChanges(); }
    @Override public Set<Page> getRecentChanges( final Date since )                             { return repository.getRecentChanges( since ); }
    @Override public boolean pageExists( final String n ) throws ProviderException               { return repository.pageExists( n ); }
    @Override public boolean pageExists( final String n, final int v ) throws ProviderException  { return repository.pageExists( n, v ); }
    @Override public boolean wikiPageExists( final String p )                                    { return repository.wikiPageExists( p ); }
    @Override public boolean wikiPageExists( final String p, final int v ) throws ProviderException { return repository.wikiPageExists( p, v ); }
    @Override public void deleteVersion( final Page p ) throws ProviderException                 { repository.deleteVersion( p ); }
    @Override public void deletePage( final String n ) throws ProviderException                  { repository.deletePage( n ); }
    @Override public void deletePage( final Page p ) throws ProviderException                    { repository.deletePage( p ); }
    @Override public PageSorter getPageSorter()                                                  { return repository.getPageSorter(); }

    @Override public void saveText( final Context c, final String t ) throws WikiException       { lifecycle.saveText( c, t ); }

    @Override public PageLock lockPage( final Page p, final String u )                           { return lockService.lockPage( p, u ); }
    @Override public void unlockPage( final PageLock l )                                         { lockService.unlockPage( l ); }
    @Override public PageLock getCurrentLock( final Page p )                                     { return lockService.getCurrentLock( p ); }
    @Override public List<PageLock> getActiveLocks()                                             { return lockService.getActiveLocks(); }

    // -------------------------------------------------------------------------
    // Event helper (retained for subclass / listener registration on *this*)
    // -------------------------------------------------------------------------

    protected final void fireEvent( final int type, final String pagename ) {
        if ( WikiEventManager.isListening( this ) ) {
            com.wikantik.core.subsystem.CoreSubsystemBridge.fromLegacyEngine( engine )
                .eventBus().fireEvent( this, new WikiPageEvent( engine, type, pagename ) );
        }
    }

    // -------------------------------------------------------------------------
    // actionPerformed — ACL rename listener
    // -------------------------------------------------------------------------

    /**
     * Listens for {@link WikiSecurityEvent#PROFILE_NAME_CHANGED} events. If a user
     * profile's name changes, each page ACL is inspected. If an entry contains a name
     * that has changed, it is replaced with the new one.
     */
    @Override
    public void actionPerformed( final WikiEvent event ) {
        if ( !( event instanceof WikiSecurityEvent se ) ) {
            return;
        }

        if ( se.getType() == WikiSecurityEvent.PROFILE_NAME_CHANGED ) {
            final UserProfile[] profiles = (UserProfile[]) se.getTarget();
            final Principal[] oldPrincipals = { new WikiPrincipal( profiles[ 0 ].getLoginName() ),
                                                new WikiPrincipal( profiles[ 0 ].getFullname() ),
                                                new WikiPrincipal( profiles[ 0 ].getWikiName() ) };
            final Principal newPrincipal = new WikiPrincipal( profiles[ 1 ].getFullname() );

            // Examine each page ACL
            try {
                int pagesChanged = 0;
                final Collection<Page> pages = getAllPages();
                for ( final Page page : pages ) {
                    final boolean aclChanged = changeAcl( page, oldPrincipals, newPrincipal );
                    if ( aclChanged ) {
                        // If the Acl needed changing, change it now
                        try {
                            com.wikantik.auth.subsystem.AuthSubsystemBridge.fromLegacyEngine( engine ).aclManager().setPermissions( page, page.getAcl() );
                        } catch ( final WikiSecurityException e ) {
                            LOG.error( "Could not change page ACL for page {}: {}", page.getName(), e.getMessage(), e );
                        }
                        pagesChanged++;
                    }
                }
                LOG.info( "Profile name change for '{}' caused {} page ACLs to change also.", newPrincipal, pagesChanged );
            } catch ( final ProviderException e ) {
                // Oooo! This is really bad...
                LOG.error( "Could not change user name in Page ACLs because of Provider error:{}", e.getMessage(), e );
            }
        }
    }

    /**
     * For a single wiki page, replaces all Acl entries matching a supplied array of Principals
     * with a new Principal.
     *
     * @param page          the wiki page whose Acl is to be modified
     * @param oldPrincipals an array of Principals to replace
     * @param newPrincipal  the Principal that should receive the old Principals' permissions
     * @return {@code true} if the Acl was actually changed; {@code false} otherwise
     */
    protected boolean changeAcl( final Page page, final Principal[] oldPrincipals, final Principal newPrincipal ) {
        final Acl acl = page.getAcl();
        boolean pageChanged = false;
        if ( acl != null ) {
            final Enumeration<AclEntry> entries = acl.aclEntries();
            final Collection<AclEntry> entriesToAdd    = new ArrayList<>();
            final Collection<AclEntry> entriesToRemove = new ArrayList<>();
            while ( entries.hasMoreElements() ) {
                final AclEntry entry = entries.nextElement();
                if ( ArrayUtils.contains( oldPrincipals, entry.getPrincipal() ) ) {
                    // Create new entry
                    final AclEntry newEntry = Wiki.acls().entry();
                    newEntry.setPrincipal( newPrincipal );
                    final Enumeration<Permission> permissions = entry.permissions();
                    while ( permissions.hasMoreElements() ) {
                        final Permission permission = permissions.nextElement();
                        newEntry.addPermission( permission );
                    }
                    pageChanged = true;
                    entriesToRemove.add( entry );
                    entriesToAdd.add( newEntry );
                }
            }
            for ( final AclEntry entry : entriesToRemove ) {
                acl.removeEntry( entry );
            }
            for ( final AclEntry entry : entriesToAdd ) {
                acl.addEntry( entry );
            }
        }
        return pageChanged;
    }
}
