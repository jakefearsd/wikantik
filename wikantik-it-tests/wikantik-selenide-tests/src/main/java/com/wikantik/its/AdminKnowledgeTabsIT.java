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
import com.wikantik.pages.admin.KnowledgeTabsAdminPage;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Smoke-level end-to-end test for the admin Knowledge Graph tab strip
 * ({@code /admin/knowledge-graph}). Clicks through all eight {@code .admin-tab}
 * buttons by their visible label and asserts each tab's panel container mounts.
 *
 * <p>This is a per-tab <em>mount</em> check, not a content check: it proves the
 * lazily-rendered panel for every tab swaps in without crashing. The panels'
 * own behaviour (proposal review, extraction, hub discovery, …) is exercised by
 * dedicated ITs and unit tests. To stay decoupled from each panel's
 * data-dependent loading/error states, the assertion targets the
 * {@code kg-tab-panel-<id>} container baked into {@code AdminKnowledgePage}.
 */
public class AdminKnowledgeTabsIT extends WithIntegrationTestSetup {

    /**
     * Tab label (as rendered on the {@code .admin-tab} button) → expected
     * {@code kg-tab-panel-<id>} container suffix. Order mirrors the tab strip
     * in {@code AdminKnowledgePage}.
     */
    private static final List< Map.Entry< String, String > > TABS = List.of(
        Map.entry( "Proposals",          "proposals" ),
        Map.entry( "Extraction",         "extraction" ),
        Map.entry( "Node Explorer",      "node-explorer" ),
        Map.entry( "Edge Explorer",      "edge-explorer" ),
        Map.entry( "Content Embeddings", "content-embeddings" ),
        Map.entry( "Hub Proposals",      "hub-proposals" ),
        Map.entry( "Hub Discovery",      "hub-discovery" ),
        Map.entry( "LLM Activity",       "llm-activity" ) );

    @BeforeEach
    void login() {
        // Fresh browser session per test so each starts anonymous — see the
        // rationale in HubDiscoveryAdminIT.login().
        Selenide.closeWebDriver();
        ViewWikiPage.open( "Main" )
            .clickOnLogin()
            .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
        // Guard the post-login session-principal binding race before hitting /admin.
        RestSeedHelper.awaitAdminReady();
    }

    @Test
    void clickThroughAllTabs_eachPanelMounts() {
        final KnowledgeTabsAdminPage page = new KnowledgeTabsAdminPage().open();

        for ( final Map.Entry< String, String > tab : TABS ) {
            page.clickTabAndAssertPanel( tab.getKey(), tab.getValue() );
        }
    }
}
