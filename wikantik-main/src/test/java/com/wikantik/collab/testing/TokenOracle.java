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
package com.wikantik.collab.testing;

import com.wikantik.collab.model.StoredUpdate;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;

/**
 * An independent text-duplication oracle for the fuzz (Phase 3 review, finding 2). Every text insert a lifecycle run
 * generates carries a unique token, {@code ⟦CORE⟧} with a {@value #CORE_LENGTH}-letter upper-case core: the generator's
 * own text uses no upper-case ASCII, so a core occurs only where its insert put it. Positions are snapped to token
 * boundaries ({@link #snap}), so no generated edit cuts a token; a replayed or shrunk trace may, and a token an edit cut
 * counts as deleted by that edit.
 *
 * <p>Checked after every step and at quiescence, independently of the attribution model (which mirrors the product's
 * claim rule, so a wrongly rejected claim, whose merge applies the publisher's own edits a second time, passes it):</p>
 * <ul>
 *   <li><b>at most once:</b> each token's core occurs at most once in the draft and in every stored page version;</li>
 *   <li><b>present:</b> a token leaves the draft only through a logged update that removed it: a client's update (its
 *   author's own delete, however a rebase placed it), or a server update (an external merge, a publish's rewrite) for
 *   a token a recorded delete took out of the page (an outside save whose range covered it, or the page's deletion).
 *   A server update that drops any other token loses text.</li>
 * </ul>
 */
final class TokenOracle {

    static final int CORE_LENGTH = 5;
    static final char OPEN = '⟦';
    static final char CLOSE = '⟧';

    /** Every token core seen in an applied insert. */
    private final Set< String > issued = new HashSet<>();
    /** The draft and its head as last checked (null before the first check). */
    private String lastDoc;
    private int lastHead;
    /** Cores a recorded delete removed from the page, or a client's update from the draft. */
    private final Set< String > deleted = new HashSet<>();
    /** Cores the documented residual M1 may duplicate once ({@link #residualM1}). */
    private final Set< String > allowedTwice = new HashSet<>();
    /** Stored page bodies already checked (they never change; compared by identity, so each is scanned once). */
    private final Set< String > checkedVersions = Collections.newSetFromMap( new IdentityHashMap<>() );

    /** A fresh token, unique within {@code used} (which it joins). */
    static String token( final SplittableRandom rnd, final Set< String > used ) {
        String core;
        do {
            final StringBuilder sb = new StringBuilder( CORE_LENGTH );
            for( int i = 0; i < CORE_LENGTH; i++ ) {
                sb.append( (char) ( 'A' + rnd.nextInt( 26 ) ) );
            }
            core = sb.toString();
        } while( !used.add( core ) );
        return OPEN + core + CLOSE;
    }

    /**
     * {@code pos} moved off the inside of a token in {@code doc}: to the token's start ({@code down}) or just past its
     * end. A position on a token boundary is kept.
     */
    static int snap( final String doc, final int pos, final boolean down ) {
        final int open = doc.lastIndexOf( OPEN, pos - 1 );
        if( open < 0 ) {
            return pos;
        }
        final int close = open + CORE_LENGTH + 1;
        if( close >= doc.length() || doc.charAt( close ) != CLOSE || pos > close || !isCore( doc, open + 1 ) ) {
            return pos;
        }
        return down ? open : close + 1;
    }

    private static boolean isCore( final String s, final int from ) {
        for( int i = from; i < from + CORE_LENGTH; i++ ) {
            final char c = s.charAt( i );
            if( c < 'A' || c > 'Z' ) {
                return false;
            }
        }
        return true;
    }

    /** The collab version of the draft as last checked. */
    int lastHead() {
        return lastHead;
    }

    /** An insert was applied: its tokens join the issued set. */
    void inserted( final String text ) {
        issued.addAll( counts( text ).keySet() );
    }

    /**
     * A recorded delete of page text: an outside save (or the page's deletion) turned {@code before} into
     * {@code after}. Tokens it took out, or cut, are deleted.
     */
    void edited( final String before, final String after ) {
        final Map< String, Integer > now = counts( after );
        for( final String core : counts( before ).keySet() ) {
            if( !now.containsKey( core ) ) {
                deleted.add( core );
            }
        }
    }

    /**
     * The documented residual M1 hit (a crash between the save and the stored saved version, with a rewritten save): the
     * reload merges the publish as an outside write, so the tokens the save added over its base ({@code saved} minus
     * {@code base}) may appear twice.
     */
    void residualM1( final String saved, final String base ) {
        final Set< String > added = new HashSet<>( counts( saved ).keySet() );
        added.removeAll( counts( base ).keySet() );
        allowedTwice.addAll( added );
    }

    /**
     * At-most-once and presence in the draft {@code doc} at collab version {@code head}; {@code logged} holds the stored
     * updates after the head last checked (from the store). A failure names the token.
     */
    void checkDraft( final String doc, final int head, final List< StoredUpdate > logged ) {
        final Map< String, Integer > c = counts( doc );
        atMostOnce( "the draft", c );
        if( lastDoc != null ) {
            final Set< String > vanished = new TreeSet<>( counts( lastDoc ).keySet() );
            vanished.removeAll( c.keySet() );
            vanished.removeAll( deleted );
            if( !vanished.isEmpty() ) {
                lost( vanished, doc, head, logged );
            }
        }
        lastDoc = doc;
        lastHead = head;
    }

    /**
     * Replays {@code logged} over the draft as last checked to find the update that removed each vanished token: a
     * client's update deletes it legitimately; a server update loses it. A log that does not account for the change (a
     * truncated gap, a reload) cannot attribute the loss and is not judged.
     */
    private void lost( final Set< String > vanished, final String doc, final int head, final List< StoredUpdate > logged ) {
        if( logged == null || logged.size() != head - lastHead
                || !logged.isEmpty() && logged.get( 0 ).version() != lastHead + 1 ) {
            return;
        }
        String cur = lastDoc;
        final Set< String > byServer = new TreeSet<>();
        for( final StoredUpdate u : logged ) {
            if( u.changes().length() != cur.length() ) {
                return;
            }
            final String next = u.changes().apply( cur );
            final Set< String > gone = new HashSet<>( counts( cur ).keySet() );
            gone.removeAll( counts( next ).keySet() );
            if( u.authorKey() != null ) {
                deleted.addAll( gone );
            } else {
                byServer.addAll( gone );
            }
            cur = next;
        }
        if( !cur.equals( doc ) ) {
            return;
        }
        byServer.retainAll( vanished );
        byServer.removeAll( deleted );
        if( !byServer.isEmpty() ) {
            throw new SimScheduler.SimFailure( "token-oracle/lost", "token oracle: a server update dropped " + byServer
                + " from the draft without a recorded delete (text lost)" );
        }
    }

    /** At-most-once in a stored page body (each body scanned once). */
    void checkVersion( final String where, final String body ) {
        if( checkedVersions.add( body ) ) {
            atMostOnce( where, counts( body ) );
        }
    }

    private void atMostOnce( final String where, final Map< String, Integer > c ) {
        for( final Map.Entry< String, Integer > e : c.entrySet() ) {
            if( e.getValue() > ( allowedTwice.contains( e.getKey() ) ? 2 : 1 ) ) {
                throw new SimScheduler.SimFailure( "token-oracle/duplicate", "token oracle: text duplicated: token "
                    + e.getKey() + " appears " + e.getValue() + " times in " + where );
            }
        }
    }

    /**
     * Each issued-looking core in {@code text}, with its count: every maximal upper-case run, and every
     * {@value #CORE_LENGTH}-letter window of a longer run (a cut can join cores). Only cores in {@link #issued} count
     * for windows of longer runs; whole runs of the core length count always.
     */
    private Map< String, Integer > counts( final String text ) {
        final Map< String, Integer > out = new HashMap<>();
        int i = 0;
        final int n = text.length();
        while( i < n ) {
            if( text.charAt( i ) < 'A' || text.charAt( i ) > 'Z' ) {
                i++;
                continue;
            }
            int j = i;
            while( j < n && text.charAt( j ) >= 'A' && text.charAt( j ) <= 'Z' ) {
                j++;
            }
            if( j - i == CORE_LENGTH ) {
                out.merge( text.substring( i, j ), 1, Integer::sum );
            } else {
                for( int k = i; k + CORE_LENGTH <= j; k++ ) {
                    final String w = text.substring( k, k + CORE_LENGTH );
                    if( issued.contains( w ) ) {
                        out.merge( w, 1, Integer::sum );
                    }
                }
            }
            i = j;
        }
        return out;
    }
}
