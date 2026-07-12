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

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

/** {@link DriveOAuthService} backed by the Google OAuth2 client. Scope: drive.readonly. */
public final class GoogleDriveOAuthService implements DriveOAuthService {

    private static final Logger LOG = LogManager.getLogger( GoogleDriveOAuthService.class );
    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/auth";
    private static final String SCOPE = "https://www.googleapis.com/auth/drive.readonly";
    private static final GsonFactory JSON = GsonFactory.getDefaultInstance();

    @Override
    public String authorizationUrl( final String clientId, final String redirectUri, final String state ) {
        return new GoogleAuthorizationCodeRequestUrl( clientId, redirectUri, List.of( SCOPE ) )
            .setAccessType( "offline" )        // request a refresh token
            .set( "prompt", "consent" )        // force refresh-token issuance even on re-consent
            .setState( state )
            .build();
    }

    @Override
    public String exchangeCodeForRefreshToken( final String clientId, final String clientSecret,
            final String redirectUri, final String code ) throws IOException {
        try {
            final NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
            final GoogleTokenResponse resp;
            try {
                resp = new GoogleAuthorizationCodeTokenRequest(
                    transport, JSON, clientId, clientSecret, code, redirectUri ).execute();
            } catch ( final Exception e ) {
                // The Google client's exception message (e.g. TokenResponseException) can embed the
                // request parameters — including the OAuth code — verbatim. Never let that reach a
                // caller (DefaultDriveAuthCoordinator logs getMessage() on failure). Log a fixed,
                // sanitized message here (not the exception) and throw a NEW exception with a fixed
                // message and no cause, so the code/token can never surface in a log or stack trace.
                LOG.warn( "Google token exchange failed (exception detail withheld: may contain the auth code)" );
                throw new IOException( "Google token exchange failed" );
            }
            final String refreshToken = resp.getRefreshToken();
            if ( refreshToken == null || refreshToken.isBlank() ) {
                throw new IOException( "Google returned no refresh_token (re-consent with access_type=offline required)" );
            }
            return refreshToken;
        } catch ( final GeneralSecurityException e ) {
            throw new IOException( "trusted transport init failed", e );   // message carries no secret
        }
    }
}
