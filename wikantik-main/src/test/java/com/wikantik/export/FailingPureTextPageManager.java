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
package com.wikantik.export;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSorter;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.event.WikiEvent;

/**
 * Delegating {@link PageManager} test double that throws a {@link RuntimeException} from
 * {@link #getPureText(Page)} for one named page, so {@code ExportService} tests can exercise its
 * per-page converter-failure fallback (spec: raw markdown written, warning recorded) without
 * needing a page whose real content is unparseable.
 */
final class FailingPureTextPageManager implements PageManager {

    private final PageManager delegate;
    private final String failingPageName;

    FailingPureTextPageManager( final PageManager delegate, final String failingPageName ) {
        this.delegate = delegate;
        this.failingPageName = failingPageName;
    }

    @Override
    public String getPureText( final Page page ) {
        if ( page != null && failingPageName.equals( page.getName() ) ) {
            throw new RuntimeException( "synthetic getPureText failure for test" );
        }
        return delegate.getPureText( page );
    }

    @Override public PageProvider getProvider() { return delegate.getProvider(); }
    @Override public Collection< Page > getAllPages() throws ProviderException { return delegate.getAllPages(); }
    @Override public String getPageText( final String pageName, final int version ) throws ProviderException {
        return delegate.getPageText( pageName, version );
    }
    @Override public String getPureText( final String page, final int version ) { return delegate.getPureText( page, version ); }
    @Override public String getText( final String page, final int version ) { return delegate.getText( page, version ); }
    @Override public void saveText( final Context context, final String text ) throws WikiException { delegate.saveText( context, text ); }
    @Override public void putPageText( final Page page, final String content ) throws ProviderException { delegate.putPageText( page, content ); }
    @Override public Page getPage( final String pagereq ) { return delegate.getPage( pagereq ); }
    @Override public Page getPage( final String pagereq, final int version ) { return delegate.getPage( pagereq, version ); }
    @Override public Page getPageInfo( final String pageName, final int version ) throws ProviderException {
        return delegate.getPageInfo( pageName, version );
    }
    @Override public < T extends Page > List< T > getVersionHistory( final String pageName ) { return delegate.getVersionHistory( pageName ); }
    @Override public String getCurrentProvider() { return delegate.getCurrentProvider(); }
    @Override public String getProviderDescription() { return delegate.getProviderDescription(); }
    @Override public int getTotalPageCount() { return delegate.getTotalPageCount(); }
    @Override public Set< Page > getRecentChanges() { return delegate.getRecentChanges(); }
    @Override public Set< Page > getRecentChanges( final Date since ) { return delegate.getRecentChanges( since ); }
    @Override public boolean pageExists( final String pageName ) throws ProviderException { return delegate.pageExists( pageName ); }
    @Override public boolean pageExists( final String pageName, final int version ) throws ProviderException {
        return delegate.pageExists( pageName, version );
    }
    @Override public boolean wikiPageExists( final String page ) { return delegate.wikiPageExists( page ); }
    @Override public boolean wikiPageExists( final String page, final int version ) throws ProviderException {
        return delegate.wikiPageExists( page, version );
    }
    @Override public void deleteVersion( final Page page ) throws ProviderException { delegate.deleteVersion( page ); }
    @Override public void deletePage( final String pageName ) throws ProviderException { delegate.deletePage( pageName ); }
    @Override public void deletePage( final Page page ) throws ProviderException { delegate.deletePage( page ); }
    @Override public PageSorter getPageSorter() { return delegate.getPageSorter(); }
    @Override public void actionPerformed( final WikiEvent event ) { delegate.actionPerformed( event ); }
}
