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
package com.wikantik.extractcli.configref;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WRITE / CHECK modes of {@link GenerateConfigReferenceCli#run} against a throw-away repo root. */
class GenerateConfigReferenceCliRunTest {

    @TempDir
    Path root;

    private Path mainDoc;
    private Path wikiDoc;

    @BeforeEach
    void layOutRepo() throws IOException {
        put( "wikantik-main/src/main/resources/ini/wikantik.properties",
             "# [Ontology]\n#  Master switch.\n#  Type: boolean\nwikantik.ontology.enabled = true\n" );
        put( "wikantik-admin-mcp/src/main/resources/wikantik-mcp.properties",
             "# [MCP access]\n#  Allow unauthenticated calls.\n#  Type: boolean\nmcp.access.allowUnrestricted = false\n" );
        put( "wikantik-tools/src/main/resources/wikantik-tools.properties",
             "# [Tools]\n#  Tool switch.\n#  Type: boolean\ntools.enabled = true\n" );
        Files.createDirectories( root.resolve( "docs/wikantik-pages" ) );
        mainDoc = root.resolve( "docs/ConfigurationReference.md" );
        wikiDoc = root.resolve( "docs/wikantik-pages/WikantikConfigurationReference.md" );
    }

    private void put( final String rel, final String content ) throws IOException {
        final Path p = root.resolve( rel );
        Files.createDirectories( p.getParent() );
        Files.writeString( p, content, StandardCharsets.ISO_8859_1 );
    }

    private GenerateConfigReferenceCli.Result run( final GenerateConfigReferenceCli.Mode mode ) throws IOException {
        return new GenerateConfigReferenceCli().run( root, mode );
    }

    @Test
    void writeModeCreatesBothDocsAndNamesThemInTheSummary() throws IOException {
        final GenerateConfigReferenceCli.Result r = run( GenerateConfigReferenceCli.Mode.WRITE );
        assertEquals( 0, r.exitCode() );
        assertEquals( "wrote " + mainDoc + " and " + wikiDoc, r.summary() );
        final String main = Files.readString( mainDoc );
        assertTrue( main.contains( "wikantik.ontology.enabled" ), main );
        assertTrue( main.contains( "mcp.access.allowUnrestricted" ), main );
        assertTrue( main.contains( "tools.enabled" ), main );
        assertTrue( Files.readString( wikiDoc ).contains( "wikantik.ontology.enabled" ) );
    }

    @Test
    void checkModeAfterWriteIsInSync() throws IOException {
        run( GenerateConfigReferenceCli.Mode.WRITE );
        final GenerateConfigReferenceCli.Result r = run( GenerateConfigReferenceCli.Mode.CHECK );
        assertEquals( 0, r.exitCode() );
        assertEquals( "in sync", r.summary() );
    }

    @Test
    void checkModeWithNoDocsOnDiskReportsBothStale() throws IOException {
        final GenerateConfigReferenceCli.Result r = run( GenerateConfigReferenceCli.Mode.CHECK );
        assertEquals( 1, r.exitCode() );
        assertEquals( "stale: " + mainDoc + ", " + wikiDoc
                + " (run bin/config-reference.sh --write to regenerate)", r.summary() );
        assertFalse( Files.exists( mainDoc ), "CHECK must never write" );
    }

    @Test
    void checkModeNamesOnlyTheMainDocWhenOnlyItIsStale() throws IOException {
        run( GenerateConfigReferenceCli.Mode.WRITE );
        Files.writeString( mainDoc, Files.readString( mainDoc ) + "hand edit\n" );
        final GenerateConfigReferenceCli.Result r = run( GenerateConfigReferenceCli.Mode.CHECK );
        assertEquals( 1, r.exitCode() );
        assertEquals( "stale: " + mainDoc + " (run bin/config-reference.sh --write to regenerate)", r.summary() );
    }

    @Test
    void checkModeNamesOnlyTheWikiDocWhenOnlyItIsStale() throws IOException {
        run( GenerateConfigReferenceCli.Mode.WRITE );
        Files.writeString( wikiDoc, Files.readString( wikiDoc ) + "hand edit\n" );
        final GenerateConfigReferenceCli.Result r = run( GenerateConfigReferenceCli.Mode.CHECK );
        assertEquals( 1, r.exitCode() );
        assertEquals( "stale: " + wikiDoc + " (run bin/config-reference.sh --write to regenerate)", r.summary() );
    }

    @Test
    void aMissingWikiDocAloneIsStale() throws IOException {
        run( GenerateConfigReferenceCli.Mode.WRITE );
        Files.delete( wikiDoc );
        assertEquals( 1, run( GenerateConfigReferenceCli.Mode.CHECK ).exitCode() );
    }

    @Test
    void theWikiDocsGenerationDateIsIgnoredButTheMainDocIsComparedExactly() throws IOException {
        run( GenerateConfigReferenceCli.Mode.WRITE );
        final String wiki = Files.readString( wikiDoc );
        assertTrue( wiki.contains( "date: '" ), "wiki frontmatter carries a date line: " + wiki );
        Files.writeString( wikiDoc, wiki.replaceAll( "(?m)^date: '.*$", "date: '1999-01-01'" ) );
        assertEquals( 0, run( GenerateConfigReferenceCli.Mode.CHECK ).exitCode() );
    }

    @Test
    void crlfLineEndingsOnDiskDoNotMakeADocStale() throws IOException {
        run( GenerateConfigReferenceCli.Mode.WRITE );
        Files.writeString( mainDoc, Files.readString( mainDoc ).replace( "\n", "\r\n" ) );
        assertEquals( 0, run( GenerateConfigReferenceCli.Mode.CHECK ).exitCode() );
    }

    // ---- stripDateLine / normalize ----

    @Test
    void stripDateLineDropsOnlyLinesStartingWithTheDateMarker() {
        assertEquals( "a\nb\n  date: 'indented'\n",
                      GenerateConfigReferenceCli.stripDateLine( "a\ndate: '2026-01-01'\nb\n  date: 'indented'\n" ) );
    }

    @Test
    void stripDateLinePreservesTheTrailingNewline() {
        assertEquals( "x\n", GenerateConfigReferenceCli.stripDateLine( "x\n" ) );
        assertEquals( "x", GenerateConfigReferenceCli.stripDateLine( "x" ) );
    }

    @Test
    void normalizeForcesLfDropsLeadingBlankAndCollapsesBlankRuns() {
        assertEquals( "a\n\nb\n", GenerateConfigReferenceCli.normalize( "\r\na\r\n\r\n\r\n\r\nb" ) );
    }

    @Test
    void normalizeTurnsNullIntoASingleNewlineAndKeepsAnExistingOne() {
        assertEquals( "\n", GenerateConfigReferenceCli.normalize( null ) );
        assertEquals( "a\n", GenerateConfigReferenceCli.normalize( "a\n" ) );
    }

    @Test
    void normalizeLeavesAnInteriorBlankLineAlone() {
        assertEquals( "a\n\nb\n", GenerateConfigReferenceCli.normalize( "a\n\nb\n" ) );
    }
}
