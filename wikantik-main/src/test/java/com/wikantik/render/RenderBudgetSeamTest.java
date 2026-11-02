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

import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.parser.markdown.BudgetedText;
import com.wikantik.parser.markdown.MarkdownDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Defense in depth for #105: a parse budget that runs out while a parsed document is rendered never escapes the render.
 * The render seam logs it and falls back to a fresh parse of the document's text.
 */
class RenderBudgetSeamTest {

    private static TestEngine engine;

    @BeforeAll
    static void start() throws Exception {
        engine = TestEngine.build();
        engine.saveText( "RenderBudgetSeam", "Hello SEAM-MARKER\n" );
    }

    @AfterAll
    static void stop() {
        engine.deleteQuietly( "RenderBudgetSeam" );
        engine.stop();
    }

    @Test
    void aBudgetThatRunsOutDuringARenderFallsBackInsteadOfEscaping() {
        final String body = "Hello SEAM-MARKER\n";
        final Parser parser = Parser.builder().build();
        final BudgetedText measure = BudgetedText.of( body, Long.MAX_VALUE );
        parser.parse( BasedSequence.of( measure ) );
        // Exactly what the parse needs: the parse succeeds, the first character the render reads exhausts it.
        final Node node = parser.parse( BasedSequence.of( BudgetedText.of( body, measure.reads() ) ) );

        final Page page = engine.getManager( PageManager.class ).getPage( "RenderBudgetSeam" );
        final Context ctx = Wiki.context().create( engine, HttpMockFactory.createHttpRequest(), page );
        ctx.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );
        final MarkdownDocument doc = new MarkdownDocument( page, node );
        doc.setPageData( body );
        doc.setContext( ctx );

        final String html = assertDoesNotThrow( () -> engine.getManager( RenderingManager.class ).getHTML( ctx, doc ) );
        assertTrue( html.contains( "SEAM-MARKER" ), html );
    }
}
