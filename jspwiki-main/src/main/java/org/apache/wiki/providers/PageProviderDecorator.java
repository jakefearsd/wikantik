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

import org.apache.wiki.api.core.Engine;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.exceptions.NoRequiredPropertyException;
import org.apache.wiki.api.exceptions.ProviderException;
import org.apache.wiki.api.providers.PageProvider;
import org.apache.wiki.api.search.QueryItem;
import org.apache.wiki.api.search.SearchResult;

import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * Abstract base class for PageProvider decorators.
 * <p>
 * This class implements the Decorator pattern, allowing cross-cutting concerns
 * (caching, logging, metrics, access control) to be added to any PageProvider
 * implementation without modifying the original provider.
 * <p>
 * Subclasses can override individual methods to add their specific behavior
 * before and/or after delegating to the wrapped provider.
 * <p>
 * Example usage:
 * <pre>
 * PageProvider provider = new CachingPageProviderDecorator(
 *     new LoggingPageProviderDecorator(
 *         new VersioningFileProvider()
 *     )
 * );
 * </pre>
 *
 * @since 2.12.3
 */
public abstract class PageProviderDecorator implements PageProvider {

    protected final PageProvider delegate;

    /**
     * Creates a decorator wrapping the given provider.
     *
     * @param delegate the provider to wrap (must not be null)
     * @throws NullPointerException if delegate is null
     */
    protected PageProviderDecorator( final PageProvider delegate ) {
        this.delegate = Objects.requireNonNull( delegate, "Delegate provider must not be null" );
    }

    /**
     * Returns the wrapped provider.
     *
     * @return the delegate provider
     */
    public PageProvider getDelegate() {
        return delegate;
    }

    /**
     * Returns the innermost real provider, unwrapping all decorators.
     *
     * @return the real (non-decorator) provider at the bottom of the chain
     */
    public PageProvider getRealProvider() {
        if ( delegate instanceof PageProviderDecorator decorator ) {
            return decorator.getRealProvider();
        }
        return delegate;
    }

    @Override
    public void initialize( final Engine engine, final Properties properties )
            throws NoRequiredPropertyException, IOException {
        delegate.initialize( engine, properties );
    }

    @Override
    public String getProviderInfo() {
        return delegate.getProviderInfo();
    }

    @Override
    public void putPageText( final Page page, final String text ) throws ProviderException {
        delegate.putPageText( page, text );
    }

    @Override
    public boolean pageExists( final String page ) {
        return delegate.pageExists( page );
    }

    @Override
    public boolean pageExists( final String page, final int version ) {
        return delegate.pageExists( page, version );
    }

    @Override
    public Collection<SearchResult> findPages( final QueryItem[] query ) {
        return delegate.findPages( query );
    }

    @Override
    public Page getPageInfo( final String page, final int version ) throws ProviderException {
        return delegate.getPageInfo( page, version );
    }

    @Override
    public Collection<Page> getAllPages() throws ProviderException {
        return delegate.getAllPages();
    }

    @Override
    public Collection<Page> getAllChangedSince( final Date date ) {
        return delegate.getAllChangedSince( date );
    }

    @Override
    public int getPageCount() throws ProviderException {
        return delegate.getPageCount();
    }

    @Override
    public List<Page> getVersionHistory( final String page ) throws ProviderException {
        return delegate.getVersionHistory( page );
    }

    @Override
    public String getPageText( final String page, final int version ) throws ProviderException {
        return delegate.getPageText( page, version );
    }

    @Override
    public void deleteVersion( final String page, final int version ) throws ProviderException {
        delegate.deleteVersion( page, version );
    }

    @Override
    public void deletePage( final String page ) throws ProviderException {
        delegate.deletePage( page );
    }

    @Override
    public void movePage( final String from, final String to ) throws ProviderException {
        delegate.movePage( from, to );
    }
}
