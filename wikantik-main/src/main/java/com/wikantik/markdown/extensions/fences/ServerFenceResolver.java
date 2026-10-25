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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the signed {@code data-fence-drawing} placeholders that {@link RenderedFenceNodeRenderer} emits for
 * server-kind fences into drawings, once, after the last sanitize pass and after every HTML cache read (see
 * {@link RenderedFenceRenderer}). For each placeholder, in document order:
 * <ul>
 *   <li>the fence source is read back from the figure's {@code <code class="language-…">} element and HTML-unescaped;
 *       a token that does not {@link FencePlaceholders#verify verify} against it is removed and the source stays
 *       shown (a forged or foreign placeholder draws nothing);</li>
 *   <li>at most {@code pageMaxDiagrams} figures are drawn, all within one shared {@code pageBudgetMs} deadline; each
 *       draw gets the remaining budget, and a figure past either limit is refused as {@code page-budget};</li>
 *   <li>a drawing replaces the token with {@code data-fence-state="drawn"} and is inserted straight after the opening
 *       tag (light and dark variants); a refusal replaces it with {@code data-fence-error} plus the optional
 *       {@code data-fence-error-line} and {@code data-fence-error-message}.</li>
 * </ul>
 * {@link #resolve} never throws: a figure whose resolution fails unexpectedly is refused as {@code render-failed}
 * and the rest of the page is still resolved.
 */
public final class ServerFenceResolver {

    private static final Logger LOG = LogManager.getLogger( ServerFenceResolver.class );

    /** A resolver with no drawers: every verified placeholder is refused as {@code render-failed}. */
    public static final ServerFenceResolver NONE = new ServerFenceResolver( Map.of(), 0, 0L );

    private static final String MARKER = "data-fence-drawing=\"";
    private static final String RENDER_FAILED = "render-failed";
    private static final String DEFAULT_LABEL = "PlantUML diagram";
    /** A quote-aware tag body: attribute values may hold {@code >} or the token text without ending the tag. */
    private static final String TAG_BODY = "(?:[^>\"']++|\"[^\"]*+\"|'[^']*+')*+";
    private static final Pattern FIGURE = Pattern.compile( "<figure(?=[\\s>])" + TAG_BODY + ">" );
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "\\s+([^\\s\"'>/=]+)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+)))?" );
    /** The only body a placeholder figure may have before its source: {@code <pre …><code …>}. */
    private static final Pattern ENVELOPE_OPEN = Pattern.compile( "\\s*<pre(?=[\\s>])" + TAG_BODY + ">\\s*<code(?=[\\s>])("
            + TAG_BODY + ")>" );
    private static final Pattern ENVELOPE_CLOSE = Pattern.compile( "</code>\\s*</pre>\\s*</figure>" );
    private static final Pattern TITLE = Pattern.compile( "^[ \\t]*title[ \\t]+(\\S.*?)[ \\t]*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE );
    private static final Pattern ENTITY = Pattern.compile( "&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,6});" );
    private static final Map< String, String > NAMED = Map.of( "lt", "<", "gt", ">", "amp", "&", "quot", "\"", "apos", "'" );

    private final Map< String, ServerFenceDrawer > drawers;
    private final int pageMaxDiagrams;
    private final long pageBudgetMs;

    /** The draws one {@link #resolve} call may still make. */
    private static final class Budget {
        private int drawsLeft;
        private final long deadlineNanos;

        Budget( final int draws, final long budgetMs ) {
            this.drawsLeft = draws;
            this.deadlineNanos = System.nanoTime() + Math.max( 0L, budgetMs ) * 1_000_000L;
        }

        /** @return the milliseconds the next draw may wait, or 0 when the page is out of draws or time */
        long claim() {
            final long leftMs = ( deadlineNanos - System.nanoTime() ) / 1_000_000L;
            if ( drawsLeft <= 0 || leftMs <= 0L ) {
                return 0L;
            }
            drawsLeft--;
            return leftMs;
        }
    }

    /**
     * @param drawers         drawers by fence language
     * @param pageMaxDiagrams the most figures one call draws
     * @param pageBudgetMs    the wall-clock budget one call shares across all its draws
     */
    public ServerFenceResolver( final Map< String, ServerFenceDrawer > drawers, final int pageMaxDiagrams,
                                final long pageBudgetMs ) {
        this.drawers = Map.copyOf( drawers );
        this.pageMaxDiagrams = pageMaxDiagrams;
        this.pageBudgetMs = pageBudgetMs;
    }

    /** @return the drawer for {@code language}, if any */
    public Optional< ServerFenceDrawer > drawer( final String language ) {
        return Optional.ofNullable( language == null ? null : drawers.get( language ) );
    }

    /**
     * @param html rendered HTML; may be {@code null}
     * @return the HTML with every placeholder resolved, or {@code html} itself when it holds none; never throws
     */
    public String resolve( final String html ) {
        if ( html == null || !html.contains( MARKER ) ) {
            return html;
        }
        try {
            return resolveAll( html );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Resolving server fence placeholders failed unexpectedly; serving the page undrawn: {}", e.toString(), e );
            return html;
        }
    }

    private String resolveAll( final String html ) {
        final Budget budget = new Budget( pageMaxDiagrams, pageBudgetMs );
        final Matcher m = FIGURE.matcher( html );
        final StringBuilder out = new StringBuilder( html.length() + 8192 );
        int last = 0;
        while ( m.find() ) {
            out.append( html, last, m.start() ).append( resolveFigure( html, m.group(), m.end(), budget ) );
            last = m.end();
        }
        return out.append( html, last, html.length() ).toString();
    }

    /** A figure opening tag's placeholder attribute (its span within the tag, including leading space) and language. */
    private record FigureTag( int tokenStart, int tokenEnd, String token, String language ) {

        /** @return the parsed tag, or null when it carries no {@code data-fence-drawing} attribute */
        static FigureTag parse( final String tag ) {
            final Matcher m = ATTRIBUTE.matcher( tag );
            int pos = "<figure".length();
            int start = -1;
            int end = -1;
            String token = null;
            String language = null;
            while ( pos < tag.length() - 1 && m.region( pos, tag.length() - 1 ).lookingAt() ) {
                if ( "data-fence-drawing".equals( m.group( 1 ) ) && token == null ) {
                    start = m.start();
                    end = m.end();
                    token = value( m );
                } else if ( "data-fence".equals( m.group( 1 ) ) ) {
                    language = value( m );
                }
                pos = m.end();
            }
            return token == null ? null : new FigureTag( start, end, token, language );
        }

        private static String value( final Matcher m ) {
            for ( int g = 2; g <= 4; g++ ) {
                if ( m.group( g ) != null ) {
                    return m.group( g );
                }
            }
            return "";
        }

        String replaceToken( final String tag, final String replacement ) {
            return tag.substring( 0, tokenStart ) + replacement + tag.substring( tokenEnd );
        }
    }

    /** @return the figure's replacement opening tag, followed by its drawing when there is one */
    private String resolveFigure( final String html, final String tag, final int tagEnd, final Budget budget ) {
        final FigureTag figure = tag.contains( MARKER ) ? FigureTag.parse( tag ) : null;
        if ( figure == null ) {
            return tag;
        }
        try {
            final String source = sourceOf( html, tagEnd, figure.language() );
            if ( source == null || !FencePlaceholders.verify( figure.token(), source ) ) {
                return figure.replaceToken( tag, "" );
            }
            final ServerDrawing d = draw( figure.language(), source, budget );
            return d.isDrawn()
                    ? figure.replaceToken( tag, " data-fence-state=\"drawn\"" ) + drawingHtml( d, source )
                    : figure.replaceToken( tag, refusalAttributes( d ) );
        } catch ( final RuntimeException e ) {
            LOG.warn( "A server fence could not be resolved; showing its source as render-failed: {}", e.toString(), e );
            return figure.replaceToken( tag, " data-fence-error=\"" + RENDER_FAILED + "\"" );
        }
    }

    private ServerDrawing draw( final String language, final String source, final Budget budget ) {
        final ServerFenceDrawer drawer = language == null ? null : drawers.get( language );
        if ( drawer == null ) {
            return ServerDrawing.refused( RENDER_FAILED, "No server drawer is configured for this fence", 0, true );
        }
        final long budgetMs = budget.claim();
        if ( budgetMs <= 0L ) {
            return ServerDrawing.refused( "page-budget", "This page has used its diagram drawing budget", 0, true );
        }
        final ServerDrawing d = drawer.draw( source, budgetMs );
        if ( d == null ) {
            LOG.warn( "The {} drawer returned no outcome; refusing the fence as render-failed", language );
            return ServerDrawing.refused( RENDER_FAILED, null, 0, true );
        }
        return d;
    }

    private static String refusalAttributes( final ServerDrawing d ) {
        final StringBuilder sb = new StringBuilder( " data-fence-error=\"" )
                .append( escape( d.reason() == null ? RENDER_FAILED : d.reason() ) ).append( '"' );
        if ( d.line() > 0 ) {
            sb.append( " data-fence-error-line=\"" ).append( d.line() ).append( '"' );
        }
        if ( d.message() != null && !d.message().isBlank() ) {
            sb.append( " data-fence-error-message=\"" ).append( escape( d.message() ) ).append( '"' );
        }
        return sb.toString();
    }

    private static String drawingHtml( final ServerDrawing d, final String source ) {
        return "<div class=\"wk-fence-drawing\" role=\"img\" aria-label=\"" + escape( label( source ) ) + "\">"
                + "<div class=\"wk-fence-variant\" data-variant=\"light\">" + d.lightSvg() + "</div>"
                + "<div class=\"wk-fence-variant\" data-variant=\"dark\" hidden>" + d.darkSvg() + "</div></div>";
    }

    /** @return the text of the source's first {@code title …} line, or the generic label */
    static String label( final String source ) {
        final Matcher m = TITLE.matcher( source );
        return m.find() ? m.group( 1 ) : DEFAULT_LABEL;
    }

    /**
     * @return the unescaped source when the figure's body is exactly the envelope the node renderer emits
     *         ({@code <pre …><code class="language-…">SOURCE</code></pre></figure>}), otherwise null; so a hand-written
     *         figure wrapping other markup (even a real fence) never receives a drawing
     */
    private static String sourceOf( final String html, final int from, final String language ) {
        if ( language == null ) {
            return null;
        }
        final Matcher open = ENVELOPE_OPEN.matcher( html ).region( from, html.length() );
        if ( !open.lookingAt() || !open.group( 1 ).contains( " class=\"language-" + language + "\"" ) ) {
            return null;
        }
        final int close = html.indexOf( "</code>", open.end() );
        if ( close < 0 || !ENVELOPE_CLOSE.matcher( html ).region( close, html.length() ).lookingAt() ) {
            return null;
        }
        return unescape( html.substring( open.end(), close ) );
    }

    /** Decodes the named ({@code lt gt amp quot apos}) and numeric entities an HTML serializer may emit. */
    static String unescape( final String text ) {
        if ( text.indexOf( '&' ) < 0 ) {
            return text;
        }
        final Matcher m = ENTITY.matcher( text );
        final StringBuilder sb = new StringBuilder( text.length() );
        while ( m.find() ) {
            m.appendReplacement( sb, Matcher.quoteReplacement( decode( m.group( 1 ), m.group() ) ) );
        }
        m.appendTail( sb );
        return sb.toString();
    }

    private static String decode( final String body, final String whole ) {
        if ( body.charAt( 0 ) != '#' ) {
            return NAMED.getOrDefault( body, whole );
        }
        final boolean hex = body.length() > 1 && ( body.charAt( 1 ) == 'x' || body.charAt( 1 ) == 'X' );
        final int cp = Integer.parseInt( body.substring( hex ? 2 : 1 ), hex ? 16 : 10 );
        return Character.isValidCodePoint( cp ) ? new String( Character.toChars( cp ) ) : whole;
    }

    private static String escape( final String text ) {
        final StringBuilder sb = new StringBuilder( text.length() + 16 );
        for ( int i = 0; i < text.length(); i++ ) {
            final char c = text.charAt( i );
            switch ( c ) {
                case '&' -> sb.append( "&amp;" );
                case '<' -> sb.append( "&lt;" );
                case '>' -> sb.append( "&gt;" );
                case '"' -> sb.append( "&quot;" );
                case '\'' -> sb.append( "&#39;" );
                default -> sb.append( c );
            }
        }
        return sb.toString();
    }
}
