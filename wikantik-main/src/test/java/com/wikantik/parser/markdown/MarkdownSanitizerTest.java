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

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.render.markdown.MarkdownRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;

/**
 * Verifies that the Markdown rendering pipeline strips dangerous HTML and
 * refuses to emit {@code javascript:} URIs even when
 * {@code wikantik.translatorReader.allowHTML=true}.
 */
public class MarkdownSanitizerTest {

    private static final String PAGE_NAME = "sanitizerpage";

    TestEngine engine = TestEngine.build(
            TestEngine.with( "wikantik.fileSystemProvider.pageDir", "./target/md-sanitizer-pageDir" ),
            TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
            TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ),
            TestEngine.with( "wikantik.translatorReader.allowHTML", "true" ) );

    @AfterEach
    public void tearDown() {
        engine.stop();
    }

    @Test
    public void rejectsScriptWhenAllowHtmlTrue() throws Exception {
        final String out = translate( "hello <script>alert(1)</script> world" );
        Assertions.assertFalse( out.toLowerCase().contains( "<script" ),
                "sanitizer should strip script tags: " + out );
        Assertions.assertFalse( out.toLowerCase().contains( "alert(1)" ),
                "sanitizer should drop inline script body: " + out );
    }

    @Test
    public void rejectsOnErrorImgWhenAllowHtmlTrue() throws Exception {
        final String out = translate( "<img src=\"x\" onerror=\"alert(1)\">" );
        Assertions.assertFalse( out.toLowerCase().contains( "onerror" ),
                "sanitizer should strip event handler attributes: " + out );
    }

    @Test
    public void rejectsIframeWhenAllowHtmlTrue() throws Exception {
        final String out = translate( "<iframe src=\"https://evil.example\"></iframe>" );
        Assertions.assertFalse( out.toLowerCase().contains( "<iframe" ),
                "sanitizer should strip iframe tags: " + out );
    }

    @Test
    public void rejectsJavascriptUriInMarkdownLink() throws Exception {
        final String out = translate( "[click](javascript:alert(1))" );
        Assertions.assertFalse( out.toLowerCase().contains( "javascript:" ),
                "sanitizer should strip javascript: URIs from links: " + out );
    }

    @Test
    public void rejectsJavascriptUriInRawHtmlAnchor() throws Exception {
        final String out = translate( "<a href=\"javascript:alert(1)\">click</a>" );
        Assertions.assertFalse( out.toLowerCase().contains( "javascript:" ),
                "sanitizer should strip javascript: URIs from raw HTML anchors: " + out );
    }

    @Test
    public void allowsExternalHttpsLink() throws Exception {
        final String out = translate( "[external](https://example.com/path)" );
        Assertions.assertTrue( out.contains( "href=\"https://example.com/path\"" ),
                "safe external HTTPS link should survive sanitization: " + out );
    }

    private String translate( final String src ) throws Exception {
        final Page page = Wiki.contents().page( engine, PAGE_NAME );
        engine.saveText( PAGE_NAME, "placeholder" );
        final Context context = Wiki.context().create( engine, HttpMockFactory.createHttpRequest(), page );
        final MarkdownParser parser = new MarkdownParser( context, new BufferedReader( new StringReader( src ) ) );
        final MarkdownRenderer renderer = new MarkdownRenderer( context, parser.parse() );
        return renderer.getString();
    }
}
