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
package com.wikantik.rest;

import com.google.gson.JsonObject;

import com.wikantik.cache.CacheInfo;
import com.wikantik.cache.CachingManager;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cache statistics and flush routes of {@link AdminContentResource}. */
final class CacheAdminHandlers {

    private static final Logger LOG = LogManager.getLogger( CacheAdminHandlers.class );

    static final String[] CACHE_NAMES = {
        CachingManager.CACHE_PAGES,
        CachingManager.CACHE_PAGES_TEXT,
        CachingManager.CACHE_PAGES_HISTORY,
        CachingManager.CACHE_ATTACHMENTS,
        CachingManager.CACHE_ATTACHMENTS_COLLECTION,
        CachingManager.CACHE_ATTACHMENTS_DYNAMIC,
        CachingManager.CACHE_DOCUMENTS,
        CachingManager.CACHE_HTML,
    };

    private final AdminContentResource resource;

    CacheAdminHandlers( final AdminContentResource resource ) {
        this.resource = resource;
    }

    /** Per-cache statistics block of the {@code stats} route. */
    List< Map< String, Object > > cacheStats() {
        final CachingManager cm = resource.getSubsystems().core().cachingManager();
        final List< Map< String, Object > > cacheStats = new ArrayList<>();
        if ( cm != null ) {
            for ( final String cacheName : CACHE_NAMES ) {
                if ( cm.enabled( cacheName ) ) {
                    final CacheInfo info = cm.info( cacheName );
                    if ( info != null ) {
                        final Map< String, Object > c = new LinkedHashMap<>();
                        c.put( "name", shortCacheName( cacheName ) );
                        c.put( "fullName", cacheName );
                        c.put( "size", cm.keys( cacheName ).size() );
                        c.put( "maxSize", info.getMaxElementsAllowed() );
                        c.put( "hits", info.getHits() );
                        c.put( "misses", info.getMisses() );
                        final long total = info.getHits() + info.getMisses();
                        c.put( "hitRatio", total > 0 ? Math.round( 100.0 * info.getHits() / total ) : 0 );
                        cacheStats.add( c );
                    }
                }
            }
        }
        return cacheStats;
    }

    void handleCacheFlush( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final JsonObject body = resource.parseJsonBody( request, response );
        if ( body == null ) return;

        final CachingManager cm = resource.getSubsystems().core().cachingManager();
        if ( cm == null ) {
            resource.sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Caching is not enabled" );
            return;
        }

        final String specificCache = resource.getJsonString( body, "cache" );

        final String[] toFlush = specificCache != null ? new String[]{ specificCache } : CACHE_NAMES;
        int flushed = 0;

        for ( final String cacheName : toFlush ) {
            if ( cm.enabled( cacheName ) ) {
                final List< ? extends Serializable > keys = cm.keys( cacheName );
                for ( final Serializable key : keys ) {
                    cm.remove( cacheName, key );
                }
                flushed += keys.size();
                LOG.info( "Flushed cache {} ({} entries)", shortCacheName( cacheName ), keys.size() );
            }
        }

        resource.sendJson( response, Map.of( "flushed", true, "entriesRemoved", flushed ) );
    }

    String shortCacheName( final String fullName ) {
        return fullName.replace( "wikantik.", "" );
    }
}
