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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaEmbeddingClientTest {

    private HttpServer server;
    private int port;
    private final AtomicReference< String > lastRequestBody = new AtomicReference<>();
    private final AtomicReference< String > lastAuthHeader = new AtomicReference<>();
    private final AtomicReference< String > lastPath = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        port = server.getAddress().getPort();
        server.setExecutor( null );
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop( 0 );
    }

    private EmbeddingConfig config( final EmbeddingModel model, final int batchSize ) {
        return new EmbeddingConfig(
            true, "ollama", "http://127.0.0.1:" + port, null,
            model, null, 5_000, batchSize );
    }

    private void handleWithFixedDim( final int dim, final int returnCountSupplier ) {
        server.createContext( "/api/embed", exchange -> {
            lastPath.set( exchange.getRequestURI().getPath() );
            lastAuthHeader.set( exchange.getRequestHeaders().getFirst( "Authorization" ) );
            final String body = new String( exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8 );
            lastRequestBody.set( body );

            final JsonObject reqJson = JsonParser.parseString( body ).getAsJsonObject();
            final int inputCount = returnCountSupplier > 0
                ? returnCountSupplier
                : reqJson.getAsJsonArray( "input" ).size();

            final JsonArray embeddings = new JsonArray();
            for( int i = 0; i < inputCount; i++ ) {
                final JsonArray vec = new JsonArray();
                for( int j = 0; j < dim; j++ ) {
                    vec.add( (float) ( i + j ) / 100f );
                }
                embeddings.add( vec );
            }
            final JsonObject resp = new JsonObject();
            resp.add( "embeddings", embeddings );
            resp.addProperty( "model", reqJson.get( "model" ).getAsString() );
            final byte[] out = resp.toString().getBytes( StandardCharsets.UTF_8 );

            exchange.getResponseHeaders().set( "Content-Type", "application/json" );
            exchange.sendResponseHeaders( 200, out.length );
            try( final OutputStream os = exchange.getResponseBody() ) {
                os.write( out );
            }
        } );
    }

    @Test
    void embedSendsModelTagAndPrefixedInputs() {
        handleWithFixedDim( 768, 0 );
        final OllamaEmbeddingClient client = new OllamaEmbeddingClient(
            HttpClient.newHttpClient(), config( EmbeddingModel.NOMIC_EMBED_V1_5, 32 ) );

        final List< float[] > out = client.embed( List.of( "hello world" ), EmbeddingKind.QUERY );

        assertEquals( 1, out.size() );
        assertEquals( 768, out.get( 0 ).length );

        final JsonObject sent = JsonParser.parseString( lastRequestBody.get() ).getAsJsonObject();
        assertEquals( "nomic-embed-text:v1.5", sent.get( "model" ).getAsString() );
        final JsonArray inputs = sent.getAsJsonArray( "input" );
        assertEquals( 1, inputs.size() );
        assertEquals( "search_query: hello world", inputs.get( 0 ).getAsString() );
    }

    @Test
    void embedAppliesDocumentPrefixOnlyWhenEmbeddingDocuments() {
        handleWithFixedDim( 768, 0 );
        final OllamaEmbeddingClient client = new OllamaEmbeddingClient(
            HttpClient.newHttpClient(), config( EmbeddingModel.NOMIC_EMBED_V1_5, 32 ) );

        client.embed( List.of( "a chunk of wiki text" ), EmbeddingKind.DOCUMENT );

        final JsonObject sent = JsonParser.parseString( lastRequestBody.get() ).getAsJsonObject();
        final JsonArray inputs = sent.getAsJsonArray( "input" );
        assertEquals( "search_document: a chunk of wiki text", inputs.get( 0 ).getAsString() );
    }

    @Test
    void embedDoesNotPrefixBgeM3Inputs() {
        handleWithFixedDim( 1024, 0 );
        final OllamaEmbeddingClient client = new OllamaEmbeddingClient(
            HttpClient.newHttpClient(), config( EmbeddingModel.BGE_M3, 32 ) );

        client.embed( List.of( "plain query" ), EmbeddingKind.QUERY );

        final JsonObject sent = JsonParser.parseString( lastRequestBody.get() ).getAsJsonObject();
        assertEquals( "plain query", sent.getAsJsonArray( "input" ).get( 0 ).getAsString() );
    }

    @Test
    void embedAppliesQwen3InstructionOnlyToQueries() {
        handleWithFixedDim( 1024, 0 );
        final OllamaEmbeddingClient client = new OllamaEmbeddingClient(
            HttpClient.newHttpClient(), config( EmbeddingModel.QWEN3_EMBEDDING_06B, 32 ) );

        client.embed( List.of( "who owns page X?" ), EmbeddingKind.QUERY );
        final String queryInput = JsonParser.parseString( lastRequestBody.get() )
            .getAsJsonObject().getAsJsonArray( "input" ).get( 0 ).getAsString();
        assertTrue( queryInput.contains( "Query: who owns page X?" ) );
        assertTrue( queryInput.startsWith( "Instruct:" ) );

        client.embed( List.of( "page X is owned by the platform team" ), EmbeddingKind.DOCUMENT );
        final String docInput = JsonParser.parseString( lastRequestBody.get() )
            .getAsJsonObject().getAsJsonArray( "input" ).get( 0 ).getAsString();
        assertEquals( "page X is owned by the platform team", docInput );
    }

    @Test
    void embedBatchesAccordingToConfig() {
        final List< Integer > observedBatchSizes = new ArrayList<>();
        server.createContext( "/api/embed", exchange -> {
            final String body = new String( exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8 );
            final JsonObject reqJson = JsonParser.parseString( body ).getAsJsonObject();
            final JsonArray inputs = reqJson.getAsJsonArray( "input" );
            observedBatchSizes.add( inputs.size() );

            final JsonArray embeddings = new JsonArray();
            for( int i = 0; i < inputs.size(); i++ ) {
                final JsonArray vec = new JsonArray();
                for( int j = 0; j < 1024; j++ ) vec.add( 0.01f * j );
                embeddings.add( vec );
            }
            final JsonObject resp = new JsonObject();
            resp.add( "embeddings", embeddings );
            final byte[] out = resp.toString().getBytes( StandardCharsets.UTF_8 );
            exchange.sendResponseHeaders( 200, out.length );
            try( final OutputStream os = exchange.getResponseBody() ) { os.write( out ); }
        } );

        final OllamaEmbeddingClient client = new OllamaEmbeddingClient(
            HttpClient.newHttpClient(), config( EmbeddingModel.BGE_M3, 3 ) );
        final List< String > texts = List.of( "a", "b", "c", "d", "e", "f", "g" );

        final List< float[] > out = client.embed( texts, EmbeddingKind.DOCUMENT );

        assertEquals( 7, out.size() );
        assertEquals( List.of( 3, 3, 1 ), observedBatchSizes );
    }

    @Test
    void emptyInputShortCircuitsWithoutHttp() {
        server.createContext( "/api/embed", exchange -> {
            throw new AssertionError( "no HTTP call expected for empty input" );
        } );
        final OllamaEmbeddingClient client = new OllamaEmbeddingClient(
            HttpClient.newHttpClient(), config( EmbeddingModel.BGE_M3, 32 ) );
        assertEquals( List.of(), client.embed( List.of(), EmbeddingKind.QUERY ) );
    }

    @Test
    void apiKeyIsSentAsBearerTokenWhenConfigured() {
        handleWithFixedDim( 1024, 0 );
        final EmbeddingConfig c = new EmbeddingConfig(
            true, "ollama", "http://127.0.0.1:" + port, "secret-key",
            EmbeddingModel.BGE_M3, null, 5_000, 32 );
        final OllamaEmbeddingClient client = new OllamaEmbeddingClient( HttpClient.newHttpClient(), c );

        client.embed( List.of( "x" ), EmbeddingKind.QUERY );

        assertEquals( "Bearer secret-key", lastAuthHeader.get() );
    }

    @Test
    void nonTwoHundredResponseIsThrownAsEmbeddingException() {
        server.createContext( "/api/embed", exchange -> {
            final byte[] body = "model not found".getBytes( StandardCharsets.UTF_8 );
            exchange.sendResponseHeaders( 404, body.length );
            try( final OutputStream os = exchange.getResponseBody() ) { os.write( body ); }
        } );
        final OllamaEmbeddingClient client = new OllamaEmbeddingClient(
            HttpClient.newHttpClient(), config( EmbeddingModel.BGE_M3, 32 ) );

        final EmbeddingException ex = assertThrows( EmbeddingException.class,
            () -> client.embed( List.of( "x" ), EmbeddingKind.QUERY ) );
        assertTrue( ex.getMessage().contains( "404" ) );
    }

    @Test
    void dimensionMismatchInResponseIsRejected() {
        // Server always returns dim=10 regardless of model advertised dimension.
        handleWithFixedDim( 10, 0 );
        final OllamaEmbeddingClient client = new OllamaEmbeddingClient(
            HttpClient.newHttpClient(), config( EmbeddingModel.BGE_M3, 32 ) );

        assertThrows( EmbeddingException.class,
            () -> client.embed( List.of( "x" ), EmbeddingKind.QUERY ) );
    }

    @Test
    void nullInputStringIsRejected() {
        handleWithFixedDim( 1024, 0 );
        final OllamaEmbeddingClient client = new OllamaEmbeddingClient(
            HttpClient.newHttpClient(), config( EmbeddingModel.BGE_M3, 32 ) );
        final List< String > texts = new ArrayList<>();
        texts.add( null );
        assertThrows( IllegalArgumentException.class,
            () -> client.embed( texts, EmbeddingKind.QUERY ) );
    }

    @Test
    void embedAsyncReturnsSameVectorsAsSyncEmbed() throws Exception {
        handleWithFixedDim( 1024, 0 );
        final OllamaEmbeddingClient client = new OllamaEmbeddingClient(
            HttpClient.newHttpClient(), config( EmbeddingModel.BGE_M3, 32 ) );

        final CompletableFuture< List< float[] > > future =
            client.embedAsync( List.of( "alpha", "beta" ), EmbeddingKind.QUERY );
        assertNotNull( future );
        final List< float[] > out = future.get( 5, java.util.concurrent.TimeUnit.SECONDS );
        assertEquals( 2, out.size() );
        assertArrayEquals( makeVec( 0, 1024 ), out.get( 0 ), 1e-6f );
        assertArrayEquals( makeVec( 1, 1024 ), out.get( 1 ), 1e-6f );
        assertEquals( "/api/embed", lastPath.get() );
    }

    @Test
    void embedAsyncOnEmptyInputCompletesWithEmptyList() throws Exception {
        server.createContext( "/api/embed", exchange -> {
            throw new AssertionError( "no HTTP call expected for empty input" );
        } );
        final OllamaEmbeddingClient client = new OllamaEmbeddingClient(
            HttpClient.newHttpClient(), config( EmbeddingModel.BGE_M3, 32 ) );

        final CompletableFuture< List< float[] > > future =
            client.embedAsync( List.of(), EmbeddingKind.QUERY );
        assertEquals( List.of(), future.get( 1, java.util.concurrent.TimeUnit.SECONDS ) );
    }

    @Test
    void embedAsyncSurfacesNonTwoHundredAsCompletionFailure() {
        server.createContext( "/api/embed", exchange -> {
            final byte[] body = "model not found".getBytes( StandardCharsets.UTF_8 );
            exchange.sendResponseHeaders( 404, body.length );
            try( final OutputStream os = exchange.getResponseBody() ) { os.write( body ); }
        } );
        final OllamaEmbeddingClient client = new OllamaEmbeddingClient(
            HttpClient.newHttpClient(), config( EmbeddingModel.BGE_M3, 32 ) );

        final CompletableFuture< List< float[] > > future =
            client.embedAsync( List.of( "x" ), EmbeddingKind.QUERY );
        final java.util.concurrent.ExecutionException ex = assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> future.get( 5, java.util.concurrent.TimeUnit.SECONDS ) );
        assertTrue( ex.getCause() instanceof EmbeddingException );
        assertTrue( ex.getCause().getMessage().contains( "404" ) );
    }

    @Test
    void returnsVectorsInInputOrderWithExpectedShape() {
        handleWithFixedDim( 1024, 0 );
        final OllamaEmbeddingClient client = new OllamaEmbeddingClient(
            HttpClient.newHttpClient(), config( EmbeddingModel.BGE_M3, 32 ) );
        final List< float[] > out = client.embed( List.of( "a", "b" ), EmbeddingKind.QUERY );
        assertEquals( 2, out.size() );
        // Each vector i is [i/100, (i+1)/100, (i+2)/100, ...] from the stub server.
        assertArrayEquals( makeVec( 0, 1024 ), out.get( 0 ), 1e-6f );
        assertArrayEquals( makeVec( 1, 1024 ), out.get( 1 ), 1e-6f );

        // Sanity: request body was sent to the embed path with the expected model tag.
        assertEquals( "/api/embed", lastPath.get() );
        final JsonObject sent = JsonParser.parseString( lastRequestBody.get() ).getAsJsonObject();
        assertEquals( "bge-m3:latest", sent.get( "model" ).getAsString() );
        assertNotNull( sent.getAsJsonArray( "input" ) );
    }

    private static float[] makeVec( final int base, final int dim ) {
        final float[] v = new float[ dim ];
        for( int j = 0; j < dim; j++ ) v[ j ] = (float) ( base + j ) / 100f;
        return v;
    }
}
