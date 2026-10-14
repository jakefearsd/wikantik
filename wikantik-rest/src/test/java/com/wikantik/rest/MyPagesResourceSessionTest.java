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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wikantik.AuthoredPages;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code /api/me/pages} against a real session: it matches the stored author exactly against the caller's
 * <em>login</em>. Full names (legacy authorship) and other spellings of the login do not count.
 */
class MyPagesResourceSessionTest {

    private static TestEngine engine;
    private static MyPagesResource servlet;
    private final Gson gson = new Gson();

    @BeforeAll
    static void startEngine() throws Exception {
        engine = TestEngine.build();
        servlet = RestTestSupport.initServlet( MyPagesResource::new, engine );
        // Stored the way legacy writers (a full name) and other accounts were recorded.
        AuthoredPages.save( engine, "MyPagesLegacyName", "Saved under a full name.", "Janne Jalkanen" );
        AuthoredPages.save( engine, "MyPagesOtherCase", "Saved under another spelling.", "JANNE" );
        AuthoredPages.save( engine, "MyPagesSomeoneElse", "Saved by another account.", "Fred" );
        // Last: leaves the shared mock HTTP session logged in as janne for the request below.
        engine.saveTextAsJanne( "MyPagesOwn", "Saved under janne's login." );
    }

    @AfterAll
    static void stopEngine() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    @Test
    void listsOnlyPagesWhoseStoredAuthorIsTheCallersLogin() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/me/pages" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        final List< String > slugs = new ArrayList<>();
        gson.fromJson( sw.toString(), JsonObject.class ).getAsJsonArray( "pages" )
            .forEach( p -> slugs.add( p.getAsJsonObject().get( "slug" ).getAsString() ) );
        assertEquals( List.of( "MyPagesOwn" ), slugs,
            "a full name or another spelling of the login must not claim authorship" );
    }
}
