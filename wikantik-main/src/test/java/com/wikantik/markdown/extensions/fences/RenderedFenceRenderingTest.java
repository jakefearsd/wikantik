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
package com.wikantik.markdown.extensions.fences;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.render.RenderingManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.parser.markdown.MarkdownDocument;
import com.wikantik.parser.markdown.WikantikHtmlSanitizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class RenderedFenceRenderingTest {

    private static final Pattern ENVELOPE = Pattern.compile(
            "<figure class=\"wk-fence\"[^>]*>.*?<code class=\"language-([^\"]+)\">(.*?)</code>", Pattern.DOTALL );

    private static TestEngine engine;

    @BeforeAll
    static void boot() {
        engine = TestEngine.build( TestEngine.with( "wikantik.translatorReader.allowHTML", "false" ) );
    }

    @AfterAll
    static void shutdown() {
        engine.stop();
    }

    private static String unescape( final String s ) {
        return s.replace( "&lt;", "<" ).replace( "&gt;", ">" ).replace( "&quot;", "\"" )
                .replace( "&#39;", "'" ).replace( "&#96;", "`" ).replace( "&amp;", "&" );
    }

    private static String render( final TestEngine e, final String name, final String markdown ) throws Exception {
        e.saveText( name, markdown );
        final Page page = e.getManager( PageManager.class ).getPage( name );
        final Context ctx = Wiki.context().create( e, page );
        return e.getManager( RenderingManager.class ).textToHTML( ctx, markdown );
    }

    private static MutableDataSet bareOptions( final RenderedFenceRegistry registry ) {
        final MutableDataSet o = new MutableDataSet( MarkdownDocument.structuralOptions() );
        // structuralOptions() already carries the production RenderedFenceExtension; swap it for ours.
        final List< com.vladsch.flexmark.util.misc.Extension > exts = new ArrayList<>( Parser.EXTENSIONS.get( o ) );
        exts.removeIf( x -> x instanceof RenderedFenceExtension );
        exts.add( RenderedFenceExtension.create( registry ) );
        o.set( Parser.EXTENSIONS, exts );
        return o;
    }

    private static String bare( final RenderedFenceRegistry registry, final String md ) {
        final MutableDataSet o = bareOptions( registry );
        return HtmlRenderer.builder( o ).build().render( Parser.builder( o ).build().parse( md ) );
    }

    @Test
    void documentsFixtureProducesEnvelopes() throws Exception {
        final JsonArray docs = JsonParser.parseString( Files.readString(
                Path.of( "..", "wikantik-frontend", "src", "utils", "__fixtures__", "renderedFences.json" ) ) )
                .getAsJsonObject().getAsJsonArray( "documents" );
        assertFalse( docs.isEmpty() );
        int n = 0;
        for ( final JsonElement e : docs ) {
            final JsonObject c = e.getAsJsonObject();
            final String html = render( engine, "FenceDoc" + n++, c.get( "markdown" ).getAsString() );
            final List< String > got = new ArrayList<>();
            final Matcher m = ENVELOPE.matcher( html );
            while ( m.find() ) {
                got.add( m.group( 1 ) + "|" + unescape( m.group( 2 ) ) );
            }
            final List< String > want = new ArrayList<>();
            for ( final JsonElement f : c.getAsJsonArray( "fences" ) ) {
                want.add( f.getAsJsonObject().get( "language" ).getAsString() + "|"
                        + f.getAsJsonObject().get( "source" ).getAsString() );
            }
            assertEquals( want, got, c.get( "name" ).getAsString() + " :: " + html );
        }
    }

    @Test
    void unregisteredAndExampleFencesUnchanged() {
        final String md = "```java\nint x = 1;\n```\n\n```mermaid example\nflowchart TD\n  A-->B\n```\n\n"
                + "    indented code\n\n```\nplain\n```\n";
        final String with = bare( RenderedFenceRegistry.defaults(), md );
        assertEquals( bare( RenderedFenceRegistry.of(), md ), with );
        assertFalse( with.contains( "wk-fence" ), with );
        assertTrue( with.contains( "<code class=\"language-java\">" ), with );
    }

    @Test
    void mathFenceStillRendersAsMath() throws Exception {
        final String html = render( engine, "FenceMath", "```math\nx^2\n```\n" );
        assertTrue( html.contains( "math-display" ), html );
        assertFalse( html.contains( "wk-fence" ), html );
    }

    @Test
    void escapesSourceAsText() throws Exception {
        final String src = "</code></pre><script>alert(1)</script> &amp; <br/>";
        final String html = render( engine, "FenceEscape", "```mermaid\n" + src + "\n```\n" );
        assertFalse( html.contains( "<script" ), html );
        final Matcher m = ENVELOPE.matcher( html );
        assertTrue( m.find(), html );
        assertEquals( src + "\n", unescape( m.group( 2 ) ) );
    }

    @Test
    void refusedFenceCarriesReason() throws Exception {
        final String src = "flowchart TD\n  A-->B\n  click A call doThing()\n";
        final String html = render( engine, "FenceRefused", "```mermaid\n" + src + "```\n" );
        assertTrue( html.contains( "data-fence-error=\"callback\"" ), html );
        final Matcher m = ENVELOPE.matcher( html );
        assertTrue( m.find(), html );
        assertEquals( src, unescape( m.group( 2 ) ) );
        assertFalse( html.contains( "wk-fence-drawing" ), html );
    }

    @Test
    void browserEnvelopeShape() throws Exception {
        final String html = render( engine, "FenceShape", "```mermaid\nflowchart TD\n```\n" );
        assertTrue( html.contains( "<figure class=\"wk-fence\" data-fence=\"mermaid\" data-fence-kind=\"browser\">" ), html );
        assertTrue( html.contains( "<pre class=\"wk-fence-source\"><code class=\"language-mermaid\">" ), html );
        assertFalse( html.contains( "data-fence-error" ), html );
    }

    @Test
    void serverKindEmitsDrawingAndSource() {
        final String html = bare( RenderedFenceRegistry.of( new FakeServerRenderer() ), "```fake\nhello\n```\n" );
        assertTrue( html.contains( "data-fence-kind=\"server\"" ), html );
        assertTrue( html.contains( "<div class=\"wk-fence-drawing\"><svg-ish>drawn:6</svg-ish></div>" ), html );
        assertTrue( html.indexOf( "wk-fence-drawing" ) < html.indexOf( "wk-fence-source" ), html );
        assertTrue( html.contains( "<code class=\"language-fake\">hello\n</code>" ), html );
        assertFalse( html.contains( "data-fence-error" ), html );
    }

    @Test
    void serverKindFailureEmitsRenderFailed() {
        final String html = bare( RenderedFenceRegistry.of( new FakeServerRenderer() ), "```fake\nboom\n```\n" );
        assertTrue( html.contains( "data-fence-error=\"render-failed\"" ), html );
        assertFalse( html.contains( "wk-fence-drawing" ), html );
        assertTrue( html.contains( "<code class=\"language-fake\">boom\n</code>" ), html );
    }

    @Test
    void serverKindRefusalHasNoDrawing() {
        final String html = bare( RenderedFenceRegistry.of( new FakeServerRenderer() ), "```fake\nrefuse\n```\n" );
        assertTrue( html.contains( "data-fence-error=\"callback\"" ), html );
        assertFalse( html.contains( "wk-fence-drawing" ), html );
    }

    @Test
    void envelopeSurvivesSanitizer() {
        final String html = bare( RenderedFenceRegistry.of( new FakeServerRenderer() ), "```fake\nhello\n```\n" );
        final String out = WikantikHtmlSanitizer.sanitize( html );
        assertTrue( out.contains( "data-fence=\"fake\"" ), out );
        assertTrue( out.contains( "data-fence-kind=\"server\"" ), out );
        assertTrue( out.contains( "class=\"wk-fence\"" ), out );
        assertTrue( out.contains( "<div class=\"wk-fence-drawing\">" ), out );
        assertTrue( out.contains( "<code class=\"language-fake\">" ), out );
        final String err = WikantikHtmlSanitizer.sanitize(
                bare( RenderedFenceRegistry.of( new FakeServerRenderer() ), "```fake\nboom\n```\n" ) );
        assertTrue( err.contains( "data-fence-error=\"render-failed\"" ), err );
    }
}
