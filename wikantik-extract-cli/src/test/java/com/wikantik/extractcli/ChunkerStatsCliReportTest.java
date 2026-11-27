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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the numbers {@link ChunkerStatsCli} reports, not just that it ran. */
class ChunkerStatsCliReportTest {

    private LogCapture cap;

    @BeforeEach
    void attach() {
        cap = LogCapture.attach( ChunkerStatsCli.class );
    }

    @AfterEach
    void detach() {
        cap.detach();
    }

    private static void page( final Path dir, final String name, final int words ) throws IOException {
        Files.writeString( dir.resolve( name + ".md" ),
                "---\ntitle: " + name + "\n---\n" + "alpha ".repeat( words ).trim() + "\n" );
    }

    @Test
    void reportsStatsAndDistributionForKnownChunkSizes( @TempDir final Path tmp ) throws Exception {
        for ( int i = 1; i <= 20; i++ ) {
            page( tmp, "P" + i, i * 10 );
        }
        final ChunkerStatsCli.Args a = new ChunkerStatsCli.Args();
        a.pagesDir = tmp.toString();
        assertEquals( 0, ChunkerStatsCli.run( a ) );
        final List< String > m = cap.messages();
        assertTrue( m.stream().anyMatch( x -> x.contains( "pages=20 chunks=20 " ) ), m.toString() );
        assertTrue( m.contains( "Tokens per chunk: min=15 mean=158 p50=165 p90=285 p99=300 max=300" ), m.toString() );
        assertTrue( m.contains( "    0-50  : 3 (15.0%)" ), m.toString() );
        assertTrue( m.contains( "   51-150 : 7 (35.0%)" ), m.toString() );
        assertTrue( m.contains( "  151-300 : 10 (50.0%)" ), m.toString() );
        assertTrue( m.contains( "  301-500 : 0 (0.0%)" ), m.toString() );
        assertTrue( m.contains( "  1001+   : 0 (0.0%)" ), m.toString() );
    }

    @Test
    void missingPagesDirIsReportedOnStderrWithTheResolvedPath( @TempDir final Path tmp ) {
        final ChunkerStatsCli.Args a = new ChunkerStatsCli.Args();
        a.pagesDir = tmp.resolve( "nope" ).toString();
        final PrintStream original = System.err;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr( new PrintStream( captured, true, StandardCharsets.UTF_8 ) );
        try {
            assertEquals( 1, ChunkerStatsCli.run( a ) );
        } finally {
            System.setErr( original );
        }
        assertEquals( "error: --pages-dir does not exist or is not a directory: " + tmp.resolve( "nope" ) + "\n",
                      captured.toString( StandardCharsets.UTF_8 ).replace( "\r\n", "\n" ) );
    }

    @Test
    void aDirectoryNamedLikeAMarkdownFileIsNotAPage( @TempDir final Path tmp ) throws Exception {
        Files.createDirectory( tmp.resolve( "Folder.md" ) );
        page( tmp, "Real", 10 );
        final ChunkerStatsCli.Args a = new ChunkerStatsCli.Args();
        a.pagesDir = tmp.toString();
        assertEquals( 0, ChunkerStatsCli.run( a ) );
        assertTrue( cap.messages().stream().anyMatch( m -> m.contains( "pages=1 " ) ), cap.messages().toString() );
    }

    @Test
    void nestedMarkdownIsScannedRecursively( @TempDir final Path tmp ) throws Exception {
        Files.createDirectory( tmp.resolve( "sub" ) );
        page( tmp.resolve( "sub" ), "Deep", 10 );
        page( tmp, "Top", 10 );
        final ChunkerStatsCli.Args a = new ChunkerStatsCli.Args();
        a.pagesDir = tmp.toString();
        assertEquals( 0, ChunkerStatsCli.run( a ) );
        assertTrue( cap.messages().stream().anyMatch( m -> m.contains( "pages=2 " ) ), cap.messages().toString() );
    }
}
