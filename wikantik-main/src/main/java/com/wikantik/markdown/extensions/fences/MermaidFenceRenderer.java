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
 * Mermaid renderer (browser kind). Enforces the safety policy server-side: no callbacks, no
 * script-capable link targets, and no init directive / YAML config touching Mermaid's
 * {@code secure} settings. The JS port in {@code wikantik-frontend} mirrors these rules and both
 * are pinned by the shared fixture.
 */
public final class MermaidFenceRenderer implements RenderedFenceRenderer {

    private static final Logger LOG = LogManager.getLogger( MermaidFenceRenderer.class );

    /** Mermaid's default {@code secure} keys plus the settings that would loosen the sandbox. */
    private static final List< String > UNSAFE_KEYS = List.of( "secure", "securityLevel", "startOnLoad",
            "maxTextSize", "maxEdges", "suppressErrorRendering", "dompurifyConfig" );

    private static final String KEY_ALT = String.join( "|", UNSAFE_KEYS );
    private static final Pattern KEY_USE = Pattern.compile( "(?<![A-Za-z0-9_])[\"']?(?:" + KEY_ALT + ")[\"']?\\s*:" );
    private static final Pattern DIRECTIVE = Pattern.compile( "%%\\{\\s*(?:init|initialize)\\s*:(.*?)\\}%%", Pattern.DOTALL );
    private static final Pattern CLICK_LINE = Pattern.compile( "^click(?:\\s.*)?$" );
    private static final Pattern CLICK_OK = Pattern.compile(
            "^click\\s+\\S+\\s+(?:href\\s+)?\"([^\"]*)\"(?:\\s+\"[^\"]*\")?(?:\\s+(?:_self|_blank|_parent|_top))?$" );
    private static final Pattern LINK_LINE = Pattern.compile( "^link\\s+[^:@]+:.*@\\s*(\\S.*)$" );
    private static final Pattern LINKS_LINE = Pattern.compile( "^links\\s+[^:]+:\\s*(\\{.*)$" );
    private static final Pattern JSON_VALUE = Pattern.compile( "\"[^\"]*\"\\s*:\\s*\"([^\"]*)\"" );
    private static final Pattern ENTITY = Pattern.compile( "&#[xX]([0-9a-fA-F]+);?|&#([0-9]+);?|&(colon|Tab|NewLine|sol);|#([0-9]+);" );

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

    private static Analysis analyse( final String source ) {
        final Set< String > links = new LinkedHashSet<>();
        if ( source == null || source.isEmpty() ) {
            return new Analysis( FenceVerdict.ALLOWED, links );
        }
        if ( touchesSecurityConfig( source ) ) {
            return new Analysis( FenceVerdict.refused( "unsafe-config" ), links );
        }
        for ( final String line : source.split( "\\R" ) ) {
            for ( final String statement : splitStatements( line ) ) {
                final String s = statement.strip();
                final List< String > urls = new ArrayList<>();
                if ( CLICK_LINE.matcher( s ).matches() ) {
                    final Matcher m = CLICK_OK.matcher( s );
                    if ( !m.matches() ) {
                        return new Analysis( FenceVerdict.refused( "callback" ), Set.of() );
                    }
                    urls.add( m.group( 1 ) );
                } else {
                    final Matcher link = LINK_LINE.matcher( s );
                    final Matcher links2 = LINKS_LINE.matcher( s );
                    if ( link.matches() ) {
                        urls.add( link.group( 1 ) );
                    } else if ( links2.matches() ) {
                        final Matcher v = JSON_VALUE.matcher( links2.group( 1 ) );
                        while ( v.find() ) {
                            urls.add( v.group( 1 ) );
                        }
                    }
                }
                for ( final String url : urls ) {
                    final String decoded = decodeEntities( url ).strip();
                    if ( !safeUrl( decoded ) ) {
                        return new Analysis( FenceVerdict.refused( "unsafe-link" ), Set.of() );
                    }
                    pageName( decoded ).ifPresent( links::add );
                }
            }
        }
        return new Analysis( FenceVerdict.ALLOWED, links );
    }

    private static boolean touchesSecurityConfig( final String source ) {
        final Matcher d = DIRECTIVE.matcher( source );
        while ( d.find() ) {
            if ( KEY_USE.matcher( d.group( 1 ) ).find() ) {
                return true;
            }
        }
        // An unterminated directive is still scanned: be conservative.
        final int open = lastOpenDirective( source );
        if ( open >= 0 && KEY_USE.matcher( source.substring( open ) ).find() ) {
            return true;
        }
        final String[] lines = source.split( "\\R", -1 );
        int i = 0;
        while ( i < lines.length && lines[ i ].isBlank() ) {
            i++;
        }
        if ( i < lines.length && lines[ i ].strip().equals( "---" ) ) {
            for ( int j = i + 1; j < lines.length && !lines[ j ].strip().equals( "---" ); j++ ) {
                if ( KEY_USE.matcher( lines[ j ] ).find() ) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int lastOpenDirective( final String source ) {
        final Matcher m = Pattern.compile( "%%\\{\\s*(?:init|initialize)\\s*:" ).matcher( source );
        int last = -1;
        while ( m.find() ) {
            last = m.start();
        }
        return last;
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
                    rep = Map.of( "colon", ":", "Tab", "\t", "NewLine", "\n", "sol", "/" ).get( m.group( 3 ) );
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
            if ( c > 0x20 && !( c >= 0x7f && c <= 0x9f ) && c != '​' && c != '­' ) {
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
