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
package com.wikantik.api.livevalues;

import java.text.NumberFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, I/O-free expander for {@code {{wiki:name|round=N|else=text}}} tokens.
 * Malformed tokens and tokens whose name nobody knows are returned verbatim; nothing here throws.
 */
public final class LiveValueExpander {

    /** Names known to the product; a resolver reports them {@link Resolution.Unwired} until a value exists. */
    public static final Set< String > BUILT_IN_NAMES = Set.of( "articles", "pages", "clusters", "subclusters",
            "tools.knowledge", "tools.admin", "tools.openapi", "version" );

    /** Prefix of per-cluster names ({@code cluster.<path>.<metric>}). */
    public static final String CLUSTER_PREFIX = "cluster.";

    /** What a resolver says about a name. */
    public sealed interface Resolution permits Resolution.Value, Resolution.Text, Resolution.Unwired, Resolution.Unknown {
        /** A numeric fact. */
        record Value( long number ) implements Resolution { }
        /** A textual fact (e.g. version); {@code round} is ignored. */
        record Text( String text ) implements Resolution { }
        /** A known name with no value yet. */
        record Unwired() implements Resolution { }
        /** A name nobody knows. */
        record Unknown() implements Resolution { }
    }

    private static final Pattern TOKEN =
            Pattern.compile( "\\{\\{wiki:([a-z][a-z0-9./-]*)((?:\\|[a-z]+=[^|}]*)*)\\}\\}" );
    private static final Pattern FENCE = Pattern.compile( "^\\s*(`{3,}|~{3,})(.*)$" );
    private static final Pattern PREFIX =
            Pattern.compile( "^(?:\\s*>)*(?:\\s*(?:[-*+]|\\d+[.)])(?=\\s))*" );

    private LiveValueExpander() { }

    /** Expands every well-formed, resolvable token in {@code text}. */
    public static String expandText( final String text, final Function< String, Resolution > resolve ) {
        if( text == null || text.indexOf( "{{wiki:" ) < 0 ) {
            return text;
        }
        final Matcher m = TOKEN.matcher( text );
        final StringBuilder sb = new StringBuilder();
        while( m.find() ) {
            final String replacement = expandToken( m.group(), m.group( 1 ), m.group( 2 ), resolve );
            m.appendReplacement( sb, Matcher.quoteReplacement( replacement ) );
        }
        m.appendTail( sb );
        return sb.toString();
    }

    private static String expandToken( final String raw, final String name, final String args,
                                       final Function< String, Resolution > resolve ) {
        long round = 0;
        String otherwise = null;
        if( !args.isEmpty() ) {
            for( final String arg : args.substring( 1 ).split( "\\|", -1 ) ) {
                final int eq = arg.indexOf( '=' );
                final String key = arg.substring( 0, eq );
                final String val = arg.substring( eq + 1 );
                if( "round".equals( key ) && round == 0 ) {
                    round = parsePositive( val );
                    if( round <= 0 ) {
                        return raw;
                    }
                } else if( "else".equals( key ) && otherwise == null ) {
                    otherwise = val;
                } else {
                    return raw;
                }
            }
        }
        final Resolution r = resolve.apply( name );
        if( r instanceof Resolution.Value v ) {
            return format( v.number(), round );
        } else if( r instanceof Resolution.Text t ) {
            return t.text();
        } else if( r instanceof Resolution.Unwired ) {
            return otherwise == null ? "" : otherwise;
        }
        return raw;
    }

    private static long parsePositive( final String s ) {
        if( s.isEmpty() || s.length() > 18 || !s.chars().allMatch( c -> c >= '0' && c <= '9' ) ) {
            return -1;
        }
        return Long.parseLong( s );
    }

    private static String format( final long value, final long round ) {
        final NumberFormat nf = NumberFormat.getIntegerInstance( Locale.US );
        if( round <= 0 ) {
            return nf.format( value );
        }
        final long floor = Math.floorDiv( value, round ) * round;
        return nf.format( floor ) + ( value > floor ? "+" : "" );
    }

    /** Names of all well-formed tokens in {@code text}, code included (for validation). */
    public static Set< String > tokenNames( final String text ) {
        final Set< String > names = new LinkedHashSet<>();
        if( text != null ) {
            final Matcher m = TOKEN.matcher( text );
            while( m.find() ) {
                names.add( m.group( 1 ) );
            }
        }
        return names;
    }

    /** True when {@code text} holds at least one well-formed token. */
    public static boolean hasToken( final String text ) {
        return text != null && TOKEN.matcher( text ).find();
    }

    /** Expands tokens in Markdown, skipping fenced code, indented code and inline code spans. */
    public static String expandBody( final String markdown, final Function< String, Resolution > resolve ) {
        if( markdown == null || markdown.indexOf( "{{wiki:" ) < 0 ) {
            return markdown;
        }
        final String[] lines = markdown.split( "\n", -1 );
        final StringBuilder out = new StringBuilder( markdown.length() );
        char fenceChar = 0;
        int fenceLen = 0;
        boolean prevBlank = true;
        boolean prevIndentedCode = false;
        boolean listContext = false;
        for( int i = 0; i < lines.length; i++ ) {
            final String line = lines[ i ];
            final String content = line.endsWith( "\r" ) ? line.substring( 0, line.length() - 1 ) : line;
            final boolean blank = content.isBlank();
            String result;
            if( fenceChar != 0 ) {
                result = line;
                final Matcher pm = PREFIX.matcher( content );
                final String rest = pm.find() ? content.substring( pm.end() ) : content;
                final Matcher fm = FENCE.matcher( rest );
                if( fm.matches() && fm.group( 1 ).charAt( 0 ) == fenceChar && fm.group( 1 ).length() >= fenceLen
                        && fm.group( 2 ).isBlank() ) {
                    fenceChar = 0;
                }
                prevIndentedCode = false;
            } else {
                final Matcher pm = PREFIX.matcher( content );
                final int prefixEnd = pm.find() ? pm.end() : 0;
                final Matcher fm = FENCE.matcher( content.substring( prefixEnd ) );
                final boolean listItem = prefixEnd > 0 && content.substring( 0, prefixEnd ).matches( ".*(?:[-*+]|\\d+[.)])" );
                if( fm.matches() && !( fm.group( 1 ).charAt( 0 ) == '`' && fm.group( 2 ).indexOf( '`' ) >= 0 ) ) {
                    fenceChar = fm.group( 1 ).charAt( 0 );
                    fenceLen = fm.group( 1 ).length();
                    result = line;
                    prevIndentedCode = false;
                } else if( !blank && ( content.startsWith( "    " ) || content.startsWith( "\t" ) )
                        && !listContext && ( prevBlank || prevIndentedCode ) ) {
                    result = line;
                    prevIndentedCode = true;
                } else {
                    result = expandOutsideInlineCode( line, resolve );
                    prevIndentedCode = false;
                }
                if( listItem ) {
                    listContext = true;
                } else if( !blank && !Character.isWhitespace( content.charAt( 0 ) ) ) {
                    listContext = false;
                }
            }
            prevBlank = blank;
            out.append( result );
            if( i < lines.length - 1 ) {
                out.append( '\n' );
            }
        }
        return out.toString();
    }

    /** Expands a single line, skipping inline code spans (matching backtick runs of equal length). */
    private static String expandOutsideInlineCode( final String line, final Function< String, Resolution > resolve ) {
        if( line.indexOf( '`' ) < 0 ) {
            return expandText( line, resolve );
        }
        final StringBuilder out = new StringBuilder();
        int segStart = 0;
        int i = 0;
        final int n = line.length();
        while( i < n ) {
            if( line.charAt( i ) != '`' ) {
                i++;
                continue;
            }
            int j = i;
            while( j < n && line.charAt( j ) == '`' ) {
                j++;
            }
            final int runLen = j - i;
            int k = j;
            int close = -1;
            while( k < n ) {
                if( line.charAt( k ) == '`' ) {
                    int e = k;
                    while( e < n && line.charAt( e ) == '`' ) {
                        e++;
                    }
                    if( e - k == runLen ) {
                        close = e;
                        break;
                    }
                    k = e;
                } else {
                    k++;
                }
            }
            if( close < 0 ) {
                i = j;
            } else {
                out.append( expandText( line.substring( segStart, i ), resolve ) );
                out.append( line, i, close );
                segStart = close;
                i = close;
            }
        }
        out.append( expandText( line.substring( segStart ), resolve ) );
        return out.toString();
    }
}
