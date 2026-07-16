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
package com.wikantik.search.embedding;

import com.wikantik.api.config.GenAiMode;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code wikantik.genai.mode} acts as a ceiling on embeddings: unlike
 * the chat-inference seams, {@code embeddings-only} must leave embeddings
 * enabled (per {@link GenAiMode#allowsEmbeddings()}) — only {@code none} turns
 * embeddings off.
 */
class EmbeddingConfigModeTest {

    @Test
    void modeAbsent_behavesIdenticallyToToday() {
        final Properties p = new Properties();
        p.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );

        final EmbeddingConfig cfg = EmbeddingConfig.fromProperties( p );

        assertTrue( cfg.enabled(), "no genai.mode set -> regression guard: unchanged behavior" );
    }

    @Test
    void embeddingsOnly_leavesEmbeddingsEnabled() {
        final Properties p = new Properties();
        p.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
        p.setProperty( GenAiMode.PROP, "embeddings-only" );

        final EmbeddingConfig cfg = EmbeddingConfig.fromProperties( p );

        assertTrue( cfg.enabled(), "embeddings-only must NOT disable embeddings (only chat inference)" );
    }

    @Test
    void none_disablesEmbeddings() {
        final Properties p = new Properties();
        p.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
        p.setProperty( GenAiMode.PROP, "none" );

        final EmbeddingConfig cfg = EmbeddingConfig.fromProperties( p );

        assertFalse( cfg.enabled(), "none must disable embeddings" );
    }

    @Test
    void individualFlagOff_staysOffUnderFullMode() {
        final Properties p = new Properties();
        p.setProperty( EmbeddingConfig.PROP_ENABLED, "false" );
        p.setProperty( GenAiMode.PROP, "full" );

        final EmbeddingConfig cfg = EmbeddingConfig.fromProperties( p );

        assertFalse( cfg.enabled(), "hybrid.enabled=false under mode=full must remain disabled" );
    }
}
