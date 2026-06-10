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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for the frontmatter validation contract on
 * {@code PUT /api/pages/{name}} against a Cargo-deployed Tomcat.
 *
 * <p>Three cases (ordered so each uses a unique page name):</p>
 * <ol>
 *   <li>metadata with {@code audience: "robots"} → 422 with an {@code audience}
 *       violation; page must NOT be written.</li>
 *   <li>metadata with {@code status: "published"} → 200 with a {@code status}
 *       entry in the {@code warnings} array (advisory, not blocking).</li>
 *   <li>all-valid metadata → 200 with an empty {@code warnings} array.</li>
 * </ol>
 *
 * <p>Mirrors {@link EdgeCurationIT}'s cookie-based admin-session pattern.</p>
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class FrontmatterSaveContractIT {

    private static final String IT_SUFFIX = UUID.randomUUID().toString().substring( 0, 8 );
    /** Page name for the invalid-audience test; must not exist before the test runs. */
    private static final String PAGE_INVALID_AUDIENCE = "FmSaveInvalidAudience-" + IT_SUFFIX;
    /** Page name for the noncanonical-status test (should be written successfully). */
    private static final String PAGE_NONCANONICAL_STATUS = "FmSaveNoncanonicalStatus-" + IT_SUFFIX;
    /** Page name for the all-valid test (should be written successfully). */
    private static final String PAGE_VALID = "FmSaveValid-" + IT_SUFFIX;
    /** Page name for the invalid-runbook test; must not exist before the test runs. */
    private static final String PAGE_INVALID_RUNBOOK = "FmSaveInvalidRunbook-" + IT_SUFFIX;

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

    // ---- HTTP helpers ----

    private HttpResponse< String > get( final String path ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > put( final String path, final String jsonBody )
            throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Content-Type", "application/json" )
                        .header( "Accept", "application/json" )
                        .PUT( HttpRequest.BodyPublishers.ofString( jsonBody ) )
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
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

    /** Authenticates the shared {@link #client} cookie jar as the admin user. */
    private void loginAsAdmin() throws IOException, InterruptedException {
        final String loginBody = GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpResponse< String > resp = post( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

    // ---- Tests ----

    @Test
    @Order( 1 )
    @SuppressWarnings( "unchecked" )
    void putWithInvalidAudienceReturns422AndPageNotWritten() throws Exception {
        loginAsAdmin();
        final String requestBody = GSON.toJson( Map.of(
                "content", "Body of the invalid audience test page.",
                "metadata", Map.of( "type", "article", "audience", "robots" ),
                "replaceMetadata", true ) );

        final HttpResponse< String > resp = put( "/api/pages/" + PAGE_INVALID_AUDIENCE, requestBody );
        assertEquals( 422, resp.statusCode(),
                "PUT with audience=robots must be rejected with 422: " + resp.body() );

        final Map< String, Object > json = parseJson( resp.body() );
        assertEquals( "frontmatter_validation_failed", json.get( "error" ),
                "422 body must carry error='frontmatter_validation_failed': " + resp.body() );

        final List< Map< String, Object > > violations =
                ( List< Map< String, Object > > ) json.get( "violations" );
        assertNotNull( violations, "422 body must contain violations: " + resp.body() );
        final boolean hasAudienceViolation = violations.stream()
                .anyMatch( v -> "audience".equals( v.get( "field" ) ) );
        assertTrue( hasAudienceViolation,
                "violations must include an 'audience' violation: " + violations );

        // Page must NOT have been written
        final HttpResponse< String > getResp = get( "/api/pages/" + PAGE_INVALID_AUDIENCE );
        assertEquals( 404, getResp.statusCode(),
                "Page must NOT exist after a 422-rejected save: " + getResp.body() );
    }

    @Test
    @Order( 2 )
    @SuppressWarnings( "unchecked" )
    void putWithNonCanonicalStatusReturns200WithWarning() throws Exception {
        loginAsAdmin();
        final String requestBody = GSON.toJson( Map.of(
                "content", "Body of the noncanonical status test page.",
                "metadata", Map.of( "type", "article", "status", "published" ),
                "replaceMetadata", true ) );

        final HttpResponse< String > resp = put( "/api/pages/" + PAGE_NONCANONICAL_STATUS, requestBody );
        assertEquals( 200, resp.statusCode(),
                "PUT with status=published must succeed (open=true → warning only): " + resp.body() );

        final Map< String, Object > json = parseJson( resp.body() );
        assertTrue( Boolean.TRUE.equals( json.get( "success" ) ),
                "Response must carry success=true: " + resp.body() );

        final List< Object > warnings = ( List< Object > ) json.get( "warnings" );
        assertNotNull( warnings, "200 body must contain a 'warnings' key: " + resp.body() );
        // At least one warning entry must reference the "status" field
        final String warningsStr = warnings.toString();
        assertTrue( warningsStr.contains( "status" ),
                "warnings must include a 'status' entry: " + warningsStr );
    }

    @Test
    @Order( 3 )
    @SuppressWarnings( "unchecked" )
    void putWithValidMetadataReturns200WithEmptyWarnings() throws Exception {
        loginAsAdmin();
        final String requestBody = GSON.toJson( Map.of(
                "content", "Body of the all-valid metadata test page.",
                "metadata", Map.of( "type", "article", "status", "active", "audience", "humans" ),
                "replaceMetadata", true ) );

        final HttpResponse< String > resp = put( "/api/pages/" + PAGE_VALID, requestBody );
        assertEquals( 200, resp.statusCode(),
                "PUT with all-valid metadata must succeed: " + resp.body() );

        final Map< String, Object > json = parseJson( resp.body() );
        assertTrue( Boolean.TRUE.equals( json.get( "success" ) ),
                "Response must carry success=true: " + resp.body() );

        final List< Object > warnings = ( List< Object > ) json.get( "warnings" );
        assertNotNull( warnings, "200 body must contain a 'warnings' key: " + resp.body() );
        assertTrue( warnings.isEmpty(),
                "warnings must be empty for all-valid metadata: " + warnings );
    }

    @Test
    @Order( 4 )
    @SuppressWarnings( "unchecked" )
    void putRunbookWithInvalidRelatedToolReturns422AndPageNotWritten() throws Exception {
        // The original failing scenario: a runbook with an invalid related_tools entry. Previously the
        // RunbookValidationPageFilter threw a bare FilterException → opaque error banner. It now throws a
        // structured FrontmatterValidationException → HTTP 422 with a runbook.related_tools violation.
        loginAsAdmin();
        final String requestBody = GSON.toJson( Map.of(
                "content", "Body of the invalid runbook test page.",
                "metadata", Map.of(
                        "type", "runbook",
                        "runbook", Map.of(
                                "when_to_use", List.of( "Agent needs X" ),
                                "steps", List.of( "a", "b" ),
                                "pitfalls", List.of( "(none known)" ),
                                "related_tools", List.of( "Bad-Tool-Name" ) ) ),
                "replaceMetadata", true ) );

        final HttpResponse< String > resp = put( "/api/pages/" + PAGE_INVALID_RUNBOOK, requestBody );
        assertEquals( 422, resp.statusCode(),
                "PUT of a runbook with an invalid related_tools entry must be rejected with 422: " + resp.body() );

        final Map< String, Object > json = parseJson( resp.body() );
        assertEquals( "frontmatter_validation_failed", json.get( "error" ),
                "422 body must carry error='frontmatter_validation_failed': " + resp.body() );

        final List< Map< String, Object > > violations =
                ( List< Map< String, Object > > ) json.get( "violations" );
        assertNotNull( violations, "422 body must contain violations: " + resp.body() );
        assertTrue( violations.stream().anyMatch( v -> "runbook.related_tools".equals( v.get( "field" ) ) ),
                "violations must include a 'runbook.related_tools' violation: " + violations );

        // Page must NOT have been written
        final HttpResponse< String > getResp = get( "/api/pages/" + PAGE_INVALID_RUNBOOK );
        assertEquals( 404, getResp.statusCode(),
                "Page must NOT exist after a 422-rejected save: " + getResp.body() );
    }
}
