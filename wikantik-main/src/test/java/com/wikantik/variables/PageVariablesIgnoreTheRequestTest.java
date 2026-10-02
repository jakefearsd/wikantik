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
package com.wikantik.variables;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.parser.markdown.MarkdownParser;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.markdown.MarkdownRenderer;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Page variables ({@code [{$name}]}) used to fall through to the viewer's session attributes and HTTP request
 * parameters. The value was inserted as raw HTML at parse time and the parsed document is cached for every viewer, so
 * {@code /wiki/Page?msg=<script>…} injected content into the page for everyone. Page content must never read either.
 */
class PageVariablesIgnoreTheRequestTest {

    private final TestEngine engine = TestEngine.build(
            TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
            TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ) );

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void aRequestParameterIsNeverRenderedIntoThePageNorIntoItsCachedDocument() throws Exception {
        final String text = "Hello [{$msg}]\n\nand [{$custom}]\n";
        engine.saveText( "PvRequest", text );
        final HttpServletRequest req = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( "<b>injected-msg</b>" ).when( req ).getParameter( "msg" );
        Mockito.doReturn( "<b>injected-custom</b>" ).when( req ).getParameter( "custom" );

        final String attacked = render( "PvRequest", text, req );
        final String next = render( "PvRequest", text, HttpMockFactory.createHttpRequest() );

        assertFalse( attacked.contains( "injected" ), "request parameters are not page variables: " + attacked );
        assertFalse( next.contains( "injected" ), "nothing request-derived is cached for the next viewer: " + next );
    }

    @Test
    void aSessionAttributeIsNeverRenderedIntoThePage() throws Exception {
        final String text = "Secret: [{$sessionSecret}]\n";
        engine.saveText( "PvSession", text );
        final HttpServletRequest req = HttpMockFactory.createHttpRequest();
        final jakarta.servlet.http.HttpSession session = req.getSession();
        Mockito.doReturn( "session-only-value" ).when( session ).getAttribute( "sessionSecret" );

        final String html = render( "PvSession", text, req );

        assertFalse( html.contains( "session-only-value" ), "a viewer's session must not leak into shared page HTML: " + html );
    }

    private String render( final String page, final String text, final HttpServletRequest request ) {
        final Context ctx = Wiki.context().create( engine, request, engine.getManager( PageManager.class ).getPage( page ) );
        ctx.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );
        return engine.getManager( RenderingManager.class ).textToHTML( ctx, text );
    }
}
