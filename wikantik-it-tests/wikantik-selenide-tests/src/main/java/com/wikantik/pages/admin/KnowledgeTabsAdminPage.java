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
package com.wikantik.pages.admin;

import com.codeborne.selenide.Condition;
import com.codeborne.selenide.Selenide;
import com.wikantik.pages.Page;

import java.net.URI;
import java.time.Duration;

import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;

/**
 * Selenide page object for the admin Knowledge Graph page ({@code
 * /admin/knowledge-graph}) and its tab strip ({@code AdminKnowledgePage}).
 *
 * <p>The page renders one {@code .admin-tab} button per tab (labels:
 * Proposals, Extraction, Node Explorer, Edge Explorer, Content Embeddings,
 * Hub Proposals, Hub Discovery, LLM Activity). Selecting a tab swaps the
 * mounted panel; each panel is wrapped in a container carrying
 * {@code data-testid="kg-tab-panel-<id>"}, so a smoke-level mount check can
 * assert the selected panel's container appears without coupling to the
 * panel's internal text or its data-dependent loading/error states.
 */
public class KnowledgeTabsAdminPage implements Page {

    /**
     * Opens {@code /admin/knowledge-graph} and waits for the default
     * (Proposals) tab panel to mount.
     *
     * <p>Uses the full {@link Page#baseUrl()} so the context-path prefix isn't
     * stripped when the IT suite runs against a non-root-context WAR.
     */
    public KnowledgeTabsAdminPage open() {
        Selenide.open( Page.baseUrl() + "/admin/knowledge-graph" );
        $( "[data-testid=kg-tab-panel-proposals]" ).shouldBe( visible, Duration.ofSeconds( 15 ) );
        return this;
    }

    /**
     * Clicks the {@code .admin-tab} button whose visible label matches the
     * given text exactly, then asserts the matching panel container mounts.
     *
     * @param label   the tab's visible label (e.g. {@code "Edge Explorer"}).
     * @param panelId the {@code kg-tab-panel-<id>} suffix expected to mount.
     */
    public KnowledgeTabsAdminPage clickTabAndAssertPanel( final String label, final String panelId ) {
        $$( ".admin-tab" )
            .findBy( Condition.exactText( label ) )
            .shouldBe( visible, Duration.ofSeconds( 10 ) )
            .click();
        $( "[data-testid=kg-tab-panel-" + panelId + "]" )
            .shouldBe( visible, Duration.ofSeconds( 15 ) );
        return this;
    }

    /** Returns the path component of the current browser URL. */
    public String currentPath() {
        return URI.create( url() ).getPath();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The admin SPA has no wiki title — return an empty default to satisfy
     * the {@link Page} contract.
     */
    @Override
    public String wikiTitle() {
        return "";
    }

    /**
     * {@inheritDoc}
     *
     * <p>See {@link #wikiTitle()}.
     */
    @Override
    public String wikiPageContent() {
        return "";
    }
}
