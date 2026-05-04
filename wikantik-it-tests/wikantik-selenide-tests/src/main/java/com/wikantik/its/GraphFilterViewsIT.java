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

import java.time.Duration;

import static com.codeborne.selenide.Condition.*;
import static com.codeborne.selenide.Selenide.*;

/**
 * Selenide ITs for the {@code /page-graph} filter UI and URL synchronisation.
 *
 * <p>Test ordering: order 1 performs the login and opens {@code /page-graph};
 * subsequent tests reuse the authenticated browser session without
 * re-logging in.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
class GraphFilterViewsIT extends WithIntegrationTestSetup {

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    /** Opens {@code /page-graph} and waits for the graph view to render. */
    private static void openGraphView() {
        open( Env.TESTS_BASE_URL + "/page-graph" );
        $( ".graph-view" ).shouldBe( visible, Duration.ofSeconds( 15 ) );
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
    void backbonePresetAddsUrlParam() {
        ViewWikiPage.open( "Main" )
                .clickOnLogin()
                .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );

        openGraphView();

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
    void communitiesPresetShowsClusterLegend() {
        openGraphView();

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
    void backboneHopStateRestoredFromUrl() {
        open( Env.TESTS_BASE_URL + "/page-graph?preset=backbone&hop=1" );
        $( ".graph-view" ).shouldBe( visible, Duration.ofSeconds( 15 ) );

        $( ".filter-preset-pill.active" )
                .shouldBe( visible, Duration.ofSeconds( 15 ) )
                .shouldHave( text( "Backbone" ) );

        // The +1 hop checkbox is rendered inside the Backbone filter section's <label>.
        $( ".filter-section input[type='checkbox']" ).shouldBe( checked );
    }
}
