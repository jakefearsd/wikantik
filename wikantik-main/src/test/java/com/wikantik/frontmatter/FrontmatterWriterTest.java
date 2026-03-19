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
}
