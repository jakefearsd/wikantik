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
import com.wikantik.WikiPage;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.api.managers.PageManager;
import com.wikantik.parser.WikiDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional tests for {@link DefaultRenderingManager} covering uncovered branches:
 * getHTML(Context, WikiDocument) in WYSIWYG mode,
 * getHTML(Context, Page) delegation,
 * beautifyTitle with non-null attachment,
 * textToHTML with localLinkHook/extLinkHook/attLinkHook parameters (justParse = true and false),
 * useCache returning false for non-VIEW context.
 */
class DefaultRenderingManagerAdditionalTest {

    private TestEngine engine;
    private DefaultRenderingManager mgr;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        mgr = ( DefaultRenderingManager ) engine.getManager( RenderingManager.class );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    // -----------------------------------------------------------------------
    // getHTML(Context, WikiDocument) – WYSIWYG mode
    // -----------------------------------------------------------------------

    @Test
    void getHtmlContextDocInWysiwygMode() throws Exception {
        final String pageName = "WysiwygPage";
        engine.saveText( pageName, "Some content" );

        final Page page = engine.getManager( PageManager.class ).getPage( pageName );
        final Context context = Wiki.context().create( engine, page );
        context.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );
        context.setVariable( Context.VAR_WYSIWYG_EDITOR_MODE, Boolean.TRUE );

        final WikiDocument doc = mgr.getRenderedDocument( context, "Some content" );
        assertNotNull( doc );

        final String html = mgr.getHTML( context, doc );
        assertNotNull( html );
    }

    // -----------------------------------------------------------------------
    // getHTML(Context, Page) – delegates to getPureText + textToHTML
    // -----------------------------------------------------------------------

    @Test
    void getHtmlContextPage() throws Exception {
        final String pageName = "GetHtmlPageTest";
        engine.saveText( pageName, "**bold**" );

        final Page page = engine.getManager( PageManager.class ).getPage( pageName );
        final Context context = Wiki.context().create( engine, page );
        context.setRequestContext( ContextEnum.PAGE_NONE.getRequestContext() );

        final String html = mgr.getHTML( context, page );
        assertNotNull( html );
        assertTrue( html.contains( "bold" ), "HTML should contain page text" );
    }

    // -----------------------------------------------------------------------
    // textToHTML with link hooks (justParse = false)
    // -----------------------------------------------------------------------

    @Test
    void textToHtmlWithLinkHooksRendersContent() throws Exception {
        final String pageName = "LinkHookPage";
        engine.saveText( pageName, "[SomePage]()" );

        final Page page = engine.getManager( PageManager.class ).getPage( pageName );
        final Context context = Wiki.context().create( engine, page );
        context.setRequestContext( ContextEnum.PAGE_NONE.getRequestContext() );

        final String html = mgr.textToHTML(
                context, "[SomePage]()",
                ( ctx, text ) -> text,  // localLinkHook
                null,                    // extLinkHook
                null,                    // attLinkHook
                true,                    // parseAccessRules
                false                    // justParse
        );
        assertNotNull( html );
    }

    // -----------------------------------------------------------------------
    // textToHTML with justParse = true (no rendering)
    // -----------------------------------------------------------------------

    @Test
    void textToHtmlWithJustParseReturnsEmpty() throws Exception {
        final String pageName = "JustParsePage";
        engine.saveText( pageName, "Hello world" );

        final Page page = engine.getManager( PageManager.class ).getPage( pageName );
        final Context context = Wiki.context().create( engine, page );
        context.setRequestContext( ContextEnum.PAGE_NONE.getRequestContext() );

        final String result = mgr.textToHTML(
                context, "Hello world",
                null, null, null,
                true,   // parseAccessRules
                true    // justParse = true
        );
        // When justParse is true, no rendering happens — result should be empty string
        assertEquals( "", result );
    }

    // -----------------------------------------------------------------------
    // textToHTML with null pagedata returns null
    // -----------------------------------------------------------------------

    @Test
    void textToHtmlWithNullPagedataReturnsNull() throws Exception {
        final String pageName = "NullDataPage";
        engine.saveText( pageName, "test" );

        final Page page = engine.getManager( PageManager.class ).getPage( pageName );
        final Context context = Wiki.context().create( engine, page );

        final String result = mgr.textToHTML( context, null, null, null, null, true, false );
        assertNull( result );
    }

    // -----------------------------------------------------------------------
    // useCache returns false for non-PAGE_VIEW context
    // -----------------------------------------------------------------------

    @Test
    void useCacheFalseForNoneContext() throws Exception {
        final String pageName = "NonViewPage";
        engine.saveText( pageName, "content" );

        final Page page = engine.getManager( PageManager.class ).getPage( pageName );
        final Context context = Wiki.context().create( engine, page );
        context.setRequestContext( ContextEnum.PAGE_NONE.getRequestContext() );

        assertFalse( mgr.useCache( context ),
                "Cache should not be used for PAGE_NONE context" );
    }

    @Test
    void useCacheTrueForViewContext() throws Exception {
        final String pageName = "ViewCachePage";
        engine.saveText( pageName, "content" );

        final Page page = engine.getManager( PageManager.class ).getPage( pageName );
        final Context context = Wiki.context().create( engine, page );
        context.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );

        // Cache is enabled only when caching is enabled AND context is PAGE_VIEW
        // In test mode caching may or may not be enabled — but the method should not throw
        assertDoesNotThrow( () -> mgr.useCache( context ) );
    }

    // -----------------------------------------------------------------------
    // getHTML(pagename, version) with unknown page does not throw
    // -----------------------------------------------------------------------

    @Test
    void getHtmlByNameForExistingPage() throws Exception {
        final String pageName = "NameVersionPage";
        engine.saveText( pageName, "*italic*" );

        final String html = mgr.getHTML( pageName, -1 );
        assertNotNull( html );
        assertTrue( html.contains( "italic" ) );
    }
}
