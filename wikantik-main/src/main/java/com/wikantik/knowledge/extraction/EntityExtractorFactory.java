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

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.wikantik.api.knowledge.EntityExtractor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * Selects a concrete {@link EntityExtractor} from configuration. Returns empty
 * when the backend is {@code disabled} or when the selected backend is missing
 * credentials / network access — the listener then silently skips enqueuing
 * so a mis-configured deploy is a no-op, not a crash.
 */
public final class EntityExtractorFactory {

    private static final Logger LOG = LogManager.getLogger( EntityExtractorFactory.class );

    private EntityExtractorFactory() {}

    public static Optional< EntityExtractor > create( final EntityExtractorConfig config ) {
        return create( config, System::getenv );
    }

    /**
     * Seam overload: identical to {@link #create(EntityExtractorConfig)} but with an
     * injectable environment lookup, so tests can drive the {@code claude} branch
     * deterministically without a real {@code ANTHROPIC_API_KEY} in the process
     * environment. Public only because the wiring caller
     * ({@code com.wikantik.knowledge.subsystem.KnowledgeWiringHelper}) lives in a
     * sibling package — treat it as a test/wiring seam, not general API; production
     * entry points bind {@code System::getenv} via the single-argument overload.
     */
    public static Optional< EntityExtractor > create( final EntityExtractorConfig config,
                                                      final Function< String, String > getenv ) {
        if( !config.enabled() ) {
            return Optional.empty();
        }
        switch( config.backend().toLowerCase( java.util.Locale.ROOT ) ) {
            case EntityExtractorConfig.BACKEND_CLAUDE:
                return createClaude( config, getenv );
            case EntityExtractorConfig.BACKEND_OLLAMA:
                return createOllama( config );
            default:
                LOG.warn( "Unknown extractor backend '{}'; extraction disabled", config.backend() );
                return Optional.empty();
        }
    }

    private static Optional< EntityExtractor > createClaude( final EntityExtractorConfig config,
                                                             final Function< String, String > getenv ) {
        final String key = getenv.apply( "ANTHROPIC_API_KEY" );
        if( key == null || key.isBlank() ) {
            LOG.warn( "ANTHROPIC_API_KEY not set; Claude extractor disabled" );
            return Optional.empty();
        }
        try {
            final AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey( key )
                .build();
            return Optional.of( new ClaudeEntityExtractor( client, config ) );
        } catch( final RuntimeException e ) {
            LOG.warn( "Failed to construct Claude client: {}", e.getMessage() );
            return Optional.empty();
        }
    }

    private static Optional< EntityExtractor > createOllama( final EntityExtractorConfig config ) {
        final HttpClient http = HttpClient.newBuilder()
            .connectTimeout( Duration.ofSeconds( 5 ) )
            .build();
        return Optional.of( new OllamaEntityExtractor( http, config ) );
    }
}
