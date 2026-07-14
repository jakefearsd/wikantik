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
package com.wikantik.connectors.gdrive;

import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.api.connectors.DriveAuthCoordinator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Map;
import java.util.Optional;

/** Drives the OAuth2 consent → refresh-token-store flow for the admin resource. Stores the refresh
 *  token under the credential name {@code refresh_token}. Never logs the OAuth code or any token. */
public final class DefaultDriveAuthCoordinator implements DriveAuthCoordinator {

    private static final Logger LOG = LogManager.getLogger( DefaultDriveAuthCoordinator.class );
    private static final String REFRESH_TOKEN = "refresh_token";

    private final Map< String, DriveConfig > byId;
    private final DriveOAuthService oauth;
    private final CredentialStore store;

    public DefaultDriveAuthCoordinator( final Map< String, DriveConfig > byId,
            final DriveOAuthService oauth, final CredentialStore store ) {
        this.byId = byId;
        this.oauth = oauth;
        this.store = store;
    }

    @Override
    public Optional< String > authorizationUrl( final String connectorId, final String state ) {
        final DriveConfig cfg = byId.get( connectorId );
        if ( cfg == null ) return Optional.empty();
        return Optional.of( oauth.authorizationUrl( cfg.clientId(), cfg.redirectUri(), state ) );
    }

    @Override
    public AuthResult completeAuthorization( final String connectorId, final String code ) {
        final DriveConfig cfg = byId.get( connectorId );
        if ( cfg == null ) {
            LOG.warn( "gdrive oauth: unknown connector id '{}'", connectorId );
            return AuthResult.UNKNOWN_CONNECTOR;
        }
        if ( !store.enabled() ) {
            LOG.warn( "gdrive oauth '{}': credential store disabled (no master key) — cannot store token", connectorId );
            return AuthResult.STORE_DISABLED;
        }
        try {
            final String refreshToken = oauth.exchangeCodeForRefreshToken(
                cfg.clientId(), cfg.clientSecret(), cfg.redirectUri(), code );
            store.put( connectorId, REFRESH_TOKEN, refreshToken );   // encrypted at rest by the store
            LOG.info( "gdrive oauth '{}': refresh token stored", connectorId );   // no token/code in the message
            return AuthResult.SUCCESS;
        } catch ( final Exception e ) {                              // never surface the code/token
            LOG.warn( "gdrive oauth '{}': code exchange failed: {}", connectorId, e.getMessage() );
            return AuthResult.EXCHANGE_FAILED;
        }
    }
}
