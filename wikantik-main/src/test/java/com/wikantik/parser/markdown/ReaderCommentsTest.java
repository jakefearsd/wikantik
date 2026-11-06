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

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.wikantik.TestEngine;
import com.wikantik.HttpMockFactory;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.markdown.MarkdownRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReaderCommentsTest {

    private final TestEngine testEngine = TestEngine.build(
            TestEngine.with( "wikantik.fileSystemProvider.pageDir", "./target/md-pageDir" ),
            TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
            TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ) );

    @AfterEach
    void tearDown() {
        testEngine.stop();
    }

    private static final Path FIXTURE = Path.of( "..", "wikantik-frontend", "src", "utils", "__fixtures__", "comments.json" );

    private static List< String > fixtureInputs() throws Exception {
        final List< String > inputs = new ArrayList<>();
        for ( final JsonElement row : JsonParser.parseString( Files.readString( FIXTURE ) ).getAsJsonArray() ) {
            inputs.add( row.getAsJsonObject().get( "input" ).getAsString() );
        }
        return inputs;
    }

    private static String render( final List< int[] > ranges ) {
        return ranges.stream().map( r -> r[ 0 ] + "-" + r[ 1 ] ).toList().toString();
    }

    @Test
    void crlfBodiesMatchTheirLfForm() throws Exception {
        final List< String > mismatches = new ArrayList<>();
        for ( final String lf : fixtureInputs() ) {
            final String crlf = lf.replace( "\n", "\r\n" );
            final List< int[] > expected = new ArrayList<>();
            for ( final int[] r : ReaderComments.ranges( lf ) ) {
                expected.add( new int[]{ r[ 0 ] + crs( lf, r[ 0 ] ), r[ 1 ] + crs( lf, r[ 1 ] ) } );
            }
            if ( !render( expected ).equals( render( ReaderComments.ranges( crlf ) ) ) ) {
                mismatches.add( lf.replace( "\n", "\\n" ) + ": expected " + render( expected ) + " but was " + render( ReaderComments.ranges( crlf ) ) );
            }
        }
        assertEquals( List.of(), mismatches, String.join( "\n", mismatches ) );
    }

    /** The number of newlines in {@code lf} before {@code offset}: each one gains a carriage return in the CRLF form. */
    private static int crs( final String lf, final int offset ) {
        int n = 0;
        for ( int i = 0; i < offset; i++ ) {
            if ( lf.charAt( i ) == '\n' ) {
                n++;
            }
        }
        return n;
    }

    @Test
    void aCommentAroundAPluginHidesItAndMapsBack() {
        final String body = "a %%[{TableOfContents}]%% b";
        assertEquals( "[2-25]", render( ReaderComments.ranges( body ) ) );
        assertEquals( "a  b", ReaderComments.strip( body ) );
    }

    @Test
    void aCommentSpanningDisplayMathIsInBodyCoordinates() {
        final String body = "x %%c\n\n$$\ny\n$$\n\nd%% e";
        final List< int[] > ranges = ReaderComments.ranges( body );
        assertEquals( 1, ranges.size(), render( ranges ) );
        assertEquals( body.indexOf( "%%" ), ranges.get( 0 )[ 0 ] );
        assertEquals( body.lastIndexOf( "%%" ) + 2, ranges.get( 0 )[ 1 ] );
        assertEquals( List.of(), ReaderComments.ranges( "$$\n%%no%%\n$$" ).stream().map( r -> r[ 0 ] ).toList() );
    }

    @Test
    void emptyInputsCostNoParse() {
        final long before = ReaderStructure.parses();
        for ( final String body : new String[]{ null, "", "plain" } ) {
            assertEquals( List.of(), ReaderComments.ranges( body ) );
            assertSame( body, ReaderComments.strip( body ) );
        }
        assertEquals( before, ReaderStructure.parses() );
    }

    @Test
    void rangesReadOffAnExistingStructureAreTheSameAndCostNoParse() {
        final String frontmatter = "---\ntitle: x\n---\n";
        final String body = "a %%x%% `%%code%%` b %%y\nz%% $%%m%%$ c\n";
        final int bodyStart = frontmatter.length();
        final RenderPathAccessRules.Structure structure = ReaderStructure.of( body, bodyStart );
        final long before = ReaderStructure.parses();
        final List< int[] > fromStructure = ReaderComments.ranges( body, structure, bodyStart );
        assertEquals( before, ReaderStructure.parses(), "no second parse" );
        assertEquals( render( ReaderComments.ranges( body ) ), render( fromStructure ) );
        assertEquals( 2, fromStructure.size(), render( fromStructure ) );
    }

    @Test
    void theParseCountIsThePerThreadCountSoOtherThreadsCannotMoveIt() throws Exception {
        final long before = ReaderStructure.parses();
        final Thread other = new Thread( () -> ReaderComments.ranges( "a %%x%% b" ) );
        other.start();
        other.join();
        assertEquals( before, ReaderStructure.parses(), "another thread's parse is not this thread's" );
        ReaderComments.ranges( "a %%x%% b" );
        assertEquals( before + 1, ReaderStructure.parses() );
    }

    @Test
    void stripReturnsTheSameInstanceWhenNothingIsRemoved() {
        final String body = "a %%unterminated and `%%code%%`";
        assertSame( body, ReaderComments.strip( body ) );
    }

    @Test
    void anUnreadableParseHidesEveryFreePair() {
        final String body = "a `%%x%%` b";
        assertEquals( "[3-8]", render( ReaderComments.ranges( body, 1 ) ), "code is ignored when the parse is cut off" );
        assertEquals( "[]", render( ReaderComments.ranges( body ) ), "code is read when the parse completes" );
    }

    @Test
    void anEmptyDisplayMathFenceHoldsNothingButItsOpener() {
        final String body = "$$\n```\n%%secret%%\n```\n$$\n";
        assertEquals( "[0-6]", render( ReaderStructure.of( body, 0 ).renderedMath() ),
                "the inner fence closes the generated math fence at once (the render draws an empty math block)" );
        assertEquals( "[7-17]", render( ReaderComments.ranges( body ) ), "the comment is a paragraph of its own in the render" );
    }

    /** The sentinel words of {@code body}'s comments are absent from the rendered page exactly when {@code strip} removed them. */
    private void assertSentinels( final String body, final List< String > hidden, final List< String > shown ) throws Exception {
        final String html = render( body );
        final String stripped = ReaderComments.strip( body );
        for ( final String word : hidden ) {
            assertFalse( html.contains( word ), () -> "the render shows " + word + " in " + body + ": " + html );
            assertFalse( stripped.contains( word ), () -> "strip keeps " + word + " in " + body );
        }
        for ( final String word : shown ) {
            assertTrue( html.contains( word ), () -> "the render hides " + word + " in " + body + ": " + html );
            assertTrue( stripped.contains( word ), () -> "strip removes " + word + " in " + body );
        }
    }

    private String render( final String markdown ) throws Exception {
        testEngine.saveText( "ReaderCommentsHost", markdown );
        final Page p = Wiki.contents().page( testEngine, "ReaderCommentsHost" );
        final Context context = Wiki.context().create( testEngine, HttpMockFactory.createHttpRequest(), p );
        return testEngine.getManager( RenderingManager.class ).textToHTML( context, markdown );
    }

    @Test
    void rangesLocateEachCommentDelimitersIncluded() throws Exception {
        final String text = "a %%xsent%% b %%ysent\nzsent%% c `%%ksent%%`";
        final List< String > found = new ArrayList<>();
        for ( final int[] r : ReaderComments.ranges( text ) ) {
            found.add( text.substring( r[ 0 ], r[ 1 ] ) );
        }
        assertEquals( List.of( "%%xsent%%", "%%ysent\nzsent%%" ), found );
        assertSentinels( text, List.of( "xsent", "ysent", "zsent" ), List.of( "ksent" ) );
        assertEquals( List.of(), ReaderComments.ranges( "a %%b" ) );
        assertEquals( List.of(), ReaderComments.ranges( null ) );
        assertEquals( null, ReaderComments.strip( null ) );
    }

    @Test
    void mathAcrossPlainProseLinesStillHidesNothing() throws Exception {
        assertEquals( "see $a\n%%xsent%% b$ end", ReaderComments.strip( "see $a\n%%xsent%% b$ end" ) );
        assertSentinels( "see $a\n%%xsent%% b$ end", List.of(), List.of( "xsent" ) );
        assertEquals( "$$a \n\n c$$", ReaderComments.strip( "$$a %%xsent\n\nbsent%% c$$" ) );
        assertSentinels( "$$a %%xsent\n\nbsent%% c$$", List.of( "xsent", "bsent" ), List.of() );
    }

    @Test
    void aPipeLessLineAfterATableEndsItSoMathMaySpanIt() throws Exception {
        final String after = "A | B\n--- | ---\nx | y\n$a\n%%csent%% b$ end\n";
        assertSame( after, ReaderComments.strip( after ) );
        assertSentinels( after, List.of(), List.of( "csent" ) );
    }

    @Test
    void anEscapedPipeDoesNotSplitMathInATableCell() throws Exception {
        final String text = "| A |\n|---|\n| $a\\|%%xsent%%b$ |\n";
        assertSame( text, ReaderComments.strip( text ) );
        assertSentinels( text, List.of(), List.of( "xsent" ) );
    }

    /** Each input has tens of thousands of markers, backticks, dollars, fences or quote levels: a quadratic scan takes minutes. */
    @Test
    void adversarialInputsStayLinear() {
        final int n = 80_000;
        final String[] inputs = {
            "%%`".repeat( n ), "`%%".repeat( n ), "%%x$".repeat( n ), "$a ".repeat( n ) + "%%", "%% " + "`%%` ".repeat( n ),
            "%%x ".repeat( n ), "$$ ".repeat( n ) + "%%", "$$x %% ".repeat( n ), "```\n%%\n```\n".repeat( n / 4 ),
            "> ```\n> %%\n> ```\n".repeat( n / 4 ), "%%\n> ```\n".repeat( n / 4 ), ">".repeat( 2_000 ) + " ```\n%%x%%\n",
            // flexmark's setext check is quadratic in a block quote's non-letter paragraph lines (#129): kept small until that is fixed
            "> ".repeat( 10 ) + "~~~\n" + "> %%x%%\n".repeat( 500 ),
            "%% x " + ( "a $b " ).repeat( 20_000 ) + "\n%%\n",
        };
        for ( final String text : inputs ) {
            assertTimeoutPreemptively( Duration.ofSeconds( 20 ), () -> ReaderComments.strip( text ),
                    () -> "slow on " + text.substring( 0, 12 ).replace( "\n", "\\n" ) + "..." );
        }
    }

    @Test
    void linearInComments() {
        assertTimeoutPreemptively( Duration.ofSeconds( 120 ), () -> {
            final double[] result = linearCost( n -> "%%a%% `b` $c$ ".repeat( n ), ReaderCommentsTest::runRanges, 2000, 4 );
            assertTrue( result[ 0 ] < result[ 1 ], "ratio " + result[ 0 ] + " must stay under " + result[ 1 ] );
        } );
    }

    private static void runRanges( final String body ) {
        assertTrue( ReaderComments.ranges( body ).size() > 0 );
    }

    /**
     * What the frontend's {@code linearCost} measures: a warm-up, then the small and large inputs timed alternately, the
     * best of five each; returns {ratio, bound} with bound = factor^1.5, between linear and quadratic.
     */
    private static double[] linearCost( final IntFunction< String > make, final java.util.function.Consumer< String > run, final int small,
                                        final int factor ) {
        final String a = make.apply( small );
        final String b = make.apply( small * factor );
        run.accept( a );
        long bestA = Long.MAX_VALUE;
        long bestB = Long.MAX_VALUE;
        for ( int i = 0; i < 5; i++ ) {
            bestA = Math.min( bestA, nanos( run, a ) );
            bestB = Math.min( bestB, nanos( run, b ) );
        }
        return new double[]{ (double) bestB / Math.max( bestA, 500_000L ), Math.pow( factor, 1.5 ) };
    }

    private static long nanos( final java.util.function.Consumer< String > run, final String input ) {
        final long t = System.nanoTime();
        run.accept( input );
        return System.nanoTime() - t;
    }
}
