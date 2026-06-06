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
package com.wikantik.its.scim;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Minimal Authentik API client (token-authed) used to drive the SCIM full-loop
 * IT: create a SCIM provider targeting the wiki, provision/disable users, and
 * trigger syncs. All endpoints live under {@code /api/v3/}.
 *
 * <p>This is a transport helper only — it carries no assertions. The concrete
 * Authentik API payloads (provider create, user create, sync trigger, disable)
 * are authored in {@link AuthentikScimFullLoopIT} once the live container is
 * running and the exact {@code /api/v3/} shapes are confirmed.
 *
 * <p>The bootstrap API token ({@code it-authentik-api-token}) is seeded via
 * {@code AUTHENTIK_BOOTSTRAP_TOKEN} in the docker-maven-plugin container env.
 */
public class AuthentikClient {

    /** Base URL of the Authentik server, e.g. {@code http://localhost:19000}. */
    private final String base;

    /** Authentik API token (bootstrap value {@code it-authentik-api-token}). */
    private final String token;

    // Force HTTP/1.1: Authentik's Go front proxy (port 9000) rejects Java's default
    // HTTP/2 cleartext attempt with "400 Invalid HTTP request received".
    private final HttpClient http = HttpClient.newBuilder()
            .version( HttpClient.Version.HTTP_1_1 )
            .build();

    public AuthentikClient( final String base, final String token ) {
        this.base = base;
        this.token = token;
    }

    /**
     * HTTP POST {@code path} with a JSON body.
     *
     * @param path  path relative to {@link #base}, e.g. {@code /api/v3/providers/scim/}
     * @param json  request body
     * @return the raw HTTP response (caller inspects status + body)
     */
    public HttpResponse<String> post( final String path, final String json )
            throws Exception {
        return http.send( HttpRequest.newBuilder()
                .uri( URI.create( base + path ) )
                .header( "Authorization", "Bearer " + token )
                .header( "Content-Type", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString( json ) ).build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    /**
     * HTTP PATCH {@code path} with a JSON body.
     *
     * @param path  path relative to {@link #base}
     * @param json  request body
     * @return the raw HTTP response
     */
    public HttpResponse<String> patch( final String path, final String json )
            throws Exception {
        return http.send( HttpRequest.newBuilder()
                .uri( URI.create( base + path ) )
                .header( "Authorization", "Bearer " + token )
                .header( "Content-Type", "application/json" )
                .method( "PATCH", HttpRequest.BodyPublishers.ofString( json ) ).build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    /**
     * HTTP GET {@code path}.
     *
     * @param path  path relative to {@link #base}
     * @return the raw HTTP response
     */
    public HttpResponse<String> get( final String path ) throws Exception {
        return http.send( HttpRequest.newBuilder()
                .uri( URI.create( base + path ) )
                .header( "Authorization", "Bearer " + token )
                .GET().build(),
                HttpResponse.BodyHandlers.ofString() );
    }
}
