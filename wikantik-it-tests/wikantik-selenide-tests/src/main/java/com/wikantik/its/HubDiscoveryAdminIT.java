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
import com.wikantik.pages.haddock.ViewWikiPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.open;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the Hub Discovery admin UI. Covers:
 * <ol>
 *   <li>Happy-path run -&gt; accept -&gt; graph projection -&gt; stub page visible.</li>
 *   <li>409 collision -&gt; inline error, card retained, existing page untouched.</li>
 *   <li>Dismiss -&gt; card disappears, list empty.</li>
 * </ol>
 *
 * <p><b>Disabled:</b> Hub Discovery requires a PostgreSQL datasource with the
 * pgvector extension to persist content embeddings and hub proposals. The
 * {@code wikantik-it-test-custom} module provisions only XML user/group
 * databases, and {@code wikantik-it-test-custom-jdbc} uses HSQLDB, neither of
 * which can host {@code kg_content_embeddings} or
 * {@code hub_discovery_proposals}. Re-enable once a PostgreSQL test-container
 * IT module is added.
 */
@Disabled("Requires a PostgreSQL+pgvector datasource; no IT module currently provides one")
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

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void runAcceptFlow_happyPath() throws Exception {
        // Seed six tightly-related cooking pages via the page REST API so the content
        // model picks them up on its next retrain. The wikantik.properties used by ITs
        // must enable on-save content-model updates (it does by default).
        RestSeedHelper.writePage( "CookingBaking", "baking bread cake flour sugar oven recipes" );
        RestSeedHelper.writePage( "CookingRoasting", "roasting meat oven temperature seasoning recipes" );
        RestSeedHelper.writePage( "CookingGrilling", "grilling outdoor charcoal meat barbecue" );
        RestSeedHelper.writePage( "CookingSauteing", "sauteing pan oil butter quick heat" );
        RestSeedHelper.writePage( "CookingBroiling", "broiling oven top direct heat meat" );
        RestSeedHelper.writePage( "CookingBoiling", "boiling water pot heat stovetop" );
        // Force a content-model retrain so the seeded pages are in the
        // TfidfModel's entity list before discovery runs.
        RestSeedHelper.retrainContentModelViaBrowser();

        final HubDiscoveryAdminPage page = new HubDiscoveryAdminPage().open();
        page.clickRunDiscovery().waitForSuccessToast();

        // Find the first proposal card that appeared. The card id is not known statically,
        // so we match the generic card data-testid prefix.
        final var firstCard = $( "[data-testid^='hub-discovery-card-']" );
        firstCard.shouldBe( visible );
        final String testid = firstCard.getAttribute( "data-testid" );
        assertTrue( testid != null && testid.startsWith( "hub-discovery-card-" ) );
        final int proposalId = Integer.parseInt( testid.substring( "hub-discovery-card-".length() ) );

        page.clickAccept( proposalId );
        page.assertCardDisappears( proposalId );

        // The stub page should now exist. Its name is the exemplar page name — the test
        // does not know it statically, so rely on the REST list to confirm the row was
        // deleted (side-effect verification). Navigating to the stub by name would also
        // work if the test plumbs the exemplar name out of the response.
        final String afterJson = RestSeedHelper.listProposals();
        // The just-accepted proposal's id must no longer appear.
        assertTrue( !afterJson.contains( "\"id\":" + proposalId + "," ),
            "Accepted proposal should be gone from list" );
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void acceptCollisionShowsInlineError() throws Exception {
        // Pre-create a wiki page that a discovery card's name will collide with.
        RestSeedHelper.writePage( "ClashingHubName",
            "Pre-existing content for collision test" );
        // Seed enough cooking pages to produce at least one cluster.
        RestSeedHelper.writePage( "SportSoccer", "soccer football goal player team" );
        RestSeedHelper.writePage( "SportBasketball", "basketball hoop court player team" );
        RestSeedHelper.writePage( "SportTennis", "tennis racquet grand slam court" );
        RestSeedHelper.writePage( "SportRugby", "rugby scrum ball tackle field" );
        RestSeedHelper.writePage( "SportHockey", "hockey stick ice puck goal" );
        RestSeedHelper.writePage( "SportBaseball", "baseball bat ball pitcher batter" );
        RestSeedHelper.retrainContentModelViaBrowser();

        final HubDiscoveryAdminPage page = new HubDiscoveryAdminPage().open();
        page.clickRunDiscovery().waitForSuccessToast();
        final var firstCard = $( "[data-testid^='hub-discovery-card-']" );
        firstCard.shouldBe( visible );
        final int proposalId = Integer.parseInt(
            firstCard.getAttribute( "data-testid" ).substring( "hub-discovery-card-".length() ) );

        page.setName( proposalId, "ClashingHubName" )
            .clickAccept( proposalId )
            .waitForErrorToast()
            .assertCardStillPresent( proposalId );

        // Pre-existing page still has original content.
        open( "/" + "ClashingHubName" );
        $( "main" ).shouldHave( com.codeborne.selenide.Condition.text( "Pre-existing content" ) );
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void dismissRemovesCard() throws Exception {
        RestSeedHelper.writePage( "DismissBaking", "baking bread cake flour sugar oven" );
        RestSeedHelper.writePage( "DismissRoasting", "roasting meat oven temperature seasoning" );
        RestSeedHelper.writePage( "DismissGrilling", "grilling outdoor charcoal meat barbecue" );
        RestSeedHelper.writePage( "DismissSauteing", "sauteing pan oil butter quick heat" );
        RestSeedHelper.writePage( "DismissBroiling", "broiling oven top direct heat meat" );
        RestSeedHelper.writePage( "DismissBoiling", "boiling water pot heat stovetop" );
        RestSeedHelper.retrainContentModelViaBrowser();

        final HubDiscoveryAdminPage page = new HubDiscoveryAdminPage().open();
        page.clickRunDiscovery().waitForSuccessToast();
        final var firstCard = $( "[data-testid^='hub-discovery-card-']" );
        firstCard.shouldBe( visible );
        final int proposalId = Integer.parseInt(
            firstCard.getAttribute( "data-testid" ).substring( "hub-discovery-card-".length() ) );

        page.clickDismiss( proposalId ).assertCardDisappears( proposalId );

        final String afterJson = RestSeedHelper.listProposals();
        assertTrue( !afterJson.contains( "\"id\":" + proposalId + "," ),
            "Dismissed proposal should be gone from list" );
    }

    /**
     * Verifies the full dismiss-retention lifecycle:
     * <ol>
     *   <li>Seed content, run discovery, dismiss the top proposal.</li>
     *   <li>Re-run discovery — the dismissed cluster must NOT reappear
     *       (signature-based rediscovery block).</li>
     *   <li>Expand the dismissed section — the row is visible with the reviewer.</li>
     *   <li>Bulk-delete the dismissed row via the confirmation modal.</li>
     *   <li>Re-run discovery — the cluster IS rediscovered, proving delete
     *       re-opens the cluster for proposal generation.</li>
     * </ol>
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void dismissedRetention_blocksRediscoveryUntilDeleted() throws Exception {
        RestSeedHelper.writePage( "RetainBaking", "baking bread cake flour sugar oven" );
        RestSeedHelper.writePage( "RetainRoasting", "roasting meat oven temperature seasoning" );
        RestSeedHelper.writePage( "RetainGrilling", "grilling outdoor charcoal meat barbecue" );
        RestSeedHelper.writePage( "RetainSauteing", "sauteing pan oil butter quick heat" );
        RestSeedHelper.writePage( "RetainBroiling", "broiling oven top direct heat meat" );
        RestSeedHelper.writePage( "RetainBoiling", "boiling water pot heat stovetop" );
        RestSeedHelper.retrainContentModelViaBrowser();

        final HubDiscoveryAdminPage page = new HubDiscoveryAdminPage().open();
        page.clickRunDiscovery().waitForSuccessToast();

        final var firstCard = $( "[data-testid^='hub-discovery-card-']" );
        firstCard.shouldBe( visible );
        final int dismissedId = Integer.parseInt(
            firstCard.getAttribute( "data-testid" ).substring( "hub-discovery-card-".length() ) );

        // 1) Dismiss the top proposal — row moves to the dismissed bucket.
        page.clickDismiss( dismissedId ).assertCardDisappears( dismissedId );

        // 2) Re-run discovery — cluster must be skipped via signature match.
        page.clickRunDiscovery().waitForSuccessToast();
        final String afterRerun = RestSeedHelper.listProposals();
        assertTrue( !afterRerun.contains( "\"id\":" + dismissedId + "," ),
            "Dismissed proposal's id should not reappear in pending list after re-run" );

        // 3) Expand the dismissed section and confirm the row is present.
        page.expandDismissedSection().assertDismissedRowPresent( dismissedId );

        // 4) Select and bulk-delete via the confirmation modal.
        page.selectDismissed( dismissedId )
            .clickBulkDeleteDismissed()
            .confirmDeleteDismissed()
            .assertDismissedRowAbsent( dismissedId );

        // 5) Re-run discovery — the previously-dismissed cluster should be rediscovered.
        page.clickRunDiscovery().waitForSuccessToast();
        // A new pending card should exist with any id (the old one is gone from the DB).
        $( "[data-testid^='hub-discovery-card-']" )
            .shouldBe( visible, java.time.Duration.ofSeconds( 15 ) );
    }
}
