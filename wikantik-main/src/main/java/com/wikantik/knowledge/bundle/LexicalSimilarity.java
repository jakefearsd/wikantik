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
package com.wikantik.knowledge.bundle;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Deterministic term-frequency cosine similarity over two texts, used by {@link MmrSectionReranker}
 *  for the MMR diversity term. Pure: lower-cases, splits on non-alphanumerics, cosine over raw
 *  term counts. Returns 0.0 when either side has no tokens. */
final class LexicalSimilarity {

    private LexicalSimilarity() {}

    static Map< String, Integer > vector( final String text ) {
        final Map< String, Integer > m = new HashMap<>();
        if ( text == null ) return m;
        for ( final String tok : text.toLowerCase( Locale.ROOT ).split( "[^a-z0-9]+" ) ) {
            if ( !tok.isEmpty() ) m.merge( tok, 1, Integer::sum );
        }
        return m;
    }

    static double cosine( final Map< String, Integer > a, final Map< String, Integer > b ) {
        if ( a.isEmpty() || b.isEmpty() ) return 0.0;
        // iterate the smaller map for the dot product
        final Map< String, Integer > small = a.size() <= b.size() ? a : b;
        final Map< String, Integer > large = small == a ? b : a;
        double dot = 0.0;
        for ( final Map.Entry< String, Integer > e : small.entrySet() ) {
            final Integer o = large.get( e.getKey() );
            if ( o != null ) dot += (double) e.getValue() * o;
        }
        final double na = norm( a );
        final double nb = norm( b );
        return ( na == 0.0 || nb == 0.0 ) ? 0.0 : dot / ( na * nb );
    }

    static double cosine( final String a, final String b ) {
        return cosine( vector( a ), vector( b ) );
    }

    private static double norm( final Map< String, Integer > v ) {
        double s = 0.0;
        for ( final int c : v.values() ) s += (double) c * c;
        return Math.sqrt( s );
    }
}
