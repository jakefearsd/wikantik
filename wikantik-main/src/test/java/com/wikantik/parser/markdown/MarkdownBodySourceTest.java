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
package com.wikantik.parser.markdown;

import com.wikantik.markdown.extensions.math.DisplayMathPreProcessor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The body preparation the Markdown parser and the render-path rule reader share. */
class MarkdownBodySourceTest {

    /** The plugin-syntax normaliser {@code MarkdownParser} used, verbatim: the hand-written one must match it. */
    private static final Pattern WIKI_BRACKET_REF = Pattern.compile( "(\\[\\{[^}]+\\}\\])(?!\\()" );

    private static final String[] FRAGMENTS = { "[{", "[{", "[{ALLOW view a", "}", "]", "}]", "}]", "(", ")", "()", "x", " ",
            "\n", "\n\n", "[", "{", "$$", "\n$$\n", "math", "`", "[{SET a=b}]", "[{ALLOW edit b}]()", "}}]", "]]" };

    private static String oracle( final String body ) {
        return DisplayMathPreProcessor.transform( WIKI_BRACKET_REF.matcher( body ).replaceAll( "$1()" ) );
    }

    @Test
    void theHandWrittenNormaliserIsTheExpressionOnSeededRandomBodies() {
        final Random random = new Random( 0xB0D1L );
        for ( int i = 0; i < 30_000; i++ ) {
            final StringBuilder sb = new StringBuilder();
            for ( int p = random.nextInt( 16 ); p >= 0; p-- ) {
                sb.append( FRAGMENTS[ random.nextInt( FRAGMENTS.length ) ] );
            }
            final String body = sb.toString();
            assertEquals( oracle( body ), MarkdownBodySource.prepare( body ).text(), "input #" + i + ": " + body );
        }
    }

    @Test
    void everyPositionOutsideTheEditsMapsBackToTheSameCharacter() {
        final String body = "a [{ALLOW view x}] b [{P}] c\n\n$$\nm [{Q}] m\n$$\nafter [{ALLOW edit y}] end";
        final MarkdownBodySource.Prepared prepared = MarkdownBodySource.prepare( body );
        final String text = prepared.text();
        for ( final String probe : new String[]{ "a ", "b", "c", "after", "[{ALLOW view", "[{ALLOW edit", "end" } ) {
            final int at = text.indexOf( probe );
            assertEquals( body.indexOf( probe ), prepared.toBody( at ), probe );
        }
        // An inserted "()" maps to where it was inserted: right after the "}]".
        final int inserted = text.indexOf( "}]()" ) + 2;
        assertEquals( body.indexOf( "}]" ) + 2, prepared.toBody( inserted ) );
        assertEquals( body.indexOf( "}]" ) + 2, prepared.toBody( inserted + 1 ) );
        assertEquals( body.indexOf( "}]" ) + 2, prepared.toBody( inserted + 2 ) );
    }

    @Test
    void manyOpenersBeforeOneCloserAreLinear() {
        final String body = "[{a ".repeat( 200_000 ) + "}]";
        assertTimeoutPreemptively( Duration.ofSeconds( 5 ), () -> {
            final String text = MarkdownBodySource.prepare( body ).text();
            assertTrue( text.endsWith( "}]()" ) );
        } );
    }
}
