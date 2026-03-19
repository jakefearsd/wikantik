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
package com.wikantik.frontmatter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FrontmatterParserTest {

    @Test
    void testParseValidFrontmatter() {
        final String text = "---\ntype: concept\ntags: [ai, wiki]\nsummary: \"A test page\"\n---\nThis is the body.";
        final ParsedPage result = FrontmatterParser.parse( text );

        assertEquals( "concept", result.metadata().get( "type" ) );
        assertEquals( List.of( "ai", "wiki" ), result.metadata().get( "tags" ) );
        assertEquals( "A test page", result.metadata().get( "summary" ) );
        assertEquals( "This is the body.", result.body() );
    }

    @Test
    void testParseValidFrontmatterCRLF() {
        final String text = "---\r\ntype: concept\r\ntags: [ai, wiki]\r\n---\r\nThis is the body.\r\n";
        final ParsedPage result = FrontmatterParser.parse( text );

        assertEquals( "concept", result.metadata().get( "type" ) );
        assertEquals( List.of( "ai", "wiki" ), result.metadata().get( "tags" ) );
        assertEquals( "This is the body.\r\n", result.body() );
    }

    @Test
    void testParseNoFrontmatter() {
        final String text = "Just a regular page with no frontmatter.";
        final ParsedPage result = FrontmatterParser.parse( text );

        assertTrue( result.metadata().isEmpty() );
        assertEquals( text, result.body() );
    }

    @Test
    void testParseEmptyFrontmatter() {
        final String text = "---\n---\nBody after empty frontmatter.";
        final ParsedPage result = FrontmatterParser.parse( text );

        assertTrue( result.metadata().isEmpty() );
        assertEquals( "Body after empty frontmatter.", result.body() );
    }

    @Test
    void testParseEmptyFrontmatterCRLF() {
        final String text = "---\r\n---\r\nBody after empty frontmatter.\r\n";
        final ParsedPage result = FrontmatterParser.parse( text );

        assertTrue( result.metadata().isEmpty() );
        assertEquals( "Body after empty frontmatter.\r\n", result.body() );
    }

    @Test
    void testParseMalformedYaml() {
        final String text = "---\n: invalid yaml [[[}\n---\nBody content.";
        final ParsedPage result = FrontmatterParser.parse( text );

        assertTrue( result.metadata().isEmpty() );
        assertEquals( "Body content.", result.body() );
    }

    @Test
    void testParseNestedValues() {
        final String text = "---\nauthor:\n  name: Claude\n  role: AI\n---\nNested content.";
        final ParsedPage result = FrontmatterParser.parse( text );

        @SuppressWarnings( "unchecked" )
        final Map< String, Object > author = ( Map< String, Object > ) result.metadata().get( "author" );
        assertEquals( "Claude", author.get( "name" ) );
        assertEquals( "AI", author.get( "role" ) );
        assertEquals( "Nested content.", result.body() );
    }

    @Test
    void testParseEmptyText() {
        final ParsedPage result = FrontmatterParser.parse( "" );

        assertTrue( result.metadata().isEmpty() );
        assertEquals( "", result.body() );
    }

    @Test
    void testParseNullText() {
        final ParsedPage result = FrontmatterParser.parse( null );

        assertTrue( result.metadata().isEmpty() );
        assertEquals( "", result.body() );
    }

    @Test
    void testParseTripleDashInBody() {
        final String text = "Some text\n---\nMore text after a horizontal rule.";
        final ParsedPage result = FrontmatterParser.parse( text );

        assertTrue( result.metadata().isEmpty() );
        assertEquals( text, result.body() );
    }

    @Test
    void testParseFrontmatterWithMultilineBody() {
        final String text = "---\ntitle: Test\n---\nLine 1\nLine 2\nLine 3";
        final ParsedPage result = FrontmatterParser.parse( text );

        assertEquals( "Test", result.metadata().get( "title" ) );
        assertEquals( "Line 1\nLine 2\nLine 3", result.body() );
    }

    @Test
    void testParseFrontmatterOnlyNoBody() {
        final String text = "---\ntitle: Test\n---\n";
        final ParsedPage result = FrontmatterParser.parse( text );

        assertEquals( "Test", result.metadata().get( "title" ) );
        assertEquals( "", result.body() );
    }
}
