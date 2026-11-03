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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * <p>The oracle is the rendered page, not the code under test: {@code everyCaseRendersAsItsRangesSay} renders each case
 * with a sentinel inside every comment (must be hidden) and a sentinel just outside every comment (must show).
 */
class CommentsDifferentialTest {

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

    @Test
    void everyCaseRendersAsItsRangesSay() throws Exception {
        final JsonObject root = JsonParser.parseString( Files.readString( CommentsDifferentialFixture.FILE ) ).getAsJsonObject();
        final Set< String > allowed = new HashSet<>();
        for ( final JsonElement e : root.getAsJsonArray( "allow" ) ) {
            allowed.add( e.getAsJsonObject().get( "input" ).getAsString() );
        }
        final JsonArray cases = root.getAsJsonArray( "cases" );
        final List< String > failures = new ArrayList<>();
        int checked = 0;
        int inside = 0;
        int outside = 0;
        for ( final JsonElement element : cases ) {
            final String input = element.getAsJsonObject().get( "input" ).getAsString();
            final List< int[] > ranges = ReaderComments.ranges( input );
            if ( ranges.isEmpty() ) {
                continue;
            }
            checked++;
            final String hiding = withInteriorsReplaced( input, ranges );
            if ( sameRanges( ranges, ReaderComments.ranges( hiding ) ) ) {
                inside++;
                final String html = render( hiding );
                for ( int i = 0; i < ranges.size(); i++ ) {
                    if ( html.contains( sentinelInside( i ) ) ) {
                        failures.add( "comment " + i + " shows in " + quote( input ) + " -> " + html );
                    }
                }
            }
            final String showing = withMarkersAround( input, ranges );
            if ( sameRanges( ranges, ReaderComments.ranges( showing ) ) ) {
                outside++;
                final String html = render( showing );
                for ( int i = 0; i < ranges.size(); i++ ) {
                    final int[] r = ranges.get( i );
                    // a sentinel inside a raw-HTML attribute value is never page text (the sanitizer drops the attribute)
                    final boolean beforeShows = inAttribute( input, ranges, r[ 0 ] ) || html.contains( sentinelBefore( i ) );
                    final boolean afterShows = inAttribute( input, ranges, r[ 1 ] ) || html.contains( sentinelAfter( i ) );
                    if ( !beforeShows || !afterShows ) {
                        failures.add( "text beside comment " + i + " is hidden in " + quote( input ) + " -> " + html );
                    }
                }
            }
        }
        assertTrue( checked > 600, "only " + checked + " cases carry a comment" );
        assertTrue( inside > 400 && outside > 400, "too few sentinel variants kept their ranges: inside=" + inside + " outside=" + outside );
        assertEquals( List.of(), failures, String.join( "\n", failures ) );
    }

    /** Whether {@code pos} lies inside the value of a {@code <b t="…"} attribute (the generator's only raw HTML); comment text is not read. */
    private static boolean inAttribute( final String input, final List< int[] > ranges, final int pos ) {
        final int open = input.lastIndexOf( "<b t=\"", pos - 1 );
        if ( open < 0 ) {
            return false;
        }
        for ( int q = input.indexOf( '"', open + 6 ); q >= 0 && q < pos; q = input.indexOf( '"', q + 1 ) ) {
            final int at = q;
            if ( ranges.stream().noneMatch( r -> at >= r[ 0 ] && at < r[ 1 ] ) ) {
                return false;
            }
        }
        return true;
    }

    private static String refreshHint() {
        return "refresh with: mvn -o -q test -pl wikantik-main -Dtest=CommentsDifferentialTest#fixtureIsCurrent -D" + WRITE_PROPERTY + "=true";
    }

    private static String sentinelInside( final int i ) {
        return "qZq" + i + "qZq";
    }

    private static String sentinelBefore( final int i ) {
        return "ZKb" + i + "K";
    }

    private static String sentinelAfter( final int i ) {
        return "ZKa" + i + "K";
    }

    /** Each comment's text between its {@code %%} pair becomes a sentinel; the line breaks it held stay. */
    private static String withInteriorsReplaced( final String input, final List< int[] > ranges ) {
        final StringBuilder out = new StringBuilder();
        int copied = 0;
        for ( int i = 0; i < ranges.size(); i++ ) {
            final int[] r = ranges.get( i );
            out.append( input, copied, r[ 0 ] + 2 ).append( sentinelInside( i ) );
            for ( int p = r[ 0 ] + 2; p < r[ 1 ] - 2; p++ ) {
                final char c = input.charAt( p );
                if ( c == '\n' || c == '\r' ) {
                    out.append( c );
                }
            }
            copied = r[ 1 ] - 2;
        }
        return out.append( input, copied, input.length() ).toString();
    }

    /** A sentinel directly before and directly after each comment, outside every range. */
    private static String withMarkersAround( final String input, final List< int[] > ranges ) {
        final StringBuilder out = new StringBuilder();
        int copied = 0;
        for ( int i = 0; i < ranges.size(); i++ ) {
            final int[] r = ranges.get( i );
            out.append( input, copied, r[ 0 ] ).append( sentinelBefore( i ) ).append( input, r[ 0 ], r[ 1 ] ).append( sentinelAfter( i ) );
            copied = r[ 1 ];
        }
        return out.append( input, copied, input.length() ).toString();
    }

    /** Same number of comments, with the same text between the markers ignored: only the count must hold. */
    private static boolean sameRanges( final List< int[] > expected, final List< int[] > actual ) {
        return expected.size() == actual.size();
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
