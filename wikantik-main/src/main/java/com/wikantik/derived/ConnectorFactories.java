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

import com.wikantik.connectors.config.ConnectorConfigCodec;
import com.wikantik.connectors.confluence.ConfluenceConfig;
import com.wikantik.connectors.confluence.ConfluenceSourceConnector;
import com.wikantik.connectors.confluence.HttpConfluenceApiFactory;
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
import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.api.connectors.SourceConnector;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongConsumer;

/** Registry of the {@link ConnectorFactory} for every admin-creatable connector type (the keys of
 *  {@link ConnectorConfigCodec#UI_TYPES}). Config classes are read from the codec registry, so
 *  there is no second type-to-class table. {@code filesystem} is properties-only and has no factory. */
public final class ConnectorFactories {

    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds( 20 );
    private static final HttpGithubApiFactory GITHUB_API_FACTORY = new HttpGithubApiFactory();
    private static final HttpConfluenceApiFactory CONFLUENCE_API_FACTORY = new HttpConfluenceApiFactory();

    private static final Map< String, ConnectorFactory< ? > > FACTORIES = registry(
        simple( "webcrawler", WebCrawlerConfig.class, ( id, cfg, creds ) -> new WebCrawlerSourceConnector( id, cfg,
            new HttpPageFetcher( cfg.userAgent(), FETCH_TIMEOUT ), sleeper() ) ),
        simple( "sitemap", SitemapConfig.class, ( id, cfg, creds ) -> new SitemapSourceConnector( id, cfg,
            new HttpPageFetcher( cfg.userAgent(), FETCH_TIMEOUT ), sleeper() ) ),
        simple( "feed", FeedConfig.class, ( id, cfg, creds ) -> new FeedSourceConnector( id, cfg,
            new HttpPageFetcher( cfg.userAgent(), FETCH_TIMEOUT ), sleeper() ) ),
        new DriveConnectorFactory(),
        simple( "github", GithubConfig.class, ( id, cfg, creds ) -> new GithubSourceConnector( id, cfg,
            () -> creds.get( id, "token" ), GITHUB_API_FACTORY ) ),
        simple( "confluence", ConfluenceConfig.class, ( id, cfg, creds ) -> new ConfluenceSourceConnector( id, cfg,
            () -> creds.get( id, "api_token" ), CONFLUENCE_API_FACTORY ) ) );

    private ConnectorFactories() {}

    /** The factory for {@code type}; empty for an unrecognized type, including {@code filesystem}. */
    public static Optional< ConnectorFactory< ? > > forType( final String type ) {
        return Optional.ofNullable( FACTORIES.get( type ) );
    }

    /** The config class the codec registered for {@code type}, which must be {@code expected} (the
     *  literal that types the factory); a disagreement fails fast rather than mis-dispatching. */
    static < C > Class< C > codecConfigClass( final String type, final Class< C > expected ) {
        final Class< ? > registered = ConnectorConfigCodec.codecFor( type ).orElseThrow().configClass();
        if ( registered != expected ) {
            throw new IllegalStateException( "codec config class " + registered + " differs from factory's " + expected );
        }
        return expected;
    }

    private static Map< String, ConnectorFactory< ? > > registry( final ConnectorFactory< ? >... factories ) {
        final Map< String, ConnectorFactory< ? > > byType = new LinkedHashMap<>();
        for ( final ConnectorFactory< ? > factory : factories ) byType.put( factory.type(), factory );
        return Map.copyOf( byType );
    }

    private static LongConsumer sleeper() {
        return ms -> { try { Thread.sleep( ms ); } catch ( final InterruptedException ie ) { Thread.currentThread().interrupt(); } };
    }

    /** Creates a connector from a typed config, given the credential store. */
    @FunctionalInterface
    private interface Creator< C > {
        SourceConnector create( String id, C config, CredentialStore creds );
    }

    private static < C > ConnectorFactory< C > simple( final String type, final Class< C > configClass, final Creator< C > creator ) {
        return new ConnectorFactory<>() {
            @Override public String type() { return type; }
            @Override public Class< C > configClass() { return codecConfigClass( type, configClass ); }
            @Override public SourceConnector create( final String id, final C config, final CredentialStore creds ) {
                return creator.create( id, config, creds );
            }
        };
    }
}
