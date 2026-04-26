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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wikantik.api.knowledge.ExtractionChunk;
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.ExtractionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uses a stubbed {@code /api/chat} server so we can verify the request shape
 * (format=json, system + user messages) and confirm the listener-level
 * contract (always returns, never throws) across success, error, timeout,
 * and malformed responses.
 */
class OllamaEntityExtractorTest {

    private HttpServer server;
    private int port;
    private final AtomicReference< String > lastBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop( 0 );
    }

    private OllamaEntityExtractor extractor( final long timeoutMs ) {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
        p.setProperty( "wikantik.knowledge.extractor.ollama.base_url", "http://127.0.0.1:" + port );
        p.setProperty( "wikantik.knowledge.extractor.ollama.model", "test-model" );
        p.setProperty( "wikantik.knowledge.extractor.timeout_ms", Long.toString( timeoutMs ) );
        p.setProperty( "wikantik.knowledge.extractor.confidence_threshold", "0.6" );
        return new OllamaEntityExtractor( HttpClient.newHttpClient(),
                                           EntityExtractorConfig.fromProperties( p ) );
    }

    private static ExtractionChunk chunk() {
        return new ExtractionChunk( UUID.randomUUID(), "Page", 0, List.of(), "Napoleon at Waterloo." );
    }

    private static ExtractionContext context() {
        return new ExtractionContext( "Page", List.of(), java.util.Map.of() );
    }

    @Test
    void wrapsOllamaJsonResponseIntoResult() {
        respondWith( 200, "{\"message\":{\"content\":\"{\\\"entities\\\":[{\\\"name\\\":\\\"A\\\",\\\"type\\\":\\\"X\\\",\\\"confidence\\\":0.9}],\\\"relations\\\":[]}\"}}" );
        final ExtractionResult r = extractor( 5_000 ).extract( chunk(), context() );
        assertEquals( 1, r.mentions().size() );
        // code() now reflects the model tag (with :latest stripped) so
        // chunk_entity_mentions.extractor carries the actual lineage.
        assertEquals( "test-model", r.extractorCode() );
        // Verify we sent a chat-shaped request with JSON format flag.
        assertNotNull( lastBody.get() );
        assertTrue( lastBody.get().contains( "\"format\":\"json\"" ) );
        assertTrue( lastBody.get().contains( "\"model\":\"test-model\"" ) );
        assertTrue( lastBody.get().contains( "\"role\":\"system\"" ) );
        assertTrue( lastBody.get().contains( "\"role\":\"user\"" ) );
    }

    @Test
    void returnsEmptyOnNon2xx() {
        respondWith( 503, "upstream unavailable" );
        final ExtractionResult r = extractor( 5_000 ).extract( chunk(), context() );
        assertTrue( r.mentions().isEmpty() );
        assertTrue( r.nodes().isEmpty() );
        // code() now reflects the model tag (with :latest stripped) so
        // chunk_entity_mentions.extractor carries the actual lineage.
        assertEquals( "test-model", r.extractorCode() );
    }

    @Test
    void returnsEmptyOnMalformedEnvelope() {
        respondWith( 200, "{\"unexpected\":\"envelope\"}" );
        final ExtractionResult r = extractor( 5_000 ).extract( chunk(), context() );
        assertTrue( r.mentions().isEmpty() );
    }

    @Test
    void codeReturnsModelNameWithLatestSuffixStripped() {
        // gemma4-assist:latest → "gemma4-assist" — the :latest tag is the
        // Ollama default and adds no useful lineage information.
        final OllamaEntityExtractor e = extractorWithModel( "gemma4-assist:latest" );
        assertEquals( "gemma4-assist", e.code() );
    }

    @Test
    void codeReturnsModelNameWithExplicitTagPreserved() {
        // qwen2.5:1.5b-instruct has a meaningful tag — keep it.
        final OllamaEntityExtractor e = extractorWithModel( "qwen2.5:1.5b-instruct" );
        assertEquals( "qwen2.5:1.5b-instruct", e.code() );
    }

    private OllamaEntityExtractor extractorWithModel( final String model ) {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
        p.setProperty( "wikantik.knowledge.extractor.ollama.base_url", "http://127.0.0.1:" + port );
        p.setProperty( "wikantik.knowledge.extractor.ollama.model", model );
        return new OllamaEntityExtractor( HttpClient.newHttpClient(),
                                           EntityExtractorConfig.fromProperties( p ) );
    }

    @Test
    void returnsEmptyOnTimeout() {
        // Handler never responds — client times out.
        server.createContext( "/api/chat", exchange -> {
            try {
                Thread.sleep( 5_000 );
            } catch( final InterruptedException ignored ) {
                Thread.currentThread().interrupt();
            }
        } );
        final long start = System.currentTimeMillis();
        final ExtractionResult r = extractor( 200 ).extract( chunk(), context() );
        final long elapsed = System.currentTimeMillis() - start;
        assertTrue( r.mentions().isEmpty() );
        assertFalse( r.nodes().isEmpty() && elapsed > 3_000,
                     "extractor must honour the configured timeout, not block for the full handler sleep" );
    }

    private void respondWith( final int status, final String body ) {
        server.createContext( "/api/chat", exchange -> {
            lastBody.set( new String( exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8 ) );
            sendResponse( exchange, status, body );
        } );
    }

    private static void sendResponse( final HttpExchange exchange, final int status, final String body ) {
        try {
            final byte[] bytes = body.getBytes( StandardCharsets.UTF_8 );
            exchange.getResponseHeaders().add( "Content-Type", "application/json" );
            exchange.sendResponseHeaders( status, bytes.length );
            try ( OutputStream os = exchange.getResponseBody() ) {
                os.write( bytes );
            }
        } catch( final IOException ignored ) {
            // Test harness; propagation would just mask the test assertion.
        }
    }
}
