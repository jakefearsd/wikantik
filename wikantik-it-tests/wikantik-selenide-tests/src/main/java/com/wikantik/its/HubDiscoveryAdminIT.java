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
 *   <li>Dismissed retention -&gt; signature block on re-run, delete re-opens cluster.</li>
 * </ol>
 *
 * <p>Each test seeds a diverse background corpus ({@link #seedBackgroundCorpus})
 * plus a dense topical cluster; without both, HDBSCAN flags every page as noise
 * on a cold database and no proposals are produced.
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
     * Seed a baseline corpus of diverse pages so HDBSCAN has enough TF-IDF
     * contrast to separate a dense cluster (the per-test cooking/sports/etc.
     * pages) from noise. Empirically a cold DB with only 6–12 seeded pages
     * causes every candidate to be flagged as noise and the discovery run
     * yields zero proposals — blocking every downstream assertion. Calling
     * {@code writePage} is idempotent (PUT overwrites) so running this on
     * every test is safe and cheap.
     */
    private static void seedBackgroundCorpus() throws Exception {
        RestSeedHelper.writePage( "BgAstronomyStars", "stars distant galaxies telescope universe light years" );
        RestSeedHelper.writePage( "BgAstronomyPlanets", "planets orbit solar system rings moons atmosphere" );
        RestSeedHelper.writePage( "BgAstronomyComets", "comets tails ice dust orbit sun periodic" );
        RestSeedHelper.writePage( "BgAstronomyNebulae", "nebulae clouds gas dust stellar nursery emission" );
        RestSeedHelper.writePage( "BgMusicClassical", "music symphony orchestra composer violin piano concert" );
        RestSeedHelper.writePage( "BgMusicJazz", "jazz improvisation saxophone blues swing trumpet" );
        RestSeedHelper.writePage( "BgMusicRock", "rock guitar drums bass amplifier vocals band" );
        RestSeedHelper.writePage( "BgMusicFolk", "folk acoustic guitar ballad traditional storytelling" );
        RestSeedHelper.writePage( "BgLanguageFrench", "french language grammar conjugation accents phrases" );
        RestSeedHelper.writePage( "BgLanguageMandarin", "mandarin chinese tones characters writing pinyin" );
        RestSeedHelper.writePage( "BgLanguageSpanish", "spanish language grammar verbs subjunctive pronouns" );
        RestSeedHelper.writePage( "BgPhysicsMechanics", "physics mechanics force motion mass acceleration gravity" );
        RestSeedHelper.writePage( "BgPhysicsOptics", "physics optics light lens refraction wavelength prism" );
        RestSeedHelper.writePage( "BgBiologyCells", "biology cells membrane nucleus dna ribosomes organelles" );
        RestSeedHelper.writePage( "BgBiologyEcology", "biology ecology ecosystem species habitat food web" );
    }

    @Test
    @Disabled( "Pending Phase 2 extractor — hub discovery needs chunk_entity_mentions populated to produce proposals" )
    @DisabledOnOs(OS.WINDOWS)
    void runAcceptFlow_happyPath() throws Exception {
        seedBackgroundCorpus();
        // 15 tightly-related cooking pages with a shared token bed so HDBSCAN sees
        // them as a single dense cluster against the diverse background.
        final String cookBody = "cooking recipe kitchen heat flavor ingredient technique food preparation meal dish savor taste ";
        RestSeedHelper.writePage( "CookingBaking", cookBody + "baking bread cake flour sugar oven dough yeast" );
        RestSeedHelper.writePage( "CookingRoasting", cookBody + "roasting meat oven temperature seasoning juices" );
        RestSeedHelper.writePage( "CookingGrilling", cookBody + "grilling outdoor charcoal meat barbecue sear char" );
        RestSeedHelper.writePage( "CookingSauteing", cookBody + "sauteing pan oil butter quick vegetables stirring" );
        RestSeedHelper.writePage( "CookingBroiling", cookBody + "broiling oven top direct meat glaze caramelize" );
        RestSeedHelper.writePage( "CookingBoiling", cookBody + "boiling water pot stovetop pasta vegetables" );
        RestSeedHelper.writePage( "CookingSteaming", cookBody + "steaming basket water vegetables fish dumplings" );
        RestSeedHelper.writePage( "CookingPoaching", cookBody + "poaching liquid gentle eggs fish delicate" );
        RestSeedHelper.writePage( "CookingBraising", cookBody + "braising slow liquid meat tender sear covered" );
        RestSeedHelper.writePage( "CookingStewing", cookBody + "stewing pot liquid meat vegetables simmer" );
        RestSeedHelper.writePage( "CookingSmoking", cookBody + "smoking wood chips slow low meat flavor" );
        RestSeedHelper.writePage( "CookingFrying", cookBody + "frying oil hot pan batter crisp golden" );
        RestSeedHelper.writePage( "CookingBaking2", cookBody + "pastry dessert sweet butter vanilla custard" );
        RestSeedHelper.writePage( "CookingSousVide", cookBody + "sous vide vacuum bag water bath precise" );
        RestSeedHelper.writePage( "CookingGriddling", cookBody + "griddle flat cast iron pancakes eggs bacon" );


        final HubDiscoveryAdminPage page = new HubDiscoveryAdminPage().open();
        page.clickRunDiscovery().waitForSuccessToast();

        // Find the first proposal card that appeared. The card id is not known statically,
        // so we match the generic card data-testid prefix. A cold-start run (fresh DB +
        // freshly-seeded pages) can take 10+ seconds for retrain+clustering to publish
        // proposals to the list, so override Selenide's default 4s visibility timeout.
        final var firstCard = $( "[data-testid^='hub-discovery-card-']" );
        firstCard.shouldBe( visible, java.time.Duration.ofSeconds( 20 ) );
        final String testid = firstCard.getAttribute( "data-testid" );
        assertTrue( testid != null && testid.startsWith( "hub-discovery-card-" ) );
        final int proposalId = Integer.parseInt( testid.substring( "hub-discovery-card-".length() ) );

        // Force a unique name to avoid colliding with either preseeded wiki pages
        // or hub stubs left behind by earlier test runs against the same DB.
        page.setName( proposalId, "HappyPathHub_" + System.currentTimeMillis() )
            .clickAccept( proposalId );
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
    @Disabled( "Pending Phase 2 extractor — hub discovery needs chunk_entity_mentions populated to produce proposals" )
    @DisabledOnOs(OS.WINDOWS)
    void acceptCollisionShowsInlineError() throws Exception {
        seedBackgroundCorpus();
        // Pre-create a wiki page that a discovery card's name will collide with.
        RestSeedHelper.writePage( "ClashingHubName",
            "Pre-existing content for collision test" );
        final String sportBody = "sport athletics competition team player match score game league stadium training skill ";
        RestSeedHelper.writePage( "SportSoccer", sportBody + "soccer football goal kick field" );
        RestSeedHelper.writePage( "SportBasketball", sportBody + "basketball hoop court dribble dunk" );
        RestSeedHelper.writePage( "SportTennis", sportBody + "tennis racquet grand slam net serve" );
        RestSeedHelper.writePage( "SportRugby", sportBody + "rugby scrum ball tackle try" );
        RestSeedHelper.writePage( "SportHockey", sportBody + "hockey stick ice puck goal" );
        RestSeedHelper.writePage( "SportBaseball", sportBody + "baseball bat pitcher batter innings" );
        RestSeedHelper.writePage( "SportVolleyball", sportBody + "volleyball net spike serve block" );
        RestSeedHelper.writePage( "SportCricket", sportBody + "cricket bat wicket bowler over" );
        RestSeedHelper.writePage( "SportGolf", sportBody + "golf club course green putt fairway" );
        RestSeedHelper.writePage( "SportBoxing", sportBody + "boxing ring gloves jab hook round" );
        RestSeedHelper.writePage( "SportSkiing", sportBody + "skiing slopes snow moguls downhill slalom" );
        RestSeedHelper.writePage( "SportSwimming", sportBody + "swimming pool laps stroke freestyle backstroke" );


        final HubDiscoveryAdminPage page = new HubDiscoveryAdminPage().open();
        page.clickRunDiscovery().waitForSuccessToast();
        final var firstCard = $( "[data-testid^='hub-discovery-card-']" );
        firstCard.shouldBe( visible, java.time.Duration.ofSeconds( 20 ) );
        final int proposalId = Integer.parseInt(
            firstCard.getAttribute( "data-testid" ).substring( "hub-discovery-card-".length() ) );

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
    @Disabled( "Pending Phase 2 extractor — hub discovery needs chunk_entity_mentions populated to produce proposals" )
    @DisabledOnOs(OS.WINDOWS)
    void dismissRemovesCard() throws Exception {
        seedBackgroundCorpus();
        final String gardenBody = "garden plant flower soil water sunlight grow bloom leaf stem root seed ";
        RestSeedHelper.writePage( "GardenRose", gardenBody + "rose thorn red petal fragrance bouquet" );
        RestSeedHelper.writePage( "GardenTulip", gardenBody + "tulip bulb spring bright cup-shaped" );
        RestSeedHelper.writePage( "GardenOrchid", gardenBody + "orchid exotic tropical delicate epiphyte" );
        RestSeedHelper.writePage( "GardenSunflower", gardenBody + "sunflower yellow tall seeds sun-tracking" );
        RestSeedHelper.writePage( "GardenLily", gardenBody + "lily trumpet shaped fragrant white pink" );
        RestSeedHelper.writePage( "GardenDaisy", gardenBody + "daisy simple white yellow center meadow" );
        RestSeedHelper.writePage( "GardenIris", gardenBody + "iris rhizome sword-shaped purple blue" );
        RestSeedHelper.writePage( "GardenPeony", gardenBody + "peony shrub fragrant large lush blooms" );
        RestSeedHelper.writePage( "GardenBegonia", gardenBody + "begonia shade tender colourful foliage" );
        RestSeedHelper.writePage( "GardenCarnation", gardenBody + "carnation frilled fragrant long-lasting pink" );
        RestSeedHelper.writePage( "GardenMarigold", gardenBody + "marigold orange yellow pest-repellent annual" );
        RestSeedHelper.writePage( "GardenPansy", gardenBody + "pansy cool-season biennial colourful face" );


        final HubDiscoveryAdminPage page = new HubDiscoveryAdminPage().open();
        page.clickRunDiscovery().waitForSuccessToast();
        final var firstCard = $( "[data-testid^='hub-discovery-card-']" );
        firstCard.shouldBe( visible, java.time.Duration.ofSeconds( 20 ) );
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
    @Disabled( "Pending Phase 2 extractor — hub discovery needs chunk_entity_mentions populated to produce proposals" )
    @DisabledOnOs(OS.WINDOWS)
    void dismissedRetention_blocksRediscoveryUntilDeleted() throws Exception {
        seedBackgroundCorpus();
        final String birdBody = "bird feather wing nest egg beak flight migration species habitat song plumage ";
        RestSeedHelper.writePage( "BirdEagle", birdBody + "eagle raptor talons soaring majestic hunter" );
        RestSeedHelper.writePage( "BirdSparrow", birdBody + "sparrow small brown common urban seeds" );
        RestSeedHelper.writePage( "BirdOwl", birdBody + "owl nocturnal silent prey mouse vision" );
        RestSeedHelper.writePage( "BirdParrot", birdBody + "parrot colourful tropical mimicry intelligent" );
        RestSeedHelper.writePage( "BirdCrow", birdBody + "crow black clever tool-user scavenger" );
        RestSeedHelper.writePage( "BirdHummingbird", birdBody + "hummingbird tiny nectar hover rapid wings" );
        RestSeedHelper.writePage( "BirdFalcon", birdBody + "falcon peregrine swift stoop dive" );
        RestSeedHelper.writePage( "BirdPenguin", birdBody + "penguin flightless antarctic swim cold" );
        RestSeedHelper.writePage( "BirdPelican", birdBody + "pelican pouch fish coastal glide wing span" );
        RestSeedHelper.writePage( "BirdHeron", birdBody + "heron wading long legs patient fish" );
        RestSeedHelper.writePage( "BirdSwallow", birdBody + "swallow aerial insect catcher forked tail" );
        RestSeedHelper.writePage( "BirdRobin", birdBody + "robin red breast garden spring worm" );


        final HubDiscoveryAdminPage page = new HubDiscoveryAdminPage().open();
        page.clickRunDiscovery().waitForSuccessToast();

        final var firstCard = $( "[data-testid^='hub-discovery-card-']" );
        firstCard.shouldBe( visible, java.time.Duration.ofSeconds( 20 ) );
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
