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
package com.wikantik.parser.markdown;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code strip}, {@code find} and {@code markerIds} skip the parse when the cheap pre-check says no marker can be in the
 * text. That is only right if the pre-check accepts every text the walk finds a marker in: over seeded token soups —
 * blanks of every kind ({@code \v}, {@code \f}, no-break space), quote and list prefixes, access rules, code, math,
 * escapes, footnotes — the pre-checked answers must equal the unchecked ones, and agree with {@code list}.
 */
class BlockIdsPreCheckFuzzTest {

    private static final int INPUTS = 60_000;

    private static final String[] TOKENS = {
        "^id", "^ab-1", " ", "\t", "\n", "\r\n", "\n\n", "> ", ">", "- ", "1. ", "  ", "    ", "[{ALLOW view Admin}]",
        "[{DENY edit Guest}]()", "Para", "x", "*", "**", "_", "`", "\\", "#", "## ", "|", "[^1]", "[link](u)", "<b>",
        "</b>", "\u000B", "\f", " ", "$", "~~", "!", "[[P]]", "==", "<!-- c -->", "&nbsp;", "[{Plugin}]", "\r",
        "\u0085", " ", "$$",
    };

    @Test
    void thePreCheckedAnswersAreTheUncheckedOnes() {
        final Random random = new Random( 0x9EC4L );
        final List< String > failures = new ArrayList<>();
        int withMarkers = 0;
        for ( int i = 0; i < INPUTS && failures.size() < 10; i++ ) {
            final StringBuilder sb = new StringBuilder();
            for ( int t = 2 + random.nextInt( 14 ); t > 0; t-- ) {
                sb.append( TOKENS[ random.nextInt( TOKENS.length ) ] );
            }
            final String text = sb.toString();
            try {
                final Set< String > unchecked = BlockIds.markerIds( text, false );
                withMarkers += unchecked.isEmpty() ? 0 : 1;
                if ( !unchecked.isEmpty() && !BlockIds.mayHoldMarker( text ) ) {
                    failures.add( "pre-check refuses a text with markers " + unchecked + ": " + esc( text ) );
                }
                if ( !BlockIds.markerIds( text ).equals( unchecked ) ) {
                    failures.add( "markerIds " + BlockIds.markerIds( text ) + " != " + unchecked + ": " + esc( text ) );
                }
                if ( unchecked.isEmpty() != ( BlockIds.strip( text ) == text ) ) {
                    failures.add( "strip disagrees with the markers " + unchecked + ": " + esc( text ) );
                }
                for ( final BlockIds.Block b : BlockIds.list( text ) ) {
                    if ( b.id() != null && BlockIds.find( text, b.id() ).map( BlockIds.Block::index ).orElse( -1 ) != b.index() ) {
                        failures.add( "find misses ^" + b.id() + " of block " + b.index() + ": " + esc( text ) );
                    }
                }
            } catch ( final RuntimeException e ) {
                failures.add( "threw " + e + ": " + esc( text ) );
            }
        }
        assertEquals( List.of(), failures, String.join( "\n", failures ) );
        assertTrue( withMarkers > INPUTS / 100, "too few soups hold a marker: " + withMarkers );
    }

    private static String esc( final String s ) {
        return s.replace( "\r", "\\r" ).replace( "\n", "\\n" ).replace( "\t", "\\t" ).replace( "\u000B", "\\v" )
                .replace( "\f", "\\f" ).replace( " ", "\\nbsp" ).replace( "\u0085", "\\u0085" ).replace( " ", "\\u2028" );
    }
}
