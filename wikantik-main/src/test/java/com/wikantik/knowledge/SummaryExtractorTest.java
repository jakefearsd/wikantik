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
package com.wikantik.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SummaryExtractorTest {

    @Test
    void selectsFirstSuitableSentence() {
        // heading line, short fragment, then a good sentence — should pick the good one
        final String body = "# Introduction\nHi.\nThis is a suitable sentence that is long enough to be selected as a summary.";
        final String result = SummaryExtractor.extract( body );
        assertEquals( "This is a suitable sentence that is long enough to be selected as a summary.", result );
    }

    @Test
    void truncatesLongSentenceWhenNoSuitableExists() {
        // Single sentence longer than 200 chars — must be truncated
        final String longSentence = "A".repeat( 10 ) + " " + "B".repeat( 240 ) + ".";
        final String result = SummaryExtractor.extract( longSentence );
        assertTrue( result.endsWith( "..." ) );
        assertTrue( result.length() <= 203 ); // 200 chars + "..."
    }

    @Test
    void stripsMarkdownBeforeExtracting() {
        final String body = "See [the documentation](https://example.com) for more details about this feature.";
        final String result = SummaryExtractor.extract( body );
        assertTrue( result.contains( "the documentation" ) );
        assertFalse( result.contains( "https://example.com" ) );
    }

    @Test
    void returnsEmptyForBlankContent() {
        assertEquals( "", SummaryExtractor.extract( "" ) );
        assertEquals( "", SummaryExtractor.extract( null ) );
    }

    @Test
    void skipsTooShortSentences() {
        final String body = "Hi. Ok. This sentence is long enough to qualify as a suitable summary for the page.";
        final String result = SummaryExtractor.extract( body );
        assertEquals( "This sentence is long enough to qualify as a suitable summary for the page.", result );
    }

    @Test
    void stripsFrontmatter() {
        final String body = "---\ntitle: Test\n---\nThis page describes the configuration of the system in detail.";
        final String result = SummaryExtractor.extract( body );
        assertFalse( result.contains( "title:" ) );
        assertTrue( result.contains( "configuration" ) );
    }

    @Test
    void stripsPluginCalls() {
        final String body = "[{TableOfContents}]()\nThis page explains how the plugin system works in detail.";
        final String result = SummaryExtractor.extract( body );
        assertFalse( result.contains( "TableOfContents" ) );
        assertTrue( result.contains( "plugin system" ) );
    }
}
