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
package com.wikantik.connectors.github;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.connectors.http.CappedBodySubscriber;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@link GithubApi} over the GitHub REST v3 API with java.net.http + gson. Package-private —
 *  built by {@link HttpGithubApiFactory} (tests construct it directly with a localhost base).
 *  Secret hygiene: the token lives only in the Authorization header — never in URLs, exception
 *  messages, or logs. */
final class HttpGithubApi implements GithubApi {

    static final int MAX_BODY_BYTES = 10 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds( 20 );

    private final String apiBase;
    private final String repo;
    private final String token;
    private final HttpClient client;

    HttpGithubApi( final String apiBase, final String repo, final String token ) {
        this.apiBase = apiBase;
        this.repo = repo;
        this.token = token;
        this.client = HttpClient.newBuilder().connectTimeout( TIMEOUT ).build();
    }

    @Override
    public String defaultBranch() throws IOException {
        final JsonObject o = JsonParser.parseString(
            new String( get( apiBase + "/repos/" + repo, "application/vnd.github+json" ),
                StandardCharsets.UTF_8 ) ).getAsJsonObject();
        return o.get( "default_branch" ).getAsString();
    }

    @Override
    public TreeListing listTree( final String branch ) throws IOException {
        final JsonObject o = JsonParser.parseString(
            new String( get( apiBase + "/repos/" + repo + "/git/trees/" + branch + "?recursive=1",
                "application/vnd.github+json" ), StandardCharsets.UTF_8 ) ).getAsJsonObject();
        final List< GithubFile > files = new ArrayList<>();
        for ( final JsonElement e : o.getAsJsonArray( "tree" ) ) {
            final JsonObject entry = e.getAsJsonObject();
            if ( !"blob".equals( entry.get( "type" ).getAsString() ) ) continue;
            files.add( new GithubFile( entry.get( "path" ).getAsString(),
                entry.get( "sha" ).getAsString(),
                entry.has( "size" ) ? entry.get( "size" ).getAsLong() : 0L ) );
        }
        return new TreeListing( files, o.has( "truncated" ) && o.get( "truncated" ).getAsBoolean() );
    }

    @Override
    public Optional< byte[] > rawContent( final String path, final String branch ) throws IOException {
        final HttpResponse< byte[] > r = send( apiBase + "/repos/" + repo + "/contents/" + path
            + "?ref=" + branch, "application/vnd.github.raw+json" );
        if ( r.statusCode() == 404 ) return Optional.empty();     // deleted between listing and fetch
        if ( r.statusCode() / 100 != 2 ) {
            throw new IOException( "GitHub API returned status " + r.statusCode() + " for contents of " + path );
        }
        return Optional.of( r.body() );
    }

    private byte[] get( final String url, final String accept ) throws IOException {
        final HttpResponse< byte[] > r = send( url, accept );
        if ( r.statusCode() / 100 != 2 ) {
            throw new IOException( "GitHub API returned status " + r.statusCode() + " for " + url );
        }
        return r.body();
    }

    private HttpResponse< byte[] > send( final String url, final String accept ) throws IOException {
        final HttpRequest req = HttpRequest.newBuilder( URI.create( url ) )
            .header( "Authorization", "Bearer " + token )
            .header( "Accept", accept )
            .header( "X-GitHub-Api-Version", "2022-11-28" )
            .header( "User-Agent", "Wikantik-Connector/1.0" )
            .timeout( TIMEOUT )
            .GET().build();
        try {
            return client.send( req, info -> new CappedBodySubscriber( MAX_BODY_BYTES ) );
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new IOException( "GitHub API request interrupted" );   // fixed string, no token
        }
    }
}
