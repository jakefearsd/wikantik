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
package com.wikantik.pages.haddock;

import com.codeborne.selenide.Condition;
import com.codeborne.selenide.SelenideElement;
import com.codeborne.selenide.WebDriverRunner;
import com.wikantik.pages.Page;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

import static com.codeborne.selenide.Selenide.$;

/**
 * Page object for the React SPA page editor (the {@code PageEditor} component).
 *
 * <p>The editor route is {@code /edit/<pageName>}, served by the SPA routing
 * filter. The component renders a two-pane layout with a textarea
 * ({@code [data-testid=editor-textarea]}) and a markdown preview. Save/Cancel
 * buttons are located in the toolbar.
 */
public class EditWikiPage implements HaddockPage {

    /**
     * Open a given page for edition.
     *
     * @param pageName Wiki page name to edit (new or existing).
     * @return {@link EditWikiPage} instance, to allow chaining of actions.
     */
    public static EditWikiPage open( final String pageName ) {
        final EditWikiPage page = Page.withUrl( Page.baseUrl() + "/edit/" + pageName ).openAs( new EditWikiPage() );
        $( "[data-testid=page-editor]" ).shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );
        $( "[data-testid=editor-textarea]" ).shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );
        return page;
    }

    /**
     * Press the cancel button and discard the edit.
     *
     * @return {@link ViewWikiPage} instance, to allow chaining of actions.
     */
    public ViewWikiPage cancel() {
        $( "[data-testid=editor-cancel]" ).click();
        $( "[data-testid=page-view]" ).shouldBe( Condition.visible, DEFAULT_WAIT );
        return new ViewWikiPage();
    }

    /**
     * Replaces the page contents with the given text and saves.
     *
     * @param text text to save.
     * @return {@link ViewWikiPage} instance, to allow chaining of actions.
     */
    public ViewWikiPage saveText( final String text ) {
        return saveText( text, text );
    }

    /**
     * Replaces the page contents with the given text and saves, verifying
     * the live preview shows the expected substring before submitting.
     *
     * @param text full text to save (may include wiki directives).
     * @param preview substring expected to appear in the preview pane after
     *                rendering (directives like {@code [{ALLOW …}]} will not
     *                appear in the preview).
     * @return {@link ViewWikiPage} instance, to allow chaining of actions.
     */
    public ViewWikiPage saveText( final String text, final String preview ) {
        // The textarea is a React controlled input (value={state} +
        // onChange={setState}). Selenide's .val() / .clear() do not reliably
        // trigger React's onChange, so the component's state would remain at
        // whatever was loaded from the server and the Save button would post
        // the old content. Use the native-setter helper to force React to
        // pick up the new value via its synthetic event pipeline.
        final SelenideElement textarea = $( "[data-testid=editor-textarea]" )
            .shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );
        ReactInputs.setTextareaValue( textarea, text );

        // The preview pane is a live-updated ReactMarkdown render; give it
        // a moment to reflect the new content before clicking Save.
        $( ".editor-preview .article-prose" )
            .shouldBe( Condition.text( preview ), Duration.ofSeconds( 5 ) );

        $( "[data-testid=editor-save]" ).click();

        // Wait for the router to navigate away from /edit/ and the viewer to
        // mount. Use both URL and testid checks: some tests save under the
        // same page name where the new view's testid flips on but URL also
        // changes back to /wiki/.
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 10 ) )
            .until( driver -> !driver.getCurrentUrl().contains( "/edit/" ) );

        $( "[data-testid=page-view]" ).shouldBe( Condition.visible, DEFAULT_WAIT );
        return new ViewWikiPage();
    }

}
