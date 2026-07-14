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
package com.wikantik.connectors.confluence;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.connectors.http.CappedBodySubscriber;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** {@link ConfluenceApi} over the Confluence Cloud v2 REST API (java.net.http + gson, HTTP Basic
 *  email:apiToken). Package-private — built by {@link HttpConfluenceApiFactory} (tests construct it
 *  directly with a localhost base). Secret hygiene: the token lives only in the Authorization
 *  header — never in URLs, exception messages, or logs. */
final class HttpConfluenceApi implements ConfluenceApi {

    private static final Logger LOG = LogManager.getLogger( HttpConfluenceApi.class );

    static final int MAX_BODY_BYTES = 10 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds( 20 );

    private final String baseUrl;
    private final String spaceKey;
    private final String authHeader;
    private final HttpClient client;

    HttpConfluenceApi( final String baseUrl, final String spaceKey, final String email, final String apiToken ) {
        this.baseUrl = baseUrl;
        this.spaceKey = spaceKey;
        this.authHeader = "Basic " + Base64.getEncoder()
            .encodeToString( ( email + ":" + apiToken ).getBytes( StandardCharsets.UTF_8 ) );
        this.client = HttpClient.newBuilder().connectTimeout( TIMEOUT ).build();
    }

    @Override
    public List< ConfluencePage > listPages( final int maxPages ) throws IOException {
        final String spaceId = spaceId();
        final List< ConfluencePage > out = new ArrayList<>();
        String next = "/wiki/api/v2/spaces/" + spaceId + "/pages?body-format=storage&limit=50";
        while ( next != null && out.size() < maxPages ) {
            final JsonObject o = getJson( baseUrl + next );
            for ( final JsonElement e : o.getAsJsonArray( "results" ) ) {
                if ( out.size() >= maxPages ) break;
                final JsonObject page = e.getAsJsonObject();
                final String id = page.has( "id" ) ? page.get( "id" ).getAsString() : null;
                final String title = page.has( "title" ) ? page.get( "title" ).getAsString() : null;
                if ( id == null || title == null ) {
                    LOG.warn( "confluence page {} ('{}') has no storage body/version in listing — skipping",
                        id == null ? "?" : id, title == null ? "?" : title );
                    continue;
                }
                final JsonObject body = page.getAsJsonObject( "body" );
                final JsonObject storage = body != null ? body.getAsJsonObject( "storage" ) : null;
                final JsonObject version = page.getAsJsonObject( "version" );
                final JsonObject links = page.getAsJsonObject( "_links" );
                if ( storage == null || !storage.has( "value" ) || version == null || !version.has( "number" )
                    || links == null || !links.has( "webui" ) ) {
                    LOG.warn( "confluence page {} ('{}') has no storage body/version in listing — skipping", id, title );
                    continue;
                }
                out.add( new ConfluencePage(
                    id,
                    title,
                    version.get( "number" ).getAsInt(),
                    links.get( "webui" ).getAsString(),
                    storage.get( "value" ).getAsString() ) );
            }
            final JsonObject links = o.getAsJsonObject( "_links" );
            next = links != null && links.has( "next" ) ? links.get( "next" ).getAsString() : null;
        }
        return out;
    }

    private String spaceId() throws IOException {
        final JsonObject o = getJson( baseUrl + "/wiki/api/v2/spaces?keys=" + spaceKey );
        final var results = o.getAsJsonArray( "results" );
        if ( results == null || results.isEmpty() ) {
            throw new IOException( "Confluence space not found: " + spaceKey );
        }
        return results.get( 0 ).getAsJsonObject().get( "id" ).getAsString();
    }

    private JsonObject getJson( final String url ) throws IOException {
        final HttpRequest req = HttpRequest.newBuilder( URI.create( url ) )
            .header( "Authorization", authHeader )
            .header( "Accept", "application/json" )
            .timeout( TIMEOUT )
            .GET().build();
        final HttpResponse< byte[] > r;
        try {
            r = client.send( req, info -> new CappedBodySubscriber( MAX_BODY_BYTES ) );
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new IOException( "Confluence API request interrupted" );   // fixed string, no token
        }
        if ( r.statusCode() / 100 != 2 ) {
            throw new IOException( "Confluence API returned status " + r.statusCode() + " for " + url );
        }
        return JsonParser.parseString( new String( r.body(), StandardCharsets.UTF_8 ) ).getAsJsonObject();
    }
}
