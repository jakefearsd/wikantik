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

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollabTextTest {

    private static final String EMOJI = "😀"; // U+1F600: 😀

    @Test
    void toLfTurnsEveryLineBreakIntoLf() {
        assertEquals( "a\nb\nc\nd", CollabText.toLf( "a\r\nb\rc\nd" ) );
        assertEquals( "\n\n", CollabText.toLf( "\r\r\n" ) );
        assertEquals( "\n\n", CollabText.toLf( "\n\r" ) );
        assertEquals( "x\n", CollabText.toLf( "x\r" ) );
        assertEquals( "", CollabText.toLf( "" ) );
        final String plain = "no breaks\nhere";
        assertSame( plain, CollabText.toLf( plain ), "LF-only text is returned as is" );
    }

    @Test
    void loneSurrogatesAreFound() {
        assertFalse( CollabText.hasLoneSurrogate( "" ) );
        assertFalse( CollabText.hasLoneSurrogate( "a" + EMOJI + "b𝔸" ) );
        assertTrue( CollabText.hasLoneSurrogate( "a\uD83D" ), "high at the end" );
        assertTrue( CollabText.hasLoneSurrogate( "\uD83Da" ), "high before a non-low" );
        assertTrue( CollabText.hasLoneSurrogate( "\uDE00a" ), "low at the start" );
        assertTrue( CollabText.hasLoneSurrogate( "a\uDE00" ), "low after a non-high" );
        assertTrue( CollabText.hasLoneSurrogate( EMOJI + "\uDE00" ), "low after a complete pair" );
        assertTrue( CollabText.hasLoneSurrogate( new StringBuilder( "\uD83D😀" ) ), "two highs" );
    }

    @Test
    void aBoundaryInsideASurrogatePairIsDetected() {
        final String doc = "a" + EMOJI + "b"; // a=0, high=1, low=2, b=3; length 4
        assertTrue( CollabText.splitsSurrogatePair( doc, ChangeSet.replace( 4, 2, 2, "x" ) ), "insert between the halves" );
        assertTrue( CollabText.splitsSurrogatePair( doc, ChangeSet.replace( 4, 0, 2, "" ) ), "delete ending inside" );
        assertTrue( CollabText.splitsSurrogatePair( doc, ChangeSet.replace( 4, 2, 4, "y" ) ), "replace starting inside" );
        assertFalse( CollabText.splitsSurrogatePair( doc, ChangeSet.replace( 4, 1, 3, "z" ) ), "the whole pair" );
        assertFalse( CollabText.splitsSurrogatePair( doc, ChangeSet.replace( 4, 1, 1, "z" ) ), "before the pair" );
        assertFalse( CollabText.splitsSurrogatePair( doc, ChangeSet.replace( 4, 3, 3, "z" ) ), "after the pair" );
        assertFalse( CollabText.splitsSurrogatePair( doc, ChangeSet.empty( 4 ) ) );
        assertFalse( CollabText.splitsSurrogatePair( "", ChangeSet.replace( 0, 0, 0, "q" ) ) );
        // a kept-span boundary (an unmerged JSON form) is not a change boundary
        assertFalse( CollabText.splitsSurrogatePair( doc,
                ChangeSetJson.fromJson( JsonParser.parseString( "[2, 2]" ).getAsJsonArray() ) ) );
        // a later change splitting a second pair is found too
        final String two = EMOJI + "x" + EMOJI; // 0-1 pair, x=2, 3-4 pair
        assertTrue( CollabText.splitsSurrogatePair( two,
                ChangeSet.replace( 5, 4, 4, "q" ).compose( ChangeSet.replace( 6, 0, 0, "p" ) ) ) );
        assertThrows( IllegalArgumentException.class,
                () -> CollabText.splitsSurrogatePair( "abc", ChangeSet.replace( 4, 0, 0, "x" ) ) );
    }

    @Test
    void adjacentChangesThatTogetherReplaceAPairDoNotSplitIt() {
        // ChangeSet.of produces this shape: two adjacent changes, one per half, together replacing the whole pair
        assertFalse( CollabText.splitsSurrogatePair( EMOJI,
                ChangeSetJson.fromJson( JsonParser.parseString( "[[1, \"x\"], [1, \"y\"]]" ).getAsJsonArray() ) ) );
        // ...but a run of adjacent changes whose outer end is inside a pair does split it
        assertTrue( CollabText.splitsSurrogatePair( "a" + EMOJI,
                ChangeSetJson.fromJson( JsonParser.parseString( "[[1, \"x\"], [1, \"y\"], 1]" ).getAsJsonArray() ) ) );
        assertTrue( CollabText.splitsSurrogatePair( EMOJI,
                ChangeSetJson.fromJson( JsonParser.parseString( "[[1, \"x\"], 1]" ).getAsJsonArray() ) ) );
    }

    @Test
    void contentHashIsSha256HexOfTheLfBody() {
        assertEquals( com.wikantik.api.pages.PageSaveHelper.computeContentHash( "a\nb\n" ),
            CollabText.contentHash( "a\r\nb\r\n" ) );
        assertEquals( CollabText.contentHash( "a\nb\n" ), CollabText.contentHash( "a\r\nb\r\n" ) );
        assertEquals( 64, CollabText.contentHash( "" ).length() );
        assertFalse( CollabText.contentHash( "a\n" ).equals( CollabText.contentHash( "b\n" ) ) );
    }
}
