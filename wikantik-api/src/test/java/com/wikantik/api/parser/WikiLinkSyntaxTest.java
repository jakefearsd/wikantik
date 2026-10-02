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
package com.wikantik.api.parser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class WikiLinkSyntaxTest {
    private static WikiLinkSyntax.WikiLinkRef one( final String token ) {
        return WikiLinkSyntax.parse( token ).orElseThrow();
    }

    @Test void plainLinkAliasHeadingAndSamePage() {
        assertEquals( "Page", one( "[[Page]]" ).target() );
        assertEquals( "Page", one( "[[Page]]" ).displayText() );
        final var a = one( "[[Page|The Alias]]" );
        assertEquals( "Page", a.target() );  assertEquals( "The Alias", a.alias() );
        final var h = one( "[[Page#My Heading]]" );
        assertEquals( "My Heading", h.heading() );  assertEquals( "Page > My Heading", h.displayText() );
        final var s = one( "[[#Intro Part]]" );
        assertTrue( s.isSamePage() );  assertEquals( "Intro Part", s.displayText() );
    }

    @Test void tableEscapedPipeSplitsLikeAPlainPipe() {
        final var r = one( "[[Page\\|cell]]" );
        assertEquals( "Page", r.target() );  assertEquals( "cell", r.alias() );
    }

    @Test void embedsAttachmentsAndSizes() {
        final var e = one( "![[Page#H]]" );
        assertTrue( e.embed() );  assertEquals( "Page", e.target() );  assertEquals( "H", e.heading() );
        final var img = one( "![[Owner/pic.png|300x200]]" );
        assertTrue( img.isAttachment() );  assertEquals( "Owner", img.pageName() );
        assertEquals( "pic.png", img.fileName() );  assertArrayEquals( new int[]{ 300, 200 }, img.size() );
        assertArrayEquals( new int[]{ 300, -1 }, one( "![[O/f.png|300]]" ).size() );
        assertNull( one( "[[Page|300 reasons]]" ).size() );
    }

    @Test void leadingWhitespaceEmptyAndBareHashAreNotWikilinks() {
        assertTrue( WikiLinkSyntax.parse( "[[ -f \"$x\" ]]" ).isEmpty() );
        assertTrue( WikiLinkSyntax.parse( "[[ Page]]" ).isEmpty() );
        assertTrue( WikiLinkSyntax.parse( "[[#]]" ).isEmpty() );
        assertTrue( WikiLinkSyntax.parse( "[[a [b] c]]" ).isEmpty() );
    }

    @Test void targetIsTrimmedAtTheEnd() {
        assertEquals( "Page", one( "[[Page  |x]]" ).target() );
        assertEquals( "Page", one( "[[Page ]]" ).target() );
    }

    @Test void findAllSkipsFencedAndInlineCodeAndBackslashEscapes() {
        final String md = "a [[One]] `[[Two]]`\n```\n[[Three]]\n```\n~~~\n![[Four]]\n~~~\n\\[[Five]] ![[Six|x]]";
        assertEquals( List.of( "One", "Six" ),
                WikiLinkSyntax.findAll( md ).stream().map( WikiLinkSyntax.WikiLinkRef::target ).toList() );
    }

    @Test void findAllOffsetsAreAbsoluteAndNameRangeCoversOnlyTheOwner() {
        final String md = "x ![[Owner/f.png|300]]";
        final var r = WikiLinkSyntax.findAll( md ).get( 0 );
        assertEquals( 2, r.start() );  assertEquals( md.length(), r.end() );
        assertEquals( "Owner", md.substring( r.nameFrom(), r.nameTo() ) );
    }

    @Test void replaceAllKeepsTokensTheFunctionDeclines() {
        assertEquals( "A b [[C]]", WikiLinkSyntax.replaceAll( "[[A]] b [[C]]",
                r -> r.target().equals( "A" ) ? "A" : null ) );
    }

    @Test void toPlainTextUsesDisplayTextAndDropsEmbeds() {
        assertEquals( "See Alias and T > H. ", WikiLinkSyntax.toPlainText( "See [[T|Alias]] and [[T#H]]. ![[Other]]" ) );
    }

    private static List< String > targets( final String md ) {
        return WikiLinkSyntax.findAll( md ).stream().map( WikiLinkSyntax.WikiLinkRef::target ).toList();
    }

    @Test void indentedCodeIsMaskedButListContinuationIsNot() {
        assertEquals( List.of(), targets( "para\n\n    [[x]]" ) );
        assertEquals( List.of(), targets( "    [[x]]" ) );
        assertEquals( List.of(), targets( "para\n\n\t[[x]]\n\n    [[y]]" ) );
        assertEquals( List.of( "x" ), targets( "- item\n\n    [[x]]" ) );
        assertEquals( List.of( "x" ), targets( "para\n    [[x]]" ) );
    }

    @Test void fencesNestedInListItemsMaskTheirContent() {
        assertEquals( List.of( "B" ), targets( "- a\n\n    ```\n    [[A]]\n    ```\n\n[[B]]" ) );
        assertEquals( List.of( "B" ), targets( "1. step\n   ```\n   [[A]]\n   ```\n[[B]]" ) );
        assertEquals( List.of( "B" ), targets( "- a\n  - b\n\n      ~~~\n      [[A]]\n      ~~~\n[[B]]" ) );
        // outside a list, a 4-space-indented fence is indented code (still masked) and ends with the code block
        assertEquals( List.of( "B" ), targets( "text\n\n    ```\n    [[A]]\n    ```\n\n[[B]]" ) );
    }

    @Test void fenceInfoStringAndClosingRules() {
        assertEquals( List.of( "A" ), targets( "```foo``` [[A]]" ) );
        assertEquals( List.of(), targets( "```\n```java\n[[A]]\n```\n" ) );
        assertEquals( List.of( "B" ), targets( "````\n```\n[[A]]\n````\n[[B]]" ) );
        assertEquals( List.of( "B" ), targets( "   ```\n[[A]]\n   ```\n[[B]]" ) );
        assertEquals( List.of(), targets( "```\n[[A]]" ) );
    }

    @Test void escapes() {
        assertEquals( List.of( "x" ), targets( "\\\\[[x]]" ) );
        final var r = WikiLinkSyntax.findAll( "\\![[x]]" ).get( 0 );
        assertEquals( "x", r.target() );  assertEquals( false, r.embed() );  assertEquals( 2, r.start() );
    }

    @Test void edgeCases() {
        final var crlf = WikiLinkSyntax.findAll( "a\r\n[[T]]" ).get( 0 );
        assertEquals( 3, crlf.start() );  assertEquals( 8, crlf.end() );
        assertEquals( List.of(), targets( "``[[x]]``" ) );
        assertEquals( "a", one( "[[a]]" ).target() );
        assertEquals( "a", WikiLinkSyntax.findAll( "[[a]]]" ).get( 0 ).target() );
        assertEquals( "b|c", one( "[[a|b|c]]" ).alias() );
        final var cell = WikiLinkSyntax.findAll( "[[Page\\|cell]]" ).get( 0 );
        assertEquals( "cell", cell.alias() );
        final var h = one( "[[T#H|A]]" );
        assertEquals( "T", "[[T#H|A]]".substring( h.nameFrom(), h.nameTo() ) );
        final var s = one( "[[#H]]" );
        assertEquals( s.nameFrom(), s.nameTo() );
    }
}
