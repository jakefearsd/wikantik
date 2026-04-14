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

/**
 * Selenide ITs for the {@code /graph} knowledge graph visualization route.
 *
 * <p>The IT modules may or may not have PostgreSQL. When the database is
 * absent the graph API returns 500 and the frontend shows an error state.
 * Tests are written to pass in both scenarios: they verify the route loads
 * and renders React components, not that graph data is present.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
class KnowledgeGraphVisualizationIT extends WithIntegrationTestSetup {

    /**
     * Authenticated tests run first (orders 1-3). The anonymous test runs
     * last (order 10) and kills the WebDriver to clear session state, so
     * there is no need for a {@code @BeforeEach} login — the first test
     * logs in and subsequent tests reuse the session.
     */
    @Test
    @Order( 1 )
    @DisabledOnOs( OS.WINDOWS )
    void graphView_sidebarLinkIncludesFocus() {
        ViewWikiPage.open( "Main" )
            .clickOnLogin()
            .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
        $$( "a" ).findBy( text( "Knowledge Graph" ) )
                .shouldBe( visible )
                .shouldHave( attributeMatching( "href", ".*?/graph\\?focus=Main" ) );
    }

    @Test
    @Order( 2 )
    @DisabledOnOs( OS.WINDOWS )
    void graphView_routeRendersReactComponent() {
        open( Env.TESTS_BASE_URL + "/graph" );
        $( ".graph-view, .graph-error-state, .graph-loading" )
                .shouldBe( visible, Duration.ofSeconds( 15 ) );
    }

    @Test
    @Order( 3 )
    @DisabledOnOs( OS.WINDOWS )
    void graphView_authenticatedSeesGraphOrServerError() {
        open( Env.TESTS_BASE_URL + "/graph" );
        $( ".graph-view, [data-testid='graph-error-state']" )
                .shouldBe( visible, Duration.ofSeconds( 15 ) );
        // If the server error state rendered (graph API returned non-2xx),
        // verify it is NOT the sign-in prompt — the authenticated path
        // should reach the graph endpoint with an auth cookie and fail (or
        // succeed) on graph-level concerns, not on authentication.
        if ( $( ".graph-error-state" ).exists() ) {
            $( ".graph-error-state" ).shouldNotHave( text( "Sign in" ) );
        }
    }

    @Test
    @Order( 10 )
    @DisabledOnOs( OS.WINDOWS )
    void graphView_anonymousShowsSignInPrompt() {
        Selenide.closeWebDriver();
        open( Env.TESTS_BASE_URL + "/graph" );
        $( ".graph-error-state, .graph-loading" )
                .shouldBe( visible, Duration.ofSeconds( 15 ) );
        $( ".graph-error-state" )
                .shouldBe( visible, Duration.ofSeconds( 15 ) );
        $( ".graph-error-state" ).shouldHave( text( "Sign in" ) );
    }
}
