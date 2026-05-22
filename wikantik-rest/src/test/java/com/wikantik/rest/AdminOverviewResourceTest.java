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
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class AdminOverviewResourceTest {
    @Test
    void writesEnvelopeWithCardsAndDegradedList() throws Exception {
        final var resp = Mockito.mock( HttpServletResponse.class );
        final StringWriter body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );

        // The resource must tolerate a missing registry / services (test env):
        // every collector degrades gracefully rather than throwing out of doGet.
        new AdminOverviewResource().doGetForTesting( Mockito.mock( jakarta.servlet.http.HttpServletRequest.class ), resp );

        final JsonObject env = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertTrue( env.has( "data" ) );
        final JsonObject data = env.getAsJsonObject( "data" );
        assertTrue( data.has( "degraded" ), "envelope must always carry a degraded list" );
    }
}
