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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.connectors.CredentialStore;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ConnectorCredentialsResource}.
 *
 * <p>The {@link CredentialStore} is injected via a subclass stub — no engine boot needed.
 */
class ConnectorCredentialsResourceTest {

    private CredentialStore              store;
    private ConnectorCredentialsResource servlet;
    private HttpServletRequest           req;
    private HttpServletResponse          resp;
    private StringWriter                 body;

    /** Test servlet subclass that injects the mocked store (or none, to simulate "disabled"). */
    private final class Stub extends ConnectorCredentialsResource {
        @Override
        protected CredentialStore resolveStore() {
            return store;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        store = mock( CredentialStore.class );
        when( store.enabled() ).thenReturn( true );
        servlet = new Stub();
        req  = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );
    }

    // -----------------------------------------------------------------------
    // GET /admin/connector-credentials/{id}  (list names)
    // -----------------------------------------------------------------------

    @Test
    void list_returnsNamesOnly_noSecretValues() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gh1" );
        when( store.list( "gh1" ) ).thenReturn( List.of( "token" ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonArray json = JsonParser.parseString( body.toString() ).getAsJsonArray();
        assertEquals( 1, json.size() );
        assertEquals( "token", json.get( 0 ).getAsString() );
        assertFalse( body.toString().contains( "ghp_x" ) );
    }

    @Test
    void list_missingId_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( null );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    @Test
    void list_storeDisabled_returns503() throws Exception {
        when( store.enabled() ).thenReturn( false );
        when( req.getPathInfo() ).thenReturn( "/gh1" );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        verify( store, never() ).list( anyString() );
    }

    @Test
    void list_storeAbsent_returns503() throws Exception {
        store = null;
        when( req.getPathInfo() ).thenReturn( "/gh1" );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    @Test
    void get_malformedPath_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gh1/token/extra" );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    // -----------------------------------------------------------------------
    // POST /admin/connector-credentials/{id}/{name}  (store secret)
    // -----------------------------------------------------------------------

    @Test
    void post_storesSecret_returns201WithoutSecretInBody() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gh1/token" );
        stubReader( "ghp_x" );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_CREATED );
        verify( store ).put( "gh1", "token", "ghp_x" );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertEquals( "gh1", json.get( "connectorId" ).getAsString() );
        assertEquals( "token", json.get( "name" ).getAsString() );
        assertFalse( body.toString().contains( "ghp_x" ) );
    }

    @Test
    void post_blankBody_returns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gh1/token" );
        stubReader( "   \n" );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        verify( store, never() ).put( anyString(), anyString(), anyString() );
    }

    @Test
    void post_stripsSurroundingWhitespace_storesTrimmedSecret() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gh1/token" );
        stubReader( "ghp_x\n" );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_CREATED );
        verify( store ).put( eq( "gh1" ), eq( "token" ), eq( "ghp_x" ) );
    }

    @Test
    void post_oversizedBody_returns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gh1/token" );
        stubReader( "x".repeat( 8193 ) );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        verify( store, never() ).put( anyString(), anyString(), anyString() );
    }

    @Test
    void post_storeDisabled_returns503() throws Exception {
        when( store.enabled() ).thenReturn( false );
        when( req.getPathInfo() ).thenReturn( "/gh1/token" );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        verify( store, never() ).put( anyString(), anyString(), anyString() );
    }

    @Test
    void post_malformedPath_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gh1" );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    // -----------------------------------------------------------------------
    // DELETE /admin/connector-credentials/{id}/{name}
    // -----------------------------------------------------------------------

    @Test
    void delete_removesSecret_returns204() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gh1/token" );

        servlet.doDelete( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NO_CONTENT );
        verify( store ).delete( "gh1", "token" );
    }

    @Test
    void delete_storeDisabled_returns503() throws Exception {
        when( store.enabled() ).thenReturn( false );
        when( req.getPathInfo() ).thenReturn( "/gh1/token" );

        servlet.doDelete( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        verify( store, never() ).delete( anyString(), anyString() );
    }

    @Test
    void delete_malformedPath_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gh1" );

        servlet.doDelete( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private void stubReader( final String content ) throws Exception {
        doReturn( new BufferedReader( new StringReader( content ) ) ).when( req ).getReader();
    }
}
