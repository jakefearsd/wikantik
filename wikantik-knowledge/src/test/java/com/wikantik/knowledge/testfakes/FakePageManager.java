package com.wikantik.knowledge.testfakes;

import com.wikantik.api.core.Acl;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageLock;
import com.wikantik.api.pages.PageSorter;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.event.WikiEvent;

import java.util.*;

import static org.mockito.Mockito.*;

/**
 * Minimal in-memory PageManager for unit tests.
 * Only implements the methods used by DefaultContextRetrievalService.getPage().
 */
public class FakePageManager implements PageManager {

    private final Map< String, StoredPage > pages = new LinkedHashMap<>();

    public void addPage( final String name, final String text, final String author, final Date lastModified ) {
        pages.put( name, new StoredPage( name, text, author, lastModified ) );
    }

    @Override
    public Page getPage( final String pagereq ) {
        final StoredPage sp = pages.get( pagereq );
        return sp == null ? null : sp.toPage( PageProvider.LATEST_VERSION );
    }

    @Override
    public Page getPage( final String pagereq, final int version ) {
        final StoredPage sp = pages.get( pagereq );
        return sp == null ? null : sp.toPage( version );
    }

    @Override
    public String getPureText( final String page, final int version ) {
        final StoredPage sp = pages.get( page );
        return sp == null ? "" : sp.text;
    }

    @Override
    public Collection< Page > getAllPages() throws ProviderException {
        final List< Page > result = new ArrayList<>();
        for ( final StoredPage sp : pages.values() ) {
            result.add( sp.toPage( PageProvider.LATEST_VERSION ) );
        }
        return result;
    }

    // ------------------------------------------------------------------ stubs

    @Override public PageProvider getProvider() { throw new UnsupportedOperationException(); }
    @Override public String getPageText( String pageName, int version ) throws ProviderException { throw new UnsupportedOperationException(); }
    @Override public String getText( String page, int version ) { throw new UnsupportedOperationException(); }
    @Override public void saveText( Context context, String text ) throws WikiException { throw new UnsupportedOperationException(); }
    @Override public void putPageText( Page page, String content ) throws ProviderException { throw new UnsupportedOperationException(); }
    @Override public PageLock lockPage( Page page, String user ) { throw new UnsupportedOperationException(); }
    @Override public void unlockPage( PageLock lock ) { throw new UnsupportedOperationException(); }
    @Override public PageLock getCurrentLock( Page page ) { throw new UnsupportedOperationException(); }
    @Override public List< PageLock > getActiveLocks() { throw new UnsupportedOperationException(); }
    @Override public Page getPageInfo( String pageName, int version ) throws ProviderException { throw new UnsupportedOperationException(); }
    @Override public < T extends Page > List< T > getVersionHistory( String pageName ) { throw new UnsupportedOperationException(); }
    @Override public String getCurrentProvider() { throw new UnsupportedOperationException(); }
    @Override public String getProviderDescription() { throw new UnsupportedOperationException(); }
    @Override public int getTotalPageCount() { throw new UnsupportedOperationException(); }
    @Override public Set< Page > getRecentChanges() { throw new UnsupportedOperationException(); }
    @Override public Set< Page > getRecentChanges( Date since ) { throw new UnsupportedOperationException(); }
    @Override public boolean pageExists( String pageName ) throws ProviderException { throw new UnsupportedOperationException(); }
    @Override public boolean pageExists( String pageName, int version ) throws ProviderException { throw new UnsupportedOperationException(); }
    @Override public boolean wikiPageExists( String page ) { throw new UnsupportedOperationException(); }
    @Override public boolean wikiPageExists( String page, int version ) throws ProviderException { throw new UnsupportedOperationException(); }
    @Override public void deleteVersion( Page page ) throws ProviderException { throw new UnsupportedOperationException(); }
    @Override public void deletePage( String pageName ) throws ProviderException { throw new UnsupportedOperationException(); }
    @Override public void deletePage( Page page ) throws ProviderException { throw new UnsupportedOperationException(); }
    @Override public PageSorter getPageSorter() { throw new UnsupportedOperationException(); }
    @Override public void actionPerformed( WikiEvent event ) { throw new UnsupportedOperationException(); }

    // ------------------------------------------------------------------ inner

    private static final class StoredPage {
        final String name;
        final String text;
        final String author;
        final Date lastModified;

        StoredPage( final String name, final String text, final String author, final Date lastModified ) {
            this.name = name;
            this.text = text;
            this.author = author;
            this.lastModified = lastModified;
        }

        Page toPage( final int version ) {
            final Page p = mock( Page.class );
            when( p.getName() ).thenReturn( name );
            when( p.getAuthor() ).thenReturn( author );
            when( p.getLastModified() ).thenReturn( lastModified );
            when( p.getVersion() ).thenReturn( version );
            return p;
        }
    }
}
