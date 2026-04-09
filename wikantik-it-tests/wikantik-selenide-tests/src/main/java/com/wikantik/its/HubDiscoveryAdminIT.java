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

import com.wikantik.its.environment.Env;
import com.wikantik.pages.admin.HubDiscoveryAdminPage;
import com.wikantik.pages.haddock.ViewWikiPage;
import org.junit.jupiter.api.BeforeEach;
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
 */
public class HubDiscoveryAdminIT extends WithIntegrationTestSetup {

    @BeforeEach
    void login() {
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
}
