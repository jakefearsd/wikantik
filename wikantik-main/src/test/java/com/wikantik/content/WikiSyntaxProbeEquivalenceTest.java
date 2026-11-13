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
package com.wikantik.content;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The linear wiki-syntax probes must agree with the regexes they replaced (kept here verbatim as the oracle). */
class WikiSyntaxProbeEquivalenceTest {

    private static final Pattern WIKI_PIPE_LINK =
            Pattern.compile( "(?<!\\[)\\[(?!\\[)([^\\]|]+)\\|([^\\]]+)\\](?!\\])" );
    private static final Pattern WIKI_PLUGIN_UNCONVERTED = Pattern.compile( "\\[\\{[^}]+\\}\\](?!\\()" );

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
    void wikiPipeLinkDetectionMatchesTheRegex() {
        forRandomText( new String[] { "[", "]", "|", "a", " ", "[[", "]]", "[a|", "b]" }, s ->
                assertEquals( WIKI_PIPE_LINK.matcher( s ).find(), WikiSyntaxProbe.hasWikiPipeLink( s ), () -> s ) );
    }

    @Test
    void unconvertedPluginDetectionMatchesTheRegex() {
        forRandomText( new String[] { "[{", "}]", "(", "}", "]", "a", "[", "{" }, s ->
                assertEquals( WIKI_PLUGIN_UNCONVERTED.matcher( s ).find(), WikiSyntaxProbe.hasUnconvertedPlugin( s ), () -> s ) );
    }
}
