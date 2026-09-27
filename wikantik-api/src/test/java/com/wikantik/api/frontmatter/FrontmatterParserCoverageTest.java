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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the branches of {@link FrontmatterParser} that {@link FrontmatterParserStrictTest}
 * and {@link FrontmatterParserHardeningTest} don't reach: null input, the CRLF empty-block
 * fast path, the no-trailing-newline salvage logic in {@code split()}, and the non-map /
 * malformed-YAML branches of both the graceful and strict loaders.
 */
class FrontmatterParserCoverageTest {

    // --- parse(): null / blank input -----------------------------------------------------

    @Test
    void parse_nullText_returnsEmptyMetadataAndEmptyBody() {
        final ParsedPage parsed = FrontmatterParser.parse( null );
        assertTrue( parsed.metadata().isEmpty() );
        assertEquals( "", parsed.body() );
    }

    // --- split(): CRLF immediate-closing fast path + parse()'s EmptyBlock case -----------

    @Test
    void parse_crlfEmptyFrontmatterBlock_returnsEmptyMetadata() {
        final ParsedPage parsed = FrontmatterParser.parse( "---\r\n---\r\nbody text" );
        assertTrue( parsed.metadata().isEmpty() );
        assertEquals( "body text", parsed.body() );
    }

    // --- parse(): Unclosed case --------------------------------------------------------

    @Test
    void parse_unclosedFrontmatterWithoutSalvage_returnsWholeTextAsBody() {
        // Opens with --- but never closes, and doesn't end in a bare "\n---" either —
        // not even the no-trailing-newline salvage can help.
        final String text = "---\ntitle: Foo\nno closing fence here\n";
        final ParsedPage parsed = FrontmatterParser.parse( text );
        assertTrue( parsed.metadata().isEmpty() );
        assertEquals( text, parsed.body() );
    }

    // --- split(): no-trailing-newline salvage ------------------------------------------

    @Test
    void parse_trailingDashesNoNewlineLf_salvagesBlock() {
        // Ends with "\n---" (LF only, no trailing newline after the closing fence).
        final ParsedPage parsed = FrontmatterParser.parse( "---\ntitle: Foo\n---" );
        assertEquals( "Foo", parsed.metadata().get( "title" ) );
        assertEquals( "", parsed.body() );
    }

    @Test
    void parse_trailingDashesNoNewlineCrlf_salvagesBlock() {
        // Ends with "\r\n---" — exercises the \r walk-back branch distinct from the LF case.
        final ParsedPage parsed = FrontmatterParser.parse( "---\r\ntitle: Foo\r\n---" );
        assertEquals( "Foo", parsed.metadata().get( "title" ) );
        assertEquals( "", parsed.body() );
    }

    @Test
    void parse_trailingDashesTooShortForSalvage_returnsNoFrontmatter() {
        // "---\n---" — after walking back past the closing fence's own newline, the
        // salvaged yaml block would start after it ends, so salvage is refused and the
        // whole text is treated as body with no frontmatter at all.
        final String text = "---\n---";
        final ParsedPage parsed = FrontmatterParser.parse( text );
        assertTrue( parsed.metadata().isEmpty() );
        assertEquals( text, parsed.body() );
    }

    // --- parseYaml(): non-map top level + malformed YAML with a position mark -----------

    @Test
    void parse_nonMapYamlTopLevel_returnsEmptyMetadata() {
        final ParsedPage parsed = FrontmatterParser.parse( "---\njust a plain string\n---\nBody" );
        assertTrue( parsed.metadata().isEmpty() );
        assertEquals( "Body", parsed.body() );
    }

    @Test
    void parse_malformedYamlWithPositionMark_logsAndReturnsEmptyMetadata() {
        // Unclosed flow sequence — SnakeYAML throws a positioned (Marked) parse error.
        final String bad = "---\nfoo: [1, 2\n---\nBody";
        final ParsedPage parsed = FrontmatterParser.parse( bad );
        assertTrue( parsed.metadata().isEmpty(),
                "malformed YAML must degrade to empty metadata, not throw" );
    }

    // --- parseStrict(): null input, blank (non-empty-block) yaml, non-map top level -----

    @Test
    void parseStrict_nullText_returnsEmptyMetadataAndEmptyBody() throws Exception {
        final ParsedPage parsed = FrontmatterParser.parseStrict( null );
        assertTrue( parsed.metadata().isEmpty() );
        assertEquals( "", parsed.body() );
    }

    @Test
    void parseStrict_whitespaceOnlyYamlBlock_returnsEmptyMetadata() throws Exception {
        // A real (non-empty-length) Block whose content is entirely whitespace — distinct
        // from the "---\n---\n" EmptyBlock fast path already covered elsewhere.
        final ParsedPage parsed = FrontmatterParser.parseStrict( "---\n   \n---\nBody" );
        assertTrue( parsed.metadata().isEmpty() );
        assertEquals( "Body", parsed.body() );
    }

    @Test
    void parseStrict_nonMapYamlTopLevel_returnsEmptyMetadata() throws Exception {
        final ParsedPage parsed = FrontmatterParser.parseStrict( "---\njust a plain string\n---\nBody" );
        assertTrue( parsed.metadata().isEmpty() );
        assertEquals( "Body", parsed.body() );
    }

    @Test
    void parseStrict_malformedYamlWithPositionMark_throwsWithLineAndColumn() {
        final String bad = "---\nfoo: [1, 2\n---\nBody";
        final FrontmatterParseException ex = assertThrows( FrontmatterParseException.class,
                () -> FrontmatterParser.parseStrict( bad ) );
        assertTrue( ex.line() > 0, "expected a positive line number — got " + ex.line() );
    }
}
