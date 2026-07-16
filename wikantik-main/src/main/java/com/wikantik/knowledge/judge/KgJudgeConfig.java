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

import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.wikantik.api.config.GenAiMode;

/** Pure-data record holding the runtime config for the judge service + runner. */
public record KgJudgeConfig(
    boolean enabled,
    String endpoint,
    String model,
    boolean cronEnabled,
    int cronIntervalMinutes,
    int batchSize,
    int concurrency,
    int timeoutSeconds,
    int maxAttempts,
    String keepAlive
) {
    private static final Logger LOG = LogManager.getLogger( KgJudgeConfig.class );

    /** Hardcoded fallback Ollama endpoint, matching EmbeddingConfig.DEFAULT_BASE_URL
     *  and EntityExtractorConfig's default. Used only when neither the judge nor
     *  the extractor properties are set. */
    public static final String DEFAULT_ENDPOINT = "http://inference.jakefear.com:11434";

    /** Hardcoded fallback Ollama model, matching EntityExtractorConfig's default. */
    public static final String DEFAULT_MODEL = "gemma4-assist:latest";

    public static KgJudgeConfig fromProperties( final Properties p ) {
        // Fall back to the extractor's canonical property names (matches what
        // EntityExtractorConfig reads via "ollama.base_url" / "ollama.model").
        // The legacy ".endpoint" key is honoured too in case any deployment
        // adopted that name from an early draft of the docs. If everything is
        // unset, fall back to the inference.jakefear.com host the rest of the
        // stack ships against.
        final String extractorEndpoint = firstNonBlank(
            p.getProperty( "wikantik.knowledge.extractor.ollama.base_url" ),
            p.getProperty( "wikantik.knowledge.extractor.ollama.endpoint" ),
            DEFAULT_ENDPOINT );
        final String extractorModel    = firstNonBlank(
            p.getProperty( "wikantik.knowledge.extractor.ollama.model" ),
            DEFAULT_MODEL );

        // wikantik.genai.mode ceiling: the judge issues chat inference calls, so
        // it must be forced disabled whenever the mode disallows chat inference,
        // regardless of wikantik.kg.judge.enabled.
        final boolean rawEnabled = getBool( p, "wikantik.kg.judge.enabled", true );
        final GenAiMode mode = GenAiMode.fromProperties( p );
        final boolean enabled = rawEnabled && mode.allowsChatInference();
        if ( rawEnabled && !enabled ) {
            LOG.warn( "{}={} disallows chat inference; forcing KG judge disabled", GenAiMode.PROP, mode );
        }

        return new KgJudgeConfig(
            enabled,
            getString( p, "wikantik.kg.judge.endpoint",         extractorEndpoint ),
            getString( p, "wikantik.kg.judge.model",            extractorModel ),
            getBool( p,   "wikantik.kg.judge.cron.enabled",     true ),
            getInt( p,    "wikantik.kg.judge.cron.interval_min", 5 ),
            getInt( p,    "wikantik.kg.judge.batch_size",        50 ),
            // Default 1: empirically, parallel judge calls don't improve
            // throughput on a single-GPU Ollama backend (the model serializes
            // anyway) and contention appears to contribute to read-timeouts.
            getInt( p,    "wikantik.kg.judge.concurrency",       1 ),
            // 120s default: the prior 30s cap was below the typical first-call
            // cold-load latency of gemma-class models on shared inference hosts.
            // Aligns with wikantik.knowledge.extractor.timeout_ms=120000.
            getInt( p,    "wikantik.kg.judge.timeout_seconds",   120 ),
            getInt( p,    "wikantik.kg.judge.max_attempts",      3 ),
            // keep_alive longer than the cron interval keeps the Ollama model
            // resident across batches and avoids the unload-cliff that caused
            // every cron pass to lose its first 1-2 proposals.
            getString( p, "wikantik.kg.judge.keep_alive",         "30m" )
        );
    }

    private static String firstNonBlank( final String... values ) {
        for ( final String v : values ) {
            if ( v != null && !v.isBlank() ) return v.trim();
        }
        return null;
    }

    private static String getString( final Properties p, final String k, final String def ) {
        final String v = p.getProperty( k );
        return ( v == null || v.isBlank() ) ? def : v.trim();
    }
    private static boolean getBool( final Properties p, final String k, final boolean def ) {
        final String v = p.getProperty( k );
        return ( v == null || v.isBlank() ) ? def : Boolean.parseBoolean( v.trim() );
    }
    private static int getInt( final Properties p, final String k, final int def ) {
        final String v = p.getProperty( k );
        try { return ( v == null || v.isBlank() ) ? def : Integer.parseInt( v.trim() ); }
        catch ( final NumberFormatException e ) { return def; }
    }
}
