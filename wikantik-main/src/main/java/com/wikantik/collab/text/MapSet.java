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
package com.wikantik.collab.text;

import static com.wikantik.collab.text.ChangeSet.KEPT;

/**
 * CodeMirror's {@code mapSet} (with {@code mkSet = true}): a copy of set A that applies to the document after set B,
 * both starting in the same document. Each branch of the library's loop is one method here, in the same order; the
 * conditions are the library's, unchanged.
 */
final class MapSet {

    private final SectionIter a;
    private final SectionIter b;
    private final boolean before;
    private final SectionBuilder out = new SectionBuilder();
    /**
     * For changes in A processed piece by piece: the section index ({@link SectionIter#i}) whose content has been
     * inserted already.
     */
    private int inserted = -1;

    MapSet( final ChangeSet setA, final ChangeSet setB, final boolean before ) {
        this.a = new SectionIter( setA );
        this.b = new SectionIter( setB );
        this.before = before;
    }

    ChangeSet run() {
        while ( true ) {
            if ( a.done() && b.len != 0 || b.done() && a.len != 0 ) {
                throw new IllegalArgumentException( "Mismatched change set lengths" );
            } else if ( a.ins == KEPT && b.ins == KEPT ) {
                skipKeptByBoth();
            } else if ( changeInBComesFirst() ) {
                skipChangeInB();
            } else if ( a.ins >= 0 ) {
                processChangeInA();
            } else if ( a.done() && b.done() ) {
                return out.build();
            } else {
                throw new IllegalArgumentException( "Mismatched change set lengths" );
            }
        }
    }

    /** Ordered by start position, then length, then the {@code before} flag. */
    private boolean changeInBComesFirst() {
        return b.ins >= 0 && ( a.ins < 0 || inserted == a.i
                || a.off == 0 && ( b.len < a.len || b.len == a.len && !before ) );
    }

    /** Moves across a range skipped by both sets. */
    private void skipKeptByBoth() {
        final int len = Math.min( a.len, b.len );
        out.addSection( len, KEPT, false );
        a.forward( len );
        b.forward( len );
    }

    /** Skips a change in B that comes before the next change in A, processing any changes in A it covers. */
    private void skipChangeInB() {
        int len = b.len;
        out.addSection( b.ins, KEPT, false );
        while ( len != 0 ) {
            final int piece = Math.min( a.len, len );
            if ( a.ins >= 0 && inserted < a.i && a.len <= piece ) {
                out.addSection( 0, a.ins, false );
                out.addInsert( a.text() );
                inserted = a.i;
            }
            a.forward( piece );
            len -= piece;
        }
        b.next();
    }

    /** Processes the part of a change in A up to the start of the next non-deletion change in B. */
    private void processChangeInA() {
        int len = 0;
        int left = a.len;
        while ( left != 0 ) {
            if ( b.ins == KEPT ) {
                final int piece = Math.min( left, b.len );
                len += piece;
                left -= piece;
                b.forward( piece );
            } else if ( b.ins == 0 && b.len < left ) {
                left -= b.len;
                b.next();
            } else {
                break;
            }
        }
        final boolean firstPiece = inserted < a.i;
        out.addSection( len, firstPiece ? a.ins : 0, false );
        if ( firstPiece ) {
            out.addInsert( a.text() );
        }
        inserted = a.i;
        a.forward( a.len - left );
    }
}
