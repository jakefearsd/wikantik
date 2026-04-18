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

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridConfigTest {

    @Test
    void emptyPropertiesYieldProvenDefaults() {
        final HybridConfig c = HybridConfig.fromProperties( new Properties() );
        assertFalse( c.enabled() );
        assertEquals( PageAggregation.SUM_TOP_3, c.pageAggregation() );
        assertEquals( 60, c.rrfK() );
        assertEquals( 1.0, c.bm25Weight() );
        assertEquals( 1.5, c.denseWeight() );
        assertEquals( 20, c.rrfTruncate() );
        assertEquals( 500, c.denseChunkTop() );
        assertEquals( 100, c.densePageTop() );
    }

    @Test
    void blankValuesFallBackToDefaults() {
        final Properties p = new Properties();
        p.setProperty( HybridConfig.PROP_ENABLED, "   " );
        p.setProperty( HybridConfig.PROP_PAGE_AGGREGATION, "" );
        final HybridConfig c = HybridConfig.fromProperties( p );
        assertFalse( c.enabled() );
        assertEquals( PageAggregation.SUM_TOP_3, c.pageAggregation() );
    }

    @Test
    void allOverridesApplied() {
        final Properties p = new Properties();
        p.setProperty( HybridConfig.PROP_ENABLED,          "true" );
        p.setProperty( HybridConfig.PROP_PAGE_AGGREGATION, "mean_top_3" );
        p.setProperty( HybridConfig.PROP_RRF_K,            "30" );
        p.setProperty( HybridConfig.PROP_RRF_BM25_WEIGHT,  "0.7" );
        p.setProperty( HybridConfig.PROP_RRF_DENSE_WEIGHT, "2.0" );
        p.setProperty( HybridConfig.PROP_RRF_TRUNCATE,     "0" );
        p.setProperty( HybridConfig.PROP_DENSE_CHUNK_TOP,  "250" );
        p.setProperty( HybridConfig.PROP_DENSE_PAGE_TOP,   "50" );

        final HybridConfig c = HybridConfig.fromProperties( p );
        assertTrue( c.enabled() );
        assertEquals( PageAggregation.MEAN_TOP_3, c.pageAggregation() );
        assertEquals( 30, c.rrfK() );
        assertEquals( 0.7, c.bm25Weight() );
        assertEquals( 2.0, c.denseWeight() );
        assertEquals( 0, c.rrfTruncate() );
        assertEquals( 250, c.denseChunkTop() );
        assertEquals( 50, c.densePageTop() );
    }

    @Test
    void invalidBooleanRejected() {
        final Properties p = new Properties();
        p.setProperty( HybridConfig.PROP_ENABLED, "yes" );
        assertThrows( IllegalArgumentException.class, () -> HybridConfig.fromProperties( p ) );
    }

    @Test
    void invalidEnumRejected() {
        final Properties p = new Properties();
        p.setProperty( HybridConfig.PROP_PAGE_AGGREGATION, "geometric_mean" );
        assertThrows( IllegalArgumentException.class, () -> HybridConfig.fromProperties( p ) );
    }

    @Test
    void invalidIntRejected() {
        final Properties p = new Properties();
        p.setProperty( HybridConfig.PROP_RRF_K, "sixty" );
        assertThrows( IllegalArgumentException.class, () -> HybridConfig.fromProperties( p ) );
    }

    @Test
    void rrfKMustBePositive() {
        final Properties p = new Properties();
        p.setProperty( HybridConfig.PROP_RRF_K, "0" );
        assertThrows( IllegalArgumentException.class, () -> HybridConfig.fromProperties( p ) );
    }

    @Test
    void truncateRejectsNegative() {
        final Properties p = new Properties();
        p.setProperty( HybridConfig.PROP_RRF_TRUNCATE, "-1" );
        assertThrows( IllegalArgumentException.class, () -> HybridConfig.fromProperties( p ) );
    }

    @Test
    void denseChunkTopRejectsZero() {
        final Properties p = new Properties();
        p.setProperty( HybridConfig.PROP_DENSE_CHUNK_TOP, "0" );
        assertThrows( IllegalArgumentException.class, () -> HybridConfig.fromProperties( p ) );
    }

    @Test
    void densePageTopRejectsZero() {
        final Properties p = new Properties();
        p.setProperty( HybridConfig.PROP_DENSE_PAGE_TOP, "0" );
        assertThrows( IllegalArgumentException.class, () -> HybridConfig.fromProperties( p ) );
    }

    @Test
    void invalidDoubleRejected() {
        final Properties p = new Properties();
        p.setProperty( HybridConfig.PROP_RRF_DENSE_WEIGHT, "two-point-zero" );
        assertThrows( IllegalArgumentException.class, () -> HybridConfig.fromProperties( p ) );
    }

    @Test
    void negativeWeightRejected() {
        final Properties p = new Properties();
        p.setProperty( HybridConfig.PROP_RRF_BM25_WEIGHT, "-0.5" );
        assertThrows( IllegalArgumentException.class, () -> HybridConfig.fromProperties( p ) );
    }

    @Test
    void nanWeightRejected() {
        final Properties p = new Properties();
        p.setProperty( HybridConfig.PROP_RRF_DENSE_WEIGHT, "NaN" );
        assertThrows( IllegalArgumentException.class, () -> HybridConfig.fromProperties( p ) );
    }

    @Test
    void infiniteWeightRejected() {
        final Properties p = new Properties();
        p.setProperty( HybridConfig.PROP_RRF_BM25_WEIGHT, "Infinity" );
        assertThrows( IllegalArgumentException.class, () -> HybridConfig.fromProperties( p ) );
    }

    @Test
    void enumParsingIsCaseInsensitive() {
        final Properties p = new Properties();
        p.setProperty( HybridConfig.PROP_PAGE_AGGREGATION, "SuM_ToP_5" );
        assertEquals( PageAggregation.SUM_TOP_5, HybridConfig.fromProperties( p ).pageAggregation() );
    }
}
