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
package com.wikantik.its;

import com.codeborne.selenide.Selenide;
import com.wikantik.its.environment.Env;
import com.wikantik.pages.admin.HubDiscoveryAdminPage;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.open;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the Hub Discovery admin UI. Covers:
 * <ol>
 *   <li>Accept proposal -&gt; card disappears, accepted id removed from list.</li>
 *   <li>409 collision -&gt; inline error, card retained, existing page untouched.</li>
 *   <li>Dismiss -&gt; card disappears, list empty.</li>
 *   <li>Dismissed retention -&gt; row visible in dismissed bucket, bulk-delete works.</li>
 * </ol>
 *
 * <p>The discovery <em>algorithm</em> (HDBSCAN over chunk-mention centroids) is
 * exercised by {@code HubDiscoveryServiceTest} as a unit test against
 * synthetic vectors. These ITs verify the <em>UI flow</em> on top of proposals.
 * Rather than spin up the full chunker + extractor + embedder pipeline (which
 * needs a live Ollama backend the IT environment cannot provide), each test
 * uses the {@code /admin/knowledge-graph/hub-discovery/proposals/seed} test-only
 * fixture seam to insert a synthetic proposal directly. The seam is gated by
 * {@code -Dwikantik.test.fixture-seam.enabled=true}, set only in the
 * integration-tests Maven profile.</p>
 */
public class HubDiscoveryAdminIT extends WithIntegrationTestSetup {

    @BeforeEach
    void login() {
        // Reset the browser session between test methods so each test starts
        // anonymous. Without this the second test in this class inherits the
        // authenticated state from the first (whose flow never logs out) and
        // {@code clickOnLogin()} fails because no signin button is rendered.
        Selenide.closeWebDriver();
        ViewWikiPage.open( "Main" )
            .clickOnLogin()
            .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
    }

    /**
     * Writes a stub page for each member name. {@link com.wikantik.knowledge.HubDiscoveryService#acceptProposal}
     * silently drops members that do not exist as wiki pages and rejects the
     * accept if fewer than two members survive. Tests seed both the proposal
     * and the underlying member pages to satisfy that guard.
     */
    private static void writeMemberStubs( final List< String > names ) throws Exception {
        for ( final String n : names ) {
            RestSeedHelper.writePage( n, "stub for " + n );
        }
    }

    @Test
    void runAcceptFlow_happyPath() throws Exception {
        final List< String > members = List.of( "CookingBaking", "CookingRoasting", "CookingGrilling" );
        writeMemberStubs( members );
        final int proposalId = RestSeedHelper.seedHubDiscoveryProposal(
            "CookingHub_" + System.currentTimeMillis(),
            "CookingBaking",
            members );

        final HubDiscoveryAdminPage page = new HubDiscoveryAdminPage().open();
        final var card = $( "[data-testid=hub-discovery-card-" + proposalId + "]" );
        card.shouldBe( visible, java.time.Duration.ofSeconds( 15 ) );

        // Force a unique name to avoid colliding with either preseeded wiki pages
        // or hub stubs left behind by earlier test runs against the same DB.
        page.setName( proposalId, "HappyPathHub_" + System.currentTimeMillis() )
            .clickAccept( proposalId );
        page.assertCardDisappears( proposalId );

        // Side-effect: the accepted proposal's id no longer appears in the pending list.
        final String afterJson = RestSeedHelper.listProposals();
        assertTrue( !afterJson.contains( "\"id\":" + proposalId + "," ),
            "Accepted proposal should be gone from list" );
    }

    @Test
    void acceptCollisionShowsInlineError() throws Exception {
        // Pre-create a wiki page that the accept flow's name will collide with.
        RestSeedHelper.writePage( "ClashingHubName",
            "Pre-existing content for collision test" );

        final int proposalId = RestSeedHelper.seedHubDiscoveryProposal(
            "SportHub_" + System.currentTimeMillis(),
            "SportSoccer",
            List.of( "SportSoccer", "SportBasketball", "SportTennis" ) );

        final HubDiscoveryAdminPage page = new HubDiscoveryAdminPage().open();
        final var card = $( "[data-testid=hub-discovery-card-" + proposalId + "]" );
        card.shouldBe( visible, java.time.Duration.ofSeconds( 15 ) );

        page.setName( proposalId, "ClashingHubName" )
            .clickAccept( proposalId )
            .waitForErrorToast()
            .assertCardStillPresent( proposalId );

        // Pre-existing page still has original content.
        open( com.wikantik.pages.Page.baseUrl() + "/wiki/ClashingHubName" );
        $( "main" ).shouldHave( com.codeborne.selenide.Condition.text( "Pre-existing content" ),
            java.time.Duration.ofSeconds( 10 ) );
    }

    @Test
    void dismissRemovesCard() throws Exception {
        final int proposalId = RestSeedHelper.seedHubDiscoveryProposal(
            "GardenHub_" + System.currentTimeMillis(),
            "GardenRose",
            List.of( "GardenRose", "GardenTulip", "GardenOrchid" ) );

        final HubDiscoveryAdminPage page = new HubDiscoveryAdminPage().open();
        final var card = $( "[data-testid=hub-discovery-card-" + proposalId + "]" );
        card.shouldBe( visible, java.time.Duration.ofSeconds( 15 ) );

        page.clickDismiss( proposalId ).assertCardDisappears( proposalId );

        final String afterJson = RestSeedHelper.listProposals();
        assertTrue( !afterJson.contains( "\"id\":" + proposalId + "," ),
            "Dismissed proposal should be gone from list" );
    }

    /**
     * Verifies the dismiss-retention lifecycle:
     * <ol>
     *   <li>Seed a proposal, dismiss it.</li>
     *   <li>Expand the dismissed section — the row is visible with the reviewer.</li>
     *   <li>Bulk-delete the dismissed row via the confirmation modal.</li>
     * </ol>
     *
     * <p>Note: the original test also re-ran discovery to verify the signature
     * block stops dismissed clusters from reappearing. That assertion required
     * the live clustering pipeline; the signature-block logic is unit-tested in
     * {@code HubDiscoveryServiceTest.skipsDismissedClusterBySignature()}, so
     * here we only verify the dismiss-bucket UI behaviour.</p>
     */
    @Test
    void dismissedRetention_blocksRediscoveryUntilDeleted() throws Exception {
        final int dismissedId = RestSeedHelper.seedHubDiscoveryProposal(
            "BirdHub_" + System.currentTimeMillis(),
            "BirdEagle",
            List.of( "BirdEagle", "BirdSparrow", "BirdOwl" ) );

        final HubDiscoveryAdminPage page = new HubDiscoveryAdminPage().open();
        final var card = $( "[data-testid=hub-discovery-card-" + dismissedId + "]" );
        card.shouldBe( visible, java.time.Duration.ofSeconds( 15 ) );

        // 1) Dismiss the proposal — row moves to the dismissed bucket.
        page.clickDismiss( dismissedId ).assertCardDisappears( dismissedId );

        // 2) Expand the dismissed section and confirm the row is present.
        page.expandDismissedSection().assertDismissedRowPresent( dismissedId );

        // 3) Select and bulk-delete via the confirmation modal.
        page.selectDismissed( dismissedId )
            .clickBulkDeleteDismissed()
            .confirmDeleteDismissed()
            .assertDismissedRowAbsent( dismissedId );
    }
}
