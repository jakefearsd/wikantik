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
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleSection;
import com.wikantik.api.eval.GoldSection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Pure, deterministic, LLM-free bundle-quality metrics for Phase 0:
 * context recall, context precision@K, and citation faithfulness. A gold
 * section is "covered" when a bundle section shares its canonical_id and a
 * heading-path that equals or extends (starts-with) the gold heading-path.
 */
public final class BundleMetricsCalculator {

    private BundleMetricsCalculator() {}

    /** Fraction of gold sections covered by the bundle. 0.0 for an empty bundle. */
    public static double contextRecall( final List< GoldSection > golds,
                                        final List< BundleSection > bundle ) {
        if ( golds == null || golds.isEmpty() ) return 0.0;
        int covered = 0;
        for ( final GoldSection g : golds ) {
            if ( isCovered( g, bundle ) ) covered++;
        }
        return (double) covered / golds.size();
    }

    /**
     * Fraction of the sections present in the top-K of the bundle that cover a gold
     * section — denominator is {@code min(k, bundle size)}, so a small all-gold bundle
     * scores 1.0 (tightness) rather than {@code goldSlots/k}, which would penalise short
     * bundles for being short instead of noisy.
     */
    public static double contextPrecisionAtK( final List< GoldSection > golds,
                                              final List< BundleSection > bundle,
                                              final int k ) {
        if ( k <= 0 || bundle == null || bundle.isEmpty() ) return 0.0;
        final int cap = Math.min( k, bundle.size() );
        int goldSlots = 0;
        for ( int i = 0; i < cap; i++ ) {
            if ( coversAnyGold( bundle.get( i ), golds ) ) goldSlots++;
        }
        return (double) goldSlots / cap;
    }

    /** A citation is faithful iff the pinned hash equals SHA-256 of the resolved span. */
    public static boolean citationFaithful( final String pinnedHash, final String resolvedText ) {
        if ( pinnedHash == null || resolvedText == null ) return false;
        return pinnedHash.equals( sha256( resolvedText ) );
    }

    /** Hex SHA-256 of UTF-8 text — the span content-hash used by citation handles (ADR-0005). */
    public static String sha256( final String text ) {
        try {
            final byte[] d = MessageDigest.getInstance( "SHA-256" )
                .digest( text.getBytes( StandardCharsets.UTF_8 ) );
            final StringBuilder sb = new StringBuilder( d.length * 2 );
            for ( final byte b : d ) sb.append( String.format( "%02x", b ) );
            return sb.toString();
        } catch ( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 unavailable", e );
        }
    }

    // ---- internals ----

    private static boolean isCovered( final GoldSection g, final List< BundleSection > bundle ) {
        if ( bundle == null ) return false;
        for ( final BundleSection s : bundle ) {
            if ( matches( g, s ) ) return true;
        }
        return false;
    }

    private static boolean coversAnyGold( final BundleSection s, final List< GoldSection > golds ) {
        if ( golds == null ) return false;
        for ( final GoldSection g : golds ) {
            if ( matches( g, s ) ) return true;
        }
        return false;
    }

    /** Same page, and the bundle section's heading-path equals or extends the gold's. */
    private static boolean matches( final GoldSection g, final BundleSection s ) {
        if ( !g.canonicalId().equals( s.canonicalId() ) ) return false;
        final List< String > gp = g.headingPath();
        final List< String > sp = s.headingPath();
        if ( sp.size() < gp.size() ) return false;
        for ( int i = 0; i < gp.size(); i++ ) {
            if ( !gp.get( i ).equalsIgnoreCase( sp.get( i ) ) ) return false;
        }
        return true;
    }
}
