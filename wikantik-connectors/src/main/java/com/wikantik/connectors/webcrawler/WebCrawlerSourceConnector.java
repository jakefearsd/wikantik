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
package com.wikantik.connectors.webcrawler;

import com.wikantik.api.connectors.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Instant;
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
            if ( r.status() / 100 != 2 || !isHtml( r.contentType() ) ) continue;

            final String finalUrl = r.finalUrl() == null ? n.url : r.finalUrl();
            final String html = new String( r.body(), java.nio.charset.StandardCharsets.UTF_8 );
            items.add( item( finalUrl, r ) );

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
        return new SyncBatch( items, List.of(), new SyncCursor( String.valueOf( items.size() ) ), true );
    }

    private CrawlScope scopeFor( final String url ) {
        try { return new CrawlScope( URI.create( url ).getHost(), config.sameHostOnly(), config.pathPrefix() ); }
        catch ( final RuntimeException e ) { return null; }
    }

    private void sleepPolitely( final RobotsPolicy robots, final String url ) {
        final long delay = Math.max( config.delayMs(), robots.crawlDelayMs( url ) );
        if ( delay > 0 ) sleeper.accept( delay );
    }

    private SourceItem item( final String url, final FetchResult r ) {
        final Map< String, Object > md = new LinkedHashMap<>();
        md.put( "url", url );
        md.put( "title", LinkExtractor.title( new String( r.body(), java.nio.charset.StandardCharsets.UTF_8 ) ) );
        md.put( "fetchedAt", Instant.now().toString() );      // NB: Instant.now is fine in prod; tests don't assert it
        md.put( "httpStatus", r.status() );
        return new SourceItem( url, r.body(), "text/html", md, List.of(), sha256Hex( r.body() ) );
    }

    private static boolean isHtml( final String contentType ) {
        return contentType != null && contentType.toLowerCase( Locale.ROOT ).contains( "text/html" );
    }

    private static String sha256Hex( final byte[] bytes ) {
        try {
            final byte[] d = MessageDigest.getInstance( "SHA-256" ).digest( bytes );
            final StringBuilder sb = new StringBuilder( d.length * 2 );
            for ( final byte b : d ) sb.append( Character.forDigit( ( b >> 4 ) & 0xF, 16 ) ).append( Character.forDigit( b & 0xF, 16 ) );
            return sb.toString();
        } catch ( final java.security.NoSuchAlgorithmException e ) { throw new IllegalStateException( e ); }
    }

    private record Node( String url, int depth ) {}
}
