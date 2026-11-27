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
package com.wikantik.api.markdown;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The pandoc {@code tex_math_dollars} rule, pinned case by case (the renderer and linter both depend on it). */
class InlineMathTest {

    private static Arguments c( final String text, final int start, final int expected ) {
        return Arguments.of( text, start, expected );
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                // simple spans: end is exclusive, just past the closing dollar
                c( "$x$", 0, 3 ),
                c( "a $x+y$ b", 2, 7 ),
                c( "$ab$$", 0, 4 ),
                // opening rules
                c( "$$x$$", 0, -1 ),
                c( "$ x$", 0, -1 ),
                c( "$\tx$", 0, -1 ),
                c( "$", 0, -1 ),
                c( "x$", 1, -1 ),
                // closing rules: whitespace before the closer, digit after it
                c( "$x $", 0, -1 ),
                c( "$x\n$", 0, -1 ),
                c( "$x$5", 0, -1 ),
                c( "$x$9", 0, -1 ),
                c( "$x$0", 0, -1 ),
                c( "$x$a", 0, 3 ),
                c( "$x$ 5", 0, 3 ),
                c( "$5$", 0, 3 ),
                // no closer at all
                c( "$x", 0, -1 ),
                c( "$x y", 0, -1 ),
                // backslash consumes itself and the next char
                c( "$a\\$b$", 0, 6 ),
                c( "$a\\$", 0, -1 ),
                c( "$\\", 0, -1 ),
                c( "$a\\", 0, -1 ),
                c( "$\\$$", 0, 4 ),
                // whitespace inside is fine, one newline is fine, a blank line is not
                c( "$a b$", 0, 5 ),
                c( "$a\nb$", 0, 5 ),
                c( "$a\n\nb$", 0, -1 ),
                c( "$a\n \nb$", 0, -1 ),
                c( "$a\n b\nc$", 0, 8 ),
                c( "$a\r\n\r\nb$", 0, -1 ),
                // a non-whitespace char resets the blank-line counter
                c( "$a\nb\nc$", 0, 7 ) );
    }

    @ParameterizedTest
    @MethodSource( "cases" )
    void matchEnd( final String text, final int start, final int expected ) {
        assertEquals( expected, InlineMath.matchEnd( text, start ), () -> "text=" + text.replace( "\n", "\\n" ) );
    }

    @Test
    void indexPastTheEndOfTheTextIsNoMatch() {
        assertEquals( -1, InlineMath.matchEnd( "$", 0 ) );
        assertEquals( -1, InlineMath.matchEnd( "ab", 5 ) );
    }

    @Test
    void anEmptyCharSequenceNeverMatches() {
        assertEquals( -1, InlineMath.matchEnd( "", 0 ) );
    }

    @Test
    void worksOnAnyCharSequence() {
        assertEquals( 5, InlineMath.matchEnd( new StringBuilder( "$a+b$" ), 0 ) );
    }
}
