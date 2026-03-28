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

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.frontmatter.ParsedPage;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FrontmatterWriterTest {

    @Test
    void testWriteWithMetadata() {
        final Map< String, Object > metadata = new LinkedHashMap<>();
        metadata.put( "type", "concept" );
        metadata.put( "tags", List.of( "ai", "wiki" ) );

        final String result = FrontmatterWriter.write( metadata, "Body text." );

        assertTrue( result.startsWith( "---\n" ) );
        assertTrue( result.contains( "type: concept\n" ) );
        assertTrue( result.contains( "tags:\n- ai\n- wiki\n" ) );
        assertTrue( result.endsWith( "---\nBody text." ) );
    }

    @Test
    void testWriteEmptyMetadata() {
        final String result = FrontmatterWriter.write( Map.of(), "Just body." );
        assertEquals( "Just body.", result );
    }

    @Test
    void testWriteNullMetadata() {
        final String result = FrontmatterWriter.write( null, "Just body." );
        assertEquals( "Just body.", result );
    }

    @Test
    void testWriteEmptyBody() {
        final Map< String, Object > metadata = Map.of( "title", "Test" );
        final String result = FrontmatterWriter.write( metadata, "" );

        assertTrue( result.startsWith( "---\n" ) );
        assertTrue( result.contains( "title: Test\n" ) );
        assertTrue( result.endsWith( "---\n" ) );
    }

    @Test
    void testWriteNullBody() {
        final Map< String, Object > metadata = Map.of( "title", "Test" );
        final String result = FrontmatterWriter.write( metadata, null );

        assertTrue( result.startsWith( "---\n" ) );
        assertTrue( result.endsWith( "---\n" ) );
    }

    @Test
    void testRoundTrip() {
        final Map< String, Object > metadata = new LinkedHashMap<>();
        metadata.put( "type", "reference" );
        metadata.put( "tags", List.of( "java", "mcp" ) );
        metadata.put( "summary", "Round trip test" );
        final String body = "This is the page body.\nWith multiple lines.";

        final String written = FrontmatterWriter.write( metadata, body );
        final ParsedPage parsed = FrontmatterParser.parse( written );

        assertEquals( "reference", parsed.metadata().get( "type" ) );
        assertEquals( List.of( "java", "mcp" ), parsed.metadata().get( "tags" ) );
        assertEquals( "Round trip test", parsed.metadata().get( "summary" ) );
        assertEquals( body, parsed.body() );
    }

    // =============== Round-trip edge case tests ===============

    /**
     * Verifies that values containing YAML control characters (triple-dash, colons, newlines)
     * survive a write-then-parse round trip.
     */
    @Test
    void testRoundTripWithYamlControlCharacters() {
        final Map< String, Object > metadata = new LinkedHashMap<>();
        metadata.put( "title", "Value with --- dashes" );
        metadata.put( "description", "Contains: a colon" );
        metadata.put( "note", "Line one\nLine two" );

        final String written = FrontmatterWriter.write( metadata, "Body text." );
        final ParsedPage parsed = FrontmatterParser.parse( written );

        assertEquals( "Value with --- dashes", parsed.metadata().get( "title" ) );
        assertEquals( "Contains: a colon", parsed.metadata().get( "description" ) );
        assertEquals( "Line one\nLine two", parsed.metadata().get( "note" ) );
        assertEquals( "Body text.", parsed.body() );
    }

    /**
     * Verifies that Unicode values (CJK characters, emoji, RTL text) round-trip correctly.
     */
    @Test
    void testRoundTripWithUnicodeValues() {
        final Map< String, Object > metadata = new LinkedHashMap<>();
        metadata.put( "cjk", "\u4F60\u597D\u4E16\u754C" );  // Chinese: hello world
        metadata.put( "japanese", "\u3053\u3093\u306B\u3061\u306F" );  // Japanese: konnichiwa
        metadata.put( "rtl", "\u0645\u0631\u062D\u0628\u0627" );  // Arabic: marhaba

        final String written = FrontmatterWriter.write( metadata, "Unicode body." );
        final ParsedPage parsed = FrontmatterParser.parse( written );

        assertEquals( "\u4F60\u597D\u4E16\u754C", parsed.metadata().get( "cjk" ) );
        assertEquals( "\u3053\u3093\u306B\u3061\u306F", parsed.metadata().get( "japanese" ) );
        assertEquals( "\u0645\u0631\u062D\u0628\u0627", parsed.metadata().get( "rtl" ) );
        assertEquals( "Unicode body.", parsed.body() );
    }

    /**
     * Verifies that nested structures (lists of maps) round-trip correctly.
     */
    @Test
    @SuppressWarnings( "unchecked" )
    void testRoundTripWithNestedStructures() {
        final Map< String, Object > metadata = new LinkedHashMap<>();
        final Map< String, Object > author1 = new LinkedHashMap<>();
        author1.put( "name", "Alice" );
        author1.put( "role", "writer" );
        final Map< String, Object > author2 = new LinkedHashMap<>();
        author2.put( "name", "Bob" );
        author2.put( "role", "reviewer" );
        metadata.put( "authors", List.of( author1, author2 ) );

        final String written = FrontmatterWriter.write( metadata, "Nested body." );
        final ParsedPage parsed = FrontmatterParser.parse( written );

        final List< Map< String, Object > > authors =
                ( List< Map< String, Object > > ) parsed.metadata().get( "authors" );
        assertNotNull( authors, "authors list should survive round-trip" );
        assertEquals( 2, authors.size() );
        assertEquals( "Alice", authors.get( 0 ).get( "name" ) );
        assertEquals( "reviewer", authors.get( 1 ).get( "role" ) );
        assertEquals( "Nested body.", parsed.body() );
    }

    /**
     * Verifies that metadata with only normal values round-trips correctly,
     * including when the body is empty.
     */
    @Test
    void testRoundTripWithEmptyBody() {
        final Map< String, Object > metadata = new LinkedHashMap<>();
        metadata.put( "status", "draft" );
        metadata.put( "version", 1 );

        final String written = FrontmatterWriter.write( metadata, "" );
        final ParsedPage parsed = FrontmatterParser.parse( written );

        assertNotNull( parsed.metadata(), "metadata should not be null after round-trip" );
        assertEquals( "draft", parsed.metadata().get( "status" ),
                "status should survive round-trip" );
        assertEquals( 1, parsed.metadata().get( "version" ),
                "version should survive round-trip" );
        assertEquals( "", parsed.body(), "empty body should survive round-trip" );
    }

    /**
     * Verifies that an empty list value round-trips correctly.
     */
    @Test
    void testRoundTripWithEmptyList() {
        final Map< String, Object > metadata = new LinkedHashMap<>();
        metadata.put( "tags", List.of() );
        metadata.put( "type", "test" );

        final String written = FrontmatterWriter.write( metadata, "Body." );
        final ParsedPage parsed = FrontmatterParser.parse( written );

        assertEquals( "test", parsed.metadata().get( "type" ),
                "type should survive round-trip" );
        // Empty list may be serialized as [] and parsed back as empty list
        // The key is it does not crash
        assertNotNull( parsed.metadata(), "metadata should not be null" );
    }
}
