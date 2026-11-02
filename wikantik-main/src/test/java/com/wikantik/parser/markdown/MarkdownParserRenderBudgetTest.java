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

import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.managers.PageManager;
import com.wikantik.render.RenderingManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.parser.MarkupParser;
import com.wikantik.render.DefaultRenderingManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The render's parse is bounded like the read-path rule check is ({@link BudgetedText}): a crafted {@code {…}} run
 * that makes the attributes expression quadratic or overflow the stack degrades to a parse without attributes, never
 * to an unbounded render (#105).
 */
class MarkdownParserRenderBudgetTest {

    private static final String CRAFTED = "intro para\n\n{edit" + " ".repeat( 200_000 ) + "x\n\nTAIL-MARKER";

    private final AtomicInteger parses = new AtomicInteger();
    private TestEngine engine;

    @AfterEach
    void tearDown() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    private void boot() throws Exception {
        engine = TestEngine.build( TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ) );
        final DefaultRenderingManager counting = new DefaultRenderingManager() {
            @Override
            public MarkupParser getParser( final Context context, final String pagedata ) {
                parses.incrementAndGet();
                return super.getParser( context, pagedata );
            }
        };
        counting.initialize( engine, engine.getWikiProperties() );
        engine.setManager( RenderingManager.class, counting );
    }

    private String view( final String name, final String text ) throws Exception {
        if ( engine == null ) {
            boot();
        }
        engine.saveText( name, text );
        final Context ctx = Wiki.context().create( engine, HttpMockFactory.createHttpRequest(),
                engine.getManager( PageManager.class ).getPage( name ) );
        ctx.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );
        return engine.getManager( RenderingManager.class ).textToHTML( ctx,
                engine.getManager( PageManager.class ).getPureText( name, -1 ) );
    }

    @Test
    void quadraticAttributeInputRendersWithinBudget() {
        final String html = assertTimeoutPreemptively( Duration.ofSeconds( 5 ), () -> view( "RbQuadratic", CRAFTED ) );
        assertTrue( html.contains( "intro para" ), html );
        assertTrue( html.contains( "TAIL-MARKER" ), html );
    }

    @Test
    void untrackedFootnoteAttributeDoesNotFailTheRender() throws Exception {
        final String html = view( "RbFootnote", "[^1]: [[^1]\\[{y}]\n\nTAIL-MARKER" );
        assertTrue( html.contains( "TAIL-MARKER" ), html );
    }

    @Test
    void ordinaryAttributesStillApply() throws Exception {
        final String html = view( "RbAttrs", "# Title {#custom-id .note}\n" );
        assertTrue( html.contains( "id=\"custom-id\"" ), html );
        assertTrue( html.contains( "class=\"note\"" ), html );
    }

    @Test
    void fallbackIsCached() throws Exception {
        assertTimeoutPreemptively( Duration.ofSeconds( 5 ), () -> view( "RbCached", CRAFTED ) );
        final int afterFirst = parses.get();
        assertTrue( afterFirst >= 1 );
        assertTimeoutPreemptively( Duration.ofSeconds( 5 ), () -> view( "RbCached", CRAFTED ) );
        assertEquals( afterFirst, parses.get(), "the second view of the crafted page must not re-enter the parser" );
    }

    /** About 24k characters of ordinary prose, tables and links: a render reads every one of them. */
    private static String ordinaryPage( final String marker ) {
        final StringBuilder sb = new StringBuilder( "# " ).append( marker ).append( "\n\n" );
        for ( int i = 0; i < 200; i++ ) {
            sb.append( "Paragraph " ).append( i ).append( " with *emphasis*, a [link](https://example.test/" ).append( i )
              .append( ") and `code`.\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n" );
        }
        return sb.toString();
    }

    @Test
    void aCachedDocumentRendersForeverAfterItsParse() throws Exception {
        boot();
        final String text = ordinaryPage( "RbForever" );
        engine.saveText( "RbForever", text );
        final Context ctx = Wiki.context().create( engine, HttpMockFactory.createHttpRequest(),
                engine.getManager( PageManager.class ).getPage( "RbForever" ) );
        ctx.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );
        final RenderingManager rm = engine.getManager( RenderingManager.class );
        final com.wikantik.parser.WikiDocument doc = rm.getRenderedDocument( ctx, text );
        final String first = rm.getHTML( ctx, doc );
        assertTrue( first.contains( "Paragraph 199" ), first );
        for ( int i = 0; i < 2_000; i++ ) {
            final int view = i;
            assertEquals( first, assertDoesNotThrow( () -> rm.getHTML( ctx, doc ), "render " + view ), "render " + view );
        }
    }

    @Test
    void aPageWithAnEmbedRendersOnEveryViewAfterItsDocumentIsCached() throws Exception {
        boot();
        engine.saveText( "RbEmbedTarget", "Embedded words EMBED-MARKER.\n" );
        final String host = "Before the embed.\n\n![[RbEmbedTarget]]\n\n" + ordinaryPage( "RbEmbedHost" );
        String first = null;
        for ( int i = 0; i < 2_000; i++ ) {
            final String html = i == 0 ? view( "RbEmbedHost", host ) : viewSaved( "RbEmbedHost" );
            if ( first == null ) {
                first = html;
                assertTrue( first.contains( "EMBED-MARKER" ), first );
                assertTrue( first.contains( "Paragraph 199" ), first );
            } else {
                assertEquals( first, html, "view " + i );
            }
        }
    }

    private String viewSaved( final String name ) {
        final Context ctx = Wiki.context().create( engine, HttpMockFactory.createHttpRequest(),
                engine.getManager( PageManager.class ).getPage( name ) );
        ctx.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );
        return engine.getManager( RenderingManager.class ).textToHTML( ctx,
                engine.getManager( PageManager.class ).getPureText( name, -1 ) );
    }

    @Test
    void aFallbackAfterAStackOverflowIsNotCached() throws Exception {
        // A stack overflow depends on how deep the render already is (a nested render has less stack), not only on the
        // text, so its fallback must not be kept for the top-level view.
        final String overflowing = "x {" + "a ".repeat( 20_000 ) + "}\n\nTAIL-MARKER";
        final String html = assertTimeoutPreemptively( Duration.ofSeconds( 5 ), () -> view( "RbOverflow", overflowing ) );
        assertTrue( html.contains( "TAIL-MARKER" ), html );
        final int afterFirst = parses.get();
        assertTimeoutPreemptively( Duration.ofSeconds( 5 ), () -> viewSaved( "RbOverflow" ) );
        assertTrue( parses.get() > afterFirst, "a stack-overflow fallback must be parsed again, not served from the cache" );
    }

    @Test
    void theLastResortShowsNoAccessRule() {
        final String html = com.vladsch.flexmark.html.HtmlRenderer.builder().build()
                .render( MarkdownParser.preformatted( "before\n[{ALLOW edit alice@example.test}]\nafter LAST-MARKER", false ) );
        assertTrue( html.contains( "LAST-MARKER" ), html );
        assertFalse( html.contains( "alice@example.test" ), html );
        assertFalse( html.contains( "ALLOW" ), html );
    }

    @Test
    void theLastResortIsOneEscapedPreformattedBlock() {
        final String html = com.vladsch.flexmark.html.HtmlRenderer.builder().build()
                .render( MarkdownParser.preformatted( "# not a heading\r\n\r\n<b>raw</b> {x}", false ) );
        assertTrue( html.contains( "<pre><code># not a heading" ), html );
        assertTrue( html.contains( "&lt;b&gt;raw&lt;/b&gt; {x}" ), html );
    }

    @Test
    void repoCorpusNeverExhaustsTheBudget() throws Exception {
        final Parser parser = Parser.builder( MarkdownDocument.structuralOptions() ).build();
        double maxPerChar = 0;
        String worst = "";
        int pages = 0;
        final List< Path > files;
        try ( Stream< Path > walk = Files.list( Path.of( "..", "docs", "wikantik-pages" ) ) ) {
            files = walk.filter( p -> p.toString().endsWith( ".md" ) ).toList();
        }
        for ( final Path file : files ) {
            final String body = MarkdownBodySource.prepare( FrontmatterParser.parse(
                    Files.readString( file, StandardCharsets.UTF_8 ) ).body() ).text();
            final BudgetedText text = BudgetedText.of( body, BudgetedText.budgetFor( body.length() ) );
            assertDoesNotThrow( () -> parser.parse( BasedSequence.of( text ) ), file.toString() );
            pages++;
            final double perChar = body.isEmpty() ? 0 : ( double ) text.reads() / body.length();
            if ( perChar > maxPerChar ) {
                maxPerChar = perChar;
                worst = file.getFileName().toString();
            }
        }
        assertTrue( pages > 100, "corpus found (" + pages + " pages)" );
        System.out.println( "corpus max reads/char = " + maxPerChar + " (" + worst + ")" );
        assertTrue( maxPerChar < 64, "max reads/char " + maxPerChar + " on " + worst + " should stay well under 128" );
    }
}
