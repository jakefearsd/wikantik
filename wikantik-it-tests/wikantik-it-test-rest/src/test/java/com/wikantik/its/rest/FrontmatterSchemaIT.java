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
package com.wikantik.its.rest;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke for {@code GET /api/frontmatter-schema} against a
 * Cargo-deployed Tomcat.
 *
 * <p>Asserts the contract that is load-bearing for the React editor's
 * widget rendering: the response must be 200, carry a {@code fields}
 * array, and include a {@code type} field whose canonical values contain
 * {@code "article"} and whose {@code open} flag is {@code true}.</p>
 */
public class FrontmatterSchemaIT {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken< Map< String, Object > >() {}.getType();

    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
            "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
    }

    /**
     * The web.xml sets {@code <secure>true</secure>} on the session cookie.
     * Java's {@link java.net.InMemoryCookieStore} filters Secure cookies on plain
     * {@code http://} requests. This wrapper fools the store into treating every
     * URI as HTTPS so the JSESSIONID is always forwarded.
     */
    private static CookieHandler secureCookieOverHttp() {
        final CookieManager cm = new CookieManager( null, CookiePolicy.ACCEPT_ALL );
        return new CookieHandler() {
            @Override
            public Map< String, List< String > > get( final URI uri,
                    final Map< String, List< String > > requestHeaders ) throws IOException {
                return cm.get( asHttps( uri ), requestHeaders );
            }

            @Override
            public void put( final URI uri,
                    final Map< String, List< String > > responseHeaders ) throws IOException {
                cm.put( uri, responseHeaders );
            }

            private URI asHttps( final URI uri ) {
                return URI.create( uri.toString().replaceFirst( "^http:", "https:" ) );
            }
        };
    }

    private HttpResponse< String > get( final String path ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private Map< String, Object > parseJson( final String body ) {
        return GSON.fromJson( body, MAP_TYPE );
    }

    // ---- Tests ----

    @Test
    @SuppressWarnings( "unchecked" )
    void schemaEndpointReturns200WithFieldsArray() throws Exception {
        final HttpResponse< String > resp = get( "/api/frontmatter-schema" );
        assertEquals( 200, resp.statusCode(), "GET /api/frontmatter-schema should return 200: " + resp.body() );

        final Map< String, Object > json = parseJson( resp.body() );
        assertNotNull( json.get( "fields" ), "Response must contain a 'fields' array" );

        final List< Map< String, Object > > fields =
                ( List< Map< String, Object > > ) json.get( "fields" );
        assertTrue( fields.size() > 0, "fields array must not be empty" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void typeFieldHasOpenTrueAndContainsArticle() throws Exception {
        final HttpResponse< String > resp = get( "/api/frontmatter-schema" );
        assertEquals( 200, resp.statusCode(), resp.body() );

        final Map< String, Object > json = parseJson( resp.body() );
        final List< Map< String, Object > > fields =
                ( List< Map< String, Object > > ) json.get( "fields" );

        Map< String, Object > typeField = null;
        for ( final Map< String, Object > f : fields ) {
            if ( "type".equals( f.get( "key" ) ) ) {
                typeField = f;
                break;
            }
        }
        assertNotNull( typeField, "Schema must contain a 'type' field; got fields: "
                + fields.stream().map( f -> f.get( "key" ) ).toList() );

        // open=true means non-canonical values are permitted (warnings, not errors)
        assertTrue( Boolean.TRUE.equals( typeField.get( "open" ) ),
                "'type' field must have open=true: " + typeField );

        final List< Object > canonicalValues =
                ( List< Object > ) typeField.get( "canonicalValues" );
        assertNotNull( canonicalValues, "canonicalValues must be present on 'type' field" );
        assertTrue( canonicalValues.contains( "article" ),
                "canonical values for 'type' must include 'article': " + canonicalValues );
    }
}
