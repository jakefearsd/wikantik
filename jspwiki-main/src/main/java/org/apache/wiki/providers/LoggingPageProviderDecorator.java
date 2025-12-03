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
import java.util.Properties;

/**
 * A PageProvider decorator that logs all provider operations.
 * <p>
 * This decorator is useful for debugging and monitoring provider activity.
 * It logs method calls with their parameters and results at DEBUG level,
 * and errors at ERROR level.
 * <p>
 * Example usage:
 * <pre>
 * PageProvider provider = new LoggingPageProviderDecorator(
 *     new VersioningFileProvider()
 * );
 * </pre>
 *
 * @since 2.12.3
 */
public class LoggingPageProviderDecorator extends PageProviderDecorator {

    private static final Logger LOG = LogManager.getLogger( LoggingPageProviderDecorator.class );

    /**
     * Creates a logging decorator wrapping the given provider.
     *
     * @param delegate the provider to wrap
     */
    public LoggingPageProviderDecorator( final PageProvider delegate ) {
        super( delegate );
    }

    @Override
    public void initialize( final Engine engine, final Properties properties )
            throws NoRequiredPropertyException, IOException {
        LOG.debug( "initialize() called" );
        final long start = System.nanoTime();
        try {
            super.initialize( engine, properties );
            LOG.debug( "initialize() completed in {} ms", (System.nanoTime() - start) / 1_000_000.0 );
        } catch ( final NoRequiredPropertyException | IOException e ) {
            LOG.error( "initialize() failed", e );
            throw e;
        }
    }

    @Override
    public String getProviderInfo() {
        LOG.debug( "getProviderInfo() called" );
        return "Logging decorator for: " + super.getProviderInfo();
    }

    @Override
    public void putPageText( final Page page, final String text ) throws ProviderException {
        LOG.debug( "putPageText( page={}, textLength={} )", page.getName(), text != null ? text.length() : 0 );
        final long start = System.nanoTime();
        try {
            super.putPageText( page, text );
            LOG.debug( "putPageText() completed in {} ms", (System.nanoTime() - start) / 1_000_000.0 );
        } catch ( final ProviderException e ) {
            LOG.error( "putPageText() failed for page {}", page.getName(), e );
            throw e;
        }
    }

    @Override
    public boolean pageExists( final String page ) {
        LOG.debug( "pageExists( page={} )", page );
        final boolean result = super.pageExists( page );
        LOG.debug( "pageExists() returned {}", result );
        return result;
    }

    @Override
    public boolean pageExists( final String page, final int version ) {
        LOG.debug( "pageExists( page={}, version={} )", page, version );
        final boolean result = super.pageExists( page, version );
        LOG.debug( "pageExists() returned {}", result );
        return result;
    }

    @Override
    public Collection<SearchResult> findPages( final QueryItem[] query ) {
        LOG.debug( "findPages( queryItems={} )", query != null ? query.length : 0 );
        final long start = System.nanoTime();
        final Collection<SearchResult> result = super.findPages( query );
        LOG.debug( "findPages() returned {} results in {} ms",
                result != null ? result.size() : 0,
                (System.nanoTime() - start) / 1_000_000.0 );
        return result;
    }

    @Override
    public Page getPageInfo( final String page, final int version ) throws ProviderException {
        LOG.debug( "getPageInfo( page={}, version={} )", page, version );
        final long start = System.nanoTime();
        try {
            final Page result = super.getPageInfo( page, version );
            LOG.debug( "getPageInfo() completed in {} ms", (System.nanoTime() - start) / 1_000_000.0 );
            return result;
        } catch ( final ProviderException e ) {
            LOG.error( "getPageInfo() failed for page {} version {}", page, version, e );
            throw e;
        }
    }

    @Override
    public Collection<Page> getAllPages() throws ProviderException {
        LOG.debug( "getAllPages() called" );
        final long start = System.nanoTime();
        try {
            final Collection<Page> result = super.getAllPages();
            LOG.debug( "getAllPages() returned {} pages in {} ms",
                    result != null ? result.size() : 0,
                    (System.nanoTime() - start) / 1_000_000.0 );
            return result;
        } catch ( final ProviderException e ) {
            LOG.error( "getAllPages() failed", e );
            throw e;
        }
    }

    @Override
    public Collection<Page> getAllChangedSince( final Date date ) {
        LOG.debug( "getAllChangedSince( date={} )", date );
        final long start = System.nanoTime();
        final Collection<Page> result = super.getAllChangedSince( date );
        LOG.debug( "getAllChangedSince() returned {} pages in {} ms",
                result != null ? result.size() : 0,
                (System.nanoTime() - start) / 1_000_000.0 );
        return result;
    }

    @Override
    public int getPageCount() throws ProviderException {
        LOG.debug( "getPageCount() called" );
        try {
            final int result = super.getPageCount();
            LOG.debug( "getPageCount() returned {}", result );
            return result;
        } catch ( final ProviderException e ) {
            LOG.error( "getPageCount() failed", e );
            throw e;
        }
    }

    @Override
    public List<Page> getVersionHistory( final String page ) throws ProviderException {
        LOG.debug( "getVersionHistory( page={} )", page );
        final long start = System.nanoTime();
        try {
            final List<Page> result = super.getVersionHistory( page );
            LOG.debug( "getVersionHistory() returned {} versions in {} ms",
                    result != null ? result.size() : 0,
                    (System.nanoTime() - start) / 1_000_000.0 );
            return result;
        } catch ( final ProviderException e ) {
            LOG.error( "getVersionHistory() failed for page {}", page, e );
            throw e;
        }
    }

    @Override
    public String getPageText( final String page, final int version ) throws ProviderException {
        LOG.debug( "getPageText( page={}, version={} )", page, version );
        final long start = System.nanoTime();
        try {
            final String result = super.getPageText( page, version );
            LOG.debug( "getPageText() returned {} chars in {} ms",
                    result != null ? result.length() : 0,
                    (System.nanoTime() - start) / 1_000_000.0 );
            return result;
        } catch ( final ProviderException e ) {
            LOG.error( "getPageText() failed for page {} version {}", page, version, e );
            throw e;
        }
    }

    @Override
    public void deleteVersion( final String page, final int version ) throws ProviderException {
        LOG.debug( "deleteVersion( page={}, version={} )", page, version );
        final long start = System.nanoTime();
        try {
            super.deleteVersion( page, version );
            LOG.debug( "deleteVersion() completed in {} ms", (System.nanoTime() - start) / 1_000_000.0 );
        } catch ( final ProviderException e ) {
            LOG.error( "deleteVersion() failed for page {} version {}", page, version, e );
            throw e;
        }
    }

    @Override
    public void deletePage( final String page ) throws ProviderException {
        LOG.debug( "deletePage( page={} )", page );
        final long start = System.nanoTime();
        try {
            super.deletePage( page );
            LOG.debug( "deletePage() completed in {} ms", (System.nanoTime() - start) / 1_000_000.0 );
        } catch ( final ProviderException e ) {
            LOG.error( "deletePage() failed for page {}", page, e );
            throw e;
        }
    }

    @Override
    public void movePage( final String from, final String to ) throws ProviderException {
        LOG.debug( "movePage( from={}, to={} )", from, to );
        final long start = System.nanoTime();
        try {
            super.movePage( from, to );
            LOG.debug( "movePage() completed in {} ms", (System.nanoTime() - start) / 1_000_000.0 );
        } catch ( final ProviderException e ) {
            LOG.error( "movePage() failed for {} -> {}", from, to, e );
            throw e;
        }
    }
}
