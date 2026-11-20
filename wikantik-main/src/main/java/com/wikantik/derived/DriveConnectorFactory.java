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
import com.wikantik.api.connectors.SourceConnector;
import com.wikantik.connectors.gdrive.DriveConfig;
import com.wikantik.connectors.gdrive.DriveSourceConnector;
import com.wikantik.connectors.gdrive.GoogleDriveApiFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;

/** The {@code gdrive} {@link ConnectorFactory}: owns Google Drive client-secret resolution and the
 *  default OAuth redirect URI, so neither lives in the assembler or the config service. */
final class DriveConnectorFactory implements ConnectorFactory< DriveConfig > {

    private static final Logger LOG = LogManager.getLogger( DriveConnectorFactory.class );
    static final String TYPE = "gdrive";
    private static final String CALLBACK_PATH = "/admin/connector-oauth/gdrive/callback";
    private static final GoogleDriveApiFactory API_FACTORY = new GoogleDriveApiFactory();

    @Override public String type() { return TYPE; }

    @Override public Class< DriveConfig > configClass() {
        return DriveConfig.class;
    }

    @Override public SourceConnector create( final String id, final DriveConfig config, final CredentialStore creds ) {
        return new DriveSourceConnector( id, resolveSecret( id, config, creds ),
            () -> creds.get( id, "refresh_token" ), API_FACTORY );
    }

    /** A missing {@code redirect_uri} is {@code wikantik.baseURL} + the gdrive OAuth callback path. */
    @Override public void applyDefaults( final JsonObject config, final Properties wikiProperties ) {
        if ( config != null && isAbsent( config ) ) {
            config.addProperty( "redirect_uri", wikiProperties.getProperty( "wikantik.baseURL", "" ) + CALLBACK_PATH );
        }
    }

    /** DB-origin {@code DriveConfig} records carry no client secret (never persisted in the config
     *  row) — it lives in the {@link CredentialStore} under "client_secret" instead. Properties-origin
     *  records already have it populated, so this is a no-op for them. */
    static DriveConfig resolveSecret( final String id, final DriveConfig cfg, final CredentialStore creds ) {
        if ( cfg.clientSecret() != null ) return cfg;
        return new DriveConfig( cfg.folderIds(), cfg.maxFiles(), cfg.clientId(),
            creds.get( id, "client_secret" ).orElse( null ), cfg.redirectUri(), cfg.exportMimeType() );
    }

    private static boolean isAbsent( final JsonObject config ) {
        if ( !config.has( "redirect_uri" ) || config.get( "redirect_uri" ).isJsonNull() ) return true;
        try {
            return config.get( "redirect_uri" ).getAsString().isBlank();
        } catch ( final RuntimeException e ) {
            // Admin-submitted JSON: a non-string value is bad input; treating it as absent lets validation report it.
            LOG.debug( "redirect_uri is not a readable string; treating it as absent: {}", e.getMessage() );
            return true;
        }
    }
}
