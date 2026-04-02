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
package com.wikantik.content;

import com.wikantik.content.WikiToMarkdownConverter.ConversionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

public class WikiToMarkdownConverterTest {

    // ==================== Headings ====================

    @ParameterizedTest
    @CsvSource( delimiter = '→', value = {
        "!!! Large heading   → ### Large heading",
        "!! Medium heading   → ## Medium heading",
        "! Small heading     → # Small heading",
    } )
    void testHeadings( final String wiki, final String expected ) {
        assertEquals( expected.trim(), convert( wiki.trim() ) );
    }

    @Test
    void testHeadingWithInlineFormatting() {
        assertEquals( "# **Bold** heading", convert( "! __Bold__ heading" ) );
    }

    // ==================== Text Formatting ====================

    @Test
    void testBold() {
        assertEquals( "This is **bold** text.", convert( "This is __bold__ text." ) );
    }

    @Test
    void testItalic() {
        assertEquals( "This is *italic* text.", convert( "This is ''italic'' text." ) );
    }

    @Test
    void testInlineCode() {
        assertEquals( "Use `System.out` for output.", convert( "Use {{System.out}} for output." ) );
    }

    @Test
    void testCombinedFormatting() {
        assertEquals( "**bold** and *italic* and `code`",
                      convert( "__bold__ and ''italic'' and {{code}}" ) );
    }

    // ==================== Links ====================

    @Test
    void testWikiLinkWithText() {
        assertEquals( "[Click here](MyPage)", convert( "[Click here|MyPage]" ) );
    }

    @Test
    void testWikiLinkWithUrl() {
        assertEquals( "[Google](http://google.com)", convert( "[Google|http://google.com]" ) );
    }

    @Test
    void testBareWikiLink() {
        assertEquals( "[MyPage]()", convert( "[MyPage]" ) );
    }

    @Test
    void testEscapedBrackets() {
        // Double brackets [[ in JSPWiki are an escape that produces a literal single bracket
        assertEquals( "[Literal]", convert( "[[Literal]" ) );
    }

    // ==================== Lists ====================

    @Test
    void testUnorderedList() {
        assertEquals( "* Item one", convert( "* Item one" ) );
    }

    @Test
    void testNestedUnorderedList() {
        final String wiki = "* Level 1\n** Level 2\n*** Level 3";
        final String expected = "* Level 1\n  * Level 2\n    * Level 3";
        assertEquals( expected, convert( wiki ) );
    }

    @Test
    void testOrderedList() {
        assertEquals( "1. First item", convert( "# First item" ) );
    }

    @Test
    void testNestedOrderedList() {
        final String wiki = "# Level 1\n## Level 2\n### Level 3";
        final String expected = "1. Level 1\n   1. Level 2\n      1. Level 3";
        assertEquals( expected, convert( wiki ) );
    }

    @Test
    void testListWithInlineFormatting() {
        assertEquals( "* **Bold** item with *italic*",
                      convert( "* __Bold__ item with ''italic''" ) );
    }

    // ==================== Tables ====================

    @Test
    void testSimpleTable() {
        final String wiki = "|| Header 1 || Header 2\n| Cell 1 | Cell 2";
        final ConversionResult result = WikiToMarkdownConverter.convert( wiki );
        final String md = result.markdown();
        assertTrue( md.contains( "Header 1" ), "Should contain header" );
        assertTrue( md.contains( "Cell 1" ), "Should contain cell" );
        assertTrue( md.contains( "---" ), "Should contain separator row" );
    }

    @Test
    void testTableWithTrailingSeparators() {
        final String wiki = "|| H1 || H2 ||\n| C1 | C2 |";
        final ConversionResult result = WikiToMarkdownConverter.convert( wiki );
        assertTrue( result.markdown().contains( "H1" ) );
        assertTrue( result.markdown().contains( "C1" ) );
    }

    // ==================== Code Blocks ====================

    @Test
    void testCodeBlock() {
        final String wiki = "{{{\nint x = 42;\nreturn x;\n}}}";
        final String expected = "```\nint x = 42;\nreturn x;\n```";
        assertEquals( expected, convert( wiki ) );
    }

    @Test
    void testCodeBlockPreservesContent() {
        // Content inside code blocks should NOT be converted
        final String wiki = "{{{\n!!!Not a heading\n__Not bold__\n}}}";
        final String expected = "```\n!!!Not a heading\n__Not bold__\n```";
        assertEquals( expected, convert( wiki ) );
    }

    @Test
    void testUnclosedCodeBlock() {
        final String wiki = "{{{\nunclosed code";
        final ConversionResult result = WikiToMarkdownConverter.convert( wiki );
        assertTrue( result.markdown().contains( "```" ), "Should have opening fence" );
        assertTrue( result.warnings().stream().anyMatch( w -> w.contains( "Unclosed code block" ) ),
                    "Should warn about unclosed block" );
    }

    // ==================== Horizontal Rules ====================

    @Test
    void testHorizontalRule() {
        assertEquals( "---", convert( "----" ) );
    }

    @Test
    void testLongHorizontalRule() {
        assertEquals( "---", convert( "----------" ) );
    }

    // ==================== Line Breaks ====================

    @Test
    void testLineBreak() {
        assertEquals( "Line one  and more", convert( "Line one\\\\and more" ) );
    }

    // ==================== Plugins / ACL / Variables ====================

    @Test
    void testPluginSyntax() {
        assertEquals( "[{TableOfContents}]()", convert( "[{TableOfContents}]" ) );
    }

    @Test
    void testPluginWithParams() {
        assertEquals( "[{INSERT CurrentTimePlugin format='yyyy-MM-dd'}]()",
                      convert( "[{INSERT CurrentTimePlugin format='yyyy-MM-dd'}]" ) );
    }

    @Test
    void testAclSyntax() {
        assertEquals( "[{ALLOW view Admin}]()", convert( "[{ALLOW view Admin}]" ) );
    }

    @Test
    void testVariableSyntax() {
        assertEquals( "[{$applicationname}]()", convert( "[{$applicationname}]" ) );
    }

    @Test
    void testSetVariableSyntax() {
        assertEquals( "[{SET alias='MyAlias'}]()", convert( "[{SET alias='MyAlias'}]" ) );
    }

    // ==================== Definition Lists ====================

    @Test
    void testDefinitionList() {
        assertEquals( "**Term**: Definition", convert( ";Term:Definition" ) );
    }

    @Test
    void testDefinitionListComment() {
        assertEquals( ": Just a comment", convert( ";:Just a comment" ) );
    }

    // ==================== Edge Cases ====================

    @Test
    void testEmptyInput() {
        final ConversionResult result = WikiToMarkdownConverter.convert( "" );
        assertEquals( "", result.markdown() );
        assertTrue( result.warnings().isEmpty() );
    }

    @Test
    void testNullInput() {
        final ConversionResult result = WikiToMarkdownConverter.convert( null );
        assertEquals( "", result.markdown() );
        assertTrue( result.warnings().isEmpty() );
    }

    @Test
    void testBlankLines() {
        // Blank lines should be preserved; the converter may normalize trailing newlines
        final String result = convert( "\n\n" );
        assertTrue( result.contains( "\n" ), "Should preserve blank lines" );
    }

    @Test
    void testPlainTextPassthrough() {
        // Plain text without wiki syntax should pass through unchanged
        final String plain = "Just some plain text.\n\nAnother paragraph.";
        assertEquals( plain, convert( plain ) );
    }

    @Test
    void testMarkdownFormattingPassthrough() {
        // Standard Markdown bold/italic should pass through (they don't match wiki patterns)
        final String md = "Some **bold** and *italic* text.";
        assertEquals( md, convert( md ) );
    }

    @Test
    void testMixedContent() {
        final String wiki = "!!! My Page\n\nThis is __bold__ and ''italic''.\n\n* Item 1\n* Item 2\n\n----\n\n[Click|http://example.com]";
        final String expected = "### My Page\n\nThis is **bold** and *italic*.\n\n* Item 1\n* Item 2\n\n---\n\n[Click](http://example.com)";
        assertEquals( expected, convert( wiki ) );
    }

    @Test
    void testPluginBeforeLinkConversion() {
        // Plugin syntax must be converted before bare link syntax to avoid [{Plugin}] being
        // treated as a bare wiki link
        final String wiki = "[{TableOfContents}] and [MyPage]";
        final String expected = "[{TableOfContents}]() and [MyPage]()";
        assertEquals( expected, convert( wiki ) );
    }

    // ==================== Heuristic Detection ====================

    @Test
    void testIsLikelyWikiSyntax_WithWikiContent() {
        final String wiki = "!!! Heading\n\nSome ''italic'' text with [Link|Page].";
        assertTrue( WikiToMarkdownConverter.isLikelyWikiSyntax( wiki ) );
    }

    @Test
    void testIsLikelyWikiSyntax_WithMarkdownContent() {
        final String md = "# Heading\n\nSome *italic* text with [Link](Page).";
        assertFalse( WikiToMarkdownConverter.isLikelyWikiSyntax( md ) );
    }

    @Test
    void testIsLikelyWikiSyntax_WithSingleWeakSignal() {
        // Just bold (__text__) alone shouldn't trigger — it's also valid Markdown
        final String weak = "Some __bold__ text here.";
        assertFalse( WikiToMarkdownConverter.isLikelyWikiSyntax( weak ) );
    }

    @Test
    void testIsLikelyWikiSyntax_NullAndEmpty() {
        assertFalse( WikiToMarkdownConverter.isLikelyWikiSyntax( null ) );
        assertFalse( WikiToMarkdownConverter.isLikelyWikiSyntax( "" ) );
        assertFalse( WikiToMarkdownConverter.isLikelyWikiSyntax( "   " ) );
    }

    @Test
    void testIsLikelyWikiSyntax_WithUnconvertedPlugins() {
        final String wiki = "Some text with [{TableOfContents}] plugin.\n! And a heading.";
        assertTrue( WikiToMarkdownConverter.isLikelyWikiSyntax( wiki ) );
    }

    // ==================== Helper ====================

    private static String convert( final String wiki ) {
        return WikiToMarkdownConverter.convert( wiki ).markdown();
    }
}
