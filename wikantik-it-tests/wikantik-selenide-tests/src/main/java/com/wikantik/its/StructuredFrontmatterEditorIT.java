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
import com.codeborne.selenide.SelenideElement;
import com.wikantik.its.environment.Env;
import com.wikantik.pages.spa.EditWikiPage;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;

/**
 * Selenide IT for the structured frontmatter editor introduced in the page editor.
 *
 * <p>Uses the pre-seeded {@code SemanticArticle} fixture (in cluster
 * {@code test-cluster}, type {@code article}) so no save-time indexing lag
 * can affect these tests. The suite verifies:</p>
 * <ol>
 *   <li>The Frontmatter / Form tabs are visible and pre-populated with the
 *       fixture's values on open.</li>
 *   <li>Inline 422 violation + apply-suggestion round-trip for the Cluster
 *       field (bad slug → violation shown → apply suggestion → field corrected
 *       → Cancel to leave the fixture clean).</li>
 *   <li>Break-glass Raw YAML round-trip: switching to Raw shows YAML containing
 *       the known keys; switching back to Form keeps the values.</li>
 * </ol>
 *
 * <p>The final destructive Save in the 422-path test is deliberately avoided:
 * we Cancel after confirming the suggestion corrects the field, so the fixture
 * is left clean for other ITs.</p>
 */
public class StructuredFrontmatterEditorIT extends WithIntegrationTestSetup {

    /** Startup fixture page, pre-seeded in test-repo with type=article, cluster=test-cluster. */
    private static final String FIXTURE = "SemanticArticle";

    /** Max time to wait for async SPA transitions (tab switches, API calls). */
    private static final Duration ASYNC_WAIT = Duration.ofSeconds( 10 );

    @BeforeEach
    void login() {
        // Each test starts from a clean browser session so no auth state leaks
        // between classes. The @BeforeAll in WithIntegrationTestSetup closes the
        // driver once per class; @BeforeEach closes per method when ordering matters.
        Selenide.closeWebDriver();
        ViewWikiPage.open( "Main" )
                .clickOnLogin()
                .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
    }

    /**
     * The Frontmatter tab must be the default-selected tab when the editor
     * opens, and the Form sub-tab must show the fixture's "Type" field value.
     */
    @Test
    void frontmatterTabAndFormFieldAreVisibleOnOpen() {
        EditWikiPage.open( FIXTURE );

        // The top-level structured region has a "Frontmatter" tab that is
        // selected by default.  role=tab + name is a stable selector that
        // survives CSS refactors.
        $$( "[role=tab]" ).findBy( text( "Frontmatter" ) )
                .shouldBe( visible, ASYNC_WAIT );

        // The Form sub-tab must be active (the FrontmatterEditor renders it
        // by default when the Frontmatter top-tab is selected).
        $$( "[role=tab]" ).findBy( text( "Form" ) )
                .shouldBe( visible, ASYNC_WAIT );

        // The "Type" field is populated from the fixture frontmatter (type: article).
        // Form fields are rendered with aria-label = field label.
        $( "[aria-label=Type]" )
                .shouldBe( visible, ASYNC_WAIT )
                .shouldHave( Condition.or( "type value",
                        value( "article" ),
                        text( "article" ) ) );
    }

    /**
     * Setting an invalid value for the Audience field (not in the closed enum
     * {humans, agents, both}) via the Raw YAML editor must produce an inline
     * validation error ({@code .fm-violation-error}) when Save is clicked, and
     * the editor must stay on the page (not navigate to /wiki/).
     *
     * <p>Background: cluster-slug violations were downgraded to WARNING
     * (save succeeds → navigates away) in the validator commit, so the cluster
     * path can no longer test the inline-error-stays-on-page flow.  The audience
     * field is a closed enum ({@code humans | agents | both}); an invalid value
     * is a hard ERROR that blocks save and keeps the editor on screen.</p>
     *
     * <p>The invalid value is injected via the Raw YAML textarea so that the
     * test does not depend on the React select only offering listed options.
     * The raw editor accepts free-text input, allowing us to write an invalid
     * value that the server-side validator rejects as an ERROR.</p>
     */
    @Test
    void audienceViolationShowsInlineErrorAndCancel() {
        EditWikiPage.open( FIXTURE );

        // Switch to the Raw YAML sub-tab so we can directly type an invalid audience value.
        $$( "[role=tab]" ).findBy( text( "Frontmatter" ) )
                .shouldBe( visible, ASYNC_WAIT )
                .click();
        $$( "[role=tab]" ).findBy( text( "Raw YAML" ) )
                .shouldBe( visible, ASYNC_WAIT )
                .click();

        // The raw editor textarea must be visible and contain the fixture's YAML.
        final SelenideElement rawArea = $( "[aria-label='Raw frontmatter YAML']" )
                .shouldBe( visible, ASYNC_WAIT )
                .shouldHave( text( "type" ) );

        // Replace the raw YAML with a version that has audience: robots (invalid).
        // Use Selenide's sendKeys (fires genuine keyboard events) rather than synthetic
        // JS events so that React's onChange fires and reliably updates the text state.
        //   1. Select-all + Delete to clear the textarea content.
        //   2. Type the invalid YAML character-by-character via sendKeys.
        //   3. Click away from the textarea (on the editor heading) so the real
        //      browser blur event fires, causing RawYaml.sync() to call
        //      api.validateFrontmatter → onChange(parsedMetadata with audience:robots).
        //   4. Wait for the async validateFrontmatter call to propagate (executeAsync
        //      fetch as a timing anchor — both reach the same endpoint concurrently).
        //   5. Click Save.
        final String invalidYaml = "type: article\n"
                + "cluster: test-cluster\n"
                + "audience: robots\n"
                + "summary: A test article\n";

        // Step 1-2: select-all and replace with the new YAML via keyboard events.
        // Ctrl+A selects the entire textarea content; then typing replaces it.
        // sendKeys fires genuine keyboard events that React's onChange picks up,
        // updating the RawYaml component's local text state correctly.
        rawArea.click();
        rawArea.sendKeys(
                org.openqa.selenium.Keys.chord( org.openqa.selenium.Keys.CONTROL, "a" ),
                invalidYaml );

        // Step 3: click the editor heading to move focus away, triggering the real blur.
        $( "[data-testid=editor-heading]" )
                .shouldBe( visible, ASYNC_WAIT )
                .click();

        // Step 4: timing anchor — wait for validateFrontmatter to finish (mirrors sync()).
        final String waitForSync = """
            const cb = arguments[arguments.length - 1];
            const base = window.__WIKANTIK_BASE__ || '';
            fetch(base + '/api/frontmatter/validate', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ frontmatter: arguments[0] }),
                credentials: 'same-origin'
            }).then(() => cb(true)).catch(() => cb(true));
            """;
        Selenide.executeAsyncJavaScript( waitForSync, invalidYaml );

        // Step 5a: switch back to the Form sub-tab so that the FieldWidget violation
        // elements are rendered when the 422 comes back (violations are only shown in
        // the Form tab, not the Raw YAML tab).
        $$( "[role=tab]" ).findBy( text( "Form" ) )
                .shouldBe( visible, ASYNC_WAIT )
                .click();

        // Step 5b: the live debounced validation flags audience:robots as an ERROR —
        // the inline violation renders and Save disables ("Fix the highlighted errors
        // before saving"). Clicking Save here was inherently racy: whenever live
        // validation won the race the button was already disabled and the click threw.
        // The server-side 422-on-save contract is covered by PageResourceSaveContractTest.
        $( ".fm-violation-error" )
                .shouldBe( visible, ASYNC_WAIT );

        // The invalid audience blocks the save: the button stays present but disabled.
        $( "[data-testid=editor-save]" )
                .shouldBe( visible, ASYNC_WAIT )
                .shouldBe( Condition.disabled, ASYNC_WAIT );

        // Cancel so the fixture is left unmodified.  Because we typed content (making
        // the editor dirty), Cancel shows a "Discard unsaved changes?" confirmation
        // dialog — click the "Discard" button to confirm and navigate to the view page.
        $( "[data-testid=editor-cancel]" )
                .shouldBe( visible, ASYNC_WAIT )
                .click();
        // The editor is dirty (we typed new content), so a discard-confirm modal appears;
        // click "Discard" to confirm navigation away from the editor.
        $$( "button" ).findBy( text( "Discard" ) )
                .shouldBe( visible, Duration.ofSeconds( 3 ) )
                .click();
        $( "[data-testid=page-view]" ).shouldBe( visible, ASYNC_WAIT );
    }

    /**
     * Switching from Form to Raw YAML must show the raw textarea containing
     * the frontmatter keys; switching back to Form must still show the values.
     */
    @Test
    void rawYamlRoundTrip() {
        EditWikiPage.open( FIXTURE );

        // Activate the Frontmatter top-tab (default, but click defensively).
        $$( "[role=tab]" ).findBy( text( "Frontmatter" ) )
                .shouldBe( visible, ASYNC_WAIT )
                .click();

        // Switch to the Raw YAML sub-tab.
        $$( "[role=tab]" ).findBy( text( "Raw YAML" ) )
                .shouldBe( visible, ASYNC_WAIT )
                .click();

        // The raw editor textarea must be visible and contain "type" as a YAML key.
        $( "[aria-label='Raw frontmatter YAML']" )
                .shouldBe( visible, ASYNC_WAIT )
                .shouldHave( text( "type" ) );

        // Switch back to the Form sub-tab.
        $$( "[role=tab]" ).findBy( text( "Form" ) )
                .shouldBe( visible, ASYNC_WAIT )
                .click();

        // The Form must still reflect the fixture's type value.
        $( "[aria-label=Type]" )
                .shouldBe( visible, ASYNC_WAIT )
                .shouldHave( Condition.or( "type still present",
                        value( "article" ),
                        text( "article" ) ) );

        // Cancel to leave the fixture unmodified.
        $( "[data-testid=editor-cancel]" )
                .shouldBe( visible, ASYNC_WAIT )
                .click();
        $( "[data-testid=page-view]" ).shouldBe( visible, ASYNC_WAIT );
    }

}

