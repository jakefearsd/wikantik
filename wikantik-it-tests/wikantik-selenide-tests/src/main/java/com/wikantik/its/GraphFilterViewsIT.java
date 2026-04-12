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
import com.codeborne.selenide.WebDriverRunner;
import com.wikantik.its.environment.Env;
import com.wikantik.pages.haddock.ViewWikiPage;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;

import static com.codeborne.selenide.Condition.*;
import static com.codeborne.selenide.Selenide.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Selenide ITs for the {@code /graph} filter UI and URL synchronisation.
 *
 * <p>The IT modules may or may not have PostgreSQL. When the database is
 * absent the graph API returns 500 and the frontend shows an error state.
 * Each test guards itself with {@link org.junit.jupiter.api.Assumptions#assumeTrue}
 * so that, if {@code .graph-view} is not rendered, the test is skipped rather
 * than failed.
 *
 * <p>Test ordering: order 1 performs the login and opens {@code /graph};
 * subsequent tests reuse the authenticated browser session without
 * re-logging in.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
class GraphFilterViewsIT extends WithIntegrationTestSetup {

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    /**
     * Opens {@code /graph}, waits up to 15 s for either the graph view or an
     * error state to become visible, then calls
     * {@code assumeTrue($(".graph-view").is(visible))} so that the calling
     * test is skipped (not failed) when the database is absent.
     */
    private static void openGraphAndAssumeGraphViewVisible() {
        open( Env.TESTS_BASE_URL + "/graph" );
        $( ".graph-view, .graph-error-state" )
                .shouldBe( visible, Duration.ofSeconds( 15 ) );
        assumeTrue( $( ".graph-view" ).is( visible ),
                "graph-view not rendered — database likely absent; skipping test" );
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * Order 1 — performs login, then verifies that clicking the Backbone
     * preset pill appends {@code preset=backbone} to the URL.
     */
    @Test
    @Order( 1 )
    @DisabledOnOs( OS.WINDOWS )
    void backbonePresetAddsUrlParam() {
        ViewWikiPage.open( "Main" )
                .clickOnLogin()
                .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );

        openGraphAndAssumeGraphViewVisible();

        $( ".filter-preset-row" ).shouldBe( visible, Duration.ofSeconds( 15 ) );
        $$( ".filter-preset-pill" ).findBy( text( "Backbone" ) ).click();

        Selenide.Wait()
                .withTimeout( Duration.ofSeconds( 15 ) )
                .until( d -> WebDriverRunner.url().contains( "preset=backbone" ) );
    }

    /**
     * Order 2 — clicking the Communities preset makes the cluster legend
     * visible in the graph sidebar.
     */
    @Test
    @Order( 2 )
    @DisabledOnOs( OS.WINDOWS )
    void communitiesPresetShowsClusterLegend() {
        openGraphAndAssumeGraphViewVisible();

        $( ".filter-preset-row" ).shouldBe( visible, Duration.ofSeconds( 15 ) );
        $$( ".filter-preset-pill" ).findBy( text( "Communities" ) ).click();

        $( ".cluster-legend" ).shouldBe( visible, Duration.ofSeconds( 15 ) );
    }

    /**
     * Order 3 — when the URL already encodes {@code preset=backbone&hop=1}
     * the page must restore that state: the Backbone pill is active and the
     * +1 hop checkbox is checked.
     */
    @Test
    @Order( 3 )
    @DisabledOnOs( OS.WINDOWS )
    void backboneHopStateRestoredFromUrl() {
        open( Env.TESTS_BASE_URL + "/graph?preset=backbone&hop=1" );
        $( ".graph-view, .graph-error-state" )
                .shouldBe( visible, Duration.ofSeconds( 15 ) );
        assumeTrue( $( ".graph-view" ).is( visible ),
                "graph-view not rendered — database likely absent; skipping test" );

        $( ".filter-preset-pill.active" )
                .shouldBe( visible, Duration.ofSeconds( 15 ) )
                .shouldHave( text( "Backbone" ) );

        // The +1 hop checkbox is rendered inside the filter controls.
        $( "input[type='checkbox'][data-hop='1'], input[type='checkbox'].hop-checkbox" )
                .shouldBe( checked );
    }
}
