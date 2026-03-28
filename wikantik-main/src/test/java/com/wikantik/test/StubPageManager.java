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
package com.wikantik.test;

import com.wikantik.api.core.Acl;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.pages.PageLock;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.event.WikiEvent;
import com.wikantik.pages.PageManager;
import com.wikantik.pages.PageSorter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stub implementation of {@link PageManager} for unit testing.
 *
 * <p>Stores pages in a ConcurrentHashMap — no filesystem, no providers,
 * no engine required. This allows testing components that depend on
 * PageManager without the overhead of a full WikiEngine initialization.
 *
 * <p>Usage:
 * <pre>{@code
 * StubPageManager pm = new StubPageManager();
 * pm.savePage("TestPage", "# Hello\nWorld.");
 * ReadPageTool tool = new ReadPageTool(pm, stubRegistry);
 * }</pre>
 *
 * @since 3.0.7
 */
public class StubPageManager implements PageManager {

    private static final long LOCK_DURATION_MINUTES = 60;

    private final Map< String, String > pageTexts = new ConcurrentHashMap<>();
    private final Map< String, StubPage > pageInfos = new ConcurrentHashMap<>();
    private final Map< String, PageLock > locks = new ConcurrentHashMap<>();

    /**
     * Convenience method for tests: save a page with content.
     */
    public void savePage( final String name, final String text ) {
        pageTexts.put( name, text );
        pageInfos.computeIfAbsent( name, k -> new StubPage( name ) )
                .setLastModified( new Date() );
    }

    // --- PageManager implementation ---

    @Override public PageProvider getProvider() { return null; }

    @Override
    public Collection< Page > getAllPages() {
        return Collections.unmodifiableCollection( pageInfos.values() );
    }

    @Override
    public String getPageText( final String pageName, final int version ) {
        return pageTexts.getOrDefault( pageName, null );
    }

    @Override
    public String getPureText( final String page, final int version ) {
        return pageTexts.getOrDefault( page, "" );
    }

    @Override
    public String getText( final String page, final int version ) {
        return pageTexts.getOrDefault( page, "" );
    }

    @Override public void saveText( final Context context, final String text ) { }
    @Override public void putPageText( final Page page, final String content ) {
        pageTexts.put( page.getName(), content );
        pageInfos.computeIfAbsent( page.getName(), k -> new StubPage( page.getName() ) );
    }

    @Override
    public PageLock lockPage( final Page page, final String user ) {
        final PageLock existing = locks.get( page.getName() );
        if ( existing != null && !existing.isExpired() && !existing.getLocker().equals( user ) ) {
            return null;
        }
        final Date now = new Date();
        final Date expiry = new Date( now.getTime() + LOCK_DURATION_MINUTES * 60 * 1000 );
        final PageLock lock = new PageLock( page, user, now, expiry );
        locks.put( page.getName(), lock );
        return lock;
    }

    @Override
    public void unlockPage( final PageLock lock ) {
        locks.remove( lock.getPage() );
    }

    @Override
    public PageLock getCurrentLock( final Page page ) {
        final PageLock lock = locks.get( page.getName() );
        if ( lock != null && lock.isExpired() ) {
            locks.remove( page.getName() );
            return null;
        }
        return lock;
    }

    @Override public List< PageLock > getActiveLocks() { return new ArrayList<>( locks.values() ); }

    @Override
    public Page getPage( final String pagereq ) {
        return pageInfos.get( pagereq );
    }

    @Override
    public Page getPage( final String pagereq, final int version ) {
        return pageInfos.get( pagereq );
    }

    @Override
    public Page getPageInfo( final String pageName, final int version ) {
        return pageInfos.get( pageName );
    }

    @Override
    @SuppressWarnings( "unchecked" )
    public < T extends Page > List< T > getVersionHistory( final String pageName ) {
        final StubPage page = pageInfos.get( pageName );
        if ( page == null ) {
            return List.of();
        }
        return ( List< T > ) List.of( page );
    }
    @Override public String getCurrentProvider() { return "StubPageManager"; }
    @Override public String getProviderDescription() { return "In-memory stub for testing"; }

    @Override
    public int getTotalPageCount() {
        return pageInfos.size();
    }

    @Override public Set< Page > getRecentChanges() { return new LinkedHashSet<>( pageInfos.values() ); }
    @Override public Set< Page > getRecentChanges( final Date since ) { return new LinkedHashSet<>( pageInfos.values() ); }

    @Override
    public boolean pageExists( final String pageName ) {
        return pageInfos.containsKey( pageName );
    }

    @Override
    public boolean pageExists( final String pageName, final int version ) {
        return pageInfos.containsKey( pageName );
    }

    @Override public boolean wikiPageExists( final String page ) { return pageInfos.containsKey( page ); }
    @Override public boolean wikiPageExists( final String page, final int version ) { return pageInfos.containsKey( page ); }

    @Override public void deleteVersion( final Page page ) { }

    @Override
    public void deletePage( final String pageName ) {
        pageTexts.remove( pageName );
        pageInfos.remove( pageName );
    }

    @Override
    public void deletePage( final Page page ) {
        deletePage( page.getName() );
    }

    @Override public PageSorter getPageSorter() { return new PageSorter(); }

    @Override public void actionPerformed( final WikiEvent event ) { }

    /**
     * Simple in-memory Page implementation for the stub.
     */
    public static class StubPage implements Page {
        private final String name;
        private String author;
        private Date lastModified = new Date();
        private int version = 1;
        private final Map< String, Object > attributes = new HashMap<>();

        public StubPage( final String name ) {
            this.name = name;
        }

        @Override public String getName() { return name; }
        @Override public String getWiki() { return "test"; }
        @Override public Date getLastModified() { return lastModified; }
        @Override public void setLastModified( final Date date ) { this.lastModified = date; }
        @Override public int getVersion() { return version; }
        @Override public void setVersion( final int version ) { this.version = version; }
        @Override public long getSize() { return 0; }
        @Override public void setSize( final long size ) { }
        @Override public String getAuthor() { return author; }
        @Override public void setAuthor( final String author ) { this.author = author; }
        @Override public void invalidateMetadata() { }
        @Override public boolean hasMetadata() { return !attributes.isEmpty(); }
        @Override public void setHasMetadata() { }

        @Override
        @SuppressWarnings("unchecked")
        public < T > T getAttribute( final String key ) {
            return ( T ) attributes.get( key );
        }

        @Override
        public void setAttribute( final String key, final Object attribute ) {
            attributes.put( key, attribute );
        }

        @Override public Map< String, Object > getAttributes() { return attributes; }
        @Override @SuppressWarnings("unchecked")
        public < T > T removeAttribute( final String key ) { return ( T ) attributes.remove( key ); }
        @Override public Acl getAcl() { return null; }
        @Override public void setAcl( final Acl acl ) { }

        @Override
        public Page clone() {
            final StubPage clone = new StubPage( name );
            clone.author = author;
            clone.lastModified = lastModified;
            clone.version = version;
            clone.attributes.putAll( attributes );
            return clone;
        }

        @Override
        public int compareTo( final Page o ) {
            return name.compareTo( o.getName() );
        }
    }
}
