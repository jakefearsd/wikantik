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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Statement-level rules of the Mermaid safety policy (see {@link MermaidFenceRenderer}): runs the whole-source
 * pre-checks, then the click/callback/link/links rules over every statement, collecting local page links.
 */
final class MermaidStatementPolicy {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    /** All quantifiers are possessive so no statement rule can backtrack quadratically. */
    private static final Pattern CLICK_OK = Pattern.compile(
            "^(?:click|link)\\s++\\S++\\s++(?:href\\s++)?\"([^\"]*+)\"(?:\\s++\"[^\"]*+\")?(?:\\s++(?:_self|_blank|_parent|_top))?+$", FLAGS );
    private static final Pattern CLASS_LINK = Pattern.compile( "^link\\s++\\S++\\s++(?:href\\s++)?\".*+$", FLAGS );
    private static final Pattern CALLBACK_LINE = Pattern.compile( "^callback\\s++[^\\s\\-=.<>~].*+$", FLAGS );
    private static final Pattern JSON_VALUE = Pattern.compile( "\"[^\"]*+\"\\s*+:\\s*+\"([^\"]*+)\"" );

    /** Outcome of analysing a source: the verdict and (when allowed) its local page links. */
    record Analysis( FenceVerdict verdict, Set< String > pageLinks ) { }

    private MermaidStatementPolicy() {
    }

    static Analysis analyse( final String rawSource ) {
        final Set< String > links = new LinkedHashSet<>();
        if ( rawSource == null || rawSource.isEmpty() ) {
            return new Analysis( FenceVerdict.ALLOWED, links );
        }
        final String source = MermaidTextRules.normalise( rawSource );
        final FenceVerdict early = checkWholeSource( rawSource, source );
        if ( early != null ) {
            return new Analysis( early, links );
        }
        for ( final String line : source.split( "\\n" ) ) {
            final FenceVerdict v = checkLine( line, links );
            if ( v != null ) {
                return new Analysis( v, Set.of() );
            }
        }
        return new Analysis( FenceVerdict.ALLOWED, links );
    }

    /** Whole-source refusals (config directive, front matter, script scheme), or {@code null} when none apply. */
    private static FenceVerdict checkWholeSource( final String rawSource, final String source ) {
        if ( source.contains( "%%{" ) || MermaidTextRules.startsFrontMatter( rawSource )
                || MermaidTextRules.startsFrontMatter( source ) ) {
            return FenceVerdict.refused( "unsafe-config" );
        }
        if ( MermaidTextRules.hasScriptScheme( source ) ) {
            return FenceVerdict.refused( "unsafe-link" );
        }
        return null;
    }

    private static FenceVerdict checkLine( final String line, final Set< String > links ) {
        // Safety runs over the UNION of quote-aware statements and naive ';' pieces: Mermaid tokens such
        // as sequence message text end at ';' regardless of quotes, so quotes can hide a statement from
        // the quote-aware split. Page links come from quote-aware statements only.
        for ( final String statement : MermaidTextRules.splitStatements( line ) ) {
            final FenceVerdict v = checkStatement( statement.strip(), links );
            if ( v != null ) {
                return v;
            }
        }
        for ( final String piece : line.split( ";", -1 ) ) {
            final FenceVerdict v = checkStatement( piece.strip(), null );
            if ( v != null ) {
                return v;
            }
        }
        return null;
    }

    /**
     * Applies the click/callback/link/links rules to one statement; returns the refusal, or {@code null} when
     * the statement is safe. Page names of its URLs are added to {@code links} unless that is {@code null}.
     */
    static FenceVerdict checkStatement( final String s, final Set< String > links ) {
        final List< String > urls = new ArrayList<>();
        final String first = MermaidTextRules.firstWord( s );
        final FenceVerdict refusal = collectUrls( first, s, urls );
        if ( refusal != null ) {
            return refusal;
        }
        if ( ( first.equals( "click" ) || first.equals( "link" ) || first.equals( "links" ) )
                && !MermaidUrlRules.quotedStringsSafe( s ) ) {
            return FenceVerdict.refused( "unsafe-link" );
        }
        return checkUrls( urls, links );
    }

    /** Gathers the URLs a statement names into {@code urls}; returns a refusal if its shape is already unsafe. */
    private static FenceVerdict collectUrls( final String first, final String s, final List< String > urls ) {
        return switch ( first ) {
            case "click" -> collectClick( s, urls );
            case "callback" -> CALLBACK_LINE.matcher( s ).matches() ? FenceVerdict.refused( "callback" ) : null;
            case "link" -> collectLink( s, urls );
            case "links" -> collectLinks( s, urls );
            default -> null;
        };
    }

    private static FenceVerdict collectClick( final String s, final List< String > urls ) {
        final Matcher m = CLICK_OK.matcher( s );
        if ( !m.matches() ) {
            return FenceVerdict.refused( "callback" );
        }
        urls.add( m.group( 1 ) );
        return null;
    }

    private static FenceVerdict collectLink( final String s, final List< String > urls ) {
        final String rest = s.substring( "link".length() );
        final int colon = rest.indexOf( ':' );
        final int at = colon < 0 ? -1 : rest.indexOf( '@', colon + 1 );
        if ( at >= 0 ) {
            // sequence link: the URL follows the FIRST '@' after the actor's ':'
            urls.add( rest.substring( at + 1 ).strip() );
        } else if ( CLASS_LINK.matcher( s ).matches() ) {
            final Matcher m = CLICK_OK.matcher( s );
            if ( !m.matches() ) {
                return FenceVerdict.refused( "unsafe-link" );
            }
            urls.add( m.group( 1 ) );
        }
        return null;
    }

    private static FenceVerdict collectLinks( final String s, final List< String > urls ) {
        final String rest = s.substring( "links".length() );
        final int colon = rest.indexOf( ':' );
        if ( colon >= 0 && rest.substring( colon + 1 ).strip().startsWith( "{" ) ) {
            final String json = rest.substring( colon + 1 );
            if ( json.indexOf( '\\' ) >= 0 ) {
                return FenceVerdict.refused( "unsafe-link" );
            }
            final Matcher v = JSON_VALUE.matcher( json );
            while ( v.find() ) {
                urls.add( v.group( 1 ) );
            }
        }
        return null;
    }

    private static FenceVerdict checkUrls( final List< String > urls, final Set< String > links ) {
        final List< String > names = new ArrayList<>();
        for ( final String url : urls ) {
            final String decoded = MermaidTextRules.decodeEntities( url ).strip();
            if ( !MermaidUrlRules.safeUrl( decoded ) ) {
                return FenceVerdict.refused( "unsafe-link" );
            }
            MermaidUrlRules.pageName( decoded ).ifPresent( names::add );
        }
        if ( links != null ) {
            links.addAll( names );
        }
        return null;
    }
}
