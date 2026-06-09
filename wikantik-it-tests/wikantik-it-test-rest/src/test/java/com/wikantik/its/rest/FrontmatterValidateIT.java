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
 * End-to-end smoke for {@code POST /api/frontmatter/validate} against a
 * Cargo-deployed Tomcat.
 *
 * <p>Two cases:</p>
 * <ol>
 *   <li>YAML string with {@code status: published} → returns a WARNING violation
 *       with code {@code status.noncanonical} and suggestion {@code active}.</li>
 *   <li>Metadata object with {@code audience: "robots"} → returns an ERROR
 *       violation on the {@code audience} field (not in canonical set
 *       {humans, agents, both}).</li>
 * </ol>
 *
 * <p>No save is performed — the endpoint is a dry-run validator.</p>
 */
public class FrontmatterValidateIT {

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

    private HttpResponse< String > post( final String path, final String jsonBody )
            throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Content-Type", "application/json" )
                        .header( "Accept", "application/json" )
                        .POST( HttpRequest.BodyPublishers.ofString( jsonBody ) )
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private Map< String, Object > parseJson( final String body ) {
        return GSON.fromJson( body, MAP_TYPE );
    }

    // ---- Tests ----

    @Test
    @SuppressWarnings( "unchecked" )
    void frontmatterWithNonCanonicalStatusProducesWarningWithSuggestion() throws Exception {
        // "published" is in the suggestion map (→ "active") but not in the canonical list.
        // The validator should return a WARNING (not an ERROR), since status.open=true.
        final String requestBody = GSON.toJson(
                Map.of( "frontmatter", "type: article\nstatus: published" ) );

        final HttpResponse< String > resp = post( "/api/frontmatter/validate", requestBody );
        assertEquals( 200, resp.statusCode(),
                "validate endpoint should always return 200 for a dry-run: " + resp.body() );

        final Map< String, Object > json = parseJson( resp.body() );
        assertNotNull( json.get( "violations" ),
                "Response must contain a 'violations' key: " + resp.body() );

        final List< Map< String, Object > > violations =
                ( List< Map< String, Object > > ) json.get( "violations" );

        // Find a violation on the "status" field
        final Map< String, Object > statusViolation = violations.stream()
                .filter( v -> "status".equals( v.get( "field" ) ) )
                .findFirst()
                .orElse( null );
        assertNotNull( statusViolation,
                "Expected a violation on 'status' field; got: " + violations );

        // Must be a WARNING (advisory), not an ERROR (blocking)
        final Object severity = statusViolation.get( "severity" );
        assertTrue( "WARNING".equalsIgnoreCase( String.valueOf( severity ) ),
                "status violation must be WARNING severity; got: " + severity );

        // The suggestion should be "active" (from the suggestion map)
        final Object suggestion = statusViolation.get( "suggestion" );
        assertEquals( "active", String.valueOf( suggestion ),
                "suggestion for 'published' status should be 'active'; got: " + suggestion );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void metadataWithInvalidAudienceProducesErrorViolation() throws Exception {
        // "robots" is not in the canonical set {humans, agents, both} and audience.open=false.
        // The validator should return an ERROR violation.
        final String requestBody = GSON.toJson(
                Map.of( "metadata", Map.of( "audience", "robots" ) ) );

        final HttpResponse< String > resp = post( "/api/frontmatter/validate", requestBody );
        assertEquals( 200, resp.statusCode(),
                "validate endpoint should always return 200 for a dry-run: " + resp.body() );

        final Map< String, Object > json = parseJson( resp.body() );
        final List< Map< String, Object > > violations =
                ( List< Map< String, Object > > ) json.get( "violations" );
        assertNotNull( violations, "Response must contain 'violations': " + resp.body() );

        final Map< String, Object > audienceViolation = violations.stream()
                .filter( v -> "audience".equals( v.get( "field" ) ) )
                .findFirst()
                .orElse( null );
        assertNotNull( audienceViolation,
                "Expected a violation on 'audience' field; got: " + violations );

        final Object severity = audienceViolation.get( "severity" );
        assertTrue( "ERROR".equalsIgnoreCase( String.valueOf( severity ) ),
                "audience violation must be ERROR severity; got: " + severity );
    }
}
