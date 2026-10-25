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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mermaid renderer (browser kind). Runs the fail-closed safety policy server-side: no callbacks,
 * no script-capable link targets, no {@code %%{...}%%} directive of any kind and no front matter.
 * The JS port in {@code wikantik-frontend} mirrors these rules (and is the only gate for a
 * hand-written envelope; see {@link RenderedFenceRenderer}); both are pinned by the shared fixture.
 */
public final class MermaidFenceRenderer implements RenderedFenceRenderer {

    private static final Logger LOG = LogManager.getLogger( MermaidFenceRenderer.class );

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    /** All quantifiers are possessive so no statement rule can backtrack quadratically. */
    private static final Pattern CLICK_OK = Pattern.compile(
            "^(?:click|link)\\s++\\S++\\s++(?:href\\s++)?\"([^\"]*+)\"(?:\\s++\"[^\"]*+\")?(?:\\s++(?:_self|_blank|_parent|_top))?+$", FLAGS );
    private static final Pattern CLASS_LINK = Pattern.compile( "^link\\s++\\S++\\s++(?:href\\s++)?\".*+$", FLAGS );
    private static final Pattern CALLBACK_LINE = Pattern.compile( "^callback\\s++[^\\s\\-=.<>~].*+$", FLAGS );
    private static final Pattern JSON_VALUE = Pattern.compile( "\"[^\"]*+\"\\s*+:\\s*+\"([^\"]*+)\"" );
    private static final Pattern QUOTED = Pattern.compile( "\"([^\"]*+)\"" );
    private static final Pattern ENTITY = Pattern.compile( "&#[xX]([0-9a-fA-F]++);?+|&#([0-9]++);?+|&(colon|Tab|NewLine|sol);|#([0-9]++);" );
    private static final Map< String, String > NAMED_ENTITIES = Map.of( "colon", ":", "Tab", "\t", "NewLine", "\n", "sol", "/" );

    @Override
    public String language() {
        return "mermaid";
    }

    @Override
    public FenceKind kind() {
        return FenceKind.BROWSER;
    }

    @Override
    public FenceVerdict check( final String source ) {
        try {
            return analyse( source ).verdict;
        } catch ( final RuntimeException e ) {
            LOG.warn( "Mermaid fence policy check failed unexpectedly; refusing the fence: {}", e.toString(), e );
            return FenceVerdict.refused( "render-failed" );
        }
    }

    @Override
    public Set< String > pageLinks( final String source ) {
        try {
            final Analysis a = analyse( source );
            return a.verdict.allowed() ? a.pageLinks : Set.of();
        } catch ( final RuntimeException e ) {
            LOG.warn( "Mermaid page-link extraction failed unexpectedly; reporting no links: {}", e.toString(), e );
            return Set.of();
        }
    }

    private record Analysis( FenceVerdict verdict, Set< String > pageLinks ) { }

    private static Analysis analyse( final String rawSource ) {
        final Set< String > links = new LinkedHashSet<>();
        if ( rawSource == null || rawSource.isEmpty() ) {
            return new Analysis( FenceVerdict.ALLOWED, links );
        }
        final String source = normalise( rawSource );
        if ( source.contains( "%%{" ) || startsFrontMatter( rawSource ) || startsFrontMatter( source ) ) {
            return new Analysis( FenceVerdict.refused( "unsafe-config" ), links );
        }
        if ( hasScriptScheme( source ) ) {
            return new Analysis( FenceVerdict.refused( "unsafe-link" ), links );
        }
        for ( final String line : source.split( "\\n" ) ) {
            // Safety runs over the UNION of quote-aware statements and naive ';' pieces: Mermaid tokens such
            // as sequence message text end at ';' regardless of quotes, so quotes can hide a statement from
            // the quote-aware split. Page links come from quote-aware statements only.
            for ( final String statement : splitStatements( line ) ) {
                final FenceVerdict v = checkStatement( statement.strip(), links );
                if ( v != null ) {
                    return new Analysis( v, Set.of() );
                }
            }
            for ( final String piece : line.split( ";", -1 ) ) {
                final FenceVerdict v = checkStatement( piece.strip(), null );
                if ( v != null ) {
                    return new Analysis( v, Set.of() );
                }
            }
        }
        return new Analysis( FenceVerdict.ALLOWED, links );
    }

    /**
     * Applies the click/callback/link/links rules to one statement; returns the refusal, or {@code null} when
     * the statement is safe. Page names of its URLs are added to {@code links} unless that is {@code null}.
     */
    private static FenceVerdict checkStatement( final String s, final Set< String > links ) {
        final List< String > urls = new ArrayList<>();
        final String first = firstWord( s );
        if ( first.equals( "click" ) ) {
            final Matcher m = CLICK_OK.matcher( s );
            if ( !m.matches() ) {
                return FenceVerdict.refused( "callback" );
            }
            urls.add( m.group( 1 ) );
        } else if ( first.equals( "callback" ) && CALLBACK_LINE.matcher( s ).matches() ) {
            return FenceVerdict.refused( "callback" );
        } else if ( first.equals( "link" ) || first.equals( "links" ) ) {
            final String rest = s.substring( first.length() );
            final int colon = rest.indexOf( ':' );
            final int at = colon < 0 ? -1 : rest.indexOf( '@', colon + 1 );
            if ( first.equals( "link" ) && at >= 0 ) {
                // sequence link: the URL follows the FIRST '@' after the actor's ':'
                urls.add( rest.substring( at + 1 ).strip() );
            } else if ( first.equals( "link" ) && CLASS_LINK.matcher( s ).matches() ) {
                final Matcher m = CLICK_OK.matcher( s );
                if ( !m.matches() ) {
                    return FenceVerdict.refused( "unsafe-link" );
                }
                urls.add( m.group( 1 ) );
            } else if ( first.equals( "links" ) && colon >= 0 && rest.substring( colon + 1 ).strip().startsWith( "{" ) ) {
                final String json = rest.substring( colon + 1 );
                if ( json.indexOf( '\\' ) >= 0 ) {
                    return FenceVerdict.refused( "unsafe-link" );
                }
                final Matcher v = JSON_VALUE.matcher( json );
                while ( v.find() ) {
                    urls.add( v.group( 1 ) );
                }
            }
        }
        if ( ( first.equals( "click" ) || first.equals( "link" ) || first.equals( "links" ) ) && !quotedStringsSafe( s ) ) {
            return FenceVerdict.refused( "unsafe-link" );
        }
        final List< String > names = new ArrayList<>();
        for ( final String url : urls ) {
            final String decoded = decodeEntities( url ).strip();
            if ( !safeUrl( decoded ) ) {
                return FenceVerdict.refused( "unsafe-link" );
            }
            pageName( decoded ).ifPresent( names::add );
        }
        if ( links != null ) {
            links.addAll( names );
        }
        return null;
    }

    /** Leading ASCII letters of the statement, lower-cased, when followed by a space or the end; else "". */
    private static String firstWord( final String s ) {
        int i = 0;
        while ( i < s.length() && ( s.charAt( i ) | 0x20 ) >= 'a' && ( s.charAt( i ) | 0x20 ) <= 'z' ) {
            i++;
        }
        return i == 0 || ( i < s.length() && s.charAt( i ) != ' ' ) ? "" : s.substring( 0, i ).toLowerCase( Locale.ROOT );
    }

    /**
     * Normalises before any analysis: only CRLF/CR become LF; every other (including U+2028, U+2029, U+0085, VT, FF)
     * whitespace, space-separator or format character (NBSP, U+2000-U+200B, U+3000, BOM, ...) becomes a space.
     */
    private static String normalise( final String s ) {
        final StringBuilder sb = new StringBuilder( s.length() );
        for ( int i = 0; i < s.length(); i++ ) {
            final char c = s.charAt( i );
            if ( c == '\r' ) {
                sb.append( '\n' );
                if ( i + 1 < s.length() && s.charAt( i + 1 ) == '\n' ) {
                    i++;
                }
            } else if ( c == '\n' ) {
                sb.append( '\n' );
            } else if ( Character.isWhitespace( c ) || Character.isSpaceChar( c ) || c == '\u0085' || Character.getType( c ) == Character.FORMAT ) {
                sb.append( ' ' );
            } else {
                sb.append( c );
            }
        }
        return sb.toString();
    }

    /**
     * Front matter is refused outright (no YAML interpretation): true when, after skipping leading
     * whitespace, control and format characters (Java or JS definitions), the text begins with {@code ---}.
     */
    private static boolean startsFrontMatter( final String text ) {
        int i = 0;
        while ( i < text.length() ) {
            final char c = text.charAt( i );
            if ( Character.isWhitespace( c ) || Character.isSpaceChar( c ) || Character.isISOControl( c )
                    || Character.getType( c ) == Character.FORMAT || c == '\uFEFF' ) {
                i++;
            } else {
                break;
            }
        }
        return text.startsWith( "---", i );
    }

    /** Every double-quoted string in a click/link/links statement must be a safe URL or contain no ':'. */
    private static boolean quotedStringsSafe( final String statement ) {
        final Matcher m = QUOTED.matcher( statement );
        while ( m.find() ) {
            final String decoded = decodeEntities( m.group( 1 ) ).strip();
            if ( decoded.indexOf( ':' ) >= 0 && !safeUrl( decoded ) ) {
                return false;
            }
        }
        return true;
    }

    /** Backstop: after entity decoding and dropping whitespace/control chars, a script scheme anywhere refuses. */
    private static boolean hasScriptScheme( final String source ) {
        final String decoded = decodeEntities( source );
        final StringBuilder sb = new StringBuilder( decoded.length() );
        for ( int i = 0; i < decoded.length(); i++ ) {
            final char c = decoded.charAt( i );
            if ( c > 0x20 && !( c >= 0x7f && c <= 0x9f ) ) {
                sb.append( c );
            }
        }
        final String u = sb.toString().toLowerCase( Locale.ROOT );
        return u.contains( "javascript:" ) || u.contains( "vbscript:" );
    }

    /** Splits a line on {@code ;} outside double quotes (Mermaid allows several statements per line). */
    private static List< String > splitStatements( final String line ) {
        final List< String > out = new ArrayList<>();
        boolean quoted = false;
        int start = 0;
        for ( int i = 0; i < line.length(); i++ ) {
            final char c = line.charAt( i );
            if ( c == '"' ) {
                quoted = !quoted;
            } else if ( c == ';' && !quoted ) {
                out.add( line.substring( start, i ) );
                start = i + 1;
            }
        }
        out.add( line.substring( start ) );
        return out;
    }

    private static String decodeEntities( final String s ) {
        final Matcher m = ENTITY.matcher( s );
        final StringBuilder sb = new StringBuilder();
        while ( m.find() ) {
            String rep = m.group();
            try {
                if ( m.group( 1 ) != null ) {
                    rep = codePoint( Integer.parseInt( m.group( 1 ), 16 ), rep );
                } else if ( m.group( 2 ) != null ) {
                    rep = codePoint( Integer.parseInt( m.group( 2 ) ), rep );
                } else if ( m.group( 3 ) != null ) {
                    rep = NAMED_ENTITIES.get( m.group( 3 ) );
                } else if ( m.group( 4 ) != null ) {
                    rep = codePoint( Integer.parseInt( m.group( 4 ) ), rep );
                }
            } catch ( final NumberFormatException e ) {
                LOG.warn( "Unparseable entity '{}' in Mermaid link target; leaving it undecoded: {}", m.group(), e.getMessage() );
            }
            m.appendReplacement( sb, Matcher.quoteReplacement( rep ) );
        }
        m.appendTail( sb );
        return sb.toString();
    }

    private static String codePoint( final int cp, final String fallback ) {
        return Character.isValidCodePoint( cp ) ? new String( Character.toChars( cp ) ) : fallback;
    }

    /** Applies the scheme rule after removing whitespace/control chars browsers ignore inside a scheme. */
    private static boolean safeUrl( final String url ) {
        final StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < url.length(); i++ ) {
            final char c = url.charAt( i );
            if ( c > 0x20 && !( c >= 0x7f && c <= 0x9f ) && c != '\u200b' && c != '\u00ad' ) {
                sb.append( c );
            }
        }
        final String u = sb.toString().toLowerCase( Locale.ROOT );
        if ( u.startsWith( "/" ) || u.startsWith( "#" ) || u.startsWith( "http://" ) || u.startsWith( "https://" ) ) {
            return true;
        }
        final int colon = u.indexOf( ':' );
        if ( colon < 0 ) {
            return true;
        }
        final int slash = u.indexOf( '/' );
        return slash >= 0 && slash < colon;
    }

    private static java.util.Optional< String > pageName( final String url ) {
        if ( !url.startsWith( "/wiki/" ) ) {
            return java.util.Optional.empty();
        }
        String name = url.substring( "/wiki/".length() );
        final int cut = firstIndexOf( name, '#', '?' );
        if ( cut >= 0 ) {
            name = name.substring( 0, cut );
        }
        try {
            name = URLDecoder.decode( name.replace( "+", "%2B" ), StandardCharsets.UTF_8 );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "Mermaid link target '{}' has bad percent-encoding; not tracked: {}", url, e.getMessage() );
            return java.util.Optional.empty();
        }
        return name.isBlank() ? java.util.Optional.empty() : java.util.Optional.of( name );
    }

    private static int firstIndexOf( final String s, final char a, final char b ) {
        final int i = s.indexOf( a );
        final int j = s.indexOf( b );
        return i < 0 ? j : j < 0 ? i : Math.min( i, j );
    }
}
