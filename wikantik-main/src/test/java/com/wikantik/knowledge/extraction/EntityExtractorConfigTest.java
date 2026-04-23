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
package com.wikantik.knowledge.extraction;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityExtractorConfigTest {

    @Test
    void defaultsDisabledOnEmptyProperties() {
        final EntityExtractorConfig cfg = EntityExtractorConfig.fromProperties( new Properties() );
        assertFalse( cfg.enabled(), "fresh deploys should not emit proposals" );
        assertEquals( EntityExtractorConfig.BACKEND_DISABLED, cfg.backend() );
        assertEquals( "claude-haiku-4-5", cfg.claudeModel() );
        assertEquals( "gemma4-assist:latest", cfg.ollamaModel() );
        assertEquals( 120_000L, cfg.timeoutMs() );
        assertEquals( 0.6, cfg.confidenceThreshold() );
        assertEquals( 200, cfg.maxExistingNodes() );
        assertEquals( 5_000L, cfg.perPageMinIntervalMs() );
        assertEquals( 2, cfg.concurrency() );
    }

    @Test
    void honoursAllOverrides() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "claude" );
        p.setProperty( "wikantik.knowledge.extractor.claude.model", "claude-sonnet-4-6" );
        p.setProperty( "wikantik.knowledge.extractor.ollama.model", "mistral" );
        p.setProperty( "wikantik.knowledge.extractor.ollama.base_url", "http://ollama:11434/" );
        p.setProperty( "wikantik.knowledge.extractor.timeout_ms", "12345" );
        p.setProperty( "wikantik.knowledge.extractor.confidence_threshold", "0.4" );
        p.setProperty( "wikantik.knowledge.extractor.max_existing_nodes", "50" );
        p.setProperty( "wikantik.knowledge.extractor.per_page_min_interval_ms", "0" );
        p.setProperty( "wikantik.knowledge.extractor.concurrency", "3" );

        final EntityExtractorConfig cfg = EntityExtractorConfig.fromProperties( p );
        assertTrue( cfg.enabled() );
        assertEquals( "claude", cfg.backend() );
        assertEquals( "claude-sonnet-4-6", cfg.claudeModel() );
        assertEquals( "mistral", cfg.ollamaModel() );
        assertEquals( "http://ollama:11434/", cfg.ollamaBaseUrl() );
        assertEquals( 12_345L, cfg.timeoutMs() );
        assertEquals( 0.4, cfg.confidenceThreshold() );
        assertEquals( 50, cfg.maxExistingNodes() );
        assertEquals( 0L, cfg.perPageMinIntervalMs() );
        assertEquals( 3, cfg.concurrency() );
    }

    @Test
    void concurrencyClampedToSafeBand() {
        final Properties hi = new Properties();
        hi.setProperty( "wikantik.knowledge.extractor.concurrency", "999" );
        assertEquals( EntityExtractorConfig.CONCURRENCY_MAX,
                EntityExtractorConfig.fromProperties( hi ).concurrency() );

        final Properties lo = new Properties();
        lo.setProperty( "wikantik.knowledge.extractor.concurrency", "0" );
        assertEquals( 1, EntityExtractorConfig.fromProperties( lo ).concurrency() );

        final Properties neg = new Properties();
        neg.setProperty( "wikantik.knowledge.extractor.concurrency", "-5" );
        assertEquals( 1, EntityExtractorConfig.fromProperties( neg ).concurrency() );
    }

    @Test
    void fallsBackToDefaultsOnBadNumericValues() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.timeout_ms", "not-a-number" );
        p.setProperty( "wikantik.knowledge.extractor.confidence_threshold", "also-bad" );

        final EntityExtractorConfig cfg = EntityExtractorConfig.fromProperties( p );
        assertEquals( 120_000L, cfg.timeoutMs() );
        assertEquals( 0.6, cfg.confidenceThreshold() );
    }

    @Test
    void nullPropertiesYieldsDefaults() {
        final EntityExtractorConfig cfg = EntityExtractorConfig.fromProperties( null );
        assertFalse( cfg.enabled() );
        assertEquals( "claude-haiku-4-5", cfg.claudeModel() );
    }
}
