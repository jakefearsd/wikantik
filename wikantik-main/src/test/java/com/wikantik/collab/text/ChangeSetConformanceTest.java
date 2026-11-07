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
package com.wikantik.collab.text;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the Java {@link ChangeSet} to the real {@code @codemirror/state} through the shared corpus that
 * {@code wikantik-frontend/scripts/collab-fixtures.mjs} records from the library itself.
 */
class ChangeSetConformanceTest {

    private static final Path FIXTURE =
            Path.of( "..", "wikantik-frontend", "src", "collab", "__fixtures__", "changesets.json" );

    private static JsonObject corpus() throws IOException {
        return JsonParser.parseString( Files.readString( FIXTURE, StandardCharsets.UTF_8 ) ).getAsJsonObject();
    }

    @Test
    void theCorpusIsTheOneThePortWasWrittenAgainst() throws IOException {
        final JsonObject corpus = corpus();
        assertEquals( "@codemirror/state 6.7.6", corpus.get( "library" ).getAsString(),
                "the corpus was regenerated against another library version: re-read the port against it" );
        assertEquals( 1200, corpus.getAsJsonArray( "cases" ).size() );
    }

    @Test
    void everyCaseMatchesTheLibrary() throws IOException {
        final JsonArray cases = corpus().getAsJsonArray( "cases" );
        final List< String > failures = new ArrayList<>();
        int checks = 0;
        for ( int i = 0; i < cases.size(); i++ ) {
            final JsonObject c = cases.get( i ).getAsJsonObject();
            final String doc = CollabText.toLf( c.get( "doc" ).getAsString() );
            final JsonArray aJson = c.getAsJsonArray( "a" );
            final JsonArray bJson = c.getAsJsonArray( "b" );
            final JsonArray cJson = c.getAsJsonArray( "c" );
            final ChangeSet a = ChangeSetJson.fromJson( aJson );
            final ChangeSet b = ChangeSetJson.fromJson( bJson );
            final ChangeSet cs = ChangeSetJson.fromJson( cJson );

            checks += expect( failures, i, "toJson(fromJson(a))", aJson, ChangeSetJson.toJson( a ) );
            checks += expect( failures, i, "toJson(fromJson(b))", bJson, ChangeSetJson.toJson( b ) );
            checks += expect( failures, i, "toJson(fromJson(c))", cJson, ChangeSetJson.toJson( cs ) );
            checks += expect( failures, i, "a.apply(doc)", c.get( "applyA" ).getAsString(), a.apply( doc ) );
            checks += expect( failures, i, "a.compose(c)", c.get( "composeAC" ), ChangeSetJson.toJson( a.compose( cs ) ) );
            checks += expect( failures, i, "b.map(a)", c.get( "mapBA" ), ChangeSetJson.toJson( b.map( a ) ) );
            checks += expect( failures, i, "b.map(a, true)", c.get( "mapBABefore" ),
                    ChangeSetJson.toJson( b.map( a, true ) ) );
            for ( final JsonElement p : c.getAsJsonArray( "positions" ) ) {
                final JsonObject pos = p.getAsJsonObject();
                final int at = pos.get( "pos" ).getAsInt();
                checks += expect( failures, i, "a.mapPos(" + at + ", -1)", pos.get( "left" ).getAsInt(), a.mapPos( at, -1 ) );
                checks += expect( failures, i, "a.mapPos(" + at + ", 1)", pos.get( "right" ).getAsInt(), a.mapPos( at, 1 ) );
            }
        }
        assertTrue( failures.isEmpty(), failures.size() + " of " + checks + " checks differ from @codemirror/state; first: "
                + failures.subList( 0, Math.min( 10, failures.size() ) ) );
        assertTrue( checks > 1200 * 7, "the corpus was not replayed: " + checks + " checks" );
    }

    private static int expect( final List< String > failures, final int caseIndex, final String what,
                               final Object expected, final Object actual ) {
        if ( !expected.equals( actual ) ) {
            failures.add( "case " + caseIndex + " " + what + ": expected " + expected + " but was " + actual );
        }
        return 1;
    }

    @Test
    void malformedJsonIsRejected() {
        // non-array
        assertRejected( null, "array" );
        // negative length, kept and changed
        assertRejected( json( "[-1]" ), "negative" );
        assertRejected( json( "[[-2, \"x\"]]" ), "negative" );
        // wrong element types
        assertRejected( json( "[\"x\"]" ), "element 0" );
        assertRejected( json( "[true]" ), "element 0" );
        assertRejected( json( "[{}]" ), "element 0" );
        assertRejected( json( "[null]" ), "element 0" );
        assertRejected( json( "[1.5]" ), "integer" );
        assertRejected( json( "[3, []]" ), "element 1" );
        assertRejected( json( "[3, [\"x\"]]" ), "element 1" );
        assertRejected( json( "[3, [1, 2]]" ), "element 1" );
        assertRejected( json( "[3, [1, \"a\", null]]" ), "element 1" );
        // an inserted line may not itself hold a line break: the line-array form is the only break
        assertRejected( json( "[[1, \"a\\nb\"]]" ), "line break" );
        assertRejected( json( "[[1, \"a\\rb\"]]" ), "line break" );
        // zero-length sections: CodeMirror's toJSON never emits them, and its compose/map mishandle them
        // ([1,0,2] composed with an insert doubles the insert; a trailing 0 makes compose and map throw)
        assertRejected( json( "[1, 0, 2]" ), "zero-length section at element 1" );
        assertRejected( json( "[3, 0]" ), "zero-length section at element 1" );
        assertRejected( json( "[0]" ), "zero-length section at element 0" );
        assertRejected( json( "[1, [0], 2]" ), "zero-length section at element 1" );
        assertRejected( json( "[[0, \"\"], 3]" ), "zero-length section at element 0" );
        // a document length past what a String can hold
        assertRejected( json( "[2147483647, 1]" ), "too long" );
        assertRejected( json( "[[0, \"a\"], 2147483647]" ), "too long" );

        // insert past end: a set longer than the document it is applied to
        final ChangeSet pastEnd = ChangeSetJson.fromJson( json( "[3, [0, \"x\"]]" ) );
        final IllegalArgumentException e = assertThrows( IllegalArgumentException.class, () -> pastEnd.apply( "ab" ) );
        assertTrue( e.getMessage().contains( "length" ), e.getMessage() );
    }

    private static JsonArray json( final String s ) {
        return JsonParser.parseString( s ).getAsJsonArray();
    }

    private static void assertRejected( final JsonArray json, final String defect ) {
        final IllegalArgumentException e =
                assertThrows( IllegalArgumentException.class, () -> ChangeSetJson.fromJson( json ), String.valueOf( json ) );
        assertTrue( e.getMessage().contains( defect ), "message for " + json + " should name '" + defect + "': "
                + e.getMessage() );
    }
}
