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
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Covers card-level branches of {@link AdminOverviewResource} not exercised by
 * the existing {@link AdminOverviewResourceTest}. Every card must land in either
 * {@code data} or {@code degraded} — this verifies each card key by name so a
 * future deletion is caught early.
 *
 * <p>With no engine / registry wired (test environment) every card that touches
 * DB or managed services degrades gracefully. The {@code health} card is an
 * exception: it has a deliberate null-guard so it always succeeds (status = "Unknown"
 * when engine is null).</p>
 */
class AdminOverviewResourceCardTest {

    private static final String[] EXPECTED_CARD_KEYS = {
        "health", "load", "llmActivity", "kgProposals", "retrieval",
        "searchIndex", "users", "recent", "kgSize", "extractor",
        "judge", "renderCache", "auth", "agentSurface",
        "contentQuality", "retrievalModes", "attachments"
    };

    private JsonObject callDoGet() throws Exception {
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        final StringWriter body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );
        new AdminOverviewResource().doGetForTesting(
            Mockito.mock( HttpServletRequest.class ), resp );
        return JsonParser.parseString( body.toString() ).getAsJsonObject();
    }

    // ---- envelope structure ----

    @Test
    void responseHasDataEnvelope() throws Exception {
        final JsonObject env = callDoGet();
        assertTrue( env.has( "data" ), "top-level 'data' key required" );
    }

    @Test
    void degradedListIsAlwaysPresent() throws Exception {
        final JsonObject env = callDoGet();
        final JsonObject data = env.getAsJsonObject( "data" );
        assertTrue( data.has( "degraded" ), "'degraded' key must always appear in data" );
        assertTrue( data.get( "degraded" ).isJsonArray(), "'degraded' must be a JSON array" );
    }

    // ---- health card — always succeeds even without engine ----

    @Test
    void healthCard_isPresentNotDegraded() throws Exception {
        final JsonObject env = callDoGet();
        final JsonObject data = env.getAsJsonObject( "data" );
        assertTrue( data.has( "health" ),
            "'health' card must be present (has a null-guard so it never degrades)" );
        final JsonObject health = data.getAsJsonObject( "health" );
        assertTrue( health.has( "status" ) );
        assertTrue( health.has( "version" ) );
        // With no engine, status is "Unknown"
        assertEquals( "Unknown", health.get( "status" ).getAsString() );
    }

    // ---- load card — metric-backed; degrades when registry null ----

    @Test
    void loadCard_isPresentOrDegraded() throws Exception {
        assertCardPresentOrDegraded( "load" );
    }

    // ---- llmActivity card ----

    @Test
    void llmActivityCard_isPresentOrDegraded() throws Exception {
        assertCardPresentOrDegraded( "llmActivity" );
    }

    @Test
    void llmActivityCard_whenPresent_hasExpectedFields() throws Exception {
        final JsonObject env = callDoGet();
        final JsonObject data = env.getAsJsonObject( "data" );
        if ( data.has( "llmActivity" ) ) {
            final JsonObject card = data.getAsJsonObject( "llmActivity" );
            // All four fields must be present when card succeeds
            assertTrue( card.has( "inFlight" ), "llmActivity.inFlight required" );
            assertTrue( card.has( "windowMinutes" ), "llmActivity.windowMinutes required" );
            assertTrue( card.has( "capacity" ), "llmActivity.capacity required" );
            assertTrue( card.has( "count" ), "llmActivity.count required" );
            assertTrue( card.has( "errors" ), "llmActivity.errors required" );
        }
    }

    // ---- recent card ----

    @Test
    void recentCard_isPresentOrDegraded() throws Exception {
        assertCardPresentOrDegraded( "recent" );
    }

    @Test
    void recentCard_whenPresent_hasItemsArray() throws Exception {
        final JsonObject env = callDoGet();
        final JsonObject data = env.getAsJsonObject( "data" );
        if ( data.has( "recent" ) ) {
            final JsonObject card = data.getAsJsonObject( "recent" );
            assertTrue( card.has( "items" ), "recent.items array required" );
            assertTrue( card.get( "items" ).isJsonArray(), "recent.items must be an array" );
        }
    }

    // ---- extractor card ----

    @Test
    void extractorCard_isPresentOrDegraded() throws Exception {
        assertCardPresentOrDegraded( "extractor" );
    }

    @Test
    void extractorCard_whenPresent_hasThreeMetricFields() throws Exception {
        final JsonObject env = callDoGet();
        final JsonObject data = env.getAsJsonObject( "data" );
        if ( data.has( "extractor" ) ) {
            final JsonObject card = data.getAsJsonObject( "extractor" );
            assertTrue( card.has( "requests" ) );
            assertTrue( card.has( "triples" ) );
            assertTrue( card.has( "failures" ) );
        }
    }

    // ---- renderCache card ----

    @Test
    void renderCacheCard_isPresentOrDegraded() throws Exception {
        assertCardPresentOrDegraded( "renderCache" );
    }

    @Test
    void renderCacheCard_whenPresent_hasFourFields() throws Exception {
        final JsonObject env = callDoGet();
        final JsonObject data = env.getAsJsonObject( "data" );
        if ( data.has( "renderCache" ) ) {
            final JsonObject card = data.getAsJsonObject( "renderCache" );
            assertTrue( card.has( "hits" ) );
            assertTrue( card.has( "misses" ) );
            assertTrue( card.has( "evictions" ) );
            assertTrue( card.has( "size" ) );
        }
    }

    // ---- auth card ----

    @Test
    void authCard_isPresentOrDegraded() throws Exception {
        assertCardPresentOrDegraded( "auth" );
    }

    @Test
    void authCard_whenPresent_hasLoginsAndFailed() throws Exception {
        final JsonObject env = callDoGet();
        final JsonObject data = env.getAsJsonObject( "data" );
        if ( data.has( "auth" ) ) {
            final JsonObject card = data.getAsJsonObject( "auth" );
            assertTrue( card.has( "logins" ) );
            assertTrue( card.has( "failed" ) );
        }
    }

    // ---- agentSurface card ----

    @Test
    void agentSurfaceCard_isPresentOrDegraded() throws Exception {
        assertCardPresentOrDegraded( "agentSurface" );
    }

    @Test
    void agentSurfaceCard_whenPresent_hasThreeFields() throws Exception {
        final JsonObject env = callDoGet();
        final JsonObject data = env.getAsJsonObject( "data" );
        if ( data.has( "agentSurface" ) ) {
            final JsonObject card = data.getAsJsonObject( "agentSurface" );
            assertTrue( card.has( "hubSynthesis" ) );
            assertTrue( card.has( "hintFailures" ) );
            assertTrue( card.has( "forAgentBytes" ) );
        }
    }

    // ---- attachments card — pure config, never degrades ----

    @Test
    void attachmentsCard_isPresentOrDegraded() throws Exception {
        // The attachments card reads from properties only; should not degrade in test env
        assertCardPresentOrDegraded( "attachments" );
    }

    // ---- all expected cards accounted for ----

    @Test
    void allExpectedCardsArePresentOrDegraded() throws Exception {
        final JsonObject env = callDoGet();
        final JsonObject data = env.getAsJsonObject( "data" );
        final Set< String > degraded = new HashSet<>();
        data.getAsJsonArray( "degraded" ).forEach( e -> degraded.add( e.getAsString() ) );

        for ( final String key : EXPECTED_CARD_KEYS ) {
            assertTrue( data.has( key ) || degraded.contains( key ),
                "Card '" + key + "' must be present in data or listed in degraded" );
        }
    }

    // ---- non-data keys must NOT appear at the top-level ----

    @Test
    void responseDoesNotLeakCardKeysOutsideDataEnvelope() throws Exception {
        final JsonObject env = callDoGet();
        // Only 'data' should be at the top level
        assertEquals( 1, env.size(),
            "Envelope should contain only 'data'; found: " + env.keySet() );
    }

    // ---- helper ----

    private void assertCardPresentOrDegraded( final String key ) throws Exception {
        final JsonObject env = callDoGet();
        final JsonObject data = env.getAsJsonObject( "data" );
        final Set< String > degraded = new HashSet<>();
        data.getAsJsonArray( "degraded" ).forEach( e -> degraded.add( e.getAsString() ) );
        assertTrue( data.has( key ) || degraded.contains( key ),
            "Card '" + key + "' must be present in data or listed in degraded" );
    }
}
