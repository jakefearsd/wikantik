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
     * Setting the Cluster field to an invalid slug must produce an inline
     * validation error ({@code .fm-violation-error}) with a suggestion button
     * (text starting with {@code Use "}). Clicking the suggestion corrects the
     * field; we then Cancel to leave the fixture unmodified.
     */
    @Test
    void clusterViolationApplySuggestionAndCancel() {
        EditWikiPage.open( FIXTURE );

        // Make sure the Form sub-tab is active (it is by default; this click
        // is a defensive no-op that avoids flakiness if another test left a
        // different sub-tab active — each test opens a fresh driver so this
        // is belt-and-suspenders against future test ordering changes).
        $$( "[role=tab]" ).findBy( text( "Form" ) )
                .shouldBe( visible, ASYNC_WAIT )
                .click();

        // Locate the Cluster input and set an invalid slug (spaces + caps
        // violate the slug pattern the editor validates). ReactInputs is
        // package-private in pages.spa, so we drive the React synthetic event
        // via inline JS — the same technique used by LoginPage and other helpers.
        final SelenideElement clusterField = $( "[aria-label=Cluster]" )
                .shouldBe( visible, ASYNC_WAIT );
        setReactInputValue( clusterField, "Bad Slug" );

        // Trigger save — the editor should stay on the page and show the
        // inline violation rather than navigating to /wiki/.
        $( "[data-testid=editor-save]" )
                .shouldBe( visible, ASYNC_WAIT )
                .click();

        // A .fm-violation-error element must appear under the Cluster field.
        $( ".fm-violation-error" )
                .shouldBe( visible, ASYNC_WAIT );

        // An apply-suggestion button must be present (text matches "Use \"bad-slug\"").
        final var suggestion = $$( "button" ).findBy( Condition.matchText( "Use\\s+\"bad-slug\"" ) );
        suggestion.shouldBe( visible, ASYNC_WAIT );
        suggestion.click();

        // After clicking the suggestion the Cluster field must show the
        // corrected value.
        $( "[aria-label=Cluster]" )
                .shouldHave( Condition.or( "corrected cluster value",
                        value( "bad-slug" ),
                        text( "bad-slug" ) ), ASYNC_WAIT );

        // Cancel so the fixture is left unmodified for other ITs.
        $( "[data-testid=editor-cancel]" )
                .shouldBe( visible, ASYNC_WAIT )
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

    /**
     * Sets a value on a React-controlled {@code <input>} element by reaching
     * into the native value setter to invalidate React's internal tracker, then
     * dispatching a bubbling {@code input} event so React's {@code onChange}
     * fires. This mirrors the private {@code ReactInputs.setInputValue} helper
     * in the {@code pages.spa} package (which is package-private and not
     * accessible from here).
     */
    private static void setReactInputValue( final SelenideElement element, final String value ) {
        Selenide.executeJavaScript(
            "var el = arguments[0];"
            + " var desc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');"
            + " desc.set.call(el, arguments[1]);"
            + " el.dispatchEvent(new Event('input', { bubbles: true }));",
            element, value );
    }
}

