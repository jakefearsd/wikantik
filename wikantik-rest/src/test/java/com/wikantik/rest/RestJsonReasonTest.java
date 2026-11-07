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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link RestJson#sendError(HttpServletResponse, int, String, String)}: the error envelope plus a reason. */
class RestJsonReasonTest {

    private static JsonObject send( final String reason ) throws Exception {
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter out = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( out, true ) );
        RestJson.sendError( resp, 423, "busy", reason );
        verify( resp ).setStatus( 423 );
        return JsonParser.parseString( out.toString() ).getAsJsonObject();
    }

    @Test
    void carriesTheReasonBesideTheEnvelope() throws Exception {
        final JsonObject o = send( "publish-in-progress" );
        assertEquals( "publish-in-progress", o.get( "reason" ).getAsString() );
        assertEquals( 423, o.get( "status" ).getAsInt() );
        assertEquals( "busy", o.get( "message" ).getAsString() );
        assertEquals( true, o.get( "error" ).getAsBoolean() );
    }

    @Test
    void aNullReasonIsUnknownNeverAnException() throws Exception {
        assertEquals( "unknown", send( null ).get( "reason" ).getAsString() );
    }
}
