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

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * robots.txt policy backed by crawler-commons. Fetches {@code scheme://host/robots.txt} once per
 * host (via the injected {@link PageFetcher}) and caches the parsed rules. A robots.txt that is
 * unreachable or empty is treated as allow-all (crawler-commons' default {@code ALLOW_SOME} mode
 * with no rules added permits everything) — this is a deliberate fail-open policy, logged once
 * per host.
 */
public final class RobotsPolicy {
    private static final Logger LOG = LogManager.getLogger( RobotsPolicy.class );

    private final PageFetcher fetcher;
    private final String userAgent;
    private final Map< String, SimpleRobotRules > rulesByHost = new ConcurrentHashMap<>();

    public RobotsPolicy( final PageFetcher fetcher, final String userAgent ) {
        this.fetcher = fetcher;
        this.userAgent = userAgent;
    }

    public boolean isAllowed( final String url ) {
        return rulesFor( url ).isAllowed( url );
    }

    /** Crawl-delay in milliseconds; 0 if unset or non-positive. */
    public long crawlDelayMs( final String url ) {
        final long delay = rulesFor( url ).getCrawlDelay();
        if ( delay == BaseRobotRules.UNSET_CRAWL_DELAY || delay <= 0 ) {
            return 0L;
        }
        // SimpleRobotRulesParser already converts the robots.txt "Crawl-delay:" seconds value
        // into milliseconds before storing it, so getCrawlDelay() is already in milliseconds.
        return delay;
    }

    private SimpleRobotRules rulesFor( final String url ) {
        final String hostKey = hostKey( url );
        if ( hostKey == null ) {
            return allowAllRules();
        }
        return rulesByHost.computeIfAbsent( hostKey, key -> fetchAndParse( key ) );
    }

    private static String hostKey( final String url ) {
        try {
            final URI uri = new URI( url );
            final String scheme = uri.getScheme();
            final String host = uri.getHost();
            if ( scheme == null || host == null ) {
                return null;
            }
            final int port = uri.getPort();
            return port == -1 ? scheme + "://" + host : scheme + "://" + host + ":" + port;
        } catch ( final URISyntaxException e ) {
            LOG.warn( "robots: could not derive host key for {}: {}", url, e.getMessage() );
            return null;
        }
    }

    private SimpleRobotRules fetchAndParse( final String hostKey ) {
        final String robotsUrl = hostKey + "/robots.txt";
        final FetchResult result = fetcher.fetch( robotsUrl );
        final SimpleRobotRulesParser parser = new SimpleRobotRulesParser();
        if ( result.status() / 100 == 2 && result.body().length > 0 ) {
            return parser.parseContent(
                robotsUrl,
                result.body(),
                result.contentType() == null ? "text/plain" : result.contentType(),
                userAgent );
        }
        LOG.warn( "robots.txt unavailable for {} (status {}) — treating as allow-all", robotsUrl, result.status() );
        return parser.parseContent( robotsUrl, new byte[0], "text/plain", userAgent );
    }

    private SimpleRobotRules allowAllRules() {
        return new SimpleRobotRulesParser().parseContent( "", new byte[0], "text/plain", userAgent );
    }
}
