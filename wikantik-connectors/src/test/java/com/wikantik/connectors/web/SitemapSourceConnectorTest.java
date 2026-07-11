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

class SitemapSourceConnectorTest {

    private static FetchResult xml( String url, String body ) {
        return new FetchResult( 200, "application/xml", body.getBytes( StandardCharsets.UTF_8 ), url );
    }
    private static FetchResult html( String url, String body ) {
        return new FetchResult( 200, "text/html", body.getBytes( StandardCharsets.UTF_8 ), url );
    }
    private static String urlset( String... locs ) {
        StringBuilder sb = new StringBuilder( "<urlset>" );
        for ( String l : locs ) sb.append( "<url><loc>" ).append( l ).append( "</loc></url>" );
        return sb.append( "</urlset>" ).toString();
    }
    private static SitemapConfig cfg( int maxPages, boolean sameHost ) {
        return new SitemapConfig( List.of( "https://ex.com/sitemap.xml" ), maxPages, 0,
            "WikantikCrawler/1.0", true, sameHost );
    }
    private static Set<String> uris( SyncBatch b ) {
        Set<String> s = new HashSet<>();
        for ( SourceItem i : b.items() ) s.add( i.sourceUri() );
        return s;
    }

    @Test void emitsOneItemPerListedUrl() {
        PageFetcher f = url -> switch ( url ) {
            case "https://ex.com/robots.txt" -> new FetchResult( 404, null, new byte[0], url );
            case "https://ex.com/sitemap.xml" -> xml( url, urlset( "https://ex.com/a", "https://ex.com/b" ) );
            case "https://ex.com/a", "https://ex.com/b" -> html( url, "<p>page</p>" );
            default -> new FetchResult( 404, null, new byte[0], url );
        };
        SyncBatch b = new SitemapSourceConnector( "sm1", cfg( 100, true ), f, ms -> {} ).poll( null );
        assertEquals( Set.of( "https://ex.com/a", "https://ex.com/b" ), uris( b ) );
        assertTrue( b.complete() );
        assertTrue( b.items().stream().allMatch( i -> "text/html".equals( i.contentType() ) ) );
    }

    @Test void sameHostOnlyDoesNotFetchForeignSubSitemap() {
        // A hostile index pointing at a foreign-host sub-sitemap must NOT be fetched when same_host_only
        // (fetch-amplification / SSRF-egress defense) — the connector never issues a GET to victim.com.
        String index = "<sitemapindex><sitemap><loc>https://ex.com/sm-ok.xml</loc></sitemap>"
            + "<sitemap><loc>https://victim.com/sm-evil.xml</loc></sitemap></sitemapindex>";
        Set<String> fetched = new HashSet<>();
        PageFetcher f = url -> {
            fetched.add( url );
            return switch ( url ) {
                case "https://ex.com/sitemap.xml" -> xml( url, index );
                case "https://ex.com/sm-ok.xml" -> xml( url, urlset( "https://ex.com/a" ) );
                case "https://ex.com/a" -> html( url, "<p>p</p>" );
                default -> new FetchResult( 404, null, new byte[0], url );   // robots.txt etc.
            };
        };
        Set<String> u = uris( new SitemapSourceConnector( "sm1", cfg( 100, true ), f, ms -> {} ).poll( null ) );
        assertTrue( u.contains( "https://ex.com/a" ) );
        assertFalse( fetched.contains( "https://victim.com/sm-evil.xml" ),
            "foreign-host sub-sitemap must never be fetched under same_host_only" );
    }

    @Test void recursesSitemapIndexOneLevel() {
        String index = "<sitemapindex><sitemap><loc>https://ex.com/sm-a.xml</loc></sitemap>"
            + "<sitemap><loc>https://ex.com/sm-b.xml</loc></sitemap></sitemapindex>";
        PageFetcher f = url -> switch ( url ) {
            case "https://ex.com/robots.txt" -> new FetchResult( 404, null, new byte[0], url );
            case "https://ex.com/sitemap.xml" -> xml( url, index );
            case "https://ex.com/sm-a.xml" -> xml( url, urlset( "https://ex.com/a" ) );
            case "https://ex.com/sm-b.xml" -> xml( url, urlset( "https://ex.com/b" ) );
            case "https://ex.com/a", "https://ex.com/b" -> html( url, "<p>p</p>" );
            default -> new FetchResult( 404, null, new byte[0], url );
        };
        assertEquals( Set.of( "https://ex.com/a", "https://ex.com/b" ),
            uris( new SitemapSourceConnector( "sm1", cfg( 100, true ), f, ms -> {} ).poll( null ) ) );
    }

    @Test void sameHostOnlyDropsForeignLocs() {
        PageFetcher f = url -> switch ( url ) {
            case "https://ex.com/robots.txt", "https://other.com/robots.txt" -> new FetchResult( 404, null, new byte[0], url );
            case "https://ex.com/sitemap.xml" -> xml( url, urlset( "https://ex.com/a", "https://other.com/evil" ) );
            case "https://ex.com/a" -> html( url, "<p>p</p>" );
            default -> new FetchResult( 404, null, new byte[0], url );
        };
        Set<String> u = uris( new SitemapSourceConnector( "sm1", cfg( 100, true ), f, ms -> {} ).poll( null ) );
        assertTrue( u.contains( "https://ex.com/a" ) );
        assertFalse( u.contains( "https://other.com/evil" ), "foreign-host loc must be dropped when same_host_only" );
    }

    @Test void respectsMaxPages() {
        PageFetcher f = url -> switch ( url ) {
            case "https://ex.com/robots.txt" -> new FetchResult( 404, null, new byte[0], url );
            case "https://ex.com/sitemap.xml" -> xml( url, urlset( "https://ex.com/a", "https://ex.com/b", "https://ex.com/c" ) );
            default -> html( url, "<p>p</p>" );
        };
        assertTrue( new SitemapSourceConnector( "sm1", cfg( 2, true ), f, ms -> {} ).poll( null ).items().size() <= 2 );
    }

    @Test void skipsRobotsDisallowedAndNonHtml() {
        PageFetcher f = url -> switch ( url ) {
            case "https://ex.com/robots.txt" -> new FetchResult( 200, "text/plain",
                "User-agent: *\nDisallow: /a\n".getBytes( StandardCharsets.UTF_8 ), url );
            case "https://ex.com/sitemap.xml" -> xml( url, urlset( "https://ex.com/a", "https://ex.com/b", "https://ex.com/img" ) );
            case "https://ex.com/b" -> html( url, "<p>b</p>" );
            case "https://ex.com/img" -> new FetchResult( 200, "image/png", new byte[]{1}, url );
            default -> new FetchResult( 404, null, new byte[0], url );
        };
        Set<String> u = uris( new SitemapSourceConnector( "sm1", cfg( 100, true ), f, ms -> {} ).poll( null ) );
        assertEquals( Set.of( "https://ex.com/b" ), u );   // /a robots-disallowed, /img non-html
    }
}
