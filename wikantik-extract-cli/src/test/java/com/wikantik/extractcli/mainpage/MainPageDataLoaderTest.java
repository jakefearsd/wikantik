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
package com.wikantik.extractcli.mainpage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MainPageDataLoaderTest {

    private static void writePage( final Path dir, final String name, final String frontmatter ) throws Exception {
        Files.writeString( dir.resolve( name ),
                "---\n" + frontmatter + "\n---\n# " + name.replace( ".md", "" ) + "\n" );
    }

    @Test
    void resolves_pinned_pages_with_frontmatter_summary( @TempDir final Path tmp ) throws Exception {
        writePage( tmp, "Alpha.md",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\n" +
                "title: Alpha\nsummary: Alpha summary." );
        writePage( tmp, "Beta.md",
                "canonical_id: 01BBBBBBBBBBBBBBBBBBBBBBBB\n" +
                "title: Beta\nsummary: Beta summary." );

        final Path pins = tmp.resolve( "Main.pins.yaml" );
        Files.writeString( pins, """
                intro: Hi.
                sections:
                  - label: "Section One"
                    pages:
                      - 01AAAAAAAAAAAAAAAAAAAAAAAA
                      - id: 01BBBBBBBBBBBBBBBBBBBBBBBB
                        summary: "Override for Beta"
                """ );

        final MainPageData data = MainPageDataLoader.load( tmp, pins );

        assertEquals( "Hi.", data.intro() );
        assertEquals( 1, data.sections().size() );
        final var section = data.sections().get( 0 );
        assertEquals( "Section One", section.label() );
        assertEquals( 2, section.pages().size() );

        assertEquals( "Alpha", section.pages().get( 0 ).slug() );
        assertEquals( "Alpha summary.", section.pages().get( 0 ).summary() );

        assertEquals( "Beta", section.pages().get( 1 ).slug() );
        assertEquals( "Override for Beta", section.pages().get( 1 ).summary() );

        assertTrue( data.warnings().isEmpty() );
    }

    @Test
    void unresolved_pin_is_dropped_and_warned( @TempDir final Path tmp ) throws Exception {
        writePage( tmp, "Real.md",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: Real\nsummary: x" );
        final Path pins = tmp.resolve( "Main.pins.yaml" );
        Files.writeString( pins, """
                sections:
                  - label: "S"
                    pages:
                      - 01AAAAAAAAAAAAAAAAAAAAAAAA
                      - 01GHOSTGHOSTGHOSTGHOSTGHOST
                """ );

        final MainPageData data = MainPageDataLoader.load( tmp, pins );
        assertEquals( 1, data.sections().get( 0 ).pages().size() );
        assertEquals( 1, data.warnings().size() );
        assertTrue( data.warnings().get( 0 ).contains( "01GHOSTGHOSTGHOSTGHOSTGHOST" ) );
    }

    @Test
    void missing_pins_file_yields_empty_data( @TempDir final Path tmp ) throws Exception {
        writePage( tmp, "X.md",
                "canonical_id: 01XXXXXXXXXXXXXXXXXXXXXXXX\ntitle: X" );
        final MainPageData data = MainPageDataLoader.load( tmp, tmp.resolve( "Main.pins.yaml" ) );
        assertTrue( data.sections().isEmpty() );
        assertEquals( "", data.intro() );
    }

    @Test
    void pages_without_canonical_id_are_unindexed( @TempDir final Path tmp ) throws Exception {
        writePage( tmp, "Anon.md", "title: Anonymous" );
        final Path pins = tmp.resolve( "Main.pins.yaml" );
        Files.writeString( pins, """
                sections:
                  - label: "S"
                    pages: [01ANYIDXXXXXXXXXXXXXXXXXXX]
                """ );
        final MainPageData data = MainPageDataLoader.load( tmp, pins );
        assertEquals( 0, data.sections().get( 0 ).pages().size() );
        assertEquals( 1, data.warnings().size() );
    }

    @Test
    void title_falls_back_to_slug_when_frontmatter_omits_it( @TempDir final Path tmp ) throws Exception {
        writePage( tmp, "NoTitle.md", "canonical_id: 01NNNNNNNNNNNNNNNNNNNNNNNN" );
        final Path pins = tmp.resolve( "Main.pins.yaml" );
        Files.writeString( pins, """
                sections:
                  - label: "S"
                    pages: [01NNNNNNNNNNNNNNNNNNNNNNNN]
                """ );
        final MainPageData data = MainPageDataLoader.load( tmp, pins );
        assertEquals( "NoTitle", data.sections().get( 0 ).pages().get( 0 ).title() );
    }
}
