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
 * CodeMirror's {@code composeSets} (with {@code mkSet = true}): set A followed by set B as one set. Each branch of the
 * library's loop is one method here, in the same order; the conditions are the library's, unchanged.
 */
final class ComposeSets {

    private final SectionIter a;
    private final SectionIter b;
    private final SectionBuilder out = new SectionBuilder();
    /** Whether the last section written is still open, so the next piece of the same change joins it. */
    private boolean open;

    ComposeSets( final ChangeSet setA, final ChangeSet setB ) {
        this.a = new SectionIter( setA );
        this.b = new SectionIter( setB );
    }

    ChangeSet run() {
        while ( true ) {
            if ( a.done() && b.done() ) {
                return out.build();
            } else if ( a.ins == 0 ) { // Deletion in A
                out.addSection( a.len, 0, open );
                a.next();
            } else if ( b.len == 0 && !b.done() ) { // Insertion in B
                out.addSection( 0, b.ins, open );
                out.addInsert( b.text() );
                b.next();
            } else if ( a.done() || b.done() ) {
                throw new IllegalArgumentException( "Mismatched change set lengths" );
            } else {
                overlap();
            }
        }
    }

    /** A's output and B's input overlap for {@code min(a.len2, b.len)}: combine that piece. */
    private void overlap() {
        final int len = Math.min( a.len2(), b.len );
        final int sectionLen = out.size();
        if ( a.ins == KEPT ) {
            keptByA( len );
        } else if ( b.ins == KEPT ) {
            keptByB( len );
        } else {
            changedByBoth();
        }
        open = ( a.ins > len || b.ins >= 0 && b.len > len ) && ( open || out.size() > sectionLen );
        a.forward2( len );
        b.forward( len );
    }

    /** A kept the span: B's section passes through (its text only on B's first piece). */
    private void keptByA( final int len ) {
        final int insB = b.ins == KEPT ? KEPT : b.off != 0 ? 0 : b.ins;
        out.addSection( len, insB, open );
        if ( insB != 0 ) { // as in CodeMirror, KEPT is truthy here; a kept section's text is empty
            out.addInsert( b.text() );
        }
    }

    /** B kept A's inserted text: A's change passes through, with the piece of its text B kept. */
    private void keptByB( final int len ) {
        out.addSection( a.off != 0 ? 0 : a.len, len, open );
        out.addInsert( a.textBit( len ) );
    }

    /** B replaced text A inserted: A's deletion (on its first piece) with B's insertion (on B's first piece). */
    private void changedByBoth() {
        out.addSection( a.off != 0 ? 0 : a.len, b.off != 0 ? 0 : b.ins, open );
        if ( b.off == 0 ) {
            out.addInsert( b.text() );
        }
    }
}
