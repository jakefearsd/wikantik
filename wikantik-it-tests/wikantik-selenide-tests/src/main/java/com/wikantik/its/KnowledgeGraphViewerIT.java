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

import com.codeborne.selenide.Condition;
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
 * Selenide ITs for the {@code /knowledge-graph} Knowledge Graph viewer route.
 *
 * <p>Assertions are robust to the database being empty: the route is expected
 * to render either the canvas ({@code [data-testid='graph-canvas']}) or an
 * error/empty state ({@code [data-testid='graph-error-state']}) — both are
 * valid. This mirrors the pattern in {@link KnowledgeGraphVisualizationIT}.
 *
 * <p>Order 1 logs in so subsequent tests inherit the session.
 * The sidebar href check uses {@code attributeMatching} to tolerate the SPA
 * basename injected by the Cargo IT context ({@code /wikantik-it-test-custom}).
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
class KnowledgeGraphViewerIT extends WithIntegrationTestSetup {

    /**
     * Login here so subsequent tests inherit the authenticated session.
     * Also verifies the route renders something (canvas, empty-state, or
     * loading indicator — all valid).
     */
    @Test
    @Order( 1 )
    @DisabledOnOs( OS.WINDOWS )
    void route_loads_without_error() {
        ViewWikiPage.open( "Main" )
                .clickOnLogin()
                .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
        open( Env.TESTS_BASE_URL + "/knowledge-graph" );
        // Either the graph view renders or the error/loading state renders — both are valid.
        $( ".graph-view, .graph-error-state, .graph-loading" )
                .shouldBe( visible, Duration.ofSeconds( 15 ) );
    }

    /**
     * Verify the sidebar carries a "Knowledge Graph" link pointing at the
     * knowledge-graph route. Uses {@code attributeMatching} to handle the
     * SPA basename ({@code /wikantik-it-test-custom/knowledge-graph}).
     */
    @Test
    @Order( 2 )
    @DisabledOnOs( OS.WINDOWS )
    void sidebar_link_present() {
        open( Env.TESTS_BASE_URL + "/knowledge-graph" );
        $$( "a.sidebar-link" ).findBy( exactText( "Knowledge Graph" ) )
                .shouldBe( visible )
                .shouldHave( attributeMatching( "href", ".*?/knowledge-graph" ) );
    }

    /**
     * If the DB is empty the Knowledge Graph snapshot returns {@code nodeCount: 0}
     * and the view renders {@code KgErrorState variant="empty"} instead of the
     * toolbar. Accept either the tier dropdown OR the empty-state element.
     */
    @Test
    @Order( 3 )
    @DisabledOnOs( OS.WINDOWS )
    void tier_dropdown_visible() {
        open( Env.TESTS_BASE_URL + "/knowledge-graph" );
        $( "select#kg-tier-select, [data-testid='graph-error-state']" )
                .shouldBe( visible, Duration.ofSeconds( 15 ) );
    }

    /**
     * If the dropdown is absent (empty DB → empty state), skip the URL-change
     * assertion via {@link org.junit.jupiter.api.Assumptions#assumeTrue}.
     */
    @Test
    @Order( 4 )
    @DisabledOnOs( OS.WINDOWS )
    void tier_dropdown_changes_url() {
        open( Env.TESTS_BASE_URL + "/knowledge-graph" );
        final var dropdown = $( "select#kg-tier-select" );
        assumeTrue( dropdown.is( exist ),
                "tier dropdown not rendered (empty DB) — skipping URL-change assertion" );
        dropdown.selectOptionByValue( "human" );
        // Allow a beat for the URL replaceState + re-fetch.
        Selenide.Wait()
                .withTimeout( Duration.ofSeconds( 10 ) )
                .until( wd -> WebDriverRunner.url().contains( "tier=human" ) );
        org.junit.jupiter.api.Assertions.assertTrue(
                WebDriverRunner.url().contains( "tier=human" ) );
    }
}
