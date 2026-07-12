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
import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.api.connectors.DriveAuthCoordinator;
import com.wikantik.api.connectors.SourceConnector;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.connectors.SyncOrchestrator;
import com.wikantik.connectors.credential.JdbcCredentialStore;
import com.wikantik.connectors.filesystem.FilesystemSourceConnector;
import com.wikantik.connectors.gdrive.DefaultDriveAuthCoordinator;
import com.wikantik.connectors.gdrive.DriveConfig;
import com.wikantik.connectors.gdrive.DriveSourceConnector;
import com.wikantik.connectors.gdrive.GoogleDriveApiFactory;
import com.wikantik.connectors.gdrive.GoogleDriveOAuthService;
import com.wikantik.connectors.runtime.ConnectorRegistry;
import com.wikantik.connectors.runtime.ConnectorRuntime;
import com.wikantik.connectors.runtime.ConnectorStatusReader;
import com.wikantik.connectors.state.JdbcSyncStateStore;
import com.wikantik.connectors.web.FeedConfig;
import com.wikantik.connectors.web.FeedSourceConnector;
import com.wikantik.connectors.web.HttpPageFetcher;
import com.wikantik.connectors.web.SitemapConfig;
import com.wikantik.connectors.web.SitemapSourceConnector;
import com.wikantik.connectors.web.WebCrawlerConfig;
import com.wikantik.connectors.web.WebCrawlerSourceConnector;
import com.wikantik.util.AesGcmCipher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/** Thin startup wiring: builds the connector runtime from config and registers it. No-op unless
 *  {@code wikantik.connectors.enabled=true}. Lives in the existing derived package (invariant #6).
 *  Named {@code *WiringHelper} so the decomposition ArchUnit rule permits engine access; it calls
 *  only {@code setManager} (never {@code getManager}), mirroring {@code OntologyWiringHelper}. */
public final class ConnectorWiringHelper {

    private static final Logger LOG = LogManager.getLogger( ConnectorWiringHelper.class );
    private static final String PREFIX = "wikantik.connectors.";

    private ConnectorWiringHelper() {}

    public static Optional< ConnectorRuntime > wireConnectors( final WikiEngine engine, final Properties props,
            final DataSource ds, final PageManager pm, final AttachmentManager am ) {
        // Registered unconditionally — an operator sets credentials before any connector is wired,
        // so the store must exist regardless of wikantik.connectors.enabled. Fail-closed: disabled
        // (enabled()==false) whenever no/invalid master key is configured.
        final CredentialStore credStore = new JdbcCredentialStore( ds, cipherFrom( props ) );
        engine.setManager( CredentialStore.class, credStore );
        if ( !Boolean.parseBoolean( props.getProperty( PREFIX + "enabled", "false" ) ) ) {
            return Optional.empty();
        }
        final Map< String, String > roots = filesystemRoots( props );
        final Map< String, WebCrawlerConfig > webcrawlers = webcrawlerConfigs( props );
        final Map< String, SitemapConfig > sitemaps = sitemapConfigs( props );
        final Map< String, FeedConfig > feeds = feedConfigs( props );
        final Map< String, DriveConfig > drives = driveConfigs( props );
        if ( roots.isEmpty() && webcrawlers.isEmpty() && sitemaps.isEmpty() && feeds.isEmpty() && drives.isEmpty() ) {
            LOG.info( "connectors enabled but no wikantik.connectors.filesystem.*.root or "
                + "wikantik.connectors.webcrawler.*.seeds or wikantik.connectors.sitemap.*.sitemap_urls or "
                + "wikantik.connectors.feed.*.feed_urls or wikantik.connectors.gdrive.*.folder_ids configured "
                + "— nothing to sync" );
            return Optional.empty();
        }
        final Map< String, SourceConnector > byId = new LinkedHashMap<>();
        final Map< String, String > typeById = new LinkedHashMap<>();
        for ( final Map.Entry< String, String > e : roots.entrySet() ) {
            byId.put( e.getKey(), new FilesystemSourceConnector( e.getKey(), Path.of( e.getValue() ) ) );
            typeById.put( e.getKey(), "filesystem" );
        }
        for ( final Map.Entry< String, WebCrawlerConfig > e : webcrawlers.entrySet() ) {
            byId.put( e.getKey(), new WebCrawlerSourceConnector( e.getKey(), e.getValue(),
                new HttpPageFetcher( e.getValue().userAgent(), java.time.Duration.ofSeconds( 20 ) ),
                ms -> { try { Thread.sleep( ms ); } catch ( final InterruptedException ie ) { Thread.currentThread().interrupt(); } } ) );
            typeById.put( e.getKey(), "webcrawler" );
        }
        for ( final Map.Entry< String, SitemapConfig > e : sitemaps.entrySet() ) {
            byId.put( e.getKey(), new SitemapSourceConnector( e.getKey(), e.getValue(),
                new HttpPageFetcher( e.getValue().userAgent(), java.time.Duration.ofSeconds( 20 ) ),
                ms -> { try { Thread.sleep( ms ); } catch ( final InterruptedException ie ) { Thread.currentThread().interrupt(); } } ) );
            typeById.put( e.getKey(), "sitemap" );
        }
        for ( final Map.Entry< String, FeedConfig > e : feeds.entrySet() ) {
            byId.put( e.getKey(), new FeedSourceConnector( e.getKey(), e.getValue(),
                new HttpPageFetcher( e.getValue().userAgent(), java.time.Duration.ofSeconds( 20 ) ),
                ms -> { try { Thread.sleep( ms ); } catch ( final InterruptedException ie ) { Thread.currentThread().interrupt(); } } ) );
            typeById.put( e.getKey(), "feed" );
        }
        final GoogleDriveApiFactory driveApiFactory = new GoogleDriveApiFactory();
        for ( final Map.Entry< String, DriveConfig > e : drives.entrySet() ) {
            final String id = e.getKey();
            byId.put( id, new DriveSourceConnector( id, e.getValue(),
                () -> credStore.get( id, "refresh_token" ), driveApiFactory ) );
            typeById.put( id, "gdrive" );
        }
        if ( !drives.isEmpty() ) {
            engine.setManager( DriveAuthCoordinator.class,
                new DefaultDriveAuthCoordinator( drives, new GoogleDriveOAuthService(), credStore ) );
        }
        final DerivedPageIngestionService ingestion = DerivedIngestionServiceFactory.build( engine, pm, am );
        final DerivedPageSinkAdapter sink = new DerivedPageSinkAdapter( ingestion, pm::deletePage, "connector-sync" );
        final SyncOrchestrator orchestrator = new SyncOrchestrator( new JdbcSyncStateStore( ds ), sink );
        final ConnectorRuntime runtime = new ConnectorRuntime(
            new ConnectorRegistry( byId, typeById ), orchestrator, new ConnectorStatusReader( ds ) );

        engine.setManager( ConnectorRuntime.class, runtime );
        final long intervalHours = parseLong( props, "sync.interval.hours", 0L );
        runtime.startScheduler( intervalHours );
        LOG.info( "connector runtime wired: {} connector(s), scheduler interval {}h", byId.size(), intervalHours );
        return Optional.of( runtime );
    }

    /** Builds the credential-encryption cipher from {@code wikantik.connectors.crypto.key} (base64,
     *  32-byte AES-256 key). Blank/absent or invalid ⇒ {@code null} (credential storage disabled,
     *  fail-closed); never logs the key value. Package-visible for testing. */
    static AesGcmCipher cipherFrom( final Properties props ) {
        final String b64 = props.getProperty( PREFIX + "crypto.key" );
        if ( b64 == null || b64.isBlank() ) return null;
        try {
            return new AesGcmCipher( AesGcmCipher.keyFromBase64( b64.trim() ) );
        } catch ( final RuntimeException e ) {
            LOG.warn( "wikantik.connectors.crypto.key is invalid (need base64 32-byte AES-256 key) — "
                + "credential storage disabled: {}", e.getMessage() );   // never log the key value
            return null;
        }
    }

    /** id → root for every {@code wikantik.connectors.filesystem.<id>.root} key. Package-visible for testing. */
    static Map< String, String > filesystemRoots( final Properties props ) {
        final String p = PREFIX + "filesystem.";
        final Map< String, String > out = new LinkedHashMap<>();
        for ( final String key : props.stringPropertyNames() ) {
            if ( key.startsWith( p ) && key.endsWith( ".root" ) ) {
                final String id = key.substring( p.length(), key.length() - ".root".length() );
                if ( !id.isBlank() && !id.contains( "." ) ) out.put( id, props.getProperty( key ).trim() );
            }
        }
        return out;
    }

    /** id → config for every {@code wikantik.connectors.webcrawler.<id>.seeds} key (plus its sibling
     *  per-id options). Package-visible for testing. An id with no non-blank {@code seeds} is skipped. */
    static Map< String, WebCrawlerConfig > webcrawlerConfigs( final Properties props ) {
        final String p = PREFIX + "webcrawler.";
        final Map< String, WebCrawlerConfig > out = new LinkedHashMap<>();
        for ( final String key : props.stringPropertyNames() ) {
            if ( key.startsWith( p ) && key.endsWith( ".seeds" ) ) {
                final String id = key.substring( p.length(), key.length() - ".seeds".length() );
                if ( id.isBlank() || id.contains( "." ) ) continue;
                final List< String > seeds = parseSeeds( props.getProperty( key ) );
                if ( seeds.isEmpty() ) continue;
                final String idPrefix = p + id + ".";
                out.put( id, new WebCrawlerConfig(
                    seeds,
                    Boolean.parseBoolean( props.getProperty( idPrefix + "same_host_only", "true" ).trim() ),
                    blankToNull( props.getProperty( idPrefix + "path_prefix" ) ),
                    parseInt( props, idPrefix + "max_pages", 100 ),
                    parseInt( props, idPrefix + "max_depth", 3 ),
                    parseLongValue( props, idPrefix + "delay_ms", 1000L ),
                    props.getProperty( idPrefix + "user_agent", "WikantikCrawler/1.0 (+https://wiki.wikantik.com)" ).trim(),
                    Boolean.parseBoolean( props.getProperty( idPrefix + "respect_robots", "true" ).trim() ) ) );
            }
        }
        return out;
    }

    /** id → config for every {@code wikantik.connectors.sitemap.<id>.sitemap_urls} key (plus its sibling
     *  per-id options). Package-visible for testing. An id with no non-blank {@code sitemap_urls} is skipped. */
    static Map< String, SitemapConfig > sitemapConfigs( final Properties props ) {
        final String p = PREFIX + "sitemap.";
        final Map< String, SitemapConfig > out = new LinkedHashMap<>();
        for ( final String key : props.stringPropertyNames() ) {
            if ( key.startsWith( p ) && key.endsWith( ".sitemap_urls" ) ) {
                final String id = key.substring( p.length(), key.length() - ".sitemap_urls".length() );
                if ( id.isBlank() || id.contains( "." ) ) continue;
                final List< String > sitemapUrls = parseSeeds( props.getProperty( key ) );
                if ( sitemapUrls.isEmpty() ) continue;
                final String idPrefix = p + id + ".";
                out.put( id, new SitemapConfig(
                    sitemapUrls,
                    parseInt( props, idPrefix + "max_pages", 500 ),
                    parseLongValue( props, idPrefix + "delay_ms", 1000L ),
                    props.getProperty( idPrefix + "user_agent", "WikantikCrawler/1.0 (+https://wiki.wikantik.com)" ).trim(),
                    Boolean.parseBoolean( props.getProperty( idPrefix + "respect_robots", "true" ).trim() ),
                    Boolean.parseBoolean( props.getProperty( idPrefix + "same_host_only", "true" ).trim() ) ) );
            }
        }
        return out;
    }

    /** id → config for every {@code wikantik.connectors.feed.<id>.feed_urls} key (plus its sibling
     *  per-id options). Package-visible for testing. An id with no non-blank {@code feed_urls} is skipped. */
    static Map< String, FeedConfig > feedConfigs( final Properties props ) {
        final String p = PREFIX + "feed.";
        final Map< String, FeedConfig > out = new LinkedHashMap<>();
        for ( final String key : props.stringPropertyNames() ) {
            if ( key.startsWith( p ) && key.endsWith( ".feed_urls" ) ) {
                final String id = key.substring( p.length(), key.length() - ".feed_urls".length() );
                if ( id.isBlank() || id.contains( "." ) ) continue;
                final List< String > feedUrls = parseSeeds( props.getProperty( key ) );
                if ( feedUrls.isEmpty() ) continue;
                final String idPrefix = p + id + ".";
                out.put( id, new FeedConfig(
                    feedUrls,
                    parseInt( props, idPrefix + "max_items", 100 ),
                    Boolean.parseBoolean( props.getProperty( idPrefix + "fetch_full_articles", "true" ).trim() ),
                    parseLongValue( props, idPrefix + "delay_ms", 1000L ),
                    props.getProperty( idPrefix + "user_agent", "WikantikCrawler/1.0 (+https://wiki.wikantik.com)" ).trim(),
                    Boolean.parseBoolean( props.getProperty( idPrefix + "respect_robots", "true" ).trim() ),
                    Boolean.parseBoolean( props.getProperty( idPrefix + "same_host_only", "true" ).trim() ) ) );
            }
        }
        return out;
    }

    /** id → config for every {@code wikantik.connectors.gdrive.<id>.folder_ids} key (plus its sibling
     *  per-id options). Package-visible for testing. An id with no non-blank {@code folder_ids}, or
     *  missing client_id/client_secret/redirect_uri, is skipped. */
    static Map< String, DriveConfig > driveConfigs( final Properties props ) {
        final String p = PREFIX + "gdrive.";
        final Map< String, DriveConfig > out = new LinkedHashMap<>();
        for ( final String key : props.stringPropertyNames() ) {
            if ( key.startsWith( p ) && key.endsWith( ".folder_ids" ) ) {
                final String id = key.substring( p.length(), key.length() - ".folder_ids".length() );
                if ( id.isBlank() || id.contains( "." ) ) continue;
                final List< String > folderIds = parseSeeds( props.getProperty( key ) );
                if ( folderIds.isEmpty() ) continue;
                final String idPrefix = p + id + ".";
                final String clientId = blankToNull( props.getProperty( idPrefix + "client_id" ) );
                final String clientSecret = blankToNull( props.getProperty( idPrefix + "client_secret" ) );
                final String redirectUri = blankToNull( props.getProperty( idPrefix + "redirect_uri" ) );
                if ( clientId == null || clientSecret == null || redirectUri == null ) {
                    LOG.warn( "gdrive '{}': missing client_id/client_secret/redirect_uri — skipping", id );
                    continue;
                }
                out.put( id, new DriveConfig( folderIds,
                    parseInt( props, idPrefix + "max_files", 500 ),
                    clientId, clientSecret, redirectUri,
                    props.getProperty( idPrefix + "export_mime", "text/markdown" ).trim() ) );
            }
        }
        return out;
    }

    private static List< String > parseSeeds( final String raw ) {
        final List< String > seeds = new ArrayList<>();
        for ( final String s : Arrays.asList( raw.split( "," ) ) ) {
            final String trimmed = s.trim();
            if ( !trimmed.isEmpty() ) seeds.add( trimmed );
        }
        return seeds;
    }

    private static String blankToNull( final String s ) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static int parseInt( final Properties props, final String key, final int def ) {
        try { return Integer.parseInt( props.getProperty( key, String.valueOf( def ) ).trim() ); }
        catch ( final NumberFormatException e ) { return def; }
    }

    private static long parseLongValue( final Properties props, final String key, final long def ) {
        try { return Long.parseLong( props.getProperty( key, String.valueOf( def ) ).trim() ); }
        catch ( final NumberFormatException e ) { return def; }
    }

    private static long parseLong( final Properties props, final String suffix, final long def ) {
        try { return Long.parseLong( props.getProperty( PREFIX + suffix, String.valueOf( def ) ).trim() ); }
        catch ( final NumberFormatException e ) { return def; }
    }
}
