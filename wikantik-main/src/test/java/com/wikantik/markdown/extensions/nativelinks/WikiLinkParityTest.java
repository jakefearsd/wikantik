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
package com.wikantik.markdown.extensions.nativelinks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.parser.markdown.MarkdownParser;
import com.wikantik.render.markdown.MarkdownRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Renders the shared {@code wikilinks.json} cases server-side; the frontend asserts the same fixture. */
class WikiLinkParityTest {

    private static final String HOST = "WikilinkParityHost";
    private static final Pattern ANCHOR = Pattern.compile( "<a\\s([^>]*)>(.*?)</a>", Pattern.DOTALL );
    private static final Pattern HREF = Pattern.compile( "href=\"([^\"]*)\"" );
    private static final Pattern CLASS = Pattern.compile( "class=\"([^\"]*)\"" );

    private final TestEngine testEngine = TestEngine.build(
            TestEngine.with( "wikantik.translatorReader.matchEnglishPlurals", "true" ),
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
                Path.of( "..", "wikantik-frontend", "src", "utils", "__fixtures__", "wikilinks.json" ) ) ).getAsJsonArray();
        assertFalse( cases.isEmpty() );
        for ( final JsonElement e : cases ) {
            final JsonObject c = e.getAsJsonObject();
            for ( final JsonElement page : c.getAsJsonArray( "pages" ) ) {
                testEngine.saveText( page.getAsString(), "## My Heading\n\nBody.\n" );
            }
            assertEquals( c.getAsJsonArray( "expected" ), anchors( render( c.get( "markdown" ).getAsString() ) ),
                    c.get( "name" ).getAsString() );
        }
    }

    private String render( final String markdown ) throws Exception {
        testEngine.saveText( HOST, markdown );
        final Page p = Wiki.contents().page( testEngine, HOST );
        final Context context = Wiki.context().create( testEngine, HttpMockFactory.createHttpRequest(), p );
        final MarkdownParser tr = new MarkdownParser( context, new BufferedReader( new StringReader( markdown ) ) );
        return new MarkdownRenderer( context, tr.parse() ).getString();
    }

    private static JsonArray anchors( final String html ) {
        final JsonArray out = new JsonArray();
        final Matcher m = ANCHOR.matcher( html );
        while ( m.find() ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "href", canonicalHref( attr( HREF, m.group( 1 ) ) ) );
            o.addProperty( "class", attr( CLASS, m.group( 1 ) ).contains( "createpage" ) ? "createpage" : "" );
            o.addProperty( "text", m.group( 2 ).replaceAll( "<[^>]*>", "" ).replace( "&gt;", ">" ).replace( "&lt;", "<" )
                    .replace( "&#34;", "\"" ).replace( "&quot;", "\"" ).replace( "&amp;", "&" ) );
            out.add( o );
        }
        return out;
    }

    private static String attr( final Pattern p, final String attrs ) {
        final Matcher m = p.matcher( attrs );
        return m.find() ? m.group( 1 ) : "";
    }

    private static String canonicalHref( final String raw ) {
        for ( final String prefix : new String[] { "/wiki/", "/edit/" } ) {
            final int i = raw.indexOf( prefix );
            if ( i >= 0 ) {
                return URLDecoder.decode( raw.substring( i + prefix.length() ), StandardCharsets.UTF_8 );
            }
        }
        return raw;
    }
}
