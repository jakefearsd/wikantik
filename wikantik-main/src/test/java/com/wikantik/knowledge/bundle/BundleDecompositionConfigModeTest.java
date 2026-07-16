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

import com.wikantik.api.config.GenAiMode;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code wikantik.genai.mode} acts as a ceiling on the bundle's LLM
 * query-decomposition planner: it must be disabled whenever the mode disallows
 * chat inference, regardless of {@code wikantik.bundle.decomposition.enabled}.
 */
class BundleDecompositionConfigModeTest {

    @Test
    void modeAbsent_behavesIdenticallyToToday() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.decomposition.enabled", "true" );

        final BundleDecompositionConfig cfg = BundleDecompositionConfig.fromProperties( p );

        assertTrue( cfg.enabled(), "no genai.mode set -> regression guard: unchanged behavior" );
    }

    @Test
    void embeddingsOnly_disablesDecompositionEvenWhenFlagEnabled() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.decomposition.enabled", "true" );
        p.setProperty( GenAiMode.PROP, "embeddings-only" );

        final BundleDecompositionConfig cfg = BundleDecompositionConfig.fromProperties( p );

        assertFalse( cfg.enabled(), "embeddings-only must disable decomposition even with decomposition.enabled=true" );
    }

    @Test
    void none_disablesDecomposition() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.decomposition.enabled", "true" );
        p.setProperty( GenAiMode.PROP, "none" );

        final BundleDecompositionConfig cfg = BundleDecompositionConfig.fromProperties( p );

        assertFalse( cfg.enabled(), "none must disable decomposition" );
    }

    @Test
    void individualFlagOff_staysOffUnderFullMode() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.decomposition.enabled", "false" );
        p.setProperty( GenAiMode.PROP, "full" );

        final BundleDecompositionConfig cfg = BundleDecompositionConfig.fromProperties( p );

        assertFalse( cfg.enabled(), "decomposition.enabled=false under mode=full must remain disabled" );
    }
}
