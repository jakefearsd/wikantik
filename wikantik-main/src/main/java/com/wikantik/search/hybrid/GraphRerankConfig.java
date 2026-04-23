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
package com.wikantik.search.hybrid;

import java.util.Properties;

/**
 * Immutable configuration for Phase 3 graph-aware rerank. Parsed from the
 * wikantik.properties bag with {@link #fromProperties(Properties)}; any value
 * that is present but malformed throws {@link IllegalArgumentException} so a
 * bad config fails loudly at boot instead of silently disabling the feature.
 *
 * <p>{@code boost == 0.0} is a valid value and explicitly disables the graph
 * rerank step without requiring a redeploy — flipping the knob is the
 * rollback path for the phase.</p>
 */
public record GraphRerankConfig(
    double boost,
    int maxHops,
    int queryEntityCacheTtlSeconds,
    int queryEntityCacheMax,
    int neighborIndexMaxEdges
) {

    public static final String PROP_BOOST                    = "wikantik.search.graph.boost";
    public static final String PROP_MAX_HOPS                 = "wikantik.search.graph.max-hops";
    public static final String PROP_QUERY_ENTITY_CACHE_TTL   = "wikantik.search.graph.query-entity.cache.ttl-seconds";
    public static final String PROP_QUERY_ENTITY_CACHE_MAX   = "wikantik.search.graph.query-entity.cache.max-entries";
    public static final String PROP_NEIGHBOR_INDEX_MAX_EDGES = "wikantik.search.graph.neighbor-index.max-edges";

    public static final double DEFAULT_BOOST                    = 0.2;
    public static final int    DEFAULT_MAX_HOPS                 = 2;
    public static final int    DEFAULT_QUERY_ENTITY_CACHE_TTL   = 300;
    public static final int    DEFAULT_QUERY_ENTITY_CACHE_MAX   = 1000;
    public static final int    DEFAULT_NEIGHBOR_INDEX_MAX_EDGES = 500_000;

    public GraphRerankConfig {
        if( Double.isNaN( boost ) || Double.isInfinite( boost ) || boost < 0.0 ) {
            throw new IllegalArgumentException( PROP_BOOST + " must be a finite number >= 0, got: " + boost );
        }
        if( maxHops < 1 ) {
            throw new IllegalArgumentException( PROP_MAX_HOPS + " must be >= 1, got: " + maxHops );
        }
        if( queryEntityCacheTtlSeconds < 1 ) {
            throw new IllegalArgumentException( PROP_QUERY_ENTITY_CACHE_TTL + " must be >= 1, got: " + queryEntityCacheTtlSeconds );
        }
        if( queryEntityCacheMax < 1 ) {
            throw new IllegalArgumentException( PROP_QUERY_ENTITY_CACHE_MAX + " must be >= 1, got: " + queryEntityCacheMax );
        }
        if( neighborIndexMaxEdges < 1 ) {
            throw new IllegalArgumentException( PROP_NEIGHBOR_INDEX_MAX_EDGES + " must be >= 1, got: " + neighborIndexMaxEdges );
        }
    }

    /** {@code true} when the rerank step should actually run; {@code false} with {@code boost == 0.0}. */
    public boolean enabled() {
        return boost > 0.0;
    }

    public static GraphRerankConfig fromProperties( final Properties p ) {
        return new GraphRerankConfig(
            doubleProp( p, PROP_BOOST,                    DEFAULT_BOOST ),
            intProp   ( p, PROP_MAX_HOPS,                 DEFAULT_MAX_HOPS,                 1, Integer.MAX_VALUE ),
            intProp   ( p, PROP_QUERY_ENTITY_CACHE_TTL,   DEFAULT_QUERY_ENTITY_CACHE_TTL,   1, Integer.MAX_VALUE ),
            intProp   ( p, PROP_QUERY_ENTITY_CACHE_MAX,   DEFAULT_QUERY_ENTITY_CACHE_MAX,   1, Integer.MAX_VALUE ),
            intProp   ( p, PROP_NEIGHBOR_INDEX_MAX_EDGES, DEFAULT_NEIGHBOR_INDEX_MAX_EDGES, 1, Integer.MAX_VALUE )
        );
    }

    private static double doubleProp( final Properties p, final String key, final double def ) {
        final String v = trimmed( p, key );
        if( v == null ) return def;
        try {
            return Double.parseDouble( v );
        } catch( final NumberFormatException ex ) {
            throw new IllegalArgumentException( key + " must be a number, got: " + v, ex );
        }
    }

    private static int intProp( final Properties p, final String key, final int def, final int min, final int max ) {
        final String v = trimmed( p, key );
        if( v == null ) return def;
        final int parsed;
        try {
            parsed = Integer.parseInt( v );
        } catch( final NumberFormatException ex ) {
            throw new IllegalArgumentException( key + " must be an integer, got: " + v, ex );
        }
        if( parsed < min || parsed > max ) {
            throw new IllegalArgumentException( key + " must be in [" + min + "," + max + "], got: " + parsed );
        }
        return parsed;
    }

    private static String trimmed( final Properties p, final String key ) {
        final String v = p.getProperty( key );
        if( v == null ) return null;
        final String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
