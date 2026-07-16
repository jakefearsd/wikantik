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
package com.wikantik.its.selenide;

import com.wikantik.its.WithIntegrationTestSetup;
import com.wikantik.its.environment.Env;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;

import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.open;

/**
 * Browser-level Cargo IT for the {@code wikantik.knowledge.enabled} master
 * flag&mdash;companion to the wire-level {@link com.wikantik.its.rest.KnowledgeDisabledIT}
 * in this same module. That test proves the KG REST/MCP surfaces refuse
 * cleanly (503, tools absent); this test proves the React SPA itself never
 * shows a "Knowledge Graph" nav entry when this deployment has the subsystem
 * off, and that a user who navigates to {@code /knowledge-graph} directly
 * (bookmark, stale link, typed URL) gets the disabled-feature panel instead
 * of a broken graph canvas.
 *
 * <p>Counterpart to {@code KnowledgeGraphViewerIT#sidebar_link_present} in
 * {@code wikantik-selenide-tests} (which asserts the link IS present against
 * the KG-enabled deployment) — this module deliberately excludes that shared
 * IT suite (see pom.xml {@code dependenciesToScan} override) since its
 * assumptions don't hold here, and instead ships this smaller, KG-disabled-specific
 * assertion.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
class KnowledgeGraphNavDisabledIT extends WithIntegrationTestSetup {

    /**
     * Anonymous reader sidebar ("Wiki Tools" section) must not carry a
     * "Knowledge Graph" link when the subsystem is off — that section has no
     * role gate, so this is checkable pre-login.
     */
    @Test
    @Order( 1 )
    void readerSidebar_hasNoKnowledgeGraphLink() {
        ViewWikiPage.open( "Main" );
        $$( "a.sidebar-link" ).findBy( exactText( "Knowledge Graph" ) )
                .shouldNot( exist );
    }

    /**
     * The admin section of the same sidebar (rendered only for an
     * authenticated Admin user) must also omit its "Knowledge Graph" entry.
     */
    @Test
    @Order( 2 )
    void adminSidebarSection_hasNoKnowledgeGraphLink() {
        ViewWikiPage.open( "Main" )
                .clickOnLogin()
                .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
        $$( "a.sidebar-link" ).findBy( exactText( "Knowledge Graph" ) )
                .shouldNot( exist );
    }

    /**
     * Direct navigation to {@code /knowledge-graph} (bookmark, stale link,
     * typed URL) must show the disabled-feature panel rather than attempting
     * to load a graph the backend can never serve (503).
     */
    @Test
    @Order( 3 )
    void directNavigationToKnowledgeGraphRoute_showsDisabledPanel() {
        open( Env.TESTS_BASE_URL + "/knowledge-graph" );
        $$( "[data-testid='graph-error-state']" ).first()
                .shouldBe( visible, Duration.ofSeconds( 15 ) )
                .shouldHave( text( "disabled" ) );
    }
}
