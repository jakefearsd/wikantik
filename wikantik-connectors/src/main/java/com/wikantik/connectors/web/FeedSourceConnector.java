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

/** Reads RSS/Atom feeds and emits a SourceItem per entry. Full-article (default) or inline content.
 *  A feed is a rolling window, so {@link #reflectsFullCorpus()} is false — aged-out entries are
 *  retained (their derived pages and sync state are left untouched; there is no archive state). */
public final class FeedSourceConnector implements SourceConnector {

    private static final Logger LOG = LogManager.getLogger( FeedSourceConnector.class );

    private final String connectorId;
    private final FeedConfig config;
    private final PageFetcher fetcher;
    private final LongConsumer sleeper;

    public FeedSourceConnector( final String connectorId, final FeedConfig config,
                                final PageFetcher fetcher, final LongConsumer sleeper ) {
        this.connectorId = connectorId;
        this.config = config;
        this.fetcher = fetcher;
        this.sleeper = sleeper;
    }

    @Override public String connectorId() { return connectorId; }

    @Override public boolean reflectsFullCorpus() { return false; }   // windowed source → aged-out items retained

    @Override
    public SyncBatch poll( final SyncCursor cursor ) {
        final RobotsPolicy robots = new RobotsPolicy( fetcher, config.userAgent() );
        final Set< String > allowedHosts = allowedHosts();
        final List< FeedEntry > entries = fetchEntries( robots );

        final List< SourceItem > items = new ArrayList<>();
        final Set< String > visited = new HashSet<>();
        for ( final FeedEntry e : entries ) {
            if ( items.size() >= config.maxItems() ) break;
            if ( !visited.add( e.link() ) ) continue;
            resolveEntryItem( e, robots, allowedHosts ).ifPresent( items::add );
        }
        if ( items.size() >= config.maxItems() ) {
            LOG.info( "feed '{}': hit max_items={}, truncated", connectorId, config.maxItems() );
        }
        return new SyncBatch( items, List.of(), new SyncCursor( String.valueOf( items.size() ) ), true );
    }

    private Set< String > allowedHosts() {
        final Set< String > allowedHosts = new HashSet<>();
        for ( final String feedUrl : config.feedUrls() ) hostOf( feedUrl ).ifPresent( allowedHosts::add );
        return allowedHosts;
    }

    private List< FeedEntry > fetchEntries( final RobotsPolicy robots ) {
        final List< FeedEntry > entries = new ArrayList<>();
        for ( final String feedUrl : config.feedUrls() ) {
            if ( config.respectRobots() && !robots.isAllowed( feedUrl ) ) {
                LOG.info( "feed '{}': robots-disallowed feed {}", connectorId, feedUrl );
                continue;
            }
            final FetchResult r = fetcher.fetch( feedUrl );
            if ( r.status() / 100 != 2 ) {
                LOG.warn( "feed '{}': fetch of {} returned status {}", connectorId, feedUrl, r.status() );
                continue;
            }
            entries.addAll( FeedParser.parse( r.body(), feedUrl ) );
        }
        return entries;
    }

    /** Resolves one feed entry to a {@link SourceItem}, applying the same-host filter and (when
     *  {@code fetchFullArticles}) the article fetch — empty if the entry is filtered out or unfetchable. */
    private Optional< SourceItem > resolveEntryItem( final FeedEntry e, final RobotsPolicy robots, final Set< String > allowedHosts ) {
        if ( config.sameHostOnly() && hostOf( e.link() ).map( h -> !allowedHosts.contains( h ) ).orElse( true ) ) {
            return Optional.empty();
        }
        if ( config.fetchFullArticles() ) {
            if ( config.respectRobots() && !robots.isAllowed( e.link() ) ) {
                LOG.info( "feed '{}': robots-disallowed article {}", connectorId, e.link() );
                return Optional.empty();
            }
            sleepPolitely( robots, e.link() );
            final FetchResult ar = fetcher.fetch( e.link() );
            if ( ar.status() / 100 != 2 || !isHtml( ar.contentType() ) ) return Optional.empty();
            final String finalUrl = ar.finalUrl() == null ? e.link() : ar.finalUrl();
            return Optional.of( WebFetchItems.toItem( finalUrl, ar ) );
        }
        if ( e.contentHtml().isBlank() ) return Optional.empty();
        return Optional.of( WebFetchItems.toItemFromContent( e.link(),
            e.contentHtml().getBytes( StandardCharsets.UTF_8 ), e.title() ) );
    }

    private void sleepPolitely( final RobotsPolicy robots, final String url ) {
        final long robotsDelay = config.respectRobots() ? robots.crawlDelayMs( url ) : 0L;
        final long delay = Math.max( config.delayMs(), robotsDelay );
        if ( delay > 0 ) sleeper.accept( delay );
    }

    private static Optional< String > hostOf( final String url ) {
        try { return Optional.ofNullable( URI.create( url ).getHost() ); }
        catch ( final RuntimeException e ) {
            LOG.warn( "feed: could not derive host for {}: {}", url, e.getMessage() );
            return Optional.empty();
        }
    }

    private static boolean isHtml( final String contentType ) {
        return contentType != null && contentType.toLowerCase( Locale.ROOT ).contains( "text/html" );
    }
}
