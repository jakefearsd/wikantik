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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.markdown.MarkdownRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ReaderTags} against the rendered page: the tags it returns are the {@code a.inline-tag} links the render draws. */
class ReaderTagsTest {

    private static final Path FIXTURES = Path.of( "..", "wikantik-frontend", "src", "utils", "__fixtures__" );
    private static final Pattern TAG_LINK = Pattern.compile( "<a [^>]*class=\"tag inline-tag\"[^>]*href=\"([^\"]*)\"" );

    private final TestEngine testEngine = TestEngine.build(
            TestEngine.with( "wikantik.fileSystemProvider.pageDir", "./target/md-pageDir" ),
            TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
            TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ) );

    @AfterEach
    void tearDown() {
        testEngine.stop();
    }

    private static List< String > inputs() throws Exception {
        final List< String > inputs = new ArrayList<>();
        for ( final JsonElement row : JsonParser.parseString( Files.readString( FIXTURES.resolve( "inline-tags.json" ) ) ).getAsJsonArray() ) {
            inputs.add( row.getAsJsonObject().get( "markdown" ).getAsString() );
        }
        final JsonObject diff = JsonParser.parseString( Files.readString( FIXTURES.resolve( "comments-differential.json" ) ) ).getAsJsonObject();
        for ( final JsonElement c : diff.getAsJsonArray( "cases" ) ) {
            inputs.add( c.getAsJsonObject().get( "input" ).getAsString() );
        }
        return inputs;
    }

    @Test
    void tagsAreTheRenderedTagLinks() throws Exception {
        final List< String > failures = new ArrayList<>();
        int withTags = 0;
        for ( final String input : inputs() ) {
            final Set< String > expected = renderedTags( input );
            final Set< String > actual = ReaderTags.normalised( input );
            if ( !expected.isEmpty() ) {
                withTags++;
            }
            if ( !List.copyOf( expected ).equals( List.copyOf( actual ) ) ) {
                failures.add( "\"" + input.replace( "\n", "\\n" ).replace( "\r", "\\r" ) + "\": the render says " + expected + " but ReaderTags says " + actual );
            }
        }
        assertTrue( withTags > 50, "the corpus must exercise tags: " + withTags );
        assertEquals( List.of(), failures, String.join( "\n", failures.subList( 0, Math.min( failures.size(), 30 ) ) ) );
    }

    @Test
    void writtenKeepsTheFirstFormPerTag() {
        assertEquals( List.of( "Foo/Bar", "x" ), ReaderTags.written( "#Foo/Bar and #foo/bar then #x\n" ) );
        assertEquals( Set.of(), ReaderTags.normalised( null ) );
    }

    @Test
    void aTagInACommentOrALinkIsNotATag() {
        assertEquals( List.of( "kept" ), ReaderTags.written( "%% #hidden %% #kept [a #inlink](x)\n" ) );
    }

    /** Inline HTML text is not a tag: the render draws only #y, where the old line scan also found #x. */
    @Test
    void aTagInsideInlineHtmlIsNotATag() throws Exception {
        for ( final String body : List.of( "<span>#x</span> #y\n", "<kbd>#x</kbd> #y\n" ) ) {
            assertEquals( Set.of( "y" ), renderedTags( body ), "the render of " + body );
            assertEquals( renderedTags( body ), ReaderTags.normalised( body ), body );
            assertEquals( List.of( "y" ), ReaderTags.written( body ) );
        }
    }

    @Test
    void noHashNoParse() {
        final long structure = ReaderStructure.parses();
        final long own = ReaderTags.parses();
        assertEquals( Set.of(), ReaderTags.normalised( "no tags %% a comment %% here\n" ) );
        assertEquals( List.of(), ReaderTags.written( "plain" ) );
        assertEquals( structure, ReaderStructure.parses() );
        assertEquals( own, ReaderTags.parses() );
        ReaderTags.normalised( "has #tag" );
        assertEquals( own + 1, ReaderTags.parses() );
    }

    @Test
    void aHeadingOnlyBodyIsNotParsed() {
        final long own = ReaderTags.parses();
        assertEquals( List.of(), ReaderTags.written( "# Title\n\n## Sub\n\ntext # alone\n" ) );
        assertEquals( own, ReaderTags.parses() );
        assertEquals( List.of(), ReaderTags.written( "# Title\r\n\r\ntext\r\n" ) );
        assertEquals( own, ReaderTags.parses() );
        // a comment between '#' and the word is removed first, so the tag is found
        assertEquals( List.of( "tag" ), ReaderTags.written( "x #%%c%%tag\n" ) );
        assertEquals( own + 1, ReaderTags.parses() );
    }

    @Test
    void crlfBodiesMatchLf() throws Exception {
        final List< String > failures = new ArrayList<>();
        for ( final String input : inputs() ) {
            final String crlf = input.replace( "\n", "\r\n" );
            assertEquals( ReaderTags.written( input ), ReaderTags.written( crlf ), () -> "CRLF differs for " + input );
            final Set< String > rendered = renderedTags( crlf );
            if ( !List.copyOf( rendered ).equals( List.copyOf( ReaderTags.normalised( crlf ) ) ) ) {
                failures.add( "\"" + crlf.replace( "\r", "\\r" ).replace( "\n", "\\n" ) + "\": the render says " + rendered + " but ReaderTags says " + ReaderTags.normalised( crlf ) );
            }
        }
        assertEquals( List.of(), failures, String.join( "\n", failures.subList( 0, Math.min( failures.size(), 30 ) ) ) );
    }

    @Test
    void aParseCutOffByItsBudgetReadsNoTagsAndSaysSo() {
        assertEquals( Set.of( "tag" ), ReaderTags.normalised( "has #tag\n", -1L ), "the standard budget reads it" );
        assertEquals( Set.of(), ReaderTags.normalised( "has #tag\n", 1L ), "an exhausted budget is an empty result, not an exception" );
    }

    /** Tag cases carried over from the deleted hand-written scanner: the render is the oracle, the old expectation the cross-check. */
    @Test
    void theOldScannersCasesAreTheRendersToo() throws Exception {
        final String[][] cases = {
            { "Hi #Work and #team/core\n# Heading #nope\n`#code` [[#anchor]] http://x/#frag #123", "work,team-core" },
            { "```\n#x\n```\n[t](a#b) word#nope", "" },
            { "`c`#x", "" },
            { "# Title with `code` #real", "" },
            { "see `c` #ok", "ok" },
            { "#2024/ #123 #1/2", "" },
            { "#2024-q1 #y2024", "2024-q1,y2024" },
            { "#123a", "123a" },
            { "use #fff here", "fff" },
            { "x#foo", "" },
            { "x\n#foo", "foo" },
            { "# Heading\n## H #x", "" },
            { "[[Page#Heading]] [t](#frag) https://e.com/#frag", "" },
            { "$#x$", "" },
            { "$$\n#x\n$$", "" },
            { "$a$ #y", "y" },
            { "%% #secret %%", "" },
            { "a %% line one\n#secret\nend %% b", "" },
            { "%% #hidden %% #shown", "shown" },
            { "<div style=\"color: #fff\">hi</div>", "" },
            { "a <span style=\"color: #fff\">x</span> b", "" },
            { "<style>\n.a { color: #fff }\n</style>", "" },
            { "<script>\nvar c = 1; // #todo\n</script>", "" },
            { "<!-- #hidden -->", "" },
            { "<style>\n.a { color: #fff }\n</style>\n\nprose #after", "after" },
            { "a <b>bold</b> #inline", "inline" },
            { "> ## H #x", "" },
            { "- # H #x", "" },
            { "> - 1. ### H #x", "" },
            { "> quote #y", "y" },
            { "| a |\n| - |\n|#foo|", "foo" },
            { "| h1 | h2 |\n|---|---|\n| #a |#b|", "a,b" },
            { "not a table |#foo|", "" },
            { "| a |\n| - |\n| x \\|#esc |", "" },
            { "[see #foo](x)", "" },
            { "![alt #foo](x.png)", "" },
            { "[see #foo][r]\n\n[r]: http://e.com", "" },
            { "[see #foo][undefined]", "foo" },
            { "[l](x) #tail", "tail" },
        };
        final List< String > failures = new ArrayList<>();
        for ( final String[] c : cases ) {
            final List< String > old = c[ 1 ].isEmpty() ? List.of() : List.of( c[ 1 ].split( "," ) );
            for ( final String input : new String[]{ c[ 0 ], c[ 0 ].replace( "\n", "\r\n" ) } ) {
                final List< String > rendered = List.copyOf( renderedTags( input ) );
                final List< String > actual = List.copyOf( ReaderTags.normalised( input ) );
                if ( !rendered.equals( actual ) || !old.equals( actual ) ) {
                    failures.add( "\"" + input.replace( "\n", "\\n" ).replace( "\r", "\\r" ) + "\": render " + rendered + ", ReaderTags " + actual
                            + ", old scanner " + old );
                }
            }
        }
        assertEquals( List.of(), failures, String.join( "\n", failures ) );
    }

    @Test
    void insertionOrderAndDuplicatesAreFirstSeen() {
        assertEquals( List.of( "b", "a" ), List.copyOf( ReaderTags.normalised( "#b #a #B" ) ) );
    }

    @Test
    void pathologicalRunsStayBounded() {
        assertTimeoutPreemptively( Duration.ofSeconds( 60 ), () -> {
            final String run = "a".repeat( 200_000 );
            final long before = ReaderTags.parses();
            // the "#t " prefix makes the parse actually run (no tag-opening '#', no parse)
            assertEquals( Set.of( "t" ), ReaderTags.normalised( "#t " + run ) );
            assertEquals( Set.of( "t" ), ReaderTags.normalised( "#t ://" + run ) );
            assertEquals( Set.of( "t" ), ReaderTags.normalised( "#t " + "[[a ".repeat( 50_000 ) ) );
            assertEquals( Set.of( "t" ), ReaderTags.normalised( "#t " + "](a".repeat( 50_000 ) ) );
            assertEquals( before + 4, ReaderTags.parses() );
        } );
    }

    /** #129: a block quote of {@code #x} lines took 11.7 s to read (flexmark's setext check), inside the index update. */
    @Test
    void aBlockQuoteOfTagLinesReadsQuickly() {
        final String body = "#t " + "> #x\n".repeat( 5_000 );
        assertEquals( Set.of( "t", "x" ), assertTimeoutPreemptively( Duration.ofSeconds( 5 ), () -> ReaderTags.normalised( body ) ) );
    }

    @Test
    void linearInTags() {
        assertTimeoutPreemptively( Duration.ofSeconds( 120 ), () -> {
            final double[] result = linearCost( n -> "#a `b` $c$ ".repeat( n ), s -> assertTrue( ReaderTags.normalised( s ).size() > 0 ), 2000, 4 );
            assertTrue( result[ 0 ] < result[ 1 ], "ratio " + result[ 0 ] + " must stay under " + result[ 1 ] );
        } );
    }

    private Set< String > renderedTags( final String markdown ) throws Exception {
        testEngine.saveText( "ReaderTagsHost", markdown );
        final Page p = Wiki.contents().page( testEngine, "ReaderTagsHost" );
        final Context context = Wiki.context().create( testEngine, HttpMockFactory.createHttpRequest(), p );
        final String html = testEngine.getManager( RenderingManager.class ).textToHTML( context, markdown ).replace( "&#61;", "=" );
        final Set< String > tags = new LinkedHashSet<>();
        final Matcher m = TAG_LINK.matcher( html );
        while ( m.find() ) {
            tags.add( URLDecoder.decode( m.group( 1 ).substring( "/search?tag=".length() ), StandardCharsets.UTF_8 ) );
        }
        return tags;
    }

    /** A warm-up, then small and large inputs timed alternately, best of five each; returns {ratio, factor^1.5}. */
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
