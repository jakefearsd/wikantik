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

/**
 * Raw-text scanner for CommonMark fenced code blocks, with no Flexmark involved: it exists so reference
 * collection ({@code DefaultReferenceManager.scanWikiLinks}, deliberately regex-fast) can read diagram link
 * targets without a full parse. It must agree with Flexmark on the {@code documents} cases of the shared
 * {@code renderedFences.json} fixture.
 *
 * <p>Handles backtick and tilde fences, closers of at least the opener's length, unclosed fences running to EOF,
 * CRLF, and the two container kinds that matter in practice: blockquote {@code >} markers and list-item content
 * indentation. Runs in a single pass, linear in the body length.
 */
public final class RenderedFenceScanner {

    private static final int MAX_FENCE_INDENT = 3;
    private static final int MAX_LIST_SPACES = 4;

    private RenderedFenceScanner() {
    }

    /** Every fenced block, any info string, in document order. */
    public static List< ScannedFence > scan( final String body ) {
        final List< ScannedFence > out = new ArrayList<>();
        if ( body == null || ( body.indexOf( "```" ) < 0 && body.indexOf( "~~~" ) < 0 ) ) {
            return out;
        }
        final List< String > lines = splitLines( body );
        final IntStack listStack = new IntStack();
        int lastBq = 0;

        // open-fence state
        boolean inFence = false;
        char fenceChar = 0;
        int fenceLen = 0;
        int bqDepth = 0;
        int container = 0;     // total list-content columns to strip after the blockquote markers
        int openIndent = 0;    // opener indent relative to the container
        FenceInfo info = null;
        StringBuilder content = null;

        for ( int i = 0; i < lines.size(); i++ ) {
            final String line = lines.get( i );
            if ( inFence ) {
                int pos = 0;
                boolean ok = true;
                for ( int d = 0; d < bqDepth && ok; d++ ) {
                    final int p = skipBlockquoteMarker( line, pos );
                    if ( p < 0 ) {
                        ok = false;
                    } else {
                        pos = p;
                    }
                }
                if ( ok ) {
                    final int stripped = countSpaces( line, pos, container );
                    if ( stripped < container && !isBlank( line, pos ) ) {
                        ok = false;     // dedent out of the list item: the container (and the fence) ends
                    } else {
                        pos += stripped;
                    }
                }
                if ( ok ) {
                    final int ind = countSpaces( line, pos, Integer.MAX_VALUE );
                    if ( ind <= MAX_FENCE_INDENT && isCloser( line, pos + ind, fenceChar, fenceLen ) ) {
                        out.add( new ScannedFence( info, content.toString() ) );
                        inFence = false;
                        continue;
                    }
                    pos += countSpaces( line, pos, openIndent );
                    content.append( line, pos, line.length() ).append( '\n' );
                    continue;
                }
                // container ended without a closer: emit and re-process this line as ordinary text
                out.add( new ScannedFence( info, content.toString() ) );
                inFence = false;
            }

            // ordinary line: strip blockquote markers
            int pos = 0;
            int bq = 0;
            for ( int p = skipBlockquoteMarker( line, pos ); p >= 0; p = skipBlockquoteMarker( line, pos ) ) {
                pos = p;
                bq++;
            }
            if ( bq != lastBq ) {
                listStack.clear();
                lastBq = bq;
            }
            final boolean blank = isBlank( line, pos );
            int ind = countSpaces( line, pos, Integer.MAX_VALUE );
            if ( !blank ) {
                while ( !listStack.isEmpty() && ind < listStack.peek() ) {
                    listStack.pop();
                }
            }
            int containerCols = listStack.isEmpty() ? 0 : listStack.peek();
            pos += Math.min( ind, containerCols );
            // list markers (possibly nested on one line)
            while ( true ) {
                final int rel = countSpaces( line, pos, Integer.MAX_VALUE );
                if ( rel > MAX_FENCE_INDENT ) {
                    break;
                }
                final int m = listMarkerEnd( line, pos + rel );
                if ( m < 0 ) {
                    break;
                }
                int sp = countSpaces( line, m, Integer.MAX_VALUE );
                final boolean emptyRest = isBlank( line, m );
                if ( emptyRest || sp > MAX_LIST_SPACES ) {
                    sp = 1;
                }
                final int width = rel + ( m - ( pos + rel ) ) + sp;
                containerCols += width;
                listStack.push( containerCols );
                pos += width;
                if ( emptyRest ) {
                    break;
                }
            }
            final int rel = countSpaces( line, pos, Integer.MAX_VALUE );
            if ( rel > MAX_FENCE_INDENT || pos + rel >= line.length() ) {
                continue;
            }
            final char c = line.charAt( pos + rel );
            if ( c != '`' && c != '~' ) {
                continue;
            }
            int end = pos + rel;
            while ( end < line.length() && line.charAt( end ) == c ) {
                end++;
            }
            final int run = end - ( pos + rel );
            if ( run < 3 ) {
                continue;
            }
            final String infoStr = line.substring( end ).trim();
            if ( c == '`' && infoStr.indexOf( '`' ) >= 0 ) {
                continue;
            }
            inFence = true;
            fenceChar = c;
            fenceLen = run;
            bqDepth = bq;
            container = containerCols;
            openIndent = rel;
            info = FenceInfo.parse( infoStr );
            content = new StringBuilder();
        }
        if ( inFence ) {
            out.add( new ScannedFence( info, content.toString() ) );
        }
        return out;
    }

    /**
     * Local page names linked from rendered (registered, non-{@code example}) fences. Empty when the body has no
     * fence markers at all, which is the overwhelmingly common case.
     */
    public static Set< String > pageLinks( final String body, final RenderedFenceRegistry registry ) {
        final Set< String > links = new LinkedHashSet<>();
        for ( final ScannedFence f : scan( body ) ) {
            registry.forInfo( f.info() ).ifPresent( r -> links.addAll( r.pageLinks( f.source() ) ) );
        }
        return links;
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private static List< String > splitLines( final String body ) {
        final List< String > lines = new ArrayList<>();
        final int n = body.length();
        int start = 0;
        for ( int i = 0; i < n; i++ ) {
            final char ch = body.charAt( i );
            if ( ch == '\n' || ch == '\r' ) {
                lines.add( body.substring( start, i ) );
                if ( ch == '\r' && i + 1 < n && body.charAt( i + 1 ) == '\n' ) {
                    i++;
                }
                start = i + 1;
            }
        }
        if ( start < n ) {
            lines.add( body.substring( start ) );
        }
        return lines;
    }

    /** Position after a blockquote marker ({@code >} with up to 3 spaces before, one optional space after), or -1. */
    private static int skipBlockquoteMarker( final String line, final int from ) {
        final int ind = countSpaces( line, from, Integer.MAX_VALUE );
        final int p = from + ind;
        if ( ind > MAX_FENCE_INDENT || p >= line.length() || line.charAt( p ) != '>' ) {
            return -1;
        }
        return p + 1 < line.length() && line.charAt( p + 1 ) == ' ' ? p + 2 : p + 1;
    }

    /** End of a list marker at {@code p} (bullet, or 1-9 digits plus {@code .}/{@code )}) followed by space/EOL; else -1. */
    private static int listMarkerEnd( final String line, final int p ) {
        final int n = line.length();
        if ( p >= n ) {
            return -1;
        }
        int e = p;
        final char c = line.charAt( p );
        if ( c == '-' || c == '*' || c == '+' ) {
            e = p + 1;
        } else if ( c >= '0' && c <= '9' ) {
            while ( e < n && e - p < 10 && line.charAt( e ) >= '0' && line.charAt( e ) <= '9' ) {
                e++;
            }
            if ( e - p > 9 || e >= n || ( line.charAt( e ) != '.' && line.charAt( e ) != ')' ) ) {
                return -1;
            }
            e++;
        } else {
            return -1;
        }
        return e >= n || line.charAt( e ) == ' ' ? e : -1;
    }

    private static boolean isCloser( final String line, final int p, final char ch, final int minLen ) {
        int e = p;
        while ( e < line.length() && line.charAt( e ) == ch ) {
            e++;
        }
        return e - p >= minLen && isBlank( line, e );
    }

    private static int countSpaces( final String line, final int from, final int max ) {
        int p = from;
        while ( p < line.length() && p - from < max && line.charAt( p ) == ' ' ) {
            p++;
        }
        return p - from;
    }

    private static boolean isBlank( final String line, final int from ) {
        for ( int p = from; p < line.length(); p++ ) {
            final char c = line.charAt( p );
            if ( c != ' ' && c != '\t' ) {
                return false;
            }
        }
        return true;
    }

    /** Minimal int stack (avoids boxing on the hot path). */
    private static final class IntStack {
        private int[] a = new int[ 8 ];
        private int n;

        boolean isEmpty() {
            return n == 0;
        }

        int peek() {
            return a[ n - 1 ];
        }

        void pop() {
            n--;
        }

        void clear() {
            n = 0;
        }

        void push( final int v ) {
            if ( n == a.length ) {
                a = java.util.Arrays.copyOf( a, n * 2 );
            }
            a[ n++ ] = v;
        }
    }
}
