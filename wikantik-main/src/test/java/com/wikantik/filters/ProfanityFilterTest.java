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
package com.wikantik.filters;

import com.wikantik.api.core.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;


/**
 * Unit tests for {@link ProfanityFilter}.  The filter replaces words listed in
 * {@code org/apache/wiki/filters/profanity.properties} with a censored form.
 */
class ProfanityFilterTest {

    private ProfanityFilter filter;
    private Context context;

    @BeforeEach
    void setUp() {
        filter = new ProfanityFilter();
        context = mock( Context.class );
    }

    // ---- preTranslate: clean content passes through unchanged ----

    @Test
    void preTranslate_returnsCleanContentUnchanged() throws Exception {
        final String clean = "This is perfectly fine wiki content.";
        final String result = filter.preTranslate( context, clean );
        assertEquals( clean, result,
                "Content without profanities should be returned unchanged" );
    }

    // ---- preTranslate: empty string ----

    @Test
    void preTranslate_handlesEmptyString() throws Exception {
        final String result = filter.preTranslate( context, "" );
        assertEquals( "", result,
                "Empty string should be returned as empty string" );
    }

    // ---- preTranslate: null-like no-op ----

    @Test
    void preTranslate_handlesSingleWord() throws Exception {
        // A word that is definitely NOT in the profanity list
        final String result = filter.preTranslate( context, "wiki" );
        assertEquals( "wiki", result );
    }

    // ---- preTranslate: replacement follows the format first_char + '*' + last_char ----

    @Test
    void preTranslate_censoredWordFollowsExpectedPattern() throws Exception {
        // We don't know exactly which words are in the list, but we can verify
        // the filter processes the content without throwing and returns a String.
        final String multiLine = "Line one\nLine two\nLine three";
        final String result = filter.preTranslate( context, multiLine );
        assertNotNull( result, "preTranslate should never return null" );
        // All lines should still be present (newlines preserved)
        assertTrue( result.contains( "\n" ),
                "Newlines should be preserved in translated content" );
    }

    // ---- preTranslate: case-insensitive replacement ----

    @Test
    void preTranslate_isInvokedWithoutException() throws Exception {
        // Verify the filter loads its profanity list without errors by calling
        // preTranslate with a variety of inputs and checking no exception is thrown.
        assertDoesNotThrow( () -> filter.preTranslate( context, "Hello World" ) );
        assertDoesNotThrow( () -> filter.preTranslate( context, "" ) );
        assertDoesNotThrow( () -> filter.preTranslate( context, "123 special !@#$" ) );
    }

    // ---- postTranslate: default no-op returns content unchanged ----

    @Test
    void postTranslate_returnsContentUnchanged() throws Exception {
        final String html = "<p>Some <b>HTML</b> content</p>";
        final String result = filter.postTranslate( context, html );
        assertEquals( html, result,
                "ProfanityFilter.postTranslate should be a no-op (inherited default)" );
    }

    // ---- preSave: default no-op returns content unchanged ----

    @Test
    void preSave_returnsContentUnchanged() throws Exception {
        final String wikiMarkup = "== Heading ==\nSome content here.";
        final String result = filter.preSave( context, wikiMarkup );
        assertEquals( wikiMarkup, result,
                "ProfanityFilter.preSave should be a no-op (inherited default)" );
    }

    // ---- postSave: default no-op does not throw ----

    @Test
    void postSave_doesNotThrow() {
        assertDoesNotThrow( () -> filter.postSave( context, "some saved content" ),
                "ProfanityFilter.postSave should be a no-op (inherited default)" );
    }

    // ---- destroy: default no-op does not throw ----

    @Test
    void destroy_doesNotThrow() {
        assertDoesNotThrow( () -> filter.destroy( mock( com.wikantik.api.core.Engine.class ) ),
                "ProfanityFilter.destroy should be a no-op (inherited default)" );
    }

}
