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
package com.wikantik.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.search.embedding.activity.EmbeddingActivityLog;
import com.wikantik.search.embedding.activity.EmbeddingActivityLogHolder;
import com.wikantik.search.embedding.activity.Subsystem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AdminEmbeddingActivityResourceTest {

    @AfterEach
    void reset() throws Exception {
        final var m = EmbeddingActivityLogHolder.class.getDeclaredMethod( "setForTesting", EmbeddingActivityLog.class );
        m.setAccessible( true );
        m.invoke( null, ( EmbeddingActivityLog ) null );
    }

    private static void installHolder( final EmbeddingActivityLog log ) throws Exception {
        final var m = EmbeddingActivityLogHolder.class.getDeclaredMethod( "setForTesting", EmbeddingActivityLog.class );
        m.setAccessible( true );
        m.invoke( null, log );
    }

    private static JsonObject doGet( final String limit ) throws Exception {
        return doGet( limit, null, null, null );
    }

    private static JsonObject doGet( final String limit, final String subsystem, final String status,
                                     final HttpServletResponse resp ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse response = resp == null ? mock( HttpServletResponse.class ) : resp;
        when( req.getParameter( "limit" ) ).thenReturn( limit );
        when( req.getParameter( "subsystem" ) ).thenReturn( subsystem );
        when( req.getParameter( "status" ) ).thenReturn( status );
        final StringWriter sw = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( sw ) );
        new AdminEmbeddingActivityResource().doGetForTesting( req, response );
        return JsonParser.parseString( sw.toString() ).getAsJsonObject();
    }

    private static JsonObject callAt( final JsonObject body, final int i ) {
        return body.getAsJsonObject( "data" ).getAsJsonArray( "calls" ).get( i ).getAsJsonObject();
    }

    @Test
    void returnsSnapshotEnvelopeWithRecordedCalls() throws Exception {
        final EmbeddingActivityLog log = new EmbeddingActivityLog( true, 60, 100, 500 );
        log.succeed( log.begin( Subsystem.EMBEDDING, "ollama", "nomic", "embed", "x" ), "ok" );
        installHolder( log );

        final JsonObject body = doGet( null );
        final JsonObject data = body.getAsJsonObject( "data" );
        assertTrue( data.get( "enabled" ).getAsBoolean() );
        assertEquals( 1, data.getAsJsonArray( "calls" ).size() );
        assertEquals( 0, data.get( "inFlight" ).getAsInt() );
        assertEquals( 60, data.get( "windowMinutes" ).getAsInt() );
    }

    @Test
    void reportsDisabledWhenLogDisabled() throws Exception {
        installHolder( new EmbeddingActivityLog( false, 60, 100, 500 ) );
        final JsonObject data = doGet( null ).getAsJsonObject( "data" );
        assertEquals( false, data.get( "enabled" ).getAsBoolean() );
    }

    @Test
    void limitParameterCapsResultCount() throws Exception {
        final EmbeddingActivityLog log = new EmbeddingActivityLog( true, 60, 100, 500 );
        for ( int i = 0; i < 5; i++ ) {
            log.succeed( log.begin( Subsystem.EMBEDDING, "ollama", "m", "embed", "n" + i ), "ok" );
        }
        installHolder( log );
        assertEquals( 2, doGet( "2" ).getAsJsonObject( "data" ).getAsJsonArray( "calls" ).size() );
    }

    @Test
    void aCallIsDescribedInFullAndTheEnvelopeNamesItsContentTypeAndCapacity() throws Exception {
        final EmbeddingActivityLog log = new EmbeddingActivityLog( true, 60, 100, 500 );
        log.succeed( log.begin( Subsystem.EMBEDDING, "ollama", "qwen3", "embed", "the prompt" ), "the response" );
        installHolder( log );
        final HttpServletResponse resp = mock( HttpServletResponse.class );

        final JsonObject body = doGet( null, null, null, resp );

        org.mockito.Mockito.verify( resp ).setContentType( "application/json; charset=UTF-8" );
        org.mockito.Mockito.verify( resp ).setStatus( 200 );
        assertEquals( 100, body.getAsJsonObject( "data" ).get( "capacity" ).getAsInt() );
        final JsonObject call = callAt( body, 0 );
        assertEquals( 1, call.get( "seq" ).getAsInt() );
        assertEquals( "EMBEDDING", call.get( "subsystem" ).getAsString().toUpperCase( java.util.Locale.ROOT ) );
        assertEquals( "ollama", call.get( "backend" ).getAsString() );
        assertEquals( "qwen3", call.get( "model" ).getAsString() );
        assertEquals( "embed", call.get( "operation" ).getAsString() );
        assertEquals( "OK", call.get( "status" ).getAsString().toUpperCase( java.util.Locale.ROOT ) );
        assertEquals( "the prompt", call.get( "promptPreview" ).getAsString() );
        assertEquals( "the response", call.get( "responsePreview" ).getAsString() );
        assertTrue( call.get( "durationMs" ).getAsLong() >= 0 );
        assertTrue( !call.get( "startedAt" ).getAsString().isBlank() );
        assertTrue( call.get( "inputTokens" ).isJsonNull() );
        assertTrue( call.get( "outputTokens" ).isJsonNull() );
    }

    @Test
    void aFailedCallCarriesItsErrorMessage() throws Exception {
        final EmbeddingActivityLog log = new EmbeddingActivityLog( true, 60, 100, 500 );
        log.fail( log.begin( Subsystem.EMBEDDING, "ollama", "m", "embed", "p" ), new IllegalStateException( "embedder down" ) );
        installHolder( log );

        final JsonObject call = callAt( doGet( null ), 0 );

        assertEquals( "ERROR", call.get( "status" ).getAsString().toUpperCase( java.util.Locale.ROOT ) );
        assertTrue( call.get( "errorMessage" ).getAsString().contains( "embedder down" ), call.toString() );
    }

    @Test
    void theStatusFilterIsCaseInsensitiveAndAnUnknownOneIsIgnored() throws Exception {
        final EmbeddingActivityLog log = new EmbeddingActivityLog( true, 60, 100, 500 );
        log.succeed( log.begin( Subsystem.EMBEDDING, "o", "m", "embed", "ok-one" ), "r" );
        log.fail( log.begin( Subsystem.EMBEDDING, "o", "m", "embed", "bad-one" ), new RuntimeException( "x" ) );
        installHolder( log );

        final var errors = doGet( null, null, " error ", null ).getAsJsonObject( "data" ).getAsJsonArray( "calls" );
        assertEquals( 1, errors.size() );
        assertEquals( "bad-one", errors.get( 0 ).getAsJsonObject().get( "promptPreview" ).getAsString() );
        assertEquals( 1, doGet( null, null, "ok", null ).getAsJsonObject( "data" ).getAsJsonArray( "calls" ).size() );
        assertEquals( 2, doGet( null, null, "bogus", null ).getAsJsonObject( "data" ).getAsJsonArray( "calls" ).size() );
        assertEquals( 2, doGet( null, null, "  ", null ).getAsJsonObject( "data" ).getAsJsonArray( "calls" ).size() );
    }

    @Test
    void theSubsystemFilterIsCaseInsensitiveAndAnUnknownOneIsIgnored() throws Exception {
        final EmbeddingActivityLog log = new EmbeddingActivityLog( true, 60, 100, 500 );
        log.succeed( log.begin( Subsystem.EMBEDDING, "o", "m", "embed", "p" ), "r" );
        installHolder( log );

        assertEquals( 1, doGet( null, " embedding ", null, null ).getAsJsonObject( "data" ).getAsJsonArray( "calls" ).size() );
        assertEquals( 1, doGet( null, "nonsense", null, null ).getAsJsonObject( "data" ).getAsJsonArray( "calls" ).size() );
        assertEquals( 1, doGet( null, "", null, null ).getAsJsonObject( "data" ).getAsJsonArray( "calls" ).size() );
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource( { "-3,1", "0,1", "1,1", "3,3", "5,5", "6,5", "99999,5", "junk,5", "' 2 ',2" } )
    void theLimitIsClampedToOneThroughTheLogCapacityAndJunkMeansDefault( final String asked, final int expected ) throws Exception {
        final EmbeddingActivityLog log = new EmbeddingActivityLog( true, 60, 5, 500 );
        for ( int i = 0; i < 5; i++ ) {
            log.succeed( log.begin( Subsystem.EMBEDDING, "o", "m", "embed", "n" + i ), "r" );
        }
        installHolder( log );

        assertEquals( expected, doGet( asked ).getAsJsonObject( "data" ).getAsJsonArray( "calls" ).size() );
    }
}
