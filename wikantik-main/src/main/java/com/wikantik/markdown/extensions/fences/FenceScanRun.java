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
import java.util.List;

import static com.wikantik.markdown.extensions.fences.FenceLines.MAX_FENCE_INDENT;
import static com.wikantik.markdown.extensions.fences.FenceLines.countSpaces;
import static com.wikantik.markdown.extensions.fences.FenceLines.isBlank;
import static com.wikantik.markdown.extensions.fences.FenceLines.isCloser;
import static com.wikantik.markdown.extensions.fences.FenceLines.listMarkerEnd;
import static com.wikantik.markdown.extensions.fences.FenceLines.skipBlockquoteMarker;
import static com.wikantik.markdown.extensions.fences.HtmlBlockRules.htmlEnds;
import static com.wikantik.markdown.extensions.fences.HtmlBlockRules.htmlStartType;

/**
 * One single-pass run of {@link RenderedFenceScanner#scan}: holds the open-fence, list-container and HTML-block
 * state and consumes the document one line at a time.
 */
final class FenceScanRun {

    private static final int MAX_LIST_SPACES = 4;

    private final List< String > lines;
    private final List< ScannedFence > out = new ArrayList<>();
    private final FenceLines.IntStack listStack = new FenceLines.IntStack();
    private int lastBq;
    /** Total list-content columns of the line being processed (the top of the list stack, plus new markers). */
    private int containerCols;

    // open-fence state
    private boolean inFence;
    private char fenceChar;
    private int fenceLen;
    private int bqDepth;
    private int container;     // total list-content columns to strip after the blockquote markers
    private int openIndent;    // opener indent relative to the container
    private FenceInfo info;
    private int openLine;
    /** 1-based number of the line being processed. */
    private int lineNo;
    private StringBuilder content;

    // open HTML block: 0 none; 1-5 end on a marker; 6-7 end on a blank line
    private int htmlType;
    private boolean prevParagraph;

    FenceScanRun( final List< String > lines ) {
        this.lines = lines;
    }

    List< ScannedFence > run() {
        for ( final String line : lines ) {
            lineNo++;
            if ( inFence && continueFence( line ) ) {
                continue;
            }
            processOrdinaryLine( line );
        }
        if ( inFence ) {
            emitFence();
        }
        return out;
    }

    private void emitFence() {
        out.add( new ScannedFence( info, content.toString(), openLine ) );
        inFence = false;
    }

    /** Handles a line inside an open fence; false when the container ended and the line must be re-processed. */
    private boolean continueFence( final String line ) {
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
            consumeFenceLine( line, pos );
            return true;
        }
        // container ended without a closer: emit and re-process this line as ordinary text
        emitFence();
        return false;
    }

    private void consumeFenceLine( final String line, final int from ) {
        int pos = from;
        final int ind = countSpaces( line, pos, Integer.MAX_VALUE );
        if ( ind <= MAX_FENCE_INDENT && isCloser( line, pos + ind, fenceChar, fenceLen ) ) {
            emitFence();
            return;
        }
        pos += countSpaces( line, pos, openIndent );
        content.append( line, pos, line.length() ).append( '\n' );
    }

    private void processOrdinaryLine( final String line ) {
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
        pos = enterListContainers( line, pos, blank );
        final int rel = countSpaces( line, pos, Integer.MAX_VALUE );
        if ( htmlType != 0 ) {
            continueHtmlBlock( line, pos );
            return;
        }
        if ( rel > MAX_FENCE_INDENT || pos + rel >= line.length() ) {
            prevParagraph = !blank && rel <= MAX_FENCE_INDENT;
            return;
        }
        final char c = line.charAt( pos + rel );
        if ( c == '<' && startHtmlBlock( line, pos + rel ) ) {
            return;
        }
        if ( c != '`' && c != '~' ) {
            prevParagraph = c != '#';
            return;
        }
        tryOpenFence( line, pos, rel, c, bq );
    }

    /** Pops finished list items, strips the container indent and consumes any (nested) list markers; returns the new position. */
    private int enterListContainers( final String line, final int from, final boolean blank ) {
        int pos = from;
        final int ind = countSpaces( line, pos, Integer.MAX_VALUE );
        if ( !blank ) {
            while ( !listStack.isEmpty() && ind < listStack.peek() ) {
                listStack.pop();
            }
        }
        containerCols = listStack.isEmpty() ? 0 : listStack.peek();
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
        return pos;
    }

    /** Inside an HTML block nothing else is recognised: a fence here is raw HTML, not a code block. */
    private void continueHtmlBlock( final String line, final int pos ) {
        if ( htmlType >= 6 ? isBlank( line, pos ) : htmlEnds( htmlType, line, pos ) ) {
            htmlType = 0;
            prevParagraph = false;
        }
    }

    /** Starts an HTML block at {@code at} if the line opens one; true when the line was consumed as HTML. */
    private boolean startHtmlBlock( final String line, final int at ) {
        final int t = htmlStartType( line, at, prevParagraph );
        if ( t == 0 ) {
            return false;
        }
        htmlType = t;
        if ( t < 6 && htmlEnds( t, line, at ) ) {
            htmlType = 0;
        }
        prevParagraph = false;
        return true;
    }

    private void tryOpenFence( final String line, final int pos, final int rel, final char c, final int bq ) {
        int end = pos + rel;
        while ( end < line.length() && line.charAt( end ) == c ) {
            end++;
        }
        final int run = end - ( pos + rel );
        if ( run < 3 ) {
            prevParagraph = true;
            return;
        }
        final String infoStr = line.substring( end ).trim();
        if ( c == '`' && infoStr.indexOf( '`' ) >= 0 ) {
            prevParagraph = true;
            return;
        }
        prevParagraph = false;
        inFence = true;
        fenceChar = c;
        fenceLen = run;
        bqDepth = bq;
        container = containerCols;
        openIndent = rel;
        info = FenceInfo.parse( infoStr );
        openLine = lineNo;
        content = new StringBuilder();
    }
}
