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
package com.wikantik.extractcli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic coverage for {@link ChunkerStatsCli.Args#parse(String[])}. */
class ChunkerStatsCliArgsTest {

    @Test
    void defaultsMatchDocumentedValues() {
        final ChunkerStatsCli.Args a = ChunkerStatsCli.Args.parse( new String[]{} );
        assertEquals( "docs/wikantik-pages", a.pagesDir );
        assertEquals( 512, a.chunkerMaxTokens );
        assertEquals( 150, a.chunkerMergeForwardTokens );
        assertEquals( 24, a.chunkerFragmentFloorTokens );
        assertEquals( 40, a.chunkerOverlapTokens );
        assertFalse( a.showHelp );
    }

    @Test
    void allFlagsOverrideDefaults() {
        final ChunkerStatsCli.Args a = ChunkerStatsCli.Args.parse( new String[]{
            "--pages-dir", "/tmp/pages",
            "--chunker-max-tokens", "1000",
            "--chunker-merge-forward-tokens", "300",
            "--chunker-fragment-floor-tokens", "50",
            "--chunker-overlap-tokens", "80"
        } );
        assertEquals( "/tmp/pages", a.pagesDir );
        assertEquals( 1000, a.chunkerMaxTokens );
        assertEquals( 300, a.chunkerMergeForwardTokens );
        assertEquals( 50, a.chunkerFragmentFloorTokens );
        assertEquals( 80, a.chunkerOverlapTokens );
    }

    @Test
    void helpFlagSkipsValidation() {
        // Even with a value that would otherwise fail validation (blank pages-dir
        // isn't reachable via CLI syntax, but an out-of-range max-tokens is), --help
        // short-circuits validation entirely.
        final ChunkerStatsCli.Args a = ChunkerStatsCli.Args.parse( new String[]{
            "--chunker-max-tokens", "0", "--help"
        } );
        assertTrue( a.showHelp );
    }

    @Test
    void shortHelpFlagAlsoWorks() {
        assertTrue( ChunkerStatsCli.Args.parse( new String[]{ "-h" } ).showHelp );
    }

    @Test
    void unknownArgumentRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> ChunkerStatsCli.Args.parse( new String[]{ "--bogus" } ) );
        assertTrue( ex.getMessage().contains( "unknown argument: --bogus" ), ex.getMessage() );
    }

    @Test
    void missingValueForFlagThrows() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> ChunkerStatsCli.Args.parse( new String[]{ "--pages-dir" } ) );
        assertTrue( ex.getMessage().contains( "--pages-dir requires a value" ), ex.getMessage() );
    }

    @Test
    void nonIntegerValueRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> ChunkerStatsCli.Args.parse( new String[]{ "--chunker-max-tokens", "NaN" } ) );
        assertTrue( ex.getMessage().contains( "expects an integer, got: NaN" ), ex.getMessage() );
    }

    @Test
    void blankPagesDirRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> ChunkerStatsCli.Args.parse( new String[]{ "--pages-dir", "  " } ) );
        assertTrue( ex.getMessage().contains( "--pages-dir must not be blank" ), ex.getMessage() );
    }

    @Test
    void zeroMaxTokensRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> ChunkerStatsCli.Args.parse( new String[]{ "--chunker-max-tokens", "0" } ) );
        assertTrue( ex.getMessage().contains( "--chunker-max-tokens must be >= 1" ), ex.getMessage() );
    }

    @Test
    void negativeMergeForwardTokensRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> ChunkerStatsCli.Args.parse( new String[]{ "--chunker-merge-forward-tokens", "-1" } ) );
        assertTrue( ex.getMessage().contains( "--chunker-merge-forward-tokens must be >= 0" ), ex.getMessage() );
    }

    @Test
    void mergeForwardExceedingMaxTokensRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> ChunkerStatsCli.Args.parse( new String[]{
                "--chunker-max-tokens", "100", "--chunker-merge-forward-tokens", "200" } ) );
        assertTrue( ex.getMessage().contains( "floor cannot exceed ceiling" ), ex.getMessage() );
    }

    @Test
    void negativeFragmentFloorTokensRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> ChunkerStatsCli.Args.parse( new String[]{ "--chunker-fragment-floor-tokens", "-1" } ) );
        assertTrue( ex.getMessage().contains( "--chunker-fragment-floor-tokens must be >= 0" ), ex.getMessage() );
    }

    @Test
    void fragmentFloorExceedingMergeForwardRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> ChunkerStatsCli.Args.parse( new String[]{
                "--chunker-merge-forward-tokens", "50", "--chunker-fragment-floor-tokens", "100" } ) );
        assertTrue( ex.getMessage().contains(
            "--chunker-fragment-floor-tokens must be <= --chunker-merge-forward-tokens" ), ex.getMessage() );
    }
}
