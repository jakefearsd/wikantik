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
import com.wikantik.llm.activity.LlmActivityLog;
import com.wikantik.llm.activity.LlmActivityLogHolder;
import com.wikantik.llm.activity.Subsystem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AdminLlmActivityResourceTest {

    @AfterEach
    void reset() throws Exception {
        final var m = LlmActivityLogHolder.class.getDeclaredMethod( "setForTesting", LlmActivityLog.class );
        m.setAccessible( true );
        m.invoke( null, ( LlmActivityLog ) null );
    }

    private static void installHolder( final LlmActivityLog log ) throws Exception {
        final var m = LlmActivityLogHolder.class.getDeclaredMethod( "setForTesting", LlmActivityLog.class );
        m.setAccessible( true );
        m.invoke( null, log );
    }

    private static JsonObject doGet( final String limit ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "limit" ) ).thenReturn( limit );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
        new AdminLlmActivityResource().doGetForTesting( req, resp );
        return JsonParser.parseString( sw.toString() ).getAsJsonObject();
    }

    @Test
    void returnsSnapshotEnvelopeWithRecordedCalls() throws Exception {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
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
        installHolder( new LlmActivityLog( false, 60, 100, 500 ) );
        final JsonObject data = doGet( null ).getAsJsonObject( "data" );
        assertEquals( false, data.get( "enabled" ).getAsBoolean() );
    }

    @Test
    void limitParameterCapsResultCount() throws Exception {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        for ( int i = 0; i < 5; i++ ) {
            log.succeed( log.begin( Subsystem.EMBEDDING, "ollama", "m", "embed", "n" + i ), "ok" );
        }
        installHolder( log );
        assertEquals( 2, doGet( "2" ).getAsJsonObject( "data" ).getAsJsonArray( "calls" ).size() );
    }
}
