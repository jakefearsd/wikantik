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

import java.util.Locale;
import java.util.Properties;

/**
 * Flag-gated configuration for the text embedding client. The master flag
 * {@link #PROP_ENABLED} defaults to {@code false} — when it is off the factory
 * refuses to build any client, nothing is wired at startup, and there is no
 * background cost.
 *
 * @param enabled        master on/off switch ({@link #PROP_ENABLED}, default {@code false})
 * @param backend        which backend implementation to select ({@link #PROP_BACKEND}, default {@code "ollama"})
 * @param baseUrl        root URL of the embedding server ({@link #PROP_BASE_URL})
 * @param apiKey         optional bearer credential ({@link #PROP_API_KEY})
 * @param model          which {@link EmbeddingModel} to load ({@link #PROP_MODEL})
 * @param ollamaTagOverride optional override of {@link EmbeddingModel#defaultOllamaTag()} ({@link #PROP_OLLAMA_TAG})
 * @param timeoutMs      per-request HTTP timeout in milliseconds ({@link #PROP_TIMEOUT_MS})
 * @param batchSize      maximum number of texts per backend round-trip ({@link #PROP_BATCH_SIZE})
 */
public record EmbeddingConfig(
    boolean enabled,
    String backend,
    String baseUrl,
    String apiKey,
    EmbeddingModel model,
    String ollamaTagOverride,
    int timeoutMs,
    int batchSize
) {

    public static final String PROP_ENABLED      = "wikantik.search.hybrid.enabled";
    public static final String PROP_BACKEND      = "wikantik.search.embedding.backend";
    public static final String PROP_BASE_URL     = "wikantik.search.embedding.base-url";
    public static final String PROP_API_KEY      = "wikantik.search.embedding.api-key";
    public static final String PROP_MODEL        = "wikantik.search.embedding.model";
    public static final String PROP_OLLAMA_TAG   = "wikantik.search.embedding.ollama-tag";
    public static final String PROP_TIMEOUT_MS   = "wikantik.search.embedding.timeout-ms";
    public static final String PROP_BATCH_SIZE   = "wikantik.search.embedding.batch-size";

    public static final String BACKEND_OLLAMA = "ollama";

    public static final String  DEFAULT_BACKEND    = BACKEND_OLLAMA;
    public static final String  DEFAULT_BASE_URL   = "http://inference.jakefear.com:11434";
    public static final String  DEFAULT_MODEL_CODE = "nomic-embed-v1.5";
    public static final int     DEFAULT_TIMEOUT_MS = 30_000;
    public static final int     DEFAULT_BATCH_SIZE = 32;

    public EmbeddingConfig {
        if( backend == null || backend.isBlank() ) {
            throw new IllegalArgumentException( PROP_BACKEND + " must not be blank" );
        }
        if( baseUrl == null || baseUrl.isBlank() ) {
            throw new IllegalArgumentException( PROP_BASE_URL + " must not be blank" );
        }
        if( model == null ) {
            throw new IllegalArgumentException( PROP_MODEL + " must not be null" );
        }
        if( timeoutMs <= 0 ) {
            throw new IllegalArgumentException( PROP_TIMEOUT_MS + " must be positive, got " + timeoutMs );
        }
        if( batchSize <= 0 ) {
            throw new IllegalArgumentException( PROP_BATCH_SIZE + " must be positive, got " + batchSize );
        }
    }

    /** Reads config from wiki properties, applying defaults for absent keys. */
    public static EmbeddingConfig fromProperties( final Properties props ) {
        final boolean enabled = parseBoolean( props.getProperty( PROP_ENABLED ), false );
        final String  backend = trimOrDefault( props.getProperty( PROP_BACKEND ), DEFAULT_BACKEND )
                                  .toLowerCase( Locale.ROOT );
        final String  baseUrl = stripTrailingSlash(
                                    trimOrDefault( props.getProperty( PROP_BASE_URL ), DEFAULT_BASE_URL ) );
        final String  apiKey  = trimOrNull( props.getProperty( PROP_API_KEY ) );
        final EmbeddingModel model = EmbeddingModel.fromCode(
                                         trimOrDefault( props.getProperty( PROP_MODEL ), DEFAULT_MODEL_CODE ) );
        final String  tagOverride = trimOrNull( props.getProperty( PROP_OLLAMA_TAG ) );
        final int     timeoutMs = parsePositiveInt( props.getProperty( PROP_TIMEOUT_MS ),
                                                    DEFAULT_TIMEOUT_MS, PROP_TIMEOUT_MS );
        final int     batchSize = parsePositiveInt( props.getProperty( PROP_BATCH_SIZE ),
                                                    DEFAULT_BATCH_SIZE, PROP_BATCH_SIZE );
        return new EmbeddingConfig( enabled, backend, baseUrl, apiKey, model,
                                    tagOverride, timeoutMs, batchSize );
    }

    /** The Ollama model tag this config should use, respecting any override. */
    public String resolvedOllamaTag() {
        return ollamaTagOverride != null ? ollamaTagOverride : model.defaultOllamaTag();
    }

    private static String trimOrDefault( final String value, final String fallback ) {
        if( value == null ) return fallback;
        final String t = value.trim();
        return t.isEmpty() ? fallback : t;
    }

    private static String trimOrNull( final String value ) {
        if( value == null ) return null;
        final String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean parseBoolean( final String value, final boolean fallback ) {
        if( value == null ) return fallback;
        final String t = value.trim();
        if( t.isEmpty() ) return fallback;
        return Boolean.parseBoolean( t );
    }

    private static int parsePositiveInt( final String value, final int fallback, final String key ) {
        if( value == null || value.trim().isEmpty() ) return fallback;
        final int parsed;
        try {
            parsed = Integer.parseInt( value.trim() );
        } catch( final NumberFormatException e ) {
            throw new IllegalArgumentException( key + " must be an integer, got: " + value, e );
        }
        if( parsed <= 0 ) {
            throw new IllegalArgumentException( key + " must be positive, got " + parsed );
        }
        return parsed;
    }

    private static String stripTrailingSlash( final String s ) {
        return s.endsWith( "/" ) ? s.substring( 0, s.length() - 1 ) : s;
    }
}
