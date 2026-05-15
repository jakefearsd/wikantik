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
package com.wikantik.api.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MarkdownLinkScannerTest {

    @Test
    void findLocalLinksReturnsEmptyForNullOrBlank() {
        assertTrue( MarkdownLinkScanner.findLocalLinks( null ).isEmpty() );
        assertTrue( MarkdownLinkScanner.findLocalLinks( "" ).isEmpty() );
        assertTrue( MarkdownLinkScanner.findLocalLinks( "no links here" ).isEmpty() );
    }

    @Test
    void findLocalLinksExtractsStandardMarkdownTarget() {
        assertEquals( Set.of( "PageName" ),
                MarkdownLinkScanner.findLocalLinks( "see [the text](PageName)" ) );
    }

    @Test
    void findLocalLinksExtractsWikantikEmptyTargetConvention() {
        assertEquals( Set.of( "PageName" ),
                MarkdownLinkScanner.findLocalLinks( "see [PageName]()" ) );
    }

    @Test
    void findLocalLinksSkipsSingleCharAndPluginEmptyTargets() {
        // checkbox [x](), single char, and plugin/variable syntax are not page links
        assertTrue( MarkdownLinkScanner.findLocalLinks( "[x]() [ ]() [{Plugin}]() [{$var}]()" ).isEmpty() );
    }

    @Test
    void findLocalLinksExcludesExternalAndAnchorTargets() {
        final String body = "[a](https://example.com) [b](http://x) [c](ftp://f) "
                + "[d](mailto:x@y) [e](#section)";
        assertTrue( MarkdownLinkScanner.findLocalLinks( body ).isEmpty() );
    }

    @Test
    void findLocalLinksStripsAnchorSuffix() {
        assertEquals( Set.of( "Page" ),
                MarkdownLinkScanner.findLocalLinks( "[a](Page#section)" ) );
        assertEquals( Set.of( "Page" ),
                MarkdownLinkScanner.findLocalLinks( "[a](Page#)" ) );
    }

    @Test
    void findLocalLinksPreservesEncounterOrderAndDeduplicates() {
        final List< String > ordered = List.copyOf(
                MarkdownLinkScanner.findLocalLinks( "[t](Beta) [t](Alpha) [t](Beta)" ) );
        assertEquals( List.of( "Beta", "Alpha" ), ordered );
    }

    @Test
    void scanAllReturnsEmptyForNullOrBlank() {
        assertTrue( MarkdownLinkScanner.scanAll( null ).isEmpty() );
        assertTrue( MarkdownLinkScanner.scanAll( "" ).isEmpty() );
    }

    @Test
    void scanAllReportsTargetTextAndType() {
        final List< Map< String, String > > links =
                MarkdownLinkScanner.scanAll( "[label](Home) [ext](https://x)" );
        assertEquals( 2, links.size() );
        assertEquals( Map.of( "target", "Home", "text", "label", "type", "local" ), links.get( 0 ) );
        assertEquals( "external", links.get( 1 ).get( "type" ) );
    }

    @Test
    void classifyLinkRecognizesExternalSchemes() {
        assertEquals( "external", MarkdownLinkScanner.classifyLink( "http://x" ) );
        assertEquals( "external", MarkdownLinkScanner.classifyLink( "https://x" ) );
        assertEquals( "external", MarkdownLinkScanner.classifyLink( "ftp://x" ) );
        assertEquals( "external", MarkdownLinkScanner.classifyLink( "mailto:a@b.com" ) );
    }

    @Test
    void classifyLinkRecognizesAnchorAndLocal() {
        assertEquals( "anchor", MarkdownLinkScanner.classifyLink( "#fragment" ) );
        assertEquals( "local", MarkdownLinkScanner.classifyLink( "SomePage" ) );
    }
}
