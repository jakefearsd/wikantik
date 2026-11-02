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
package com.wikantik.parser.markdown;

import com.wikantik.auth.acl.AclDirectiveText;
import com.wikantik.parser.markdown.BlockIds.Block;
import com.wikantik.parser.markdown.BlockIds.Kind;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link BlockIds}: the server's model of a page's addressable blocks and their {@code ^id} markers. */
class BlockIdsTest {

    private static final String TABLE = "| a | b |\n|---|---|\n| 1 | 2 |\n";

    /** Every fixture in this class, for the whole-corpus properties ({@link #stripKeepsLineCount()}). */
    private static final List< String > FIXTURES = List.of(
            "Alpha beta ^abc\n",
            "- one ^a1\n- two\n",
            TABLE + "\n^tbl\n",
            "> quoted\n\n^q\n",
            "- x\n\n^l\n",
            "```\ncode\n```\n\n^cd\n",
            "$$\nx\n$$\n\n^m\n",
            "> [!note] T\n> body\n\n^c1\n",
            "^lone\n",
            "## Title ^h\n",
            "`x ^a`\n",
            "```\ny ^b\n```\n",
            "$x ^c$\n",
            "e^x and 2^10\n",
            "squared ^2\n",
            "a ^d\n\nb ^d\n",
            "Alpha ^abc\r\n\r\n- x ^k\r\n",
            "---\ntitle: t\n---\nText ^z\n",
            "$$\na\n$$\n\nPara\n",
            "- one\n- two\n",
            "- a ^p\n  - child\n- b\n",
            "Para ^in\n\n^dup\n",
            "> para ^qq\n>\n> ^qs\n",
            "- item\n  $$\n  y\n  $$\n\n  ^mi\n",
            "See [[Foo|Bar]] and **bold** ^w\n",
            "> [!note] T\n> body ^b1\n",
            "Intro\n\n    code line\n    more\n",
            "- a\n\n  ^x\n",
            "- ^x\n",
            "$$\n$$\n\n^em\n",
            "> line one\n> line two ^qp\n",
            "Para\n^ll\n",
            "- Item\n  ^li\n",
            "> q\n> ^lq\n",
            "Para\r\n^cr\r\n",
            "Para  \n^hb\n" );

    // ---- list / find ----------------------------------------------------------------------------------------------

    @Test
    void paragraphInlineMarker() {
        final List< Block > blocks = BlockIds.list( "Alpha beta ^abc\n" );
        assertEquals( 1, blocks.size() );
        final Block b = blocks.get( 0 );
        assertEquals( Kind.PARAGRAPH, b.kind() );
        assertEquals( "abc", b.id() );
        assertEquals( "Alpha beta", b.plainText() );
        assertEquals( 0, b.index() );
        assertEquals( 0, b.start() );
        assertEquals( "Alpha beta ^abc".length(), b.end() );
        assertEquals( "Alpha beta".length(), b.markerStart() );
        assertEquals( "Alpha beta ^abc".length(), b.markerEnd() );
    }

    @Test
    void listItemMarker() {
        final List< Block > blocks = BlockIds.list( "- one ^a1\n- two\n" );
        assertEquals( 2, blocks.size() );
        assertEquals( Kind.LIST_ITEM, blocks.get( 0 ).kind() );
        assertEquals( "a1", blocks.get( 0 ).id() );
        assertEquals( "one", blocks.get( 0 ).plainText() );
        assertEquals( Kind.LIST_ITEM, blocks.get( 1 ).kind() );
        assertNull( blocks.get( 1 ).id() );
        assertEquals( 1, blocks.get( 1 ).index() );
    }

    @Test
    void standaloneMarkerTagsPreviousTable() {
        final String text = TABLE + "\n^tbl\n";
        final List< Block > blocks = BlockIds.list( text );
        assertEquals( 1, blocks.size() );
        final Block b = blocks.get( 0 );
        assertEquals( Kind.TABLE, b.kind() );
        assertEquals( "tbl", b.id() );
        assertEquals( TABLE.length() + 1, b.markerStart() );
        assertEquals( TABLE.length() + 1 + "^tbl".length(), b.markerEnd() );
        assertEquals( TABLE.stripTrailing(), BlockIds.sourceOf( text, b ) );
    }

    @Test
    void standaloneMarkerTagsQuoteListCodeMath() {
        assertSingleTagged( "> quoted\n\n^q\n", Kind.QUOTE, "q", "> quoted" );
        assertSingleTagged( "```\ncode\n```\n\n^cd\n", Kind.CODE, "cd", "```\ncode\n```" );
        assertSingleTagged( "$$\nx\n$$\n\n^m\n", Kind.MATH, "m", "$$\nx\n$$" );

        final String list = "- x\n\n^l\n";
        final List< Block > blocks = BlockIds.list( list );
        assertEquals( 2, blocks.size(), blocks.toString() );
        assertEquals( Kind.LIST, blocks.get( 0 ).kind() );
        assertEquals( "l", blocks.get( 0 ).id() );
        assertEquals( "- x", BlockIds.sourceOf( list, blocks.get( 0 ) ) );
        assertEquals( Kind.LIST_ITEM, blocks.get( 1 ).kind() );
        assertNull( blocks.get( 1 ).id() );
    }

    private static void assertSingleTagged( final String text, final Kind kind, final String id, final String source ) {
        final List< Block > tagged = BlockIds.list( text ).stream().filter( b -> b.id() != null ).toList();
        assertEquals( 1, tagged.size(), text );
        assertEquals( kind, tagged.get( 0 ).kind(), text );
        assertEquals( id, tagged.get( 0 ).id(), text );
        assertEquals( 0, BlockIds.list( text ).indexOf( tagged.get( 0 ) ), "the tagged block comes first: " + text );
        assertEquals( source, BlockIds.sourceOf( text, tagged.get( 0 ) ), text );
    }

    @Test
    void calloutKind() {
        final String text = "> [!note] T\n> body\n\n^c1\n";
        final Block b = BlockIds.find( text, "c1" ).orElseThrow();
        assertEquals( Kind.CALLOUT, b.kind() );
        assertEquals( 0, b.index() );
        assertEquals( "> [!note] T\n> body", BlockIds.sourceOf( text, b ) );
    }

    @Test
    void orphanStandalone() {
        assertTrue( BlockIds.list( "^lone\n" ).isEmpty() );
        assertTrue( BlockIds.find( "^lone\n", "lone" ).isEmpty() );
        assertEquals( "\n", BlockIds.strip( "^lone\n" ) );
    }

    @Test
    void headingIgnored() {
        final String text = "## Title ^h\n";
        assertTrue( BlockIds.list( text ).stream().noneMatch( b -> "h".equals( b.id() ) ) );
        assertTrue( BlockIds.find( text, "h" ).isEmpty() );
        assertEquals( text, BlockIds.strip( text ) );
    }

    @Test
    void codeAndMathIgnored() {
        for ( final String text : List.of( "`x ^a`\n", "```\ny ^b\n```\n", "$x ^c$\n", "    z ^e\n" ) ) {
            assertTrue( BlockIds.list( text ).stream().allMatch( b -> b.id() == null ), text );
            assertEquals( text, BlockIds.strip( text ), text );
        }
    }

    @Test
    void needsWhitespace() {
        final List< Block > blocks = BlockIds.list( "e^x and 2^10\n" );
        assertEquals( 1, blocks.size() );
        assertEquals( Kind.PARAGRAPH, blocks.get( 0 ).kind() );
        assertNull( blocks.get( 0 ).id() );
        assertEquals( -1, blocks.get( 0 ).markerStart() );
        assertEquals( -1, blocks.get( 0 ).markerEnd() );
    }

    @Test
    void proseCaretBecomesId() {
        assertEquals( "2", BlockIds.list( "squared ^2\n" ).get( 0 ).id() );
    }

    @Test
    void duplicateFirstWins() {
        final String text = "a ^d\n\nb ^d\n";
        final List< Block > blocks = BlockIds.list( text );
        assertEquals( 2, blocks.size() );
        assertEquals( "d", blocks.get( 0 ).id() );
        assertNull( blocks.get( 1 ).id() );
        assertTrue( blocks.get( 1 ).markerStart() >= 0, "the duplicate's marker is still known, so it can be stripped" );
        assertEquals( "b", blocks.get( 1 ).plainText() );
        assertEquals( 0, BlockIds.find( text, "d" ).orElseThrow().index() );
        assertEquals( "a\n\nb\n", BlockIds.strip( text ) );
    }

    @Test
    void idsMatchCaseSensitively() {
        assertTrue( BlockIds.find( "Alpha ^Abc\n", "abc" ).isEmpty() );
        assertEquals( "Abc", BlockIds.find( "Alpha ^Abc\n", "Abc" ).orElseThrow().id() );
    }

    @Test
    void crlf() {
        final String text = "Alpha ^abc\r\n\r\n- x ^k\r\n";
        final List< Block > blocks = BlockIds.list( text );
        assertEquals( List.of( "abc", "k" ), blocks.stream().map( Block::id ).toList() );
        assertEquals( "Alpha", blocks.get( 0 ).plainText() );
        assertEquals( "Alpha\r\n\r\n- x\r\n", BlockIds.strip( text ) );
        assertEquals( "- x", BlockIds.sourceOf( text, blocks.get( 1 ) ) );
    }

    @Test
    void crlfStandalone() {
        final String text = "> q\r\n\r\n^s\r\n";
        assertEquals( Kind.QUOTE, BlockIds.find( text, "s" ).orElseThrow().kind() );
        assertEquals( "> q\r\n\r\n\r\n", BlockIds.strip( text ) );
    }

    @Test
    void frontmatterOffsets() {
        final String text = "---\ntitle: t\n---\nText ^z\n";
        final Block b = BlockIds.find( text, "z" ).orElseThrow();
        assertEquals( text.indexOf( " ^z" ), b.markerStart() );
        assertEquals( text.indexOf( "Text" ), b.start() );
        assertEquals( "Text", BlockIds.sourceOf( text, b ) );
        assertEquals( "---\ntitle: t\n---\nText\n", BlockIds.strip( text ) );
    }

    @Test
    void offsetsAfterPluginSyntax() {
        // MarkdownBodySource inserts "()" after [{…}]; offsets must still point into the raw text
        final String text = "[{TableOfContents}] then ^p1\n";
        final Block b = BlockIds.find( text, "p1" ).orElseThrow();
        assertEquals( text.indexOf( " ^p1" ), b.markerStart() );
        assertEquals( "[{TableOfContents}] then", BlockIds.sourceOf( text, b ) );
    }

    // ---- assign ---------------------------------------------------------------------------------------------------

    @Test
    void assignAfterDisplayMath() {
        final String text = "$$\na\n$$\n\nPara\n";
        final List< Block > blocks = BlockIds.list( text );
        assertEquals( List.of( Kind.MATH, Kind.PARAGRAPH ), blocks.stream().map( Block::kind ).toList() );
        assertEquals( "$$\na\n$$", BlockIds.sourceOf( text, blocks.get( 0 ) ) );
        assertEquals( "$$\na\n$$\n\nPara ^q1\n", BlockIds.assign( text, blocks.get( 1 ), "q1" ) );
        assertEquals( "$$\na\n$$\n\n^q2\n\nPara\n", BlockIds.assign( text, blocks.get( 0 ), "q2" ) );
    }

    @Test
    void mathInsideListItem() {
        final String text = "- item\n  $$\n  y\n  $$\n\n  ^mi\n";
        final Block math = BlockIds.find( text, "mi" ).orElseThrow();
        assertEquals( Kind.MATH, math.kind() );
        assertEquals( "$$\ny\n$$", BlockIds.sourceOf( text, math ), "the list's continuation indent is not the block's" );
    }

    @Test
    void assignStandaloneForTable() {
        final Block table = BlockIds.list( TABLE ).get( 0 );
        assertEquals( TABLE + "\n^t2\n", BlockIds.assign( TABLE, table, "t2" ) );
        assertEquals( "t2", BlockIds.list( BlockIds.assign( TABLE, table, "t2" ) ).get( 0 ).id() );
    }

    @Test
    void assignStandaloneKeepsFollowingBlockSeparate() {
        final String text = "```\nc\n```\nNext\n";
        final Block code = BlockIds.list( text ).get( 0 );
        final String assigned = BlockIds.assign( text, code, "cc" );
        assertEquals( "```\nc\n```\n\n^cc\n\nNext\n", assigned );
        assertEquals( Kind.CODE, BlockIds.find( assigned, "cc" ).orElseThrow().kind() );
    }

    @Test
    void assignStandaloneAtEndOfTextWithoutNewline() {
        final String text = "> q";
        final String assigned = BlockIds.assign( text, BlockIds.list( text ).get( 0 ), "e1" );
        assertEquals( "> q\n\n^e1", assigned );
        assertEquals( Kind.QUOTE, BlockIds.find( assigned, "e1" ).orElseThrow().kind() );
    }

    @Test
    void assignStandaloneInsideQuoteKeepsContainer() {
        final String text = "> ```\n> c\n> ```\n";
        final Block code = BlockIds.list( text ).stream().filter( b -> b.kind() == Kind.CODE ).findFirst().orElseThrow();
        final String assigned = BlockIds.assign( text, code, "qc" );
        assertEquals( "> ```\n> c\n> ```\n>\n> ^qc\n", assigned );
        assertEquals( Kind.CODE, BlockIds.find( assigned, "qc" ).orElseThrow().kind() );
    }

    @Test
    void assignCrlfUsesCrlf() {
        final String text = "| a |\r\n|---|\r\n| 1 |\r\n";
        assertEquals( text + "\r\n^t\r\n", BlockIds.assign( text, BlockIds.list( text ).get( 0 ), "t" ) );
    }

    @Test
    void assignListItem() {
        final String text = "- one\n- two\n";
        assertEquals( "- one\n- two ^x\n", BlockIds.assign( text, BlockIds.list( text ).get( 1 ), "x" ) );
    }

    @Test
    void assignListItemWithChildrenMarksItsOwnLine() {
        final String text = "- a\n  - child\n- b\n";
        final String assigned = BlockIds.assign( text, BlockIds.list( text ).get( 0 ), "ai" );
        assertEquals( "- a ^ai\n  - child\n- b\n", assigned );
        assertEquals( 0, BlockIds.find( assigned, "ai" ).orElseThrow().index() );
    }

    @Test
    void assignReplacesDuplicateMarker() {
        final String text = "a ^d\n\nb ^d\n";
        final String assigned = BlockIds.assign( text, BlockIds.list( text ).get( 1 ), "e" );
        assertEquals( "a ^d\n\nb ^e\n", assigned );
    }

    @Test
    void assignRefusesBadInput() {
        final String text = "Alpha ^abc\n\nBeta\n";
        final List< Block > blocks = BlockIds.list( text );
        assertThrows( IllegalArgumentException.class, () -> BlockIds.assign( text, blocks.get( 0 ), "new" ) );
        assertThrows( IllegalArgumentException.class, () -> BlockIds.assign( text, blocks.get( 1 ), "bad id" ) );
        assertThrows( IllegalArgumentException.class, () -> BlockIds.assign( "Other\n", blocks.get( 1 ), "x" ) );
    }

    @Test
    void calloutBodyParagraphStartsAfterTitle() {
        final String text = "> [!note] T\n> body ^b1\n";
        final Block body = BlockIds.find( text, "b1" ).orElseThrow();
        assertEquals( Kind.PARAGRAPH, body.kind() );
        assertEquals( 1, body.index() );
        assertEquals( text.indexOf( "body" ), body.start() );
        assertEquals( "body", BlockIds.sourceOf( text, body ) );
        assertEquals( "body", body.plainText() );
    }

    @Test
    void indentedCodeKeepsItsIndent() {
        final String text = "Intro\n\n    code line\n    more\n";
        final Block code = BlockIds.list( text ).get( 1 );
        assertEquals( Kind.CODE, code.kind() );
        assertEquals( "    code line\n    more", BlockIds.sourceOf( text, code ) );
        final String assigned = BlockIds.assign( text, code, "ic" );
        assertEquals( text + "\n^ic\n", assigned );
        assertEquals( Kind.CODE, BlockIds.find( assigned, "ic" ).orElseThrow().kind() );
    }

    @Test
    void indentedCodeWithExtraIndentKeepsItAsContent() {
        final String text = "Intro\n\n        deep\n";
        final Block code = BlockIds.list( text ).get( 1 );
        assertEquals( "        deep", BlockIds.sourceOf( text, code ) );
        final String assigned = BlockIds.assign( text, code, "dc" );
        assertEquals( text + "\n^dc\n", assigned );
    }

    @Test
    void assignIndentedCodeInsideListItemStaysInTheItem() {
        final String text = "- a\n\n      code\n";
        final Block code = BlockIds.list( text ).stream().filter( b -> b.kind() == Kind.CODE ).findFirst().orElseThrow();
        final String assigned = BlockIds.assign( text, code, "lc" );
        assertEquals( "- a\n\n      code\n\n  ^lc\n", assigned );
        assertEquals( Kind.CODE, BlockIds.find( assigned, "lc" ).orElseThrow().kind() );
    }

    @Test
    void standaloneMarkerInsideItemTagsTheItem() {
        final String text = "- a\n\n  ^x\n";
        final Block item = BlockIds.find( text, "x" ).orElseThrow();
        assertEquals( Kind.LIST_ITEM, item.kind() );
        assertEquals( "- a", BlockIds.sourceOf( text, item ) );
        assertEquals( "- a\n\n  \n", BlockIds.strip( text ) );
    }

    @Test
    void assignReplacesDuplicateStandaloneMarkerInsideItem() {
        final String text = "Top ^x\n\n- a\n\n  ^x\n";
        final Block item = BlockIds.list( text ).get( 1 );
        assertNull( item.id() );
        assertEquals( "Top ^x\n\n- a\n\n  ^y\n", BlockIds.assign( text, item, "y" ) );
    }

    @Test
    void sourceOfNestedBlockDropsContainerPrefix() {
        final String quote = "> line one\n> line two ^qp\n";
        assertEquals( "line one\nline two", BlockIds.sourceOf( quote, BlockIds.find( quote, "qp" ).orElseThrow() ) );
        final String nested = "- a\n  - b ^nb\n    - c\n";
        assertEquals( "- b\n  - c", BlockIds.sourceOf( nested, BlockIds.find( nested, "nb" ).orElseThrow() ) );
    }

    @Test
    void orphanInsideListItem() {
        final String text = "- ^x\n";
        final List< Block > blocks = BlockIds.list( text );
        assertEquals( 1, blocks.size() );
        assertNull( blocks.get( 0 ).id() );
        assertTrue( BlockIds.find( text, "x" ).isEmpty() );
        assertEquals( "- \n", BlockIds.strip( text ) );
        assertThrows( IllegalArgumentException.class, () -> BlockIds.assign( text, blocks.get( 0 ), "y" ) );
    }

    @Test
    void emptyAndAdjacentDisplayMath() {
        final String empty = "$$\n$$\n\n^em\n";
        assertEquals( "$$\n$$", BlockIds.sourceOf( empty, BlockIds.find( empty, "em" ).orElseThrow() ) );
        final String two = "$$\na\n$$\n$$\nb\n$$\n\n^m2\n";
        final List< Block > blocks = BlockIds.list( two );
        assertEquals( List.of( Kind.MATH, Kind.MATH ), blocks.stream().map( Block::kind ).toList() );
        assertEquals( "$$\nb\n$$", BlockIds.sourceOf( two, BlockIds.find( two, "m2" ).orElseThrow() ) );
        assertEquals( "b", blocks.get( 1 ).plainText() );
    }

    @Test
    void standaloneMarkerWithNothingAddressableBeforeIt() {
        for ( final String text : List.of( "## H\n\n^h\n", "> [!note] T\n> ^cb\n", "---\n\n^hr\n" ) ) {
            assertTrue( BlockIds.list( text ).stream().allMatch( b -> b.id() == null && b.kind() != Kind.PARAGRAPH ), text );
            assertEquals( text.substring( 0, text.lastIndexOf( '^' ) ) + "\n", BlockIds.strip( text ), text );
        }
    }

    // ---- sourceOf / strip -----------------------------------------------------------------------------------------

    @Test
    void sourceOfListItemKeepsChildren() {
        final String text = "- a ^p\n  - child\n- b\n";
        assertEquals( "- a\n  - child", BlockIds.sourceOf( text, BlockIds.find( text, "p" ).orElseThrow() ) );
    }

    @Test
    void inlineMarkerWinsOverFollowingStandalone() {
        final String text = "Para ^in\n\n^dup\n";
        assertEquals( "in", BlockIds.list( text ).get( 0 ).id() );
        assertTrue( BlockIds.find( text, "dup" ).isEmpty() );
        assertEquals( "Para\n\n\n", BlockIds.strip( text ) );
    }

    // ---- a marker alone on a paragraph's last line ---------------------------------------------------------------

    @Test
    void markerAloneOnLastLineIsInline() {
        final String text = "Para\n^ll\n";
        final List< Block > blocks = BlockIds.list( text );
        assertEquals( 1, blocks.size() );
        final Block b = blocks.get( 0 );
        assertEquals( Kind.PARAGRAPH, b.kind() );
        assertEquals( "ll", b.id() );
        assertEquals( "Para", b.plainText() );
        assertEquals( text.indexOf( '^' ), b.markerStart() );
        assertEquals( text.indexOf( '^' ) + "^ll".length(), b.markerEnd() );
        assertEquals( "Para", BlockIds.sourceOf( text, b ) );
        assertEquals( "Para\n\n", BlockIds.strip( text ) );
    }

    @Test
    void markerAfterBlankLineStaysStandalone() {
        final String text = "Para\n\n^sa\n";
        final Block b = BlockIds.list( text ).get( 0 );
        assertEquals( Kind.PARAGRAPH, b.kind() );
        assertEquals( "sa", b.id() );
        assertEquals( "Para", b.plainText() );
        assertEquals( text.indexOf( '^' ), b.markerStart() );
        assertEquals( "Para\n\n\n", BlockIds.strip( text ) );
    }

    @Test
    void lastLineMarkerInListItemAndQuote() {
        final Block item = BlockIds.find( "- Item\n  ^li\n", "li" ).orElseThrow();
        assertEquals( Kind.LIST_ITEM, item.kind() );
        assertEquals( "Item", item.plainText() );
        assertEquals( "- Item", BlockIds.sourceOf( "- Item\n  ^li\n", item ) );

        final String quote = "> q\n> ^lq\n";
        final Block para = BlockIds.find( quote, "lq" ).orElseThrow();
        assertEquals( Kind.PARAGRAPH, para.kind() );
        assertEquals( 1, para.index() );
        assertEquals( "q", para.plainText() );
        assertEquals( "q", BlockIds.sourceOf( quote, para ) );
        assertEquals( "> q\n>\n", BlockIds.strip( quote ) );
    }

    @Test
    void lastLineMarkerCrlfAndHardBreak() {
        final String crlf = "Para\r\n^cr\r\n";
        final Block b = BlockIds.find( crlf, "cr" ).orElseThrow();
        assertEquals( "Para", b.plainText() );
        assertEquals( "Para\r\n\r\n", BlockIds.strip( crlf ) );
        assertEquals( "Para", BlockIds.find( "Para  \n^hb\n", "hb" ).orElseThrow().plainText() );
    }

    @Test
    void lastLineNeedsTheMarkerAlone() {
        assertNull( BlockIds.list( "Para\n^x more\n" ).get( 0 ).id() );
        assertEquals( "Para\n^x more", BlockIds.list( "Para\n^x more\n" ).get( 0 ).plainText() );
    }

    @Test
    void assignReplacesDuplicateLastLineMarkerInPlace() {
        final String text = "a ^d\n\n- b\n  ^d\n";
        final Block item = BlockIds.list( text ).get( 1 );
        assertNull( item.id() );
        assertEquals( "a ^d\n\n- b\n  ^e\n", BlockIds.assign( text, item, "e" ) );
    }

    @Test
    void markersInsideQuote() {
        final String text = "> para ^qq\n>\n> ^qs\n";
        assertEquals( Kind.PARAGRAPH, BlockIds.find( text, "qq" ).orElseThrow().kind() );
        assertEquals( "> para\n>\n> \n", BlockIds.strip( text ) );
    }

    @Test
    void plainTextReducesMarkdownAndWikiLinks() {
        final Block b = BlockIds.find( "See [[Foo|Bar]] and **bold** ^w\n", "w" ).orElseThrow();
        assertEquals( "See Bar and bold", b.plainText() );
    }

    @Test
    void stripWithoutCaretIsIdentity() {
        final String text = "plain text\n";
        assertTrue( text == BlockIds.strip( text ) );
    }

    @Test
    void stripKeepsLineCount() {
        for ( final String text : FIXTURES ) {
            assertEquals( text.split( "\n", -1 ).length, BlockIds.strip( text ).split( "\n", -1 ).length, text );
            assertTrue( BlockIds.list( BlockIds.strip( text ) ).stream().allMatch( b -> b.id() == null && b.markerStart() < 0 ),
                    "no marker survives strip: " + text );
        }
    }

    @Test
    void indicesArePreOrder() {
        final String text = "> [!tip] T\n> in callout ^ic\n\n- a\n  - b\n\n| x |\n|---|\n";
        final List< Block > blocks = BlockIds.list( text );
        assertEquals( List.of( Kind.CALLOUT, Kind.PARAGRAPH, Kind.LIST_ITEM, Kind.LIST_ITEM, Kind.TABLE ),
                blocks.stream().map( Block::kind ).toList() );
        for ( int i = 0; i < blocks.size(); i++ ) {
            assertEquals( i, blocks.get( i ).index() );
        }
        assertEquals( "ic", blocks.get( 1 ).id() );
    }

    // ---- small helpers --------------------------------------------------------------------------------------------

    @Test
    void newIdUnique() {
        final Set< String > existing = new HashSet<>( Set.of( "aaaaaa", "bbbbbb" ) );
        for ( int i = 0; i < 1000; i++ ) {
            final String id = BlockIds.newId( existing );
            assertTrue( id.matches( "[a-z0-9]{6}" ), id );
            assertFalse( existing.contains( id ), id );
            existing.add( id );
        }
    }

    @Test
    void fingerprint() {
        assertEquals( "hélloworld42", BlockIds.fingerprint( "Héllo, World! 42" ) );
        assertEquals( 200, BlockIds.fingerprint( "ab ".repeat( 150 ) ).length() );
        assertEquals( "", BlockIds.fingerprint( null ) );
    }

    @Test
    void blockRefHeadings() {
        assertTrue( BlockIds.isBlockRef( "^abc" ) );
        assertFalse( BlockIds.isBlockRef( "Heading" ) );
        assertFalse( BlockIds.isBlockRef( null ) );
        assertEquals( "abc", BlockIds.idOf( "^abc" ) );
        assertTrue( BlockIds.ID.matcher( "a-B9" ).matches() );
        assertFalse( BlockIds.ID.matcher( "a b" ).matches() );
    }

    @Test
    void nullAndEmptyText() {
        assertTrue( BlockIds.list( null ).isEmpty() );
        assertTrue( BlockIds.list( "" ).isEmpty() );
        assertEquals( "", BlockIds.strip( "" ) );
        assertNull( BlockIds.strip( null ) );
        assertTrue( BlockIds.find( "x ^a\n", null ).isEmpty() );
    }

    @Test
    void aMarkerBesideAnAccessRuleIsStandaloneAndStripsWithItsSpaces() {
        assertEquals( "Para.\n\n[{ALLOW view Admin}]\n\nAfter.\n",
                BlockIds.strip( "Para.\n\n[{ALLOW view Admin}] ^id\n\nAfter.\n" ) );
        assertEquals( "Para.\r\n\r\n[{ALLOW view Admin}]\r\n\r\nAfter.\r\n",
                BlockIds.strip( "Para.\r\n\r\n[{ALLOW view Admin}] ^id\r\n\r\nAfter.\r\n" ) );
        assertEquals( "Para.\n\n[{DENY edit Guest}]\n", BlockIds.strip( "Para.\n\n^id [{DENY edit Guest}]\n" ) );
        assertEquals( "Para.\n\n[{ALLOW view Admin}]\n\n", BlockIds.strip( "Para.\n\n[{ALLOW view Admin}]\n^id\n" ) );
        final List< Block > blocks = BlockIds.list( "Para.\n\n[{ALLOW view Admin}] ^id\n\nAfter.\n" );
        assertEquals( 2, blocks.size() );
        assertEquals( "id", blocks.get( 0 ).id() );
        assertEquals( "[{ALLOW view Admin}] ^id".indexOf( '^' ) + "Para.\n\n".length() - 1, blocks.get( 0 ).markerStart() );
    }

    @Test
    void aListItemOfOnlyAccessRulesIsReadAsTheEmptyItemTheRenderSees() {
        // "  - " (empty once the rule is stripped) cannot interrupt the paragraph "one": no nested item there
        final String text = "- one\n  - [{ALLOW view Admin}]\n  - three\n- four\n";
        final List< Block > blocks = BlockIds.list( text );
        assertEquals( 3, blocks.size() );
        assertEquals( "three", blocks.get( 1 ).plainText() );
        assertEquals( text.indexOf( "- three" ), blocks.get( 1 ).start() );
        final String tagged = BlockIds.assign( text, blocks.get( 1 ), "t3" );
        assertEquals( "- one\n  - [{ALLOW view Admin}]\n  - three ^t3\n- four\n", tagged );
        // ... and a blank line after an empty item ends it: the marker below tags the list
        final List< Block > ended = BlockIds.list( "- one\n- [{ALLOW view Admin}]\n\n  ^mk\n\nAfter.\n" );
        assertEquals( Kind.LIST, ended.get( 0 ).kind() );
        assertEquals( "mk", ended.get( 0 ).id() );
    }

    @Test
    void markerIdsCoverBlockIdsLosingDuplicatesAndOrphans() {
        assertEquals( Set.of( "o", "b", "q" ), BlockIds.markerIds( "^o\n\nA ^o\n\nB ^b\n\n> ^q\n" ) );
        assertEquals( Set.of(), BlockIds.markerIds( null ) );
        assertEquals( Set.of(), BlockIds.markerIds( "`^code` only\n" ) );
    }

    @Test
    void textWithoutAPossibleMarkerIsNeverParsedByStripOrFind() {
        final String text = "Energy $E = mc^2$ and $x^{n+1}$, see the note[^1].\n\n[^1]: Footnote 2^10.\n";
        final long before = BlockIds.parsesOnThisThread();
        assertSame( text, BlockIds.strip( text ) );
        assertTrue( BlockIds.find( text, "1" ).isEmpty() );
        assertTrue( BlockIds.find( text, "2" ).isEmpty() );
        assertEquals( Set.of(), BlockIds.markerIds( text ) );
        assertEquals( before, BlockIds.parsesOnThisThread(), "parsed although no marker can be there" );
        // every marker form still gets through the pre-check
        for ( final String marked : List.of( "Para ^a\n", "Para\n^a\n", ">^a\n", "[{ALLOW view Admin}]^a\n",
                "^a [{DENY edit Guest}]\n", "Para ^a\r\n", "- item\t^a\n" ) ) {
            assertFalse( BlockIds.strip( marked ).contains( "^a" ), marked );
            assertTrue( BlockIds.markerIds( marked ).contains( "a" ), marked );
        }
    }

    @Test
    void findComputesTheTextOfTheFoundBlock() {
        final Block b = BlockIds.find( "First **bold** one.\n\nSecond [[Page|shown]] text ^s2\n", "s2" ).orElseThrow();
        assertEquals( "Second shown text", b.plainText() );
        assertEquals( 1, b.index() );
    }

    @Test
    void bodyEntryPointsReadALeadingDashPairAsThematicBreaks() {
        final String body = "---\nPara ^h\n\n---\n\nMore ^m2\n";
        assertEquals( "---\nPara\n\n---\n\nMore\n", BlockIds.stripBody( body ) );
        final Block h = BlockIds.findBody( body, "h" ).orElseThrow();
        assertEquals( "Para", h.plainText() );
        assertEquals( body.indexOf( "Para" ), h.start() );
        assertEquals( "More", BlockIds.findBody( body, "m2" ).orElseThrow().plainText() );
        assertTrue( BlockIds.find( body, "h" ).isEmpty(), "as a full text those lines are frontmatter" );
        assertSame( "No marker.\n", BlockIds.stripBody( "No marker.\n" ) );
        assertTrue( BlockIds.findBody( "No marker.\n", "h" ).isEmpty() );
    }

    /**
     * Preparation appends {@code ()} to a text ending in <code>[{…}]</code>: a block ending there ends at the text's end,
     * never one past it. Nothing here may throw, whatever the text ends in.
     */
    @Test
    void aTextEndingInAnAccessRuleOrAPluginNeverThrows() {
        final String[] cases = {
            "Text [{ALLOW view Admin}]", "- [{ALLOW view Admin}]", "Para ^id\n\nx[{ALLOW view Admin}]", "x[{Plugin}]",
            "Para ^id\n\nx[{Plugin}]", "Para ^id\n\n- [{Plugin}]", "Para ^id\n\n|[{ALLOW view Admin}]",
            "Para ^id\n\n==[{ALLOW view Admin}]", "Para ^id\n\nText [{ALLOW view Admin}]", "[{ALLOW view Admin}]",
            "Para ^id\n\n[{ALLOW view Admin}]", "Para ^id\r\n\r\nx[{Plugin}]",
        };
        for ( final String text : cases ) {
            final List< Block > blocks = BlockIds.list( text );
            for ( final Block b : blocks ) {
                assertTrue( b.start() >= 0 && b.start() <= b.end() && b.end() <= text.length(), text + " " + b );
                if ( b.id() == null && b.markerStart() < 0 ) {
                    try {
                        assertEquals( "zz", BlockIds.list( BlockIds.assign( text, b, "zz" ) ).get( b.index() ).id() );
                    } catch ( final IllegalArgumentException e ) {
                        assertTrue( e.getMessage().contains( "no text line" ), e.getMessage() );
                    }
                }
            }
            BlockIds.markerIds( text );
            BlockIds.find( text, "id" ).ifPresent( b -> assertEquals( "Para", b.plainText() ) );
            assertFalse( BlockIds.strip( text ).contains( "^id" ), text );
        }
        assertEquals( "Para\n\nx[{ALLOW view Admin}]", BlockIds.strip( "Para ^id\n\nx[{ALLOW view Admin}]" ) );
        assertEquals( "x[{Plugin}]".length(), BlockIds.list( "x[{Plugin}]" ).get( 0 ).end() );
        assertEquals( "Text".length(), BlockIds.list( "Text [{ALLOW view Admin}]" ).get( 0 ).end() );
        assertEquals( "Text ^t [{ALLOW view Admin}]",
                BlockIds.assign( "Text [{ALLOW view Admin}]", BlockIds.list( "Text [{ALLOW view Admin}]" ).get( 0 ), "t" ) );
    }

    /** A marker the render sees once a rule inside it is stripped is a marker; taking it out leaves the rule alone. */
    @Test
    void aMarkerSplicedAroundAnAccessRuleIsStrippedWithoutTheRule() {
        final String text = "Para ^i[{ALLOW view Admin}]d\n";
        assertEquals( "Para ^id\n", AclDirectiveText.strip( text ) );
        assertEquals( "id", BlockIds.list( text ).get( 0 ).id() );
        assertEquals( "Para[{ALLOW view Admin}]\n", BlockIds.strip( text ) );
    }

    /** A {@code ^id} inside an access rule is stripped with the rule before the render parses: it is no marker. */
    @Test
    void aMarkerInsideAnAccessRuleIsNoMarker() {
        final String text = "Para [{ALLOW view ^id}]\n";
        assertNull( BlockIds.list( text ).get( 0 ).id() );
        assertEquals( Set.of(), BlockIds.markerIds( text ) );
        assertSame( text, BlockIds.strip( text ) );
        assertTrue( BlockIds.find( text, "id" ).isEmpty() );
    }

    /** Editing markers into or out of raw text never touches its access rules. */
    @Test
    void assigningAndStrippingKeepEveryAccessRule() {
        final String text = "Para.\n\n[{ALLOW view Admin}] ^id\n\nAfter [{DENY edit Guest}]\n\n| a |\n|---|\n| 1 |\n[{ALLOW view All}]\n";
        final List< Block > blocks = BlockIds.list( text );
        assertEquals( List.of( Kind.PARAGRAPH, Kind.PARAGRAPH, Kind.TABLE ), blocks.stream().map( Block::kind ).toList() );
        assertEquals( "id", blocks.get( 0 ).id() );
        final String after = BlockIds.assign( text, blocks.get( 1 ), "af" );
        assertEquals( "Para.\n\n[{ALLOW view Admin}] ^id\n\nAfter ^af [{DENY edit Guest}]\n\n| a |\n|---|\n| 1 |\n[{ALLOW view All}]\n", after );
        final String table = BlockIds.assign( text, blocks.get( 2 ), "tb" );
        assertEquals( "tb", BlockIds.list( table ).get( 2 ).id() );
        assertTrue( table.contains( "[{ALLOW view All}]" ) && table.contains( "[{DENY edit Guest}]" ), table );
        assertEquals( "Para.\n\n ^id\n\nAfter \n\n| a |\n|---|\n| 1 |\n\n^tb\n", AclDirectiveText.strip( table ) );
        final String stripped = BlockIds.strip( after );
        // the blanks around an inline marker go with it, as they always did: the rule then follows the word directly
        assertEquals( "Para.\n\n[{ALLOW view Admin}]\n\nAfter[{DENY edit Guest}]\n\n| a |\n|---|\n| 1 |\n[{ALLOW view All}]\n", stripped );
    }

    /**
     * Tagging must not change any block's kind. A table whose paragraph runs on into an indented line
     * ({@code | 1 | 2 |\n\tPara.}) cannot take a standalone marker: the blank line it needs would make that line code.
     */
    @Test
    void assignRefusesAnEditThatWouldChangeTheKindOfABlock() {
        final String text = "| a | b |\n|---|---|\n| 1 | 2 |\n\tPara.\n";
        final List< Block > blocks = BlockIds.list( text );
        assertEquals( List.of( Kind.TABLE, Kind.PARAGRAPH ), blocks.stream().map( Block::kind ).toList() );
        final IllegalArgumentException e = assertThrows( IllegalArgumentException.class,
                () -> BlockIds.assign( text, blocks.get( 0 ), "tb" ) );
        assertTrue( e.getMessage().contains( "without changing the blocks around it" ), e.getMessage() );
        // the paragraph itself still takes one
        assertEquals( "pp", BlockIds.list( BlockIds.assign( text, blocks.get( 1 ), "pp" ) ).get( 1 ).id() );
    }

    /**
     * A text whose every {@code ^} is preceded or followed by something no marker can have, even once its access rules
     * are stripped, is not even stripped (the strip parses the text to read its rules).
     */
    @Test
    void aTextNoCaretOfWhichCanBeAMarkerIsNotEvenStripped() {
        final String text = "Energy $E = mc^2$ [{ALLOW view Admin}] and x^{n+1}.\n\n[{DENY edit Guest}] see[^1].\n";
        final long before = BlockIds.stripsOnThisThread();
        assertSame( text, BlockIds.strip( text ) );
        assertSame( text, BlockIds.stripBody( text ) );
        assertTrue( BlockIds.find( text, "1" ).isEmpty() );
        assertEquals( Set.of(), BlockIds.markerIds( text ) );
        assertEquals( before, BlockIds.stripsOnThisThread(), "stripped although no caret can be a marker" );
        // a caret right after a rule, or before one, still gets through
        for ( final String marked : List.of( "Para [{ALLOW view Admin}]^a\n", "Para ^a [{ALLOW view Admin}]\n",
                "Para ^a\n[{ALLOW view Admin}]\n", "[{ALLOW view Admin}]()^a\n", "x\n[{ALLOW view Admin}]\n^a\n" ) ) {
            assertTrue( BlockIds.markerIds( marked ).contains( "a" ), marked );
        }
    }

    // ---- comments are not blocks (the render never sees them) -----------------------------------------------------

    @Test
    void aCommentOnlyParagraphIsNoBlockAndShiftsNoLaterOrdinal() {
        final List< Block > blocks = BlockIds.list( "First.\n\n%%todo%%\n\nSecond.\n\n%%a\n\nhidden\n\nb%%\n\nThird.\n" );
        assertEquals( List.of( "First.", "Second.", "Third." ), blocks.stream().map( Block::plainText ).toList() );
        assertEquals( List.of( 0, 1, 2 ), blocks.stream().map( Block::index ).toList() );
    }

    @Test
    void aBlocksPlainTextNeverHoldsItsComment() {
        final Block b = BlockIds.find( "Visible %%secret%% text ^abc\n", "abc" ).orElseThrow();
        assertFalse( b.plainText().contains( "secret" ), b.plainText() );
        assertTrue( b.plainText().startsWith( "Visible" ) && b.plainText().endsWith( "text" ), b.plainText() );
    }

    @Test
    void aMarkerInsideACommentAddressesNothing() {
        assertTrue( BlockIds.find( "Para.\n\n%%\n\nhidden para ^sec\n\n%%\n\nAfter.\n", "sec" ).isEmpty() );
        assertTrue( BlockIds.findBody( "%%\n\nhidden ^sec\n\n%%\n", "sec" ).isEmpty() );
        assertFalse( BlockIds.markerIds( "Para.\n\n%%\n\nhidden ^sec\n\n%%\n" ).contains( "sec" ) );
    }

    @Test
    void blockOffsetsStillIndexTheRawTextAroundAComment() {
        final String raw = "---\nsummary: 100%% a %% b\n---\nFirst.\n\n%%note%%\n\nTarget %%c%% end.\n";
        final List< Block > blocks = BlockIds.list( raw );
        assertEquals( 2, blocks.size(), "a %% pair in frontmatter is a value, not a comment: " + blocks );
        final Block target = blocks.get( 1 );
        assertEquals( "Target %%c%% end.", raw.substring( target.start(), target.end() ) );
        final String tagged = BlockIds.assign( raw, target, "abc123" );
        assertTrue( tagged.endsWith( "Target %%c%% end. ^abc123\n" ), tagged );
        assertEquals( "First.\n\n%%note%%\n\nTarget %%c%% end.\n", BlockIds.strip( tagged ).substring( raw.indexOf( "First." ) ) );
    }
}
