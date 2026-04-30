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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link FrontmatterParser#parseStrict(String)} — the write-side guard that
 * surfaces YAML errors instead of silently dropping metadata. The graceful
 * {@link FrontmatterParser#parse(String)} path keeps existing read-side tests.
 */
class FrontmatterParserStrictTest {

    @Test
    void parseStrict_unquotedColonInTitleThrowsWithLineColumn() {
        // The exact failure mode that prompted this guard: title with a colon
        // confuses SnakeYAML because it parses as a nested mapping start.
        final String bad = "---\n"
                + "title: Woodworking Joinery: Structural Mechanics\n"
                + "---\n"
                + "body";

        final FrontmatterParseException ex = assertThrows( FrontmatterParseException.class,
                () -> FrontmatterParser.parseStrict( bad ),
                "unquoted colon must surface as a strict-parse error" );
        assertNotNull( ex.getMessage(), "exception must carry SnakeYAML's message" );
        assertTrue( ex.line() > 0, "line should be 1-based and positive — got " + ex.line() );
    }

    @Test
    void parseStrict_quotedColonInTitleParsesCleanly() throws Exception {
        final String good = "---\n"
                + "title: \"Woodworking Joinery: Structural Mechanics\"\n"
                + "---\n"
                + "body";

        final ParsedPage parsed = FrontmatterParser.parseStrict( good );
        assertEquals( "Woodworking Joinery: Structural Mechanics", parsed.metadata().get( "title" ) );
        assertEquals( "body", parsed.body() );
    }

    @Test
    void parseStrict_noFrontmatterReturnsEmptyMetadata() throws Exception {
        final ParsedPage parsed = FrontmatterParser.parseStrict( "Just a body, no fences." );
        assertTrue( parsed.metadata().isEmpty() );
        assertEquals( "Just a body, no fences.", parsed.body() );
    }

    @Test
    void parseStrict_emptyFrontmatterReturnsEmptyMetadata() throws Exception {
        final ParsedPage parsed = FrontmatterParser.parseStrict( "---\n---\nbody" );
        assertTrue( parsed.metadata().isEmpty() );
        assertEquals( "body", parsed.body() );
    }

    @Test
    void parseStrict_unclosedFrontmatterThrows() {
        // Page opens with --- but never closes — the graceful parse() falls back
        // to "no frontmatter"; strict mode rejects so the agent learns the page
        // is malformed.
        final String bad = "---\ntitle: Foo\nbody without closing fence";
        final FrontmatterParseException ex = assertThrows( FrontmatterParseException.class,
                () -> FrontmatterParser.parseStrict( bad ) );
        assertTrue( ex.getMessage().contains( "no closing" ),
                "message must explain the missing closing fence — got: " + ex.getMessage() );
    }

    @Test
    void parseStrict_acceptsCrlfLineEndings() throws Exception {
        // JSPWiki normalizes stored text to CRLF — strict parsing must handle that.
        final String good = "---\r\n"
                + "title: \"Foo: Bar\"\r\n"
                + "---\r\n"
                + "body";
        final ParsedPage parsed = FrontmatterParser.parseStrict( good );
        assertEquals( "Foo: Bar", parsed.metadata().get( "title" ) );
    }

    @Test
    void parse_unquotedColonStillDegradesGracefullyForReadSide() {
        // Sanity check that the graceful path is unchanged — readers must keep
        // rendering pages even when the metadata is broken; the write-side guard
        // is the only place that fails loud.
        final String bad = "---\n"
                + "title: Woodworking Joinery: Structural Mechanics\n"
                + "---\n"
                + "body";
        final ParsedPage parsed = FrontmatterParser.parse( bad );
        assertTrue( parsed.metadata().isEmpty(),
                "graceful parse must return empty metadata on YAML failure, not throw" );
    }
}
