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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Value-level contracts of the pins file, its records and the loader that resolves them. */
class MainPagePinsContractTest {

    private static final String ID_A = "01AAAAAAAAAAAAAAAAAAAAAAAA";

    // ---- records ----

    @Test
    void pinsConfigNormalisesNullsAndStripsTitleAndSummary() {
        final PinsConfig c = new PinsConfig( "  T  ", "\tS\n", null, null, null );
        assertEquals( "T", c.title() );
        assertEquals( "S", c.summary() );
        assertEquals( "", c.intro() );
        assertEquals( "", c.footer() );
        assertEquals( List.of(), c.sections() );
        assertEquals( "", new PinsConfig( null, null, "i", "f", List.of() ).title() );
    }

    @Test
    void pinsConfigKeepsIntroAndFooterVerbatim() {
        final PinsConfig c = new PinsConfig( "t", "s", "  intro \n", " footer ", List.of() );
        assertEquals( "  intro \n", c.intro() );
        assertEquals( " footer ", c.footer() );
    }

    @Test
    void threeArgPinsConfigPutsIntroAndFooterFirst() {
        final PinsConfig c = new PinsConfig( "intro", "footer", List.of() );
        assertEquals( "intro", c.intro() );
        assertEquals( "footer", c.footer() );
        assertEquals( "", c.title() );
    }

    @Test
    void pinsSectionRequiresALabelAndCopiesItsPages() {
        assertThrows( IllegalArgumentException.class, () -> new PinsConfig.PinsSection( null, null, List.of() ) );
        assertThrows( IllegalArgumentException.class, () -> new PinsConfig.PinsSection( "  ", null, List.of() ) );
        assertEquals( List.of(), new PinsConfig.PinsSection( "L", null, null ).pages() );
        final List< PinsConfig.PinsPage > src = new ArrayList<>( List.of( new PinsConfig.PinsPage( "id", null, null ) ) );
        final PinsConfig.PinsSection s = new PinsConfig.PinsSection( "L", "c", src );
        src.clear();
        assertEquals( 1, s.pages().size() );
    }

    @Test
    void mainPageDataNormalisesNullsToEmpty() {
        final MainPageData d = new MainPageData( null, null, null, null, null, null );
        assertEquals( "", d.title() );
        assertEquals( "", d.summary() );
        assertEquals( "", d.intro() );
        assertEquals( "", d.footer() );
        assertEquals( List.of(), d.sections() );
        assertEquals( List.of(), d.warnings() );
        assertEquals( "i", new MainPageData( "i", "f", null, null ).intro() );
        assertEquals( "f", new MainPageData( "i", "f", null, null ).footer() );
    }

    @Test
    void mainPageSectionAndPageDefaults() {
        assertEquals( List.of(), new MainPageData.Section( "L", null, null ).pages() );
        assertThrows( IllegalArgumentException.class, () -> new MainPageData.Page( "id", null, "t", "s" ) );
        assertThrows( IllegalArgumentException.class, () -> new MainPageData.Page( "id", " ", "t", "s" ) );
        final MainPageData.Page blankTitle = new MainPageData.Page( "id", "Slug", " ", null );
        assertEquals( "Slug", blankTitle.title() );
        assertEquals( "", blankTitle.summary() );
        assertEquals( "Real", new MainPageData.Page( "id", "Slug", "Real", "x" ).title() );
    }

    // ---- parser ----

    @Test
    void aCommentOnlyDocumentIsAnEmptyConfig() {
        final PinsConfig c = PinsParser.parse( "# nothing here\n" );
        assertEquals( "", c.intro() );
        assertEquals( List.of(), c.sections() );
    }

    @Test
    void anExplicitEmptyIdIsRejectedEvenWithACanonicalIdAlias() {
        assertThrows( IllegalArgumentException.class, () -> PinsParser.parse( """
                sections:
                  - label: L
                    pages:
                      - id: ""
                """ ) );
    }

    @Test
    void idWinsOverCanonicalIdWhenBothArePresent() {
        final PinsConfig c = PinsParser.parse( """
                sections:
                  - label: L
                    pages:
                      - id: X
                        canonical_id: Y
                """ );
        assertEquals( "X", c.sections().get( 0 ).pages().get( 0 ).canonicalId() );
    }

    @Test
    void emptyOverridesAndClusterBecomeNull() {
        final PinsConfig c = PinsParser.parse( """
                sections:
                  - label: L
                    cluster: ""
                    pages:
                      - id: X
                        title: ""
                        summary: ""
                """ );
        assertNull( c.sections().get( 0 ).cluster() );
        assertNull( c.sections().get( 0 ).pages().get( 0 ).titleOverride() );
        assertNull( c.sections().get( 0 ).pages().get( 0 ).summaryOverride() );
    }

    @Test
    void aWhitespaceOnlyLabelIsRejectedAndTheMessageNamesTheIndex() {
        final IllegalArgumentException e = assertThrows( IllegalArgumentException.class, () -> PinsParser.parse( """
                sections:
                  - label: ok
                  - label: "   "
                """ ) );
        assertEquals( "Main.pins.yaml: sections[1] is missing a label", e.getMessage() );
    }

    @Test
    void aBareIdIsTrimmedAndKeepsNoOverrides() {
        final PinsConfig c = PinsParser.parse( "sections:\n  - label: L\n    pages:\n      - \" X \"\n" );
        final PinsConfig.PinsPage p = c.sections().get( 0 ).pages().get( 0 );
        assertEquals( "X", p.canonicalId() );
        assertNull( p.titleOverride() );
        assertNull( p.summaryOverride() );
    }

    @Test
    void parseErrorsNameTheOffendingSectionAndPageIndex() {
        assertEquals( "Main.pins.yaml: sections[0].pages[1] is blank",
                assertThrows( IllegalArgumentException.class, () -> PinsParser.parse(
                        "sections:\n  - label: L\n    pages:\n      - X\n      - \" \"\n" ) ).getMessage() );
        assertEquals( "Main.pins.yaml: sections[0].pages[0] is missing id/canonical_id",
                assertThrows( IllegalArgumentException.class, () -> PinsParser.parse(
                        "sections:\n  - label: L\n    pages:\n      - title: t\n" ) ).getMessage() );
        assertEquals( "Main.pins.yaml: root must be a mapping, got String",
                assertThrows( IllegalArgumentException.class, () -> PinsParser.parse( "just text" ) ).getMessage() );
    }

    // ---- loader ----

    @TempDir
    Path tmp;

    private void page( final String file, final String frontmatter ) throws IOException {
        Files.writeString( tmp.resolve( file ), "---\n" + frontmatter + "\n---\nbody\n" );
    }

    private Path pins( final String yaml ) throws IOException {
        final Path p = tmp.resolve( "pins.yaml" );
        Files.writeString( p, yaml );
        return p;
    }

    private static final String ONE_PIN = "sections:\n  - label: L\n    cluster: c\n    pages:\n      - " + ID_A + "\n";

    @Test
    void aMissingPagesDirectoryWarnsForEveryPinInsteadOfThrowing() throws IOException {
        final MainPageData d = MainPageDataLoader.load( tmp.resolve( "nope" ), pins( ONE_PIN ) );
        assertEquals( List.of( "section 'L': canonical_id " + ID_A + " does not resolve to any page (skipped)" ), d.warnings() );
        assertEquals( List.of(), d.sections().get( 0 ).pages() );
        assertEquals( "c", d.sections().get( 0 ).cluster() );
    }

    @Test
    void aDirectoryNamedLikeAPageAndNonMarkdownFilesAreNotIndexed() throws IOException {
        Files.createDirectory( tmp.resolve( "Fake.md" ) );
        Files.writeString( tmp.resolve( "Other.txt" ), "---\ncanonical_id: " + ID_A + "\n---\n" );
        final MainPageData d = MainPageDataLoader.load( tmp, pins( ONE_PIN ) );
        assertEquals( 1, d.warnings().size() );
    }

    @Test
    void aBlankCanonicalIdIsNotIndexed() throws IOException {
        page( "Blank.md", "canonical_id: '   '" );
        assertEquals( 0, MainPageDataLoader.indexPagesDirectory( tmp ).byCanonicalId().size() );
    }

    @Test
    void indexTrimsIdTitleAndSummaryAndDefaultsTheSummaryToEmpty() throws IOException {
        page( "A.md", "canonical_id: '  " + ID_A + "  '\ntitle: '  Nice Title '\nsummary: ' sum '" );
        page( "B.md", "canonical_id: 01BBBBBBBBBBBBBBBBBBBBBBBB\ntitle: ''" );
        final var index = MainPageDataLoader.indexPagesDirectory( tmp ).byCanonicalId();
        assertEquals( new MainPageDataLoader.Resolved( "A", "Nice Title", "sum" ), index.get( ID_A ) );
        assertEquals( new MainPageDataLoader.Resolved( "B", "B", "" ), index.get( "01BBBBBBBBBBBBBBBBBBBBBBBB" ) );
    }

    @Test
    void overridesBeatFrontmatterAndAnEmptyOverrideDoesNot() throws IOException {
        page( "A.md", "canonical_id: " + ID_A + "\ntitle: FM Title\nsummary: FM Summary" );
        final MainPageData d = MainPageDataLoader.load( tmp, pins(
                "sections:\n  - label: L\n    pages:\n"
              + "      - id: " + ID_A + "\n        title: Over Title\n        summary: Over Summary\n"
              + "      - id: " + ID_A + "\n        title: ''\n        summary: ''\n" ) );
        final var pages = d.sections().get( 0 ).pages();
        assertEquals( "Over Title", pages.get( 0 ).title() );
        assertEquals( "Over Summary", pages.get( 0 ).summary() );
        assertEquals( "FM Title", pages.get( 1 ).title() );
        assertEquals( "FM Summary", pages.get( 1 ).summary() );
    }

    @Test
    void anUnresolvedPinDoesNotDropItsResolvedNeighbours() throws IOException {
        page( "A.md", "canonical_id: " + ID_A );
        final MainPageData d = MainPageDataLoader.load( tmp, pins(
                "sections:\n  - label: L\n    pages:\n      - MISSING\n      - " + ID_A + "\n" ) );
        assertEquals( List.of( "A" ), d.sections().get( 0 ).pages().stream().map( MainPageData.Page::slug ).toList() );
        assertTrue( d.warnings().get( 0 ).contains( "MISSING" ) );
    }
}
