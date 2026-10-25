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
    private static final Pattern FIGURE = Pattern.compile( "<figure\\b[^>]*?\\sdata-fence-drawing=\"[^\"]*\"[^>]*>" );
    private static final Pattern TOKEN_ATTR = Pattern.compile( "\\sdata-fence-drawing=\"([^\"]*)\"" );
    private static final Pattern LANGUAGE_ATTR = Pattern.compile( "\\sdata-fence=\"([^\"]*)\"" );
    private static final Pattern CODE_OPEN = Pattern.compile( "<code\\b[^>]*>" );
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

    /** @return the figure's replacement opening tag, followed by its drawing when there is one */
    private String resolveFigure( final String html, final String tag, final int tagEnd, final Budget budget ) {
        final Matcher token = TOKEN_ATTR.matcher( tag );
        if ( !token.find() ) {
            return tag;
        }
        try {
            final String language = attribute( LANGUAGE_ATTR, tag );
            final String source = sourceOf( html, tagEnd, language );
            if ( source == null || !FencePlaceholders.verify( token.group( 1 ), source ) ) {
                return replaceToken( tag, token, "" );
            }
            final ServerDrawing d = draw( language, source, budget );
            return d.isDrawn()
                    ? replaceToken( tag, token, " data-fence-state=\"drawn\"" ) + drawingHtml( d, source )
                    : replaceToken( tag, token, refusalAttributes( d ) );
        } catch ( final RuntimeException e ) {
            LOG.warn( "A server fence could not be resolved; showing its source as render-failed: {}", e.toString(), e );
            return replaceToken( tag, token, " data-fence-error=\"" + RENDER_FAILED + "\"" );
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

    private static String replaceToken( final String tag, final Matcher token, final String replacement ) {
        return tag.substring( 0, token.start() ) + replacement + tag.substring( token.end() );
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

    private static String attribute( final Pattern attr, final String tag ) {
        final Matcher m = attr.matcher( tag );
        return m.find() ? m.group( 1 ) : null;
    }

    /** @return the unescaped text of the figure's {@code language-…} code element, or null when it has none */
    private static String sourceOf( final String html, final int from, final String language ) {
        final int figureEnd = html.indexOf( "</figure>", from );
        if ( language == null || figureEnd < 0 ) {
            return null;
        }
        final String wanted = "class=\"language-" + language + "\"";
        final Matcher code = CODE_OPEN.matcher( html ).region( from, figureEnd );
        while ( code.find() ) {
            if ( code.group().contains( wanted ) ) {
                final int close = html.indexOf( "</code>", code.end() );
                return close < 0 || close > figureEnd ? null : unescape( html.substring( code.end(), close ) );
            }
        }
        return null;
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
