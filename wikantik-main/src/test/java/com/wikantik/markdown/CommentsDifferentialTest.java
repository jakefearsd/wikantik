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
package com.wikantik.markdown;

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
import com.wikantik.parser.markdown.ReaderComments;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.markdown.MarkdownRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The checked-in differential corpus ({@code comments-differential.json}) and its render oracle.
 *
 * <p>The corpus is regenerated in memory and must equal the file. To refresh it after changing the generator or
 * {@code ReaderComments}: {@code mvn -o -q test -pl wikantik-main -Dtest=CommentsDifferentialTest#fixtureIsCurrent -Dcomments.differential.write=true}
 * (no exec plugin is configured; the test itself writes the file when that property is set). The {@code allow} list in
 * the file is kept across a refresh.
 *
 * <p>The oracle is the rendered page, not the code under test: {@code theRenderedPageSaysWhereTheCommentsAre} (generated corpus)
 * and {@code theSharedFixtureInputsAgreeWithTheRenderedPage} (every input of {@code comments.json}) place a token at each
 * {@code %%} and read the rendered page to see which markers the reader treats as free.
 */
class CommentsDifferentialTest {

    private static final java.nio.file.Path SHARED_FIXTURE =
            java.nio.file.Path.of( "..", "wikantik-frontend", "src", "utils", "__fixtures__", "comments.json" );
    private static final String WRITE_PROPERTY = "comments.differential.write";

    private final TestEngine testEngine = TestEngine.build(
            TestEngine.with( "wikantik.fileSystemProvider.pageDir", "./target/md-pageDir" ),
            TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
            TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ) );

    @AfterEach
    void tearDown() {
        testEngine.stop();
    }

    /** The `allow` entries of the checked-in file, each as JSON text; none when the file is absent. */
    static List< String > currentAllow() throws IOException {
        final List< String > entries = new ArrayList<>();
        if ( Files.exists( CommentsDifferentialFixture.FILE ) ) {
            final JsonObject root = JsonParser.parseString( Files.readString( CommentsDifferentialFixture.FILE ) ).getAsJsonObject();
            for ( final JsonElement e : root.getAsJsonArray( "allow" ) ) {
                entries.add( e.toString() );
            }
        }
        return entries;
    }

    @Test
    void fixtureIsCurrent() throws IOException {
        final String expected = CommentsDifferentialFixture.render( currentAllow() );
        if ( Boolean.getBoolean( WRITE_PROPERTY ) ) {
            Files.writeString( CommentsDifferentialFixture.FILE, expected, StandardCharsets.UTF_8 );
        }
        assertTrue( Files.exists( CommentsDifferentialFixture.FILE ), "missing " + CommentsDifferentialFixture.FILE + "; " + refreshHint() );
        assertEquals( expected, Files.readString( CommentsDifferentialFixture.FILE ), "comments-differential.json is stale; " + refreshHint() );
    }

    /**
     * The render oracle, independent of {@code ReaderComments}. Every {@code %%} of a case (left to right, not overlapping)
     * becomes a letters-only token, so the text holds no marker and nothing is stripped; the page is rendered, and each
     * token's place in the HTML says what the reader made of that marker: inside {@code <code>} or a math element it is
     * opaque, in ordinary text it is free. The comments are then the free markers paired in order (the next free marker
     * closes, an unterminated one is literal), and must equal {@code ReaderComments.ranges}. Each case runs as stored (LF)
     * and with CRLF line ends. A case whose token is not in the rendered text (an HTML attribute ate it) is skipped.
     */
    @Test
    void theRenderedPageSaysWhereTheCommentsAre() throws Exception {
        final JsonArray cases = JsonParser.parseString( Files.readString( CommentsDifferentialFixture.FILE ) ).getAsJsonObject().getAsJsonArray( "cases" );
        final List< String > failures = new ArrayList<>();
        int checked = 0;
        int skippedUnknown = 0;
        int withComment = 0;
        int opaqueMarkers = 0;
        for ( final JsonElement element : cases ) {
            final String lf = element.getAsJsonObject().get( "input" ).getAsString();
            for ( final String input : new String[] { lf, lf.replace( "\n", "\r\n" ) } ) {
                final List< int[] > expected = renderedRanges( input );
                if ( expected == null ) {
                    skippedUnknown++;
                    continue;
                }
                checked++;
                withComment += expected.isEmpty() ? 0 : 1;
                final List< int[] > actual = ReaderComments.ranges( input );
                if ( !toString( expected ).equals( toString( actual ) ) ) {
                    failures.add( quote( input ) + ": the render says " + toString( expected ) + " but ReaderComments says " + toString( actual ) );
                }
            }
        }
        System.out.println( "render oracle: checked=" + checked + " skippedTokenNotInText=" + skippedUnknown + " withComment=" + withComment );
        assertEquals( List.of(), failures, String.join( "\n", failures ) );
        assertTrue( skippedUnknown < checked / 4, "the oracle skipped too much: " + skippedUnknown + " of " + ( checked + skippedUnknown ) );
        assertTrue( withComment > 1000, "only " + withComment + " variants carry a comment" );
    }

    /**
     * The same independent oracle over every input of the shared fixture {@code comments.json} (LF and CRLF), so the rows
     * the editor, {@code ReaderComments} and the access rules are all pinned to are also placed by the rendered page.
     */
    @Test
    void theSharedFixtureInputsAgreeWithTheRenderedPage() throws Exception {
        final JsonArray rows = JsonParser.parseString( Files.readString( SHARED_FIXTURE, StandardCharsets.UTF_8 ) ).getAsJsonArray();
        final List< String > failures = new ArrayList<>();
        int checked = 0;
        for ( final JsonElement row : rows ) {
            final String lf = row.getAsJsonObject().get( "input" ).getAsString();
            for ( final String input : new String[] { lf, lf.replace( "\r\n", "\n" ).replace( "\n", "\r\n" ) } ) {
                final List< int[] > expected = renderedRanges( input );
                if ( expected == null ) {
                    continue; // no %% marker in the text, or a token an HTML attribute ate
                }
                checked++;
                final List< int[] > actual = ReaderComments.ranges( input );
                if ( !toString( expected ).equals( toString( actual ) ) ) {
                    failures.add( quote( input ) + ": the render says " + toString( expected ) + " but ReaderComments says " + toString( actual ) );
                }
            }
        }
        assertEquals( List.of(), failures, String.join( "\n", failures ) );
        assertTrue( checked > rows.size(), "the oracle checked only " + checked + " of " + ( 2 * rows.size() ) + " variants" );
    }

    /** The comments the rendered page implies for {@code input}, or {@code null} when a marker's token is not in the page text. */
    private List< int[] > renderedRanges( final String input ) throws Exception {
        final List< Integer > at = new ArrayList<>();
        final StringBuilder substituted = new StringBuilder();
        int copied = 0;
        for ( int p = input.indexOf( "%%" ); p >= 0; p = input.indexOf( "%%", p + 2 ) ) {
            substituted.append( input, copied, p ).append( token( at.size() ) );
            at.add( p );
            copied = p + 2;
        }
        substituted.append( input, copied, input.length() );
        final String html = render( substituted.toString() );
        final List< Integer > free = new ArrayList<>();
        for ( int i = 0; i < at.size(); i++ ) {
            final Boolean opaque = opaqueInHtml( html, token( i ) );
            if ( opaque == null ) {
                return null;
            }
            if ( !opaque ) {
                free.add( at.get( i ) );
            }
        }
        final List< int[] > ranges = new ArrayList<>();
        for ( int i = 0; i + 1 < free.size(); i += 2 ) {
            ranges.add( new int[] { free.get( i ), free.get( i + 1 ) + 2 } );
        }
        return ranges;
    }

    /** Letters only: no digit, {@code $}, backtick, {@code |} or {@code %} to change what the markup means. */
    private static String token( final int i ) {
        return "Zq" + (char) ( 'a' + i / 26 ) + (char) ( 'a' + i % 26 ) + "qZ";
    }

    /** {@code TRUE} when the token's first appearance in text is inside code or math, {@code FALSE} in ordinary text, {@code null} when absent from text. */
    private static Boolean opaqueInHtml( final String html, final String token ) {
        for ( int p = html.indexOf( token ); p >= 0; p = html.indexOf( token, p + 1 ) ) {
            if ( html.lastIndexOf( '<', p ) > html.lastIndexOf( '>', p ) ) {
                continue; // inside a tag (an attribute value), not text
            }
            return insideElement( html, p, "<code" ) || insideElement( html, p, "<span class=\"math-inline\"" )
                    || insideElement( html, p, "<div class=\"math-display\"" );
        }
        return null;
    }

    private static boolean insideElement( final String html, final int pos, final String open ) {
        final int start = html.lastIndexOf( open, pos );
        if ( start < 0 ) {
            return false;
        }
        final String name = open.substring( 1, open.indexOf( ' ' ) < 0 ? open.length() : open.indexOf( ' ' ) );
        final int end = html.indexOf( "</" + name + ">", start );
        return end > pos;
    }

    private static String toString( final List< int[] > ranges ) {
        return ranges.stream().map( java.util.Arrays::toString ).toList().toString();
    }

    private static String refreshHint() {
        return "refresh with: mvn -o -q test -pl wikantik-main -Dtest=CommentsDifferentialTest#fixtureIsCurrent -D" + WRITE_PROPERTY + "=true";
    }

    private static String quote( final String s ) {
        return "\"" + s.replace( "\\", "\\\\" ).replace( "\n", "\\n" ).replace( "\r", "\\r" ) + "\"";
    }

    private String render( final String markdown ) throws Exception {
        testEngine.saveText( "DifferentialHost", markdown );
        final Page p = Wiki.contents().page( testEngine, "DifferentialHost" );
        final Context context = Wiki.context().create( testEngine, HttpMockFactory.createHttpRequest(), p );
        return testEngine.getManager( RenderingManager.class ).textToHTML( context, markdown );
    }
}
