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
import java.util.*;
import java.util.function.LongConsumer;

/** BFS web crawler. Emits one SourceItem per in-scope, robots-allowed, 2xx HTML page. Fail-closed. */
public final class WebCrawlerSourceConnector implements SourceConnector {

    private static final Logger LOG = LogManager.getLogger( WebCrawlerSourceConnector.class );

    private final String connectorId;
    private final WebCrawlerConfig config;
    private final PageFetcher fetcher;
    private final LongConsumer sleeper;

    public WebCrawlerSourceConnector( final String connectorId, final WebCrawlerConfig config,
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
        final Set< String > visited = new HashSet<>();
        final Deque< Node > queue = new ArrayDeque<>();
        for ( final String seed : config.seeds() ) queue.add( new Node( seed, 0 ) );
        final List< SourceItem > items = new ArrayList<>();
        boolean trusted = true;   // false once any fetch fails non-authoritatively → no tombstone derivation

        while ( !queue.isEmpty() && items.size() < config.maxPages() ) {
            final Node n = queue.poll();
            if ( !visited.add( n.url ) || n.depth > config.maxDepth() ) continue;

            final CrawlScope scope = scopeFor( n.url );
            if ( config.respectRobots() && !robots.isAllowed( n.url ) ) {
                LOG.info( "crawler '{}': robots-disallowed, skipping {}", connectorId, n.url );
                continue;
            }
            sleepPolitely( robots, n.url );

            final FetchResult r = fetcher.fetch( n.url );      // fetcher is fail-closed (status 0 on error)
            if ( r.status() / 100 != 2 ) {
                // 404/410 = authoritative "gone" (safe to treat as absent); anything else (0 = network
                // error, 5xx, 429, auth walls…) means this crawl is NOT a trustworthy full snapshot —
                // returning complete=true would let the orchestrator tombstone every page it missed.
                if ( r.status() != 404 && r.status() != 410 ) {
                    trusted = false;
                    LOG.warn( "crawler '{}': fetch of {} failed (status {}) — batch marked incomplete, "
                        + "no tombstones this cycle", connectorId, n.url, r.status() );
                }
                continue;
            }
            if ( !isHtml( r.contentType() ) ) continue;

            final String finalUrl = r.finalUrl() == null ? n.url : r.finalUrl();
            final String html = new String( r.body(), java.nio.charset.StandardCharsets.UTF_8 );
            items.add( WebFetchItems.toItem( finalUrl, r ) );

            if ( n.depth < config.maxDepth() ) {
                for ( final String link : LinkExtractor.links( html, finalUrl ) ) {
                    if ( !visited.contains( link ) && scope != null && scope.inScope( link ) ) {
                        queue.add( new Node( link, n.depth + 1 ) );
                    }
                }
            }
        }
        if ( items.size() >= config.maxPages() ) {
            LOG.info( "crawler '{}': hit max_pages={}, crawl truncated", connectorId, config.maxPages() );
        }
        if ( !trusted ) {
            // deliver what was fetched, but signal an untrusted enumeration: input cursor + incomplete
            // (the orchestrator skips tombstone derivation and retries next scheduled run)
            return new SyncBatch( items, List.of(), cursor, false );
        }
        return new SyncBatch( items, List.of(), new SyncCursor( String.valueOf( items.size() ) ), true );
    }

    private CrawlScope scopeFor( final String url ) {
        try { return new CrawlScope( URI.create( url ).getHost(), config.sameHostOnly(), config.pathPrefix() ); }
        catch ( final RuntimeException e ) {
            LOG.warn( "crawler '{}': could not derive scope for {}: {}", connectorId, url, e.getMessage() );
            return null;
        }
    }

    private void sleepPolitely( final RobotsPolicy robots, final String url ) {
        // Only consult robots crawl-delay when robots are respected — otherwise robots.txt is not fetched at all.
        final long robotsDelay = config.respectRobots() ? robots.crawlDelayMs( url ) : 0L;
        final long delay = Math.max( config.delayMs(), robotsDelay );
        if ( delay > 0 ) sleeper.accept( delay );
    }

    private static boolean isHtml( final String contentType ) {
        return contentType != null && contentType.toLowerCase( Locale.ROOT ).contains( "text/html" );
    }

    private record Node( String url, int depth ) {}
}
