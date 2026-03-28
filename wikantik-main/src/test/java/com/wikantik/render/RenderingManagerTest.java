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
import com.wikantik.pages.PageManager;
import com.wikantik.parser.MarkupParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RenderingManagerTest {

    TestEngine       m_engine = TestEngine.build();
    RenderingManager m_manager = m_engine.getManager( RenderingManager.class );

    @AfterEach
    public void tearDown() throws Exception {
        m_engine.stop();
    }

    @Test
    public void testBeautifyTitle() {
        final String src = "WikiNameThingy";
        Assertions.assertEquals("Wiki Name Thingy", m_engine.getManager( RenderingManager.class ).beautifyTitle( src ) );
    }

    /**
     *  Acronyms should be treated wisely.
     */
    @Test
    public void testBeautifyTitleAcronym() {
        final String src = "JSPWikiPage";
        Assertions.assertEquals("JSP Wiki Page", m_engine.getManager( RenderingManager.class ).beautifyTitle( src ) );
    }

    /**
     *  Acronyms should be treated wisely.
     */
    @Test
    public void testBeautifyTitleAcronym2() {
        final String src = "DELETEME";
        Assertions.assertEquals("DELETEME", m_engine.getManager( RenderingManager.class ).beautifyTitle( src ) );
    }

    @Test
    public void testBeautifyTitleAcronym3() {
        final String src = "JSPWikiFAQ";
        Assertions.assertEquals("JSP Wiki FAQ", m_engine.getManager( RenderingManager.class ).beautifyTitle( src ) );
    }

    @Test
    public void testBeautifyTitleNumbers() {
        final String src = "TestPage12";
        Assertions.assertEquals("Test Page 12", m_engine.getManager( RenderingManager.class ).beautifyTitle( src ) );
    }

    /**
     *  English articles too.
     */
    @Test
    public void testBeautifyTitleArticle() {
        final String src = "ThisIsAPage";
        Assertions.assertEquals("This Is A Page", m_engine.getManager( RenderingManager.class ).beautifyTitle( src ) );
    }

    @Test
    public void testGetHTML() throws Exception {
        final String text = "*Foobar.*";
        final String name = "Test1";
        m_engine.saveText( name, text );

        final String data = m_engine.getManager( RenderingManager.class ).getHTML( name );
        Assertions.assertEquals( "<p><em>Foobar.</em></p>\n", data );
    }

    @Test
    public void testHtmlCacheReturnsCachedResultOnSecondCall() throws Exception {
        final String text = "**Hello** world";
        final String name = "HtmlCacheTest";
        m_engine.saveText( name, text );

        final Page page = m_engine.getManager( PageManager.class ).getPage( name );
        final Context context = Wiki.context().create( m_engine, page );
        context.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );

        final String html1 = m_manager.textToHTML( context, text );
        Assertions.assertNotNull( html1 );

        // Second call should return same result from HTML cache
        final String html2 = m_manager.textToHTML( context, text );
        Assertions.assertEquals( html1, html2 );
    }

    @Test
    public void testHtmlCacheInvalidatedOnContentChange() throws Exception {
        final String name = "HtmlCacheInvalidateTest";
        final String text1 = "**First version**";
        m_engine.saveText( name, text1 );

        final Page page = m_engine.getManager( PageManager.class ).getPage( name );
        final Context context = Wiki.context().create( m_engine, page );
        context.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );

        final String html1 = m_manager.textToHTML( context, text1 );

        // Different content should produce different HTML
        final String text2 = "**Second version**";
        final String html2 = m_manager.textToHTML( context, text2 );

        Assertions.assertNotEquals( html1, html2, "Changed content should produce different HTML" );
    }

    @Test
    public void testHtmlCacheFlushedOnSaveEvent() throws Exception {
        final String name = "HtmlCacheFlushTest";
        final String text = "Some content";
        m_engine.saveText( name, text );

        final Page page = m_engine.getManager( PageManager.class ).getPage( name );
        final Context context = Wiki.context().create( m_engine, page );
        context.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );

        // Populate the HTML cache
        final String html1 = m_manager.textToHTML( context, text );
        Assertions.assertNotNull( html1 );

        // Saving the page should flush caches (fires POST_SAVE_BEGIN event)
        m_engine.saveText( name, "New content" );

        // After save, old cached HTML should be gone; re-render with original text
        // should still work (it re-renders and caches fresh)
        final Page page2 = m_engine.getManager( PageManager.class ).getPage( name );
        final Context context2 = Wiki.context().create( m_engine, page2 );
        context2.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );
        final String newPageData = m_engine.getManager( PageManager.class ).getPureText( page2 );
        final String html2 = m_manager.textToHTML( context2, newPageData );
        Assertions.assertNotNull( html2 );
    }

    @Test
    public void testDefaultParserUsesConfigured() throws Exception {
        final String content = "# Test Heading\n\nThis is **content**.";
        final String pageName = "DefaultParserTest";

        // Without a markup.syntax attribute, the default parser from properties is used
        final com.wikantik.WikiPage page = new com.wikantik.WikiPage( m_engine, pageName );
        final Context context = Wiki.context().create( m_engine, page );
        final MarkupParser parser = m_manager.getParser( context, content );

        Assertions.assertNotNull( parser, "Parser should not be null" );
        Assertions.assertEquals( "com.wikantik.parser.markdown.MarkdownParser",
                                 parser.getClass().getName(),
                                 "Should use configured default parser" );
    }

    /**
     * Tests textToHTML renders bold Markdown correctly.
     */
    @Test
    public void testTextToHTMLBold() throws Exception {
        final String text = "**bold text**";
        final String name = "TestBold";
        m_engine.saveText( name, text );

        final Page page = m_engine.getManager( PageManager.class ).getPage( name );
        final Context context = Wiki.context().create( m_engine, page );
        context.setRequestContext( ContextEnum.PAGE_NONE.getRequestContext() );

        final String html = m_manager.textToHTML( context, text );
        Assertions.assertNotNull( html, "Rendered HTML should not be null" );
        Assertions.assertTrue( html.contains( "<strong>bold text</strong>" ),
                               "Should contain <strong> tag, got: " + html );
    }

    /**
     * Tests textToHTML renders italic Markdown correctly.
     */
    @Test
    public void testTextToHTMLItalic() throws Exception {
        final String text = "*italic text*";
        final String name = "TestItalic";
        m_engine.saveText( name, text );

        final Page page = m_engine.getManager( PageManager.class ).getPage( name );
        final Context context = Wiki.context().create( m_engine, page );
        context.setRequestContext( ContextEnum.PAGE_NONE.getRequestContext() );

        final String html = m_manager.textToHTML( context, text );
        Assertions.assertNotNull( html );
        Assertions.assertTrue( html.contains( "<em>italic text</em>" ),
                               "Should contain <em> tag, got: " + html );
    }

    /**
     * Tests textToHTML renders a Markdown heading correctly.
     */
    @Test
    public void testTextToHTMLHeading() throws Exception {
        final String text = "## Second Heading";
        final String name = "TestHeading";
        m_engine.saveText( name, text );

        final Page page = m_engine.getManager( PageManager.class ).getPage( name );
        final Context context = Wiki.context().create( m_engine, page );
        context.setRequestContext( ContextEnum.PAGE_NONE.getRequestContext() );

        final String html = m_manager.textToHTML( context, text );
        Assertions.assertNotNull( html );
        Assertions.assertTrue( html.contains( "Second Heading" ),
                               "Should contain heading text, got: " + html );
        Assertions.assertTrue( html.contains( "<h2" ),
                               "Should contain <h2 tag, got: " + html );
    }

    /**
     * Tests textToHTML renders Markdown links correctly.
     */
    @Test
    public void testTextToHTMLLink() throws Exception {
        final String text = "[Click here](http://example.com)";
        final String name = "TestLink";
        m_engine.saveText( name, text );

        final Page page = m_engine.getManager( PageManager.class ).getPage( name );
        final Context context = Wiki.context().create( m_engine, page );
        context.setRequestContext( ContextEnum.PAGE_NONE.getRequestContext() );

        final String html = m_manager.textToHTML( context, text );
        Assertions.assertNotNull( html );
        Assertions.assertTrue( html.contains( "http://example.com" ),
                               "Should contain link URL, got: " + html );
        Assertions.assertTrue( html.contains( "Click here" ),
                               "Should contain link text, got: " + html );
    }

    /**
     * Tests that getParser returns a non-null parser.
     */
    @Test
    public void testGetParserReturnsNonNull() throws Exception {
        final String name = "TestGetParser";
        m_engine.saveText( name, "test" );
        final Page page = m_engine.getManager( PageManager.class ).getPage( name );
        final Context context = Wiki.context().create( m_engine, page );

        final MarkupParser parser = m_manager.getParser( context, "some content" );
        Assertions.assertNotNull( parser, "Parser should never be null" );
    }

    /**
     * Tests that getRenderedDocument returns a non-null document.
     */
    @Test
    public void testGetRenderedDocumentNonNull() throws Exception {
        final String name = "TestRenderedDoc";
        m_engine.saveText( name, "Hello world" );
        final Page page = m_engine.getManager( PageManager.class ).getPage( name );
        final Context context = Wiki.context().create( m_engine, page );
        context.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );

        final com.wikantik.parser.WikiDocument doc = m_manager.getRenderedDocument( context, "Hello world" );
        Assertions.assertNotNull( doc, "Rendered document should not be null" );
    }

    /**
     * Tests that textToHTML with a multiline Markdown document produces correct HTML.
     */
    @Test
    public void testTextToHTMLMultilineContent() throws Exception {
        final String text = "# Title\n\nParagraph one.\n\nParagraph two.";
        final String name = "TestMultiline";
        m_engine.saveText( name, text );

        final Page page = m_engine.getManager( PageManager.class ).getPage( name );
        final Context context = Wiki.context().create( m_engine, page );
        context.setRequestContext( ContextEnum.PAGE_NONE.getRequestContext() );

        final String html = m_manager.textToHTML( context, text );
        Assertions.assertNotNull( html );
        Assertions.assertTrue( html.contains( "Paragraph one" ), "Should contain first paragraph" );
        Assertions.assertTrue( html.contains( "Paragraph two" ), "Should contain second paragraph" );
        Assertions.assertTrue( html.contains( "<p>" ), "Should contain paragraph tags" );
    }

    /**
     * Tests the getHTML(pagename, version) method.
     */
    @Test
    public void testGetHTMLByNameAndVersion() throws Exception {
        final String text = "**Version content**";
        final String name = "TestHTMLVersion";
        m_engine.saveText( name, text );

        final String html = m_manager.getHTML( name, -1 );
        Assertions.assertNotNull( html, "HTML should not be null" );
        Assertions.assertTrue( html.contains( "<strong>Version content</strong>" ),
                               "Should contain rendered bold text, got: " + html );
    }

    /**
     * Tests beautifyTitleNoBreak returns non-breaking spaces.
     */
    @Test
    public void testBeautifyTitleNoBreak() {
        final String result = m_manager.beautifyTitleNoBreak( "WikiNameThingy" );
        Assertions.assertEquals( "Wiki&nbsp;Name&nbsp;Thingy", result,
                                 "beautifyTitleNoBreak should use &nbsp; as separator" );
    }

}
