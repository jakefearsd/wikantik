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

import com.wikantik.api.config.GenAiMode;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code wikantik.genai.mode} acts as a ceiling on the entity extractor:
 * chat inference (the extractor backend) is disabled whenever the mode disallows
 * chat inference, regardless of the extractor's own {@code backend} setting.
 */
class EntityExtractorConfigModeTest {

    @Test
    void modeAbsent_behavesIdenticallyToToday() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "claude" );

        final EntityExtractorConfig cfg = EntityExtractorConfig.fromProperties( p );

        assertTrue( cfg.enabled(), "no genai.mode set -> regression guard: unchanged behavior" );
        assertEquals( "claude", cfg.backend() );
    }

    @Test
    void embeddingsOnly_disablesExtractorEvenWhenBackendConfigured() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "claude" );
        p.setProperty( GenAiMode.PROP, "embeddings-only" );

        final EntityExtractorConfig cfg = EntityExtractorConfig.fromProperties( p );

        assertFalse( cfg.enabled(), "embeddings-only must disable chat-inference extractor even with backend=claude" );
        assertEquals( EntityExtractorConfig.BACKEND_DISABLED, cfg.backend() );
    }

    @Test
    void none_disablesExtractor() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
        p.setProperty( GenAiMode.PROP, "none" );

        final EntityExtractorConfig cfg = EntityExtractorConfig.fromProperties( p );

        assertFalse( cfg.enabled(), "none must disable the extractor" );
        assertEquals( EntityExtractorConfig.BACKEND_DISABLED, cfg.backend() );
    }

    @Test
    void individualFlagOff_staysOffUnderFullMode() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "disabled" );
        p.setProperty( GenAiMode.PROP, "full" );

        final EntityExtractorConfig cfg = EntityExtractorConfig.fromProperties( p );

        assertFalse( cfg.enabled(), "backend=disabled under mode=full must remain disabled" );
    }
}
