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
package com.wikantik.importer;

import java.util.LinkedHashSet;
import java.util.Set;

/** Per-call mutable state shared by the body rewriting passes. */
final class RewriteCtx {
    final String fromPath;
    final VaultTargets targets;
    int blockRefs;
    int comments;
    private static final int NONE = -1;
    private String fullBody;
    private int bodyCursor;
    private int bodyLineFirst = NONE;
    private String proseRef;
    private int proseCursor;
    private int proseLineFirst = NONE;
    final Set< String > unresolved = new LinkedHashSet<>();

    RewriteCtx( final String fromPath, final VaultTargets targets ) {
        this.fromPath = fromPath;
        this.targets = targets;
    }

    /** Records an unresolved page target once per distinct name. */
    void unresolvedPage( final String t ) {
        unresolved.add( t );
    }

    /** Cuts a block reference off a heading; returns the remaining heading (possibly null) and counts it. */
    String cutBlock( final String frag ) {
        if ( frag == null ) {
            return null;
        }
        if ( frag.startsWith( "^" ) ) {
            blockRefs++;
            return null;
        }
        final int i = frag.indexOf( "#^" );
        if ( i >= 0 ) {
            blockRefs++;
            return frag.substring( 0, i );
        }
        return frag;
    }

    /**
     * Records the full body and the offset of the prose run about to be transformed. Runs arrive in increasing
     * offset order, so a forward cursor over the body tracks the current line's first non-blank character without
     * ever rescanning (a long single line with many runs stays linear).
     */
    void enterRun( final String body, final int start ) {
        if ( body != fullBody || start < bodyCursor ) {
            fullBody = body;
            bodyCursor = 0;
            bodyLineFirst = NONE;
        }
        bodyLineFirst = advance( body, bodyCursor, start, bodyLineFirst );
        bodyCursor = start;
        proseRef = null;
    }

    /**
     * True when the whole original line containing {@code pos} (an offset within the current prose run) starts with a
     * table pipe, even if the line began in an earlier segment (for example before an inline code span). Lookups for
     * one prose string arrive in increasing order, so the scan only moves forward: no substring, no lookback.
     */
    boolean inTableRow( final String prose, final int pos ) {
        if ( prose != proseRef || pos < proseCursor ) {
            proseRef = prose;
            proseCursor = 0;
            proseLineFirst = fullBody == null ? NONE : bodyLineFirst;
        }
        proseLineFirst = advance( prose, proseCursor, pos, proseLineFirst );
        proseCursor = pos;
        return proseLineFirst == '|';
    }

    /** Scans {@code s[from, to)}: a newline resets the line, otherwise the first non-blank char of the line is kept. */
    private static int advance( final String s, final int from, final int to, final int lineFirst ) {
        int first = lineFirst;
        for ( int i = from; i < to; i++ ) {
            final char c = s.charAt( i );
            if ( c == '\n' ) {
                first = NONE;
            } else if ( first == NONE && !Character.isWhitespace( c ) ) {
                first = c;
            }
        }
        return first;
    }
}
