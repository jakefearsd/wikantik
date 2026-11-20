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

import com.google.gson.JsonObject;
import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.connectors.config.ConnectorConfigCodec;
import com.wikantik.connectors.gdrive.DriveConfig;
import com.wikantik.connectors.web.SitemapConfig;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorFactoriesTest {

    private final CredentialStore store = new CredentialStore() {
        @Override public boolean enabled() { return true; }
        @Override public void put( final String c, final String n, final String s ) {}
        @Override public Optional< String > get( final String c, final String n ) { return Optional.of( "sekrit-" + n ); }
        @Override public List< String > list( final String c ) { return List.of(); }
        @Override public void delete( final String c, final String n ) {}
    };

    @Test void everyUiTypeHasAFactoryWhoseConfigClassMatchesItsCodec() {
        for ( final String type : ConnectorConfigCodec.UI_TYPES ) {
            final ConnectorFactory< ? > factory = ConnectorFactories.forType( type )
                .orElseThrow( () -> new AssertionError( "no factory for " + type ) );
            assertEquals( type, factory.type() );
            assertEquals( ConnectorConfigCodec.codecFor( type ).orElseThrow().configClass(), factory.configClass() );
        }
    }

    @Test void unknownTypeHasNoFactory() {
        assertTrue( ConnectorFactories.forType( "nope" ).isEmpty() );
        assertTrue( ConnectorFactories.forType( "filesystem" ).isEmpty() );
    }

    @Test void mismatchedConfigIsEmptyAndWarnsWithIdTypeAndClass() {
        final List< String > warnings = new ArrayList<>();
        final String name = ConnectorAssembler.class.getName();
        final AbstractAppender appender = new AbstractAppender( "connectorFactoriesTest", null, null, true, Property.EMPTY_ARRAY ) {
            @Override public void append( final LogEvent event ) {
                if ( event.getLevel() == Level.WARN ) warnings.add( event.getMessage().getFormattedMessage() );
            }
        };
        appender.start();
        final LoggerContext ctx = ( LoggerContext ) LogManager.getContext( false );
        final Configuration config = ctx.getConfiguration();
        final LoggerConfig lc = LoggerConfig.newBuilder().setAdditivity( false ).setLevel( Level.WARN )
            .setLoggerName( name ).setIncludeLocation( "false" ).setRefs( new AppenderRef[ 0 ] ).setConfig( config ).build();
        lc.addAppender( appender, Level.WARN, null );
        config.addLogger( name, lc );
        ctx.updateLoggers();
        try {
            assertTrue( ConnectorAssembler.build( "x", "feed",
                new SitemapConfig( List.of( "https://e.com/sitemap.xml" ), 10, 0L, "UA", true, true ), store ).isEmpty() );
        } finally {
            config.removeLogger( name );
            ctx.updateLoggers();
            appender.stop();
        }
        assertEquals( 1, warnings.size(), warnings.toString() );
        final String w = warnings.get( 0 );
        assertTrue( w.contains( "x" ) && w.contains( "feed" ) && w.contains( "SitemapConfig" ), w );
    }

    @Test void driveSecretResolvesThroughTheCredentialStore() {
        final DriveConfig bare = new DriveConfig( List.of( "f" ), 10, "cid", null, "https://cb", "text/markdown" );
        final DriveConfig resolved = DriveConnectorFactory.resolveSecret( "g", bare, store );
        assertEquals( "sekrit-client_secret", resolved.clientSecret() );
        final DriveConfig withSecret = new DriveConfig( List.of( "f" ), 10, "cid", "own", "https://cb", "text/markdown" );
        assertEquals( "own", DriveConnectorFactory.resolveSecret( "g", withSecret, store ).clientSecret() );
    }

    @Test void driveFactoryDefaultsRedirectUriFromBaseUrl() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.baseURL", "https://w.example" );
        final JsonObject cfg = new JsonObject();
        ConnectorFactories.forType( "gdrive" ).orElseThrow().applyDefaults( cfg, props );
        assertEquals( "https://w.example/admin/connector-oauth/gdrive/callback", cfg.get( "redirect_uri" ).getAsString() );
        final JsonObject given = new JsonObject();
        given.addProperty( "redirect_uri", "https://mine" );
        ConnectorFactories.forType( "gdrive" ).orElseThrow().applyDefaults( given, props );
        assertEquals( "https://mine", given.get( "redirect_uri" ).getAsString() );
        final JsonObject other = new JsonObject();
        ConnectorFactories.forType( "feed" ).orElseThrow().applyDefaults( other, props );
        assertFalse( other.has( "redirect_uri" ) );
    }
}
