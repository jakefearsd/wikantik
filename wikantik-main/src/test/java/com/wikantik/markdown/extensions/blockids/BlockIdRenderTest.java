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
package com.wikantik.markdown.extensions.blockids;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.parser.markdown.BlockIds;
import com.wikantik.parser.markdown.BlockIds.Block;
import com.wikantik.parser.markdown.BlockIds.Kind;
import com.wikantik.parser.markdown.MarkdownDocument;
import com.wikantik.parser.markdown.MarkdownParser;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.markdown.MarkdownRenderer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rendering {@code ^id} block markers as {@code id="^id"} anchors and stamping {@code data-block} ordinals. */
class BlockIdRenderTest {

    private static final String HOST = "BlockIdRenderHost";

    /** An opening tag carrying a {@code data-block}: tag name, then all its attributes. */
    private static final Pattern STAMPED = Pattern.compile( "<([a-z0-9]+)\\b([^>]*\\bdata-block=\"(\\d+)\"[^>]*)>" );

    private final TestEngine testEngine = TestEngine.build(
            TestEngine.with( "wikantik.fileSystemProvider.pageDir", "./target/md-pageDir" ),
            TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
            TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ),
            TestEngine.with( "wikantik.translatorReader.allowHTML", "true" ) );   // the sanitized path

    @AfterEach
    void tearDown() {
        testEngine.stop();
    }

    private String render( final String markdown ) throws Exception {
        testEngine.saveText( HOST, markdown );
        final Page p = Wiki.contents().page( testEngine, HOST );
        final Context context = Wiki.context().create( testEngine, HttpMockFactory.createHttpRequest(), p );
        return testEngine.getManager( RenderingManager.class ).textToHTML( context, markdown );
    }

    // ---- anchors ------------------------------------------------------------------------------------------------------

    @Test
    void inlineMarkerBecomesIdAndIsRemoved() throws Exception {
        final String html = render( "Alpha beta ^abc\n" );
        assertTrue( html.contains( "<p id=\"^abc\" data-block=\"0\">Alpha beta</p>" ), html );
        assertFalse( html.contains( "^abc<" ) || html.contains( " ^abc" ), html );
    }

    @Test
    void lastLineMarkerTagsItsParagraph() throws Exception {
        assertTrue( render( "Para\n^ll\n" ).contains( "<p id=\"^ll\" data-block=\"0\">Para</p>" ) );
        assertTrue( render( "Para\n\n^sa\n" ).contains( "<p id=\"^sa\" data-block=\"0\">Para</p>" ) );
        assertTrue( render( "- Item\n  ^li\n" ).contains( "<li id=\"^li\" data-block=\"0\">Item</li>" ) );
    }

    @Test
    void listItemId() throws Exception {
        final String html = render( "- one ^a1\n- two\n" );
        assertTrue( html.contains( "<li id=\"^a1\" data-block=\"0\">one</li>" ), html );
        assertTrue( html.contains( "<li data-block=\"1\">two</li>" ), html );
    }

    @Test
    void standaloneMarkerOnTableQuoteListCodeMath() throws Exception {
        assertTagged( render( "| a | b |\n|---|---|\n| 1 | 2 |\n\n^tbl\n" ), "table", "tbl", 0 );
        assertTagged( render( "> quoted\n\n^q\n" ), "blockquote", "q", 0 );
        final String list = render( "- x\n\n^l\n" );
        assertTagged( list, "ul", "l", 0 );
        assertTrue( list.contains( "<li data-block=\"1\">x</li>" ), list );
        assertTagged( render( "```\ncode\n```\n\n^cd\n" ), "pre", "cd", 0 );
        assertTagged( render( "$$\nx\n$$\n\n^m\n" ), "div", "m", 0 );
        assertTagged( render( "> [!note] T\n> body\n\n^c1\n" ), "div", "c1", 0 );
    }

    private static void assertTagged( final String html, final String tag, final String id, final int ordinal ) {
        final Matcher m = STAMPED.matcher( html );
        assertTrue( m.find(), html );
        assertEquals( tag, m.group( 1 ), html );
        assertTrue( m.group( 2 ).contains( "id=\"^" + id + "\"" ), html );
        assertEquals( String.valueOf( ordinal ), m.group( 3 ), html );
        assertEquals( 1, count( html, "id=\"^" + id + "\"" ), html );
        assertFalse( html.contains( ">^" + id ) || html.contains( " ^" + id ), "the marker text is gone: " + html );
    }

    @Test
    void orphanAnchor() throws Exception {
        final String html = render( "^lone\n\nText\n" );
        assertTrue( html.contains( "<span id=\"^lone\" class=\"block-anchor\"></span>" ), html );
        assertTrue( html.contains( "<p data-block=\"0\">Text</p>" ), html );
    }

    @Test
    void headingCaretStaysLiteral() throws Exception {
        final String html = render( "## T ^h\n\nBody\n" );
        assertTrue( html.contains( "T ^h</h2>" ), html );
        assertFalse( html.contains( "id=\"^h\"" ), html );
        assertTrue( html.contains( "<p data-block=\"0\">Body</p>" ), html );
    }

    @Test
    void duplicateGetsNoSecondAnchor() throws Exception {
        final String html = render( "a ^d\n\nb ^d\n\n^d\n" );
        assertEquals( 1, count( html, "id=\"^d\"" ), html );
        assertTrue( html.contains( "<p id=\"^d\" data-block=\"0\">a</p>" ), html );
        assertTrue( html.contains( "<p data-block=\"1\">b</p>" ), html );
        assertFalse( html.contains( "^d<" ) || html.contains( ">^d" ) || html.contains( "block-anchor" ), html );
    }

    @Test
    void blockIdWinsOverAttributeId() throws Exception {
        // {#custom} on a paragraph's last line is the paragraph's id; a standalone marker after it tags the same <p>.
        final String html = render( "Para\n{#custom}\n\n^abc\n" );
        assertTrue( html.contains( "id=\"^abc\"" ), html );
        assertFalse( html.contains( "custom" ), html );
        assertEquals( "abc", BlockIds.list( "Para\n{#custom}\n\n^abc\n" ).get( 0 ).id() );
        // A marker must end the paragraph: followed by {#custom} it is not one, and stays text (BlockIds agrees).
        final String literal = render( "Para ^abc\n{#custom}\n" );
        assertTrue( literal.contains( "id=\"custom\"" ) && literal.contains( "Para ^abc" ), literal );
        assertNull( BlockIds.list( "Para ^abc\n{#custom}\n" ).get( 0 ).id() );
    }

    // ---- ordinals -----------------------------------------------------------------------------------------------------

    private static final String FIXTURE = String.join( "\n",
            "---",
            "title: Parity",
            "---",
            "# Heading ^nope",
            "",
            "> [!note] Title",
            "> callout body ^cb",
            "",
            "- top ^t1",
            "  - nested",
            "    - deeper",
            "- second",
            "",
            "| a | b |",
            "|---|---|",
            "| 1 | 2 |",
            "",
            "^tbl",
            "",
            "$$",
            "x^2",
            "$$",
            "",
            "![[BlockIdOther]]",
            "",
            "- [ ] task one",
            "- [x] task two ^tk",
            "",
            "Para after ^pa",
            "",
            "- only for marker",
            "",
            "^lst",
            "",
            "    indented code",
            "",
            "Closing paragraph",
            "" );

    @Test
    void ordinalsEqualBlockIdsList() throws Exception {
        testEngine.saveText( "BlockIdOther", "Other body.\n" );
        final List< Block > blocks = BlockIds.list( FIXTURE );
        final String html = render( FIXTURE );

        assertTrue( html.contains( "Other body." ), "the embed still renders: " + html );
        final String own = withoutIncludedContent( html );   // an embed numbers its own blocks; the reader skips them
        final Matcher m = STAMPED.matcher( own );
        final List< Integer > seen = new ArrayList<>();
        while ( m.find() ) {
            final int ordinal = Integer.parseInt( m.group( 3 ) );
            assertTrue( ordinal < blocks.size(), "ordinal " + ordinal + " out of range: " + html );
            final Block b = blocks.get( ordinal );
            assertTrue( tagsFor( b.kind() ).contains( m.group( 1 ) ),
                    "ordinal " + ordinal + " is a " + b.kind() + " but renders as <" + m.group( 1 ) + ">: " + html );
            if ( b.id() != null ) {
                assertTrue( m.group( 2 ).contains( "id=\"^" + b.id() + "\"" ), "ordinal " + ordinal + ": " + html );
            }
            if ( b.kind() == Kind.PARAGRAPH ) {
                final int close = own.indexOf( "</p>", m.end() );
                assertEquals( BlockIds.fingerprint( b.plainText() ),
                        BlockIds.fingerprint( own.substring( m.end(), close ).replaceAll( "<[^>]*>", "" ) ), "ordinal " + ordinal );
            }
            seen.add( ordinal );
        }
        assertEquals( new HashSet<>( seen ).size(), seen.size(), "no ordinal is stamped twice: " + seen );
        assertEquals( seen.stream().sorted().toList(), seen, "ordinals appear in document order" );

        // Every block is stamped except the embed-only paragraph, which the embed replaces.
        final int embed = indexOf( blocks, b -> b.kind() == Kind.PARAGRAPH && b.plainText().contains( "BlockIdOther" ) );
        final List< Integer > expected = new ArrayList<>();
        for ( int i = 0; i < blocks.size(); i++ ) {
            if ( i != embed ) {
                expected.add( i );
            }
        }
        assertEquals( expected, seen, html );
        assertEquals( Kind.LIST, blocks.get( indexOf( blocks, b -> "lst".equals( b.id() ) ) ).kind() );
        assertFalse( html.contains( "id=\"^nope\"" ), html );
    }

    /** {@code html} without the elements of class {@code wiki-embed} / {@code inserted-page}: another parse's blocks. */
    private static String withoutIncludedContent( final String html ) {
        final Matcher open = Pattern.compile( "<div class=\"(wiki-embed|inserted-page)\\b" ).matcher( html );
        if ( !open.find() ) {
            return html;
        }
        final Matcher div = Pattern.compile( "<div\\b|</div>" ).matcher( html );
        int depth = 0;
        int from = open.start();
        while ( div.find( from ) ) {
            depth += div.group().startsWith( "</" ) ? -1 : 1;
            from = div.end();
            if ( depth == 0 ) {
                return withoutIncludedContent( html.substring( 0, open.start() ) + html.substring( div.end() ) );
            }
        }
        throw new AssertionError( "unclosed included content: " + html );
    }

    private static int indexOf( final List< Block > blocks, final java.util.function.Predicate< Block > p ) {
        for ( int i = 0; i < blocks.size(); i++ ) {
            if ( p.test( blocks.get( i ) ) ) {
                return i;
            }
        }
        throw new AssertionError( "no such block in " + blocks );
    }

    private static Set< String > tagsFor( final Kind kind ) {
        return switch ( kind ) {
            case PARAGRAPH -> Set.of( "p" );
            case LIST_ITEM -> Set.of( "li" );
            case LIST -> Set.of( "ul", "ol" );
            case TABLE -> Set.of( "table" );
            case QUOTE -> Set.of( "blockquote" );
            case CALLOUT -> Set.of( "div", "details" );
            case CODE -> Set.of( "pre", "figure" );
            case MATH -> Set.of( "div" );
        };
    }

    @Test
    void insertPageHostOrdinalsUnaffected() throws Exception {
        testEngine.saveText( "BlockIdInc", "Inc one ^i1\n\nInc two\n" );
        final String host = "First ^f\n\n[{InsertPage page=BlockIdInc}]\n\nLast ^l\n";
        final List< Block > blocks = BlockIds.list( host );
        assertEquals( 3, blocks.size() );
        final String html = render( host );

        assertTrue( html.contains( "<p id=\"^f\" data-block=\"0\">First</p>" ), html );
        assertTrue( html.contains( "<p id=\"^l\" data-block=\"2\">Last</p>" ), html );
        final int inserted = html.indexOf( "inserted-page" );
        assertTrue( inserted > 0, html );
        final String included = html.substring( inserted, html.indexOf( "<p id=\"^l\"" ) );
        // the included page's anchors come along; its own ordinals (another parse's numbering) do not
        assertTrue( included.contains( "<p id=\"^i1\">Inc one</p>" ), included );
        assertTrue( included.contains( "<p>Inc two</p>" ), included );
    }

    @Test
    void pluginOutputCarriesNoOrdinals() throws Exception {
        // A plugin's generated markup is a nested parse with its own numbering from 0; spliced into the host without a
        // wrapper the reader can recognise, those ordinals would name the host's blocks.
        testEngine.saveText( "BlockIdReferrerOne", "See [[" + HOST + "]].\n" );
        testEngine.saveText( "BlockIdReferrerTwo", "See [[" + HOST + "]] too.\n" );
        final String host = "Intro ^in\n\n[{ReferringPagesPlugin}]\n\nOutro\n";
        assertEquals( 3, BlockIds.list( host ).size() );
        final String html = render( host );
        assertTrue( html.contains( "BlockIdReferrerOne" ) && html.contains( "<li>" ), "the plugin rendered a list: " + html );
        assertTrue( html.contains( "<p id=\"^in\" data-block=\"0\">Intro</p>" ), html );
        assertTrue( html.contains( "<p data-block=\"2\">Outro</p>" ), html );
        assertEquals( 3, count( html, "data-block=" ), "only the host's own blocks: " + html );
        assertEquals( "<li id=\"^a\">x</li><p class=\"k\">data-block=\"3\"</p>",
                BlockIdAttributeProvider.withoutOrdinals( "<li id=\"^a\" data-block=\"0\">x</li><p data-block=\"12\" class=\"k\">data-block=\"3\"</p>" ) );
        final String plain = "<p>no ordinals</p>";
        assertTrue( plain == BlockIdAttributeProvider.withoutOrdinals( plain ) );
    }

    @Test
    void caretIdSurvivesSanitizer() throws Exception {
        // allowHTML=true: the render goes through WikantikHtmlSanitizer
        final String html = render( "Text ^k-9\n\n| a |\n|---|\n\n^t\n" );
        assertTrue( html.contains( "id=\"^k-9\"" ), html );
        assertTrue( html.contains( "<table id=\"^t\" data-block=\"1\">" ), html );
    }

    @Test
    void crlfPage() throws Exception {
        final String html = render( "Alpha ^abc\r\n\r\n- x ^k\r\n\r\nPara\r\n^ll\r\n" );
        assertTrue( html.contains( "<p id=\"^abc\" data-block=\"0\">Alpha</p>" ), html );
        assertTrue( html.contains( "<li id=\"^k\" data-block=\"1\">x</li>" ), html );
        assertTrue( html.contains( "<p id=\"^ll\" data-block=\"2\">Para</p>" ), html );
    }

    // ---- failure ------------------------------------------------------------------------------------------------------

    @Test
    void aFailingWalkLeavesTheRenderIntact() {
        final List< LogEvent > events = new CopyOnWriteArrayList<>();
        final Logger logger = ( Logger ) LogManager.getLogger( BlockIdPostProcessor.class );
        final AbstractAppender appender = new AbstractAppender( "BlockIdRenderTest-" + System.nanoTime(), null,
                PatternLayout.createDefaultLayout(), true, null ) {
            @Override
            public void append( final LogEvent event ) {
                events.add( event.toImmutable() );
            }
        };
        appender.start();
        final Level prior = logger.getLevel();
        Configurator.setLevel( logger.getName(), Level.WARN );
        logger.addAppender( appender );
        try {
            final MutableDataSet options = MarkdownDocument.structuralOptions();
            final Document doc = Parser.builder( options ).build().parse( "Alpha ^abc\n" );
            new BlockIdPostProcessor( "BrokenPage", d -> {
                throw new IllegalStateException( "boom" );
            } ).processDocument( doc );
            final String html = HtmlRenderer.builder( options )
                    .attributeProviderFactory( new BlockIdAttributeProvider.Factory() ).build().render( doc );
            assertEquals( "<p>Alpha ^abc</p>\n", html, "rendered as if there were no block ids" );
            assertTrue( events.stream().anyMatch( e -> e.getLevel() == Level.WARN
                    && e.getMessage().getFormattedMessage().contains( "BrokenPage" )
                    && e.getMessage().getFormattedMessage().contains( "boom" ) ), events.toString() );
        } finally {
            logger.removeAppender( appender );
            appender.stop();
            Configurator.setLevel( logger.getName(), prior );
        }
    }

    @Test
    void attributesOnlyOnTheBlocksOwnElement() throws Exception {
        // A callout renders three elements from one node; only the outer one is the block.
        final String html = render( "> [!note] T\n> body ^b\n" );
        assertEquals( 1, count( html, "data-block=\"0\"" ), html );
        assertTrue( html.contains( "<p id=\"^b\" data-block=\"1\">body</p>" ), html );
    }

    private static int count( final String haystack, final String needle ) {
        int n = 0;
        for ( int i = haystack.indexOf( needle ); i >= 0; i = haystack.indexOf( needle, i + 1 ) ) {
            n++;
        }
        return n;
    }
}
