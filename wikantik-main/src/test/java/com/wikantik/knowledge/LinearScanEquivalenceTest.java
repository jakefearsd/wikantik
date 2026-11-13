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

import com.wikantik.citation.CitationMarkupParser;
import com.wikantik.citation.ParsedCitation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hand-written linear scans must find exactly what the regexes they replaced found. Each regex is kept here,
 * verbatim, as the oracle and compared against the scan over random text built from the characters that matter.
 */
class LinearScanEquivalenceTest {

    private static final Pattern CITE = Pattern.compile(
            "\\[([^\\]]*)]\\(\\s*cite://([^\\s)\"]+)\\s*(?:\"([^\"]*)\")?\\s*\\)" );
    private static final Pattern PLUGIN_CALL = Pattern.compile( "\\[\\{[^}]*\\}\\](?:\\([^)]*\\))?" );
    private static final Pattern HTML_TAG = Pattern.compile( "<[^>]+>" );
    private static void forRandomText( final String[] tokens, final Consumer< String > check ) {
        final Random random = new Random( 20261112L );
        for ( int round = 0; round < 20_000; round++ ) {
            final StringBuilder sb = new StringBuilder();
            final int len = random.nextInt( 14 );
            for ( int i = 0; i < len; i++ ) {
                sb.append( tokens[ random.nextInt( tokens.length ) ] );
            }
            check.accept( sb.toString() );
        }
    }

    @Test
    void citationsMatchTheRegex() {
        final CitationMarkupParser parser = new CitationMarkupParser();
        final int[] withCitations = { 0 };
        forRandomText( new String[] { "[", "]", "(", ")", "cite://", "\"", " ", "\n", "a", "t/h", "](", "](cite://x", "[c" }, s -> {
            final List< String > expected = new ArrayList<>();
            final Matcher m = CITE.matcher( s );
            while ( m.find() ) {
                final String path = m.group( 2 );
                final int slash = path.indexOf( '/' );
                expected.add( m.group( 1 ).trim() + "|" + ( slash < 0 ? path : path.substring( 0, slash ) ) + "|"
                        + ( m.group( 3 ) == null ? "" : m.group( 3 ) ) );
            }
            final List< String > actual = new ArrayList<>();
            for ( final ParsedCitation c : parser.parse( s ) ) {
                actual.add( c.claimText() + "|" + c.targetCanonicalId() + "|" + c.spanText() );
            }
            assertEquals( expected, actual, () -> "citations in: " + s );
            if ( !expected.isEmpty() ) {
                withCitations[ 0 ]++;
            }
        } );
        assertTrue( withCitations[ 0 ] > 100, "the random text must actually contain citations: " + withCitations[ 0 ] );
    }

    @Test
    void pluginCallBlankingMatchesTheRegex() {
        forRandomText( new String[] { "[{", "}]", "(", ")", "[", "]", "{", "}", "a", " " }, s ->
                assertEquals( PLUGIN_CALL.matcher( s ).replaceAll( " " ), SummaryExtractor.blankPluginCalls( s ), () -> s ) );
    }

    @Test
    void htmlTagBlankingMatchesTheRegex() {
        forRandomText( new String[] { "<", ">", "a", " ", "<b", "/>" }, s ->
                assertEquals( HTML_TAG.matcher( s ).replaceAll( " " ), MarkdownStripper.blankHtmlTags( s ), () -> s ) );
    }
}
