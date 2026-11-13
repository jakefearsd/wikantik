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

/**
 * One-pass probes for legacy wiki syntax, standing in for regexes that rescanned to the next closer from every
 * opener (quadratic on a body of unclosed brackets, and run on every page read).
 */
final class WikiSyntaxProbe {

    private WikiSyntaxProbe() {
    }

    /**
     * True when the text holds JSPWiki's single-bracket {@code [text|Page]} (never the Obsidian alias wikilink
     * {@code [[Page|alias]]}, which is Markdown here): what the regex
     * {@code (?<!\[)\[(?!\[)([^\]|]+)\|([^\]]+)\](?!\])} finds.
     */
    static boolean hasWikiPipeLink( final String s ) {
        int close = -1;
        int pos = 0;
        while( true ) {
            final int open = s.indexOf( '[', pos );
            final int stop = open < 0 ? -1 : firstPipeOrClose( s, open + 1 );
            if( stop < 0 ) {
                return false;
            }
            pos = stop + 1; // every opener before this stop shares it
            if( s.charAt( stop ) == ']' ) {
                continue;
            }
            if( close < pos ) {
                close = s.indexOf( ']', pos );
            }
            if( close < 0 ) {
                return false;
            }
            if( closesALink( s, stop, close ) && hasLoneOpener( s, open, stop - 1 ) ) {
                return true;
            }
        }
    }

    /** Offset of the first {@code |} or {@code ]} at or after {@code from}, or -1. */
    private static int firstPipeOrClose( final String s, final int from ) {
        for( int i = from; i < s.length(); i++ ) {
            final char c = s.charAt( i );
            if( c == ']' || c == '|' ) {
                return i;
            }
        }
        return -1;
    }

    /** The page part between the pipe and the {@code ]} is not empty, and the {@code ]} is not doubled. */
    private static boolean closesALink( final String s, final int pipe, final int close ) {
        return close > pipe + 1 && ( close + 1 >= s.length() || s.charAt( close + 1 ) != ']' );
    }

    /** True when {@code [from, to)} holds a {@code [} that neither follows nor precedes another {@code [}. */
    private static boolean hasLoneOpener( final String s, final int from, final int to ) {
        for( int i = from; i < to; i++ ) {
            if( s.charAt( i ) == '[' && s.charAt( i + 1 ) != '[' && ( i == 0 || s.charAt( i - 1 ) != '[' ) ) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the text holds a plugin call still in wiki form ({@code [{...}]} not followed by {@code (}): what
     * the regex {@code \[\{[^}]+\}\](?!\()} finds.
     */
    static boolean hasUnconvertedPlugin( final String s ) {
        final int n = s.length();
        int pos = 0;
        while( true ) {
            final int open = s.indexOf( "[{", pos );
            final int brace = open < 0 ? -1 : s.indexOf( '}', open + 2 );
            if( brace < 0 ) {
                return false;
            }
            pos = brace + 1; // every opener before this } shares it
            if( brace > open + 2 && brace + 1 < n && s.charAt( brace + 1 ) == ']'
                    && ( brace + 2 >= n || s.charAt( brace + 2 ) != '(' ) ) {
                return true;
            }
        }
    }
}
