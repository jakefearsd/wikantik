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

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingConfigTest {

    @Test
    void emptyPropertiesYieldDisabledDefaults() {
        final EmbeddingConfig c = EmbeddingConfig.fromProperties( new Properties() );
        assertFalse( c.enabled() );
        assertEquals( EmbeddingConfig.DEFAULT_BACKEND, c.backend() );
        assertEquals( EmbeddingConfig.DEFAULT_BASE_URL, c.baseUrl() );
        assertNull( c.apiKey() );
        assertEquals( EmbeddingModel.QWEN3_EMBEDDING_06B, c.model() );
        assertNull( c.ollamaTagOverride() );
        assertEquals( EmbeddingConfig.DEFAULT_TIMEOUT_MS, c.timeoutMs() );
        assertEquals( EmbeddingConfig.DEFAULT_BATCH_SIZE, c.batchSize() );
    }

    @Test
    void explicitPropertiesOverrideDefaults() {
        final Properties p = new Properties();
        p.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
        p.setProperty( EmbeddingConfig.PROP_BASE_URL, "http://ollama.local:11434/" );
        // Sanity: the default differs, so this test actually exercises override.
        assertEquals( "http://inference.jakefear.com:11434", EmbeddingConfig.DEFAULT_BASE_URL );
        p.setProperty( EmbeddingConfig.PROP_API_KEY, "  s3cret  " );
        p.setProperty( EmbeddingConfig.PROP_MODEL, "bge-m3" );
        p.setProperty( EmbeddingConfig.PROP_OLLAMA_TAG, "bge-m3:latest" );
        p.setProperty( EmbeddingConfig.PROP_TIMEOUT_MS, "5000" );
        p.setProperty( EmbeddingConfig.PROP_BATCH_SIZE, "16" );
        final EmbeddingConfig c = EmbeddingConfig.fromProperties( p );
        assertTrue( c.enabled() );
        assertEquals( "http://ollama.local:11434", c.baseUrl(), "trailing slash must be stripped" );
        assertEquals( "s3cret", c.apiKey() );
        assertEquals( EmbeddingModel.BGE_M3, c.model() );
        assertEquals( "bge-m3:latest", c.resolvedOllamaTag() );
        assertEquals( 5000, c.timeoutMs() );
        assertEquals( 16, c.batchSize() );
    }

    @Test
    void defaultProfileIsCpuAndKeepsTheCpuSafeBatchSize() {
        // Absent profile must behave exactly as before: the bundled CPU embedder
        // cannot finish a large batch inside timeout-ms, so the conservative
        // batch size is the safe default for anyone without a GPU.
        final EmbeddingConfig c = EmbeddingConfig.fromProperties( new Properties() );
        assertEquals( EmbeddingConfig.PROFILE_CPU, c.profile() );
        assertEquals( EmbeddingConfig.DEFAULT_BATCH_SIZE, c.batchSize() );
    }

    @Test
    void gpuProfileRaisesTheBatchSizeDefault() {
        // Measured against the GPU inference host: batch 64 is the throughput knee
        // (9.5 ms/item vs 59.9 sequential); 128+ gains nothing and only widens the
        // timeout exposure.
        final Properties p = new Properties();
        p.setProperty( EmbeddingConfig.PROP_PROFILE, EmbeddingConfig.PROFILE_GPU );
        final EmbeddingConfig c = EmbeddingConfig.fromProperties( p );
        assertEquals( EmbeddingConfig.PROFILE_GPU, c.profile() );
        assertEquals( EmbeddingConfig.GPU_BATCH_SIZE, c.batchSize() );
        assertTrue( c.batchSize() > EmbeddingConfig.DEFAULT_BATCH_SIZE,
            "the gpu profile must raise the batch default, or it is pointless" );
    }

    @Test
    void explicitBatchSizeBeatsTheProfile() {
        // The profile only supplies defaults. An operator who has measured their
        // own hardware must still win.
        final Properties p = new Properties();
        p.setProperty( EmbeddingConfig.PROP_PROFILE, EmbeddingConfig.PROFILE_GPU );
        p.setProperty( EmbeddingConfig.PROP_BATCH_SIZE, "7" );
        assertEquals( 7, EmbeddingConfig.fromProperties( p ).batchSize() );
    }

    @Test
    void unrecognisedProfileFallsBackToCpuRatherThanFailing() {
        // Fail safe, not closed: a typo must not hand a CPU-only box a batch size
        // it cannot complete inside the timeout.
        final Properties p = new Properties();
        p.setProperty( EmbeddingConfig.PROP_PROFILE, "gpu-ish" );
        final EmbeddingConfig c = EmbeddingConfig.fromProperties( p );
        assertEquals( EmbeddingConfig.PROFILE_CPU, c.profile() );
        assertEquals( EmbeddingConfig.DEFAULT_BATCH_SIZE, c.batchSize() );
    }

    @Test
    void commitBatchSizeDefaultsWhenAbsent() {
        assertEquals( EmbeddingConfig.DEFAULT_COMMIT_BATCH_SIZE,
            EmbeddingConfig.fromProperties( new Properties() ).commitBatchSize() );
    }

    @Test
    void commitBatchSizeReadsConfiguredValue() {
        final Properties p = new Properties();
        p.setProperty( EmbeddingConfig.PROP_COMMIT_BATCH_SIZE, "512" );
        assertEquals( 512, EmbeddingConfig.fromProperties( p ).commitBatchSize() );
    }

    @Test
    void nonPositiveCommitBatchSizeIsRejected() {
        final Properties p = new Properties();
        p.setProperty( EmbeddingConfig.PROP_COMMIT_BATCH_SIZE, "0" );
        // Fails fast rather than silently defaulting, matching every other numeric
        // property here — a commit interval of 0 would otherwise mean "never commit",
        // which is the exact bug this setting exists to prevent.
        assertThrows( IllegalArgumentException.class,
            () -> EmbeddingConfig.fromProperties( p ) );
    }

    @Test
    void missingTagOverrideFallsBackToModelDefault() {
        final Properties p = new Properties();
        p.setProperty( EmbeddingConfig.PROP_MODEL, "qwen3-embedding-0.6b" );
        final EmbeddingConfig c = EmbeddingConfig.fromProperties( p );
        assertEquals( EmbeddingModel.QWEN3_EMBEDDING_06B.defaultOllamaTag(), c.resolvedOllamaTag() );
    }

    @Test
    void invalidNumericPropertiesAreRejected() {
        final Properties p = new Properties();
        p.setProperty( EmbeddingConfig.PROP_TIMEOUT_MS, "not-a-number" );
        assertThrows( IllegalArgumentException.class, () -> EmbeddingConfig.fromProperties( p ) );

        p.setProperty( EmbeddingConfig.PROP_TIMEOUT_MS, "0" );
        assertThrows( IllegalArgumentException.class, () -> EmbeddingConfig.fromProperties( p ) );

        p.clear();
        p.setProperty( EmbeddingConfig.PROP_BATCH_SIZE, "-1" );
        assertThrows( IllegalArgumentException.class, () -> EmbeddingConfig.fromProperties( p ) );
    }

    @Test
    void unknownModelIsRejected() {
        final Properties p = new Properties();
        p.setProperty( EmbeddingConfig.PROP_MODEL, "gpt-4" );
        assertThrows( IllegalArgumentException.class, () -> EmbeddingConfig.fromProperties( p ) );
    }
}
