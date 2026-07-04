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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link ChunkerStatsCli#run(ChunkerStatsCli.Args)} against a small
 * on-disk corpus. The method reports everything through the logger rather
 * than a return value, so a {@link LogCapture} pins the actual computed
 * numbers (pages/chunks scanned, prefilter kept/skipped) instead of merely
 * checking the exit code.
 */
class ChunkerStatsCliTest {

    private LogCapture cap;

    @BeforeEach
    void attach() {
        cap = LogCapture.attach( ChunkerStatsCli.class );
    }

    @AfterEach
    void detach() {
        cap.detach();
    }

    @Test
    void missingPagesDirReturnsExitOne( @TempDir final Path tmp ) {
        final ChunkerStatsCli.Args a = new ChunkerStatsCli.Args();
        a.pagesDir = tmp.resolve( "does-not-exist" ).toString();
        assertEquals( 1, ChunkerStatsCli.run( a ) );
    }

    @Test
    void emptyDirectoryReturnsExitZeroWithNoFilesFoundWarning( @TempDir final Path tmp ) {
        final ChunkerStatsCli.Args a = new ChunkerStatsCli.Args();
        a.pagesDir = tmp.toString();
        assertEquals( 0, ChunkerStatsCli.run( a ) );
        assertTrue( cap.messages().stream().anyMatch( m -> m.contains( "no .md files found" ) ),
            () -> "messages: " + cap.messages() );
    }

    @Test
    void nonMarkdownFilesAreIgnoredAndPageCountReflectsOnlyMdFiles( @TempDir final Path tmp ) throws Exception {
        // Two real pages, one non-.md file that must be ignored by the extension filter.
        Files.writeString( tmp.resolve( "Solo.md" ),
            "---\ntitle: Solo\n---\n# Solo\n\n"
          + "Kafka is a distributed streaming platform originally built at LinkedIn and later "
          + "open-sourced through the Apache Software Foundation for durable, high-throughput, "
          + "publish-subscribe messaging across many independent consumer teams.\n" );
        Files.writeString( tmp.resolve( "notes.txt" ), "Kafka Kafka Kafka Kafka Kafka Kafka Kafka" );

        final ChunkerStatsCli.Args a = new ChunkerStatsCli.Args();
        a.pagesDir = tmp.toString();
        assertEquals( 0, ChunkerStatsCli.run( a ) );

        final List< String > messages = cap.messages();
        assertTrue( messages.stream().anyMatch( m -> m.contains( "pages=1" ) ),
            () -> "expected exactly 1 page scanned (the .txt file must be skipped): " + messages );
    }

    @Test
    void shortChunkIsSkippedByPrefilterWithTooShortReason( @TempDir final Path tmp ) throws Exception {
        // "Kafka rocks." has a proper noun (Kafka) so it won't hit no_proper_noun,
        // and its estimated token count (chars/4) is well under the 20-token floor,
        // so the prefilter must skip it with reason=too_short.
        Files.writeString( tmp.resolve( "Tiny.md" ), "---\ntitle: Tiny\n---\nKafka rocks.\n" );

        final ChunkerStatsCli.Args a = new ChunkerStatsCli.Args();
        a.pagesDir = tmp.toString();
        assertEquals( 0, ChunkerStatsCli.run( a ) );

        final List< String > messages = cap.messages();
        assertTrue( messages.stream().anyMatch( m -> m.contains( "reason=too_short" ) ),
            () -> "expected a too_short prefilter reason line: " + messages );
        assertTrue( messages.stream().anyMatch( m -> m.contains( "kept=0 skipped=1" ) ),
            () -> "expected the lone chunk to be skipped, none kept: " + messages );
    }

    @Test
    void longChunkIsKeptByPrefilterAndDistributionSumsToTotal( @TempDir final Path tmp ) throws Exception {
        final String longParagraph =
            "Kafka is a distributed streaming platform originally built at LinkedIn and later "
          + "open-sourced through the Apache Software Foundation for durable, high-throughput, "
          + "publish-subscribe messaging across many independent consumer teams, supporting "
          + "replayable logs, consumer groups, and exactly-once semantics for critical pipelines.";
        Files.writeString( tmp.resolve( "Solo.md" ), "---\ntitle: Solo\n---\n" + longParagraph + "\n" );

        final ChunkerStatsCli.Args a = new ChunkerStatsCli.Args();
        a.pagesDir = tmp.toString();
        assertEquals( 0, ChunkerStatsCli.run( a ) );

        final List< String > messages = cap.messages();
        assertTrue( messages.stream().anyMatch( m -> m.contains( "pages=1" ) && m.contains( "chunks=1" ) ),
            () -> "expected exactly one page producing exactly one chunk: " + messages );
        assertTrue( messages.stream().anyMatch( m -> m.contains( "kept=1 skipped=0" ) ),
            () -> "long, proper-noun-bearing chunk should be kept by the prefilter: " + messages );
        assertFalse( messages.stream().anyMatch( m -> m.contains( "reason=" ) ),
            () -> "no reject reasons expected when nothing was skipped: " + messages );
    }

    @Test
    void unreadableMdFileIsSkippedWithAWarningInsteadOfFailingTheScan( @TempDir final Path tmp ) throws Exception {
        // Root (and some CI sandboxes) bypass POSIX permission bits entirely, in
        // which case Files.readString would succeed and the "read failed" branch
        // wouldn't be exercised — skip rather than assert a false failure.
        Assumptions.assumeTrue( ! "root".equals( System.getProperty( "user.name" ) ) );
        final Path unreadable = tmp.resolve( "Locked.md" );
        Files.writeString( unreadable, "---\ntitle: Locked\n---\nKafka is unreadable on purpose.\n" );
        try {
            Files.setPosixFilePermissions( unreadable, Set.of() );
        } catch ( final UnsupportedOperationException e ) {
            Assumptions.abort( "POSIX permissions unsupported on this filesystem" );
        }

        try {
            final ChunkerStatsCli.Args a = new ChunkerStatsCli.Args();
            a.pagesDir = tmp.toString();
            final int rc = ChunkerStatsCli.run( a );

            Assumptions.assumeTrue( cap.messages().stream().anyMatch( m -> m.contains( "read failed" ) ),
                "permission bits were not enforced (likely running as an unconfined user) — skipping" );
            assertEquals( 0, rc );
            assertTrue( cap.messages().stream().anyMatch( m -> m.contains( "no .md files found" ) ),
                () -> "the unreadable file must be skipped, leaving zero readable pages: " + cap.messages() );
        } finally {
            Files.setPosixFilePermissions( unreadable, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE ) );
        }
    }

    @Test
    void mainWithHelpFlagPrintsUsageAndDoesNotExitTheJvm() {
        final PrintStream original = System.out;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut( new PrintStream( captured, true, StandardCharsets.UTF_8 ) );
        try {
            ChunkerStatsCli.main( new String[]{ "--help" } );
        } finally {
            System.setOut( original );
        }
        final String out = captured.toString( StandardCharsets.UTF_8 );
        assertTrue( out.contains( "wikantik-chunker-stats" ), out );
        assertTrue( out.contains( "Exit codes: 0 = stats printed" ), out );
    }
}
