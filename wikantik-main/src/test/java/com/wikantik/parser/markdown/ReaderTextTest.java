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

import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ReaderTextTest {

    @Test
    void stripsBlockMarkers() {
        assertEquals( "Para\n", ReaderText.derived( "Para ^abc\n" ) );
    }

    @Test
    void textWithoutMarkersIsReturnedAsIs() {
        final String text = "Plain paragraph.\n";
        assertSame( text, ReaderText.derived( text ) );
    }

    @Test
    void nullIsNull() {
        assertNull( ReaderText.derived( null ) );
    }

    @Test
    void anInternalFailureReturnsTheInputUnchanged() {
        final UnaryOperator< String > broken = t -> { throw new IllegalStateException( "invariant breach" ); };
        assertEquals( "Para ^abc\n", ReaderText.derived( "Para ^abc\n", broken ) );
    }

    @Test
    void aCaretInsideFrontmatterIsLeftUntouched() {
        assertEquals( "---\nsummary: see ^x\n---\nBody\n", ReaderText.derived( "---\nsummary: see ^x\n---\nBody ^abc\n" ) );
    }

    @Test
    void aBodyIsNeverReadForFrontmatter() {
        final String body = "---\nPara ^h\n\n---\n\nMore ^m2\n";
        assertEquals( "---\nPara\n\n---\n\nMore\n", ReaderText.derivedBody( body ) );
        assertNull( ReaderText.derivedBody( null ) );
        // as a full text, the same lines are frontmatter: only the body's marker goes
        assertEquals( "---\nPara ^h\n\n---\n\nMore\n", ReaderText.derived( body ) );
    }

    @Test
    void stripsCommentsAndMarkersTogether() {
        assertEquals( "Para  tail\n", ReaderText.derived( "Para %%hidden%% tail ^abc\n" ) );
        assertEquals( "Para  tail\n", ReaderText.derivedBody( "Para %%hidden%% tail ^abc\n" ) );
    }

    @Test
    void aCommentSpanningLinesKeepsTheLineCount() {
        assertEquals( "a\n%%x\ny%%\nb\n".chars().filter( c -> c == '\n' ).count(),
                ReaderText.derived( "a\n%%x\ny%%\nb\n" ).chars().filter( c -> c == '\n' ).count() );
    }

    @Test
    void aPercentPairInFrontmatterIsNotAComment() {
        final String text = "---\nsummary: 100%% sure %% really\n---\nBody %%gone%% here\n";
        assertEquals( "---\nsummary: 100%% sure %% really\n---\nBody  here\n", ReaderText.derived( text ) );
    }

    @Test
    void aCommentHidesAMarkerInsideItAndNeverExposesOne() {
        assertEquals( "Para \n", ReaderText.derived( "Para %%note ^abc%%\n" ) );
    }
}
