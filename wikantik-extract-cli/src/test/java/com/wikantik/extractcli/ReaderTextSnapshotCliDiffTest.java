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

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verdicts, exposure geometry and exit codes of {@link ReaderTextSnapshotCli}, driven by hand-written snapshots. */
class ReaderTextSnapshotCliDiffTest {

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream( buffer, true, StandardCharsets.UTF_8 );

    private String output() {
        return buffer.toString( StandardCharsets.UTF_8 ).replace( "\r\n", "\n" );
    }

    private static JsonArray ranges( final String json ) {
        return JsonParser.parseString( json ).getAsJsonArray();
    }

    private static String entry( final String name, final String body, final String stripped, final int length,
                                 final String comments, final String tags ) {
        return "\"" + name + "\":{" + ( body == null ? "" : "\"body\":\"" + body + "\"," )
             + "\"stripped\":\"" + stripped + "\",\"strippedLength\":" + length
             + ",\"comments\":" + comments + ",\"tags\":" + tags + "}";
    }

    private Path snap( final String file, final String... entries ) throws IOException {
        final Path p = tmp.resolve( file );
        Files.writeString( p, "{" + String.join( ",", entries ) + "}" );
        return p;
    }

    // ---- exposes() geometry ----

    @Test
    void exposesIsFalseWhenEveryHiddenCharacterStaysHidden() {
        assertFalse( ReaderTextSnapshotCli.exposes( ranges( "[[0,10]]" ), ranges( "[[0,10]]" ) ) );
        assertFalse( ReaderTextSnapshotCli.exposes( ranges( "[[0,10]]" ), ranges( "[[0,12]]" ) ) );
        assertFalse( ReaderTextSnapshotCli.exposes( ranges( "[]" ), ranges( "[]" ) ) );
    }

    @Test
    void exposesIsTrueWhenAHiddenCharacterBecomesVisible() {
        assertTrue( ReaderTextSnapshotCli.exposes( ranges( "[[0,10]]" ), ranges( "[]" ) ) );
        assertTrue( ReaderTextSnapshotCli.exposes( ranges( "[[0,10]]" ), ranges( "[[0,9]]" ) ) );
        assertTrue( ReaderTextSnapshotCli.exposes( ranges( "[[0,10]]" ), ranges( "[[1,10]]" ) ) );
    }

    @Test
    void anAfterRangeThatEndsExactlyWhereTheBeforeRangeStartsCoversNothing() {
        assertTrue( ReaderTextSnapshotCli.exposes( ranges( "[[5,10]]" ), ranges( "[[0,5]]" ) ) );
    }

    @Test
    void anEmptyAfterRangeCoversNothing() {
        assertTrue( ReaderTextSnapshotCli.exposes( ranges( "[[0,5]]" ), ranges( "[[0,0]]" ) ) );
    }

    @Test
    void anEmptyBeforeRangeHidesNothingSoNothingCanBeExposed() {
        assertFalse( ReaderTextSnapshotCli.exposes( ranges( "[[3,3]]" ), ranges( "[]" ) ) );
    }

    @Test
    void adjacentAfterRangesTogetherCoverTheBeforeRangeWhateverTheirOrder() {
        assertFalse( ReaderTextSnapshotCli.exposes( ranges( "[[0,10]]" ), ranges( "[[0,6],[6,10]]" ) ) );
        assertFalse( ReaderTextSnapshotCli.exposes( ranges( "[[0,10]]" ), ranges( "[[6,10],[0,6]]" ) ),
                     "after ranges must be sorted before they are walked" );
    }

    @Test
    void aGapBetweenAfterRangesExposesTheGap() {
        assertTrue( ReaderTextSnapshotCli.exposes( ranges( "[[0,10]]" ), ranges( "[[0,4],[6,10]]" ) ) );
    }

    @Test
    void oneRangeUnhiddenWhileAnotherIsNewlyHiddenStillExposes() {
        assertTrue( ReaderTextSnapshotCli.exposes( ranges( "[[0,5]]" ), ranges( "[[10,15]]" ) ) );
    }

    // ---- diff verdicts ----

    @Test
    void sameBodyWithAnUnhiddenCommentEXPOSESEvenWhenTheTextShrinks() throws IOException {
        final Path a = snap( "a.json", entry( "P", "b1", "s1", 100, "[[0,10]]", "[]" ) );
        final Path b = snap( "b.json", entry( "P", "b1", "s2", 90, "[]", "[]" ) );
        assertEquals( ReaderTextSnapshotCli.EXIT_GROWS, ReaderTextSnapshotCli.diff( a, b, out ) );
        assertEquals( "EXPOSES P comments 1→0 tags +[] -[]\n", output() );
    }

    @Test
    void differentBodiesAreJudgedByLengthAlone() throws IOException {
        final Path a = snap( "a.json", entry( "Up", "b1", "s1", 10, "[]", "[]" ),
                                       entry( "Down", "b1", "s1", 10, "[]", "[]" ),
                                       entry( "Flat", "b1", "s1", 10, "[]", "[]" ) );
        final Path b = snap( "b.json", entry( "Up", "b2", "s2", 11, "[]", "[]" ),
                                       entry( "Down", "b2", "s2", 9, "[]", "[]" ),
                                       entry( "Flat", "b2", "s2", 10, "[]", "[]" ) );
        assertEquals( ReaderTextSnapshotCli.EXIT_GROWS, ReaderTextSnapshotCli.diff( a, b, out ) );
        assertEquals( "SHRINKS Down comments 0→0 tags +[] -[]\n"
                    + "SAME-LENGTH Flat comments 0→0 tags +[] -[]\n"
                    + "GROWS Up comments 0→0 tags +[] -[]\n", output() );
    }

    @Test
    void shrinkingOrSameLengthAloneExitsZero() throws IOException {
        final Path a = snap( "a.json", entry( "Down", "b1", "s1", 10, "[]", "[]" ),
                                       entry( "Flat", "b1", "s1", 10, "[]", "[]" ) );
        final Path b = snap( "b.json", entry( "Down", "b2", "s2", 9, "[]", "[]" ),
                                       entry( "Flat", "b2", "s2", 10, "[]", "[]" ) );
        assertEquals( 0, ReaderTextSnapshotCli.diff( a, b, out ) );
    }

    @Test
    void snapshotsWithoutABodyHashAreTreatedAsDifferingBodies() throws IOException {
        final Path a = snap( "a.json", entry( "P", null, "s1", 10, "[[0,5]]", "[]" ) );
        final Path b = snap( "b.json", entry( "P", null, "s2", 10, "[]", "[]" ) );
        assertEquals( 0, ReaderTextSnapshotCli.diff( a, b, out ) );
        assertEquals( "SAME-LENGTH P comments 1→0 tags +[] -[]\n", output() );
    }

    @Test
    void aTagOnlyChangeIsReportedWithAddedAndRemovedTags() throws IOException {
        final Path a = snap( "a.json", entry( "P", "b1", "s1", 10, "[]", "[\"old\",\"keep\"]" ) );
        final Path b = snap( "b.json", entry( "P", "b1", "s1", 10, "[]", "[\"keep\",\"new\"]" ) );
        assertEquals( 0, ReaderTextSnapshotCli.diff( a, b, out ) );
        assertEquals( "SAME-LENGTH P comments 0→0 tags +[new] -[old]\n", output() );
    }

    @Test
    void aCommentCountChangeAloneIsReported() throws IOException {
        final Path a = snap( "a.json", entry( "P", "b1", "s1", 10, "[[0,2]]", "[]" ) );
        final Path b = snap( "b.json", entry( "P", "b1", "s1", 10, "[[0,2],[4,6]]", "[]" ) );
        assertEquals( 0, ReaderTextSnapshotCli.diff( a, b, out ) );
        assertEquals( "SAME-LENGTH P comments 1→2 tags +[] -[]\n", output() );
    }

    @Test
    void anUnchangedPageIsSilentAndPagesPresentOnOneSideAreNamed() throws IOException {
        final Path a = snap( "a.json", entry( "Same", "b1", "s1", 10, "[]", "[]" ),
                                       entry( "Zed", "b1", "s1", 10, "[]", "[]" ),
                                       entry( "Amy", "b1", "s1", 10, "[]", "[]" ) );
        final Path b = snap( "b.json", entry( "Same", "b1", "s1", 10, "[]", "[]" ),
                                       entry( "Yan", "b1", "s1", 10, "[]", "[]" ),
                                       entry( "Bob", "b1", "s1", 10, "[]", "[]" ) );
        assertEquals( 0, ReaderTextSnapshotCli.diff( a, b, out ) );
        assertEquals( "ONLY-IN-A Amy\nONLY-IN-A Zed\nONLY-IN-B Bob\nONLY-IN-B Yan\n", output() );
    }

    // ---- run(): argument handling and exit codes ----

    private int runWith( final String[] args, final ByteArrayOutputStream err ) {
        final PrintStream originalErr = System.err;
        final PrintStream originalOut = System.out;
        System.setErr( new PrintStream( err, true, StandardCharsets.UTF_8 ) );
        System.setOut( out );
        try {
            return ReaderTextSnapshotCli.run( args );
        } finally {
            System.setErr( originalErr );
            System.setOut( originalOut );
        }
    }

    @Test
    void runSnapshotWritesTheFileAndExitsZero() throws IOException {
        final Path dir = Files.createDirectories( tmp.resolve( "pages" ) );
        Files.writeString( dir.resolve( "A.md" ), "hello\n" );
        final Path target = tmp.resolve( "out.json" );
        assertEquals( 0, runWith( new String[]{ "snapshot", dir.toString(), target.toString() }, new ByteArrayOutputStream() ) );
        assertTrue( Files.readString( target ).contains( "\"A\"" ) );
    }

    @Test
    void runDiffReturnsTheDiffExitCodeAndPrintsToStdout() throws IOException {
        final Path a = snap( "a.json", entry( "P", "b1", "s1", 10, "[]", "[]" ) );
        final Path b = snap( "b.json", entry( "P", "b2", "s2", 20, "[]", "[]" ) );
        assertEquals( 3, runWith( new String[]{ "diff", a.toString(), b.toString() }, new ByteArrayOutputStream() ) );
        assertEquals( "GROWS P comments 0→0 tags +[] -[]\n", output() );
    }

    @Test
    void runWithAMissingInputExitsTwoAndNamesTheFailure() {
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int code = runWith( new String[]{ "snapshot", tmp.resolve( "nope" ).toString(), tmp.resolve( "o.json" ).toString() }, err );
        assertEquals( 2, code );
        assertTrue( err.toString( StandardCharsets.UTF_8 ).startsWith( "ReaderTextSnapshotCli failed: " ), err.toString() );
    }

    @Test
    void runWithBadArgumentsExitsOneWithTheUsageText() {
        for ( final String[] args : new String[][]{ {}, { "snapshot", "a" }, { "diff", "a", "b", "c" }, { "bogus", "a", "b" } } ) {
            final ByteArrayOutputStream err = new ByteArrayOutputStream();
            assertEquals( 1, runWith( args, err ), String.join( " ", args ) );
            assertTrue( err.toString( StandardCharsets.UTF_8 ).startsWith( "usage: ReaderTextSnapshotCli snapshot <pagesDir> <out.json> | diff <a.json> <b.json>" ),
                        err.toString() );
        }
    }
}
