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

import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.references.BrokenBlockRefs;
import com.wikantik.wikilink.WikiLinkResolver;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Page-health routes of {@link AdminContentResource}: stats, orphans, broken links, locked pages. */
final class AdminContentHealthHandlers {

    private static final Logger LOG = LogManager.getLogger( AdminContentHealthHandlers.class );

    /** Successful block-ref scan results, cached for {@code wikantik.admin.scanCacheTTL} seconds. */
    private final ScanCache< List< Map< String, Object > > > blockRefCache;

    private final AdminCacheHandlers cache;

    private final AdminContentResource resource;

    AdminContentHealthHandlers( final AdminContentResource resource, final AdminCacheHandlers cache ) {
        this.resource = resource;
        this.cache = cache;
        this.blockRefCache = new ScanCache<>( () -> resource.clockMs.getAsLong() );
    }

    void handleStats( final HttpServletResponse response ) throws IOException {
        final PageManager pm = resource.getSubsystems().page().pages();
        final ReferenceManager rm = resource.getSubsystems().page().referenceManager();

        final Map< String, Object > stats = new LinkedHashMap<>();
        stats.put( "pageCount", pm.getTotalPageCount() );

        if ( rm != null ) {
            stats.put( "orphanedCount", rm.findUnreferenced().size() );
            stats.put( "brokenLinkCount", rm.findUncreated().size() );
        }

        stats.put( "caches", cache.cacheStats() );

        resource.sendJson( response, stats );
    }

    void handleOrphanedPages( final HttpServletResponse response ) throws IOException {
        final ReferenceManager rm = resource.getSubsystems().page().referenceManager();
        if ( rm == null ) {
            resource.sendJson( response, Map.of( "pages", List.of() ) );
            return;
        }

        final Collection< String > orphaned = rm.findUnreferenced();
        final List< String > sorted = new ArrayList<>( orphaned );
        sorted.sort( String::compareToIgnoreCase );
        resource.sendJson( response, Map.of( "pages", sorted ) );
    }

    void handleBrokenLinks( final HttpServletResponse response ) throws IOException {
        final ReferenceManager rm = resource.getSubsystems().page().referenceManager();
        if ( rm == null ) {
            resource.sendJson( response, Map.of( "links", List.of(), "brokenBlockRefs", List.of() ) );
            return;
        }

        final Collection< String > uncreated = rm.findUncreated();
        final List< Map< String, Object > > links = new ArrayList<>();

        for ( final String target : uncreated ) {
            final Set< String > referrers = rm.findReferrers( target );
            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "target", target );
            entry.put( "referencedBy", referrers != null ? new ArrayList<>( referrers ) : List.of() );
            entry.put( "referrerCount", referrers != null ? referrers.size() : 0 );
            links.add( entry );
        }

        // Sort by referrer count descending
        links.sort( ( a, b ) -> Integer.compare(
            ( int ) b.get( "referrerCount" ), ( int ) a.get( "referrerCount" ) ) );

        resource.sendJson( response, Map.of( "links", links, "brokenBlockRefs", brokenBlockRefs() ) );
    }

    /**
     * Every page whose access rules lock it (#104): {@code { pages: [{name, version, reasons: [{shape, line | field, text}]}] }},
     * by name. A page whose text cannot be read is skipped (and logged): its lock is a provider failure, not its text.
     */
    void handleLockedPages( final HttpServletResponse response ) throws IOException {
        final PageManager pm = resource.getSubsystems().page().pages();
        final Collection< Page > pages;
        try {
            pages = pm.getAllPages();
        } catch ( final ProviderException e ) {
            LOG.warn( "Locked-pages report: page enumeration failed: {}", e.getMessage(), e );
            resource.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not list the pages." );
            return;
        }
        final List< Map< String, Object > > locked = new ArrayList<>();
        for ( final Page page : pages ) {
            final String text;
            try {
                text = pm.getPureText( page );
            } catch ( final RuntimeException e ) {
                LOG.warn( "Locked-pages report: could not read page {}, skipping it: {}", page.getName(), e.getMessage() );
                continue;
            }
            final Map< String, Object > rules = LockedAccessRules.of( text );
            if ( rules != null ) {
                final Map< String, Object > entry = new LinkedHashMap<>();
                entry.put( "name", page.getName() );
                entry.put( "version", Math.max( page.getVersion(), 1 ) );
                entry.put( "reasons", rules.get( "reasons" ) );
                locked.add( entry );
            }
        }
        locked.sort( ( a, b ) -> ( ( String ) a.get( "name" ) ).compareToIgnoreCase( ( String ) b.get( "name" ) ) );
        resource.sendJson( response, Map.of( "pages", locked ) );
    }
    /** Block references whose target page exists but has no such block; empty (never an error) on failure. */
    List< Map< String, Object > > brokenBlockRefs() {
        try {
            return blockRefCache.get( ScanCache.ttlSeconds( resource.getEngine() ), () -> {
                final List< BrokenBlockRefs.Entry > entries = resource.blockRefScan != null ? resource.blockRefScan.get()
                        : new BrokenBlockRefs().find( resource.getSubsystems().page().pages(), WikiLinkResolver.forEngine( resource.getEngine() ) );
                final List< Map< String, Object > > out = new ArrayList<>();
                for ( final BrokenBlockRefs.Entry e : entries ) {
                    out.add( e.toMap() );
                }
                return List.copyOf( out );
            } ).value();
        } catch ( final RuntimeException e ) {
            // A failed scan is never cached: the next request retries.
            LOG.warn( "Broken block reference scan failed: {}", e.getMessage(), e );
            return List.of();
        }
    }
}
