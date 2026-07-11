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

import com.wikantik.api.connectors.SourceItem;
import com.wikantik.api.connectors.SyncBatch;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FeedSourceConnectorTest {

    private static FetchResult feed( String url, String body ) {
        return new FetchResult( 200, "application/rss+xml", body.getBytes( StandardCharsets.UTF_8 ), url );
    }
    private static FetchResult html( String url, String body ) {
        return new FetchResult( 200, "text/html", body.getBytes( StandardCharsets.UTF_8 ), url );
    }
    private static final String RSS =
        "<rss version='2.0'><channel><title>C</title>"
        + "<item><title>A</title><link>https://ex.com/a</link><description>&lt;p&gt;inline a&lt;/p&gt;</description></item>"
        + "<item><title>B</title><link>https://ex.com/b</link><description>&lt;p&gt;inline b&lt;/p&gt;</description></item>"
        + "</channel></rss>";
    private static FeedConfig cfg( int maxItems, boolean fetchFull ) {
        return new FeedConfig( List.of( "https://ex.com/feed.xml" ), maxItems, fetchFull, 0,
            "WikantikCrawler/1.0", true, true );
    }
    private static Set<String> uris( SyncBatch b ) {
        Set<String> s = new HashSet<>();
        for ( SourceItem i : b.items() ) s.add( i.sourceUri() );
        return s;
    }

    @Test void reflectsFullCorpusIsFalse() {
        assertFalse( new FeedSourceConnector( "f1", cfg( 100, true ), u -> new FetchResult( 404, null, new byte[0], u ), ms -> {} )
            .reflectsFullCorpus() );
    }

    @Test void fetchFullArticlesFetchesEachLink() {
        PageFetcher f = url -> switch ( url ) {
            case "https://ex.com/robots.txt" -> new FetchResult( 404, null, new byte[0], url );
            case "https://ex.com/feed.xml" -> feed( url, RSS );
            case "https://ex.com/a", "https://ex.com/b" -> html( url, "<p>full article</p>" );
            default -> new FetchResult( 404, null, new byte[0], url );
        };
        SyncBatch b = new FeedSourceConnector( "f1", cfg( 100, true ), f, ms -> {} ).poll( null );
        assertEquals( Set.of( "https://ex.com/a", "https://ex.com/b" ), uris( b ) );
        assertTrue( b.items().stream().allMatch( i -> new String( i.content() ).contains( "full article" ) ) );
        assertTrue( b.complete() );
    }

    @Test void inlineModeEmitsEntryContentWithoutArticleFetch() {
        Set<String> fetched = new HashSet<>();
        PageFetcher f = url -> {
            fetched.add( url );
            if ( url.equals( "https://ex.com/feed.xml" ) ) return feed( url, RSS );
            return new FetchResult( 404, null, new byte[0], url );
        };
        SyncBatch b = new FeedSourceConnector( "f1", cfg( 100, false ), f, ms -> {} ).poll( null );
        assertEquals( Set.of( "https://ex.com/a", "https://ex.com/b" ), uris( b ) );
        assertTrue( b.items().stream().anyMatch( i -> new String( i.content() ).contains( "inline a" ) ) );
        assertFalse( fetched.contains( "https://ex.com/a" ), "inline mode must not fetch the article link" );
    }

    @Test void respectsMaxItems() {
        PageFetcher f = url -> url.equals( "https://ex.com/feed.xml" ) ? feed( url, RSS )
            : new FetchResult( 404, null, new byte[0], url );
        assertTrue( new FeedSourceConnector( "f1", cfg( 1, false ), f, ms -> {} ).poll( null ).items().size() <= 1 );
    }

    @Test void fullArticleSkipsRobotsDisallowedAndNonHtml() {
        PageFetcher f = url -> switch ( url ) {
            case "https://ex.com/robots.txt" -> new FetchResult( 200, "text/plain",
                "User-agent: *\nDisallow: /a\n".getBytes( StandardCharsets.UTF_8 ), url );
            case "https://ex.com/feed.xml" -> feed( url, RSS );
            case "https://ex.com/b" -> html( url, "<p>b</p>" );
            default -> new FetchResult( 404, null, new byte[0], url );
        };
        Set<String> u = uris( new FeedSourceConnector( "f1", cfg( 100, true ), f, ms -> {} ).poll( null ) );
        assertEquals( Set.of( "https://ex.com/b" ), u );   // /a robots-disallowed
    }
}
