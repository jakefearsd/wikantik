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

import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.api.connectors.SourceConnector;
import com.wikantik.connectors.confluence.ConfluenceConfig;
import com.wikantik.connectors.confluence.ConfluenceSourceConnector;
import com.wikantik.connectors.confluence.HttpConfluenceApiFactory;
import com.wikantik.connectors.gdrive.DriveConfig;
import com.wikantik.connectors.gdrive.DriveSourceConnector;
import com.wikantik.connectors.gdrive.GoogleDriveApiFactory;
import com.wikantik.connectors.github.GithubConfig;
import com.wikantik.connectors.github.GithubSourceConnector;
import com.wikantik.connectors.github.HttpGithubApiFactory;
import com.wikantik.connectors.web.FeedConfig;
import com.wikantik.connectors.web.FeedSourceConnector;
import com.wikantik.connectors.web.HttpPageFetcher;
import com.wikantik.connectors.web.SitemapConfig;
import com.wikantik.connectors.web.SitemapSourceConnector;
import com.wikantik.connectors.web.WebCrawlerConfig;
import com.wikantik.connectors.web.WebCrawlerSourceConnector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.function.LongConsumer;

/** One shared per-type connector-construction path, extracted from {@link ConnectorWiringHelper}
 *  so properties-defined connectors (today) and DB-defined connectors (a later task) build the
 *  exact same {@link SourceConnector} instances. The API factories are stateless, so they are
 *  created once and reused across every {@link #build} call. */
public final class ConnectorAssembler {

    private static final Logger LOG = LogManager.getLogger( ConnectorAssembler.class );

    private static final GoogleDriveApiFactory DRIVE_API_FACTORY = new GoogleDriveApiFactory();
    private static final HttpGithubApiFactory GITHUB_API_FACTORY = new HttpGithubApiFactory();
    private static final HttpConfluenceApiFactory CONFLUENCE_API_FACTORY = new HttpConfluenceApiFactory();

    private ConnectorAssembler() {}

    /** Builds the {@link SourceConnector} for one type + config record, or {@code Optional.empty()}
     *  (with a {@code LOG.warn}) for an unrecognized {@code type}. {@code config} must be the config
     *  record matching {@code type} (e.g. {@link WebCrawlerConfig} for {@code "webcrawler"}); a
     *  mismatched record throws {@link ClassCastException} — callers are expected to pair type and
     *  config correctly, as {@link ConnectorWiringHelper} already does via its per-type maps. */
    public static Optional< SourceConnector > build( final String id, final String type, final Object config,
            final CredentialStore credStore ) {
        return switch ( type ) {
            case "webcrawler" -> {
                final WebCrawlerConfig cfg = ( WebCrawlerConfig ) config;
                yield Optional.of( new WebCrawlerSourceConnector( id, cfg,
                    new HttpPageFetcher( cfg.userAgent(), Duration.ofSeconds( 20 ) ), sleeper() ) );
            }
            case "sitemap" -> {
                final SitemapConfig cfg = ( SitemapConfig ) config;
                yield Optional.of( new SitemapSourceConnector( id, cfg,
                    new HttpPageFetcher( cfg.userAgent(), Duration.ofSeconds( 20 ) ), sleeper() ) );
            }
            case "feed" -> {
                final FeedConfig cfg = ( FeedConfig ) config;
                yield Optional.of( new FeedSourceConnector( id, cfg,
                    new HttpPageFetcher( cfg.userAgent(), Duration.ofSeconds( 20 ) ), sleeper() ) );
            }
            case "gdrive" -> {
                final DriveConfig cfg = resolveDriveSecret( id, ( DriveConfig ) config, credStore );
                yield Optional.of( new DriveSourceConnector( id, cfg,
                    () -> credStore.get( id, "refresh_token" ), DRIVE_API_FACTORY ) );
            }
            case "github" -> {
                final GithubConfig cfg = ( GithubConfig ) config;
                yield Optional.of( new GithubSourceConnector( id, cfg,
                    () -> credStore.get( id, "token" ), GITHUB_API_FACTORY ) );
            }
            case "confluence" -> {
                final ConfluenceConfig cfg = ( ConfluenceConfig ) config;
                yield Optional.of( new ConfluenceSourceConnector( id, cfg,
                    () -> credStore.get( id, "api_token" ), CONFLUENCE_API_FACTORY ) );
            }
            default -> {
                LOG.warn( "connector '{}': unknown type '{}' — skipping", id, type );
                yield Optional.empty();
            }
        };
    }

    /** DB-origin {@code DriveConfig} records carry no client secret (never persisted in the config
     *  row) — it lives in the {@link CredentialStore} under "client_secret" instead. Properties-origin
     *  records already have it populated, so this is a no-op for them. */
    private static DriveConfig resolveDriveSecret( final String id, final DriveConfig cfg, final CredentialStore credStore ) {
        if ( cfg.clientSecret() != null ) return cfg;
        return new DriveConfig( cfg.folderIds(), cfg.maxFiles(), cfg.clientId(),
            credStore.get( id, "client_secret" ).orElse( null ), cfg.redirectUri(), cfg.exportMimeType() );
    }

    private static LongConsumer sleeper() {
        return ms -> { try { Thread.sleep( ms ); } catch ( final InterruptedException ie ) { Thread.currentThread().interrupt(); } };
    }
}
