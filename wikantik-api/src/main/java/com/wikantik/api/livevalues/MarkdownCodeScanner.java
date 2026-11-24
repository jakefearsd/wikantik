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

import com.wikantik.api.livevalues.LiveValueExpander.Resolution;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Line-based, code-aware Markdown scan that expands tokens only outside fenced code, indented code
 * and inline code spans. Inline code spans are matched per line only. Not thread-safe; one per call.
 */
final class MarkdownCodeScanner {

    private static final Pattern FENCE = Pattern.compile( "^\\s*(`{3,}|~{3,})(.*)$" );
    private static final Pattern PREFIX =
            Pattern.compile( "^(?:\\s*>)*(?:\\s*(?:[-*+]|\\d+[.)])(?=\\s))*" );
    private static final Pattern ENDS_WITH_LIST_MARKER = Pattern.compile( ".*(?:[-*+]|\\d+[.)])" );

    private final Function< String, Resolution > resolve;
    private char fenceChar;
    private int fenceLen;
    private boolean prevBlank = true;
    private boolean prevIndentedCode;
    private boolean listContext;

    MarkdownCodeScanner( final Function< String, Resolution > resolve ) {
        this.resolve = resolve;
    }

    String expand( final String markdown ) {
        final String[] lines = markdown.split( "\n", -1 );
        final StringBuilder out = new StringBuilder( markdown.length() );
        for( int i = 0; i < lines.length; i++ ) {
            out.append( processLine( lines[ i ] ) );
            if( i < lines.length - 1 ) {
                out.append( '\n' );
            }
        }
        return out.toString();
    }

    private String processLine( final String line ) {
        final String content = line.endsWith( "\r" ) ? line.substring( 0, line.length() - 1 ) : line;
        final boolean blank = content.isBlank();
        final String result = fenceChar != 0 ? insideFence( line, content ) : outsideFence( line, content, blank );
        prevBlank = blank;
        return result;
    }

    private static int prefixEnd( final String content ) {
        final Matcher pm = PREFIX.matcher( content );
        return pm.find() ? pm.end() : 0;
    }

    private String insideFence( final String line, final String content ) {
        final Matcher fm = FENCE.matcher( content.substring( prefixEnd( content ) ) );
        if( fm.matches() && closesFence( fm.group( 1 ), fm.group( 2 ) ) ) {
            fenceChar = 0;
        }
        prevIndentedCode = false;
        return line;
    }

    private boolean closesFence( final String marker, final String info ) {
        return marker.charAt( 0 ) == fenceChar && marker.length() >= fenceLen && info.isBlank();
    }

    private String outsideFence( final String line, final String content, final boolean blank ) {
        final int prefixEnd = prefixEnd( content );
        final Matcher fm = FENCE.matcher( content.substring( prefixEnd ) );
        final String result;
        if( fm.matches() && opensFence( fm.group( 1 ), fm.group( 2 ) ) ) {
            fenceChar = fm.group( 1 ).charAt( 0 );
            fenceLen = fm.group( 1 ).length();
            result = line;
            prevIndentedCode = false;
        } else if( isIndentedCode( content, blank ) ) {
            result = line;
            prevIndentedCode = true;
        } else {
            result = expandOutsideInlineCode( line );
            prevIndentedCode = false;
        }
        updateListContext( content, prefixEnd, blank );
        return result;
    }

    private static boolean opensFence( final String marker, final String info ) {
        return marker.charAt( 0 ) != '`' || info.indexOf( '`' ) < 0;
    }

    private boolean isIndentedCode( final String content, final boolean blank ) {
        final boolean indented = content.startsWith( "    " ) || content.startsWith( "\t" );
        return !blank && indented && !listContext && ( prevBlank || prevIndentedCode );
    }

    private void updateListContext( final String content, final int prefixEnd, final boolean blank ) {
        if( prefixEnd > 0 && ENDS_WITH_LIST_MARKER.matcher( content.substring( 0, prefixEnd ) ).matches() ) {
            listContext = true;
        } else if( !blank && !Character.isWhitespace( content.charAt( 0 ) ) ) {
            listContext = false;
        }
    }

    /** Expands one line, skipping inline code spans (matching backtick runs of equal length). */
    private String expandOutsideInlineCode( final String line ) {
        if( line.indexOf( '`' ) < 0 ) {
            return LiveValueExpander.expandText( line, resolve );
        }
        final StringBuilder out = new StringBuilder();
        int segStart = 0;
        int i = 0;
        while( i < line.length() ) {
            if( line.charAt( i ) != '`' ) {
                i++;
                continue;
            }
            final int runEnd = runEnd( line, i );
            final int close = findClosingRun( line, runEnd, runEnd - i );
            if( close < 0 ) {
                i = runEnd;
            } else {
                out.append( LiveValueExpander.expandText( line.substring( segStart, i ), resolve ) );
                out.append( line, i, close );
                segStart = close;
                i = close;
            }
        }
        out.append( LiveValueExpander.expandText( line.substring( segStart ), resolve ) );
        return out.toString();
    }

    /** Index just past the backtick run starting at {@code from}. */
    private static int runEnd( final String line, final int from ) {
        int e = from;
        while( e < line.length() && line.charAt( e ) == '`' ) {
            e++;
        }
        return e;
    }

    /** End index of the next backtick run of exactly {@code runLen}, or -1. */
    private static int findClosingRun( final String line, final int from, final int runLen ) {
        int k = from;
        while( k < line.length() ) {
            if( line.charAt( k ) != '`' ) {
                k++;
                continue;
            }
            final int e = runEnd( line, k );
            if( e - k == runLen ) {
                return e;
            }
            k = e;
        }
        return -1;
    }
}
