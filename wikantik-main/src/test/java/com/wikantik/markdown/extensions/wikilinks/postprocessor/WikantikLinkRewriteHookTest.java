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
package com.wikantik.markdown.extensions.wikilinks.postprocessor;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.parser.markdown.MarkdownParser;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.markdown.MarkdownRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Context#VAR_LINK_TARGET_REWRITE} is a hook a host sets to rewrite link targets; it must be invisible to page
 * rendering: unset, or set to anything that is not an operator, the output is what it was before the hook existed.
 */
class WikantikLinkRewriteHookTest {

    private static final String HOST = "RewriteHookHost";
    private static final String TEXT = "![s](work-attachment:5)\n\n[l](work-attachment:6)\n";

    /** Rendered by the code as it was BEFORE the hook existed (the hook is inert unless the variable is set). */
    private static final String NORMAL_BEFORE = "<p data-block=\"0\"><img alt=\"s\" /></p>\n"
        + "<p data-block=\"1\"><span class=\"error\">No InterWiki reference defined in properties for Wiki called "
        + "&#34;work-attachment&#34;!</span></p>\n";
    private static final String AS_TYPED_BEFORE = NORMAL_BEFORE;   // links-as-typed draws this text the same way in this renderer

    private final TestEngine engine = TestEngine.build(
        TestEngine.with( "wikantik.fileSystemProvider.pageDir", "./target/md-pageDir" ),
        TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
        TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ) );

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    private String render( final boolean asTyped, final Object rewrite ) throws Exception {
        final Page p = Wiki.contents().page( engine, HOST );
        final Context context = Wiki.context().create( engine, HttpMockFactory.createHttpRequest(), p );
        if ( asTyped ) {
            context.setVariable( Context.VAR_LINKS_AS_TYPED, Boolean.TRUE );
        }
        if ( rewrite != null ) {
            context.setVariable( Context.VAR_LINK_TARGET_REWRITE, rewrite );
        }
        return engine.getManager( RenderingManager.class ).textToHTML( context, TEXT );
    }

    @Test
    void withoutTheVariablePageRenderingIsUnchanged() throws Exception {
        assertEquals( NORMAL_BEFORE, render( false, null ) );
        assertEquals( AS_TYPED_BEFORE, render( true, null ) );
    }

    @Test
    void aNonFunctionVariableIsIgnored() throws Exception {
        assertEquals( NORMAL_BEFORE, render( false, "work-attachment" ) );
        assertEquals( AS_TYPED_BEFORE, render( true, "work-attachment" ) );
    }

    @Test
    void anOperatorRewritesAnImageAndALinkAndNothingElse() throws Exception {
        final UnaryOperator< String > op = url -> url.startsWith( "work-attachment:" ) ? "/x/" + url.substring( 16 ) : null;
        final String html = render( true, op );
        assertTrue( html.contains( "src=\"/x/5\"" ), html );
        assertTrue( html.contains( "<a href=\"/x/6\">l</a>" ), html );
        assertFalse( html.contains( "work-attachment" ), html );
    }

    @Test
    void anOperatorReturningNullLeavesTheOutputAlone() throws Exception {
        assertEquals( NORMAL_BEFORE, render( false, ( UnaryOperator< String > ) url -> null ) );
    }
}
