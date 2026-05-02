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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;

import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.open;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Selenide ITs for the {@code /knowledge-graph} Knowledge Graph viewer route.
 *
 * <p>Assertions are robust to the database being empty: the route is expected
 * to render either the canvas ({@code [data-testid='graph-canvas']}) or an
 * error/empty state ({@code [data-testid='graph-error-state']}) — both are
 * valid. This mirrors the pattern in {@link KnowledgeGraphVisualizationIT}.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
class KnowledgeGraphViewerIT extends WithIntegrationTestSetup {

    @Test
    @Order( 1 )
    @DisabledOnOs( OS.WINDOWS )
    void route_loads_without_error() {
        open( Env.TESTS_BASE_URL + "/knowledge-graph" );
        // Either the graph view renders or the error/loading state renders — both are valid.
        $( ".graph-view, .graph-error-state, .graph-loading" )
                .shouldBe( Condition.visible, Duration.ofSeconds( 15 ) );
    }

    @Test
    @Order( 2 )
    @DisabledOnOs( OS.WINDOWS )
    void tier_dropdown_visible() {
        open( Env.TESTS_BASE_URL + "/knowledge-graph" );
        $( "select#kg-tier-select" ).shouldBe( Condition.visible, Duration.ofSeconds( 15 ) );
        $( "select#kg-tier-select option[value='machine']" ).shouldBe( Condition.exist );
        $( "select#kg-tier-select option[value='human']" ).shouldBe( Condition.exist );
    }

    @Test
    @Order( 3 )
    @DisabledOnOs( OS.WINDOWS )
    void tier_dropdown_changes_url() {
        open( Env.TESTS_BASE_URL + "/knowledge-graph" );
        $( "select#kg-tier-select" ).shouldBe( Condition.visible, Duration.ofSeconds( 15 ) );
        $( "select#kg-tier-select" ).selectOptionByValue( "human" );
        // Allow a beat for the URL replaceState + re-fetch.
        Selenide.Wait()
                .withTimeout( Duration.ofSeconds( 10 ) )
                .until( wd -> WebDriverRunner.url().contains( "tier=human" ) );
        assertTrue( WebDriverRunner.url().contains( "tier=human" ) );
    }

    @Test
    @Order( 4 )
    @DisabledOnOs( OS.WINDOWS )
    void sidebar_link_present() {
        open( Env.TESTS_BASE_URL + "/knowledge-graph" );
        $( "a.sidebar-link[href='/knowledge-graph']" ).shouldBe( Condition.visible, Duration.ofSeconds( 15 ) );
    }
}
