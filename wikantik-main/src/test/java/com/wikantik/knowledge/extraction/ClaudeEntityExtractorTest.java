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
import com.wikantik.api.knowledge.ExtractionChunk;
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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
}
