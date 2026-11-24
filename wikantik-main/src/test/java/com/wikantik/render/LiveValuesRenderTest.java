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
package com.wikantik.render;

import com.wikantik.TestEngine;
import com.wikantik.WikiEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Page;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.livevalues.LiveValueExpander;
import com.wikantik.api.livevalues.LiveValueExpander.Resolution;
import com.wikantik.api.livevalues.LiveValueSource;
import com.wikantik.api.livevalues.LiveValues;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.filters.FilterManager;
import com.wikantik.parser.markdown.MarkdownParser;
import com.wikantik.render.markdown.MarkdownRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Display renders expand {@code {{wiki:...}}} tokens, and both render caches stay correct and effective. */
class LiveValuesRenderTest {

    private final TestEngine engine = TestEngine.build(
            TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
            TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ) );
    private final AtomicInteger articles = new AtomicInteger( 1142 );
    private final AtomicInteger postTranslates = new AtomicInteger();

    /** Real expander over a mutable {@code articles} value. */
    private final LiveValues values = new LiveValues() {
        @Override public String expandText( final String text ) {
            return LiveValueExpander.expandText( text, this::resolve );
        }
        @Override public String expandBody( final String markdown ) {
            return LiveValueExpander.expandBody( markdown, this::resolve );
        }
        @Override public String generation() { return String.valueOf( articles.get() ); }
        @Override public Map< String, String > renderedValues() { return Map.of(); }
        @Override public void register( final LiveValueSource source ) { }
        private Resolution resolve( final String name ) {
            return "articles".equals( name ) ? new Resolution.Value( articles.get() ) : new Resolution.Unknown();
        }
    };

    @BeforeEach
    void wire() {
        ( ( WikiEngine ) engine ).serviceRegistry().put( LiveValues.class, values );
        engine.getManager( FilterManager.class ).addPageFilter( new PageFilter() {
            @Override
            public String postTranslate( final Context context, final String htmlContent ) {
                postTranslates.incrementAndGet();
                return htmlContent;
            }
        }, -1000 );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    private RenderingManager rm() {
        return engine.getManager( RenderingManager.class );
    }

    private Context view( final String page ) {
        final Page p = engine.getManager( PageManager.class ).getPage( page );
        final Context ctx = Wiki.context().create( engine, p );
        ctx.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );
        return ctx;
    }

    private String render( final String page, final String text ) {
        return plain( rm().textToHTML( view( page ), text ) );
    }

    /** The rendered HTML with the entity and comment escapes the renderer adds to {@code +}, {@code =} and braces undone. */
    private static String plain( final String html ) {
        return html.replace( "&#43;", "+" ).replace( "&#61;", "=" ).replace( "<!-- -->", "" );
    }

    private static final String BODY = "We have {{wiki:articles|round=100}} articles\n";

    @Test
    void pageViewShowsValue() throws Exception {
        engine.saveText( "LvShow", BODY );
        final String html = render( "LvShow", BODY );
        assertTrue( html.contains( "1,100+" ), html );
        assertFalse( html.contains( "{{wiki" ), html );
    }

    @Test
    void cacheHitWhileRoundedValueUnchanged() throws Exception {
        engine.saveText( "LvHit", BODY );
        render( "LvHit", BODY );
        articles.set( 1150 );
        final String second = render( "LvHit", BODY );
        assertEquals( 1, postTranslates.get(), "second view is an HTML cache hit" );
        assertTrue( second.contains( "1,100+" ), second );
    }

    @Test
    void cacheMissWhenRoundedValueMoves() throws Exception {
        articles.set( 1199 );
        engine.saveText( "LvMiss", BODY );
        assertTrue( render( "LvMiss", BODY ).contains( "1,100+" ) );
        articles.set( 1201 );
        final String after = render( "LvMiss", BODY );
        assertEquals( 2, postTranslates.get(), "re-rendered when the displayed value moved" );
        assertTrue( after.contains( "1,200+" ), after );
    }

    @Test
    void documentCacheFollowsTheDisplayedValue() throws Exception {
        articles.set( 1199 );
        engine.saveText( "LvDoc", BODY );
        final Context ctx = view( "LvDoc" );
        final var first = rm().getRenderedDocument( ctx, BODY );
        assertTrue( rm().getRenderedDocument( view( "LvDoc" ), BODY ) == first, "unchanged value is a hit" );
        articles.set( 1201 );
        final var after = rm().getRenderedDocument( view( "LvDoc" ), BODY );
        assertTrue( after != first, "moved value is a miss" );
        assertTrue( plain( rm().getHTML( view( "LvDoc" ), after ) ).contains( "1,200+" ) );
    }

    @Test
    void wysiwygShowsRawToken() throws Exception {
        engine.saveText( "LvWys", BODY );
        final Context ctx = view( "LvWys" );
        ctx.setVariable( Context.VAR_WYSIWYG_EDITOR_MODE, Boolean.TRUE );
        final String html = plain( rm().textToHTML( ctx, BODY ) );
        assertTrue( html.contains( "{{wiki:articles" ), html );
        assertFalse( html.contains( "1,100" ), html );
    }

    @Test
    void codeFenceTokenNotExpanded() throws Exception {
        final String text = "```\n{{wiki:articles}}\n```\n\nand {{wiki:articles}}\n";
        engine.saveText( "LvFence", text );
        final String html = render( "LvFence", text );
        assertTrue( html.contains( "{{wiki:articles}}" ), html );
        assertTrue( html.contains( "and 1,142" ), html );
    }

    @Test
    void transcludedPageExpandsAgainstSameSnapshot() throws Exception {
        engine.saveText( "LvInner", "Inner {{wiki:articles}} here\n" );
        final String host = "Host {{wiki:articles}}\n\n![[LvInner]]\n";
        engine.saveText( "LvHost", host );
        final String html = render( "LvHost", host );
        assertFalse( html.contains( "{{wiki" ), html );
        assertEquals( 2, html.split( "1,142", -1 ).length - 1, html );
    }

    @Test
    void frontmatterTokensStayRawAndDoNotChurnTheCache() throws Exception {
        final String text = "---\ntitle: T {{wiki:articles}}\nsummary: S {{wiki:articles}}\n---\nplain body\n";
        engine.saveText( "LvFront", text );
        final Context ctx = view( "LvFront" );
        rm().textToHTML( ctx, text );
        assertEquals( "T {{wiki:articles}}", ctx.getPage().getAttribute( "title" ) );
        assertEquals( "S {{wiki:articles}}", ctx.getPage().getAttribute( "summary" ) );
        articles.set( 5000 );
        rm().textToHTML( view( "LvFront" ), text );
        assertEquals( 1, postTranslates.get(), "a frontmatter token never invalidates the cache" );
    }
}
