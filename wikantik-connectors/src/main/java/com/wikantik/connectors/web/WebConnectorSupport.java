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

import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.function.LongConsumer;

/**
 * Helpers shared by the web-source connectors ({@link FeedSourceConnector}, {@link
 * SitemapSourceConnector}, {@link WebCrawlerSourceConnector}): the politeness-delay sleep, URL→host
 * extraction, and the HTML content-type check. A package-private static utility rather than a common
 * base class — the three connectors implement {@link com.wikantik.api.connectors.SourceConnector}
 * directly (each with its own config record shape) and share no supertype worth introducing just to
 * hang three static helpers on.
 */
final class WebConnectorSupport {

    private WebConnectorSupport() {}

    /**
     * Sleeps for the greater of the connector's configured delay and (when robots are respected) the
     * target host's robots.txt crawl-delay. No-op when the resulting delay is not positive.
     */
    static void sleepPolitely( final RobotsPolicy robots, final String url, final boolean respectRobots,
                               final long delayMs, final LongConsumer sleeper ) {
        // Only consult robots crawl-delay when robots are respected — otherwise robots.txt is not fetched at all.
        final long robotsDelay = respectRobots ? robots.crawlDelayMs( url ) : 0L;
        final long delay = Math.max( delayMs, robotsDelay );
        if ( delay > 0 ) sleeper.accept( delay );
    }

    /**
     * Extracts the host from {@code url}; empty (logged via the caller's own {@code log}, tagged with
     * {@code label}) if the URL can't be parsed.
     */
    static Optional< String > hostOf( final String url, final Logger log, final String label ) {
        try { return Optional.ofNullable( URI.create( url ).getHost() ); }
        catch ( final RuntimeException e ) {
            log.warn( "{}: could not derive host for {}: {}", label, url, e.getMessage() );
            return Optional.empty();
        }
    }

    static boolean isHtml( final String contentType ) {
        return contentType != null && contentType.toLowerCase( Locale.ROOT ).contains( "text/html" );
    }
}
