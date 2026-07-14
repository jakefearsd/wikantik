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

class WebCrawlerSourceConnectorTest {

    private static FetchResult html( String url, String body ) {
        return new FetchResult( 200, "text/html", body.getBytes( StandardCharsets.UTF_8 ), url );
    }
    /** Serves a canned site: root links to /a and /b; /a links to /c (in scope) and to other.com (out). */
    private static PageFetcher site() {
        Map<String,FetchResult> m = new HashMap<>();
        m.put( "https://ex.com/robots.txt", new FetchResult( 404, "text/plain", new byte[0], "https://ex.com/robots.txt" ) );
        m.put( "https://ex.com/", html( "https://ex.com/", "<a href='/a'>a</a><a href='/b'>b</a>" ) );
        m.put( "https://ex.com/a", html( "https://ex.com/a", "<a href='/c'>c</a><a href='https://other.com/x'>o</a>" ) );
        m.put( "https://ex.com/b", html( "https://ex.com/b", "<p>leaf b</p>" ) );
        m.put( "https://ex.com/c", html( "https://ex.com/c", "<p>leaf c</p>" ) );
        return url -> m.getOrDefault( url, new FetchResult( 404, null, new byte[0], url ) );
    }
    private static WebCrawlerConfig cfg( int maxPages, int maxDepth ) {
        return new WebCrawlerConfig( List.of( "https://ex.com/" ), true, null, maxPages, maxDepth, 0, "WikantikCrawler/1.0", true );
    }
    private static WebCrawlerSourceConnector crawler( WebCrawlerConfig c ) {
        return new WebCrawlerSourceConnector( "web1", c, site(), ms -> {} );  // no-op sleeper
    }

    @Test void crawlsInScopePagesBreadthFirst() {
        SyncBatch b = crawler( cfg( 100, 5 ) ).poll( null );
        Set<String> uris = new HashSet<>();
        for ( SourceItem i : b.items() ) uris.add( i.sourceUri() );
        assertTrue( uris.containsAll( Set.of( "https://ex.com/", "https://ex.com/a", "https://ex.com/b", "https://ex.com/c" ) ) );
        assertFalse( uris.contains( "https://other.com/x" ), "out-of-scope host must not be crawled" );
        assertTrue( b.complete() );
        assertTrue( b.items().stream().allMatch( i -> "text/html".equals( i.contentType() ) && i.contentHash().length() == 64 ) );
    }
    @Test void respectRobotsFalseCrawlsDisallowedPage() {
        // robots disallows /a, but respect_robots=false → /a IS crawled (robots not consulted).
        PageFetcher f = url -> {
            if ( url.equals( "https://ex.com/robots.txt" ) )
                return new FetchResult( 200, "text/plain", "User-agent: *\nDisallow: /a\n".getBytes( java.nio.charset.StandardCharsets.UTF_8 ), url );
            if ( url.equals( "https://ex.com/" ) ) return html( "https://ex.com/", "<a href='/a'>a</a>" );
            if ( url.equals( "https://ex.com/a" ) ) return html( "https://ex.com/a", "<p>a</p>" );
            return new FetchResult( 404, null, new byte[0], url );
        };
        WebCrawlerConfig c = new WebCrawlerConfig( List.of( "https://ex.com/" ), true, null, 100, 5, 0, "WikantikCrawler/1.0", false );
        Set<String> uris = new HashSet<>();
        for ( SourceItem i : new WebCrawlerSourceConnector( "web1", c, f, ms -> {} ).poll( null ).items() ) uris.add( i.sourceUri() );
        assertTrue( uris.contains( "https://ex.com/a" ), "respect_robots=false must crawl the robots-disallowed page" );
    }

    @Test void respectsMaxPages() {
        assertTrue( crawler( cfg( 2, 5 ) ).poll( null ).items().size() <= 2 );
    }
    @Test void respectsMaxDepth() {
        // depth 0 = root only
        SyncBatch b = crawler( cfg( 100, 0 ) ).poll( null );
        assertEquals( 1, b.items().size() );
        assertEquals( "https://ex.com/", b.items().get( 0 ).sourceUri() );
    }
    @Test void skipsRobotsDisallowed() {
        PageFetcher f = url -> {
            if ( url.equals( "https://ex.com/robots.txt" ) )
                return new FetchResult( 200, "text/plain", "User-agent: *\nDisallow: /a\n".getBytes( StandardCharsets.UTF_8 ), url );
            if ( url.equals( "https://ex.com/" ) ) return html( url, "<a href='/a'>a</a><a href='/b'>b</a>" );
            if ( url.equals( "https://ex.com/b" ) ) return html( url, "<p>b</p>" );
            return html( url, "<p>should not be fetched</p>" );
        };
        SyncBatch b = new WebCrawlerSourceConnector( "web1", cfg( 100, 5 ), f, ms -> {} ).poll( null );
        Set<String> uris = new HashSet<>();
        for ( SourceItem i : b.items() ) uris.add( i.sourceUri() );
        assertFalse( uris.contains( "https://ex.com/a" ), "/a is robots-disallowed" );
        assertTrue( uris.contains( "https://ex.com/b" ) );
    }
    // A transient fetch failure (5xx / network error) means the crawl is NOT a trustworthy full
    // snapshot — the batch must be incomplete so the orchestrator derives no tombstones from it.
    @Test void transientFetchFailureMakesBatchIncomplete() {
        PageFetcher f = url -> {
            if ( url.equals( "https://ex.com/robots.txt" ) ) return new FetchResult( 404, null, new byte[0], url );
            if ( url.equals( "https://ex.com/" ) ) return html( url, "<a href='/a'>a</a>" );
            if ( url.equals( "https://ex.com/a" ) ) return new FetchResult( 503, "text/html", new byte[0], url );
            return new FetchResult( 404, null, new byte[0], url );
        };
        SyncBatch b = new WebCrawlerSourceConnector( "web1", cfg( 100, 5 ), f, ms -> {} ).poll( null );
        assertFalse( b.complete(), "5xx mid-crawl → untrusted enumeration → incomplete batch" );
        assertNull( b.nextCursor(), "untrusted batch returns the input cursor verbatim (null on first sync)" );
        assertEquals( 1, b.items().size(), "successfully fetched pages are still delivered" );
    }

    // A 404/410 is an authoritative "this page is gone" — it must NOT taint the batch, so genuine
    // deletions still tombstone on a healthy crawl.
    @Test void notFound404DoesNotTaintBatch() {
        SyncBatch b = crawler( cfg( 100, 5 ) ).poll( null );   // site() fixture 404s all unknown urls
        assertTrue( b.complete(), "404s are authoritative absence, not a fetch failure" );
    }

    @Test void skipsNon2xxAndNonHtml() {
        PageFetcher f = url -> {
            if ( url.equals( "https://ex.com/robots.txt" ) ) return new FetchResult( 404, null, new byte[0], url );
            if ( url.equals( "https://ex.com/" ) ) return html( url, "<a href='/dead'>d</a><a href='/img'>i</a>" );
            if ( url.equals( "https://ex.com/dead" ) ) return new FetchResult( 500, "text/html", new byte[0], url );
            if ( url.equals( "https://ex.com/img" ) ) return new FetchResult( 200, "image/png", new byte[]{1,2}, url );
            return new FetchResult( 404, null, new byte[0], url );
        };
        SyncBatch b = new WebCrawlerSourceConnector( "web1", cfg( 100, 5 ), f, ms -> {} ).poll( null );
        assertEquals( 1, b.items().size() );  // only the root; /dead (500) and /img (non-html) skipped
    }
}
