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
import com.wikantik.render.RenderingManager;
import com.wikantik.render.markdown.MarkdownRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The reader's third of the live-preview parity fixture ({@code live-preview-parity.json}): every entry is rendered
 * through the path that produces {@code page.contentHtml} ({@code RenderingManager.textToHTML}, as {@code PageResource}
 * calls it) and classified into the fixture's kinds. The editor's live preview ({@code livePreview/parity.test.js}) and
 * the editor preview pane ({@code EditorPreviewMarkdown.parity.test.jsx}) classify the same entries, so the three agree
 * on what each construct is.
 *
 * <p>The expectation here is the entry's {@code readerExpect}, else its {@code kind}. An entry that states any
 * per-renderer expectation must say why in {@code notes}. Classification is kind-aware: the predicate of the expected
 * label must hold; on a mismatch every label that does hold is listed, with the HTML.
 */
class LivePreviewParityTest {

    private static final String HOST = "LivePreviewParityHost";
    private static final Path FIXTURE = Path.of( "..", "wikantik-frontend", "src", "utils", "__fixtures__", "live-preview-parity.json" );
    private static final Set< String > EXPECT_FIELDS = Set.of( "liveExpect", "previewExpect", "readerExpect" );

    private static final Pattern TAG = Pattern.compile( "<(/?)([A-Za-z][A-Za-z0-9-]*)([^>]*?)(/?)>" );
    private static final Pattern ATTR = Pattern.compile( "([A-Za-z_:][-A-Za-z0-9_:.]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+)))?" );
    private static final Pattern COMMENT = Pattern.compile( "<!--.*?-->", Pattern.DOTALL );
    private static final Set< String > VOID = Set.of( "br", "hr", "img", "input", "meta", "link", "wbr", "col", "source" );

    /** The fixture's labels: every kind, plus {@code literal} (shown as its source text) and {@code chip}. */
    private static final Map< String, BiPredicate< El, JsonObject > > PREDICATES = new LinkedHashMap<>();

    static {
        PREDICATES.put( "table", ( root, e ) -> root.any( el -> el.is( "table" ) && !el.inside( "li", "blockquote" ) ) );
        PREDICATES.put( "table-nested", ( root, e ) -> root.any( el -> el.is( "table" ) && el.inside( "li", "blockquote" ) ) );
        PREDICATES.put( "footnote-ref", ( root, e ) -> hasFootnoteRef( root ) );
        PREDICATES.put( "footnote-def", ( root, e ) -> root.any( el -> el.is( "li" ) && el.insideClass( "footnotes" )
                && !el.text().isBlank() ) );
        PREDICATES.put( "ref-link", ( root, e ) -> hasTextLink( root ) && !root.text().contains( "[" ) );
        PREDICATES.put( "shortcut-link", PREDICATES.get( "ref-link" ) );
        PREDICATES.put( "ref-link-undefined", ( root, e ) -> !root.any( el -> el.is( "a" ) )
                && root.text().contains( "[" ) && root.text().contains( "]" ) );
        PREDICATES.put( "autolink", ( root, e ) -> root.any( el -> el.is( "a" ) && isUrlLink( el ) ) );
        PREDICATES.put( "bare-url", PREDICATES.get( "autolink" ) );
        PREDICATES.put( "escaped", ( root, e ) -> root.text().contains( unescaped( firstLine( e ) ) )
                && !root.any( el -> el.is( "em" ) || el.is( "strong" ) ) && !hasTextLink( root ) );
        PREDICATES.put( "escaped-footnote", ( root, e ) -> !hasFootnoteRef( root ) && root.text().contains( "[^" ) );
        PREDICATES.put( "setext-h1", ( root, e ) -> root.any( el -> el.is( "h1" ) ) );
        PREDICATES.put( "setext-h2", ( root, e ) -> root.any( el -> el.is( "h2" ) ) );
        PREDICATES.put( "html-block", ( root, e ) -> {
            final Matcher m = TAG.matcher( firstLine( e ) );
            return m.find() && root.any( el -> el.is( m.group( 2 ).toLowerCase() ) ) && !root.text().contains( "<" );
        } );
        PREDICATES.put( "plugin-line", ( root, e ) -> !root.text().contains( "[{" ) && !root.any( el -> el.hasClass( "wiki-plugin-chip" ) ) );
        PREDICATES.put( "inline-math", ( root, e ) -> root.any( el -> ( el.hasClass( "math-inline" ) || el.hasClass( "katex" ) )
                && !el.insideClass( "math-display" ) && !el.insideClass( "katex-display" ) ) );
        PREDICATES.put( "display-math", ( root, e ) -> root.any( el -> el.hasClass( "math-display" ) || el.hasClass( "katex-display" ) ) );
        PREDICATES.put( "callout", ( root, e ) -> root.any( el -> el.hasClass( "callout" ) ) );
        PREDICATES.put( "task", ( root, e ) -> root.any( el -> el.is( "input" ) && "checkbox".equals( el.attrs.get( "type" ) ) ) );
        PREDICATES.put( "wikilink", ( root, e ) -> root.any( el -> el.is( "a" ) && el.attrs.getOrDefault( "href", "" ).contains( "OtherPage" )
                && !el.text().contains( "[[" ) ) && !root.text().contains( "[[" ) );
        PREDICATES.put( "chip", ( root, e ) -> root.any( el -> el.hasClass( "wiki-plugin-chip" ) ) );
        PREDICATES.put( "literal", ( root, e ) -> !PREDICATES.get( e.get( "kind" ).getAsString() ).test( root, e )
                && root.text().contains( firstLine( e ) ) );
    }

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
    void readerAgreesWithTheSharedParityFixture() throws Exception {
        final JsonArray entries = JsonParser.parseString( Files.readString( FIXTURE ) ).getAsJsonArray();
        assertFalse( entries.isEmpty() );
        final List< String > mismatches = new ArrayList<>();
        for ( final JsonElement element : entries ) {
            final JsonObject e = element.getAsJsonObject();
            final String name = e.get( "name" ).getAsString();
            final boolean differs = EXPECT_FIELDS.stream().anyMatch( e::has );
            if ( differs && ( !e.has( "notes" ) || e.get( "notes" ).getAsString().isBlank() ) ) {
                mismatches.add( name + ": a per-renderer expectation must say why in notes" );
            }
            if ( !PREDICATES.containsKey( e.get( "kind" ).getAsString() ) ) {
                mismatches.add( name + ": unknown kind " + e.get( "kind" ) );
                continue;
            }
            final String expected = e.has( "readerExpect" ) ? e.get( "readerExpect" ).getAsString() : e.get( "kind" ).getAsString();
            if ( !PREDICATES.containsKey( expected ) ) {
                mismatches.add( name + ": unknown readerExpect " + expected );
                continue;
            }
            final String html = render( e.get( "markdown" ).getAsString() );
            final El root = parse( html );
            if ( !PREDICATES.get( expected ).test( root, e ) ) {
                final List< String > observed = new ArrayList<>();
                PREDICATES.forEach( ( label, p ) -> {
                    if ( p.test( root, e ) ) {
                        observed.add( label );
                    }
                } );
                mismatches.add( name + "\n  expected " + expected + "\n  observed " + observed + "\n  html     " + html );
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

    private static boolean hasFootnoteRef( final El root ) {
        return root.any( el -> el.is( "a" ) && el.attrs.getOrDefault( "href", "" ).startsWith( "#" ) && el.inside( "sup" ) );
    }

    /** A link whose text is not its own URL: a styled link label (inline, reference or shortcut). */
    private static boolean hasTextLink( final El root ) {
        return root.any( el -> el.is( "a" ) && el.attrs.getOrDefault( "href", "" ).startsWith( "http" ) && !isBroken( el ) && !isUrlLink( el ) );
    }

    /** A link flagged as a missing attachment (the editor preview's rule; kept here so both sides read links alike). */
    private static boolean isBroken( final El a ) {
        return a.attrs.containsKey( "data-attachment-missing" );
    }

    private static boolean isUrlLink( final El a ) {
        if ( isBroken( a ) ) {
            return false;
        }
        final String href = a.attrs.getOrDefault( "href", "" );
        final String text = a.text().trim();
        // the link text is its own target (GFM writes www.x as http://www.x); trailing sentence punctuation stays outside
        return !text.isEmpty() && !text.matches( ".*[.,:;!?]$" )
                && ( href.equals( text ) || href.equals( "mailto:" + text ) || href.equals( "http://" + text ) );
    }

    private static String firstLine( final JsonObject e ) {
        return e.get( "markdown" ).getAsString().split( "\n", -1 )[ 0 ];
    }

    private static String unescaped( final String s ) {
        return s.replaceAll( "\\\\(\\p{Punct})", "$1" );
    }

    /** A parsed element: tag, attributes, children (elements only) and its text (all descendant text). */
    static final class El {
        final String tag;
        final Map< String, String > attrs;
        final El parent;
        final List< El > children = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();

        El( final String tag, final Map< String, String > attrs, final El parent ) {
            this.tag = tag;
            this.attrs = attrs;
            this.parent = parent;
        }

        boolean is( final String t ) {
            return tag.equals( t );
        }

        String text() {
            return text.toString();
        }

        boolean hasClass( final String c ) {
            return List.of( attrs.getOrDefault( "class", "" ).split( "\\s+" ) ).contains( c );
        }

        boolean inside( final String... tags ) {
            for ( El p = parent; p != null; p = p.parent ) {
                for ( final String t : tags ) {
                    if ( p.is( t ) ) {
                        return true;
                    }
                }
            }
            return false;
        }

        boolean insideClass( final String c ) {
            for ( El p = parent; p != null; p = p.parent ) {
                if ( p.hasClass( c ) ) {
                    return true;
                }
            }
            return false;
        }

        boolean any( final java.util.function.Predicate< El > p ) {
            for ( final El c : children ) {
                if ( p.test( c ) || c.any( p ) ) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A forgiving tag scan into an element tree (flexmark's output is HTML, not XML); unclosed tags close with their parent. */
    static El parse( final String html ) {
        final El root = new El( "#root", Map.of(), null );
        final Deque< El > stack = new ArrayDeque<>();
        stack.push( root );
        final String source = COMMENT.matcher( html ).replaceAll( "" );
        final Matcher m = TAG.matcher( source );
        int pos = 0;
        while ( m.find() ) {
            final String text = decode( source.substring( pos, m.start() ) );
            for ( final El open : stack ) {
                open.text.append( text );
            }
            pos = m.end();
            final String tag = m.group( 2 ).toLowerCase();
            if ( !m.group( 1 ).isEmpty() ) {
                if ( stack.stream().anyMatch( o -> o.is( tag ) ) ) {
                    // pops unclosed descendants up to and including the matching open tag (never the root)
                    El popped = stack.pop();
                    while ( !popped.is( tag ) ) {
                        popped = stack.pop();
                    }
                }
                continue;
            }
            final El el = new El( tag, attrs( m.group( 3 ) ), stack.peek() );
            stack.peek().children.add( el );
            if ( !VOID.contains( tag ) && m.group( 4 ).isEmpty() ) {
                stack.push( el );
            }
        }
        final String tail = decode( source.substring( pos ) );
        for ( final El open : stack ) {
            open.text.append( tail );
        }
        return root;
    }

    private static Map< String, String > attrs( final String raw ) {
        final Map< String, String > out = new LinkedHashMap<>();
        final Matcher m = ATTR.matcher( raw );
        while ( m.find() ) {
            final String v = m.group( 2 ) != null ? m.group( 2 ) : m.group( 3 ) != null ? m.group( 3 ) : m.group( 4 );
            out.put( m.group( 1 ).toLowerCase(), v == null ? "" : decode( v ) );
        }
        return out;
    }

    private static String decode( final String s ) {
        return s.replace( "&lt;", "<" ).replace( "&gt;", ">" ).replace( "&quot;", "\"" ).replace( "&#34;", "\"" )
                .replace( "&#39;", "'" ).replace( "&#x27;", "'" ).replace( "&#61;", "=" ).replace( "&#91;", "[" )
                .replace( "&#93;", "]" ).replace( "&#123;", "{" ).replace( "&#125;", "}" ).replace( "&#92;", "\\" )
                .replace( "&#94;", "^" ).replace( "&#64;", "@" ).replace( "&#42;", "*" ).replace( "&nbsp;", " " ).replace( "&amp;", "&" );
    }
}
