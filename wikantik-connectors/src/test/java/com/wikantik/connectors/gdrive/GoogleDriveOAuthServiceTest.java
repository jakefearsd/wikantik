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

import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class GoogleDriveOAuthServiceTest {

    private static MockHttpTransport respondWith( final int status, final String json ) {
        return new MockHttpTransport.Builder()
            .setLowLevelHttpResponse( new MockLowLevelHttpResponse()
                .setStatusCode( status )
                .setContentType( "application/json" )
                .setContent( json ) )
            .build();
    }

    // M-a: the load-bearing secret-hygiene contract — a failing exchange must throw a FIXED-string
    // IOException with NO cause: Google's TokenResponseException can embed the request parameters
    // (including the OAuth code) verbatim, and DefaultDriveAuthCoordinator logs e.getMessage().
    @Test void exchangeFailureThrowsSanitizedExceptionWithoutCodeOrCause() {
        MockHttpTransport failing = respondWith( 400,
            "{\"error\":\"invalid_grant\",\"error_description\":\"code SECRET-AUTH-CODE rejected\"}" );
        GoogleDriveOAuthService svc = new GoogleDriveOAuthService( failing );
        IOException e = assertThrows( IOException.class,
            () -> svc.exchangeCodeForRefreshToken( "cid", "csec", "https://w/cb", "SECRET-AUTH-CODE" ) );
        assertEquals( "Google token exchange failed", e.getMessage(), "fixed sanitized message only" );
        assertNull( e.getCause(), "no cause chained — the Google exception can embed the auth code" );
        assertFalse( String.valueOf( e ).contains( "SECRET-AUTH-CODE" ) );
    }

    @Test void exchangeSuccessReturnsRefreshToken() throws IOException {
        MockHttpTransport ok = respondWith( 200,
            "{\"access_token\":\"at\",\"refresh_token\":\"rt-1\",\"expires_in\":3600,\"token_type\":\"Bearer\"}" );
        assertEquals( "rt-1", new GoogleDriveOAuthService( ok )
            .exchangeCodeForRefreshToken( "cid", "csec", "https://w/cb", "CODE" ) );
    }

    @Test void missingRefreshTokenThrowsReconsentHint() {
        MockHttpTransport noRefresh = respondWith( 200,
            "{\"access_token\":\"at\",\"expires_in\":3600,\"token_type\":\"Bearer\"}" );
        IOException e = assertThrows( IOException.class, () -> new GoogleDriveOAuthService( noRefresh )
            .exchangeCodeForRefreshToken( "cid", "csec", "https://w/cb", "CODE" ) );
        assertTrue( e.getMessage().contains( "no refresh_token" ), e.getMessage() );
    }

    @Test void authorizationUrlContainsClientRedirectStateScopeAndOfflineConsent() {
        String url = new GoogleDriveOAuthService()
            .authorizationUrl( "CID.apps.googleusercontent.com", "https://wiki/cb", "st8-nonce" );
        assertTrue( url.startsWith( "https://accounts.google.com/o/oauth2/auth" ), url );
        assertTrue( url.contains( "client_id=CID.apps.googleusercontent.com" ), url );
        assertTrue( url.contains( "state=st8-nonce" ), url );
        assertTrue( url.contains( "access_type=offline" ), url );          // guarantees a refresh token
        assertTrue( url.contains( "prompt=consent" ), url );               // forces refresh-token re-issue
        assertTrue( url.contains( "drive.readonly" ), url );               // least-privilege scope
        assertTrue( url.contains( "redirect_uri=" ), url );
    }
}
