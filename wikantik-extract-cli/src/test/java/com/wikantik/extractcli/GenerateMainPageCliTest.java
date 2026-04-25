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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GenerateMainPageCliTest {

    private static void writePage( final Path dir, final String name, final String fm ) throws Exception {
        Files.writeString( dir.resolve( name ),
                "---\n" + fm + "\n---\n# " + name.replace( ".md", "" ) + "\n" );
    }

    @Test
    void check_mode_reports_drift_when_main_md_missing( @TempDir final Path tmp ) throws Exception {
        Files.writeString( tmp.resolve( "Main.pins.yaml" ), """
                intro: Hi.
                sections:
                  - label: "S"
                    pages: []
                """ );
        final var result = new GenerateMainPageCli().run( tmp, GenerateMainPageCli.Mode.CHECK );
        assertEquals( 2, result.exitCode() );
        assertTrue( result.summary().contains( "does not exist" ) );
    }

    @Test
    void write_mode_creates_main_md_and_check_passes_after( @TempDir final Path tmp ) throws Exception {
        writePage( tmp, "Alpha.md",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: Alpha\nsummary: Alpha summary." );
        Files.writeString( tmp.resolve( "Main.pins.yaml" ), """
                intro: Hi.
                sections:
                  - label: "S"
                    pages: [01AAAAAAAAAAAAAAAAAAAAAAAA]
                """ );

        final var write = new GenerateMainPageCli().run( tmp, GenerateMainPageCli.Mode.WRITE );
        assertEquals( 0, write.exitCode() );
        assertTrue( Files.exists( tmp.resolve( "Main.md" ) ) );

        final var check = new GenerateMainPageCli().run( tmp, GenerateMainPageCli.Mode.CHECK );
        assertEquals( 0, check.exitCode() );
    }

    @Test
    void check_mode_detects_hand_edit_drift( @TempDir final Path tmp ) throws Exception {
        writePage( tmp, "Alpha.md",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: Alpha\nsummary: x" );
        Files.writeString( tmp.resolve( "Main.pins.yaml" ), """
                sections:
                  - label: "S"
                    pages: [01AAAAAAAAAAAAAAAAAAAAAAAA]
                """ );
        new GenerateMainPageCli().run( tmp, GenerateMainPageCli.Mode.WRITE );

        // Simulate a hand-edit.
        final Path mainMd = tmp.resolve( "Main.md" );
        Files.writeString( mainMd, Files.readString( mainMd ) + "\n## hand-added section\n" );

        final var check = new GenerateMainPageCli().run( tmp, GenerateMainPageCli.Mode.CHECK );
        assertEquals( 2, check.exitCode() );
        assertTrue( check.summary().contains( "out of sync" ) );
    }

    @Test
    void missing_pages_dir_returns_exit_three( @TempDir final Path tmp ) throws Exception {
        final var result = new GenerateMainPageCli().run( tmp.resolve( "nope" ), GenerateMainPageCli.Mode.CHECK );
        assertEquals( 3, result.exitCode() );
    }
}
