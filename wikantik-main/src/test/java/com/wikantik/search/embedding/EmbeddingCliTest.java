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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link EmbeddingCli#run} — the testable core behind {@code main} — against a
 * local stub HTTP server standing in for Ollama, so no real inference host is contacted.
 */
class EmbeddingCliTest {

    private HttpServer server;
    private int port;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private ByteArrayOutputStream outCapture;
    private ByteArrayOutputStream errCapture;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        port = server.getAddress().getPort();
        server.setExecutor( null );
        server.start();

        originalOut = System.out;
        originalErr = System.err;
        outCapture = new ByteArrayOutputStream();
        errCapture = new ByteArrayOutputStream();
        System.setOut( new PrintStream( outCapture, true, StandardCharsets.UTF_8 ) );
        System.setErr( new PrintStream( errCapture, true, StandardCharsets.UTF_8 ) );
    }

    @AfterEach
    void tearDown() {
        System.setOut( originalOut );
        System.setErr( originalErr );
        server.stop( 0 );
        System.clearProperty( EmbeddingConfig.PROP_BASE_URL );
        System.clearProperty( EmbeddingConfig.PROP_TIMEOUT_MS );
        System.clearProperty( EmbeddingConfig.PROP_BATCH_SIZE );
        System.clearProperty( EmbeddingConfig.PROP_API_KEY );
        System.clearProperty( EmbeddingConfig.PROP_OLLAMA_TAG );
        System.clearProperty( EmbeddingConfig.PROP_BACKEND );
    }

    private void stubEmbedEndpoint( final int dim ) {
        server.createContext( "/api/embed", exchange -> {
            final String body = new String( exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8 );
            final JsonObject reqJson = JsonParser.parseString( body ).getAsJsonObject();
            final int inputCount = reqJson.getAsJsonArray( "input" ).size();

            final JsonArray embeddings = new JsonArray();
            for( int i = 0; i < inputCount; i++ ) {
                final JsonArray vec = new JsonArray();
                for( int j = 0; j < dim; j++ ) vec.add( ( i + j ) * 0.01f );
                embeddings.add( vec );
            }
            final JsonObject resp = new JsonObject();
            resp.add( "embeddings", embeddings );
            final byte[] out = resp.toString().getBytes( StandardCharsets.UTF_8 );
            exchange.getResponseHeaders().set( "Content-Type", "application/json" );
            exchange.sendResponseHeaders( 200, out.length );
            try( final OutputStream os = exchange.getResponseBody() ) { os.write( out ); }
        } );
    }

    @Test
    void tooFewArgumentsPrintsUsageAndReturnsExitCodeTwo() throws IOException {
        final int code = EmbeddingCli.run( new String[]{ "bge-m3", "query" } );
        assertEquals( 2, code );
        assertTrue( errCapture.toString( StandardCharsets.UTF_8 ).contains( "Usage: EmbeddingCli" ) );
    }

    @Test
    void zeroArgumentsPrintsUsageAndReturnsExitCodeTwo() throws IOException {
        final int code = EmbeddingCli.run( new String[ 0 ] );
        assertEquals( 2, code );
        assertTrue( errCapture.toString( StandardCharsets.UTF_8 ).contains( "model-code:" ) );
    }

    @Test
    void invalidKindPrintsDiagnosticAndReturnsExitCodeTwo() throws IOException {
        final int code = EmbeddingCli.run( new String[]{ "bge-m3", "sideways", "hello" } );
        assertEquals( 2, code );
        assertTrue( errCapture.toString( StandardCharsets.UTF_8 ).contains( "invalid kind: sideways" ) );
    }

    @Test
    void successfulRunPrintsModelLatencyAndVectorHead() throws IOException {
        stubEmbedEndpoint( 1024 );
        System.setProperty( EmbeddingConfig.PROP_BASE_URL, "http://127.0.0.1:" + port );
        System.setProperty( EmbeddingConfig.PROP_TIMEOUT_MS, "5000" );

        final int code = EmbeddingCli.run( new String[]{ "bge-m3", "query", "hello", "world" } );

        assertEquals( 0, code );
        final String out = outCapture.toString( StandardCharsets.UTF_8 );
        assertTrue( out.contains( "model=bge-m3" ), out );
        assertTrue( out.contains( "dim=1024" ), out );
        assertTrue( out.contains( "kind=query" ), out );
        assertTrue( out.contains( "input (11 chars): hello world" ), out );
        assertTrue( out.contains( "latency=" ) && out.contains( "l2_norm=" ) && out.contains( "head=[" ), out );
    }

    @Test
    void longInputTextIsAbbreviatedInOutput() throws IOException {
        stubEmbedEndpoint( 1024 );
        System.setProperty( EmbeddingConfig.PROP_BASE_URL, "http://127.0.0.1:" + port );
        System.setProperty( EmbeddingConfig.PROP_TIMEOUT_MS, "5000" );

        final String longWord = "x".repeat( 100 );
        final int code = EmbeddingCli.run( new String[]{ "bge-m3", "document", longWord } );

        assertEquals( 0, code );
        final String out = outCapture.toString( StandardCharsets.UTF_8 );
        // abbreviate() truncates to 79 chars of content plus an ellipsis.
        assertTrue( out.contains( "x".repeat( 79 ) + "…" ), out );
        assertTrue( out.contains( "kind=document" ), out );
    }
}
