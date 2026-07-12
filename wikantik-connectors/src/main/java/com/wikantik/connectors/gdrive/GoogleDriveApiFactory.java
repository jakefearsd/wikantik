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

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import java.util.List;

/** Builds a {@link GoogleDriveApi} from OAuth2 user credentials (client id/secret + refresh token). */
public final class GoogleDriveApiFactory implements DriveApiFactory {

    private static final GsonFactory JSON = GsonFactory.getDefaultInstance();

    @Override
    public DriveApi create( final String clientId, final String clientSecret, final String refreshToken ) {
        try {
            final NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
            // UserCredentials.createScoped(...) resolves to the inherited GoogleCredentials method,
            // which returns GoogleCredentials (not covariant to UserCredentials) in this library
            // version — HttpCredentialsAdapter only needs the Credentials interface either way.
            final GoogleCredentials credentials = UserCredentials.newBuilder()
                .setClientId( clientId )
                .setClientSecret( clientSecret )
                .setRefreshToken( refreshToken )
                .build()
                .createScoped( List.of( DriveScopes.DRIVE_READONLY ) );
            final Drive drive = new Drive.Builder( transport, JSON, new HttpCredentialsAdapter( credentials ) )
                .setApplicationName( "Wikantik" )
                .build();
            return new GoogleDriveApi( drive );
        } catch ( final Exception e ) {
            // Build failures are surfaced to poll()'s catch as an unchecked error → empty batch.
            // Message carries no secret (client id/secret/refresh token are never in a JDK/Google build error).
            throw new IllegalStateException( "Drive client build failed: " + e.getMessage(), e );
        }
    }
}
