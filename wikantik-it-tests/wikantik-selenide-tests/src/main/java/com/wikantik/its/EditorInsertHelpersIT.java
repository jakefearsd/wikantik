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
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.codeborne.selenide.Selenide.$;

/**
 * UI test for the editor insert helpers — the Table and Code-block toolbar
 * buttons added alongside the {@code [[} wikilink autocomplete.
 *
 * <p>Opening a fresh page editor and clicking each button must inject the
 * corresponding markdown skeleton into the editor source: a GFM table and a
 * fenced code block. Assertions read the CodeMirror source (which updates
 * synchronously on insert) rather than the debounced live preview.</p>
 */
public class EditorInsertHelpersIT extends WithIntegrationTestSetup {

    @Test
    void tableButtonInsertsAGfmTable() {
        EditWikiPage.open( "EditorTable" + System.currentTimeMillis() );

        // Wait for the new-page boilerplate to settle before interacting, then
        // focus the document and insert a table via the toolbar.
        focusReadyEditor();
        $( "[aria-label='Table']" ).click();

        // The table skeleton lands in the editor source.
        $( "[data-testid=editor-textarea] .cm-content" )
            .shouldHave( Condition.text( "Header 1" ), Duration.ofSeconds( 5 ) )
            .shouldHave( Condition.text( "Cell 1" ) );
    }

    @Test
    void codeBlockButtonInsertsAFencedBlock() {
        EditWikiPage.open( "EditorCodeBlock" + System.currentTimeMillis() );

        focusReadyEditor();
        $( "[aria-label='Code block']" ).click();

        // The fenced block's language placeholder appears in the editor source.
        $( "[data-testid=editor-textarea] .cm-content" )
            .shouldHave( Condition.text( "language" ), Duration.ofSeconds( 5 ) );
    }

    /**
     * Waits for the editor's new-page boilerplate to load (so a later
     * async content reset cannot clobber the inserted skeleton), then clicks
     * into the CodeMirror surface to focus it.
     */
    private static void focusReadyEditor() {
        $( "[data-testid=editor-textarea] .cm-content" )
            .shouldBe( Condition.visible, Duration.ofSeconds( 10 ) )
            .shouldHave( Condition.text( "Write your content here" ), Duration.ofSeconds( 10 ) )
            .click();
    }
}
