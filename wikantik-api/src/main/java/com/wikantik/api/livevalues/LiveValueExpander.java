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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
        final TokenArgs parsed = TokenArgs.parse( args );
        if( parsed == null ) {
            return raw;
        }
        final Resolution r = resolve.apply( name );
        if( r instanceof Resolution.Value v ) {
            return format( v.number(), parsed.round );
        } else if( r instanceof Resolution.Text t ) {
            return t.text();
        } else if( r instanceof Resolution.Unwired ) {
            return parsed.otherwise == null ? "" : parsed.otherwise;
        }
        return raw;
    }

    /** Parsed {@code |key=value} arguments; {@code round} 0 means none. */
    private static final class TokenArgs {
        private long round;
        private String otherwise;

        /** Returns null when any argument is unknown, repeated or invalid. */
        static TokenArgs parse( final String args ) {
            final TokenArgs t = new TokenArgs();
            if( args.isEmpty() ) {
                return t;
            }
            for( final String arg : args.substring( 1 ).split( "\\|", -1 ) ) {
                final int eq = arg.indexOf( '=' );
                if( !t.accept( arg.substring( 0, eq ), arg.substring( eq + 1 ) ) ) {
                    return null;
                }
            }
            return t;
        }

        private boolean accept( final String key, final String val ) {
            if( "round".equals( key ) && round == 0 ) {
                round = parsePositive( val );
                return round > 0;
            }
            if( "else".equals( key ) && otherwise == null ) {
                otherwise = val;
                return true;
            }
            return false;
        }
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

    /** Every well-formed token in {@code text}, as written, in order and with repeats (code included). */
    public static List< String > tokens( final String text ) {
        final List< String > out = new java.util.ArrayList<>();
        if( text != null ) {
            final Matcher m = TOKEN.matcher( text );
            while( m.find() ) {
                out.add( m.group() );
            }
        }
        return out;
    }

    private static final Pattern WELL_FORMED_CLUSTER_NAME =
            Pattern.compile( "cluster\\.[a-z0-9]+(?:-[a-z0-9]+)*(?:/[a-z0-9]+(?:-[a-z0-9]+)*)?\\.pages" );

    /**
     * True when {@code name} is a built-in name or a well-formed {@code cluster.<path>.pages}. A well-formed
     * cluster name is "known" even when no such cluster exists yet: it may be created later.
     */
    public static boolean isKnownName( final String name ) {
        return name != null && ( BUILT_IN_NAMES.contains( name ) || WELL_FORMED_CLUSTER_NAME.matcher( name ).matches() );
    }

    /**
     * Raw text of every {@code {{wiki:} opening that would not expand: not a well-formed token, or one with an
     * unknown, repeated or invalid argument such as {@code round=0}.
     */
    public static List< String > malformedTokens( final String text ) {
        final List< String > bad = new ArrayList<>();
        if( text == null || text.indexOf( "{{wiki:" ) < 0 ) {
            return bad;
        }
        final Matcher m = TOKEN.matcher( text );
        int from = 0;
        while( m.find() ) {
            collectStrayOpenings( text, from, m.start(), bad );
            if( TokenArgs.parse( m.group( 2 ) ) == null ) {
                bad.add( m.group() );
            }
            from = m.end();
        }
        collectStrayOpenings( text, from, text.length(), bad );
        return bad;
    }

    private static void collectStrayOpenings( final String text, final int from, final int to, final List< String > bad ) {
        int i = text.indexOf( "{{wiki:", from );
        while( i >= 0 && i < to ) {
            bad.add( text.substring( i, Math.min( text.length(), i + 40 ) ) );
            i = text.indexOf( "{{wiki:", i + 1 );
        }
    }

    /** True when {@code text} holds at least one well-formed token. */
    public static boolean hasToken( final String text ) {
        return text != null && TOKEN.matcher( text ).find();
    }

    /**
     * Expands tokens in Markdown, skipping fenced code, indented code and inline code spans.
     * Inline code spans are matched per line only.
     */
    public static String expandBody( final String markdown, final Function< String, Resolution > resolve ) {
        if( markdown == null || markdown.indexOf( "{{wiki:" ) < 0 ) {
            return markdown;
        }
        return new MarkdownCodeScanner( resolve ).expand( markdown );
    }
}
