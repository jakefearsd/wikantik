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

class GraphRerankConfigTest {

    @Test
    void defaultsMatchDocumentedValues() {
        final GraphRerankConfig cfg = GraphRerankConfig.fromProperties( new Properties() );
        assertEquals( 0.2, cfg.boost(), 1e-9 );
        assertEquals( 2, cfg.maxHops() );
        assertEquals( 300, cfg.queryEntityCacheTtlSeconds() );
        assertEquals( 1_000, cfg.queryEntityCacheMax() );
        assertEquals( 500_000, cfg.neighborIndexMaxEdges() );
        assertTrue( cfg.enabled(), "default boost 0.2 should be enabled" );
    }

    @Test
    void boostZeroDisablesStepButIsValid() {
        final Properties p = new Properties();
        p.setProperty( GraphRerankConfig.PROP_BOOST, "0" );
        final GraphRerankConfig cfg = GraphRerankConfig.fromProperties( p );
        assertEquals( 0.0, cfg.boost(), 1e-9 );
        assertFalse( cfg.enabled(), "boost=0 must disable the rerank step" );
    }

    @Test
    void negativeBoostRejected() {
        final Properties p = new Properties();
        p.setProperty( GraphRerankConfig.PROP_BOOST, "-0.1" );
        assertThrows( IllegalArgumentException.class, () -> GraphRerankConfig.fromProperties( p ) );
    }

    @Test
    void nonNumericBoostRejected() {
        final Properties p = new Properties();
        p.setProperty( GraphRerankConfig.PROP_BOOST, "banana" );
        assertThrows( IllegalArgumentException.class, () -> GraphRerankConfig.fromProperties( p ) );
    }

    @Test
    void hopsMustBePositive() {
        final Properties p = new Properties();
        p.setProperty( GraphRerankConfig.PROP_MAX_HOPS, "0" );
        assertThrows( IllegalArgumentException.class, () -> GraphRerankConfig.fromProperties( p ) );
    }

    @Test
    void overridesApplied() {
        final Properties p = new Properties();
        p.setProperty( GraphRerankConfig.PROP_BOOST, "0.35" );
        p.setProperty( GraphRerankConfig.PROP_MAX_HOPS, "3" );
        p.setProperty( GraphRerankConfig.PROP_QUERY_ENTITY_CACHE_TTL, "60" );
        p.setProperty( GraphRerankConfig.PROP_QUERY_ENTITY_CACHE_MAX, "50" );
        p.setProperty( GraphRerankConfig.PROP_NEIGHBOR_INDEX_MAX_EDGES, "1000" );

        final GraphRerankConfig cfg = GraphRerankConfig.fromProperties( p );
        assertEquals( 0.35, cfg.boost(), 1e-9 );
        assertEquals( 3, cfg.maxHops() );
        assertEquals( 60, cfg.queryEntityCacheTtlSeconds() );
        assertEquals( 50, cfg.queryEntityCacheMax() );
        assertEquals( 1_000, cfg.neighborIndexMaxEdges() );
    }
}
