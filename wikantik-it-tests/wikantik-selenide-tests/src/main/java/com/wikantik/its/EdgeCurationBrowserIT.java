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
import com.wikantik.pages.Page;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;

/**
 * End-to-end browser smoke for the admin Edge Explorer curation flow.
 * Drives login → seed nodes via REST helper → open Edge Explorer →
 * Create → Delete + Prevent → verify row removed.
 *
 * <p>Tab label is "Edge Explorer" (AdminKnowledgePage TABS array uses that
 * label, not the bare word "Edges"). The EdgeFormModal source/target inputs
 * carry {@code aria-label="Source"} and {@code aria-label="Target"}.
 * The ConfirmModal for delete-and-reject has an extra field whose
 * {@code aria-label} is {@code "reason"} (lowercased from the label "Reason").</p>
 *
 * <p>Mirrors the HubDiscoveryAdminIT pattern.</p>
 */
public class EdgeCurationBrowserIT extends WithIntegrationTestSetup {

    private static final String SUFFIX = "" + System.currentTimeMillis();
    private static final String SRC = "EdgeBrowserSrc" + SUFFIX;
    private static final String TGT = "EdgeBrowserTgt" + SUFFIX;

    @BeforeEach
    void login() {
        // Reset the browser session between test methods so each test starts
        // anonymous. Without this the test inherits auth state from a previous
        // class and clickOnLogin() fails because no signin button is rendered.
        Selenide.closeWebDriver();
        ViewWikiPage.open( "Main" )
            .clickOnLogin()
            .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
    }

    @Test
    void edgeExplorerCreateAndDeletePreventFlow() {
        // Open the admin knowledge-graph page first so seedKgNode's
        // executeAsyncJavaScript runs in the SPA context where the session
        // cookie is attached (AdminAuthFilter accepts browser-session cookies).
        Selenide.open( Page.baseUrl() + "/admin/knowledge-graph" );

        RestSeedHelper.seedKgNode( SRC, "concept", null );
        RestSeedHelper.seedKgNode( TGT, "concept", null );

        // Switch to the Edge Explorer tab.  AdminKnowledgePage renders the tab
        // as a plain <button> with label "Edge Explorer" (no data-testid).
        $$( "button" ).findBy( text( "Edge Explorer" ) )
            .shouldBe( visible, Duration.ofSeconds( 10 ) )
            .click();

        // Click "New edge" to open the EdgeFormModal.
        $$( "button" ).findBy( text( "New edge" ) )
            .shouldBe( visible, Duration.ofSeconds( 10 ) )
            .click();

        // The EdgeFormModal carries role="dialog".
        $( "[role=dialog]" ).shouldBe( visible );

        // Source field: aria-label="Source" (NodeAutocomplete passes label prop
        // through as aria-label on its <input>).
        $( "input[aria-label=Source]" ).setValue( SRC );
        // The autocomplete renders results as <li><button class="btn-link">.
        // Selenide text() does a substring match, so SRC text in the button is enough.
        $$( "li button" ).findBy( text( SRC ) )
            .shouldBe( visible, Duration.ofSeconds( 10 ) )
            .click();

        // Target field.
        $( "input[aria-label=Target]" ).setValue( TGT );
        $$( "li button" ).findBy( text( TGT ) )
            .shouldBe( visible, Duration.ofSeconds( 10 ) )
            .click();

        // Relationship dropdown: aria-label="Relationship".
        $( "select[aria-label=Relationship]" ).selectOptionByValue( "related_to" );

        // Save the new edge.
        $$( "button" ).findBy( text( "Save" ) ).click();
        // Modal should close after successful save.
        $( "[role=dialog]" ).shouldNotBe( visible, Duration.ofSeconds( 10 ) );

        // The new edge row should appear in the EdgeExplorer table.
        $( "table.admin-table" )
            .shouldHave( text( SRC ), Duration.ofSeconds( 10 ) );

        // Click the row to select it and open the EdgeDetail panel.
        $$( "tr" ).findBy( text( SRC ) ).click();

        // The EdgeDetail panel renders the "Delete + Prevent" button once
        // node details have loaded (button is in the EdgeDetail component,
        // guarded by !loading).
        $$( "button" ).findBy( text( "Delete + Prevent" ) )
            .shouldBe( visible, Duration.ofSeconds( 10 ) )
            .click();

        // The ConfirmModal for delete-and-reject (confirmMode === 'reject') has
        // an extraField with label "Reason"; NodeAutocomplete lowercases the
        // aria-label, so the input is aria-label="reason".
        $( "input[aria-label=reason]" )
            .shouldBe( visible, Duration.ofSeconds( 5 ) )
            .setValue( "smoke test" );

        $$( "button" ).findBy( text( "Confirm" ) ).click();

        // Row should disappear from the table after the delete completes.
        $( "table.admin-table" )
            .shouldNotHave( text( SRC ), Duration.ofSeconds( 10 ) );
    }
}
