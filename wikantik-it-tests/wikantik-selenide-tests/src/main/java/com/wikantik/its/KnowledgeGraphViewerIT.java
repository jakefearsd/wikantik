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
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;

import static com.codeborne.selenide.Condition.*;
import static com.codeborne.selenide.Selenide.*;

/**
 * Selenide ITs for the {@code /knowledge-graph} Knowledge Graph viewer route.
 *
 * <p>Assertions are robust to the database being empty: the route is expected
 * to render either the canvas ({@code [data-testid='graph-canvas']}) or an
 * error/empty state ({@code [data-testid='graph-error-state']}) — both are
 * valid. This mirrors the pattern in {@link KnowledgeGraphVisualizationIT}.
 *
 * <p>Order 1 logs in so subsequent tests inherit the session.
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
     * The sidebar no longer links to the Knowledge Graph viewer (the route itself stays reachable
     * by URL). Waits for the "Page Graph" link first so the absence check runs against a rendered
     * sidebar, not an empty page.
     */
    @Test
    @Order( 2 )
    void sidebar_has_no_knowledge_graph_link() {
        ViewWikiPage.open( "Main" );
        $$( "a.sidebar-link" ).findBy( exactText( "Page Graph" ) ).shouldBe( visible, Duration.ofSeconds( 15 ) );
        $$( "a.sidebar-link" ).findBy( exactText( "Knowledge Graph" ) ).shouldNot( exist );
    }

    /**
     * If the DB is empty the Knowledge Graph snapshot returns {@code nodeCount: 0}
     * and the view renders {@code KgErrorState variant="empty"} instead of the
     * toolbar. Accept either the tier dropdown OR the empty-state element.
     */
    @Test
    @Order( 3 )
    void tier_dropdown_visible() {
        open( Env.TESTS_BASE_URL + "/knowledge-graph" );
        $( "select#kg-tier-select, [data-testid='graph-error-state']" )
                .shouldBe( visible, Duration.ofSeconds( 15 ) );
    }

    /**
     * Seeds a single KG node so the dropdown renders, then exercises the
     * tier-select → URL-state binding. Empty-DB conditional skip removed
     * 2026-05-04 — IT now seeds its own data instead of opting out.
     */
    @Test
    @Order( 4 )
    void tier_dropdown_changes_url() {
        // Seed before navigating: the SPA snapshot is fetched on mount.
        // source_page must NOT be a system/hub page — those are in kg_excluded_pages
        // by default and KgInclusionFilter would hide the freshly-inserted node.
        RestSeedHelper.seedKgNode( "TierDropdownFixture", "concept", "TierDropdownFixturePage" );

        open( Env.TESTS_BASE_URL + "/knowledge-graph" );
        final var dropdown = $( "select#kg-tier-select" );
        dropdown.shouldBe( visible, Duration.ofSeconds( 15 ) );
        dropdown.selectOptionByValue( "human" );
        // Allow a beat for the URL replaceState + re-fetch.
        Selenide.Wait()
                .withTimeout( Duration.ofSeconds( 10 ) )
                .until( wd -> WebDriverRunner.url().contains( "tier=human" ) );
        org.junit.jupiter.api.Assertions.assertTrue(
                WebDriverRunner.url().contains( "tier=human" ) );
    }
}
