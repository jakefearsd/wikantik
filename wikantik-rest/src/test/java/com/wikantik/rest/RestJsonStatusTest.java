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

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link RestJson#sendJsonKeepingNulls(HttpServletResponse, int, Object)} and the null-tolerant error message. */
class RestJsonStatusTest {

    @Test
    void setsStatusAndKeepsNullsCompact() throws Exception {
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter out = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( out, true ) );
        final Map< String, Object > payload = new LinkedHashMap<>();
        payload.put( "state", "RUNNING" );
        payload.put( "startedAt", null );

        RestJson.sendJsonKeepingNulls( resp, 202, payload );

        verify( resp ).setStatus( 202 );
        verify( resp ).setContentType( "application/json" );
        verify( resp ).setCharacterEncoding( "UTF-8" );
        assertEquals( "{\"state\":\"RUNNING\",\"startedAt\":null}", out.toString() );
    }

    @Test
    void sendErrorToleratesANullMessage() throws Exception {
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter out = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( out, true ) );

        RestJson.sendError( resp, 500, null );

        verify( resp ).setStatus( 500 );
        assertEquals( true, com.google.gson.JsonParser.parseString( out.toString() ).getAsJsonObject().get( "error" ).getAsBoolean() );
    }
}
