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
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;
import com.wikantik.api.knowledge.ExtractionChunk;
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.ExtractionResult;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ClaudeEntityExtractor}. We subclass rather than mock
 * the Kotlin-backed SDK types — {@code TextBlock.text()} is final and
 * Mockito's inline maker balks on Kotlin intrinsics. The subclass stubs out
 * {@code callClaude} so we can drive the extractor's contract without going
 * near the transport layer.
 *
 * <p>HTTP-level concerns (automatic 429 / 5xx retry, OkHttp timeout) are
 * properties of the Anthropic Java SDK and are covered by that project's own
 * tests — re-asserting them here would just pin us to SDK internals.
 */
class ClaudeEntityExtractorTest {

    private static EntityExtractorConfig config() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "claude" );
        p.setProperty( "wikantik.knowledge.extractor.claude.model", "claude-haiku-4-5" );
        p.setProperty( "wikantik.knowledge.extractor.confidence_threshold", "0.6" );
        return EntityExtractorConfig.fromProperties( p );
    }

    private static ExtractionChunk chunk() {
        return new ExtractionChunk( UUID.randomUUID(), "Page", 0, List.of(), "Napoleon at Waterloo." );
    }

    private static ExtractionContext context() {
        return new ExtractionContext( "Page", List.of(), java.util.Map.of() );
    }

    private static KgNode node( final String name, final String type ) {
        return new KgNode( UUID.randomUUID(), name, type, null,
                Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now(), "human", null );
    }

    /**
     * Builds a real (not mocked) SDK {@link Message} carrying a single text block — the
     * Kotlin-backed response types can't be Mockito-mocked (see class javadoc), but they can
     * be constructed via their public builders, which is enough to drive the real
     * {@link ClaudeEntityExtractor#callClaude} request-building + response-extraction code
     * end to end.
     */
    private static Message fakeMessage( final String responseText ) {
        final TextBlock textBlock = TextBlock.builder()
            .text( responseText )
            .citations( List.of() )
            .build();
        return Message.builder()
            .id( "msg_test" )
            .container( Optional.empty() )
            .content( List.of( ContentBlock.ofText( textBlock ) ) )
            .model( Model.CLAUDE_HAIKU_4_5 )
            .role( JsonValue.from( "assistant" ) )
            .stopDetails( Optional.empty() )
            .stopReason( Optional.empty() )
            .stopSequence( Optional.empty() )
            .type( JsonValue.from( "message" ) )
            .usage( Usage.builder()
                .inputTokens( 10 )
                .outputTokens( 5 )
                .cacheCreation( Optional.empty() )
                .cacheCreationInputTokens( Optional.empty() )
                .cacheReadInputTokens( Optional.empty() )
                .inferenceGeo( Optional.empty() )
                .outputTokensDetails( Optional.empty() )
                .serverToolUse( Optional.empty() )
                .serviceTier( Optional.empty() )
                .build() )
            .build();
    }

    /** A real {@link AnthropicClient} mock wired to return the given canned response. */
    private static AnthropicClient clientReturning( final Message response ) {
        final MessageService messageService = mock( MessageService.class );
        when( messageService.create( any( MessageCreateParams.class ) ) ).thenReturn( response );
        final AnthropicClient client = mock( AnthropicClient.class );
        when( client.messages() ).thenReturn( messageService );
        return client;
    }

    /** Subclass that lets the test script the API response text. */
    private static final class TestableExtractor extends ClaudeEntityExtractor {
        private final BiFunction< ExtractionChunk, ExtractionContext, String > responder;

        TestableExtractor( final BiFunction< ExtractionChunk, ExtractionContext, String > responder ) {
            super( mock( AnthropicClient.class ), config() );
            this.responder = responder;
        }

        @Override
        protected String callClaude( final ExtractionChunk chunk, final ExtractionContext ctx ) {
            return responder.apply( chunk, ctx );
        }
    }

    @Test
    void parsesSuccessResponseThroughSharedParser() {
        final ExtractionResult r = new TestableExtractor( ( c, ctx ) ->
            "{\"entities\":[{\"name\":\"Napoleon\",\"type\":\"Person\",\"confidence\":0.9}],\"relations\":[]}" )
            .extract( chunk(), context() );
        assertEquals( 1, r.mentions().size() );
        assertEquals( "claude", r.extractorCode() );
    }

    @Test
    void swallowsApiExceptionsAndReturnsEmptyResult() {
        // Simulates 429 after SDK retries exhaust, network error, etc.
        final ExtractionResult r = new TestableExtractor( ( c, ctx ) -> {
            throw new RuntimeException( "429 after retries exhausted" );
        } ).extract( chunk(), context() );
        assertTrue( r.mentions().isEmpty() );
        assertTrue( r.nodes().isEmpty() );
        assertEquals( "claude", r.extractorCode() );
    }

    @Test
    void returnsEmptyOnMalformedJsonResponse() {
        final ExtractionResult r = new TestableExtractor( ( c, ctx ) ->
            "sorry — I can't comply with that request" )
            .extract( chunk(), context() );
        assertTrue( r.mentions().isEmpty() );
        assertEquals( "claude", r.extractorCode() );
    }

    @Test
    void returnsEmptyOnNullResponse() {
        final ExtractionResult r = new TestableExtractor( ( c, ctx ) -> null )
            .extract( chunk(), context() );
        assertTrue( r.mentions().isEmpty() );
    }

    @Test
    void realCallClaudeBuildsRequestWithoutNodeDictionaryWhenNoExistingNodes() {
        final AnthropicClient client = clientReturning( fakeMessage(
            "{\"entities\":[{\"name\":\"Napoleon\",\"type\":\"Person\",\"confidence\":0.9}],\"relations\":[]}" ) );

        final ExtractionResult r = new ClaudeEntityExtractor( client, config() )
            .extract( chunk(), context() );

        assertEquals( 1, r.mentions().size() );
        assertEquals( "claude", r.extractorCode() );
    }

    @Test
    void realCallClaudeBuildsRequestWithNodeDictionaryWhenExistingNodesPresent() {
        final AnthropicClient client = clientReturning( fakeMessage( "{\"entities\":[],\"relations\":[]}" ) );
        final ExtractionContext ctxWithNodes = new ExtractionContext(
            "Page", List.of( node( "Napoleon", "Person" ), node( "Waterloo", "Place" ) ), Map.of() );

        final ExtractionResult r = new ClaudeEntityExtractor( client, config() )
            .extract( chunk(), ctxWithNodes );

        assertTrue( r.mentions().isEmpty() );
        assertEquals( "claude", r.extractorCode() );
    }
}
