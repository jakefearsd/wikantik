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
package com.wikantik.importer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.attachment.AttachmentUploadPolicy;

/**
 * Planner DoS regressions: every 256 KB single-line note an authenticated user could upload must plan in
 * linear-ish time. Each input targets one formerly super-linear scan (regex backtracking or a per-link copy).
 */
class ImporterPathologicalInputTest {

    /** Just under {@code wikantik.api.maxPageBytes}, so the note is buffered and fully planned. */
    private static final int N = 262_000;
    private static final Duration BUDGET = Duration.ofSeconds( 2 );

    private static String fill( final String unit ) {
        return unit.repeat( N / unit.length() );
    }

    private static String backtickStaircase() {
        final StringBuilder sb = new StringBuilder();
        for ( int k = 1; sb.length() + k + 1 < N; k++ ) {
            sb.append( "`".repeat( k ) ).append( 'a' );
        }
        return sb.toString();
    }

    static Stream< Arguments > inputs() {
        return Stream.of(
            Arguments.of( "letters (URL scan)", fill( "a" ) ),
            Arguments.of( "scheme then letters (URL scan)", "://" + fill( "a" ) ),
            Arguments.of( "spaces then block id", "p" + fill( " " ) + "^id" ),
            Arguments.of( "spaces then text (trailing block scan)", "p" + fill( " " ) + "x" ),
            Arguments.of( "wikilink tokens (table-row lookback)", fill( "[[a]]" ) ),
            Arguments.of( "table row of wikilinks split by code", "| " + fill( "[[a]] `c` " ) ),
            Arguments.of( "unclosed wikilink openers (tag masking)", fill( "[[a " ) ),
            Arguments.of( "open brackets", fill( "[" ) ),
            Arguments.of( "unclosed link destinations (tag masking)", fill( "](a" ) ),
            Arguments.of( "unclosed angle destinations", fill( "[a](<" ) ),
            Arguments.of( "unclosed bare destinations", fill( "[a](x" ) ),
            Arguments.of( "markdown note links", fill( "[x](a.md) " ) ),
            Arguments.of( "unclosed comment", "%%" + fill( "a" ) ),
            Arguments.of( "hash runs", fill( "#a" ) ),
            Arguments.of( "backtick staircase", backtickStaircase() ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "inputs" )
    void singleLineNotePlansInBoundedTime( final String label, final String body ) throws Exception {
        final Map< String, byte[] > vault = new LinkedHashMap<>();
        vault.put( "a.md", "target".getBytes( StandardCharsets.UTF_8 ) );
        vault.put( "Big.md", body.getBytes( StandardCharsets.UTF_8 ) );
        final Path zip = TestVaults.write( TestVaults.zip( vault ) );
        try {
            final VaultArchive archive = new VaultArchiveReader( ImportLimits.defaults() ).read( zip );
            final VaultImportPlanner planner = new VaultImportPlanner( FrontmatterSchema.defaultSchema(),
                new AttachmentGate( new AttachmentUploadPolicy( new String[ 0 ], new String[ 0 ], Long.MAX_VALUE ) ),
                2000 );
            final PlanResult r = assertTimeoutPreemptively( BUDGET,
                () -> planner.plan( archive, ImportOptions.parse( "none", null ),
                    new FakeWikiSnapshot( Set.of(), Set.of(), Map.of() ), "sha", "v.zip" ), label );
            assertNotNull( r );
        } finally {
            Files.deleteIfExists( zip );
        }
    }

    @Test
    void inlineTagScanIsLinearOnLongRuns() {
        assertTimeoutPreemptively( BUDGET, () -> {
            InlineTags.scan( fill( "a" ) );
            InlineTags.scan( "://" + fill( "a" ) );
            InlineTags.scan( fill( "[[a " ) );
            InlineTags.scan( fill( "](a" ) );
        } );
    }
}
