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
import com.wikantik.connectors.confluence.ConfluenceConfig;
import com.wikantik.connectors.gdrive.DriveConfig;
import com.wikantik.connectors.github.GithubConfig;
import com.wikantik.connectors.web.FeedConfig;
import com.wikantik.connectors.web.SitemapConfig;
import com.wikantik.connectors.web.WebCrawlerConfig;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConnectorConfigCodecTest {

    @Test void uiTypesExcludesFilesystem() {
        assertEquals( Set.of( "webcrawler", "sitemap", "feed", "gdrive", "github", "confluence" ), ConnectorConfigCodec.UI_TYPES );
        assertFalse( ConnectorConfigCodec.UI_TYPES.contains( "filesystem" ) );
    }

    // ---- webcrawler --------------------------------------------------------------------------

    @Test void webcrawlerHappyPathAppliesDefaults() {
        final JsonObject c = JsonParser.parseString( "{\"seeds\":[\"https://example.com\"]}" ).getAsJsonObject();
        assertTrue( ConnectorConfigCodec.validate( "webcrawler", c ).ok() );
        final WebCrawlerConfig cfg = ( WebCrawlerConfig ) ConnectorConfigCodec.toConfig( "webcrawler", c );
        assertEquals( java.util.List.of( "https://example.com" ), cfg.seeds() );
        assertTrue( cfg.sameHostOnly() );
        assertNull( cfg.pathPrefix() );
        assertEquals( 100, cfg.maxPages() );
        assertEquals( 3, cfg.maxDepth() );
        assertEquals( 1000L, cfg.delayMs() );
        assertEquals( "WikantikCrawler/1.0 (+https://wiki.wikantik.com)", cfg.userAgent() );
        assertTrue( cfg.respectRobots() );
    }

    @Test void webcrawlerRequiresHttpSeeds() {
        final JsonObject c = JsonParser.parseString( "{\"seeds\":[\"ftp://x\"]}" ).getAsJsonObject();
        assertTrue( ConnectorConfigCodec.validate( "webcrawler", c ).errors().containsKey( "seeds" ) );
    }

    // ---- sitemap -------------------------------------------------------------------------------

    @Test void sitemapHappyPathAppliesDefaults() {
        final JsonObject c = JsonParser.parseString( "{\"sitemap_urls\":[\"https://example.com/sitemap.xml\"]}" ).getAsJsonObject();
        assertTrue( ConnectorConfigCodec.validate( "sitemap", c ).ok() );
        final SitemapConfig cfg = ( SitemapConfig ) ConnectorConfigCodec.toConfig( "sitemap", c );
        assertEquals( java.util.List.of( "https://example.com/sitemap.xml" ), cfg.sitemapUrls() );
        assertEquals( 500, cfg.maxPages() );
        assertEquals( 1000L, cfg.delayMs() );
        assertEquals( "WikantikCrawler/1.0 (+https://wiki.wikantik.com)", cfg.userAgent() );
        assertTrue( cfg.respectRobots() );
        assertTrue( cfg.sameHostOnly() );
    }

    @Test void sitemapRejectsBadUrl() {
        final JsonObject c = JsonParser.parseString( "{\"sitemap_urls\":[\"not-a-url\"]}" ).getAsJsonObject();
        assertTrue( ConnectorConfigCodec.validate( "sitemap", c ).errors().containsKey( "sitemap_urls" ) );
    }

    // ---- feed ----------------------------------------------------------------------------------

    @Test void feedHappyPathAppliesDefaults() {
        final JsonObject c = JsonParser.parseString( "{\"feed_urls\":[\"https://example.com/rss\"]}" ).getAsJsonObject();
        assertTrue( ConnectorConfigCodec.validate( "feed", c ).ok() );
        final FeedConfig cfg = ( FeedConfig ) ConnectorConfigCodec.toConfig( "feed", c );
        assertEquals( java.util.List.of( "https://example.com/rss" ), cfg.feedUrls() );
        assertEquals( 100, cfg.maxItems() );
        assertTrue( cfg.fetchFullArticles() );
        assertEquals( 1000L, cfg.delayMs() );
        assertEquals( "WikantikCrawler/1.0 (+https://wiki.wikantik.com)", cfg.userAgent() );
        assertTrue( cfg.respectRobots() );
        assertTrue( cfg.sameHostOnly() );
    }

    @Test void feedRejectsBadUrl() {
        final JsonObject c = JsonParser.parseString( "{\"feed_urls\":[\"javascript:alert(1)\"]}" ).getAsJsonObject();
        assertTrue( ConnectorConfigCodec.validate( "feed", c ).errors().containsKey( "feed_urls" ) );
    }

    // ---- gdrive --------------------------------------------------------------------------------

    @Test void gdriveHappyPathAppliesDefaults() {
        final JsonObject c = JsonParser.parseString(
            "{\"folder_ids\":[\"folder1\"],\"client_id\":\"cid\",\"redirect_uri\":\"https://cb.example.com\"}" ).getAsJsonObject();
        assertTrue( ConnectorConfigCodec.validate( "gdrive", c ).ok() );
        final DriveConfig cfg = ( DriveConfig ) ConnectorConfigCodec.toConfig( "gdrive", c );
        assertEquals( java.util.List.of( "folder1" ), cfg.folderIds() );
        assertEquals( 500, cfg.maxFiles() );
        assertEquals( "cid", cfg.clientId() );
        assertNull( cfg.clientSecret() );
        assertEquals( "https://cb.example.com", cfg.redirectUri() );
        assertEquals( "text/markdown", cfg.exportMimeType() );
    }

    @Test void gdriveRequiresFoldersAndClientId() {
        final var v = ConnectorConfigCodec.validate( "gdrive", JsonParser.parseString( "{}" ).getAsJsonObject() );
        assertTrue( v.errors().containsKey( "folder_ids" ) );
        assertTrue( v.errors().containsKey( "client_id" ) );
        assertFalse( v.errors().containsKey( "client_secret" ) );   // secret is NOT part of config JSON
    }

    // ---- github --------------------------------------------------------------------------------

    @Test void githubHappyPathAppliesDefaults() {
        final JsonObject c = JsonParser.parseString( "{\"repo\":\"jake/notes\"}" ).getAsJsonObject();
        assertTrue( ConnectorConfigCodec.validate( "github", c ).ok() );
        final GithubConfig cfg = ( GithubConfig ) ConnectorConfigCodec.toConfig( "github", c );
        assertEquals( "jake/notes", cfg.repo() );
        assertNull( cfg.branch() );
        assertEquals( 500, cfg.maxFiles() );
    }

    @Test void githubRejectsBadRepoShape() {
        final JsonObject c = JsonParser.parseString( "{\"repo\":\"not-a-repo\"}" ).getAsJsonObject();
        final var v = ConnectorConfigCodec.validate( "github", c );
        assertFalse( v.ok() );
        assertTrue( v.errors().containsKey( "repo" ) );
    }

    // ---- confluence ----------------------------------------------------------------------------

    @Test void confluenceHappyPathAppliesDefaults() {
        final JsonObject c = JsonParser.parseString(
            "{\"base_url\":\"https://acme.atlassian.net\",\"space_key\":\"SP\",\"email\":\"a@b.com\"}" ).getAsJsonObject();
        assertTrue( ConnectorConfigCodec.validate( "confluence", c ).ok() );
        final ConfluenceConfig cfg = ( ConfluenceConfig ) ConnectorConfigCodec.toConfig( "confluence", c );
        assertEquals( "https://acme.atlassian.net", cfg.baseUrl() );
        assertEquals( "SP", cfg.spaceKey() );
        assertEquals( "a@b.com", cfg.email() );
        assertEquals( 500, cfg.maxPages() );
    }

    @Test void confluenceRequiresBaseUrlSpaceEmail() {
        final var v = ConnectorConfigCodec.validate( "confluence", JsonParser.parseString( "{}" ).getAsJsonObject() );
        assertEquals( Set.of( "base_url", "space_key", "email" ), v.errors().keySet() );
    }

    // ---- id validation -------------------------------------------------------------------------

    @Test void idValidation() {
        assertTrue( ConnectorConfigCodec.validateId( "team-notes-2" ).ok() );
        assertFalse( ConnectorConfigCodec.validateId( "Bad.Id" ).ok() );
        assertFalse( ConnectorConfigCodec.validateId( "" ).ok() );
    }

    // ---- test-connection clamping ---------------------------------------------------------------

    @Test void testClampShrinksCaps() {
        final JsonObject c = JsonParser.parseString(
            "{\"seeds\":[\"https://example.com\"],\"max_pages\":500,\"max_depth\":4}" ).getAsJsonObject();
        final WebCrawlerConfig cfg = ( WebCrawlerConfig ) ConnectorConfigCodec.toConfigForTest( "webcrawler", c );
        assertEquals( 3, cfg.maxPages() );
        assertEquals( 1, cfg.maxDepth() );
        assertEquals( 0L, cfg.delayMs() );
    }

    // ---- unknown / rejected types ----------------------------------------------------------------

    @Test void unknownTypeRejected() {
        assertTrue( ConnectorConfigCodec.validate( "filesystem", new JsonObject() ).errors().containsKey( "connector_type" ) );
    }

    @Test void toConfigThrowsWhenInvalid() {
        final JsonObject c = JsonParser.parseString( "{}" ).getAsJsonObject();
        assertThrows( IllegalArgumentException.class, () -> ConnectorConfigCodec.toConfig( "github", c ) );
    }
}
