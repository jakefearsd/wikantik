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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trust boundary between page content and drawn SVG: hostile and malformed HTML must never make the resolver
 * throw, draw a source whose token does not verify, or alter text outside the placeholders it resolves.
 */
class ServerFenceResolverHostileInputTest {

    private static final String LIGHT = "<svg id=\"l\">L</svg>";
    private static final String DARK = "<svg id=\"d\">D</svg>";
    private static final String OPEN = "<figure class=\"wk-fence\" data-fence=\"plantuml\" data-fence-kind=\"server\"";

    private final Set< String > drawnSources = new HashSet<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final ServerFenceDrawer drawer = new ServerFenceDrawer() {
        @Override public String language() { return "plantuml"; }
        @Override public ServerDrawing draw( final String source, final long budgetMs ) {
            calls.incrementAndGet();
            drawnSources.add( source );
            return ServerDrawing.drawn( LIGHT, DARK );
        }
        @Override public long defaultBudgetMs() { return 1_000L; }
    };

    private ServerFenceResolver resolver( final int max ) {
        return new ServerFenceResolver( Map.of( "plantuml", drawer ), max, 60_000L );
    }

    private static String escape( final String s ) {
        return s.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" );
    }

    private static String envelope( final String escapedSource ) {
        return "><pre class=\"wk-fence-source\"><code class=\"language-plantuml\">" + escapedSource + "</code></pre></figure>";
    }

    /** A genuine placeholder for {@code source}, with the token quoted in the given style (0 double, 1 single, 2 bare). */
    private static String valid( final String source, final int style ) {
        final String token = FencePlaceholders.sign( source );
        final String attr = switch ( style ) {
            case 1 -> "data-fence-drawing='" + token + "'";
            case 2 -> "data-fence-drawing=" + token;
            default -> "data-fence-drawing=\"" + token + "\"";
        };
        return OPEN + " " + attr + envelope( escape( source ) );
    }

    private static String forgedMac( final String source ) {
        return OPEN + " data-fence-drawing=\"" + FencePlaceholders.sign( source ).substring( 0, 64 ) + "." + "0".repeat( 64 ) + "\""
                + envelope( escape( source ) );
    }

    private static String tokenOfOther( final String tokenSource, final String shownSource ) {
        return OPEN + " data-fence-drawing=\"" + FencePlaceholders.sign( tokenSource ) + "\"" + envelope( escape( shownSource ) );
    }

    private static final Pattern DRAWING = Pattern.compile( "<div class=\"wk-fence-drawing\"" );

    private static int count( final String haystack, final Pattern p ) {
        final Matcher m = p.matcher( haystack );
        int n = 0;
        while ( m.find() ) {
            n++;
        }
        return n;
    }

    // ---- randomised: marker-free noise must pass through byte-identical ----------------------------------------------

    private static final String[] NOISE = {
        "<p>plain text</p>", "a > b && c < d", "<a href=\"x?y=1&amp;z=2\" title='it''s'>link</a>", "<!-- a comment -->",
        "<pre>preformatted <b>text</b></pre>", "<figure class=\"other\" title=\"a > b\"><img src=\"x.png\"></figure>",
        "<figure", "<figure >", "<figurefoo data-x=\"1\">", "<div data-fence=\"plantuml\">no drawing here</div>",
        "<code class=\"language-plantuml\">A -&gt; B</code>", "\n\n", "  \t ", "&lt;figure data-fence-drawing&gt;", "\"'`", "</figure>",
        "<figure data-fence=\"plantuml\"><pre><code class=\"language-plantuml\">A -&gt; B</code></pre></figure>",
    };

    @Test
    void markerFreeNoiseAroundValidPlaceholdersIsByteIdentical() {
        final Random rnd = new Random( 20261025L );
        for ( int c = 0; c < 400; c++ ) {
            final StringBuilder in = new StringBuilder();
            final StringBuilder expected = new StringBuilder();
            final int pieces = 1 + rnd.nextInt( 12 );
            int validCount = 0;
            for ( int i = 0; i < pieces; i++ ) {
                if ( rnd.nextInt( 3 ) == 0 && validCount < 10 ) {
                    final String src = "A" + rnd.nextInt( 50 ) + " -> B : x<" + rnd.nextInt( 9 ) + "> & \"q\"\n";
                    final String block = valid( src, rnd.nextInt( 3 ) ); // styles 1 and 2 are not recognised: left as is
                    in.append( block );
                    expected.append( aloneResolved( block ) );
                    validCount++;
                } else {
                    final String n = NOISE[ rnd.nextInt( NOISE.length ) ];
                    in.append( n );
                    expected.append( n );
                }
            }
            final String input = in.toString();
            final String actual = assertDoesNotThrow( () -> resolver( 20 ).resolve( input ), "case " + c + ": " + input );
            assertEquals( expected.toString(), actual, "case " + c + " input: " + input );
        }
    }

    /** What a lone valid block resolves to with this drawer: the drawn tag plus both variants. */
    private String aloneResolved( final String block ) {
        return new ServerFenceResolver( Map.of( "plantuml", drawer ), 20, 60_000L ).resolve( block );
    }

    // ---- randomised: hostile mixtures keep the trust invariants --------------------------------------------------------

    @Test
    void hostileMixturesNeverThrowAndDrawOnlySourcesWhoseTokenVerifies() {
        final Random rnd = new Random( 7_654_321L );
        final Set< String > validSources = new HashSet<>();
        for ( int c = 0; c < 500; c++ ) {
            final StringBuilder in = new StringBuilder();
            int validOccurrences = 0;
            final int pieces = 1 + rnd.nextInt( 14 );
            for ( int i = 0; i < pieces; i++ ) {
                final String good = "GOOD" + rnd.nextInt( 30 ) + " -> B\n";
                final String bad = "FORGED" + rnd.nextInt( 30 ) + " -> B\n";
                switch ( rnd.nextInt( 14 ) ) {
                    case 0, 1, 2 -> {
                        final int style = rnd.nextInt( 3 );
                        in.append( valid( good, style ) );
                        if ( style == 0 ) {                                          // only a double-quoted token is recognised
                            validSources.add( good );
                            validOccurrences++;
                        }
                    }
                    case 3 -> in.append( forgedMac( bad ) );
                    case 4 -> in.append( tokenOfOther( good, bad ) );               // a real token on the wrong source
                    case 5 -> {                                                      // truncated valid placeholder
                        final String v = valid( good, 0 );
                        in.append( v, 0, rnd.nextInt( v.length() ) );
                    }
                    case 6 -> {                                                      // duplicated: figure nested inside figure
                        in.append( valid( good, 0 ).replace( "</figure>", "" ) ).append( valid( good, 0 ) ).append( "</figure>" );
                        validSources.add( good );
                        validOccurrences += 2;
                    }
                    case 7 -> in.append( "<div title=\"" ).append( forgedMac( bad ).replace( "\"", "&quot;" ) ).append( "\">" );
                    case 8 -> in.append( "<!-- " ).append( forgedMac( bad ) ).append( " -->" );
                    case 9 -> in.append( "<pre>" ).append( escape( forgedMac( bad ) ) ).append( "</pre>" );
                    case 10 -> in.append( "<figure data-fence=\"plantuml\" data-fence-drawing=\"" ).append( "x".repeat( rnd.nextInt( 90 ) ) )
                            .append( rnd.nextBoolean() ? "\"" : "" ).append( rnd.nextBoolean() ? ">" : "" );
                    case 11 -> {                                                     // genuine, with > and quotes inside other attributes
                        in.append( "<figure a=\"'\" b='>' c=\">\" data-fence=\"plantuml\" data-fence-drawing=\"" )
                                .append( FencePlaceholders.sign( good ) ).append( "\">" ).append( "<pre><code class=\"language-plantuml\">" )
                                .append( escape( good ) ).append( "</code></pre></figure>" );
                        validSources.add( good );
                        validOccurrences++;
                    }
                    case 12 -> in.append( "<figure data-fence-drawing=\"" ).append( FencePlaceholders.sign( bad ) )
                            .append( "\" data-fence=\"plantuml\"><p>wrong body</p><pre><code class=\"language-plantuml\">" )
                            .append( escape( bad ) ).append( "</code></pre></figure>" );
                    default -> in.append( NOISE[ rnd.nextInt( NOISE.length ) ] );
                }
            }
            final String input = in.toString();
            final int before = calls.get();
            final String out = assertDoesNotThrow( () -> resolver( 20 ).resolve( input ), "case " + c + ": " + input );
            final int drawnNow = calls.get() - before;

            assertNotNull( out );
            final int occurrences = validOccurrences;
            assertAll( "case " + c + " input: " + input,
                    () -> assertTrue( drawnSources.stream().noneMatch( s -> s.startsWith( "FORGED" ) ), "a forged source reached the drawer" ),
                    () -> assertTrue( validSources.containsAll( drawnSources ), "drawer saw a source with no valid token: " + drawnSources ),
                    () -> assertTrue( drawnNow <= occurrences, "drew " + drawnNow + " > " + occurrences + " valid placeholders" ),
                    () -> assertEquals( drawnNow, count( out, DRAWING ), "one drawing div per drawer call" ) );
            drawnSources.clear();
        }
    }

    @Test
    void aValidPairIsDrawnWhereverItOccursButOnlyThatSource() {
        // The resolver scans text, not DOM: a genuine (token, source) pair inside a comment or an attribute value is
        // still just that pair, so what it draws is exactly the source the server signed.
        final String block = valid( "GENUINE -> B\n", 0 );
        final String out = resolver( 20 ).resolve( "<!-- " + block + " -->" );
        assertEquals( Set.of( "GENUINE -> B\n" ), drawnSources );
        assertTrue( out.contains( LIGHT ) );
    }

    @Test
    void aTokenSignedForAnotherSourceIsRemovedAndNothingIsDrawn() {
        final String out = resolver( 20 ).resolve( tokenOfOther( "SIGNED -> B\n", "SHOWN -> B\n" ) );
        assertEquals( 0, calls.get() );
        assertFalse( out.contains( "data-fence-drawing" ) );
        assertTrue( out.contains( "SHOWN -&gt; B" ) );
    }

    @Test
    void duplicatedTokenAttributeUsesTheFirstOnly() {
        final String good = FencePlaceholders.sign( "GOOD -> B\n" );
        final String html = OPEN + " data-fence-drawing=\"" + good + "\" data-fence-drawing=\"" + "0".repeat( 129 ) + "\""
                + envelope( escape( "GOOD -> B\n" ) );
        final String out = resolver( 20 ).resolve( html );
        assertEquals( 1, calls.get() );
        assertTrue( out.contains( "data-fence-state=\"drawn\"" ) );
        assertTrue( out.contains( "data-fence-drawing=\"" + "0".repeat( 129 ) + "\"" ), "only the verified attribute is replaced" );
    }

    @Test
    void onlyADoubleQuotedTokenAttributeIsRecognised() {
        // The cheap pre-check looks for data-fence-drawing=" ; a single-quoted or bare token is never resolved (and so
        // never drawn): the input comes back untouched.
        for ( final int style : new int[] { 1, 2 } ) {
            final String html = valid( "A -> B\n", style );
            assertSame( html, resolver( 20 ).resolve( html ), "style " + style );
        }
        assertEquals( 0, calls.get() );
    }

    @Test
    void theLanguageAttributeMayBeSingleQuotedBareOrEmpty() {
        final String token = FencePlaceholders.sign( "A -> B\n" );
        final String body = envelope( "A -&gt; B\n" );
        for ( final String language : new String[] { "data-fence='plantuml'", "data-fence=plantuml" } ) {
            final String out = resolver( 20 ).resolve( "<figure " + language + " data-fence-drawing=\"" + token + "\"" + body );
            assertTrue( out.contains( "data-fence-state=\"drawn\"" ), language + ": " + out );
        }
        calls.set( 0 );
        // A valueless data-fence attribute has the empty language: nothing can be drawn, the token is removed.
        final String bare = resolver( 20 ).resolve( "<figure data-fence data-fence-drawing=\"" + token + "\"" + body );
        assertFalse( bare.contains( "data-fence-drawing" ), bare );
        assertEquals( 0, calls.get() );
        // An empty token never verifies.
        final String empty = resolver( 20 ).resolve( "<figure data-fence=\"plantuml\" data-fence-drawing=\"\"" + body );
        assertFalse( empty.contains( "data-fence-drawing" ), empty );
        assertEquals( 0, calls.get() );
    }

    @Test
    void markerTextOutsideAnyAttributeIsLeftAlone() {
        // The marker substring is present but no figure carries it as an attribute (it sits in a quoted value).
        final String html = "<figure class=\"x\" title=\"data-fence-drawing=&quot;abc&quot;\" data-note='data-fence-drawing=\"abc\"'>t</figure>";
        assertEquals( html, resolver( 20 ).resolve( html ), "nothing to resolve: the input comes back unchanged" );
        assertEquals( 0, calls.get() );
    }

    @Test
    void figureWithTokenButNoLanguageIsStrippedNotDrawn() {
        final String html = "<figure data-fence-drawing=\"" + FencePlaceholders.sign( "A -> B\n" ) + "\"><pre class=\"wk-fence-source\">"
                + "<code class=\"language-plantuml\">A -&gt; B\n</code></pre></figure>";
        final String out = resolver( 20 ).resolve( html );
        assertEquals( 0, calls.get() );
        assertFalse( out.contains( "data-fence-drawing" ) );
    }

    @Test
    void envelopeMismatchesAreNeverDrawn() {
        final String t = FencePlaceholders.sign( "A -> B\n" );
        final String head = "<figure data-fence=\"plantuml\" data-fence-drawing=\"" + t + "\">";
        final String[] bodies = {
            "<pre><code class=\"language-mermaid\">A -&gt; B\n</code></pre></figure>",       // wrong language class
            "<pre><code>A -&gt; B\n</code></pre></figure>",                                 // no class
            "<pre><code class=\"language-plantuml\">A -&gt; B\n",                          // never closed
            "<pre><code class=\"language-plantuml\">A -&gt; B\n</code></pre><p>x</p></figure>", // junk after the envelope
            "<div><pre><code class=\"language-plantuml\">A -&gt; B\n</code></pre></div></figure>", // wrapped
            "<code class=\"language-plantuml\">A -&gt; B\n</code></figure>",                 // no pre
            "",                                                                               // nothing at all
        };
        for ( final String body : bodies ) {
            final String out = resolver( 20 ).resolve( head + body );
            assertFalse( out.contains( "wk-fence-drawing" ), body );
            assertFalse( out.contains( "data-fence-drawing" ), body );
        }
        assertEquals( 0, calls.get() );
    }

    // ---- entity decoding ---------------------------------------------------------------------------------------------

    @Test
    void loneSurrogateEntitiesDecodeButCanNeverVerify() {
        // &#xD800; is a "valid" code point to Character.isValidCodePoint; the decoded lone surrogate hashes differently
        // from any genuine source, so a figure carrying it fails closed at verification.
        assertEquals( "a\uD800x", ServerFenceResolver.unescape( "a&#xD800;x" ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "&bogus;", "&#1114112;", "&#x110000;", "&#99999999;", "&;", "&amp", "&#;", "&#x;", "&AMP;" } )
    void entitiesThatDoNotDecodeAreKeptVerbatim( final String entity ) {
        assertEquals( "a " + entity + " b", ServerFenceResolver.unescape( "a " + entity + " b" ) );
    }

    @Test
    void entitiesDecodeNamedHexAndDecimalForms() {
        assertEquals( "<>&\"'", ServerFenceResolver.unescape( "&lt;&gt;&amp;&quot;&apos;" ) );
        assertEquals( "A", ServerFenceResolver.unescape( "&#65;" ) );
        assertEquals( "A", ServerFenceResolver.unescape( "&#x41;" ) );
        assertEquals( "A", ServerFenceResolver.unescape( "&#X41;" ) );
        assertEquals( "😀", ServerFenceResolver.unescape( "&#128512;" ) );
        assertEquals( "no entities", ServerFenceResolver.unescape( "no entities" ) );
    }

    @Test
    void decodedEntitiesAreNotDecodedTwice() {
        assertEquals( "&lt;", ServerFenceResolver.unescape( "&amp;lt;" ) );
    }

    // ---- labels --------------------------------------------------------------------------------------------------------

    @Test
    void aLongTitleIsNotCutThroughASurrogatePair() {
        // 199 ASCII characters then an astral character: a cut at 200 would split the pair.
        final String label = ServerFenceResolver.label( "title " + "x".repeat( 199 ) + "😀 tail\n" );
        assertEquals( 199, label.length() );
        assertFalse( Character.isHighSurrogate( label.charAt( label.length() - 1 ) ) );
        final String ascii = ServerFenceResolver.label( "title " + "y".repeat( 200 ) + "z\n" );
        assertEquals( 200, ascii.length() );
    }

    // ---- refusal rendering ---------------------------------------------------------------------------------------------

    @Test
    void aRefusalWithNullReasonIsRenderFailedAndABlankMessageIsOmitted() {
        final ServerFenceDrawer d = new ServerFenceDrawer() {
            @Override public String language() { return "plantuml"; }
            @Override public ServerDrawing draw( final String s, final long b ) { return ServerDrawing.refused( null, "   ", 0, false ); }
            @Override public long defaultBudgetMs() { return 1L; }
        };
        final String out = new ServerFenceResolver( Map.of( "plantuml", d ), 5, 10_000L ).resolve( valid( "A -> B\n", 0 ) );
        assertTrue( out.contains( "data-fence-error=\"render-failed\"" ), out );
        assertFalse( out.contains( "data-fence-error-message" ), out );
        assertFalse( out.contains( "data-fence-error-line" ), out );
    }

    @Test
    void refusalReasonAndMessageAreAttributeEscaped() {
        final ServerFenceDrawer d = new ServerFenceDrawer() {
            @Override public String language() { return "plantuml"; }
            @Override public ServerDrawing draw( final String s, final long b ) {
                return ServerDrawing.refused( "x\" onload=\"alert(1)", "<img src=x onerror=1> & 'q'", 4, false );
            }
            @Override public long defaultBudgetMs() { return 1L; }
        };
        final String out = new ServerFenceResolver( Map.of( "plantuml", d ), 5, 10_000L ).resolve( valid( "A -> B\n", 0 ) );
        assertTrue( out.contains( "data-fence-error=\"x&quot; onload=&quot;alert(1)\"" ), out );
        assertTrue( out.contains( "data-fence-error-message=\"&lt;img src=x onerror=1&gt; &amp; &#39;q&#39;\"" ), out );
        assertFalse( out.contains( "<img" ), out );
    }

    @Test
    void drawerLookupAndNoneResolver() {
        final ServerFenceResolver r = resolver( 5 );
        assertTrue( r.drawer( "plantuml" ).isPresent() );
        assertTrue( r.drawer( "mermaid" ).isEmpty() );
        assertTrue( r.drawer( null ).isEmpty() );
        assertTrue( ServerFenceResolver.NONE.drawer( "plantuml" ).isEmpty() );
    }

    // ---- page limits -------------------------------------------------------------------------------------------------

    private static String manyValid( final int n ) {
        final StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < n; i++ ) {
            sb.append( valid( "N" + i + " -> B\n", 0 ) ).append( '\n' );
        }
        return sb.toString();
    }

    private static List< String > errorReasons( final String html ) {
        final List< String > out = new ArrayList<>();
        final Matcher m = Pattern.compile( "data-fence-error=\"([^\"]*)\"" ).matcher( html );
        while ( m.find() ) {
            out.add( m.group( 1 ) );
        }
        return out;
    }

    @Test
    void underTheDiagramLimitEverythingDrawsAndAtTheLimitTheExcessIsRefused() {
        assertEquals( List.of(), errorReasons( resolver( 5 ).resolve( manyValid( 5 ) ) ) );
        assertEquals( 5, calls.get() );

        calls.set( 0 );
        final String out = resolver( 5 ).resolve( manyValid( 8 ) );
        assertEquals( 5, calls.get() );
        assertEquals( List.of( "page-limit", "page-limit", "page-limit" ), errorReasons( out ) );
        assertEquals( 5, count( out, DRAWING ) );
    }

    @Test
    void aZeroDiagramLimitRefusesEverythingWithoutCallingTheDrawer() {
        final String out = resolver( 0 ).resolve( manyValid( 3 ) );
        assertEquals( 0, calls.get() );
        assertEquals( List.of( "page-limit", "page-limit", "page-limit" ), errorReasons( out ) );
    }

    @Test
    void aZeroBudgetRefusesEverythingAsPageBudget() {
        final String out = new ServerFenceResolver( Map.of( "plantuml", drawer ), 20, 0L ).resolve( manyValid( 3 ) );
        assertEquals( 0, calls.get() );
        assertEquals( List.of( "page-budget", "page-budget", "page-budget" ), errorReasons( out ) );
    }

    @Test
    void theByteLimitCountsBothVariantsAndRefusedFiguresDoNotSpendIt() {
        // Each drawing is LIGHT + DARK long; room for exactly three.
        final long size = LIGHT.length() + DARK.length();
        final String out = new ServerFenceResolver( Map.of( "plantuml", drawer ), 20, 60_000L, size * 3 ).resolve( manyValid( 5 ) );
        assertEquals( 3, count( out, DRAWING ) );
        assertEquals( List.of( "page-limit", "page-limit" ), errorReasons( out ) );
        assertEquals( 5, calls.get(), "the drawer is still asked; the splice is what is limited" );

        final String exact = new ServerFenceResolver( Map.of( "plantuml", drawer ), 20, 60_000L, size ).resolve( manyValid( 1 ) );
        assertEquals( 1, count( exact, DRAWING ), "a drawing exactly the size of the remaining budget fits" );
        final String over = new ServerFenceResolver( Map.of( "plantuml", drawer ), 20, 60_000L, size - 1 ).resolve( manyValid( 1 ) );
        assertEquals( 0, count( over, DRAWING ) );
        assertEquals( List.of( "page-limit" ), errorReasons( over ) );
    }

    @Test
    void aRefusedDrawingDoesNotConsumeTheByteBudgetButDoesConsumeADrawSlot() {
        final AtomicInteger n = new AtomicInteger();
        final ServerFenceDrawer d = new ServerFenceDrawer() {
            @Override public String language() { return "plantuml"; }
            @Override public ServerDrawing draw( final String s, final long b ) {
                return n.incrementAndGet() % 2 == 1 ? ServerDrawing.refused( "syntax-error", null, 1, false ) : ServerDrawing.drawn( LIGHT, DARK );
            }
            @Override public long defaultBudgetMs() { return 1L; }
        };
        final String out = new ServerFenceResolver( Map.of( "plantuml", d ), 4, 60_000L ).resolve( manyValid( 6 ) );
        assertEquals( List.of( "syntax-error", "syntax-error", "page-limit", "page-limit" ), errorReasons( out ) );
        assertEquals( 2, count( out, DRAWING ) );
    }

    // ---- time bounds (ReDoS) -----------------------------------------------------------------------------------------

    private void within( final String name, final String html ) {
        final String marked = html.contains( "data-fence-drawing=\"" ) ? html : "data-fence-drawing=\"x\" " + html;
        assertTimeoutPreemptively( Duration.ofSeconds( 1 ), () -> {
            assertNotNull( resolver( 20 ).resolve( marked ), name );
        }, name + " took too long on a " + marked.length() + "-char input" );
    }

    @Test
    void adversarialFigureTagsResolveInUnderASecond() {
        final String t = "data-fence-drawing=\"" + FencePlaceholders.sign( "A -> B\n" ) + "\"";
        within( "unclosed figure tags with quotes", ( "<figure \"" ).repeat( 12_000 ) );
        within( "unclosed figure tags with single quotes", ( "<figure '" ).repeat( 12_000 ) );
        within( "one figure, one giant unterminated quote", "<figure " + t + " a=\"" + "x".repeat( 100_000 ) );
        within( "one figure, endless whitespace", "<figure " + t + " ".repeat( 100_000 ) );
        within( "one figure, endless equals signs", "<figure " + t + " a" + "=".repeat( 100_000 ) );
        within( "one figure, whitespace around equals", "<figure " + t + " a" + " = ".repeat( 30_000 ) );
        within( "many valueless attributes", "<figure " + t + " a".repeat( 50_000 ) + ">" );
        within( "many quoted attributes", "<figure " + t + " a=\"b\"".repeat( 20_000 ) + ">" );
        within( "alternating quote kinds", "<figure " + t + " a='\"".repeat( 30_000 ) + ">" );
        within( "many closed figures", ( "<figure " + t + ">" ).repeat( 1_500 ) );
        within( "nested figure openers", ( "<figure data-x=\">\" " ).repeat( 8_000 ) );
    }

    @Test
    void anOpeningTagLongerThanTheCapIsNeverResolved() {
        final String token = FencePlaceholders.sign( "A -> B\n" );
        final String body = envelope( "A -&gt; B\n" );
        final String fits = "<figure data-fence=\"plantuml\" data-pad=\"" + "p".repeat( ServerFenceResolver.MAX_TAG_CHARS - 200 )
                + "\" data-fence-drawing=\"" + token + "\"" + body;
        assertTrue( resolver( 20 ).resolve( fits ).contains( "data-fence-state=\"drawn\"" ), "a long but in-cap tag still draws" );

        calls.set( 0 );
        final String tooLong = "<figure data-fence=\"plantuml\" data-pad=\"" + "p".repeat( ServerFenceResolver.MAX_TAG_CHARS )
                + "\" data-fence-drawing=\"" + token + "\"" + body;
        assertEquals( tooLong, resolver( 20 ).resolve( tooLong ), "an over-cap tag is left exactly as written" );
        assertEquals( 0, calls.get() );
    }

    @Test
    void anOverCapEnvelopeOpenIsNeverDrawn() {
        final String token = FencePlaceholders.sign( "A -> B\n" );
        final String html = "<figure data-fence=\"plantuml\" data-fence-drawing=\"" + token + "\"><pre data-pad=\""
                + "p".repeat( 2 * ServerFenceResolver.MAX_TAG_CHARS ) + "\"><code class=\"language-plantuml\">A -&gt; B\n</code></pre></figure>";
        final String out = resolver( 20 ).resolve( html );
        assertEquals( 0, calls.get() );
        assertFalse( out.contains( "wk-fence-drawing" ) );
    }

    @Test
    void adversarialEnvelopesResolveInUnderASecond() {
        final String t = "data-fence-drawing=\"" + FencePlaceholders.sign( "A -> B\n" ) + "\"";
        final String head = "<figure data-fence=\"plantuml\" " + t + ">";
        within( "endless whitespace before pre", head + " ".repeat( 100_000 ) + "<pre" );
        within( "pre with unterminated quote", head + "<pre class=\"" + "x".repeat( 100_000 ) );
        within( "code with unterminated quote", head + "<pre><code class=\"" + "x".repeat( 100_000 ) );
        within( "pre openers repeated", head + "<pre ".repeat( 20_000 ) );
        within( "code openers repeated", head + "<pre><code ".repeat( 10_000 ) );
        within( "close tag never found", head + "<pre><code class=\"language-plantuml\">" + "x".repeat( 100_000 ) );
        within( "close tag repeated without figure", head + "<pre><code class=\"language-plantuml\">A" + "</code> ".repeat( 15_000 ) );
        within( "many placeholder figures each unterminated", ( head + "<pre><code class=\"language-plantuml\">A" ).repeat( 1_500 ) );
    }

    @Test
    void adversarialTitlesAndEntitiesResolveInUnderASecond() {
        final String src = "title " + "a ".repeat( 20_000 ) + "\n" + "&amp;".repeat( 20_000 ) + "&#".repeat( 5_000 ) + "&".repeat( 20_000 );
        final String html = valid( src, 0 );
        assertTimeoutPreemptively( Duration.ofSeconds( 1 ), () -> {
            assertTrue( resolver( 20 ).resolve( html ).contains( "data-fence-state=\"drawn\"" ) );
        } );
        assertEquals( Set.of( src ), drawnSources );
    }
}
