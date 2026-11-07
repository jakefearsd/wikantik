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
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hostile and non-canonical inputs for the network-facing {@link ChangeSetJsonParser} (via {@link ChangeSetJson}). */
class ChangeSetJsonParserTest {

    private static ChangeSet parse( final String json ) {
        return ChangeSetJson.fromJson( JsonParser.parseString( json ).getAsJsonArray() );
    }

    private static IllegalArgumentException refused( final String json ) {
        return assertThrows( IllegalArgumentException.class, () -> parse( json ), json );
    }

    // ---- canonical form: CodeMirror's toJSON never emits these ---------------------------------------------

    @Test
    void adjacentKeptSpansAreRefusedNamingTheElement() {
        final IllegalArgumentException e = refused( "[1,2]" );
        assertTrue( e.getMessage().contains( "element 1" ), e.getMessage() );
        assertTrue( e.getMessage().contains( "kept" ), e.getMessage() );
        assertTrue( refused( "[[0,\"a\"],3,4]" ).getMessage().contains( "element 2" ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "[[1],[2]]", "[ [1], [2] ]", "[3,[1],[2]]", "[[1],[2,\"\"]]", "[[1,\"\"],[2]]" } )
    void adjacentPureDeletionsAreRefused( final String json ) {
        final IllegalArgumentException e = refused( json );
        assertTrue( e.getMessage().contains( "deletion" ), e.getMessage() );
    }

    @Test
    void adjacentPureDeletionsNameTheSecondElement() {
        assertTrue( refused( "[3,[1],[2]]" ).getMessage().contains( "element 2" ) );
    }

    @Test
    void canonicalNeighboursAreAccepted() {
        assertEquals( 3, parse( "[1,[1],1]" ).length() );
        assertEquals( 2, parse( "[[1],1]" ).length() );
        assertEquals( "xyb", parse( "[[0,\"x\"],[1,\"y\"],1]" ).apply( "ab" ) );
        assertEquals( "Xc", parse( "[[2,\"X\"],1]" ).apply( "abc" ) );
    }

    // ---- numbers -------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource( strings = { "[1e400]", "[99999999999]", "[2147483648]", "[1.5]", "[-1]", "[1e-400]", "[1E2147483648]",
        "[[1e400]]", "[[-3]]", "[[2.5,\"a\"]]", "[2147483647,[1],2147483647]",
        "[123456789012345678901234567890123456789012345678901234567890]" } )
    void hostileNumbersAreRefusedAsIllegalArgument( final String json ) {
        refused( json );
    }

    @Test
    void integralFloatsAndExponentsAreLengths() {
        assertEquals( 100, parse( "[1e2]" ).length() );
        assertEquals( 3, parse( "[3.0]" ).length() );
    }

    // ---- shape and types -----------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource( strings = { "[[[1]]]", "[[1,[\"a\"]]]", "[[1,2]]", "[[\"1\"]]", "[\"a\"]", "[true]", "[null]",
        "[{}]", "[[]]", "[[1,null]]", "[[1,{}]]", "[[1,true]]", "[[null]]" } )
    void wrongTypesAndNestingAreRefused( final String json ) {
        refused( json );
    }

    @Test
    void nullIsRefused() {
        assertThrows( IllegalArgumentException.class, () -> ChangeSetJson.fromJson( (JsonArray) null ) );
    }

    @Test
    void anEmptyArrayIsTheEmptyDocumentAndDoesNotApplyToANonEmptyOne() {
        final ChangeSet cs = parse( "[]" );
        assertEquals( 0, cs.length() );
        assertEquals( 0, cs.newLength() );
        assertThrows( IllegalArgumentException.class, () -> cs.apply( "abc" ) );
    }

    @Test
    void lineBreaksInsideALineAreRefused() {
        refused( "[[0,\"a\\nb\"]]" );
        refused( "[[0,\"a\\rb\"]]" );
    }

    // ---- size ----------------------------------------------------------------------------------------------

    @Test
    void veryLongInsertLinesAreAcceptedAtTheirExactLength() {
        final String line = "x".repeat( 1_000_000 );
        final JsonArray change = new JsonArray();
        change.add( 0 );
        change.add( line );
        change.add( line );
        final JsonArray json = new JsonArray();
        json.add( change );
        final ChangeSet cs = ChangeSetJson.fromJson( json );
        assertEquals( 2_000_001, cs.newLength() );
        assertEquals( json, ChangeSetJson.toJson( cs ) );
    }

    @Test
    void manyShortLinesAreAccepted() {
        final JsonArray change = new JsonArray();
        change.add( 0 );
        for( int i = 0; i < 100_000; i++ ) {
            change.add( "" );
        }
        final JsonArray json = new JsonArray();
        json.add( change );
        assertEquals( 99_999, ChangeSetJson.fromJson( json ).newLength() );
    }
}
