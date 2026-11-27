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
package com.wikantik.providers;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.search.QueryItem;
import com.wikantik.api.search.SearchResult;
import com.wikantik.api.spi.Wiki;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * A small versioned in-memory {@link PageProvider} for testing the {@link CachingProvider} in front of it. Tests
 * change its content directly ({@link #outOfBandPut}, {@link #outOfBandRemove}) to model a change the cache
 * has not been told about, and read its call counters to see what the cache did or did not ask for.
 */
public class MapPageProvider implements PageProvider {

    /** Versions of every page, oldest first. */
    final Map< String, List< String > > versions = new TreeMap<>();
    private final Map< String, Long > stamps = new TreeMap<>();
    private long clock = 1_000_000L;
    private Engine engine;

    public int textReads;
    public int infoReads;
    public int historyReads;
    public int existsWithVersionCalls;
    /** When set, {@link #getPageInfo} fails with a {@link ProviderException}. */
    public boolean failInfo;

    @Override
    public void initialize( final Engine engine, final Properties props ) {
        this.engine = engine;
    }

    /** Stores a new version without going through the cache. */
    public void outOfBandPut( final String name, final String text ) {
        versions.computeIfAbsent( name, k -> new ArrayList<>() ).add( text );
        stamps.put( name, clock += 1000L );
    }

    /** Removes a page without going through the cache. */
    public void outOfBandRemove( final String name ) {
        versions.remove( name );
        stamps.remove( name );
    }

    private Page build( final String name, final int version ) {
        final Page p = Wiki.contents().page( engine, name );
        p.setVersion( version );
        p.setAuthor( "author" + version );
        p.setLastModified( new Date( stamps.get( name ) - ( versions.get( name ).size() - version ) ) );
        return p;
    }

    @Override
    public String getProviderInfo() {
        return "MapPageProvider";
    }

    @Override
    public void putPageText( final Page page, final String text ) {
        outOfBandPut( page.getName(), text );
    }

    @Override
    public boolean pageExists( final String page ) {
        return versions.containsKey( page );
    }

    @Override
    public boolean pageExists( final String page, final int version ) {
        existsWithVersionCalls++;
        final List< String > v = versions.get( page );
        return v != null && ( version == LATEST_VERSION || ( version >= 1 && version <= v.size() ) );
    }

    @Override
    public Collection< SearchResult > findPages( final QueryItem[] query ) {
        return List.of();
    }

    @Override
    public Page getPageInfo( final String page, final int version ) throws ProviderException {
        infoReads++;
        if( failInfo ) {
            throw new ProviderException( "info unavailable" );
        }
        final List< String > v = versions.get( page );
        if( v == null ) {
            return null;
        }
        final int real = version == LATEST_VERSION ? v.size() : version;
        return real >= 1 && real <= v.size() ? build( page, real ) : null;
    }

    @Override
    public Collection< Page > getAllPages() {
        final List< Page > all = new ArrayList<>();
        for( final Map.Entry< String, List< String > > e : versions.entrySet() ) {
            all.add( build( e.getKey(), e.getValue().size() ) );
        }
        return all;
    }

    @Override
    public Collection< Page > getAllChangedSince( final Date date ) {
        return getAllPages();
    }

    @Override
    public int getPageCount() {
        return versions.size();
    }

    @Override
    public List< Page > getVersionHistory( final String page ) {
        historyReads++;
        final List< Page > history = new ArrayList<>();
        final List< String > v = versions.get( page );
        if( v != null ) {
            for( int i = v.size(); i >= 1; i-- ) {
                history.add( build( page, i ) );
            }
        }
        return history;
    }

    @Override
    public String getPageText( final String page, final int version ) throws ProviderException {
        textReads++;
        final List< String > v = versions.get( page );
        if( v == null ) {
            return null;
        }
        final int real = version == LATEST_VERSION ? v.size() : version;
        if( real < 1 || real > v.size() ) {
            throw new ProviderException( "no version " + version );
        }
        return v.get( real - 1 );
    }

    @Override
    public void deleteVersion( final String pageName, final int version ) {
        final List< String > v = versions.get( pageName );
        if( v == null ) {
            return;
        }
        if( version == LATEST_VERSION || version == v.size() ) {
            v.remove( v.size() - 1 );
            if( v.isEmpty() ) {
                versions.remove( pageName );
                stamps.remove( pageName );
            }
        } else {
            v.set( version - 1, "" );
        }
    }

    @Override
    public void deletePage( final String pageName ) {
        outOfBandRemove( pageName );
    }

    @Override
    public void movePage( final String from, final String to ) {
        final List< String > v = versions.remove( from );
        final Long stamp = stamps.remove( from );
        if( v != null ) {
            versions.put( to, v );
            stamps.put( to, stamp );
        }
    }
}
