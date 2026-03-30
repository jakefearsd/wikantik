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
package com.wikantik;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage tests for {@link WikiEngine} using {@link TestEngine#build()}.
 * Covers getStartTime, getAllInterWikiLinks, decodeName/encodeName round-trip,
 * getApplicationName, getFrontPage, and getTemplateDir.
 */
class WikiEngineCITest {

    private final TestEngine engine = TestEngine.build();

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    // -----------------------------------------------------------------------
    // getStartTime
    // -----------------------------------------------------------------------

    @Test
    void getStartTime_returnsNonNull() {
        final Date startTime = engine.getStartTime();
        assertNotNull( startTime, "start time must not be null" );
    }

    @Test
    void getStartTime_returnsClone() {
        final Date first = engine.getStartTime();
        final Date second = engine.getStartTime();

        assertEquals( first, second, "both calls return equal dates" );
        assertNotSame( first, second, "each call returns a fresh clone" );
    }

    // -----------------------------------------------------------------------
    // getAllInterWikiLinks
    // -----------------------------------------------------------------------

    @Test
    void getAllInterWikiLinks_returnsConfiguredLinks() {
        final Collection< String > links = engine.getAllInterWikiLinks();
        assertNotNull( links );
        // The default test properties include at least some interwiki refs
        // (e.g., Edit, Raw, etc. defined in wikantik.properties)
        assertFalse( links.isEmpty(), "should have at least one interwiki link from test properties" );
    }

    // -----------------------------------------------------------------------
    // decodeName / encodeName round-trip
    // -----------------------------------------------------------------------

    @Test
    void encodeDecode_roundTripPlainName() {
        final String name = "TestPage";
        assertEquals( name, engine.decodeName( engine.encodeName( name ) ) );
    }

    @Test
    void encodeDecode_roundTripWithSpaces() {
        final String name = "Test Page With Spaces";
        assertEquals( name, engine.decodeName( engine.encodeName( name ) ) );
    }

    @Test
    void encodeDecode_roundTripWithSpecialCharacters() {
        final String name = "Page&Name=Special?Chars";
        assertEquals( name, engine.decodeName( engine.encodeName( name ) ) );
    }

    @Test
    void encodeDecode_roundTripWithUnicode() {
        final String name = "\u00e5\u00e4\u00f6";
        assertEquals( name, engine.decodeName( engine.encodeName( name ) ) );
    }

    // -----------------------------------------------------------------------
    // getApplicationName
    // -----------------------------------------------------------------------

    @Test
    void getApplicationName_returnsConfiguredName() {
        final String appName = engine.getApplicationName();
        assertNotNull( appName );
        assertFalse( appName.isEmpty(), "application name must not be empty" );
    }

    // -----------------------------------------------------------------------
    // getFrontPage
    // -----------------------------------------------------------------------

    @Test
    void getFrontPage_returnsConfiguredFrontPage() {
        final String frontPage = engine.getFrontPage();
        assertNotNull( frontPage );
        assertFalse( frontPage.isEmpty(), "front page must not be empty" );
    }

    // -----------------------------------------------------------------------
    // getTemplateDir
    // -----------------------------------------------------------------------

    @Test
    void getTemplateDir_returnsConfiguredTemplateDir() {
        final String templateDir = engine.getTemplateDir();
        assertNotNull( templateDir );
        assertFalse( templateDir.isEmpty(), "template dir must not be empty" );
    }

}
