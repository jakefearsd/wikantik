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
package com.wikantik.connectors.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.connectors.web.FeedConfig;
import com.wikantik.connectors.web.SitemapConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Feed and sitemap codec behaviour not covered by the happy-path tests: validation, dry-run clamping, JSON round trip. */
class FeedAndSitemapCodecTest {

    private static JsonObject json( final String s ) {
        return JsonParser.parseString( s ).getAsJsonObject();
    }

    @Test
    void feedRejectsMissingUrlsAndNegativeNumbers() {
        final var errors = ConnectorConfigCodec.validate( "feed",
                json( "{\"max_items\":-1,\"delay_ms\":-5}" ) ).errors();
        assertTrue( errors.containsKey( "feed_urls" ) );
        assertTrue( errors.containsKey( "max_items" ) );
        assertTrue( errors.containsKey( "delay_ms" ) );
    }

    @Test
    void sitemapRejectsMissingUrlsAndNegativeNumbers() {
        final var errors = ConnectorConfigCodec.validate( "sitemap",
                json( "{\"max_pages\":-1,\"delay_ms\":-5}" ) ).errors();
        assertTrue( errors.containsKey( "sitemap_urls" ) );
        assertTrue( errors.containsKey( "max_pages" ) );
        assertTrue( errors.containsKey( "delay_ms" ) );
    }

    @Test
    void feedBuildAppliesOverridesAndRoundTripsThroughJson() {
        final JsonObject in = json( "{\"feed_urls\":[\"https://a.example/feed\"],\"max_items\":2,"
                + "\"fetch_full_articles\":false,\"delay_ms\":250,\"user_agent\":\"UA\","
                + "\"respect_robots\":false,\"same_host_only\":false}" );
        final FeedConfig cfg = ( FeedConfig ) ConnectorConfigCodec.toConfig( "feed", in );
        assertFalse( cfg.fetchFullArticles() );
        assertEquals( 250L, cfg.delayMs() );
        assertEquals( "UA", cfg.userAgent() );
        assertFalse( cfg.respectRobots() );
        assertFalse( cfg.sameHostOnly() );
        assertEquals( in, ConnectorConfigCodec.toJson( "feed", cfg ) );
    }

    @Test
    void sitemapBuildAppliesOverridesAndRoundTripsThroughJson() {
        final JsonObject in = json( "{\"sitemap_urls\":[\"https://a.example/s.xml\"],\"max_pages\":7,"
                + "\"delay_ms\":250,\"user_agent\":\"UA\",\"respect_robots\":false,\"same_host_only\":false}" );
        final SitemapConfig cfg = ( SitemapConfig ) ConnectorConfigCodec.toConfig( "sitemap", in );
        assertEquals( 7, cfg.maxPages() );
        assertEquals( 250L, cfg.delayMs() );
        assertFalse( cfg.respectRobots() );
        assertEquals( in, ConnectorConfigCodec.toJson( "sitemap", cfg ) );
    }

    @Test
    void dryRunClampsCountsAndDelay() {
        final FeedConfig feed = ( FeedConfig ) ConnectorConfigCodec.toConfigForTest( "feed",
                json( "{\"feed_urls\":[\"https://a.example/feed\"],\"max_items\":50,\"delay_ms\":900}" ) );
        assertEquals( 3, feed.maxItems() );
        assertEquals( 0L, feed.delayMs() );
        final SitemapConfig sm = ( SitemapConfig ) ConnectorConfigCodec.toConfigForTest( "sitemap",
                json( "{\"sitemap_urls\":[\"https://a.example/s.xml\"],\"max_pages\":50,\"delay_ms\":900}" ) );
        assertEquals( 3, sm.maxPages() );
        assertEquals( 0L, sm.delayMs() );
    }

    @Test
    void buildingAnInvalidConfigThrows() {
        assertThrows( IllegalArgumentException.class, () -> ConnectorConfigCodec.toConfig( "feed", json( "{}" ) ) );
        assertThrows( IllegalArgumentException.class, () -> ConnectorConfigCodec.toJson( "filesystem", new Object() ) );
    }
}
