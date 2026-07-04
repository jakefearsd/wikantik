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
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link AssignCanonicalIdsCli#main(String[])}'s success paths directly.
 * Unlike most CLIs in this module, {@code main()} here only calls
 * {@code System.exit} on the usage-error branch (missing pagesDir argument) —
 * the dry-run and --write paths just print a summary and return, so they're
 * safely callable in-process without killing the test JVM.
 */
class AssignCanonicalIdsCliMainTest {

    private static String runMainCapturingStdout( final String... args ) throws Exception {
        final PrintStream original = System.out;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut( new PrintStream( captured, true, StandardCharsets.UTF_8 ) );
        try {
            AssignCanonicalIdsCli.main( args );
        } finally {
            System.setOut( original );
        }
        return captured.toString( StandardCharsets.UTF_8 );
    }

    @Test
    void mainDryRunReportsScannedAndMissingCounts( @TempDir final Path tmp ) throws Exception {
        Files.writeString( tmp.resolve( "A.md" ), "---\ntitle: A\n---\nbody" );
        Files.writeString( tmp.resolve( "B.md" ), "---\ncanonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\n---\nbody" );

        final String out = runMainCapturingStdout( tmp.toString() );

        assertEquals( "scanned=2  missing=1  updated=0  mode=DRY-RUN", out.trim() );
        // Dry-run must not have touched the file.
        assertTrue( Files.readString( tmp.resolve( "A.md" ) ).equals( "---\ntitle: A\n---\nbody" ) );
    }

    @Test
    void mainWriteFlagAssignsCanonicalIdAndReportsUpdatedCount( @TempDir final Path tmp ) throws Exception {
        Files.writeString( tmp.resolve( "A.md" ), "---\ntitle: A\n---\nbody" );

        final String out = runMainCapturingStdout( tmp.toString(), "--write" );

        assertEquals( "scanned=1  missing=1  updated=1  mode=WRITE", out.trim() );
        final String rewritten = Files.readString( tmp.resolve( "A.md" ) );
        assertTrue( rewritten.contains( "canonical_id:" ), rewritten );
    }
}
