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
package com.wikantik.insights.runtime;

import com.wikantik.insights.readers.PathResolver;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves Umami URL paths to {@code canonical_id}s: {@code /} is the front page and
 * {@code /wiki/<slug>} is a page, found by current slug and then by a historical (renamed-away)
 * slug. Anything else is not a page. Results are memoised for the life of this instance only,
 * so create one per read-back run; renames made later must not be masked by a stale cache.
 */
public final class WikiPathResolver implements PathResolver {

    private static final Logger LOG = LogManager.getLogger( WikiPathResolver.class );
    private static final String WIKI_PREFIX = "/wiki/";

    private final PageCanonicalIdsDao dao;
    private final String frontPageName;
    private final Map< String, Optional< String > > memo = new HashMap<>();

    public WikiPathResolver( final PageCanonicalIdsDao dao, final String frontPageName ) {
        this.dao = dao;
        this.frontPageName = frontPageName;
    }

    @Override
    public Optional< String > canonicalIdFor( final String urlPath ) {
        if ( urlPath == null ) {
            return Optional.empty();
        }
        return memo.computeIfAbsent( urlPath, this::resolve );
    }

    private Optional< String > resolve( final String urlPath ) {
        final String slug = slugOf( urlPath );
        if ( slug == null || slug.isEmpty() ) {
            return Optional.empty();
        }
        final Optional< String > current = dao.findBySlug( slug ).map( PageCanonicalIdsDao.Row::canonicalId );
        return ( current.isPresent() ? current : dao.findCanonicalIdByHistoricalSlug( slug ) )
                .map( String::strip ); // canonical_id is CHAR(26); drop any padding
    }

    /** The page slug a path names, or null when the path is not a page path. */
    private String slugOf( final String urlPath ) {
        String path = urlPath;
        final int query = path.indexOf( '?' );
        if ( query >= 0 ) {
            path = path.substring( 0, query );
        }
        final int fragment = path.indexOf( '#' );
        if ( fragment >= 0 ) {
            path = path.substring( 0, fragment );
        }
        try {
            // '+' is a literal in a path (URLDecoder would turn it into a space)
            path = URLDecoder.decode( path.replace( "+", "%2B" ), StandardCharsets.UTF_8 );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "Cannot URL-decode analytics path '{}': {}", urlPath, e.getMessage() );
            return null;
        }
        if ( path.length() > 1 && path.endsWith( "/" ) ) {
            path = path.substring( 0, path.length() - 1 );
        }
        if ( "/".equals( path ) ) {
            return frontPageName;
        }
        if ( !path.startsWith( WIKI_PREFIX ) ) {
            return null;
        }
        final String slug = path.substring( WIKI_PREFIX.length() );
        return slug.indexOf( '/' ) >= 0 ? null : slug;
    }
}
