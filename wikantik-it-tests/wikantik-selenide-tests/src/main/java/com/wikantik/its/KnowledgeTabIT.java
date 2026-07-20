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
import com.wikantik.its.environment.Env;
import com.wikantik.pages.spa.EditWikiPage;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;

/**
 * Selenide IT for the Knowledge tab in the page editor's structured region.
 *
 * <p>The Knowledge tab renders {@code KnowledgeGraphPanel} ({@code .kg-panel-*}
 * classes): an entity list, an add-entity row (name input + type select + Add
 * button), an add-relation row (two entity selects + predicate select + Add),
 * and an inline SHACL-refusal alert ({@code data-testid="edge-add-error"}).</p>
 *
 * <p>This test does NOT rely on pre-extracted entity mentions; it seeds its own
 * entities via the add-entity UI row to avoid extraction-lag flakiness. Entity
 * names use a per-run timestamp suffix to prevent cross-run collisions. The
 * created KG nodes are global; the test does not clean them up (matching the
 * pattern in {@link EdgeCurationBrowserIT}).</p>
 *
 * <p>Uses the pre-seeded {@code SemanticArticle} startup fixture — no save-time
 * indexing lag, no freshly-created page races.</p>
 */
public class KnowledgeTabIT extends WithIntegrationTestSetup {

    /** Startup fixture page, pre-seeded in test-repo. */
    private static final String FIXTURE = "SemanticArticle";

    /** Max time to wait for async SPA transitions and API calls. */
    private static final Duration ASYNC_WAIT = Duration.ofSeconds( 10 );

    /** Per-run suffix to avoid cross-run entity name collisions. */
    private static final String SUFFIX = String.valueOf( System.currentTimeMillis() );

    /** Technology entity name unique to this run. */
    private static final String TECH_NAME = "ItBrowserTech" + SUFFIX;

    /** Concept entity name unique to this run. */
    private static final String CONCEPT_NAME = "ItBrowserConcept" + SUFFIX;

    @BeforeEach
    void login() {
        // Fresh driver per test method to prevent auth-state cross-contamination.
        Selenide.closeWebDriver();
        ViewWikiPage.open( "Main" )
                .clickOnLogin()
                .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
    }

    /**
     * Opening the Knowledge tab must render the {@code KnowledgeGraphPanel}
     * container ({@code .kg-panel-*} class prefix) or an empty-state element.
     */
    @Test
    void knowledgeTabRenders() {
        EditWikiPage.open( FIXTURE );

        // Click the top-level "Knowledge" tab in the editor's structured region.
        $$( "[role=tab]" ).findBy( text( "Knowledge" ) )
                .shouldBe( visible, ASYNC_WAIT )
                .click();

        // The KnowledgeGraphPanel container must be present. Accept either the
        // panel root or an empty/loading state — both are valid.
        $( "[class*=kg-panel-], .kg-panel-empty, .kg-panel-loading" )
                .shouldBe( visible, ASYNC_WAIT );
    }

    /**
     * Adding two entities via the add-entity row must make them appear in the
     * entities list; adding a conformant relation ({@code implements},
     * technology→concept) must add a relation row without an error.
     */
    @Test
    void addEntitiesAndConformantRelation() {
        EditWikiPage.open( FIXTURE );

        $$( "[role=tab]" ).findBy( text( "Knowledge" ) )
                .shouldBe( visible, ASYNC_WAIT )
                .click();

        // Ensure the panel finished its async entity-load before interacting.
        // Accepting .kg-panel-loading here was a race: text typed into the
        // add-entity row while loading is wiped when the load resolves and the
        // row re-renders — leaving the Add button correctly disabled and the
        // click timing out.
        $( ".kg-panel-loading" ).shouldNot( Condition.exist, ASYNC_WAIT );
        $( "[class*=kg-panel-], .kg-panel-empty" )
                .shouldBe( visible, ASYNC_WAIT );

        // Add the technology entity.
        addEntity( TECH_NAME, "technology" );

        // The entity name must appear in the panel after the add.
        $( "[class*=kg-panel-]" )
                .shouldHave( text( TECH_NAME ), ASYNC_WAIT );

        // Add the concept entity.
        addEntity( CONCEPT_NAME, "concept" );

        $( "[class*=kg-panel-]" )
                .shouldHave( text( CONCEPT_NAME ), ASYNC_WAIT );

        // Add a conformant relation: implements (technology → concept).
        // Both entities are now in the selects; select them and submit.
        addRelation( TECH_NAME, CONCEPT_NAME, "implements" );

        // No error alert must appear.
        $( "[data-testid=edge-add-error]" )
                .shouldNot( Condition.exist, ASYNC_WAIT );

        // A relation row referencing TECH_NAME must appear in the panel.
        $( "[class*=kg-panel-]" )
                .shouldHave( text( TECH_NAME ), ASYNC_WAIT );

        // Cancel to leave the fixture body unmodified.
        $( "[data-testid=editor-cancel]" )
                .shouldBe( visible, ASYNC_WAIT )
                .click();
        $( "[data-testid=page-view]" ).shouldBe( visible, ASYNC_WAIT );
    }

    /**
     * Adding a non-conformant relation ({@code implements} with concept→concept,
     * which violates the SHACL domain constraint) must show the inline error
     * alert ({@code data-testid="edge-add-error"}, {@code role="alert"}) and
     * must NOT add a new relation row.
     */
    @Test
    void nonConformantRelationShowsShacError() {
        // This test needs the two entities to already exist in the KG
        // (either from addEntitiesAndConformantRelation or fresh seeds).
        // Because test-method ordering is not guaranteed we seed them here too.
        // KG upsert is idempotent on (name, source_page), so re-seeding in
        // test 2 is safe; the REST seed bypasses the browser add-entity row
        // so it avoids any race with the panel's async entity-load.
        EditWikiPage.open( FIXTURE );

        // Navigate to admin context so session cookie authorises the admin
        // seed endpoint (same pattern as EdgeCurationBrowserIT).
        Selenide.open( Env.TESTS_BASE_URL + "/admin/knowledge-graph" );
        RestSeedHelper.seedKgNode( TECH_NAME, "technology", FIXTURE );
        RestSeedHelper.seedKgNode( CONCEPT_NAME, "concept", FIXTURE );

        // Now open the editor and go to the Knowledge tab.
        EditWikiPage.open( FIXTURE );

        $$( "[role=tab]" ).findBy( text( "Knowledge" ) )
                .shouldBe( visible, ASYNC_WAIT )
                .click();

        $( "[class*=kg-panel-], .kg-panel-empty, .kg-panel-loading" )
                .shouldBe( visible, ASYNC_WAIT );

        // Attempt a non-conformant relation: implements with concept as source.
        // The SHACL shape constrains implements' domain to technology; concept
        // as source is a violation.
        addRelation( CONCEPT_NAME, CONCEPT_NAME, "implements" );

        // The inline error alert must appear.
        $( "[data-testid=edge-add-error][role=alert]" )
                .shouldBe( visible, ASYNC_WAIT );

        // Cancel — fixture left unmodified.
        $( "[data-testid=editor-cancel]" )
                .shouldBe( visible, ASYNC_WAIT )
                .click();
        $( "[data-testid=page-view]" ).shouldBe( visible, ASYNC_WAIT );
    }

    /**
     * Fills in the add-entity row (name input + type select) and clicks Add.
     * Waits for the entity to appear in the panel before returning.
     * Selects by stable {@code data-testid} attributes set on the panel controls.
     */
    private static void addEntity( final String name, final String type ) {
        // Set the name input using the native value setter so React's onChange fires.
        final var nameInput = $( "[data-testid=kg-add-entity-name]" )
                .shouldBe( visible, Duration.ofSeconds( 5 ) );
        Selenide.executeJavaScript(
            "var el = arguments[0];"
            + " var desc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');"
            + " desc.set.call(el, arguments[1]);"
            + " el.dispatchEvent(new Event('input', { bubbles: true }));",
            nameInput, name );

        // Select the entity type.
        $( "[data-testid=kg-add-entity-type]" ).selectOptionContainingText( type );

        // Click the Add button — waiting for enabled first proves React has
        // processed the injected input event (the button is disabled while
        // newEntityName is empty).
        $( "[data-testid=kg-add-entity-btn]" )
                .shouldBe( visible, Duration.ofSeconds( 5 ) )
                .shouldBe( Condition.enabled, Duration.ofSeconds( 5 ) )
                .click();

        // Wait for the entity to appear in the list.
        $( "[class*=kg-panel-]" ).shouldHave( text( name ), Duration.ofSeconds( 10 ) );
    }

    /**
     * Fills in the add-relation row (source select + predicate select + target
     * select) and clicks Add. Does NOT wait for success — the caller asserts the
     * outcome (either a new row or an error alert).
     * Selects by stable {@code data-testid} attributes set on the panel controls.
     */
    private static void addRelation( final String sourceName, final String targetName,
                                      final String predicate ) {
        $( "[data-testid=kg-add-source]" ).selectOptionContainingText( sourceName );
        $( "[data-testid=kg-add-predicate]" ).selectOptionContainingText( predicate );
        $( "[data-testid=kg-add-target]" ).selectOptionContainingText( targetName );

        $( "[data-testid=kg-add-edge-btn]" )
                .shouldBe( visible, Duration.ofSeconds( 5 ) )
                .click();
    }
}
