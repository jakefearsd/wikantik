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
            "maxTextSize", "maxEdges", "suppressErrorRendering", "dompurifyConfig", "htmlLabels" );
    /** Lower-cased: keys are matched as bare case-insensitive substrings (fail closed; over-refusal is acceptable). */
    private static final List< String > UNSAFE_KEYS_LC = UNSAFE_KEYS.stream().map( k -> k.toLowerCase( Locale.ROOT ) ).toList();

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern DIRECTIVE_START = Pattern.compile( "%%\\{\\s*(?:init|initialize)\\b", FLAGS );
    private static final String DIRECTIVE_END = "}%%";
    private static final Pattern CLICK_LINE = Pattern.compile( "^click(?:\\s.*)?$", FLAGS );
    private static final Pattern CLICK_OK = Pattern.compile(
            "^(?:click|link)\\s+\\S+\\s+(?:href\\s+)?\"([^\"]*)\"(?:\\s+\"[^\"]*\")?(?:\\s+(?:_self|_blank|_parent|_top))?$", FLAGS );
    private static final Pattern CLASS_LINK = Pattern.compile( "^link\\s+\\S+\\s+(?:href\\s+)?\".*$", FLAGS );
    private static final Pattern CALLBACK_LINE = Pattern.compile( "^callback\\s+[^\\s\\-=.<>~].*$", FLAGS );
    private static final Pattern LINK_LINE = Pattern.compile( "^link\\s+[^:@]+:[^@]*@\\s*(\\S.*)$", FLAGS );
    private static final Pattern LINKS_LINE = Pattern.compile( "^links\\s+[^:]+:\\s*(\\{.*)$", FLAGS );
    private static final Pattern JSON_VALUE = Pattern.compile( "\"[^\"]*\"\\s*:\\s*\"([^\"]*)\"" );
    private static final Pattern ENTITY = Pattern.compile( "&#[xX]([0-9a-fA-F]+);?|&#([0-9]+);?|&(colon|Tab|NewLine|sol);|#([0-9]+);" );
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
                final String s = normaliseWhitespace( statement ).strip();
                final List< String > urls = new ArrayList<>();
                if ( CLICK_LINE.matcher( s ).matches() ) {
                    final Matcher m = CLICK_OK.matcher( s );
                    if ( !m.matches() ) {
                        return new Analysis( FenceVerdict.refused( "callback" ), Set.of() );
                    }
                    urls.add( m.group( 1 ) );
                } else if ( CALLBACK_LINE.matcher( s ).matches() ) {
                    return new Analysis( FenceVerdict.refused( "callback" ), Set.of() );
                } else if ( CLASS_LINK.matcher( s ).matches() ) {
                    final Matcher m = CLICK_OK.matcher( s );
                    if ( !m.matches() ) {
                        return new Analysis( FenceVerdict.refused( "unsafe-link" ), Set.of() );
                    }
                    urls.add( m.group( 1 ) );
                } else {
                    final Matcher link = LINK_LINE.matcher( s );
                    final Matcher links2 = LINKS_LINE.matcher( s );
                    if ( link.matches() ) {
                        urls.add( link.group( 1 ) );
                    } else if ( links2.matches() ) {
                        if ( links2.group( 1 ).indexOf( '\\' ) >= 0 ) {
                            return new Analysis( FenceVerdict.refused( "unsafe-link" ), Set.of() );
                        }
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
        final Matcher d = DIRECTIVE_START.matcher( source );
        int pos = 0;
        while ( pos <= source.length() && d.find( pos ) ) {
            final int end = source.indexOf( DIRECTIVE_END, d.start() );
            final int bodyEnd = end < 0 ? source.length() : end;   // unterminated: scan to the end
            if ( suspicious( source.substring( d.start(), bodyEnd ) ) ) {
                return true;
            }
            if ( end < 0 ) {
                break;
            }
            pos = bodyEnd;
        }
        final String[] lines = source.split( "\\R", -1 );
        int i = 0;
        while ( i < lines.length && lines[ i ].isBlank() ) {
            i++;
        }
        if ( i < lines.length && lines[ i ].strip().equals( "---" ) ) {
            final String l0 = lines[ i ];
            final String closer = l0.substring( 0, l0.length() - l0.stripLeading().length() ) + "---";
            final StringBuilder fm = new StringBuilder();
            for ( int j = i + 1; j < lines.length && !lines[ j ].startsWith( closer ); j++ ) {
                fm.append( lines[ j ] ).append( '\n' );
            }
            return suspicious( fm.toString() );
        }
        return false;
    }

    /** Fail closed: any backslash (JSON/YAML escapes can spell a key) or any unsafe key name as a bare word. */
    private static boolean suspicious( final String config ) {
        if ( config.indexOf( '\\' ) >= 0 ) {
            return true;
        }
        final String lc = config.toLowerCase( Locale.ROOT );
        for ( final String k : UNSAFE_KEYS_LC ) {
            if ( lc.contains( k ) ) {
                return true;
            }
        }
        return false;
    }

    /** Maps Unicode whitespace (including NBSP and BOM, which JS {@code \s} matches) to a plain space. */
    private static String normaliseWhitespace( final String s ) {
        final StringBuilder sb = new StringBuilder( s.length() );
        for ( int i = 0; i < s.length(); i++ ) {
            final char c = s.charAt( i );
            sb.append( Character.isWhitespace( c ) || Character.isSpaceChar( c ) || c == '\uFEFF' ? ' ' : c );
        }
        return sb.toString();
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
