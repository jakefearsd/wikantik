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
package com.wikantik.knowledge.judge;

import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class KgJudgeConfigTest {

    @Test
    void fromProperties_falls_back_to_extractor_settings_when_unset() {
        final Properties p = new Properties();
        // Legacy ".endpoint" key — honored for any deployment that adopted it from
        // an early draft of the docs.
        p.setProperty( "wikantik.knowledge.extractor.ollama.endpoint", "http://extractor:11434" );
        p.setProperty( "wikantik.knowledge.extractor.ollama.model", "gemma4-assist:latest" );

        final KgJudgeConfig cfg = KgJudgeConfig.fromProperties( p );

        assertEquals( "http://extractor:11434", cfg.endpoint() );
        assertEquals( "gemma4-assist:latest", cfg.model() );
    }

    @Test
    void fromProperties_falls_back_to_canonical_extractor_base_url_key() {
        // The real extractor reads "wikantik.knowledge.extractor.ollama.base_url"
        // (see EntityExtractorConfig). Judge fallback must accept that key too.
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.ollama.base_url",
            "http://inference.example.com:11434" );
        p.setProperty( "wikantik.knowledge.extractor.ollama.model", "gemma4-assist:latest" );

        final KgJudgeConfig cfg = KgJudgeConfig.fromProperties( p );

        assertEquals( "http://inference.example.com:11434", cfg.endpoint() );
        assertEquals( "gemma4-assist:latest", cfg.model() );
    }

    @Test
    void fromProperties_canonical_base_url_wins_when_both_legacy_and_canonical_set() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.ollama.base_url", "http://canonical:11434" );
        p.setProperty( "wikantik.knowledge.extractor.ollama.endpoint", "http://legacy:11434" );
        p.setProperty( "wikantik.knowledge.extractor.ollama.model", "gemma4-assist:latest" );

        final KgJudgeConfig cfg = KgJudgeConfig.fromProperties( p );

        assertEquals( "http://canonical:11434", cfg.endpoint(),
            "canonical base_url should take precedence over legacy endpoint" );
    }

    @Test
    void fromProperties_explicit_judge_settings_override_extractor_fallback() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.ollama.endpoint", "http://extractor:11434" );
        p.setProperty( "wikantik.knowledge.extractor.ollama.model", "gemma4-assist:latest" );
        p.setProperty( "wikantik.kg.judge.endpoint", "http://judge:11434" );
        p.setProperty( "wikantik.kg.judge.model", "gemma4-judge:latest" );

        final KgJudgeConfig cfg = KgJudgeConfig.fromProperties( p );

        assertEquals( "http://judge:11434", cfg.endpoint() );
        assertEquals( "gemma4-judge:latest", cfg.model() );
    }

    @Test
    void fromProperties_defaults_for_runtime_knobs() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.ollama.endpoint", "http://x" );
        p.setProperty( "wikantik.knowledge.extractor.ollama.model", "m" );

        final KgJudgeConfig cfg = KgJudgeConfig.fromProperties( p );
        assertTrue( cfg.enabled() );
        assertTrue( cfg.cronEnabled() );
        assertEquals( 5, cfg.cronIntervalMinutes() );
        assertEquals( 50, cfg.batchSize() );
        assertEquals( 2, cfg.concurrency() );
        assertEquals( 120, cfg.timeoutSeconds() );
        assertEquals( 3, cfg.maxAttempts() );
        assertEquals( "30m", cfg.keepAlive() );
    }

    @Test
    void fromProperties_falls_back_to_inference_jakefear_when_nothing_set() {
        // No extractor or judge properties at all — should use the hardcoded
        // defaults that match the rest of the stack (EmbeddingConfig,
        // EntityExtractorConfig).
        final Properties p = new Properties();

        final KgJudgeConfig cfg = KgJudgeConfig.fromProperties( p );

        assertEquals( KgJudgeConfig.DEFAULT_ENDPOINT, cfg.endpoint() );
        assertEquals( KgJudgeConfig.DEFAULT_MODEL,    cfg.model() );
        assertEquals( "http://inference.jakefear.com:11434", cfg.endpoint() );
        assertEquals( "gemma4-assist:latest",                cfg.model() );
    }

    @Test
    void fromProperties_blank_string_treated_as_unset() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.ollama.endpoint", "http://x" );
        p.setProperty( "wikantik.knowledge.extractor.ollama.model", "m" );
        p.setProperty( "wikantik.kg.judge.batch_size", "" );

        final KgJudgeConfig cfg = KgJudgeConfig.fromProperties( p );
        assertEquals( 50, cfg.batchSize() );
    }
}
