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
package com.wikantik.derived;

import com.wikantik.WikiEngine;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.connectors.config.ConnectorConfigRow;
import com.wikantik.connectors.config.JdbcConnectorConfigStore;
import com.wikantik.connectors.runtime.ConnectorRuntime;
import com.wikantik.connectors.runtime.ConnectorsDisabledException;
import com.wikantik.connectors.web.FeedConfig;
import com.wikantik.connectors.web.SitemapConfig;
import com.wikantik.connectors.web.WebCrawlerConfig;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ConnectorWiringHelperTest {

    @Test void parsesWebcrawlerConfigsById() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.webcrawler.site.seeds", "https://a.com/, https://a.com/docs" );
        p.setProperty( "wikantik.connectors.webcrawler.site.max_pages", "50" );
        p.setProperty( "wikantik.connectors.webcrawler.site.path_prefix", "/docs" );
        Map< String, WebCrawlerConfig > cfgs = ConnectorWiringHelper.webcrawlerConfigs( p );
        assertEquals( 1, cfgs.size() );
        WebCrawlerConfig c = cfgs.get( "site" );
        assertEquals( List.of( "https://a.com/", "https://a.com/docs" ), c.seeds() );
        assertEquals( 50, c.maxPages() );
        assertEquals( "/docs", c.pathPrefix() );
        assertTrue( c.sameHostOnly() );          // default
        assertTrue( c.respectRobots() );          // default
    }

    @Test void webcrawlerRequiresSeeds() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.webcrawler.nope.max_pages", "10" );  // no seeds → skipped
        assertTrue( ConnectorWiringHelper.webcrawlerConfigs( p ).isEmpty() );
    }

    @Test void parsesFilesystemRootsById() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.filesystem.docs.root", "/data/docs" );
        p.setProperty( "wikantik.connectors.filesystem.wiki-src.root", "/data/wiki" );
        p.setProperty( "wikantik.connectors.filesystem.docs.ignored", "x" ); // non-.root key ignored
        Map< String, String > roots = ConnectorWiringHelper.filesystemRoots( p );
        assertEquals( 2, roots.size() );
        assertEquals( "/data/docs", roots.get( "docs" ) );
        assertEquals( "/data/wiki", roots.get( "wiki-src" ) );
    }

    // An operator typo in a filesystem root must not throw out of wireConnectors (it runs during
    // engine startup) — the bad connector is skipped, the rest of the wiring proceeds.
    @Test void parseRootInvalidPathIsEmptyNotThrow() {
        assertTrue( ConnectorWiringHelper.parseRoot( "docs", "/data/docs" ).isPresent() );
        assertTrue( ConnectorWiringHelper.parseRoot( "bad", "/data/\0oops" ).isEmpty() );
    }

    // ---- full wireConnectors() wiring (Task 9: DB-backed config store, kill-switch default true) ----

    /** Fresh H2 database with the {@code connector_configs} table — every wireConnectors() call now
     *  reaches the DB (ConnectorConfigService.rebuild() runs unconditionally, even when disabled). */
    private static DataSource h2WithConnectorConfigsSchema() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:wiring" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL" );
        try ( Connection c = h2.getConnection(); var s = c.createStatement() ) {
            s.execute( "CREATE TABLE IF NOT EXISTS connector_configs (connector_id VARCHAR PRIMARY KEY,"
                + " connector_type VARCHAR NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,"
                + " sync_interval_hours INT NOT NULL DEFAULT 0, config VARCHAR NOT NULL,"
                + " cluster VARCHAR, default_tags VARCHAR, page_prefix VARCHAR,"
                + " created TIMESTAMP WITH TIME ZONE DEFAULT now(), modified TIMESTAMP WITH TIME ZONE DEFAULT now())" );
        }
        return h2;
    }

    @Test void runtimeWiresWithZeroConnectorsByDefault() throws Exception {
        // Empty properties → enabled defaults true, but no properties-origin connectors and no DB
        // rows means the registry is legitimately empty. wireConnectors() must still return a
        // present ConnectorRuntime (Task 9: runtime is always registered, even with zero connectors).
        final WikiEngine engine = mock( WikiEngine.class );
        final DataSource ds = h2WithConnectorConfigsSchema();
        final PageManager pm = mock( PageManager.class );
        final AttachmentManager am = mock( AttachmentManager.class );

        final Optional< ConnectorRuntime > result =
            ConnectorWiringHelper.wireConnectors( engine, new Properties(), ds, pm, am );

        assertTrue( result.isPresent() );
        assertTrue( result.get().registry().ids().isEmpty() );
        assertTrue( result.get().syncingEnabled() );
    }

    @Test void enabledFalseSuppressesSyncing() throws Exception {
        // wikantik.connectors.enabled=false → the runtime is still wired (DB-backed config CRUD
        // keeps working), but syncing itself is refused and the due-tick scheduler never starts.
        final WikiEngine engine = mock( WikiEngine.class );
        final DataSource ds = h2WithConnectorConfigsSchema();
        final PageManager pm = mock( PageManager.class );
        final AttachmentManager am = mock( AttachmentManager.class );
        final Properties props = new Properties();
        props.setProperty( "wikantik.connectors.enabled", "false" );

        final Optional< ConnectorRuntime > result =
            ConnectorWiringHelper.wireConnectors( engine, props, ds, pm, am );

        assertTrue( result.isPresent() );
        final ConnectorRuntime runtime = result.get();
        assertFalse( runtime.syncingEnabled() );
        assertFalse( runtime.isSchedulerRunning() );
        assertThrows( ConnectorsDisabledException.class, () -> runtime.syncNow( "whatever" ) );
    }

    @Test void dbRowsJoinPropertiesConnectors() throws Exception {
        // A DB-origin connector row (github) alongside a properties-origin connector (feed) — both
        // must end up in the rebuilt registry, correctly attributed to their origin.
        final WikiEngine engine = mock( WikiEngine.class );
        final DataSource ds = h2WithConnectorConfigsSchema();
        new JdbcConnectorConfigStore( ds ).upsert( new ConnectorConfigRow(
            "gh", "github", true, 0, "{\"repo\":\"jake/notes\"}", null, null, null ) );
        final PageManager pm = mock( PageManager.class );
        final AttachmentManager am = mock( AttachmentManager.class );
        final Properties props = new Properties();
        props.setProperty( "wikantik.connectors.feed.news.feed_urls", "https://example.com/feed.xml" );

        final Optional< ConnectorRuntime > result =
            ConnectorWiringHelper.wireConnectors( engine, props, ds, pm, am );

        assertTrue( result.isPresent() );
        final ConnectorRuntime runtime = result.get();
        assertTrue( runtime.registry().get( "gh" ).isPresent(), "db-origin connector must be registered" );
        assertEquals( "db", runtime.registry().originOf( "gh" ) );
        assertTrue( runtime.registry().get( "news" ).isPresent(), "properties-origin connector must be registered" );
        assertEquals( "properties", runtime.registry().originOf( "news" ) );
    }

    @Test void cipherFromValidKeyBuildsCipher() {
        Properties p = new Properties();
        byte[] k = new byte[32]; new java.security.SecureRandom().nextBytes( k );
        p.setProperty( "wikantik.connectors.crypto.key", java.util.Base64.getEncoder().encodeToString( k ) );
        assertNotNull( ConnectorWiringHelper.cipherFrom( p ) );
    }

    @Test void cipherFromAbsentOrInvalidKeyIsNull() {
        assertNull( ConnectorWiringHelper.cipherFrom( new Properties() ) );                       // absent
        Properties bad = new Properties();
        bad.setProperty( "wikantik.connectors.crypto.key", "not-base64!!" );
        assertNull( ConnectorWiringHelper.cipherFrom( bad ) );                                    // invalid → null (no throw)
        Properties short_ = new Properties();
        short_.setProperty( "wikantik.connectors.crypto.key", java.util.Base64.getEncoder().encodeToString( new byte[16] ) );
        assertNull( ConnectorWiringHelper.cipherFrom( short_ ) );                                 // 16 bytes → null
    }

    @Test void noFilesystemRootsMeansEmptyMap() {
        assertTrue( ConnectorWiringHelper.filesystemRoots( new Properties() ).isEmpty() );
    }

    @Test void parsesSitemapConfigsById() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.sitemap.site.sitemap_urls", "https://a.com/sitemap.xml, https://a.com/sm2.xml" );
        p.setProperty( "wikantik.connectors.sitemap.site.max_pages", "250" );
        p.setProperty( "wikantik.connectors.sitemap.site.same_host_only", "false" );
        Map< String, SitemapConfig > cfgs = ConnectorWiringHelper.sitemapConfigs( p );
        assertEquals( 1, cfgs.size() );
        SitemapConfig c = cfgs.get( "site" );
        assertEquals( List.of( "https://a.com/sitemap.xml", "https://a.com/sm2.xml" ), c.sitemapUrls() );
        assertEquals( 250, c.maxPages() );
        assertFalse( c.sameHostOnly() );
        assertTrue( c.respectRobots() );   // default
    }

    @Test void sitemapRequiresUrls() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.sitemap.nope.max_pages", "10" );  // no sitemap_urls → skipped
        assertTrue( ConnectorWiringHelper.sitemapConfigs( p ).isEmpty() );
    }

    @Test void parsesFeedConfigsById() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.feed.news.feed_urls", "https://a.com/rss, https://a.com/atom" );
        p.setProperty( "wikantik.connectors.feed.news.max_items", "40" );
        p.setProperty( "wikantik.connectors.feed.news.fetch_full_articles", "false" );
        Map< String, FeedConfig > cfgs = ConnectorWiringHelper.feedConfigs( p );
        assertEquals( 1, cfgs.size() );
        FeedConfig c = cfgs.get( "news" );
        assertEquals( List.of( "https://a.com/rss", "https://a.com/atom" ), c.feedUrls() );
        assertEquals( 40, c.maxItems() );
        assertFalse( c.fetchFullArticles() );
        assertTrue( c.respectRobots() );   // default
    }

    @Test void feedDefaultsFetchFullArticlesTrue() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.feed.news.feed_urls", "https://a.com/rss" );
        assertTrue( ConnectorWiringHelper.feedConfigs( p ).get( "news" ).fetchFullArticles() );  // default true
    }

    @Test void feedRequiresUrls() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.feed.nope.max_items", "10" );
        assertTrue( ConnectorWiringHelper.feedConfigs( p ).isEmpty() );
    }

    @Test void driveConfigsParsesRequiredFieldsAndDefaults() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.gdrive.gd.folder_ids", "F1, F2" );
        p.setProperty( "wikantik.connectors.gdrive.gd.client_id", "cid" );
        p.setProperty( "wikantik.connectors.gdrive.gd.client_secret", "csec" );
        p.setProperty( "wikantik.connectors.gdrive.gd.redirect_uri", "https://w/cb" );
        Map<String, com.wikantik.connectors.gdrive.DriveConfig> cfgs = ConnectorWiringHelper.driveConfigs( p );
        assertEquals( 1, cfgs.size() );
        var c = cfgs.get( "gd" );
        assertEquals( java.util.List.of( "F1", "F2" ), c.folderIds() );
        assertEquals( "cid", c.clientId() );
        assertEquals( "https://w/cb", c.redirectUri() );
        assertEquals( 500, c.maxFiles() );                     // default
        assertEquals( "text/markdown", c.exportMimeType() );   // default
    }
    @Test void driveConfigSkippedWhenRequiredFieldMissing() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.gdrive.gd.folder_ids", "F1" );
        // no client_id/secret/redirect_uri
        assertTrue( ConnectorWiringHelper.driveConfigs( p ).isEmpty() );
    }

    @Test void githubConfigsParsesRequiredFieldsAndDefaults() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.github.handbook.repo", "acme/handbook" );
        p.setProperty( "wikantik.connectors.github.handbook.branch", "main" );
        p.setProperty( "wikantik.connectors.github.handbook.path_prefix", "docs/" );
        p.setProperty( "wikantik.connectors.github.handbook.max_files", "42" );
        p.setProperty( "wikantik.connectors.github.min.repo", "acme/min" );   // defaults only
        var cfgs = ConnectorWiringHelper.githubConfigs( p );
        assertEquals( 2, cfgs.size() );
        var c = cfgs.get( "handbook" );
        assertEquals( "acme/handbook", c.repo() );
        assertEquals( "main", c.branch() );
        assertEquals( "docs/", c.pathPrefix() );
        assertEquals( 42, c.maxFiles() );
        var m = cfgs.get( "min" );
        assertNull( m.branch() );
        assertNull( m.pathPrefix() );
        assertEquals( 500, m.maxFiles() );
    }

    @Test void githubConfigSkippedWhenRepoMalformed() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.github.bad.repo", "not-owner-slash-name" );
        p.setProperty( "wikantik.connectors.github.bad2.repo", "a/b/c" );
        assertTrue( ConnectorWiringHelper.githubConfigs( p ).isEmpty() );
    }

    @Test void confluenceConfigsParsesRequiredFieldsAndDefaults() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.confluence.acme.space_key", "ENG" );
        p.setProperty( "wikantik.connectors.confluence.acme.base_url", "https://acme.atlassian.net" );
        p.setProperty( "wikantik.connectors.confluence.acme.email", "bot@acme.com" );
        var cfgs = ConnectorWiringHelper.confluenceConfigs( p );
        assertEquals( 1, cfgs.size() );
        var c = cfgs.get( "acme" );
        assertEquals( "https://acme.atlassian.net", c.baseUrl() );
        assertEquals( "ENG", c.spaceKey() );
        assertEquals( "bot@acme.com", c.email() );
        assertEquals( 500, c.maxPages() );
    }

    @Test void confluenceConfigSkippedWhenBaseUrlOrEmailMissing() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.confluence.a.space_key", "ENG" );   // no base_url/email
        p.setProperty( "wikantik.connectors.confluence.b.space_key", "OPS" );
        p.setProperty( "wikantik.connectors.confluence.b.base_url", "https://x.atlassian.net" );  // no email
        assertTrue( ConnectorWiringHelper.confluenceConfigs( p ).isEmpty() );
    }
}
