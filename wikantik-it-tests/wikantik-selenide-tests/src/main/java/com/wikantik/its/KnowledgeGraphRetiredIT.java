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
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;

import static com.codeborne.selenide.Condition.*;
import static com.codeborne.selenide.Selenide.*;

/**
 * The Knowledge Graph was retired: the reader sidebar has no link to it and the
 * {@code /knowledge-graph} route no longer renders the viewer.
 *
 * <p>Order 1 logs in so subsequent tests inherit the session.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
class KnowledgeGraphRetiredIT extends WithIntegrationTestSetup {

    /**
     * Waits for the "Page Graph" link first so the absence check runs against a rendered
     * sidebar, not an empty page (a bare {@code shouldNot( exist )} would be vacuous).
     */
    @Test
    @Order( 1 )
    void sidebar_has_no_knowledge_graph_link() {
        ViewWikiPage.open( "Main" )
                .clickOnLogin()
                .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
        ViewWikiPage.open( "Main" );
        $$( "a.sidebar-link" ).findBy( exactText( "Page Graph" ) ).shouldBe( visible, Duration.ofSeconds( 15 ) );
        $$( "a.sidebar-link" ).findBy( exactText( "Knowledge Graph" ) ).shouldNot( exist );
    }

    /** The old viewer route must not mount the graph view, its tier selector or its canvas. */
    @Test
    @Order( 2 )
    void knowledge_graph_route_no_longer_renders_the_viewer() {
        open( Env.TESTS_BASE_URL + "/knowledge-graph" );
        $( "body" ).shouldBe( visible, Duration.ofSeconds( 15 ) );
        $( ".graph-view" ).shouldNot( exist );
        $( "select#kg-tier-select" ).shouldNot( exist );
        $( "[data-testid='graph-canvas']" ).shouldNot( exist );
    }
}
