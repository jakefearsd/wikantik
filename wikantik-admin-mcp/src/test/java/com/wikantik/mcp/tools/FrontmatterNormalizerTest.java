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
package com.wikantik.mcp.tools;

import com.wikantik.api.frontmatter.FrontmatterParseException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontmatterNormalizerTest {

    @Test
    void normalize_quotedTitleEmbeddedInContentRoundTrips() throws Exception {
        // Agent passes well-formed frontmatter in the content string; the parser
        // recovers it and saveHelper will recompose with FrontmatterWriter.
        final String content = "---\n"
                + "title: \"Foo: Bar\"\n"
                + "type: article\n"
                + "---\n"
                + "body";
        final FrontmatterNormalizer.Normalized n = FrontmatterNormalizer.normalize( content, null );
        assertEquals( "body", n.body() );
        assertEquals( "Foo: Bar", n.metadata().get( "title" ) );
        assertEquals( "article", n.metadata().get( "type" ) );
    }

    @Test
    void normalize_unquotedColonInTitleThrowsWithHint() {
        // The recurring failure mode that prompted this layer.
        final String content = "---\n"
                + "title: Woodworking Joinery: Structural Mechanics\n"
                + "---\n"
                + "body";
        final FrontmatterParseException ex = assertThrows( FrontmatterParseException.class,
                () -> FrontmatterNormalizer.normalize( content, null ),
                "unquoted colon must surface as a parse error so the tool can hint" );
        assertNotNull( ex.getMessage() );
        assertTrue( ex.line() > 0 );
    }

    @Test
    void normalize_explicitMetadataWinsOverEmbedded() throws Exception {
        // MCP contract: when both are supplied, structured metadata wins on conflict.
        final String content = "---\n"
                + "title: \"From Embedded\"\n"
                + "type: article\n"
                + "---\n"
                + "body";
        final Map< String, Object > explicit = new LinkedHashMap<>();
        explicit.put( "title", "From Explicit" );

        final FrontmatterNormalizer.Normalized n = FrontmatterNormalizer.normalize( content, explicit );
        assertEquals( "From Explicit", n.metadata().get( "title" ) );
        assertEquals( "article", n.metadata().get( "type" ),
                "non-conflicting embedded keys must be preserved" );
        assertEquals( "body", n.body() );
    }

    @Test
    void normalize_contentWithoutFrontmatterTreatedAsBody() throws Exception {
        final Map< String, Object > explicit = new LinkedHashMap<>();
        explicit.put( "title", "Foo: Bar" );
        final FrontmatterNormalizer.Normalized n =
                FrontmatterNormalizer.normalize( "just body, no fences", explicit );
        assertEquals( "just body, no fences", n.body() );
        assertEquals( "Foo: Bar", n.metadata().get( "title" ) );
    }

    @Test
    void normalize_nullContentIsSafe() throws Exception {
        final FrontmatterNormalizer.Normalized n = FrontmatterNormalizer.normalize( null, null );
        assertEquals( "", n.body() );
        assertTrue( n.metadata().isEmpty() );
    }

    @Test
    void normalize_outputRoundTripsThroughGracefulParser() throws Exception {
        // End-to-end: feed in the original failure mode (with explicit-quoted variant),
        // recompose via FrontmatterWriter (which is what saveHelper does), and assert
        // that the result re-parses cleanly. This is the invariant that makes the
        // whole layer worth shipping.
        final String content = "---\n"
                + "title: \"Woodworking Joinery: Structural Mechanics\"\n"
                + "tags: [woodworking, joinery]\n"
                + "---\n"
                + "How to cut a mortise and tenon ...";
        final FrontmatterNormalizer.Normalized n = FrontmatterNormalizer.normalize( content, null );
        final String composed = com.wikantik.api.frontmatter.FrontmatterWriter.write(
                n.metadata(), n.body() );
        final ParsedPage parsed = FrontmatterParser.parse( composed );
        assertEquals( "Woodworking Joinery: Structural Mechanics", parsed.metadata().get( "title" ) );
        assertEquals( n.body(), parsed.body() );
    }
}
