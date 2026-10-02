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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CodeMaskTest {

    @Test
    void fencedMarksOnlyFenceLines() {
        final String md = "a `x`\n```\nb\n```\n\n    indented\n";
        final boolean[] all = CodeMask.of( md );
        final boolean[] fenced = CodeMask.fenced( md );
        assertTrue( all[ md.indexOf( "x" ) ] );
        assertFalse( fenced[ md.indexOf( "x" ) ] );
        assertTrue( fenced[ md.indexOf( "b" ) ] );
        assertTrue( fenced[ md.indexOf( "```" ) ] );
        assertTrue( all[ md.indexOf( "indented" ) ] );
        assertFalse( fenced[ md.indexOf( "indented" ) ] );
        assertFalse( fenced[ 0 ] );
    }

    /**
     * Unmatched backtick runs of distinct lengths each used to rescan the rest of the line (O(n^1.5) on one long
     * line: ~100 ms per 256 KB note per call, five calls per imported note). Pairing is now precomputed per line.
     */
    @Test
    void inlineCodeScanIsLinearOnUnmatchedRunStaircase() {
        final StringBuilder sb = new StringBuilder();
        for ( int k = 1; sb.length() < 16_000_000; k++ ) {
            sb.append( "`".repeat( k ) ).append( 'a' );
        }
        final String line = sb.toString();
        final boolean[] mask = assertTimeoutPreemptively( java.time.Duration.ofSeconds( 2 ), () -> CodeMask.of( line ) );
        assertFalse( mask[ line.length() - 1 ] );
    }

    @Test
    void inlineCodePairsTheNextRunOfEqualLengthSkippingEnclosedRuns() {
        final String md = "a ``x ` y`` b `z` c ``` d `e";
        final boolean[] m = CodeMask.of( md );
        assertTrue( m[ md.indexOf( "x" ) ] && m[ md.indexOf( "y" ) ] );
        assertFalse( m[ md.indexOf( "b" ) ] );
        assertTrue( m[ md.indexOf( "z" ) ] );
        assertFalse( m[ md.indexOf( "d" ) ] );
        assertFalse( m[ md.indexOf( "e" ) ] );
    }
}
