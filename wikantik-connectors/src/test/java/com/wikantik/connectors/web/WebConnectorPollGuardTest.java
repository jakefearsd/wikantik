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

import com.wikantik.api.connectors.SourceConnector;
import com.wikantik.api.connectors.SyncBatch;
import com.wikantik.api.connectors.SyncCursor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** poll() never throws (load-bearing): a RuntimeException mid-poll degrades to an empty INCOMPLETE batch with the input cursor. */
class WebConnectorPollGuardTest {

    private static final SyncCursor IN = new SyncCursor( "7" );

    /** robots.txt is a plain 404; every other fetch blows up. */
    private static final PageFetcher EXPLODING = url -> {
        if ( url.endsWith( "/robots.txt" ) ) return new FetchResult( 404, null, new byte[0], url );
        throw new IllegalStateException( "boom " + url );
    };

    private static void assertFailureShaped( final SourceConnector c ) {
        final SyncBatch b = assertDoesNotThrow( () -> c.poll( IN ) );
        assertTrue( b.items().isEmpty() );
        assertFalse( b.complete() );
        assertSame( IN, b.nextCursor() );
    }

    @Test void feedPollDegradesOnRuntimeException() {
        assertFailureShaped( new FeedSourceConnector( "f1", new FeedConfig( List.of( "https://ex.com/feed.xml" ),
            10, false, 0, "WikantikCrawler/1.0", true, true ), EXPLODING, ms -> {} ) );
    }

    @Test void sitemapPollDegradesOnRuntimeException() {
        assertFailureShaped( new SitemapSourceConnector( "s1", new SitemapConfig( List.of( "https://ex.com/sitemap.xml" ),
            10, 0, "WikantikCrawler/1.0", true, true ), EXPLODING, ms -> {} ) );
    }

    @Test void crawlerPollDegradesOnRuntimeException() {
        assertFailureShaped( new WebCrawlerSourceConnector( "w1", new WebCrawlerConfig( List.of( "https://ex.com/" ),
            true, null, 10, 2, 0, "WikantikCrawler/1.0", true ), EXPLODING, ms -> {} ) );
    }
}
