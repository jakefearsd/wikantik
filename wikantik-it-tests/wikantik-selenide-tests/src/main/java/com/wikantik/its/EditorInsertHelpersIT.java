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
import com.wikantik.pages.spa.EditWikiPage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;

import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UI test for the editor insert helpers — the Table and Code-block toolbar
 * buttons added alongside the {@code [[} wikilink autocomplete.
 *
 * <p>Opening a fresh page editor and clicking each button must inject the
 * corresponding markdown skeleton into the editor source: a GFM table and a
 * fenced code block. Runs in every {@link EditorMode}; assertions read the
 * CodeMirror document (which updates synchronously on insert) rather than the
 * screen — in live mode the inserted table may be drawn as a widget — or the
 * debounced preview.</p>
 */
public class EditorInsertHelpersIT extends WithIntegrationTestSetup {

    @ParameterizedTest
    @EnumSource( EditorMode.class )
    void tableButtonInsertsAGfmTable( final EditorMode mode ) {
        EditWikiPage.openInMode( "EditorTable" + mode.name() + System.currentTimeMillis(), mode );

        // Wait for the new-page boilerplate to settle before interacting, then
        // focus the document and insert a table via the toolbar.
        focusReadyEditor();
        $( "[aria-label='Table']" ).click();

        // The table skeleton lands in the editor document.
        final String doc = EditWikiPage.awaitDocumentContaining( "Header 1", Duration.ofSeconds( 5 ) );
        assertTrue( doc.contains( "Cell 1" ), "the table skeleton must carry its body cells; document:\n" + doc );
    }

    @ParameterizedTest
    @EnumSource( EditorMode.class )
    void codeBlockButtonInsertsAFencedBlock( final EditorMode mode ) {
        EditWikiPage.openInMode( "EditorCodeBlock" + mode.name() + System.currentTimeMillis(), mode );

        focusReadyEditor();
        $( "[aria-label='Code block']" ).click();

        // The fenced block's language placeholder appears in the editor document.
        EditWikiPage.awaitDocumentContaining( "language", Duration.ofSeconds( 5 ) );
    }

    /**
     * Waits for the editor's new-page boilerplate to load (so a later
     * async content reset cannot clobber the inserted skeleton), then clicks
     * into the CodeMirror surface to focus it.
     */
    private static void focusReadyEditor() {
        EditWikiPage.awaitDocumentContaining( EditWikiPage.NEW_PAGE_BOILERPLATE, Duration.ofSeconds( 10 ) );
        $( "[data-testid=editor-textarea] .cm-content" )
            .shouldBe( Condition.visible, Duration.ofSeconds( 10 ) )
            .click();
    }
}
