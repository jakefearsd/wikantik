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
package com.wikantik.work;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileKindsTest {

    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0 };

    private static byte[] ascii( final String s ) {
        return s.getBytes( StandardCharsets.US_ASCII );
    }

    @Test
    void detectsTheFourImageKindsByTheirLeadingBytes() {
        assertEquals( Optional.of( "image/png" ), FileKinds.detect( PNG ) );
        assertEquals( Optional.of( "image/jpeg" ), FileKinds.detect( new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0 } ) );
        assertEquals( Optional.of( "image/gif" ), FileKinds.detect( ascii( "GIF87a...." ) ) );
        assertEquals( Optional.of( "image/gif" ), FileKinds.detect( ascii( "GIF89a...." ) ) );
        assertEquals( Optional.of( "image/webp" ), FileKinds.detect( ascii( "RIFF\0\0\0\0WEBPVP8 " ) ) );
    }

    @Test
    void htmlNamedPngIsNotAnImage() {
        assertEquals( Optional.empty(), FileKinds.detect( ascii( "<html><script>alert(1)</script>" ) ) );
    }

    @Test
    void svgIsNeverDetectedOrInline() {
        assertEquals( Optional.empty(), FileKinds.detect( ascii( "<svg xmlns=\"http://www.w3.org/2000/svg\">" ) ) );
        assertFalse( FileKinds.inline( "image/svg+xml" ) );
    }

    @Test
    void riffThatIsNotWebpIsNotAnImage() {
        assertEquals( Optional.empty(), FileKinds.detect( ascii( "RIFF\0\0\0\0WAVEfmt " ) ) );
    }

    @Test
    void shortOrEmptyInputIsNotAnImage() {
        assertEquals( Optional.empty(), FileKinds.detect( null ) );
        assertEquals( Optional.empty(), FileKinds.detect( new byte[ 0 ] ) );
        assertEquals( Optional.empty(), FileKinds.detect( Arrays.copyOf( PNG, 3 ) ) );
    }

    @Test
    void inlineIsTrueForExactlyTheFour() {
        for ( final String t : new String[] { "image/png", "image/jpeg", "image/gif", "image/webp" } ) {
            assertTrue( FileKinds.inline( t ), t );
        }
        assertFalse( FileKinds.inline( "text/html" ) );
        assertFalse( FileKinds.inline( "application/octet-stream" ) );
        assertFalse( FileKinds.inline( "IMAGE/PNG" ) );
        assertFalse( FileKinds.inline( null ) );
    }

    @Test
    void safeNameStripsPathsAndControls() {
        assertEquals( "passwd", FileKinds.safeName( "../../etc/passwd" ) );
        assertEquals( "shot.png", FileKinds.safeName( "C:\\x\\shot.png" ) );
        assertEquals( "ab.png", FileKinds.safeName( "a\r\nb.png" ) );
        assertEquals( "evilgnp.exe", FileKinds.safeName( "evil\u202Egnp.exe" ) );
        assertEquals( "a\"b.png", FileKinds.safeName( "a\"b.png" ) );
    }

    @Test
    void safeNameOfDotsOrBlankIsEmpty() {
        assertEquals( "", FileKinds.safeName( ".." ) );
        assertEquals( "", FileKinds.safeName( "." ) );
        assertEquals( "", FileKinds.safeName( "   " ) );
        assertEquals( "", FileKinds.safeName( null ) );
    }

    @Test
    void safeNameCapsAt200KeepingTheExtension() {
        final String name = FileKinds.safeName( "a".repeat( 300 ) + ".log" );
        assertEquals( 200, name.length() );
        assertTrue( name.endsWith( ".log" ) );
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource( {
        "31,true", "32,false", "126,false", "127,true", "128,false",
        "8233,false", "8234,true", "8238,true", "8239,false",
        "8293,false", "8294,true", "8297,true", "8298,false" } )
    void onlyControlAndBidiCharactersAreStrippedFromAName( final int codePoint, final boolean stripped ) {
        final String raw = "a" + new String( Character.toChars( codePoint ) ) + "b";
        assertEquals( stripped ? "ab" : raw, FileKinds.safeName( raw ),
            "code point " + codePoint );
    }

    @Test
    void aNameOfExactlyTwoHundredCharactersIsKeptAndTwoHundredAndOneIsCut() {
        assertEquals( "n".repeat( 200 ), FileKinds.safeName( "n".repeat( 200 ) ) );
        assertEquals( "n".repeat( 200 ), FileKinds.safeName( "n".repeat( 201 ) ) );
    }

    @Test
    void anExtensionOfSixteenCharactersIsKeptAndOneOfSeventeenIsDropped() {
        final String keep = "." + "e".repeat( 15 );
        final String longName = "n".repeat( 250 );
        final String kept = FileKinds.safeName( longName + keep );
        assertEquals( 200, kept.length() );
        assertTrue( kept.endsWith( keep ) );
        final String drop = "." + "e".repeat( 16 );
        final String dropped = FileKinds.safeName( longName + drop );
        assertEquals( 200, dropped.length() );
        assertFalse( dropped.endsWith( drop ), "a seventeen character extension is not worth keeping" );
    }

    @Test
    void aWebpNeedsRiffAndWebpAtTheirOwnOffsets() {
        final byte[] exact = new byte[ 12 ];
        System.arraycopy( ascii( "RIFF" ), 0, exact, 0, 4 );
        System.arraycopy( ascii( "WEBP" ), 0, exact, 8, 4 );
        assertEquals( java.util.Optional.of( "image/webp" ), FileKinds.detect( exact ), "twelve bytes is enough" );
        assertEquals( java.util.Optional.empty(), FileKinds.detect( java.util.Arrays.copyOf( exact, 11 ) ) );
    }
}
