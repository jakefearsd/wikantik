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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

/**
 * Builds a {@link TextEmbeddingClient} from an {@link EmbeddingConfig}, or
 * returns {@link Optional#empty()} when the master flag is off. The factory is
 * the single decision point for backend selection so that callers never see
 * backend types directly — the rest of the system holds an
 * {@code Optional<TextEmbeddingClient>} and nothing more.
 */
public final class EmbeddingClientFactory {

    private static final Logger LOG = LogManager.getLogger( EmbeddingClientFactory.class );

    private EmbeddingClientFactory() {}

    /** Creates a client using a default {@link HttpClient} sized to the config timeout. */
    public static Optional< TextEmbeddingClient > create( final EmbeddingConfig config ) {
        return create( config, defaultHttpClient( config ) );
    }

    /** Creates a client using the given {@link HttpClient} (used by tests to inject stubs). */
    public static Optional< TextEmbeddingClient > create( final EmbeddingConfig config,
                                                          final HttpClient httpClient ) {
        if( config == null ) {
            throw new IllegalArgumentException( "config must not be null" );
        }
        if( !config.enabled() ) {
            LOG.info( "Hybrid embedding disabled ({}=false); no embedding client created",
                      EmbeddingConfig.PROP_ENABLED );
            return Optional.empty();
        }
        return Optional.of( build( config, httpClient ) );
    }

    private static TextEmbeddingClient build( final EmbeddingConfig config, final HttpClient httpClient ) {
        switch( config.backend() ) {
            case EmbeddingConfig.BACKEND_OLLAMA:
                LOG.info( "Embedding client: backend=ollama model={} tag={} baseUrl={}",
                          config.model().code(), config.resolvedOllamaTag(), config.baseUrl() );
                return new OllamaEmbeddingClient( httpClient, config );
            default:
                throw new IllegalArgumentException( "Unsupported embedding backend: " + config.backend()
                    + " (supported: " + EmbeddingConfig.BACKEND_OLLAMA + ")" );
        }
    }

    private static HttpClient defaultHttpClient( final EmbeddingConfig config ) {
        return HttpClient.newBuilder()
            .connectTimeout( Duration.ofMillis( Math.min( config.timeoutMs(), 10_000 ) ) )
            .build();
    }
}
