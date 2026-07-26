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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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

import static org.junit.jupiter.api.Assertions.*;

class HistoryResourceTest {

    private static TestEngine engine;
    private static HistoryResource servlet;
    private final Gson gson = new Gson();

    // Per-class engine (see RestTestSupport javadoc): "RestHistoryPage" is seeded
    // once with identical content — no test mutates, renames, or deletes it, and no
    // test asserts an absolute version count (all checks are ">= 1").
    @BeforeAll
    static void startEngine() throws Exception {
        engine = TestEngine.build();
        engine.saveText( "RestHistoryPage", "Version 1 content." );
        servlet = RestTestSupport.initServlet( HistoryResource::new, engine );
    }

    @AfterAll
    static void stopEngine() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    @Test
    void testGetVersionHistory() throws Exception {
        final String json = doGet( "RestHistoryPage" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "RestHistoryPage", obj.get( "name" ).getAsString() );
        assertTrue( obj.has( "versions" ) );
        assertTrue( obj.get( "versions" ).isJsonArray() );

        final JsonArray versions = obj.getAsJsonArray( "versions" );
        assertTrue( versions.size() >= 1, "Should have at least one version" );
    }

    @Test
    void testGetVersionHistoryFields() throws Exception {
        final String json = doGet( "RestHistoryPage" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        final JsonArray versions = obj.getAsJsonArray( "versions" );
        final JsonObject firstVersion = versions.get( 0 ).getAsJsonObject();

        assertTrue( firstVersion.has( "version" ) );
        assertTrue( firstVersion.has( "author" ) );
        assertTrue( firstVersion.has( "lastModified" ) );
        assertTrue( firstVersion.get( "version" ).getAsInt() >= 1 );
    }

    @Test
    void testGetHistoryPageNotFound() throws Exception {
        final String json = doGet( "NonExistentPage12345" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testGetHistoryMissingName() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/history" );
        Mockito.doReturn( null ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testGetHistoryEmptyPathInfo() throws Exception {
        // PathInfo of "/" should return null from extractPathParam
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/history/" );
        Mockito.doReturn( "/" ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testGetHistoryVersionEntryStructure() throws Exception {
        final String json = doGet( "RestHistoryPage" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        final JsonArray versions = obj.getAsJsonArray( "versions" );
        assertTrue( versions.size() >= 1, "Should have at least one version" );

        final JsonObject firstVersion = versions.get( 0 ).getAsJsonObject();
        // The version entry should have these fields (changeNote may be null)
        assertTrue( firstVersion.has( "version" ) );
        assertTrue( firstVersion.has( "author" ) );
        assertTrue( firstVersion.has( "lastModified" ) );
        assertTrue( firstVersion.get( "version" ).getAsInt() >= 1 );
    }

    // ----- Helper methods -----

    private String doGet( final String pageName ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/history/" + pageName );
        Mockito.doReturn( "/" + pageName ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        return sw.toString();
    }

}
