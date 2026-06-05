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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end SCIM full-loop IT: a real Authentik IdP (SCIM client) provisions
 * and then deactivates a user, and the wiki's SCIM service-provider reflects
 * each step.
 *
 * <p><strong>This test is opt-in only</strong> — it runs exclusively under
 * {@code -Pscim-fullloop} and is never part of the per-commit default gate.
 *
 * <h2>What is complete vs TODO-stubbed</h2>
 * <ul>
 *   <li><strong>Complete:</strong> {@link #setUp()} reading system properties,
 *       {@link #pollWikiUser(String, Predicate, String)} polling the wiki's
 *       {@code /scim/v2/Users} endpoint with a deadline, and the wiki-side
 *       assertions (user appears in wiki, then {@code active} flips to false).</li>
 *   <li><strong>TODO-stubbed:</strong> The four Authentik provisioning helpers
 *       ({@code configureScimProvider}, {@code createUser}, {@code triggerSync},
 *       {@code disableUser}) are not yet authored because their request bodies
 *       must be written against the live {@code /api/v3/} API surface.  Run
 *       {@code bin/run-tests.sh --fullloop} to bring the stack up, inspect the
 *       Authentik API with curl against the reserved port, then fill in the
 *       TODO blocks in this class.</li>
 * </ul>
 *
 * <h2>Expected iteration loop</h2>
 * <ol>
 *   <li>Run {@code bin/run-tests.sh --unit && bin/run-tests.sh --fullloop}.</li>
 *   <li>The test compiles and executes; it fails at step 2 ({@code pollWikiUser}
 *       timeout) because no user has been pushed — that is the expected scaffold
 *       state.</li>
 *   <li>Fill in the TODO stubs (configureScimProvider, createUser, triggerSync,
 *       disableUser) against the live API and iterate until the full loop is
 *       green.</li>
 * </ol>
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

    @BeforeAll
    static void setUp() {
        wikiBase = System.getProperty( "it-wikantik.base.url" );
        authentikBase = System.getProperty( "it-wikantik.authentik.base-url" );
        authentik = new AuthentikClient( authentikBase, "it-authentik-api-token" );
    }

    // -------------------------------------------------------------------------
    // TODO stubs — author these against the live Authentik /api/v3/ API
    // -------------------------------------------------------------------------

    /**
     * TODO: Configure an Authentik SCIM provider pointing at the wiki.
     *
     * <p>Use {@code authentik.post("/api/v3/providers/scim/", ...)} to create a
     * provider with:
     * <ul>
     *   <li>{@code url} = {@code wikiBase + "/scim/v2"}</li>
     *   <li>{@code token} = {@code "it-scim-token"}</li>
     *   <li>{@code verify_certificates} = {@code false} (HTTP in IT)</li>
     * </ul>
     * Then bind it to an Authentik Application via
     * {@code POST /api/v3/core/applications/} referencing the provider's pk.
     * Assign the application to the "authentikUsers" group (or all users) so
     * Authentik syncs created users.  Store the provider pk as an instance
     * field for use in triggerSync().
     *
     * <p>See Authentik REST API docs: https://goauthentik.io/developer-docs/api/
     */
    private void configureScimProvider() throws Exception {
        // TODO: implement against live /api/v3/ API — see class-level Javadoc.
        throw new UnsupportedOperationException(
            "configureScimProvider() not yet implemented; "
            + "run --fullloop with the stack up and author against the live API" );
    }

    /**
     * TODO: Create a user in Authentik.
     *
     * <p>Use {@code authentik.post("/api/v3/core/users/", ...)} with JSON body:
     * <pre>
     * {
     *   "username": userName,
     *   "name": "Authentik Loop User",
     *   "email": userName + "@example.com",
     *   "is_active": true
     * }
     * </pre>
     * Assert the response is 201 and store the user pk for disableUser().
     *
     * @param userName the login name to create in Authentik
     * @return the Authentik user pk (int) — needed by disableUser()
     */
    private int createUser( final String userName ) throws Exception {
        // TODO: implement against live /api/v3/ API — see class-level Javadoc.
        throw new UnsupportedOperationException(
            "createUser() not yet implemented; "
            + "run --fullloop with the stack up and author against the live API" );
    }

    /**
     * TODO: Trigger an Authentik SCIM sync for the configured provider.
     *
     * <p>Authentik exposes a sync trigger at (version-dependent):
     * {@code POST /api/v3/providers/scim/{id}/sync/} or via an outpost task.
     * Confirm the exact endpoint by inspecting the live API's OpenAPI schema:
     * {@code GET /api/v3/schema/} or the Authentik admin UI.
     *
     * <p>The sync is asynchronous; after triggering, the caller must poll the
     * wiki (via {@link #pollWikiUser}) rather than blocking on the sync result.
     *
     * @param providerPk the Authentik SCIM provider primary key
     */
    private void triggerSync( final int providerPk ) throws Exception {
        // TODO: implement against live /api/v3/ API — see class-level Javadoc.
        throw new UnsupportedOperationException(
            "triggerSync() not yet implemented; "
            + "run --fullloop with the stack up and author against the live API" );
    }

    /**
     * TODO: Deactivate a user in Authentik.
     *
     * <p>Use {@code authentik.patch("/api/v3/core/users/{pk}/", ...)} with body:
     * <pre>
     * { "is_active": false }
     * </pre>
     * Assert the response is 200.  After patching, call triggerSync() so the
     * worker picks up the change and pushes {@code active:false} to the wiki.
     *
     * @param userPk the Authentik user pk (returned by createUser())
     */
    private void disableUser( final int userPk ) throws Exception {
        // TODO: implement against live /api/v3/ API — see class-level Javadoc.
        throw new UnsupportedOperationException(
            "disableUser() not yet implemented; "
            + "run --fullloop with the stack up and author against the live API" );
    }

    // -------------------------------------------------------------------------
    // Complete: wiki-side polling helper
    // -------------------------------------------------------------------------

    /**
     * Polls the wiki's {@code /scim/v2/Users?filter=userName eq "..."} endpoint
     * (bearer-authed) until {@code bodyOk} returns {@code true} or the deadline
     * expires.
     *
     * <p>This models {@code RestSeedHelper.awaitAdminReady} — absorbing async
     * propagation latency between Authentik triggering a sync and the wiki
     * persisting the SCIM push.
     *
     * @param userName the SCIM userName to filter on
     * @param bodyOk   predicate applied to the raw response body; return
     *                 {@code true} when the expected state is observed
     * @param what     human-readable description for the failure message
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
     *
     * <p>Steps 1 and 3 (Authentik provisioning) are TODO-stubbed; steps 2 and 4
     * (wiki-side assertions via polling) are complete.
     */
    @Test
    void authentik_provisions_then_disables_user_in_wiki() throws Exception {
        final String userName = "authentik-loop-user";

        // 1. Configure Authentik SCIM provider + create user + trigger initial sync.
        //    TODO: uncomment once the stubs above are implemented.
        // configureScimProvider();
        // final int userPk = createUser( userName );
        // triggerSync( /* providerPk — store from configureScimProvider */ 0 );

        // 2. Wiki reflects provisioning — poll until the user appears.
        pollWikiUser( userName,
                body -> {
                    final var resources = JsonParser.parseString( body )
                            .getAsJsonObject()
                            .getAsJsonArray( "Resources" );
                    return resources != null && resources.size() > 0;
                },
                "user '" + userName + "' provisioned into wiki via Authentik SCIM" );

        // 3. Disable the user in Authentik + trigger sync.
        //    TODO: uncomment once the stubs above are implemented.
        // disableUser( userPk );
        // triggerSync( /* providerPk */ 0 );

        // 4. Wiki reflects deactivation — poll until active:false.
        pollWikiUser( userName,
                body -> {
                    final var resources = JsonParser.parseString( body )
                            .getAsJsonObject()
                            .getAsJsonArray( "Resources" );
                    if ( resources == null || resources.size() == 0 ) {
                        return false;
                    }
                    final var activeField = resources.get( 0 )
                            .getAsJsonObject()
                            .get( "active" );
                    return activeField != null && !activeField.getAsBoolean();
                },
                "user '" + userName + "' deactivated in wiki via Authentik SCIM" );

        assertTrue( true, "full loop: provision + disable propagated via Authentik SCIM" );
    }
}
