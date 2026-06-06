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

import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end SCIM full-loop IT: a real Authentik IdP (SCIM client) provisions a
 * user into the wiki, then deactivates it, and the wiki's SCIM service-provider
 * reflects each transition.
 *
 * <p><strong>Opt-in only</strong> — runs exclusively under {@code -Pscim-fullloop}
 * (see {@code bin/run-tests.sh --fullloop}); never part of the per-commit default gate.
 *
 * <h2>How Authentik drives the wiki</h2>
 * <ol>
 *   <li>Create a SCIM <em>provider</em> ({@code /api/v3/providers/scim/}) whose
 *       {@code url} points at the wiki's {@code /scim/v2} (reached via
 *       {@code host.docker.internal} from the Authentik container) and whose
 *       {@code token} matches the wiki's {@code wikantik.scim.token}. Attach the
 *       managed default SCIM property mappings, and bind the provider to an
 *       Application as a backchannel provider.</li>
 *   <li>Create a user. Authentik's {@code scim_sync_direct} signal fires on the
 *       user's save; a provider PATCH additionally forces a full
 *       {@code scim_sync} reconciliation. The worker POSTs the user to the wiki's
 *       {@code /scim/v2/Users} (after a {@code GET /ServiceProviderConfig} probe).</li>
 *   <li>Deactivate the user ({@code is_active:false}); the same sync path pushes
 *       {@code active:false} to the wiki.</li>
 * </ol>
 * Each wiki-side transition is observed by polling the wiki's SCIM endpoint
 * (async propagation is absorbed by {@link #pollWikiUser}).
 */
public class AuthentikScimFullLoopIT {

    /** Bearer token the wiki's SCIM endpoint accepts (matches {@code -Dwikantik.scim.token}). */
    private static final String SCIM_TOKEN = "it-scim-token";

    /** How long to poll the wiki waiting for async SCIM propagation. */
    private static final long POLL_MS = 60_000L;

    /** Interval between poll attempts. */
    private static final long INTERVAL_MS = 1_000L;

    private static String wikiBase;
    private static String authentikBase;
    private static AuthentikClient authentik;
    private static final HttpClient http = HttpClient.newHttpClient();

    /** The Authentik SCIM provider pk, set by {@link #configureScimProvider()}. */
    private int providerPk;

    @BeforeAll
    static void setUp() {
        wikiBase = System.getProperty( "it-wikantik.base.url" );
        authentikBase = System.getProperty( "it-wikantik.authentik.base-url" );
        authentik = new AuthentikClient( authentikBase, "it-authentik-api-token" );
    }

    // -------------------------------------------------------------------------
    // Authentik provisioning (live /api/v3/ API)
    // -------------------------------------------------------------------------

    /**
     * Creates a SCIM provider pointing at the wiki, attaches the managed default
     * SCIM property mappings, and binds it to an application as a backchannel
     * provider. Stores the provider pk in {@link #providerPk}.
     */
    private void configureScimProvider() throws Exception {
        // The wiki's Cargo Tomcat runs on the HOST; the Authentik container reaches
        // it via host.docker.internal (host-gateway extra host wired in the pom).
        final String wikiScimUrl = wikiBase.replace( "localhost", "host.docker.internal" ) + "/scim/v2";

        // The managed default SCIM mappings (pk is instance-specific; the managed
        // name is stable across Authentik instances).
        final String userMapping = firstPk( authentik.get(
            "/api/v3/propertymappings/provider/scim/?managed=goauthentik.io/providers/scim/user" ) );
        final String groupMapping = firstPk( authentik.get(
            "/api/v3/propertymappings/provider/scim/?managed=goauthentik.io/providers/scim/group" ) );

        final String providerBody = "{"
            + "\"name\":\"wiki-scim\","
            + "\"url\":\"" + wikiScimUrl + "\","
            + "\"token\":\"" + SCIM_TOKEN + "\","
            + "\"exclude_users_service_account\":true,"
            + "\"property_mappings\":[\"" + userMapping + "\"],"
            + "\"property_mappings_group\":[\"" + groupMapping + "\"]"
            + "}";
        final HttpResponse<String> pr = authentik.post( "/api/v3/providers/scim/", providerBody );
        System.out.println( "[FULLLOOP] create SCIM provider -> " + pr.statusCode() + " " + pr.body() );
        assertEquals( 201, pr.statusCode(), "SCIM provider create should be 201: " + pr.body() );
        providerPk = JsonParser.parseString( pr.body() ).getAsJsonObject().get( "pk" ).getAsInt();

        final String appBody = "{"
            + "\"name\":\"Wiki\",\"slug\":\"wiki\","
            + "\"backchannel_providers\":[" + providerPk + "]"
            + "}";
        final HttpResponse<String> ar = authentik.post( "/api/v3/core/applications/", appBody );
        System.out.println( "[FULLLOOP] create application -> " + ar.statusCode() + " " + ar.body() );
        assertEquals( 201, ar.statusCode(), "application create should be 201: " + ar.body() );
    }

    /** Creates a user in Authentik; returns its pk. The save fires a direct SCIM sync. */
    private int createUser( final String userName ) throws Exception {
        final String body = "{"
            + "\"username\":\"" + userName + "\","
            + "\"name\":\"Authentik Loop User\","
            + "\"email\":\"" + userName + "@example.com\","
            + "\"is_active\":true,"
            + "\"path\":\"users\""
            + "}";
        final HttpResponse<String> r = authentik.post( "/api/v3/core/users/", body );
        System.out.println( "[FULLLOOP] create user -> " + r.statusCode() + " " + r.body() );
        assertEquals( 201, r.statusCode(), "user create should be 201: " + r.body() );
        return JsonParser.parseString( r.body() ).getAsJsonObject().get( "pk" ).getAsInt();
    }

    /**
     * Forces a full SCIM reconciliation. Authentik exposes no REST trigger
     * ({@code POST /sync/} is 405), but saving the provider re-runs {@code scim_sync},
     * which deterministically pushes all in-scope users (the per-user
     * {@code scim_sync_direct} signal also fires on create/patch, but forcing a full
     * sync removes timing flakiness).
     */
    private void triggerSync() throws Exception {
        final HttpResponse<String> r = authentik.patch( "/api/v3/providers/scim/" + providerPk + "/",
            "{\"name\":\"wiki-scim-" + System.nanoTime() + "\"}" );
        System.out.println( "[FULLLOOP] trigger full sync (provider patch) -> " + r.statusCode() );
        assertEquals( 200, r.statusCode(), "provider patch (sync) should be 200: " + r.body() );
    }

    /** Deactivates a user in Authentik (is_active:false); fires a direct SCIM sync. */
    private void disableUser( final int userPk ) throws Exception {
        final HttpResponse<String> r = authentik.patch( "/api/v3/core/users/" + userPk + "/",
            "{\"is_active\":false}" );
        System.out.println( "[FULLLOOP] disable user -> " + r.statusCode() + " " + r.body() );
        assertEquals( 200, r.statusCode(), "user disable should be 200: " + r.body() );
    }

    /** Extracts the first {@code results[].pk} from a paginated Authentik list response. */
    private String firstPk( final HttpResponse<String> r ) {
        final var results = JsonParser.parseString( r.body() ).getAsJsonObject().getAsJsonArray( "results" );
        if ( results == null || results.size() == 0 ) {
            fail( "expected at least one result in: " + r.body() );
        }
        return results.get( 0 ).getAsJsonObject().get( "pk" ).getAsString();
    }

    // -------------------------------------------------------------------------
    // Wiki-side polling helper
    // -------------------------------------------------------------------------

    /**
     * Polls the wiki's {@code /scim/v2/Users?filter=userName eq "..."} endpoint
     * (bearer-authed) until {@code bodyOk} returns {@code true} or the deadline
     * expires — absorbing async propagation latency between Authentik's sync and
     * the wiki persisting the push.
     */
    private void pollWikiUser( final String userName,
                               final Predicate<String> bodyOk,
                               final String what ) throws Exception {
        final long deadline = System.currentTimeMillis() + POLL_MS;
        while ( System.currentTimeMillis() < deadline ) {
            final HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder()
                            .uri( URI.create( wikiBase + "/scim/v2/Users?filter="
                                    + URLEncoder.encode(
                                            "userName eq \"" + userName + "\"",
                                            StandardCharsets.UTF_8 ) ) )
                            .header( "Authorization", "Bearer " + SCIM_TOKEN )
                            .header( "Accept", "application/scim+json" )
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString() );
            if ( r.statusCode() == 200 && bodyOk.test( r.body() ) ) {
                return;
            }
            Thread.sleep( INTERVAL_MS );
        }
        fail( "Timed out after " + ( POLL_MS / 1000 ) + "s waiting for: " + what );
    }

    // -------------------------------------------------------------------------
    // Test
    // -------------------------------------------------------------------------

    /**
     * Full SCIM loop: Authentik provisions a user into the wiki, then deactivates
     * it, and the wiki reflects both transitions.
     */
    @Test
    void authentik_provisions_then_disables_user_in_wiki() throws Exception {
        final String userName = "authentik-loop-user";

        // 1. Configure the SCIM provider + app, create the user, force a sync.
        configureScimProvider();
        final int userPk = createUser( userName );
        triggerSync();

        // 2. Wiki reflects provisioning — poll until the user appears.
        pollWikiUser( userName,
                body -> {
                    final var resources = JsonParser.parseString( body )
                            .getAsJsonObject()
                            .getAsJsonArray( "Resources" );
                    return resources != null && resources.size() > 0;
                },
                "user '" + userName + "' provisioned into wiki via Authentik SCIM" );

        // 3. Disable the user in Authentik, force a sync.
        disableUser( userPk );
        triggerSync();

        // 4. Wiki reflects deactivation — poll until active:false.
        pollWikiUser( userName,
                body -> {
                    final var resources = JsonParser.parseString( body )
                            .getAsJsonObject()
                            .getAsJsonArray( "Resources" );
                    if ( resources == null || resources.size() == 0 ) {
                        return false;
                    }
                    final var activeField = resources.get( 0 ).getAsJsonObject().get( "active" );
                    return activeField != null && !activeField.getAsBoolean();
                },
                "user '" + userName + "' deactivated in wiki via Authentik SCIM" );

        assertTrue( true, "full loop: provision + disable propagated via Authentik SCIM" );
    }
}
