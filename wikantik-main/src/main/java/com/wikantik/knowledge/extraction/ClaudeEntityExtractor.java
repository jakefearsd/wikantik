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
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.wikantik.api.knowledge.EntityExtractor;
import com.wikantik.api.knowledge.ExtractionChunk;
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.ExtractionResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.List;

/**
 * Extractor backed by the Anthropic Claude API. Uses prompt caching on the
 * frozen system prompt + the existing-node dictionary so repeated extractions
 * across a page-save burst share a cache prefix (5-minute TTL, matches the
 * save cadence well).
 *
 * <p>The chunk text is the only volatile part of each request, placed after
 * the cache breakpoints. That ordering is load-bearing for caching — do not
 * reshuffle without re-reading the {@code claude-api} skill's guidance on
 * prefix-match invalidation.
 *
 * <p>Never throws. Timeouts, API errors and malformed JSON all translate to
 * an empty {@link ExtractionResult} — the listener catches these via the
 * empty-result convention and keeps processing subsequent chunks.
 */
public class ClaudeEntityExtractor implements EntityExtractor {

    private static final Logger LOG = LogManager.getLogger( ClaudeEntityExtractor.class );

    public static final String CODE = EntityExtractorConfig.BACKEND_CLAUDE;
    private static final long MAX_OUTPUT_TOKENS = 4_096L;

    private final AnthropicClient client;
    private final EntityExtractorConfig config;

    public ClaudeEntityExtractor( final AnthropicClient client, final EntityExtractorConfig config ) {
        this.client = client;
        this.config = config;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public ExtractionResult extract( final ExtractionChunk chunk, final ExtractionContext context ) {
        final long started = System.nanoTime();
        try {
            final String json = callClaude( chunk, context );
            final Duration latency = Duration.ofNanos( System.nanoTime() - started );
            if( json == null || json.isBlank() ) {
                return ExtractionResult.empty( CODE, latency );
            }
            return ExtractionResponseParser.parse(
                json, chunk, context, CODE, latency, config.confidenceThreshold() );
        } catch( final RuntimeException e ) {
            LOG.warn( "Claude extraction failed for chunk {}: {}", chunk.id(), e.getMessage() );
            return ExtractionResult.empty( CODE, Duration.ofNanos( System.nanoTime() - started ) );
        }
    }

    /**
     * Visible for testing — lets a subclass swap in a deterministic response
     * without mocking the Kotlin-backed SDK classes that expose final methods.
     */
    protected String callClaude( final ExtractionChunk chunk, final ExtractionContext context ) {
        final String nodeDict = ExtractionPromptBuilder.existingNodesDictionary(
            context, config.maxExistingNodes() );
        final String userPrompt = ExtractionPromptBuilder.buildUserPrompt(
            chunk, context, config.maxExistingNodes() );

        // System blocks: frozen task prompt + node dictionary. Both carry a cache
        // breakpoint so a burst of page saves rides the same cached prefix.
        final TextBlockParam systemBase = TextBlockParam.builder()
            .text( ExtractionPromptBuilder.SYSTEM_PROMPT )
            .cacheControl( CacheControlEphemeral.builder().build() )
            .build();

        final List< TextBlockParam > systemBlocks;
        if( nodeDict.isEmpty() ) {
            systemBlocks = List.of( systemBase );
        } else {
            final TextBlockParam nodeDictBlock = TextBlockParam.builder()
                .text( "Known entities (name :: type):\n" + nodeDict )
                .cacheControl( CacheControlEphemeral.builder().build() )
                .build();
            systemBlocks = List.of( systemBase, nodeDictBlock );
        }

        final MessageCreateParams params = MessageCreateParams.builder()
            .model( config.claudeModel() )
            .maxTokens( MAX_OUTPUT_TOKENS )
            .systemOfTextBlockParams( systemBlocks )
            .addUserMessage( userPrompt )
            .build();

        final Message response = client.messages().create( params );
        final StringBuilder sb = new StringBuilder( 512 );
        for( final ContentBlock block : response.content() ) {
            block.text().ifPresent( t -> sb.append( t.text() ) );
        }
        return sb.toString();
    }
}
