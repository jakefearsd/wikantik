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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FrontmatterWriterTest {

    @Test
    void writesBodyOnlyWhenMetadataNullOrEmpty() {
        assertEquals( "the body", FrontmatterWriter.write( null, "the body" ) );
        assertEquals( "the body", FrontmatterWriter.write( Map.of(), "the body" ) );
    }

    @Test
    void nullBodyBecomesEmptyString() {
        assertEquals( "", FrontmatterWriter.write( null, null ) );
        assertEquals( "", FrontmatterWriter.write( Map.of(), null ) );
    }

    @Test
    void emitsDelimitedFrontmatterBlockBeforeBody() {
        final String out = FrontmatterWriter.write( Map.of( "title", "Hello" ), "body text" );
        assertTrue( out.startsWith( "---\n" ), out );
        assertTrue( out.contains( "title: Hello" ), out );
        assertTrue( out.contains( "\n---\n" ), out );
        assertTrue( out.endsWith( "body text" ), out );
    }

    @Test
    void roundTripsThroughFrontmatterParser() {
        final Map< String, Object > meta = new LinkedHashMap<>();
        meta.put( "title", "Round Trip" );
        meta.put( "cluster", "Reference" );

        final ParsedPage parsed = FrontmatterParser.parse(
                FrontmatterWriter.write( meta, "Body content." ) );

        assertEquals( meta, parsed.metadata() );
        assertEquals( "Body content.", parsed.body() );
    }

    @Test
    void writeLikeUsesTheOriginalsCrlfLineEndingsForTheFrontmatter() {
        final Map< String, Object > meta = new LinkedHashMap<>();
        meta.put( "title", "T" );
        meta.put( "tags", java.util.List.of( "a", "b" ) );
        final String out = FrontmatterWriter.writeLike( "old\r\ntext\r\n", meta, "body\r\n" );
        assertEquals( "---\r\ntitle: T\r\ntags:\r\n- a\r\n- b\r\n---\r\nbody\r\n", out );
    }

    @Test
    void writeLikeKeepsLfForLfOriginals() {
        final Map< String, Object > meta = new LinkedHashMap<>();
        meta.put( "title", "T" );
        assertEquals( FrontmatterWriter.write( meta, "body\n" ), FrontmatterWriter.writeLike( "old\ntext\n", meta, "body\n" ) );
        assertEquals( FrontmatterWriter.write( meta, "body" ), FrontmatterWriter.writeLike( null, meta, "body" ) );
    }

    @Test
    void writeUnfoldedKeepsALongScalarOnOneLine() {
        final String rule = "[{ALLOW edit Bob, Alice, Charlie, SomeoneWithAVeryLongLoginName, AnotherQuiteLongName, More}]";
        final String text = FrontmatterWriter.writeUnfolded( Map.of( "acl", rule ), "body" );
        assertTrue( text.contains( rule ), text );
        assertTrue( FrontmatterWriter.write( Map.of( "acl", rule ), "body" ).indexOf( rule ) < 0,
            "the stored form folds it (why the scanned form must not)" );
    }

    @Test
    void writeUnfoldedKeepsALongValueOnOneLine() {
        final String longSummary = "word ".repeat( 60 ).trim();
        final String out = FrontmatterWriter.writeUnfolded( java.util.Map.of( "summary", longSummary ), "body" );
        assertEquals( "---\nsummary: " + longSummary + "\n---\nbody", out );
    }

    @Test
    void writeFoldsALongValueAndTheFoldReadsBackIdentically() {
        final String longSummary = "word ".repeat( 60 ).trim();
        final String out = FrontmatterWriter.write( java.util.Map.of( "summary", longSummary ), "body" );
        final String yaml = out.substring( 4, out.indexOf( "---\nbody" ) );
        assertTrue( yaml.lines().count() > 1, "a 300-character scalar is folded" );
        assertTrue( yaml.lines().skip( 1 ).allMatch( l -> l.startsWith( "  " ) ), "continuation lines are indented: " + yaml );
        assertEquals( longSummary, FrontmatterParser.parse( out ).metadata().get( "summary" ) );
    }

    @Test
    void listsAreWrittenInBlockStyleOnePerLine() {
        final String out = FrontmatterWriter.write( java.util.Map.of( "tags", java.util.List.of( "a", "b" ) ), "" );
        assertEquals( "---\ntags:\n- a\n- b\n---\n", out );
    }

    @Test
    void aNullBodyIsWrittenAsEmptyAfterTheBlockAndAloneWhenThereIsNoMetadata() {
        assertEquals( "---\nk: v\n---\n", FrontmatterWriter.write( java.util.Map.of( "k", "v" ), null ) );
        assertEquals( "", FrontmatterWriter.write( java.util.Map.of(), null ) );
        assertEquals( "body", FrontmatterWriter.write( null, "body" ) );
    }

    @Test
    void writeLikeGivesTheBlockTheOriginalsLineEndings() {
        assertEquals( "---\r\nk: v\r\n---\r\nbody", FrontmatterWriter.writeLike( "x\r\ny", java.util.Map.of( "k", "v" ), "body" ) );
        assertEquals( "---\nk: v\n---\nbody", FrontmatterWriter.writeLike( "x\ny", java.util.Map.of( "k", "v" ), "body" ) );
        assertEquals( "---\nk: v\n---\nbody", FrontmatterWriter.writeLike( null, java.util.Map.of( "k", "v" ), "body" ) );
    }
}
