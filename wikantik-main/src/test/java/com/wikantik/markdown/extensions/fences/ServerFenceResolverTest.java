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

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.wikantik.parser.markdown.MarkdownDocument;
import com.wikantik.parser.markdown.WikantikHtmlSanitizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Placeholder splicing: verified tokens become drawings or reasons, forged ones become nothing. */
class ServerFenceResolverTest {

    private static final String LIGHT = "<svg id=\"l\">L</svg>";
    private static final String DARK = "<svg id=\"d\">D</svg>";
    private static final Pattern TOKEN = Pattern.compile( "data-fence-drawing=\"([0-9a-f]{64})\\.([0-9a-f]{64})\"" );

    /** Counting fake drawer for {@code plantuml}. */
    static final class FakeDrawer implements ServerFenceDrawer {
        final AtomicInteger calls = new AtomicInteger();
        final List< String > sources = Collections.synchronizedList( new ArrayList<>() );
        final List< Long > budgets = Collections.synchronizedList( new ArrayList<>() );
        volatile long sleepMs;
        volatile Function< String, ServerDrawing > outcome = s -> ServerDrawing.drawn( LIGHT, DARK );

        @Override
        public String language() {
            return "plantuml";
        }

        @Override
        public ServerDrawing draw( final String source, final long budgetMs ) {
            calls.incrementAndGet();
            sources.add( source );
            budgets.add( budgetMs );
            if ( sleepMs > 0 ) {
                try {
                    Thread.sleep( sleepMs );
                } catch ( final InterruptedException e ) {
                    Thread.currentThread().interrupt();
                }
            }
            return outcome.apply( source );
        }

        @Override
        public long defaultBudgetMs() {
            return 5_000L;
        }
    }

    private final FakeDrawer drawer = new FakeDrawer();

    private ServerFenceResolver resolver( final int maxDiagrams, final long budgetMs ) {
        return new ServerFenceResolver( Map.of( "plantuml", drawer ), maxDiagrams, budgetMs );
    }

    private ServerFenceResolver resolver() {
        return resolver( 20, 10_000L );
    }

    /** The real Flexmark pipeline (production fence registry), sanitized as {@code MarkdownRenderer} does when allowHTML is on. */
    static String render( final String md, final boolean allowHtml ) {
        final MutableDataSet o = MarkdownDocument.structuralOptions();
        o.set( HtmlRenderer.ESCAPE_HTML, !allowHtml );
        final String html = HtmlRenderer.builder( o ).build().render( Parser.builder( o ).build().parse( md ) );
        return allowHtml ? WikantikHtmlSanitizer.sanitize( html ) : html;
    }

    private static String fence( final String source ) {
        return "```plantuml\n" + source + "```\n\n";
    }

    private static int count( final String haystack, final String needle ) {
        int n = 0;
        for ( int i = haystack.indexOf( needle ); i >= 0; i = haystack.indexOf( needle, i + needle.length() ) ) {
            n++;
        }
        return n;
    }

    private static List< String > errors( final String html ) {
        final List< String > out = new ArrayList<>();
        final Matcher m = Pattern.compile( "data-fence-error=\"([^\"]*)\"" ).matcher( html );
        while ( m.find() ) {
            out.add( m.group( 1 ) );
        }
        return out;
    }

    @Test
    void drawnSplicesBothVariantsAndState() {
        final String html = render( fence( "title Order <flow> & more\nA -> B\n" ), false );
        assertTrue( TOKEN.matcher( html ).find(), html );

        final String out = resolver().resolve( html );

        assertFalse( out.contains( "data-fence-drawing" ), out );
        assertTrue( Pattern.compile( "<figure [^>]*data-fence-state=\"drawn\"[^>]*>"
                + Pattern.quote( "<div class=\"wk-fence-drawing\" role=\"img\" aria-label=\"Order &lt;flow&gt; &amp; more\">"
                + "<div class=\"wk-fence-variant\" data-variant=\"light\">" + LIGHT + "</div>"
                + "<div class=\"wk-fence-variant\" data-variant=\"dark\" hidden>" + DARK + "</div></div>" ) )
                .matcher( out ).find(), out );
        assertTrue( out.contains( "<pre class=\"wk-fence-source\"><code class=\"language-plantuml\">" ), out );
        assertEquals( List.of( "title Order <flow> & more\nA -> B\n" ), drawer.sources );
    }

    @Test
    void untitledDiagramGetsDefaultLabel() {
        final String out = resolver().resolve( render( fence( "A -> B\n" ), false ) );
        assertTrue( out.contains( "role=\"img\" aria-label=\"PlantUML diagram\"" ), out );
    }

    @Test
    void refusalSetsErrorLineAndMessage() {
        drawer.outcome = s -> ServerDrawing.refused( "syntax-error", "Bad \"thing\" <here>", 3, false );

        final String out = resolver().resolve( render( fence( "A -> B\n" ), false ) );

        assertTrue( out.contains( "data-fence-error=\"syntax-error\" data-fence-error-line=\"3\" "
                + "data-fence-error-message=\"Bad &quot;thing&quot; &lt;here&gt;\"" ), out );
        assertFalse( out.contains( "data-fence-drawing" ), out );
        assertFalse( out.contains( "data-fence-state" ), out );
        assertFalse( out.contains( "wk-fence-drawing" ), out );
        assertTrue( out.contains( "A -&gt; B" ), out );
    }

    @Test
    void refusalWithoutLineOrMessageCarriesOnlyTheReason() {
        drawer.outcome = s -> ServerDrawing.refused( "busy", null, 0, true );

        final String out = resolver().resolve( render( fence( "A -> B\n" ), false ) );

        assertEquals( List.of( "busy" ), errors( out ) );
        assertFalse( out.contains( "data-fence-error-line" ), out );
        assertFalse( out.contains( "data-fence-error-message" ), out );
    }

    @Test
    void forgedTokenResolvesToNothing() {
        final String html = render( fence( "A -> B\n" ), false );
        final Matcher m = TOKEN.matcher( html );
        assertTrue( m.find(), html );
        final String wrongMac = m.group( 2 ).charAt( 0 ) == 'a' ? "b" + m.group( 2 ).substring( 1 ) : "a" + m.group( 2 ).substring( 1 );
        final String forged = html.replace( m.group( 2 ), wrongMac );

        final String out = resolver().resolve( forged );

        assertFalse( out.contains( "data-fence-drawing" ), out );
        assertFalse( out.contains( "data-fence-state" ), out );
        assertFalse( out.contains( "wk-fence-drawing" ), out );
        assertTrue( errors( out ).isEmpty(), out );
        assertTrue( out.contains( "A -&gt; B" ), out );
        assertEquals( 0, drawer.calls.get() );
    }

    @Test
    void tokenWithoutSourceElementIsRemoved() {
        final String html = "<figure class=\"wk-fence\" data-fence=\"plantuml\" data-fence-drawing=\""
                + FencePlaceholders.sign( "A -> B\n" ) + "\"><p>no code here</p></figure>";

        final String out = resolver().resolve( html );

        assertEquals( "<figure class=\"wk-fence\" data-fence=\"plantuml\"><p>no code here</p></figure>", out );
        assertEquals( 0, drawer.calls.get() );
    }

    @Test
    void pageMaxDiagramsEnforced() {
        final StringBuilder md = new StringBuilder();
        for ( int i = 0; i < 21; i++ ) {
            md.append( fence( "A -> B" + i + "\n" ) );
        }

        final String out = resolver( 20, 60_000L ).resolve( render( md.toString(), false ) );

        assertEquals( 20, count( out, "data-fence-state=\"drawn\"" ), out );
        assertEquals( List.of( "page-budget" ), errors( out ) );
        assertTrue( out.lastIndexOf( "data-fence-error=\"page-budget\"" ) > out.lastIndexOf( "data-fence-state=\"drawn\"" ), out );
        assertEquals( 20, drawer.calls.get() );
    }

    @Test
    void pageBudgetEnforced() {
        // Wide margins so a loaded forked JVM cannot flip the outcome: two 200 ms draws always overrun 300 ms, so the
        // third figure is always out of budget, and the first always starts well inside it.
        drawer.sleepMs = 200;
        final StringBuilder md = new StringBuilder();
        for ( int i = 0; i < 3; i++ ) {
            md.append( fence( "A -> B" + i + "\n" ) );
        }

        final String out = resolver( 20, 300L ).resolve( render( md.toString(), false ) );

        assertTrue( out.indexOf( "data-fence-state=\"drawn\"" ) >= 0, out );
        assertTrue( out.indexOf( "data-fence-state=\"drawn\"" ) < out.indexOf( "data-fence-error=\"page-budget\"" ), out );
        assertTrue( out.lastIndexOf( "data-fence-error=\"page-budget\"" ) > out.lastIndexOf( "data-fence-state=\"drawn\"" ), out );
        assertTrue( drawer.calls.get() < 3, "later figures never reach the drawer" );
        assertTrue( drawer.budgets.get( 0 ) <= 300L && drawer.budgets.get( 0 ) > 0L, drawer.budgets.toString() );
        if ( drawer.budgets.size() > 1 ) {
            assertTrue( drawer.budgets.get( 1 ) < drawer.budgets.get( 0 ), "each figure gets only the remaining budget" );
        }
    }

    // ---- adversarial splices (allowHTML on: hand-written figures pass the sanitizer) ---------------------------------

    private static final String SOURCE = "A -> B\n";
    private static final String ESCAPED_SOURCE = "A -&gt; B\n";

    private static String handWrittenEnvelope( final String figureAttributes ) {
        return "<figure class=\"wk-fence\" data-fence=\"plantuml\" data-fence-kind=\"server\" " + figureAttributes
                + "><pre class=\"wk-fence-source\"><code class=\"language-plantuml\">" + ESCAPED_SOURCE
                + "</code></pre></figure>";
    }

    @Test
    void handWrittenFigureWithWrongTokenIsRemovedAndDrawsNothing() {
        final String hash = FencePlaceholders.sign( SOURCE ).substring( 0, 64 );
        final String html = render( handWrittenEnvelope( "data-fence-drawing=\"" + hash + "." + "0".repeat( 64 ) + "\"" ) + "\n", true );
        assertTrue( html.contains( "data-fence-drawing=\"" ), "the sanitizer keeps a well-formed token: " + html );

        final String out = resolver().resolve( html );

        assertFalse( out.contains( "data-fence-drawing" ), out );
        assertFalse( out.contains( "data-fence-state" ), out );
        assertFalse( out.contains( "wk-fence-drawing" ), out );
        assertTrue( out.contains( ESCAPED_SOURCE ), out );
        assertEquals( 0, drawer.calls.get() );
    }

    @Test
    void handWrittenFigureWrappingARealFenceDrawsOnlyTheRealOne() {
        final String md = "<figure class=\"wk-fence\" data-fence=\"plantuml\" data-fence-drawing=\""
                + FencePlaceholders.sign( SOURCE ) + "\">\n\n" + fence( SOURCE ) + "</figure>\n";
        final String html = render( md, true );
        assertEquals( 2, count( html, "data-fence-drawing=\"" ), "both tokens reach the resolver: " + html );

        final String out = resolver().resolve( html );

        assertEquals( 1, drawer.calls.get(), out );
        assertEquals( 1, count( out, "<div class=\"wk-fence-drawing\"" ), out );
        assertFalse( out.contains( "data-fence-drawing" ), out );
        final int outer = out.indexOf( "<figure" );
        final int inner = out.indexOf( "<figure", outer + 1 );
        assertFalse( out.substring( outer, out.indexOf( '>', outer ) ).contains( "data-fence-state" ), out );
        assertTrue( out.substring( inner, out.indexOf( '>', inner ) ).contains( "data-fence-state=\"drawn\"" ), out );
        assertTrue( out.indexOf( "<div class=\"wk-fence-drawing\"" ) == out.indexOf( '>', inner ) + 1,
                "the drawing goes straight after the real figure's opening tag: " + out );
    }

    @Test
    void tokenTextInsideAnotherAttributeIsNotMatched() {
        final String token = FencePlaceholders.sign( SOURCE );
        final String raw = handWrittenEnvelope( "title='see data-fence-drawing=\"" + token + "\"'" );
        assertEquals( raw, resolver().resolve( raw ), "a single-quoted title holding the token text is not the attribute" );

        final String sanitized = render( raw + "\n", true );
        assertEquals( sanitized, resolver().resolve( sanitized ) );
        assertEquals( 0, drawer.calls.get() );
    }

    @Test
    void htmlWithoutTokensUnchanged() {
        final String html = render( "plain *text*\n\n```java\nint x;\n```\n", false );
        assertSame( html, resolver().resolve( html ) );
        assertNull( resolver().resolve( null ) );
        assertEquals( 0, drawer.calls.get() );
    }

    @Test
    void sameDiagramTwiceDrawsBoth() {
        final String out = resolver().resolve( render( fence( "A -> B\n" ) + fence( "A -> B\n" ), false ) );

        assertEquals( 2, count( out, "data-fence-state=\"drawn\"" ), out );
        assertEquals( 2, count( out, "<div class=\"wk-fence-drawing\"" ), out );
        assertFalse( out.contains( "data-fence-drawing" ), out );
    }

    @ParameterizedTest
    @ValueSource( booleans = { false, true } )
    void specialCharactersRoundTrip( final boolean allowHtml ) {
        final String source = "class Größe\nA -> B : List<String>\nB -> C : a && b\n"
                + "note right of A : it's \"quoted\" + x@y = z `tick`\n";
        final String html = render( fence( source ), allowHtml );
        assertTrue( TOKEN.matcher( html ).find(), html );

        final String out = resolver().resolve( html );

        assertEquals( 1, count( out, "data-fence-state=\"drawn\"" ), out );
        assertEquals( List.of( source ), drawer.sources );
    }

    @Test
    void numericAndNamedEntitiesAreDecoded() {
        final String source = "A -> B : <x> & \"y\" 'z' + @ é 😀\n";
        final String escaped = "A -&gt; B : &lt;x&gt; &amp; &quot;y&quot; &#39;z&#x27; &#43; &#64; &#233; &#128512;\n";
        final String html = "<figure class=\"wk-fence\" data-fence=\"plantuml\" data-fence-kind=\"server\" data-fence-drawing=\""
                + FencePlaceholders.sign( source ) + "\"><pre class=\"wk-fence-source\"><code class=\"language-plantuml\">"
                + escaped + "</code></pre></figure>";

        final String out = resolver().resolve( html );

        assertTrue( out.contains( "data-fence-state=\"drawn\"" ), out );
        assertEquals( List.of( source ), drawer.sources );
    }

    @Test
    void crlfSourceResolves() {
        final String out = resolver().resolve( render( "```plantuml\r\nA -> B\r\nB -> C\r\n```\r\n", false ) );

        assertEquals( 1, count( out, "data-fence-state=\"drawn\"" ), out );
        assertEquals( List.of( "A -> B\nB -> C\n" ), drawer.sources );
    }

    @ParameterizedTest
    @ValueSource( strings = { "> ```plantuml\n> A -> B\n> ```\n", "- item\n\n  ```plantuml\n  A -> B\n  ```\n" } )
    void nestedFenceResolves( final String md ) {
        final String out = resolver().resolve( render( md, true ) );

        assertEquals( 1, count( out, "data-fence-state=\"drawn\"" ), out );
        assertEquals( List.of( "A -> B\n" ), drawer.sources );
    }

    @Test
    void drawerExceptionBecomesRenderFailedAndTheRestStillResolve() {
        drawer.outcome = s -> {
            if ( s.contains( "boom" ) ) {
                throw new IllegalStateException( "boom" );
            }
            return ServerDrawing.drawn( LIGHT, DARK );
        };

        final String out = resolver().resolve( render( fence( "A -> boom\n" ) + fence( "A -> B\n" ), false ) );

        assertEquals( List.of( "render-failed" ), errors( out ) );
        assertEquals( 1, count( out, "data-fence-state=\"drawn\"" ), out );
    }

    @Test
    void nullDrawingBecomesRenderFailed() {
        drawer.outcome = s -> null;
        assertEquals( List.of( "render-failed" ), errors( resolver().resolve( render( fence( "A -> B\n" ), false ) ) ) );
    }

    @Test
    void languageWithoutDrawerIsRefusedAndNoneDrawsNothing() {
        final String html = render( fence( "A -> B\n" ), false );

        assertEquals( List.of( "render-failed" ), errors( ServerFenceResolver.NONE.resolve( html ) ) );
        assertFalse( ServerFenceResolver.NONE.resolve( html ).contains( "data-fence-drawing" ) );
    }
}
