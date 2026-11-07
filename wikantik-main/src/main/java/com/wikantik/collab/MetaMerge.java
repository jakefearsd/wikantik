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

import com.wikantik.collab.model.StoredMetaOp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Meta rules of publish and external merge (D4, section 7.4, A14). Every comparison is between values normalised by
 * {@link MetaJson} on both sides (a YAML {@code Date} and its JSON string are one value); an absent field and a null
 * one are the same (an op with a null value removes the field).
 */
final class MetaMerge {

    /** The field assignments a merge makes (in field order; a null value removes) and the fields both sides changed. */
    record External( Map< String, Object > changes, List< String > conflicts ) {
        /** True when the merge took a field of theirs or kept ours against theirs. */
        boolean touched() {
            return !changes.isEmpty() || !conflicts.isEmpty();
        }
    }

    private MetaMerge() {
    }

    /**
     * Section 7.4's per-field last-writer-wins: the outside writer changed {@code f} and the session did not → take
     * theirs; both changed it to different values → keep ours and report {@code f}.
     */
    static External external( final Map< String, Object > baseMeta, final Map< String, Object > meta,
                              final Map< String, Object > theirs ) {
        final Map< String, Object > changes = new LinkedHashMap<>();
        final List< String > conflicts = new ArrayList<>();
        for( final String f : fields( baseMeta, theirs ) ) {
            final Object base = baseMeta.get( f );
            final Object ext = theirs.get( f );
            if( Objects.equals( base, ext ) ) {
                continue;
            }
            final Object ours = meta.get( f );
            if( Objects.equals( base, ours ) ) {
                changes.put( f, ext );
            } else if( !Objects.equals( ours, ext ) ) {
                conflicts.add( f );
            }
        }
        return new External( changes, conflicts );
    }

    /**
     * The meta a publish saves: the draft's {@code meta}, except that a field still equal (normalised) to the base keeps
     * the base page's original typed value ({@code original}, as the page store read it), so publishing never turns a
     * YAML {@code Date} into a quoted string.
     */
    static Map< String, Object > published( final Map< String, Object > meta, final Map< String, Object > original ) {
        final Map< String, Object > out = new LinkedHashMap<>();
        for( final Map.Entry< String, Object > e : meta.entrySet() ) {
            final String f = e.getKey();
            final boolean unchanged = original.containsKey( f )
                && Objects.equals( MetaJson.normaliseValue( original.get( f ) ), e.getValue() );
            out.put( f, unchanged ? original.get( f ) : e.getValue() );
        }
        return out;
    }

    /**
     * The meta changes the save filters made: every field where the saved meta differs from what was published
     * ({@code published}, M_V) and the session has not changed it since ({@code meta}).
     */
    static Map< String, Object > fold( final Map< String, Object > published, final Map< String, Object > meta,
                                       final Map< String, Object > saved ) {
        final Map< String, Object > changes = new LinkedHashMap<>();
        for( final String f : fields( published, saved ) ) {
            final Object sent = published.get( f );
            final Object kept = saved.get( f );
            if( !Objects.equals( sent, kept ) && Objects.equals( meta.get( f ), sent ) ) {
                changes.put( f, kept );
            }
        }
        return changes;
    }

    /** Server meta ops (no author) for {@code changes}, numbered from {@code metaSeq + 1}. */
    static List< StoredMetaOp > ops( final Map< String, Object > changes, final long metaSeq ) {
        final List< StoredMetaOp > ops = new ArrayList<>( changes.size() );
        changes.forEach( ( f, v ) -> ops.add( new StoredMetaOp( metaSeq + ops.size() + 1, null, f, v ) ) );
        return ops;
    }

    private static Set< String > fields( final Map< String, Object > a, final Map< String, Object > b ) {
        final Set< String > out = new LinkedHashSet<>( a.keySet() );
        out.addAll( b.keySet() );
        return out;
    }
}
