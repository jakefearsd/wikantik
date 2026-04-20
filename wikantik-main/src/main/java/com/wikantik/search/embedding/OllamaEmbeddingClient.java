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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Talks to a local Ollama server's {@code /api/embed} endpoint. Applies the
 * selected model's per-kind prefix to each input, splits large requests into
 * batches, and parses the response into dense float vectors.
 *
 * <p>Errors are surfaced as {@link EmbeddingException}. The client is safe to
 * share across threads — it holds no mutable state and relies on the injected
 * {@link HttpClient}'s thread safety.</p>
 */
public class OllamaEmbeddingClient implements TextEmbeddingClient {

    private static final Logger LOG = LogManager.getLogger( OllamaEmbeddingClient.class );

    private static final String EMBED_PATH = "/api/embed";

    private final HttpClient httpClient;
    private final EmbeddingConfig config;
    private final Gson gson;

    public OllamaEmbeddingClient( final HttpClient httpClient, final EmbeddingConfig config ) {
        this.httpClient = httpClient;
        this.config = config;
        this.gson = new Gson();
    }

    @Override
    public List< float[] > embed( final List< String > texts, final EmbeddingKind kind ) {
        if( texts == null ) {
            throw new IllegalArgumentException( "texts must not be null" );
        }
        if( texts.isEmpty() ) {
            return List.of();
        }
        final String prefix = config.model().prefix( kind );
        final int batchSize = config.batchSize();
        final List< float[] > out = new ArrayList<>( texts.size() );
        for( int start = 0; start < texts.size(); start += batchSize ) {
            final int end = Math.min( start + batchSize, texts.size() );
            out.addAll( embedBatch( texts.subList( start, end ), prefix ) );
        }
        return out;
    }

    @Override
    public CompletableFuture< List< float[] > > embedAsync( final List< String > texts,
                                                             final EmbeddingKind kind ) {
        if( texts == null ) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException( "texts must not be null" ) );
        }
        if( texts.isEmpty() ) {
            return CompletableFuture.completedFuture( List.of() );
        }
        final String prefix = config.model().prefix( kind );
        final int batchSize = config.batchSize();
        // Issue all batches concurrently and stitch the per-batch results back together
        // in input order. Native HttpClient.sendAsync gives us cancellable, non-blocking
        // I/O so the request thread never has to park on a slow embedding backend.
        final List< CompletableFuture< List< float[] > > > batchFutures = new ArrayList<>();
        for( int start = 0; start < texts.size(); start += batchSize ) {
            final int end = Math.min( start + batchSize, texts.size() );
            batchFutures.add( embedBatchAsync( texts.subList( start, end ), prefix ) );
        }
        return CompletableFuture
            .allOf( batchFutures.toArray( new CompletableFuture[ 0 ] ) )
            .thenApply( ignored -> {
                final List< float[] > merged = new ArrayList<>( texts.size() );
                for( final CompletableFuture< List< float[] > > bf : batchFutures ) {
                    merged.addAll( bf.join() );
                }
                return merged;
            } );
    }

    @Override
    public int dimension() {
        return config.model().dimension();
    }

    @Override
    public String modelName() {
        return config.model().code();
    }

    private List< float[] > embedBatch( final List< String > batch, final String prefix ) {
        final List< String > prefixed = new ArrayList<>( batch.size() );
        for( final String t : batch ) {
            if( t == null ) {
                throw new IllegalArgumentException( "embedding input must not contain null strings" );
            }
            prefixed.add( prefix.isEmpty() ? t : prefix + t );
        }

        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "model", config.resolvedOllamaTag() );
        body.put( "input", prefixed );

        final HttpRequest.Builder req = HttpRequest.newBuilder()
            .uri( URI.create( config.baseUrl() + EMBED_PATH ) )
            .timeout( Duration.ofMillis( config.timeoutMs() ) )
            .header( "Content-Type", "application/json" )
            .POST( HttpRequest.BodyPublishers.ofString( gson.toJson( body ), StandardCharsets.UTF_8 ) );
        if( config.apiKey() != null ) {
            req.header( "Authorization", "Bearer " + config.apiKey() );
        }

        final HttpResponse< String > resp;
        try {
            resp = httpClient.send( req.build(), HttpResponse.BodyHandlers.ofString( StandardCharsets.UTF_8 ) );
        } catch( final IOException e ) {
            throw new EmbeddingException( "Ollama embed request failed: " + e.getMessage(), e );
        } catch( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException( "Ollama embed request interrupted", e );
        }

        if( resp.statusCode() / 100 != 2 ) {
            LOG.warn( "Ollama embed returned HTTP {} for model {}: {}",
                      resp.statusCode(), config.resolvedOllamaTag(), resp.body() );
            throw new EmbeddingException( "Ollama embed HTTP " + resp.statusCode() + ": " + resp.body() );
        }

        return parseEmbeddings( resp.body(), batch.size() );
    }

    private CompletableFuture< List< float[] > > embedBatchAsync( final List< String > batch,
                                                                    final String prefix ) {
        final List< String > prefixed = new ArrayList<>( batch.size() );
        for( final String t : batch ) {
            if( t == null ) {
                return CompletableFuture.failedFuture(
                    new IllegalArgumentException( "embedding input must not contain null strings" ) );
            }
            prefixed.add( prefix.isEmpty() ? t : prefix + t );
        }

        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "model", config.resolvedOllamaTag() );
        body.put( "input", prefixed );

        final HttpRequest.Builder req = HttpRequest.newBuilder()
            .uri( URI.create( config.baseUrl() + EMBED_PATH ) )
            .timeout( Duration.ofMillis( config.timeoutMs() ) )
            .header( "Content-Type", "application/json" )
            .POST( HttpRequest.BodyPublishers.ofString( gson.toJson( body ), StandardCharsets.UTF_8 ) );
        if( config.apiKey() != null ) {
            req.header( "Authorization", "Bearer " + config.apiKey() );
        }

        return httpClient.sendAsync( req.build(), HttpResponse.BodyHandlers.ofString( StandardCharsets.UTF_8 ) )
            .handle( ( resp, err ) -> {
                if( err != null ) {
                    final Throwable cause = err instanceof CompletionException ce && ce.getCause() != null
                        ? ce.getCause() : err;
                    throw new CompletionException(
                        new EmbeddingException( "Ollama embed request failed: " + cause.getMessage(), cause ) );
                }
                if( resp.statusCode() / 100 != 2 ) {
                    LOG.warn( "Ollama embed returned HTTP {} for model {}: {}",
                              resp.statusCode(), config.resolvedOllamaTag(), resp.body() );
                    throw new CompletionException(
                        new EmbeddingException( "Ollama embed HTTP " + resp.statusCode() + ": " + resp.body() ) );
                }
                return parseEmbeddings( resp.body(), batch.size() );
            } );
    }

    private List< float[] > parseEmbeddings( final String body, final int expectedCount ) {
        final JsonElement root;
        try {
            root = JsonParser.parseString( body );
        } catch( final JsonSyntaxException e ) {
            throw new EmbeddingException( "Ollama embed returned non-JSON body: " + truncate( body ), e );
        }
        if( !root.isJsonObject() ) {
            throw new EmbeddingException( "Ollama embed returned non-object body: " + truncate( body ) );
        }
        final JsonObject obj = root.getAsJsonObject();
        if( !obj.has( "embeddings" ) || !obj.get( "embeddings" ).isJsonArray() ) {
            throw new EmbeddingException( "Ollama embed response missing 'embeddings' array: " + truncate( body ) );
        }
        final JsonArray embeddings = obj.getAsJsonArray( "embeddings" );
        if( embeddings.size() != expectedCount ) {
            throw new EmbeddingException( "Ollama embed returned " + embeddings.size()
                + " vectors for " + expectedCount + " inputs" );
        }
        final int dim = dimension();
        final List< float[] > out = new ArrayList<>( embeddings.size() );
        for( int i = 0; i < embeddings.size(); i++ ) {
            final JsonElement vecEl = embeddings.get( i );
            if( !vecEl.isJsonArray() ) {
                throw new EmbeddingException( "Ollama embed vector " + i + " is not an array" );
            }
            final JsonArray vec = vecEl.getAsJsonArray();
            if( vec.size() != dim ) {
                throw new EmbeddingException( "Ollama embed vector " + i + " has dimension " + vec.size()
                    + ", expected " + dim + " for model " + config.model().code() );
            }
            final float[] arr = new float[ vec.size() ];
            for( int j = 0; j < vec.size(); j++ ) {
                arr[ j ] = vec.get( j ).getAsFloat();
            }
            out.add( arr );
        }
        return out;
    }

    private static String truncate( final String s ) {
        if( s == null ) return "<null>";
        return s.length() > 200 ? s.substring( 0, 200 ) + "…" : s;
    }
}
