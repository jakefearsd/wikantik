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
package com.wikantik.markdown.extensions.blockids;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Renders the shared {@code block-ids.json} cases through the reader's render path; the editor preview
 * ({@code remarkBlockIds.test.jsx}) asserts the same fixture, so the two render block ids alike. A case may carry an
 * {@code editor} entry: what the preview draws instead, and why — the preview draws the raw buffer, rules as chips, the
 * reader the text with its rules stripped. {@code expected} is always the reader's, checked here.
 *
 * <p>What is compared, in document order: every {@code <p>} and {@code <li>}, and every element whose {@code id} starts
 * with {@code ^}, as {@code {tag, id, text}}. {@code text} is the element's text (whitespace collapsed) for a {@code p}
 * or {@code li}, and empty for anything else — containers render differently in the two (KaTeX, callout chrome). Math is
 * left out of a text, and nothing inside an embed ({@code .wiki-embed}) is listed: that is another page's content.
 * {@code data-block} ordinals are server-only and not compared.
 */
class BlockIdParityTest {

    private static final String HOST = "BlockIdParityHost";

    /** A tag, an opening tag's attributes, or a closing tag. */
    private static final Pattern TAG = Pattern.compile( "<(/?)([A-Za-z][A-Za-z0-9-]*)([^>]*?)(/?)>" );
    private static final Pattern ID = Pattern.compile( "\\bid=\"([^\"]*)\"" );
    private static final Pattern CLASS = Pattern.compile( "\\bclass=\"([^\"]*)\"" );
    private static final Set< String > VOID = Set.of( "br", "hr", "img", "input", "meta", "link", "wbr", "col", "source" );

    private final TestEngine testEngine = TestEngine.build(
            TestEngine.with( "wikantik.fileSystemProvider.pageDir", "./target/md-pageDir" ),
            TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
            TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ),
            TestEngine.with( "wikantik.translatorReader.allowHTML", "true" ) );

    @AfterEach
    void tearDown() {
        testEngine.stop();
    }

    @Test
    void matchesTheSharedFrontendFixture() throws Exception {
        final JsonArray cases = JsonParser.parseString( Files.readString(
                Path.of( "..", "wikantik-frontend", "src", "utils", "__fixtures__", "block-ids.json" ) ) ).getAsJsonArray();
        assertFalse( cases.isEmpty() );
        final List< String > mismatches = new ArrayList<>();
        for ( final JsonElement e : cases ) {
            final JsonObject c = e.getAsJsonObject();
            if ( c.has( "pages" ) ) {
                for ( final JsonElement page : c.getAsJsonArray( "pages" ) ) {
                    testEngine.saveText( page.getAsString(), "Embedded body.\n" );
                }
            }
            if ( c.has( "editor" ) && ( !c.getAsJsonObject( "editor" ).has( "why" )
                    || c.getAsJsonObject( "editor" ).get( "why" ).getAsString().isBlank() ) ) {
                mismatches.add( c.get( "name" ).getAsString() + ": an editor-only expectation must say why it differs" );
            }
            final String html = render( c.get( "markdown" ).getAsString() );
            final JsonArray actual = describe( html );
            if ( !c.getAsJsonArray( "expected" ).equals( actual ) ) {
                mismatches.add( c.get( "name" ).getAsString() + "\n  expected " + c.getAsJsonArray( "expected" )
                        + "\n  actual   " + actual + "\n  html     " + html );
            }
        }
        assertEquals( List.of(), mismatches, String.join( "\n", mismatches ) );
    }

    private String render( final String markdown ) throws Exception {
        testEngine.saveText( HOST, markdown );
        final Page p = Wiki.contents().page( testEngine, HOST );
        final Context context = Wiki.context().create( testEngine, HttpMockFactory.createHttpRequest(), p );
        return testEngine.getManager( RenderingManager.class ).textToHTML( context, markdown );
    }

    /** One open element while scanning. */
    private static final class Open {
        final String tag;
        final JsonObject listed;          // its fixture entry, when it is listed
        final StringBuilder text = new StringBuilder();
        final boolean skipText;           // math: its text is left out
        final boolean embed;              // .wiki-embed: nothing inside is listed

        Open( final String tag, final JsonObject listed, final boolean skipText, final boolean embed ) {
            this.tag = tag;
            this.listed = listed;
            this.skipText = skipText;
            this.embed = embed;
        }
    }

    /** The listed elements of {@code html}, in document order. */
    static JsonArray describe( final String html ) {
        final JsonArray out = new JsonArray();
        final Deque< Open > stack = new ArrayDeque<>();
        final Matcher m = TAG.matcher( html );
        int pos = 0;
        while ( m.find() ) {
            appendText( stack, decode( html.substring( pos, m.start() ) ) );
            pos = m.end();
            final String tag = m.group( 2 ).toLowerCase();
            if ( !m.group( 1 ).isEmpty() ) {
                close( stack, tag );
                continue;
            }
            if ( VOID.contains( tag ) || !m.group( 4 ).isEmpty() ) {
                continue;
            }
            final String attrs = m.group( 3 );
            final String id = attr( ID, attrs );
            final String cls = attr( CLASS, attrs );
            final boolean insideEmbed = stack.stream().anyMatch( o -> o.embed );
            JsonObject listed = null;
            if ( !insideEmbed && ( "p".equals( tag ) || "li".equals( tag ) || id != null && id.startsWith( "^" ) ) ) {
                listed = new JsonObject();
                listed.addProperty( "tag", tag );
                if ( id != null && id.startsWith( "^" ) ) {
                    listed.addProperty( "id", id );
                } else {
                    listed.add( "id", JsonNull.INSTANCE );
                }
                listed.addProperty( "text", "" );
                out.add( listed );
            }
            final boolean math = cls != null && ( cls.contains( "math" ) || cls.contains( "katex" ) );
            stack.push( new Open( tag, listed, math, cls != null && cls.matches( "(^|.*\\s)wiki-embed(\\s.*|$)" ) ) );
        }
        return out;
    }

    private static void appendText( final Deque< Open > stack, final String text ) {
        if ( text.isEmpty() || stack.stream().anyMatch( o -> o.skipText ) ) {
            return;
        }
        for ( final Open o : stack ) {
            o.text.append( text );
        }
    }

    private static void close( final Deque< Open > stack, final String tag ) {
        while ( !stack.isEmpty() ) {
            final Open o = stack.pop();
            if ( o.listed != null && ( "p".equals( o.tag ) || "li".equals( o.tag ) ) ) {
                o.listed.addProperty( "text", o.text.toString().replaceAll( "\\s+", " " ).trim() );
            }
            if ( o.tag.equals( tag ) ) {
                return;
            }
        }
    }

    private static String attr( final Pattern p, final String attrs ) {
        final Matcher m = p.matcher( attrs );
        return m.find() ? decode( m.group( 1 ) ) : null;
    }

    private static String decode( final String s ) {
        return s.replace( "&lt;", "<" ).replace( "&gt;", ">" ).replace( "&quot;", "\"" ).replace( "&#34;", "\"" )
                .replace( "&#39;", "'" ).replace( "&#x27;", "'" ).replace( "&#61;", "=" ).replace( "&nbsp;", " " ).replace( "&amp;", "&" );
    }
}
