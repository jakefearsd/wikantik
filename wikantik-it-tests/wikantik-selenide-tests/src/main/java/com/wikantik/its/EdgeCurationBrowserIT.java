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
 * Seeds two nodes + an edge via the admin REST API (driven through the
 * browser's session cookie), then exercises the UI:
 * <ol>
 *   <li>Edge Explorer tab shows the seeded edge.</li>
 *   <li>Selecting the row reveals Edit / Delete / Delete + Prevent buttons.</li>
 *   <li>Delete + Prevent + reason removes the row.</li>
 * </ol>
 *
 * <p>The Create-via-modal path is exercised by {@code EdgeFormModal.test.jsx}
 * (Vitest, all React state under our control). Driving React-controlled
 * autocomplete inputs from Selenide is fragile and adds little signal beyond
 * what the Vitest covers; the IT focuses on the integration boundaries that
 * unit tests can't see — auth, real network, real database, real DOM rendering
 * of the list and detail panel.</p>
 *
 * <p>Tab label is "Edge Explorer" (AdminKnowledgePage TABS array uses that
 * label, not the bare word "Edges"). The ConfirmModal for delete-and-reject
 * has an extra field whose {@code aria-label} is {@code "reason"} (lowercased
 * from the label "Reason").</p>
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
    void edgeExplorerDeleteAndPreventFlow() {
        // Open the admin knowledge-graph page first so seedKgNode's
        // executeAsyncJavaScript runs in the SPA context where the session
        // cookie is attached (AdminAuthFilter accepts browser-session cookies).
        Selenide.open( Page.baseUrl() + "/admin/knowledge-graph" );

        // source_page must be a non-null, non-system page name. KgInclusionFilter
        // hides nodes whose source_page is in kg_excluded_pages (system/hub pages
        // by default), and the admin endpoint rejects JsonNull on .getAsString().
        final String srcJson = RestSeedHelper.seedKgNode( SRC, "concept", SRC + "Page" );
        final String tgtJson = RestSeedHelper.seedKgNode( TGT, "concept", TGT + "Page" );
        final String srcId = parseUuidField( srcJson, "id" );
        final String tgtId = parseUuidField( tgtJson, "id" );

        // Seed the edge directly through the admin REST API rather than driving
        // the autocomplete-driven EdgeFormModal: typing into React-controlled
        // inputs from Selenide is racy and the unit tests in EdgeFormModal.test.jsx
        // already cover that surface.
        seedKgEdge( srcId, tgtId, "related_to" );

        // Switch to the Edge Explorer tab.
        $$( "button" ).findBy( text( "Edge Explorer" ) )
            .shouldBe( visible, Duration.ofSeconds( 10 ) )
            .click();

        // The seeded edge row should appear in the table.
        $( "table.admin-table" )
            .shouldHave( text( SRC ), Duration.ofSeconds( 10 ) );

        // Click the source-name link to select the row and open the EdgeDetail
        // panel. AdminTable suppresses <tr>-level onRowClick when selectable=true
        // so the row's onClick is a no-op; the column-cell renders a btn-link
        // button explicitly for this navigation.
        $$( "button.btn-link" ).findBy( text( SRC ) )
            .shouldBe( visible, Duration.ofSeconds( 5 ) )
            .click();

        // The EdgeDetail panel renders the "Delete + Prevent" button once
        // node details have loaded (button is guarded by !loading).
        $$( "button" ).findBy( text( "Delete + Prevent" ) )
            .shouldBe( visible, Duration.ofSeconds( 10 ) )
            .click();

        // The ConfirmModal for delete-and-reject has an extra reason field;
        // the JSX lowercases the aria-label so it is "reason", not "Reason".
        $( "input[aria-label=reason]" )
            .shouldBe( visible, Duration.ofSeconds( 5 ) )
            .setValue( "smoke test" );

        // Scope the Confirm click to the modal. The detail pane carries its
        // own "Confirm" button (one-click elevate-to-human-curated) which is
        // btn-primary btn-sm, not btn-danger — so ".modal-overlay button.btn-danger"
        // uniquely targets the modal's red Confirm.
        //
        // Headless Chrome's default viewport is short enough that the modal's
        // action row can sit below the fold; without explicit scrollIntoView,
        // WebDriver reports the button as not displayed and Selenide's text
        // filter also returns "" so findBy(text("Confirm")) misses it. Scroll
        // and then click.
        $( ".modal-overlay button.btn-danger" )
            .scrollIntoView( true )
            .shouldBe( visible, Duration.ofSeconds( 5 ) )
            .click();

        // Row should disappear from the table after the delete completes.
        $( "table.admin-table" )
            .shouldNotHave( text( SRC ), Duration.ofSeconds( 10 ) );
    }

    /**
     * Seeds a kg_edge through the admin endpoint, driven from the browser so the
     * UI session cookie authorises {@code AdminAuthFilter}. Mirrors the pattern
     * already used by {@link RestSeedHelper#seedKgNode}.
     */
    private static void seedKgEdge( final String sourceId, final String targetId,
                                    final String relationshipType ) {
        final String script = """
            const cb = arguments[arguments.length - 1];
            const base = window.__WIKANTIK_BASE__ || '';
            const body = JSON.stringify({
                source_id: arguments[0],
                target_id: arguments[1],
                relationship_type: arguments[2]
            });
            fetch(base + '/admin/knowledge-graph/edges', {
                method: 'POST',
                headers: { 'Accept': 'application/json', 'Content-Type': 'application/json' },
                credentials: 'same-origin',
                body
            })
            .then(r => r.text().then(b => ({ status: r.status, body: b })))
            .then(res => cb(res))
            .catch(err => cb({ status: -1, body: String(err) }));
            """;
        final Object result = Selenide.executeAsyncJavaScript( script,
            sourceId, targetId, relationshipType );
        if ( result instanceof java.util.Map< ?, ? > m ) {
            final Object status = m.get( "status" );
            if ( status instanceof Number n && n.intValue() >= 200 && n.intValue() < 300 ) {
                return;
            }
            throw new IllegalStateException( "seedKgEdge failed: "
                + status + " " + m.get( "body" ) );
        }
        throw new IllegalStateException( "seedKgEdge: unexpected result "
            + ( result == null ? "null" : result.getClass().getName() ) );
    }

    /**
     * Tiny inline JSON-string parser: extracts a UUID-shaped field value without
     * pulling in a JSON library. Acceptable here because the response shape is
     * fixed (Gson serialises {@code "id":"<uuid>"} verbatim).
     */
    private static String parseUuidField( final String json, final String fieldName ) {
        // Gson default formatting emits {@code "id": "<uuid>"} with a space after
        // the colon. Match either with-space or no-space (compact) forms.
        final java.util.regex.Matcher m = java.util.regex.Pattern
            .compile( "\"" + java.util.regex.Pattern.quote( fieldName ) + "\"\\s*:\\s*\"([^\"]+)\"" )
            .matcher( json );
        if ( !m.find() ) {
            throw new IllegalStateException( "field " + fieldName + " not found in: " + json );
        }
        return m.group( 1 );
    }
}
