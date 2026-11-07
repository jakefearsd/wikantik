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
package com.wikantik.collab;

import com.wikantik.collab.model.Contribution;
import com.wikantik.collab.model.StoredMetaOp;
import com.wikantik.collab.model.StoredUpdate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Who contributed to the draft since its last publish (docs/MultiEdit.MD section 7.3, ruling R2): per author key, in
 * first-contribution order, the first and last collab version of its text updates and the first and last seq of its
 * meta ops ({@link Contribution}). A publish credits exactly the authors with a contribution inside the published
 * snapshot, at or below its version V and meta seq M_V ({@link #creditedAt}); edits made during the save count toward the
 * next version ({@link #afterPublish}). Server changes (external merges, discards, publish rewrites) carry no author and
 * credit nobody; the creator's starting text does (it is stored with the creator's key).
 *
 * <p>Engine-free and not thread-safe: the session holds its lock around every call.</p>
 */
final class Contributors {

    /** The public name of a contributor whose identity has none: never the key, never a positional label. */
    static final String UNKNOWN = com.wikantik.api.identity.IdentityDisplay.UNKNOWN_CONTRIBUTOR_LABEL;

    private final Map< String, Contribution > byAuthor;

    private Contributors( final Map< String, Contribution > byAuthor ) {
        this.byAuthor = byAuthor;
    }

    /** A mutable record starting from {@code m} (copied, order kept), such as a snapshot's contributors. */
    static Contributors of( final Map< String, Contribution > m ) {
        return new Contributors( new LinkedHashMap<>( m ) );
    }

    /** A text update by {@code authorKey} accepted at collab version {@code version} (at least 1). */
    void text( final String authorKey, final int version ) {
        positive( version, "version" );
        final Contribution c = byAuthor.get( key( authorKey ) );
        byAuthor.put( authorKey, c == null || c.firstVersion() == Contribution.NONE
            ? new Contribution( version, version, c == null ? Contribution.NONE : c.firstMetaSeq(),
                c == null ? Contribution.NONE : c.lastMetaSeq() )
            : new Contribution( Math.min( c.firstVersion(), version ), Math.max( c.lastVersion(), version ),
                c.firstMetaSeq(), c.lastMetaSeq() ) );
    }

    /** A meta op by {@code authorKey} stored at meta seq {@code seq} (at least 1). */
    void meta( final String authorKey, final long seq ) {
        positive( seq, "meta seq" );
        final Contribution c = byAuthor.get( key( authorKey ) );
        byAuthor.put( authorKey, c == null || c.firstMetaSeq() == Contribution.NONE
            ? new Contribution( c == null ? Contribution.NONE : c.firstVersion(),
                c == null ? Contribution.NONE : c.lastVersion(), seq, seq )
            : new Contribution( c.firstVersion(), c.lastVersion(), Math.min( c.firstMetaSeq(), seq ),
                Math.max( c.lastMetaSeq(), seq ) ) );
    }

    /**
     * Records stored changes in order: each update, then each meta op, that carries an author key (a server change
     * without one is skipped). Used by push, by the first join's seed and by the replay of the log on load.
     */
    void record( final List< StoredUpdate > updates, final List< StoredMetaOp > ops ) {
        for( final StoredUpdate u : updates ) {
            if( u.authorKey() != null ) {
                text( u.authorKey(), u.version() );
            }
        }
        for( final StoredMetaOp op : ops ) {
            if( op.authorKey() != null ) {
                meta( op.authorKey(), op.seq() );
            }
        }
    }

    /**
     * The authors credited by a publish of version {@code version} with meta seq {@code metaSeq}: those with a text
     * update at or below the version or a meta op at or below the seq, in first-contribution order.
     */
    Set< String > creditedAt( final int version, final long metaSeq ) {
        final Set< String > out = new LinkedHashSet<>();
        byAuthor.forEach( ( author, c ) -> {
            final boolean text = c.firstVersion() != Contribution.NONE && c.firstVersion() <= version;
            final boolean meta = c.firstMetaSeq() != Contribution.NONE && c.firstMetaSeq() <= metaSeq;
            if( text || meta ) {
                out.add( author );
            }
        } );
        return out;
    }

    /**
     * The record after a publish of version {@code version} with meta seq {@code metaSeq}, as a new instance: the
     * receiver is unchanged, so it stays live until the publish's commit succeeds. An author is kept when it has a text
     * update after the version ({@code lastVersion > V}) or a meta op after the seq ({@code lastMetaSeq > M}); each kept
     * kind becomes {@code [V+1, last]} (or {@code [M+1, last]}), a kind with nothing later becomes none.
     *
     * <p>Resetting {@code first} to V+1 is exact for the only question ever asked ({@code first <= V'}), because the next
     * publish's V' is the head at that time, which is at least {@code lastVersion}. (The same holds for the meta seq.)</p>
     */
    Contributors afterPublish( final int version, final long metaSeq ) {
        final Map< String, Contribution > kept = new LinkedHashMap<>();
        byAuthor.forEach( ( author, c ) -> {
            final boolean textKept = c.lastVersion() > version;
            final boolean metaKept = c.lastMetaSeq() > metaSeq;
            if( textKept || metaKept ) {
                kept.put( author, new Contribution( textKept ? version + 1 : Contribution.NONE,
                    textKept ? c.lastVersion() : Contribution.NONE, metaKept ? metaSeq + 1 : Contribution.NONE,
                    metaKept ? c.lastMetaSeq() : Contribution.NONE ) );
            }
        } );
        return new Contributors( kept );
    }

    /** A discard: nothing before it is ever credited. */
    void clear() {
        byAuthor.clear();
    }

    /** The author keys, in first-contribution order. */
    List< String > authors() {
        return List.copyOf( byAuthor.keySet() );
    }

    /** An unmodifiable, ordered copy for a snapshot. */
    Map< String, Contribution > snapshot() {
        return Collections.unmodifiableMap( new LinkedHashMap<>( byAuthor ) );
    }

    boolean isEmpty() {
        return byAuthor.isEmpty();
    }

    /**
     * The public names of {@code authorKeys}, in order: {@code displayName}'s, or {@link #UNKNOWN} when it has none.
     * Never the key itself (logins are private) and never the positional {@code "Editor N"} of presence.
     */
    static List< String > names( final List< String > authorKeys, final Function< String, String > displayName ) {
        final List< String > out = new ArrayList<>( authorKeys.size() );
        for( final String k : authorKeys ) {
            final String name = displayName.apply( k );
            out.add( name == null || name.isBlank() ? UNKNOWN : name );
        }
        return List.copyOf( out );
    }

    private static String key( final String authorKey ) {
        if( authorKey == null || authorKey.isBlank() ) {
            throw new IllegalArgumentException( "a contributor needs an author key" );
        }
        return authorKey;
    }

    private static void positive( final long n, final String what ) {
        if( n < 1 ) {
            throw new IllegalArgumentException( what + " must be at least 1: " + n );
        }
    }
}
