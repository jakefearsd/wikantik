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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;

/**
 * Configuration for the save-time entity extraction pipeline. All knobs live
 * under the {@code wikantik.knowledge.extractor.*} namespace; defaults are
 * chosen so a fresh deploy is a no-op until an operator enables extraction.
 *
 * @param backend              {@code "claude"}, {@code "ollama"}, or {@code "disabled"}
 * @param claudeModel          Anthropic model id used when backend is {@code "claude"}
 * @param ollamaModel          Ollama tag (e.g. {@code "llama3.1:8b"}) used for extraction
 * @param ollamaBaseUrl        Base URL for Ollama (e.g. {@code "http://inference.jakefear.com:11434"})
 * @param timeoutMs            Per-chunk extraction timeout
 * @param confidenceThreshold  Proposals below this are dropped, not filed
 * @param maxExistingNodes     Cap on existing-node dictionary size included in prompt
 * @param perPageMinIntervalMs Minimum interval between extraction runs for the same page
 * @param concurrency                  Max parallel extraction RPCs for the admin batch job
 * @param prefilterEnabled             Master switch; when {@code false} the prefilter is a no-op
 * @param prefilterDryRun              Log skips but do not actually suppress extraction
 * @param prefilterSkipPureCode        Skip pages whose content is predominantly code fences
 * @param prefilterSkipNoProperNoun    Skip pages with no detectable proper noun tokens
 * @param prefilterSkipTooShort        Skip chunks whose estimated token count is below {@code prefilterMinTokens}
 * @param prefilterMinTokens           Threshold for {@code prefilterSkipTooShort} (estimated tokens = chars/4)
 */
public record EntityExtractorConfig(
    String backend,
    String claudeModel,
    String ollamaModel,
    String ollamaBaseUrl,
    long timeoutMs,
    double confidenceThreshold,
    int maxExistingNodes,
    long perPageMinIntervalMs,
    int concurrency,
    boolean prefilterEnabled,
    boolean prefilterDryRun,
    boolean prefilterSkipPureCode,
    boolean prefilterSkipNoProperNoun,
    boolean prefilterSkipTooShort,
    int prefilterMinTokens
) {

    private static final Logger LOG = LogManager.getLogger( EntityExtractorConfig.class );

    public static final String BACKEND_DISABLED = "disabled";
    public static final String BACKEND_CLAUDE = "claude";
    public static final String BACKEND_OLLAMA = "ollama";

    public static final String PREFIX = "wikantik.knowledge.extractor.";

    /** Hard cap on {@code concurrency}; operator-friendly guard-rail against
     *  accidentally saturating the inference backend. Loosened from 4 to 10
     *  to support small-model experiments where the GPU can comfortably
     *  serve more in-flight requests than a 4B model allows. */
    public static final int CONCURRENCY_MAX = 10;

    public boolean enabled() {
        return !BACKEND_DISABLED.equalsIgnoreCase( backend );
    }

    public static EntityExtractorConfig fromProperties( final Properties props ) {
        return new EntityExtractorConfig(
            getString( props, "backend", BACKEND_DISABLED ),
            getString( props, "claude.model", "claude-haiku-4-5" ),
            getString( props, "ollama.model", "gemma4-assist:latest" ),
            getString( props, "ollama.base_url", "http://inference.jakefear.com:11434" ),
            getLong( props, "timeout_ms", 120_000L ),
            getDouble( props, "confidence_threshold", 0.6 ),
            getInt( props, "max_existing_nodes", 200 ),
            getLong( props, "per_page_min_interval_ms", 5_000L ),
            clampConcurrency( getInt( props, "concurrency", 2 ) ),
            getBoolean( props, "prefilter.enabled", false ),
            getBoolean( props, "prefilter.dry_run", false ),
            getBoolean( props, "prefilter.skip_pure_code", true ),
            getBoolean( props, "prefilter.skip_no_proper_noun", true ),
            getBoolean( props, "prefilter.skip_too_short", true ),
            getInt( props, "prefilter.min_tokens", 20 )
        );
    }

    /** Clamp concurrency to {@code [1, CONCURRENCY_MAX]}. Configured values
     *  outside the band are silently coerced so a typo in production can't
     *  open the floodgates to the inference host. */
    public static int clampConcurrency( final int raw ) {
        if( raw < 1 ) return 1;
        if( raw > CONCURRENCY_MAX ) return CONCURRENCY_MAX;
        return raw;
    }

    private static String getString( final Properties p, final String suffix, final String def ) {
        final String v = p == null ? null : p.getProperty( PREFIX + suffix );
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static long getLong( final Properties p, final String suffix, final long def ) {
        final String v = p == null ? null : p.getProperty( PREFIX + suffix );
        if( v == null || v.isBlank() ) {
            return def;
        }
        try {
            return Long.parseLong( v.trim() );
        } catch( final NumberFormatException e ) {
            LOG.info( "Ignoring non-numeric value '{}' for property {}{} — using default {}: {}",
                v, PREFIX, suffix, def, e.getMessage() );
            return def;
        }
    }

    private static int getInt( final Properties p, final String suffix, final int def ) {
        return (int) getLong( p, suffix, def );
    }

    private static double getDouble( final Properties p, final String suffix, final double def ) {
        final String v = p == null ? null : p.getProperty( PREFIX + suffix );
        if( v == null || v.isBlank() ) {
            return def;
        }
        try {
            return Double.parseDouble( v.trim() );
        } catch( final NumberFormatException e ) {
            LOG.info( "Ignoring non-numeric value '{}' for property {}{} — using default {}: {}",
                v, PREFIX, suffix, def, e.getMessage() );
            return def;
        }
    }

    private static boolean getBoolean( final Properties p, final String suffix, final boolean def ) {
        final String v = p == null ? null : p.getProperty( PREFIX + suffix );
        if( v == null || v.isBlank() ) {
            return def;
        }
        final String trimmed = v.trim();
        if( trimmed.equalsIgnoreCase( "true" ) || trimmed.equals( "1" ) || trimmed.equalsIgnoreCase( "yes" ) ) {
            return true;
        }
        if( trimmed.equalsIgnoreCase( "false" ) || trimmed.equals( "0" ) || trimmed.equalsIgnoreCase( "no" ) ) {
            return false;
        }
        LOG.info( "Ignoring non-boolean value '{}' for property {}{} — using default {}",
            v, PREFIX, suffix, def );
        return def;
    }
}
