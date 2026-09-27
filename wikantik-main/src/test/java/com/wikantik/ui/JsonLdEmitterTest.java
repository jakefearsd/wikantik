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
package com.wikantik.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted tests for {@link JsonLdEmitter} branches with no prior coverage:
 * multi-entry {@code relatedLink} comma separation, a null-cluster breadcrumb,
 * and the {@code jsonStr} escape table (backslash/quote/control chars).
 */
class JsonLdEmitterTest {

    private static final String BASE_URL = "http://example.com";
    private static final String APP_NAME = "Wikantik";

    // ---- appendRelatedLinks: comma separates 2+ entries ----

    @Test
    void multipleRelatedLinksAreCommaSeparated() {
        final String body = "---\ntype: article\nrelated: [PageA, PageB]\n---\n# P\n\nBody.\n";
        final PageSeoModel model = PageSeoModel.from( "P", body, BASE_URL, APP_NAME, null );
        final String json = JsonLdEmitter.buildMainJsonLd( model );
        // jsonStr also escapes '/' as '\/', so the expected fragment mirrors that.
        assertTrue( json.contains( "\"relatedLink\":[\"http:\\/\\/example.com\\/wiki\\/PageA\",\"http:\\/\\/example.com\\/wiki\\/PageB\"]" ),
                "expected comma-separated relatedLink entries; got: " + json );
    }

    // ---- jsonStr: null input renders as an empty JSON string ----

    @Test
    void breadcrumbWithNullClusterRendersEmptyStringForThatField() {
        final String json = JsonLdEmitter.buildBreadcrumbJsonLd( "P", BASE_URL, BASE_URL + "/wiki/P", null );
        // jsonStr also escapes '/' as '\/', so the expected fragment mirrors that.
        assertTrue( json.contains( "\"name\":\"\",\"item\":\"http:\\/\\/example.com\\/wiki\\/null\"" ),
                "a null cluster should render as an empty JSON string; got: " + json );
    }

    // ---- jsonStr: escape table for backslash, quote, newline, CR, tab, and other control chars ----

    @Test
    void jsonStrEscapesBackslashQuoteAndWhitespaceControlChars() {
        final String weirdName = "Weird\\Name\"With\nNewline\rCR\tTab";
        final String json = JsonLdEmitter.buildBreadcrumbJsonLd( weirdName, BASE_URL, BASE_URL + "/wiki/x", "cluster" );
        assertTrue( json.contains( "Weird\\\\Name\\\"With\\nNewline\\rCR\\tTab" ),
                "expected escaped backslash/quote/newline/CR/tab; got: " + json );
    }

    @Test
    void jsonStrEscapesOtherControlCharsAsUnicodeSequence() {
        // \u0001 (SOH) is a control char with no dedicated escape — falls to the \\uXXXX branch.
        final String weirdName = "Ctrl\u0001Char";
        final String json = JsonLdEmitter.buildBreadcrumbJsonLd( weirdName, BASE_URL, BASE_URL + "/wiki/x", "cluster" );
        assertTrue( json.contains( "Ctrl\\u0001Char" ), "expected \\u0001 escape; got: " + json );
    }
}
