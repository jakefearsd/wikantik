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

import java.util.Locale;
import java.util.Properties;

/**
 * Immutable configuration for the hybrid retrieval pipeline. All values are
 * resolved from a {@link Properties} bag with documented defaults; any value
 * that is present but malformed causes {@link #fromProperties(Properties)} to
 * throw {@link IllegalArgumentException}.
 */
public record HybridConfig(
    boolean enabled,
    PageAggregation pageAggregation,
    int rrfK,
    double bm25Weight,
    double denseWeight,
    int rrfTruncate,
    int denseChunkTop,
    int densePageTop
) {

    public static final String PROP_ENABLED          = "wikantik.search.hybrid.enabled";
    public static final String PROP_PAGE_AGGREGATION = "wikantik.search.hybrid.page-aggregation";
    public static final String PROP_RRF_K            = "wikantik.search.hybrid.rrf.k";
    public static final String PROP_RRF_BM25_WEIGHT  = "wikantik.search.hybrid.rrf.bm25-weight";
    public static final String PROP_RRF_DENSE_WEIGHT = "wikantik.search.hybrid.rrf.dense-weight";
    public static final String PROP_RRF_TRUNCATE     = "wikantik.search.hybrid.rrf.truncate";
    public static final String PROP_DENSE_CHUNK_TOP  = "wikantik.search.hybrid.dense.chunk-top";
    public static final String PROP_DENSE_PAGE_TOP   = "wikantik.search.hybrid.dense.page-top";

    public static final boolean         DEFAULT_ENABLED          = false;
    public static final PageAggregation DEFAULT_PAGE_AGGREGATION = PageAggregation.SUM_TOP_3;
    public static final int             DEFAULT_RRF_K            = 60;
    public static final double          DEFAULT_BM25_WEIGHT      = 1.0;
    public static final double          DEFAULT_DENSE_WEIGHT     = 1.5;
    public static final int             DEFAULT_RRF_TRUNCATE     = 20;
    public static final int             DEFAULT_DENSE_CHUNK_TOP  = 500;
    public static final int             DEFAULT_DENSE_PAGE_TOP   = 100;

    public static HybridConfig fromProperties( final Properties p ) {
        return new HybridConfig(
            boolProp( p, PROP_ENABLED,             DEFAULT_ENABLED ),
            enumProp( p, PROP_PAGE_AGGREGATION,    DEFAULT_PAGE_AGGREGATION ),
            intProp(  p, PROP_RRF_K,               DEFAULT_RRF_K,           1, Integer.MAX_VALUE ),
            doubleProp( p, PROP_RRF_BM25_WEIGHT,   DEFAULT_BM25_WEIGHT,     0.0 ),
            doubleProp( p, PROP_RRF_DENSE_WEIGHT,  DEFAULT_DENSE_WEIGHT,    0.0 ),
            intProp(  p, PROP_RRF_TRUNCATE,        DEFAULT_RRF_TRUNCATE,    0, Integer.MAX_VALUE ),
            intProp(  p, PROP_DENSE_CHUNK_TOP,     DEFAULT_DENSE_CHUNK_TOP, 1, Integer.MAX_VALUE ),
            intProp(  p, PROP_DENSE_PAGE_TOP,      DEFAULT_DENSE_PAGE_TOP,  1, Integer.MAX_VALUE )
        );
    }

    private static boolean boolProp( final Properties p, final String key, final boolean def ) {
        final String v = trimmed( p, key );
        if( v == null ) return def;
        if( v.equalsIgnoreCase( "true" )  ) return true;
        if( v.equalsIgnoreCase( "false" ) ) return false;
        throw new IllegalArgumentException( key + " must be 'true' or 'false', got: " + v );
    }

    private static PageAggregation enumProp( final Properties p, final String key, final PageAggregation def ) {
        final String v = trimmed( p, key );
        if( v == null ) return def;
        try {
            return PageAggregation.valueOf( v.toUpperCase( Locale.ROOT ) );
        } catch( final IllegalArgumentException ex ) {
            throw new IllegalArgumentException( key + " must be one of "
                + java.util.Arrays.toString( PageAggregation.values() ) + ", got: " + v, ex );
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

    private static double doubleProp( final Properties p, final String key, final double def, final double min ) {
        final String v = trimmed( p, key );
        if( v == null ) return def;
        final double parsed;
        try {
            parsed = Double.parseDouble( v );
        } catch( final NumberFormatException ex ) {
            throw new IllegalArgumentException( key + " must be a number, got: " + v, ex );
        }
        if( Double.isNaN( parsed ) || Double.isInfinite( parsed ) || parsed < min ) {
            throw new IllegalArgumentException( key + " must be a finite number >= " + min + ", got: " + v );
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
