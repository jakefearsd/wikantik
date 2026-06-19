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

import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class InjectionConfigTest {
    @Test
    void defaultsWhenUnset() {
        final InjectionConfig c = InjectionConfig.fromProperties(new Properties());
        assertFalse(c.enabled());
        assertEquals(20, c.bm25RankMax());
        assertEquals(50, c.denseColdMin());
        assertEquals(0.3, c.scoreFrac(), 1e-9);
        assertEquals(3, c.maxInject());
        assertEquals(3, c.position());
        assertTrue(c.symbolBoost());
        assertEquals(50, c.jBoost());
        assertEquals(0.1, c.alphaBoost(), 1e-9);
        assertEquals(300, c.denseScanK());
    }
    @Test
    void overridesParsed() {
        final Properties p = new Properties();
        p.setProperty("wikantik.bundle.inject.enabled", "true");
        p.setProperty("wikantik.bundle.inject.bm25_rank_max", "10");
        p.setProperty("wikantik.bundle.inject.score_frac", "0.5");
        p.setProperty("wikantik.bundle.inject.symbol_boost", "false");
        final InjectionConfig c = InjectionConfig.fromProperties(p);
        assertTrue(c.enabled());
        assertEquals(10, c.bm25RankMax());
        assertEquals(0.5, c.scoreFrac(), 1e-9);
        assertFalse(c.symbolBoost());
    }
    @Test
    void malformedFallsBackToDefault() {
        final Properties p = new Properties();
        p.setProperty("wikantik.bundle.inject.bm25_rank_max", "notanumber");
        assertEquals(20, InjectionConfig.fromProperties(p).bm25RankMax());
    }

    @Test
    void nullPropertiesYieldsAllDefaults() {
        final InjectionConfig c = InjectionConfig.fromProperties(null);
        assertFalse(c.enabled());
        assertEquals(20, c.bm25RankMax());
        assertEquals(50, c.denseColdMin());
        assertEquals(0.3, c.scoreFrac(), 1e-9);
        assertEquals(3, c.maxInject());
        assertEquals(3, c.position());
        assertTrue(c.symbolBoost());
        assertEquals(50, c.jBoost());
        assertEquals(0.1, c.alphaBoost(), 1e-9);
        assertEquals(300, c.denseScanK());
    }

    @Test
    void malformedDoubleFallsBackToDefault() {  // exercises the dbl() warn-and-default branch
        final Properties p = new Properties();
        p.setProperty("wikantik.bundle.inject.score_frac", "notadouble");
        assertEquals(0.3, InjectionConfig.fromProperties(p).scoreFrac(), 1e-9);
    }
}
