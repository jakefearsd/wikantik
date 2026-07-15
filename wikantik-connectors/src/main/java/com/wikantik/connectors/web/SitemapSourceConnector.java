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
package com.wikantik.connectors.web;

import com.wikantik.api.connectors.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongConsumer;

/** Reads a site's sitemap.xml (and one level of sitemap-index) and emits a SourceItem per listed page. */
public final class SitemapSourceConnector implements SourceConnector {

    private static final Logger LOG = LogManager.getLogger( SitemapSourceConnector.class );

    private final String connectorId;
    private final SitemapConfig config;
    private final PageFetcher fetcher;
    private final LongConsumer sleeper;

    public SitemapSourceConnector( final String connectorId, final SitemapConfig config,
                                   final PageFetcher fetcher, final LongConsumer sleeper ) {
        this.connectorId = connectorId;
        this.config = config;
        this.fetcher = fetcher;
        this.sleeper = sleeper;
    }

    @Override public String connectorId() { return connectorId; }

    @Override
    public SyncBatch poll( final SyncCursor cursor ) {
        final RobotsPolicy robots = new RobotsPolicy( fetcher, config.userAgent() );
        final Set< String > allowedHosts = allowedHosts();

        // trusted=false once enumeration can no longer be treated as a full snapshot — the orchestrator
        // must not derive tombstones from what this poll happened to miss
        final Enumeration enumeration = enumeratePages( robots, allowedHosts );
        boolean trusted = enumeration.trusted();

        final List< SourceItem > items = new ArrayList<>();
        final Set< String > visited = new HashSet<>();
        for ( final String url : enumeration.pageUrls() ) {
            if ( items.size() >= config.maxPages() ) break;
            final UrlFetchOutcome outcome = fetchPageItem( url, robots, allowedHosts, visited );
            trusted &= outcome.trusted();
            outcome.item().ifPresent( items::add );
        }
        if ( items.size() >= config.maxPages() ) {
            LOG.info( "sitemap '{}': hit max_pages={}, truncated", connectorId, config.maxPages() );
        }
        if ( !trusted ) {
            return new SyncBatch( items, List.of(), cursor, false );   // untrusted enumeration: input cursor, incomplete
        }
        return new SyncBatch( items, List.of(), new SyncCursor( String.valueOf( items.size() ) ), true );
    }

    private Set< String > allowedHosts() {
        final Set< String > allowedHosts = new HashSet<>();
        for ( final String sm : config.sitemapUrls() ) hostOf( sm ).ifPresent( allowedHosts::add );
        return allowedHosts;
    }

    private Enumeration enumeratePages( final RobotsPolicy robots, final Set< String > allowedHosts ) {
        final Set< String > pageUrls = new LinkedHashSet<>();
        boolean trusted = true;
        for ( final String sm : config.sitemapUrls() ) {
            trusted &= collectPages( sm, robots, pageUrls, allowedHosts, 0 );
        }
        return new Enumeration( pageUrls, trusted );
    }

    /** Resolves one enumerated page URL to a fetched {@link SourceItem}, and whether the fetch taints
     *  the batch's trust (a non-404/410 failure means enumeration can't be trusted for tombstoning). */
    private UrlFetchOutcome fetchPageItem( final String url, final RobotsPolicy robots,
                                           final Set< String > allowedHosts, final Set< String > visited ) {
        if ( config.sameHostOnly() && hostOf( url ).map( h -> !allowedHosts.contains( h ) ).orElse( true ) ) {
            return UrlFetchOutcome.skip();
        }
        if ( !visited.add( url ) ) return UrlFetchOutcome.skip();
        if ( config.respectRobots() && !robots.isAllowed( url ) ) {
            LOG.info( "sitemap '{}': robots-disallowed, skipping {}", connectorId, url );
            return UrlFetchOutcome.skip();
        }
        sleepPolitely( robots, url );
        final FetchResult r = fetcher.fetch( url );
        if ( r.status() / 100 != 2 ) {
            // the sitemap still LISTS this page, so it is not absent-at-source. 404/410 = the page
            // itself is authoritatively gone (tombstoning is correct); anything else is transient —
            // mark the batch untrusted so the missing item is not tombstoned.
            if ( r.status() != 404 && r.status() != 410 ) {
                LOG.warn( "sitemap '{}': fetch of listed page {} failed (status {}) — batch marked "
                    + "incomplete, no tombstones this cycle", connectorId, url, r.status() );
                return new UrlFetchOutcome( Optional.empty(), false );
            }
            return UrlFetchOutcome.skip();
        }
        if ( !isHtml( r.contentType() ) ) return UrlFetchOutcome.skip();
        final String finalUrl = r.finalUrl() == null ? url : r.finalUrl();
        return new UrlFetchOutcome( Optional.of( WebFetchItems.toItem( finalUrl, r ) ), true );
    }

    private record Enumeration( Set< String > pageUrls, boolean trusted ) {}

    private record UrlFetchOutcome( Optional< SourceItem > item, boolean trusted ) {
        private static UrlFetchOutcome skip() { return new UrlFetchOutcome( Optional.empty(), true ); }
    }

    /** @return {@code false} if any sitemap in this subtree could not be fetched (enumeration untrustworthy). */
    private boolean collectPages( final String sitemapUrl, final RobotsPolicy robots,
                                  final Set< String > out, final Set< String > allowedHosts, final int depth ) {
        if ( config.respectRobots() && !robots.isAllowed( sitemapUrl ) ) {
            LOG.info( "sitemap '{}': robots-disallowed sitemap {}", connectorId, sitemapUrl );
            return true;    // deliberate skip, not a failure
        }
        final FetchResult r = fetcher.fetch( sitemapUrl );
        if ( r.status() / 100 != 2 ) {
            // ANY failure to fetch an enumeration source (even a 404) taints the batch: a missing
            // sitemap is not proof the site is empty, and tombstoning on it would mass-delete.
            LOG.warn( "sitemap '{}': fetch of {} returned status {} — batch marked incomplete, "
                + "no tombstones this cycle", connectorId, sitemapUrl, r.status() );
            return false;
        }
        final ParsedSitemap parsed = SitemapParser.parse( new String( r.body(), StandardCharsets.UTF_8 ) );
        boolean trusted = true;
        if ( parsed.isIndex() ) {
            if ( depth == 0 ) {
                for ( final String sub : parsed.locs() ) {
                    // don't fetch a foreign-host sub-sitemap when same_host_only — otherwise a hostile
                    // sitemap-index could point us at thousands of GETs to an unrelated site.
                    if ( config.sameHostOnly() && hostOf( sub ).map( h -> !allowedHosts.contains( h ) ).orElse( true ) ) {
                        LOG.info( "sitemap '{}': skipping foreign-host sub-sitemap {}", connectorId, sub );
                        continue;
                    }
                    trusted &= collectPages( sub, robots, out, allowedHosts, depth + 1 );
                }
            } else {
                LOG.info( "sitemap '{}': nested index beyond one level ignored: {}", connectorId, sitemapUrl );
            }
        } else {
            out.addAll( parsed.locs() );
        }
        return trusted;
    }

    private void sleepPolitely( final RobotsPolicy robots, final String url ) {
        final long robotsDelay = config.respectRobots() ? robots.crawlDelayMs( url ) : 0L;
        final long delay = Math.max( config.delayMs(), robotsDelay );
        if ( delay > 0 ) sleeper.accept( delay );
    }

    private static Optional< String > hostOf( final String url ) {
        try { return Optional.ofNullable( URI.create( url ).getHost() ); }
        catch ( final RuntimeException e ) {
            LOG.warn( "sitemap: could not derive host for {}: {}", url, e.getMessage() );
            return Optional.empty();
        }
    }

    private static boolean isHtml( final String contentType ) {
        return contentType != null && contentType.toLowerCase( Locale.ROOT ).contains( "text/html" );
    }
}
