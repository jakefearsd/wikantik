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
package com.wikantik.pagegraph.spine;

import java.util.BitSet;

/**
 * A lower-cased, whitespace-free match key that remembers where each word of the original text started.
 * Word starts are found BEFORE lower-casing so CamelCase boundaries survive: the first character, the
 * character after whitespace, {@code -}, {@code _} or {@code /}, an upper-case letter after a lower-case
 * letter or digit, the last capital of an acronym run ({@code HTMLParser} → {@code P}), and a
 * letter/digit transition (mirrors {@code TextUtil.beautifyString}).
 *
 * @param text       the normalized key (lower-cased per character, whitespace removed)
 * @param wordStarts indices into {@code text} that begin a word
 */
record MatchKey( String text, BitSet wordStarts ) {

    static MatchKey of( final String original ) {
        final StringBuilder text = new StringBuilder( original.length() );
        final BitSet starts = new BitSet( original.length() );
        boolean afterSeparator = true;
        for ( int k = 0; k < original.length(); k++ ) {
            final char cur = original.charAt( k );
            if ( Character.isWhitespace( cur ) ) {
                afterSeparator = true;
                continue;
            }
            if ( afterSeparator || isCaseOrDigitBoundary( original, k ) ) {
                starts.set( text.length() );
            }
            text.append( Character.toLowerCase( cur ) );
            afterSeparator = cur == '-' || cur == '_' || cur == '/';
        }
        return new MatchKey( text.toString(), starts );
    }

    /** Lower-cases per character and strips whitespace — the same normalization {@link #of} applies. */
    static String normalize( final String s ) {
        return of( s ).text();
    }

    private static boolean isCaseOrDigitBoundary( final String s, final int k ) {
        if ( k == 0 ) {
            return true;
        }
        final char prev = s.charAt( k - 1 );
        final char cur = s.charAt( k );
        if ( Character.isUpperCase( cur ) ) {
            return Character.isLowerCase( prev ) || Character.isDigit( prev )
                    || Character.isUpperCase( prev ) && k + 1 < s.length() && Character.isLowerCase( s.charAt( k + 1 ) );
        }
        return Character.isDigit( cur ) ? Character.isLetter( prev )
                : Character.isLetter( cur ) && Character.isDigit( prev );
    }

    /**
     * True when every character of {@code needle} matches in order, each one either starting a word or
     * immediately continuing the previous match. Tracks every viable position per query character, so a
     * greedy dead end (binding a character to an earlier word that cannot be continued) never hides a match.
     */
    boolean matchesWordStarts( final String needle ) {
        BitSet at = null;
        for ( int i = 0; i < needle.length(); i++ ) {
            at = step( at, needle.charAt( i ) );
            if ( at.isEmpty() ) {
                return false;
            }
        }
        return true;
    }

    private BitSet step( final BitSet prev, final char c ) {
        final BitSet next = new BitSet( text.length() );
        final int from = prev == null ? 0 : prev.nextSetBit( 0 ) + 1;
        for ( int j = wordStarts.nextSetBit( from ); j >= 0; j = wordStarts.nextSetBit( j + 1 ) ) {
            if ( text.charAt( j ) == c ) {
                next.set( j );
            }
        }
        if ( prev != null ) {
            for ( int p = prev.nextSetBit( 0 ); p >= 0 && p + 1 < text.length(); p = prev.nextSetBit( p + 1 ) ) {
                if ( text.charAt( p + 1 ) == c ) {
                    next.set( p + 1 );
                }
            }
        }
        return next;
    }
}
