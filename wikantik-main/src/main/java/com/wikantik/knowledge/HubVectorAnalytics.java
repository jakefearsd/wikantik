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
package com.wikantik.knowledge;

import com.wikantik.knowledge.HubOverviewService.NearMissTfidf;
import com.wikantik.knowledge.HubOverviewService.OverlapHub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vector-space analytics over hub member sets: centroid computation, near-miss
 * scoring, overlap detection between hubs.
 *
 * <p>Factored out of {@link HubOverviewService} as part of Phase 11 Ckpt 6
 * god-class decomposition. All methods are pure computations over
 * {@code float[]} vectors — no I/O, no state.
 */
class HubVectorAnalytics {

    /** Normalised centroid + mean-dot coherence for a hub; centroid is null and coherence NaN if &lt;2 vectors. */
    record HubShape( float[] centroid, double coherence ) {
        static HubShape empty() { return new HubShape( null, Double.NaN ); }
    }

    private final double nearMissThreshold;
    private final double overlapThreshold;
    private final int    nearMissMaxResults;

    HubVectorAnalytics( final double nearMissThreshold,
                        final double overlapThreshold,
                        final int nearMissMaxResults ) {
        this.nearMissThreshold  = nearMissThreshold;
        this.overlapThreshold   = overlapThreshold;
        this.nearMissMaxResults = nearMissMaxResults;
    }

    /** Per-hub centroid + coherence over members that have mention-centroid vectors. */
    Map< String, HubShape > computeHubShapes(
            final Map< String, float[] > vectors,
            final Map< String, com.wikantik.api.knowledge.KgNode > hubsByName,
            final Map< String, Set< String > > hubMembers ) {
        final Map< String, HubShape > shapes = new LinkedHashMap<>();
        for ( final String hubName : hubsByName.keySet() ) {
            final List< float[] > vecs = vectorsFor( vectors, hubMembers.getOrDefault( hubName, Set.of() ) );
            if ( vecs.size() < 2 ) {
                shapes.put( hubName, HubShape.empty() );
                continue;
            }
            final float[][] arr = vecs.toArray( new float[ 0 ][] );
            final float[] centroid = HubDiscoveryService.normalizedCentroid( arr );
            shapes.put( hubName, new HubShape( centroid, HubDiscoveryService.meanDot( arr, centroid ) ) );
        }
        return shapes;
    }

    /** Per-hub count of non-member non-hub entities whose cosine ≥ threshold. */
    Map< String, Integer > computeNearMissCounts(
            final Map< String, float[] > vectors,
            final Map< String, com.wikantik.api.knowledge.KgNode > hubsByName,
            final Map< String, Set< String > > hubMembers,
            final Map< String, HubShape > shapes ) {
        final Set< String > allMemberNames = new HashSet<>();
        for ( final Set< String > set : hubMembers.values() ) allMemberNames.addAll( set );
        final Map< String, Integer > counts = new HashMap<>();
        for ( final String hubName : hubsByName.keySet() ) counts.put( hubName, 0 );
        for ( final Map.Entry< String, float[] > entry : vectors.entrySet() ) {
            final String entityName = entry.getKey();
            if ( hubsByName.containsKey( entityName ) ) continue;
            if ( allMemberNames.contains( entityName ) ) continue;
            final float[] vec = entry.getValue();
            for ( final String hubName : hubsByName.keySet() ) {
                final float[] centroid = shapes.get( hubName ).centroid();
                if ( centroid != null && cosine( vec, centroid ) >= nearMissThreshold ) {
                    counts.merge( hubName, 1, Integer::sum );
                }
            }
        }
        return counts;
    }

    /** Centroid + coherence for an explicit member set. */
    HubShape hubShapeOf( final Map< String, float[] > vectors, final Set< String > members ) {
        if ( vectors.isEmpty() || members.size() < 2 ) return HubShape.empty();
        final List< float[] > vecs = vectorsFor( vectors, members );
        if ( vecs.size() < 2 ) return HubShape.empty();
        final float[][] arr = vecs.toArray( new float[ 0 ][] );
        final float[] centroid = HubDiscoveryService.normalizedCentroid( arr );
        return new HubShape( centroid, HubDiscoveryService.meanDot( arr, centroid ) );
    }

    /** Top-N non-member non-hub entities with cosine ≥ nearMissThreshold, descending. */
    List< NearMissTfidf > computeNearMissTfidf(
            final Map< String, float[] > vectors, final float[] centroid,
            final Set< String > allHubNames, final Set< String > rawMembers ) {
        if ( centroid == null ) return List.of();
        final List< NearMissTfidf > scored = new ArrayList<>();
        for ( final Map.Entry< String, float[] > entry : vectors.entrySet() ) {
            final String entityName = entry.getKey();
            if ( allHubNames.contains( entityName ) ) continue;
            if ( rawMembers.contains( entityName ) ) continue;
            final double cos = cosine( entry.getValue(), centroid );
            if ( cos >= nearMissThreshold ) {
                scored.add( new NearMissTfidf( entityName, cos ) );
            }
        }
        scored.sort( ( a, b ) -> Double.compare( b.cosineToCentroid(), a.cosineToCentroid() ) );
        return scored.subList( 0, Math.min( scored.size(), nearMissMaxResults ) );
    }

    /** Other hubs whose centroid cosine with this one is ≥ overlapThreshold. */
    List< OverlapHub > computeOverlapHubs(
            final Map< String, float[] > vectors, final float[] centroid,
            final Map< String, Set< String > > allHubMembers,
            final String hubName, final Set< String > rawMembers ) {
        if ( centroid == null ) return List.of();
        final List< OverlapHub > overlapHubs = new ArrayList<>();
        for ( final var entry : allHubMembers.entrySet() ) {
            final String otherHub = entry.getKey();
            if ( otherHub.equals( hubName ) ) continue;
            final List< float[] > otherVecs = vectorsFor( vectors, entry.getValue() );
            if ( otherVecs.size() < 2 ) continue;
            final float[][] arr = otherVecs.toArray( new float[ 0 ][] );
            final float[] otherCentroid = HubDiscoveryService.normalizedCentroid( arr );
            final double cos = cosine( centroid, otherCentroid );
            if ( cos < overlapThreshold ) continue;
            final Set< String > shared = new HashSet<>( rawMembers );
            shared.retainAll( entry.getValue() );
            overlapHubs.add( new OverlapHub( otherHub, cos, shared.size() ) );
        }
        overlapHubs.sort( ( a, b ) -> Double.compare( b.centroidCosine(), a.centroidCosine() ) );
        return overlapHubs;
    }

    // ---- static helpers ----

    static List< float[] > vectorsFor( final Map< String, float[] > vectors,
                                        final Set< String > names ) {
        final List< float[] > out = new ArrayList<>();
        for ( final String name : names ) {
            final float[] v = vectors.get( name );
            if ( v != null ) out.add( v );
        }
        return out;
    }

    static double cosine( final float[] a, final float[] b ) {
        if ( a.length != b.length ) return 0.0;
        double dot = 0;
        for ( int i = 0; i < a.length; i++ ) dot += a[ i ] * b[ i ];
        return dot;
    }
}
