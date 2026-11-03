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
import com.google.gson.JsonObject;
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

class ReaderTextSnapshotCliTest {

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream( buffer, true, StandardCharsets.UTF_8 );

    private String output() {
        out.flush();
        return buffer.toString( StandardCharsets.UTF_8 );
    }

    private Path pages() throws IOException {
        final Path dir = Files.createDirectories( tmp.resolve( "pages" ) );
        Files.writeString( dir.resolve( "Plain.md" ), "---\ntitle: x\n---\nJust text.\n" );
        Files.writeString( dir.resolve( "Commented.md" ), "Before %% hidden %% after\r\n" );
        Files.writeString( dir.resolve( "Tagged.md" ), "A line with #Alpha-Tag in it\n" );
        Files.writeString( dir.resolve( "notes.txt" ), "ignored" );
        return dir;
    }

    private JsonObject snapshot( final Path dir, final String name ) throws IOException {
        final Path json = tmp.resolve( name );
        ReaderTextSnapshotCli.snapshot( dir, json );
        return JsonParser.parseString( Files.readString( json ) ).getAsJsonObject();
    }

    @Test
    void snapshot_records_stripped_hash_length_comments_and_tags() throws IOException {
        final JsonObject snap = snapshot( pages(), "a.json" );

        assertEquals( java.util.Set.of( "Plain", "Commented", "Tagged" ), snap.keySet() );

        final JsonObject plain = snap.getAsJsonObject( "Plain" );
        assertEquals( "Just text.\n".length(), plain.get( "strippedLength" ).getAsInt() );
        assertEquals( 64, plain.get( "stripped" ).getAsString().length() );
        assertEquals( 0, plain.getAsJsonArray( "comments" ).size() );

        final JsonObject commented = snap.getAsJsonObject( "Commented" );
        final JsonArray ranges = commented.getAsJsonArray( "comments" );
        assertEquals( 1, ranges.size() );
        assertEquals( 7, ranges.get( 0 ).getAsJsonArray().get( 0 ).getAsInt() );
        assertEquals( 19, ranges.get( 0 ).getAsJsonArray().get( 1 ).getAsInt() );
        assertEquals( "Before  after\r\n".length(), commented.get( "strippedLength" ).getAsInt() );

        final JsonArray tags = snap.getAsJsonObject( "Tagged" ).getAsJsonArray( "tags" );
        assertEquals( 1, tags.size() );
        assertEquals( "alpha-tag", tags.get( 0 ).getAsString() );
    }

    @Test
    void identical_snapshots_diff_to_nothing_and_exit_zero() throws IOException {
        final Path dir = pages();
        snapshot( dir, "a.json" );
        snapshot( dir, "b.json" );
        assertEquals( 0, ReaderTextSnapshotCli.diff( tmp.resolve( "a.json" ), tmp.resolve( "b.json" ), out ) );
        assertEquals( "", output() );
    }

    @Test
    void a_page_that_shows_more_text_GROWS_and_exits_three() throws IOException {
        final Path dir = pages();
        snapshot( dir, "a.json" );
        // The "new" build no longer treats the comment as hidden: the reader sees more.
        Files.writeString( dir.resolve( "Commented.md" ), "Before hidden after\r\n" );
        snapshot( dir, "b.json" );

        final int exit = ReaderTextSnapshotCli.diff( tmp.resolve( "a.json" ), tmp.resolve( "b.json" ), out );

        assertEquals( 3, exit );
        final String text = output();
        assertTrue( text.startsWith( "GROWS Commented comments 1→0" ), text );
    }

    @Test
    void a_page_that_shows_less_text_SHRINKS_and_exits_zero() throws IOException {
        final Path dir = pages();
        snapshot( dir, "before.json" );
        // The "new" build shows the reader less of this page than the old one did.
        Files.writeString( dir.resolve( "Plain.md" ), "Just\n" );
        snapshot( dir, "after.json" );

        final int exit = ReaderTextSnapshotCli.diff( tmp.resolve( "before.json" ), tmp.resolve( "after.json" ), out );

        assertEquals( 0, exit );
        assertTrue( output().startsWith( "SHRINKS Plain" ), output() );
    }

    @Test
    void pages_in_only_one_snapshot_are_listed_and_do_not_fail_the_diff() throws IOException {
        final Path dir = pages();
        snapshot( dir, "a.json" );
        Files.delete( dir.resolve( "Plain.md" ) );
        Files.writeString( dir.resolve( "Fresh.md" ), "new page\n" );
        snapshot( dir, "b.json" );

        final int exit = ReaderTextSnapshotCli.diff( tmp.resolve( "a.json" ), tmp.resolve( "b.json" ), out );

        assertEquals( 0, exit );
        assertEquals( "ONLY-IN-A Plain\nONLY-IN-B Fresh\n", output().replace( "\r\n", "\n" ) );
    }

    @Test
    void usage_and_io_errors_have_their_own_exit_codes() {
        assertEquals( 1, ReaderTextSnapshotCli.run( new String[] { "bogus" } ) );
        assertEquals( 2, ReaderTextSnapshotCli.run( new String[] { "diff", tmp.resolve( "nope-a.json" ).toString(),
                tmp.resolve( "nope-b.json" ).toString() } ) );
    }

    @Test
    void a_tag_only_change_is_reported_with_the_tag_delta() throws IOException {
        final Path dir = pages();
        snapshot( dir, "a.json" );
        Files.writeString( dir.resolve( "Tagged.md" ), "A line with #Alpha-Tag and #beta in it\n" );
        snapshot( dir, "b.json" );

        final int exit = ReaderTextSnapshotCli.diff( tmp.resolve( "a.json" ), tmp.resolve( "b.json" ), out );

        final String text = output();
        assertFalse( text.isEmpty() );
        assertTrue( text.contains( "+[beta]" ), text );
        assertEquals( 3, exit, "the visible text grew, so this is a GROWS" );
    }
}
