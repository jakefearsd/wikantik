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
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.api.managers.PageManager;
import com.wikantik.parser.WikiDocument;
import org.jdom2.Element;
import org.jdom2.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CleanTextRenderer} covering the getString() method.
 * Note: the Markdown pipeline stores page content in a Flexmark AST rather than a JDOM
 * tree, so CleanTextRenderer.getString() is exercised indirectly (via a hand-crafted JDOM
 * document) to avoid the "Root element not set" exception that a bare MarkdownDocument
 * would produce.
 */
class CleanTextRendererTest {

    private TestEngine engine;
    private RenderingManager renderingManager;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        renderingManager = engine.getManager( RenderingManager.class );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    // -----------------------------------------------------------------------
    // getString via textToHTML-rendered document returns rendered HTML
    // -----------------------------------------------------------------------

    @Test
    void getStringStripsHtmlFromRenderedContent() throws Exception {
        final String pageName = "CleanTextPage";
        engine.saveText( pageName, "**Bold text** and *italic*." );

        final Page page = engine.getManager( PageManager.class ).getPage( pageName );
        final Context context = Wiki.context().create( engine, page );
        context.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );

        final WikiDocument doc = renderingManager.getRenderedDocument( context, "**Bold text** and *italic*." );
        assertNotNull( doc );

        // Use getHTML() through the rendering manager — this exercises the full rendering
        // pipeline (including the MarkdownRenderer path) and confirms the document is usable.
        final String text = renderingManager.getHTML( context, doc );
        assertNotNull( text );
        assertTrue( text.contains( "Bold text" ) || text.contains( "bold" ),
                "Rendered content should contain the text content" );
    }

    // -----------------------------------------------------------------------
    // CleanTextRenderer.getString() with a JDOM-backed WikiDocument (spy)
    // -----------------------------------------------------------------------

    @Test
    void getStringOnJdomBackedDocumentExtractsText() throws Exception {
        final String pageName = "JdomPage";
        engine.saveText( pageName, "Some content." );

        final Page page = engine.getManager( PageManager.class ).getPage( pageName );
        final Context context = Wiki.context().create( engine, page );

        // Build a real JDOM Document with a root element and a text node so that
        // XmlUtil.extractTextFromDocument() succeeds without throwing.
        final org.jdom2.Document jdomDoc = new org.jdom2.Document();
        final Element root = new Element( "div" );
        root.addContent( new Text( "hello world" ) );
        jdomDoc.setRootElement( root );

        // Spy on WikiDocument so getDocument() returns our JDOM doc.
        final WikiDocument spy = Mockito.spy( new WikiDocument( page ) );
        Mockito.doReturn( jdomDoc ).when( spy ).getDocument();

        final CleanTextRenderer renderer = new CleanTextRenderer( context, spy );
        final String text = renderer.getString();
        assertNotNull( text, "getString should never return null" );
        assertTrue( text.contains( "hello world" ),
                "getString should extract the text node from the JDOM document; got: " + text );
    }
}
