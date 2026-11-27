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
package com.wikantik.api.frontmatter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Delimiter edge cases and error positions of {@link FrontmatterParser} (found by mutation testing). */
class FrontmatterParserBoundaryTest {

    @ParameterizedTest
    @ValueSource( strings = { "---\nk: v\n---", "---\r\nk: v\r\n---" } )
    void aClosingDelimiterWithNoTrailingNewlineStillClosesTheBlock( final String text ) throws Exception {
        final ParsedPage relaxed = FrontmatterParser.parse( text );
        assertEquals( Map.of( "k", "v" ), relaxed.metadata() );
        assertEquals( "", relaxed.body() );
        final ParsedPage strict = FrontmatterParser.parseStrict( text );
        assertEquals( Map.of( "k", "v" ), strict.metadata() );
        assertEquals( "", strict.body() );
        assertEquals( text.length(), FrontmatterParser.bodyOffset( text ) );
    }

    @Test
    void anEmptyBlockWhoseClosingDelimiterHasNoNewlineYieldsAnEmptyBody() throws Exception {
        // opener, one blank line, bare closer: the YAML block is empty but the block is real
        final ParsedPage p = FrontmatterParser.parse( "---\n\n---" );
        assertTrue( p.metadata().isEmpty() );
        assertEquals( "", p.body() );
        assertEquals( "", FrontmatterParser.parseStrict( "---\n\n---" ).body() );
    }

    @Test
    void anOpenerImmediatelyFollowedByABareCloserIsNotFrontmatterAtAll() throws Exception {
        final String text = "---\n---";
        assertEquals( text, FrontmatterParser.parse( text ).body() );
        assertEquals( text, FrontmatterParser.parseStrict( text ).body() );
        assertEquals( 0, FrontmatterParser.bodyOffset( text ) );
    }

    @Test
    void anUnclosedBlockIsBodyForParseAndAnErrorForParseStrict() {
        final String text = "---\nk: v\nmore text";
        assertTrue( FrontmatterParser.parse( text ).metadata().isEmpty() );
        assertEquals( text, FrontmatterParser.parse( text ).body() );
        final FrontmatterParseException ex = assertThrows( FrontmatterParseException.class,
                () -> FrontmatterParser.parseStrict( text ) );
        assertEquals( -1, ex.line() );
        assertEquals( -1, ex.column() );
    }

    @Test
    void strictErrorsCarryTheOneBasedLineAndColumnOfTheProblem() {
        // "title: a: b" -- SnakeYAML objects to the second colon, 0-based line 0 / column 8
        final FrontmatterParseException ex = assertThrows( FrontmatterParseException.class,
                () -> FrontmatterParser.parseStrict( "---\ntitle: a: b\n---\nbody" ) );
        assertEquals( 1, ex.line() );
        assertEquals( 9, ex.column() );
    }

    @Test
    void strictErrorLineCountsFromTheFirstYamlLineNotTheDelimiter() {
        final FrontmatterParseException ex = assertThrows( FrontmatterParseException.class,
                () -> FrontmatterParser.parseStrict( "---\nok: 1\nalso: 2\ntitle: a: b\n---\nbody" ) );
        assertEquals( 3, ex.line() );
        assertEquals( 9, ex.column() );
    }

    @Test
    void relaxedParsingOfBadYamlKeepsTheBodyAndDropsTheMetadata() {
        final ParsedPage p = FrontmatterParser.parse( "---\ntitle: a: b\n---\nbody text" );
        assertTrue( p.metadata().isEmpty() );
        assertEquals( "body text", p.body() );
    }

    @Test
    void whitespaceOnlyYamlIsAnEmptyBlockNotAnError() throws Exception {
        final ParsedPage p = FrontmatterParser.parseStrict( "---\n   \n---\nbody" );
        assertTrue( p.metadata().isEmpty() );
        assertEquals( "body", p.body() );
        assertEquals( "body", FrontmatterParser.parse( "---\n   \n---\nbody" ).body() );
    }

    @Test
    void nullAndEmptyTextHaveNoMetadataAndAnEmptyBody() throws Exception {
        for ( final String text : new String[]{ null, "" } ) {
            assertEquals( Map.of(), FrontmatterParser.parse( text ).metadata() );
            assertEquals( "", FrontmatterParser.parse( text ).body() );
            assertEquals( Map.of(), FrontmatterParser.parseStrict( text ).metadata() );
            assertEquals( "", FrontmatterParser.parseStrict( text ).body() );
            assertEquals( 0, FrontmatterParser.bodyOffset( text ) );
        }
    }

    @Test
    void theBodyOffsetPointsAtTheBodyForEveryBlockShape() {
        for ( final String text : new String[]{ "---\nk: v\n---\nbody", "---\r\nk: v\r\n---\r\nbody",
                "---\n---\nbody", "---\r\n---\r\nbody" } ) {
            assertEquals( "body", text.substring( FrontmatterParser.bodyOffset( text ) ), text.replace( "\r", "\\r" ) );
            assertEquals( FrontmatterParser.parse( text ).body(), text.substring( FrontmatterParser.bodyOffset( text ) ) );
        }
    }
}
